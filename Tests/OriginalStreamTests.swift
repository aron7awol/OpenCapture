import Foundation
import XCTest
@testable import OpenCaptureCore

@MainActor final class OriginalStreamTests: XCTestCase {
    private func root() throws -> URL {
        let value=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:value,withIntermediateDirectories:true)
        addTeardownBlock { try? FileManager.default.removeItem(at:value) }
        return value
    }
    private func camera() -> CameraCapabilities {
        CameraCapabilities(cameraID:"main",name:"Fixture",formats:[.bayerRAW],minISO:25,maxISO:1000,
            minExposureSeconds:0.001,maxExposureSeconds:1,manualExposure:true,manualFocus:true,
            manualWhiteBalance:true,maxWhiteBalanceGain:8,maxPhotoWidth:8,maxPhotoHeight:6,
            osVersion:"test",appVersion:"test")
    }
    private func frame(_ directory: URL,_ index: Int) throws -> FrameRecord {
        func file(_ ext: String,_ data: Data,_ media: String) throws -> CapturedFile {
            let name=String(format:"frame-%04d.%@",index,ext)
            try data.write(to:directory.appendingPathComponent(name))
            return CapturedFile(name:name,bytes:data.count,sha256:String(repeating:"a",count:64),mediaType:media)
        }
        return FrameRecord(index:index,file:try file("dng",Data("original".utf8),"image/dng"),
            metadataFile:try file("json",Data("{}".utf8),"application/json"),previewFile:nil,width:8,height:6,
            timestampSeconds:Double(index),actualExposureSeconds:0.1,actualISO:100,
            deviceExposureSeconds:0.1,deviceISO:100,lensPosition:0.5,
            whiteBalance:WhiteBalance(red:1,green:1,blue:1),isBayerRAW:true)
    }
    private func verdict(_ index: Int,_ accepted: Bool) -> StreamVerdict {
        StreamVerdict(index:index,sha256:String(repeating:"a",count:64),accepted:accepted,reason:accepted ? "usable":"empty_signal")
    }
    private func wait(_ condition: () throws -> Bool) async throws {
        for _ in 0..<200 {
            if try condition() { return }
            try await Task.sleep(nanoseconds:5_000_000)
        }
        XCTFail("Stream condition timed out")
    }
    private func waitForFrame(_ session: CameraServerSession,_ id: String) async throws {
        try await wait {
            let state=try session.state(id)
            if state.status == "failed" || state.status == "cancelled" {
                throw CameraServerError(500,"Capture stopped before publication: \(state.message)")
            }
            return state.streaming?.frames.count == 1
        }
    }
    func testOneAheadBackpressureAndImmutableFrozenPrefix() async throws {
        let directory=try root();var request=CaptureRequest();request.frameCount=2;request.streaming=StreamingOptions(mode:"originals-v1")
        let stream=OriginalStream(request:request,directory:directory)
        stream.begin(camera:camera(),started:"start",processing:"fixture")
        XCTAssertTrue(try stream.tryNext());XCTAssertTrue(try stream.tryNext())
        try stream.publish(frame(directory,1));try stream.publish(frame(directory,2))
        var proceeded=false
        let waiting=Task { @MainActor in proceeded=try await stream.next() }
        try await Task.sleep(nanoseconds:20_000_000);XCTAssertFalse(proceeded)
        try stream.verdict(verdict(1,true));try await waiting.value;XCTAssertTrue(proceeded)
        try stream.publish(frame(directory,3));try stream.verdict(verdict(2,false))
        XCTAssertTrue(try stream.tryNext());try stream.publish(frame(directory,4))
        try stream.verdict(verdict(3,true));let frozen=try await stream.finish()
        try stream.verdict(verdict(3,true));XCTAssertEqual(frozen.data,stream.result?.data)
        let manifest=try JSONDecoder().decode(CaptureManifest.self,from:frozen.data)
        XCTAssertEqual(manifest.streaming?.acceptedIndices,[1,3]);XCTAssertEqual(manifest.frames.count,3)
        XCTAssertNil(frozen.files["frame-0004.dng"])
        XCTAssertThrowsError(try stream.verdict(verdict(3,false)))
        XCTAssertThrowsError(try stream.verdict(verdict(4,true)))
        let next=try await stream.next();XCTAssertFalse(next)
    }
    func testExactTargetsAndReplacementBudget() async throws {
        for target in [1,6,18] {
            for accept in [true,false] {
                let directory=try root();var request=CaptureRequest();request.frameCount=target
                request.streaming=StreamingOptions(mode:"originals-v1")
                let stream=OriginalStream(request:request,directory:directory)
                stream.begin(camera:camera(),started:"start",processing:"fixture")
                let attempts=accept ? target : 2*target
                for index in 1...attempts {
                    let next=try await stream.next();XCTAssertTrue(next)
                    try stream.publish(frame(directory,index));try stream.verdict(verdict(index,accept))
                }
                let next=try await stream.next();XCTAssertFalse(next)
                let result=try await stream.finish()
                let manifest=try JSONDecoder().decode(CaptureManifest.self,from:result.data)
                XCTAssertEqual(manifest.frames.count,attempts)
                XCTAssertEqual(manifest.streaming?.acceptedIndices.count,accept ? target : 0)
                XCTAssertEqual(manifest.streaming?.status,accept ? "complete":"insufficient")
            }
        }
    }
    func testPublicationBudgetAndDiscardedLateExposure() async throws {
        let directory=try root();var request=CaptureRequest();request.frameCount=1
        request.streaming=StreamingOptions(mode:"originals-v1")
        let stream=OriginalStream(request:request,directory:directory,maximumBytes:15)
        stream.begin(camera:camera(),started:"start",processing:"fixture")
        let first=try frame(directory,1);XCTAssertTrue(try stream.tryNext());try stream.publish(first)
        let second=try frame(directory,2);XCTAssertTrue(try stream.tryNext())
        XCTAssertThrowsError(try stream.publish(second))
        XCTAssertNil(stream.file(second.file.name))
        XCTAssertThrowsError(try stream.verdict(verdict(2,true)))
        XCTAssertThrowsError(try stream.verdict(StreamVerdict(index:1,sha256:"wrong",accepted:true,reason:"usable")))
        try stream.verdict(verdict(1,true));let frozen=try await stream.finish()
        try stream.publish(second)
        XCTAssertEqual(frozen.data,stream.result?.data)
        XCTAssertFalse(FileManager.default.fileExists(atPath:directory.appendingPathComponent(second.file.name).path))
        XCTAssertTrue(FileManager.default.fileExists(atPath:directory.appendingPathComponent(first.file.name).path))
    }
    func testRequestRejectsMixedModesAndUnknownStreamingFields() throws {
        var request=CaptureRequest();request.streaming=StreamingOptions(mode:"originals-v1")
        try request.validate()
        request.operation = .applyControls
        XCTAssertThrowsError(try request.validate())
        request.operation = .capture
        request.averaging = AveragingOptions(mode:BayerAverage.mode,emptyFramePolicy:"dark",keepOriginals:true)
        XCTAssertThrowsError(try request.validate())
        request.averaging = nil
        var fields=try XCTUnwrap(JSONSerialization.jsonObject(with:JSONEncoder().encode(request)) as? [String:Any])
        fields["streaming"]=["mode":"originals-v1","unexpected":true]
        XCTAssertThrowsError(try CaptureRequest.decode(JSONSerialization.data(withJSONObject:fields)))
    }
    func testReservationsIncludeUnpublishedExposuresAndFreezeStopsAdmission() async throws {
        let directory=try root();var request=CaptureRequest();request.frameCount=2
        request.streaming=StreamingOptions(mode:"originals-v1")
        let stream=OriginalStream(request:request,directory:directory)
        stream.begin(camera:camera(),started:"start",processing:"fixture")
        XCTAssertTrue(try stream.tryNext());XCTAssertTrue(try stream.tryNext())
        XCTAssertFalse(try stream.tryNext())
        try stream.publish(frame(directory,1))
        XCTAssertFalse(try stream.tryNext())
        try stream.verdict(verdict(1,false));XCTAssertTrue(try stream.tryNext())
        XCTAssertFalse(try stream.tryNext())
        try stream.publish(frame(directory,2));try stream.verdict(verdict(2,true))
        XCTAssertTrue(try stream.tryNext())
        try stream.publish(frame(directory,3));try stream.verdict(verdict(3,true))
        let frozen=try await stream.finish()
        XCTAssertFalse(try stream.tryNext())
        try stream.publish(frame(directory,4))
        XCTAssertEqual(frozen.data,stream.result?.data)
        XCTAssertFalse(FileManager.default.fileExists(atPath:directory.appendingPathComponent("frame-0004.dng").path))
    }
    func testUnreservedPublicationFails() throws {
        let directory=try root();var request=CaptureRequest();request.frameCount=1
        let stream=OriginalStream(request:request,directory:directory)
        stream.begin(camera:camera(),started:"start",processing:"fixture")
        XCTAssertThrowsError(try stream.publish(frame(directory,1)))
    }
    func testFrozenAckDoesNotWaitForExtraExposureAndNextJobCannotOverlap() async throws {
        let base=try root();let directory=base.appendingPathComponent("Transfers")
        var request=CaptureRequest();request.frameCount=1;request.streaming=StreamingOptions(mode:"originals-v1")
        var release=false
        let session=try CameraServerSession(root:directory,hash:{_ in "hash"},streamCapture:{ request,stream in
            let folder=try CaptureArchiveStore(root:directory).create(request.id)
            stream.begin(camera:self.camera(),started:"start",processing:"fixture")
            XCTAssertTrue(try stream.tryNext());XCTAssertTrue(try stream.tryNext())
            try stream.publish(self.frame(folder,1))
            while !release { try await Task.sleep(nanoseconds:5_000_000) }
            XCTAssertTrue(FileManager.default.fileExists(atPath:folder.path))
            return try await stream.finish()
        },capture:{_ in throw CaptureError.invalid("Unexpected batch")})
        try await session.connect(id:UUID().uuidString.lowercased(),client:"PC")
        _=try session.submit(request)
        try await waitForFrame(session,request.id)
        _=try session.verdict(request.id,verdict(1,true))
        XCTAssertEqual(try session.state(request.id).status,"complete")
        let replay=try session.submit(request)
        XCTAssertEqual(replay.streaming?.verdictCount,1);XCTAssertFalse(replay.cameraReady)
        let ack=try session.acknowledge(request.id,hash:"hash")
        XCTAssertFalse(ack.cameraReady)
        XCTAssertFalse(session.cameraReady)
        XCTAssertTrue(FileManager.default.fileExists(atPath:directory.appendingPathComponent(request.id).path))
        XCTAssertThrowsError(try session.submit(CaptureRequest()))
        release=true;try await wait { session.cameraReady }
        XCTAssertTrue(try session.submit(request).cameraReady)
        XCTAssertFalse(FileManager.default.fileExists(atPath:directory.appendingPathComponent(request.id).path))
        try await session.end()
    }
    func testFailureOfDiscardedExposurePreservesCompletedResult() async throws {
        let directory=try root().appendingPathComponent("Transfers")
        var request=CaptureRequest();request.frameCount=1;request.streaming=StreamingOptions(mode:"originals-v1")
        var release=false
        let session=try CameraServerSession(root:directory,hash:{_ in "hash"},streamCapture:{ request,stream in
            let folder=try CaptureArchiveStore(root:directory).create(request.id)
            stream.begin(camera:self.camera(),started:"start",processing:"fixture")
            XCTAssertTrue(try stream.tryNext());XCTAssertTrue(try stream.tryNext())
            try stream.publish(self.frame(folder,1))
            while !release { try await Task.sleep(nanoseconds:5_000_000) }
            throw CaptureError.invalid("Discarded lookahead failed")
        },capture:{_ in throw CaptureError.invalid("Unexpected batch")})
        try await session.connect(id:UUID().uuidString.lowercased(),client:"PC")
        _=try session.submit(request)
        try await waitForFrame(session,request.id)
        _=try session.verdict(request.id,verdict(1,true))
        let frozen=try session.result(request.id).data
        release=true;try await wait { session.cameraReady }
        XCTAssertEqual(try session.state(request.id).status,"complete")
        XCTAssertEqual(try session.result(request.id).data,frozen)
        _=try session.acknowledge(request.id,hash:"hash")
        try await session.end()
    }
    func testCancellationOfFrozenResultDrainsWriterBeforeDeleting() async throws {
        let directory=try root().appendingPathComponent("Transfers")
        var request=CaptureRequest();request.frameCount=1;request.streaming=StreamingOptions(mode:"originals-v1")
        var drained=false
        let session=try CameraServerSession(root:directory,hash:{_ in "hash"},streamCapture:{ request,stream in
            let folder=try CaptureArchiveStore(root:directory).create(request.id)
            stream.begin(camera:self.camera(),started:"start",processing:"fixture")
            XCTAssertTrue(try stream.tryNext());XCTAssertTrue(try stream.tryNext())
            try stream.publish(self.frame(folder,1))
            do { try await Task.sleep(nanoseconds:60_000_000_000) }
            catch {
                XCTAssertTrue(FileManager.default.fileExists(atPath:folder.path));drained=true;throw error
            }
            return try await stream.finish()
        },capture:{_ in throw CaptureError.invalid("Unexpected batch")})
        try await session.connect(id:UUID().uuidString.lowercased(),client:"PC")
        _=try session.submit(request)
        try await waitForFrame(session,request.id)
        _=try session.verdict(request.id,verdict(1,true))
        _=try await session.cancel(request.id)
        XCTAssertTrue(drained);XCTAssertTrue(session.cameraReady)
        XCTAssertFalse(FileManager.default.fileExists(atPath:directory.appendingPathComponent(request.id).path))
        try await session.end()
    }
}
