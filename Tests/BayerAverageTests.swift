import Foundation
import XCTest
@testable import OpenCaptureCore

final class BayerAverageTests: XCTestCase {
    private func root() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:url,withIntermediateDirectories:true)
        addTeardownBlock { try? FileManager.default.removeItem(at:url) }
        return url
    }
    private var layout: BayerLayout {
        BayerLayout(rawWidth:8,rawHeight:8,activeArea:[0,0,8,8],blackLevels:[10,10,10,10],whiteLevel:1010,cfa:[0,1,1,2],radialGain:[])
    }
    private func stage(_ directory: URL, index: Int, values: [UInt16], layout: BayerLayout? = nil) throws -> BayerEvidence {
        let layout = layout ?? self.layout
        let file = directory.appendingPathComponent("test-\(index).raw16")
        var bytes = Data()
        for y in 0..<layout.height*2 { for x in 0..<layout.width*2 {
            var v = values[(y%2)*2+x%2].littleEndian
            withUnsafeBytes(of:&v) { bytes.append(contentsOf:$0) }
        } }
        try bytes.write(to:file)
        return try BayerAverage.inspect(file:file,layout:layout,integrity:"consistent_native_buffer_and_file")
    }
    private func floats(_ url: URL) throws -> [Float] {
        let bytes = try Data(contentsOf:url)
        return stride(from:0,to:bytes.count,by:4).map { i in
            var bits: UInt32 = 0
            for b in 0..<4 { bits |= UInt32(bytes[i+b]) << (b*8) }
            return Float(bitPattern:bits)
        }
    }
    func testProtocolIsExplicitAndBayerOnly() throws {
        var request = CaptureRequest()
        request.averaging = AveragingOptions(mode:BayerAverage.mode,emptyFramePolicy:"relative",keepOriginals:false)
        XCTAssertEqual(try CaptureRequest.decode(JSONEncoder().encode(request)),request)
        request.format = .jpeg; XCTAssertThrowsError(try request.validate())
        request.format = .appleProRAW; request.quality = .quality; XCTAssertThrowsError(try request.validate())
        request.format = .bayerRAW; request.quality = .speed
        request.operation = .applyControls; request.frameCount = 1; XCTAssertThrowsError(try request.validate())
        request.operation = nil; request.averaging?.mode = "bayer-mean-v1"; XCTAssertThrowsError(try request.validate())
        request.averaging?.mode = BayerAverage.mode; request.averaging?.emptyFramePolicy = "trim"; XCTAssertThrowsError(try request.validate())
    }
    func testFractionalMeanSampleVarianceSignedNoiseAndClipping() throws {
        let directory = try root()
        let a = try stage(directory,index:1,values:[9,11,1010,21])
        let b = try stage(directory,index:2,values:[12,14,1011,24])
        let receipts = try BayerAverage.write([a,b],directory:directory)
        let actual = try floats(directory.appendingPathComponent(receipts["moments"]!.name))
        var luminances: [Float] = []
        for source: [UInt16] in [[9,11,1010,21],[12,14,1011,24]] {
            var value: Float = 0
            for native in source { value += ((Float(native)-10)/1000)*0.25 }
            luminances.append(value)
        }
        for site in 0..<4 {
            let first: [Float] = [-0.001,0.001,1,0.011], second: [Float] = [0.002,0.004,1.001,0.014]
            XCTAssertEqual(actual[site].bitPattern,Float((Double(first[site])+Double(second[site]))/2).bitPattern)
        }
        XCTAssertEqual(actual[4],Float((Double(luminances[0])+Double(luminances[1]))/2))
        XCTAssertEqual(actual[5],Float(pow(Double(luminances[1])-Double(luminances[0]),2)/2))
        XCTAssertEqual(try Data(contentsOf:directory.appendingPathComponent("average-clips.u8")),Data(repeating:2,count:16))
        XCTAssertEqual(actual.count,16*6)
        _ = try BayerAverage.write([a],directory:directory)
        XCTAssertEqual(try floats(directory.appendingPathComponent("average.f32"))[0],-0.001)
        XCTAssertEqual(try floats(directory.appendingPathComponent("average.f32"))[5],0)
    }
    func testRadialGainBeforeBinAndNativeClipping() throws {
        let directory = try root()
        var model = layout
        model.radialGain = [DNGRadialGain(flags:0,coefficients:[1,0,0,0,0],center:[0.5,0.5])]
        let evidence = try stage(directory,index:1,values:[600,600,600,600],layout:model)
        _ = try BayerAverage.write([evidence],directory:directory)
        let data = try floats(directory.appendingPathComponent("average.f32"))
        XCTAssertEqual(data[0],Float(0.59)*2)
        XCTAssertGreaterThan(data[4],1)
        XCTAssertEqual(evidence.clippedFraction,0)
        XCTAssertEqual(try Data(contentsOf:directory.appendingPathComponent("average-clips.u8")),Data(repeating:0,count:16))
        model.radialGain[0].coefficients[0] = 100
        XCTAssertThrowsError(try model.gain(x:0,y:0))
    }
    func testReplacementBudgetAndDarkPolicy() throws {
        let directory = try root()
        let blank = try stage(directory,index:1,values:[10,10,10,10])
        let lit = try stage(directory,index:2,values:[100,100,100,100])
        for n in [1,2,10,32] {
            var selection = try BayerSelection(target:n,policy:"illuminated")
            for _ in 0..<n { selection.attempts.append(blank) }
            XCTAssertTrue(selection.needsMore)
            for _ in 0..<n { selection.attempts.append(lit) }
            XCTAssertFalse(selection.needsMore); XCTAssertEqual(selection.accepted.count,n)
            XCTAssertEqual(selection.maxAttempts,2*n)
        }
        var dark = try BayerSelection(target:1,policy:"dark"); dark.attempts.append(blank)
        XCTAssertFalse(dark.needsMore); XCTAssertEqual(dark.accepted,[0])
        var relative = try BayerSelection(target:2,policy:"relative")
        relative.attempts = [blank,lit]
        // Constant lit data has no spatial structure: a real flat/dark field is not trimmed solely by brightness.
        XCTAssertEqual(relative.accepted,[0,1])
        var bad = lit; bad.integrity = "sample_mismatch_observed"
        dark.attempts = [bad,blank]; XCTAssertEqual(dark.accepted,[1])
    }
    func testRejectsChangedLayoutTruncatedRasterAndCancellation() async throws {
        let directory = try root()
        let a = try stage(directory,index:1,values:[100,100,100,100])
        var b = a; b.layout.whiteLevel = 2000
        XCTAssertThrowsError(try BayerAverage.write([a,b],directory:directory))
        try Data([0]).write(to:a.file)
        XCTAssertThrowsError(try BayerAverage.inspect(file:a.file,layout:a.layout,integrity:a.integrity))
        let valid = try stage(directory,index:1,values:[100,100,100,100])
        let task = Task {
            try await Task.sleep(nanoseconds:1_000_000_000)
            return try BayerAverage.write([valid],directory:directory)
        }
        task.cancel()
        do { _ = try await task.value; XCTFail("Cancelled work succeeded") } catch is CancellationError {} catch { XCTFail("Unexpected \(error)") }
    }
    private func records(_ n: Int, directory: URL) throws -> [FrameRecord] {
        try (1...n).map { index in
            let name = String(format:"frame-%04d",index)
            let metadata = directory.appendingPathComponent(name+".json")
            try Data("{}".utf8).write(to:metadata)
            return FrameRecord(index:index,file:CapturedFile(name:name+".dng",bytes:8,sha256:String(repeating:"a",count:64),mediaType:"image/dng"),
                metadataFile:try BayerAverage.receipt(metadata,media:"application/json"),previewFile:nil,width:8,height:8,timestampSeconds:Double(index),
                actualExposureSeconds:0.01,actualISO:100,deviceExposureSeconds:0.01,deviceISO:100,lensPosition:0.5,
                whiteBalance:WhiteBalance(red:1,green:1,blue:1),isBayerRAW:true)
        }
    }
    func testFinalManifestAndUnderfilledEvidenceOnly() throws {
        let directory = try root()
        let blank = try stage(directory,index:1,values:[10,10,10,10])
        let lit = try stage(directory,index:2,values:[100,100,100,100])
        let options = AveragingOptions(mode:BayerAverage.mode,emptyFramePolicy:"illuminated",keepOriginals:false)
        var selection = try BayerSelection(target:2,policy:"illuminated")
        selection.attempts = [blank,lit,lit]
        var frames = try records(3,directory:directory)
        let result = try BayerAverage.finalize(selection,options:options,frames:&frames,directory:directory)
        XCTAssertEqual(result.status,"complete"); XCTAssertEqual(result.acceptedIndices,[2,3])
        XCTAssertEqual(result.referenceIndex,2); XCTAssertEqual(result.retainedOriginalIndices,[1,2])
        XCTAssertEqual(Set(result.files.keys),["moments","clipping"])
        let saved = try JSONSerialization.jsonObject(with:Data(contentsOf:directory.appendingPathComponent("frame-0003.json"))) as! [String:Any]
        XCTAssertEqual((saved["averagingSelection"] as? [String:Any])?["accepted"] as? Bool,true)
        selection.attempts = [blank,blank,lit,blank]; frames = try records(4,directory:directory)
        let failed = try BayerAverage.finalize(selection,options:options,frames:&frames,directory:directory)
        XCTAssertEqual(failed.status,"insufficient"); XCTAssertTrue(failed.files.isEmpty)
        XCTAssertEqual(failed.retainedOriginalIndices,[1,2,3,4])
    }
    // Synthetic metadata only; no private device images enter this public test suite.
    private func dng(big: Bool = false, opcode: Int = 3, cycle: Bool = false) -> Data {
        func integer(_ value: UInt64, _ width: Int, big: Bool) -> Data {
            Data((0..<width).map { UInt8(truncatingIfNeeded:value >> ((big ? width-1-$0 : $0)*8)) })
        }
        var ops = integer(1,4,big:true)
        for value in [opcode,0x01030000,0,56] { ops.append(integer(UInt64(value),4,big:true)) }
        for value in [0.25,0,0,0,0,0.5,0.5] { ops.append(integer(value.bitPattern,8,big:true)) }
        let fields: [(Int,Int,Int,Data)] = [
            (256,4,1,integer(8,4,big:big)), (257,4,1,integer(8,4,big:big)),
            (258,3,1,integer(14,2,big:big)), (259,3,1,integer(7,2,big:big)),
            (262,3,1,integer(32803,2,big:big)), (33421,3,2,integer(2,2,big:big)+integer(2,2,big:big)),
            (33422,1,4,Data([0,1,1,2])), (50706,1,4,Data([1,4,0,0])),
            (50714,3,1,integer(10,2,big:big)), (50717,4,1,integer(1010,4,big:big)), (51022,7,ops.count,ops)]
        var result = Data(big ? [77,77] : [73,73])
        result.append(integer(42,2,big:big)); result.append(integer(8,4,big:big)); result.append(integer(UInt64(fields.count),2,big:big))
        var extra = Data()
        for (tag,type,count,bytes) in fields {
            result.append(integer(UInt64(tag),2,big:big)); result.append(integer(UInt64(type),2,big:big)); result.append(integer(UInt64(count),4,big:big))
            if bytes.count <= 4 { result.append(bytes); result.append(Data(repeating:0,count:4-bytes.count)) }
            else { result.append(integer(UInt64(14+fields.count*12+extra.count),4,big:big)); extra.append(bytes) }
        }
        result.append(integer(cycle ? 8 : 0,4,big:big)); result.append(extra)
        return result
    }
    func testBoundedCompressedDNGMetadataAndRadialGain() throws {
        for big in [false,true] {
            let parsed = try BayerDNG.layout(dng(big:big))
            XCTAssertEqual(parsed.cfa,[0,1,1,2]); XCTAssertEqual(parsed.blackLevels,[10,10,10,10])
            XCTAssertEqual(parsed.radialGain.count,1); XCTAssertEqual(try parsed.gain(x:0,y:0),1.25)
            XCTAssertEqual(parsed.activeArea,[0,0,8,8])
        }
        XCTAssertThrowsError(try BayerDNG.layout(dng(opcode:9)))
        XCTAssertThrowsError(try BayerDNG.layout(dng(cycle:true)))
        XCTAssertThrowsError(try BayerDNG.layout(dng().dropLast()))
        XCTAssertThrowsError(try BayerDNG.layout(Data([0,1,2])))
    }
}
