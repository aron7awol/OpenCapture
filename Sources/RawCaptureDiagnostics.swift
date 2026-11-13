import AVFoundation
import CryptoKit
import Foundation

struct NativeRawSnapshot {
    let width: Int
    let height: Int
    let rowBytes: Int
    let format: OSType
    let cfa: [Int]
    let points: [[Int]]
    let hash: String
    let pixels: Data?
    var fields: [String:Any] { ["width":width,"height":height,"rowBytes":rowBytes,"pixelFormat":format,
        "cfa":cfa,"points":points,"sha256":hash,"sampleEncoding":"uint16-little-endian"] }
}

enum RawCaptureDiagnostics {
    static var now: UInt64 { DispatchTime.now().uptimeNanoseconds }
    static let formats: [OSType:[Int]] = [kCVPixelFormatType_14Bayer_RGGB:[0,1,1,2], kCVPixelFormatType_14Bayer_BGGR:[2,1,1,0],
        kCVPixelFormatType_14Bayer_GRBG:[1,0,2,1], kCVPixelFormatType_14Bayer_GBRG:[1,2,0,1]]

    static func snapshot(_ buffer: CVPixelBuffer, retain: Bool) throws -> NativeRawSnapshot {
        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer), rowBytes = CVPixelBufferGetBytesPerRow(buffer)
        let format = CVPixelBufferGetPixelFormatType(buffer)
        guard let cfa = formats[format], !CVPixelBufferIsPlanar(buffer), width >= 8, height >= 8,
              width*height <= 13_000_000, rowBytes >= width*2, rowBytes*height <= CVPixelBufferGetDataSize(buffer),
              CVPixelBufferLockBaseAddress(buffer,.readOnly) == kCVReturnSuccess else {
            throw CaptureError.unavailable("Unsupported native Bayer buffer layout")
        }
        defer { CVPixelBufferUnlockBaseAddress(buffer,.readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { throw CaptureError.unavailable("RAW buffer has no address") }
        let bytes = base.assumingMemoryBound(to:UInt8.self)
        var points: [[Int]] = [], samples = Data()
        for gy in 0..<12 { for gx in 0..<16 {
            let x = (gx*(width/2-1)/15)*2, y = (gy*(height/2-1)/11)*2
            for site in 0..<4 {
                let xx = x+site%2, yy = y+site/2, offset = yy*rowBytes+xx*2
                let value = Int(bytes[offset]) | Int(bytes[offset+1]) << 8
                points.append([xx,yy,value]); samples.append(bytes[offset]); samples.append(bytes[offset+1])
            }
        } }
        var pixels: Data?
        if retain {
            var data = Data(capacity:width*height*2)
            for y in 0..<height { data.append(bytes+y*rowBytes,count:width*2) }
            pixels = data
        }
        return NativeRawSnapshot(width:width,height:height,rowBytes:rowBytes,format:format,cfa:cfa,points:points,
            hash:SHA256.hash(data:samples).map {String(format:"%02x",$0)}.joined(),pixels:pixels)
    }

    static func finish(before: NativeRawSnapshot, after: NativeRawSnapshot, data: Data, file: URL,
                       stage: URL?, start: UInt64, encoded: UInt64) throws -> [String:Any] {
        let fileReceipt = try BayerAverage.receipt(file,media:"image/dng")
        let encodedHash = SHA256.hash(data:data).map {String(format:"%02x",$0)}.joined()
        let same = before.hash == after.hash && before.width == after.width && before.height == after.height && before.format == after.format
        let written = fileReceipt.sha256 == encodedHash && fileReceipt.bytes == data.count
        var diagnostic: [String:Any] = ["diagnosticsVersion":3,"platform":"ios","rawBeforeDng":before.fields,"rawAfterDng":after.fields,
            "beforeEncodingElapsedNs":start,"afterEncodingElapsedNs":encoded,"afterReadbackElapsedNs":now,
            "rawSavedDng":["status":written ? "encoded_bytes_verified" : "file_mismatch","sha256":fileReceipt.sha256,"encodedSha256":encodedHash,
                "scope":"compressed file bytes only; native DNG samples are independently checked by the desktop"],
            "sampleIntegrity":["assessment":same && written ? "consistent_native_buffer_and_file" : "sample_mismatch_observed",
                "checks":["beforeToAfterEncoding":same ? "match":"mismatch","encodedToSavedFile":written ? "match":"mismatch"],
                "findings":same && written ? [] : ["native_buffer_or_file_changed"]]]
        do {
            let layout = try BayerDNG.layout(data)
            let full = before.width == layout.rawWidth && before.height == layout.rawHeight
            let active = before.width == layout.width*2 && before.height == layout.height*2
            guard full || active else { throw CaptureError.invalid("Native buffer and DNG crop disagree") }
            let ox = full ? layout.activeArea[1] : 0, oy = full ? layout.activeArea[0] : 0
            let cfa = (0..<4).map {before.cfa[(($0/2+oy)%2)*2+($0%2+ox)%2]}
            guard cfa == layout.cfa else { throw CaptureError.invalid("Native Bayer pattern disagrees with DNG") }
            diagnostic["bufferOrigin"] = full ? [0,0] : [layout.activeArea[1],layout.activeArea[0]]
            diagnostic["normalization"] = try layout.fields()
            if let stage {
                guard let pixels = before.pixels else { throw CaptureError.invalid("No native Bayer raster") }
                let output = FileManager.default.createFile(atPath:stage.path,contents:nil)
                guard output else { throw CaptureError.unavailable("Cannot stage native Bayer raster") }
                let stream = try FileHandle(forWritingTo:stage); defer { try? stream.close() }
                for y in 0..<layout.height*2 {
                    let start = ((y+oy)*before.width+ox)*2
                    try stream.write(contentsOf:pixels.subdata(in:start..<start+layout.width*4))
                }
                try stream.synchronize()
            }
        } catch {
            if stage != nil { throw error }
            diagnostic["normalizationError"] = error.localizedDescription
        }
        return diagnostic
    }
}
