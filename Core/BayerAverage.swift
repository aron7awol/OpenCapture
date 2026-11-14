import Foundation
import CryptoKit

public struct BayerEvidence: Sendable {
    public var file: URL
    public var layout: BayerLayout
    public var rms: Double
    public var spatialStd: Double
    public var mean: Double
    public var clippedFraction: Double
    public var integrity: String
}

public struct BayerSelection: Sendable {
    public let target: Int
    public let policy: String
    public var attempts: [BayerEvidence] = []
    public var maxAttempts: Int { target*2 }
    public init(target: Int, policy: String) throws {
        guard (1...32).contains(target), ["relative","illuminated","dark"].contains(policy) else { throw CaptureError.invalid("Invalid averaging selection") }
        self.target = target; self.policy = policy
    }
    public var reasons: [String?] {
        let credible = attempts.filter { $0.integrity == "consistent_native_buffer_and_file" && $0.rms >= 0.001 }
        func median(_ values: [Double]) -> Double {
            let v = values.sorted(); return v.isEmpty ? 0 : (v[(v.count-1)/2]+v[v.count/2])/2
        }
        let rms = median(credible.map(\.rms)), std = median(credible.map(\.spatialStd))
        return attempts.map { item in
            if item.integrity != "consistent_native_buffer_and_file" { return "capture_integrity_not_verified" }
            if policy == "dark" { return nil }
            if item.clippedFraction == 0, item.rms < 0.001, item.spatialStd < 0.00025,
               policy == "illuminated" || (rms >= 0.001 && item.rms < rms*0.1 && item.spatialStd < std*0.1) { return "empty_signal" }
            return nil
        }
    }
    public var accepted: [Int] { reasons.enumerated().compactMap { $0.element == nil ? $0.offset : nil } }
    public var needsMore: Bool { accepted.count < target && attempts.count < maxAttempts }
}

public struct BayerRejection: Codable, Sendable { public let index: Int; public let reason: String }

public struct BayerAggregation: Codable, Sendable {
    public var version = 2
    public var mode = BayerAverage.mode
    public var status: String
    public var targetFrames: Int
    public var attemptedFrames: Int
    public var acceptedIndices: [Int]
    public var referenceIndex: Int
    public var retainedOriginalIndices: [Int]
    public var rejected: [BayerRejection]
    public var files: [String:CapturedFile]
    public var domain: String
    public var rawWidth: Int
    public var rawHeight: Int
    public var width: Int
    public var height: Int
    public var activeArea: [Int]
    public var blackLevels: [Double]
    public var whiteLevel: Double
    public var cfa: [Int]
    public var radialGain: [DNGRadialGain]
}

public enum BayerAverage {
    public static let mode = "bayer-mean-v2"
    public static func receipt(_ url: URL, media: String) throws -> CapturedFile {
        let f = try FileHandle(forReadingFrom:url); defer { try? f.close() }
        var hash = SHA256(), count = 0
        while let data = try f.read(upToCount:1_048_576), !data.isEmpty {
            count += data.count
            guard count <= 134_217_728 else { throw CaptureError.invalid("Capture file too large") }
            hash.update(data:data)
        }
        return CapturedFile(name:url.lastPathComponent, bytes:count, sha256:hash.finalize().map {String(format:"%02x",$0)}.joined(), mediaType:media)
    }
    private static func open(_ file: URL, _ layout: BayerLayout) throws -> FileHandle {
        let handle=try FileHandle(forReadingFrom:file)
        do {
            guard try handle.seekToEnd() == UInt64(layout.width*layout.height*8) else { throw CaptureError.invalid("Invalid staged Bayer raster size") }
            return handle
        } catch { try? handle.close(); throw error }
    }
    private static func blocks(_ handle: FileHandle, _ layout: BayerLayout, start: Int, rows: Int,
                               gains: [Float]? = nil, body: (Int,[Float],Bool) throws -> Void) throws {
        try handle.seek(toOffset:UInt64(start*layout.width*8))
        let count = rows*layout.width*8
        guard let data = try handle.read(upToCount:count), data.count == count else { throw CaptureError.invalid("Truncated staged Bayer raster") }
        var values = [Float](repeating:0,count:5)
        for y in 0..<rows {
            try Task.checkCancellation()
            for x in 0..<layout.width {
                var clipped = false
                values[4] = 0
                for site in 0..<4 {
                    let offset = ((2*y+site/2)*layout.width*2+2*x+site%2)*2
                    let native = UInt16(data[offset]) | UInt16(data[offset+1]) << 8
                    let v = (Float(native)-Float(layout.blackLevels[site]))/(Float(layout.whiteLevel)-Float(layout.blackLevels[site]))
                    guard v.isFinite, (-1...2).contains(v) else { throw CaptureError.invalid("Native Bayer units disagree with DNG levels") }
                    clipped = clipped || v >= 0.995
                    let gain: Float
                    if let gains { gain=gains[(y*layout.width+x)*4+site] }
                    else { gain=try layout.gain(x:2*x+site%2,y:2*(start+y)+site/2) }
                    let corrected = v * gain
                    values[site] = corrected
                    values[4] += corrected * 0.25
                }
                try body(y*layout.width+x,values,clipped)
            }
        }
    }
    public static func inspect(file: URL, layout: BayerLayout, integrity: String) throws -> BayerEvidence {
        let handle=try open(file,layout); defer { try? handle.close() }
        var n = 0, mean = 0.0, m2 = 0.0, clipped = 0
        for start in stride(from:0,to:layout.height,by:32) {
            try blocks(handle,layout,start:start,rows:min(32,layout.height-start)) { _, values, clip in
                n += 1; let value = Double(values[4]), delta = value-mean
                mean += delta/Double(n); m2 += delta*(value-mean)
                if clip { clipped += 1 }
            }
        }
        return BayerEvidence(file:file,layout:layout,rms:sqrt(m2/Double(n)+mean*mean),spatialStd:sqrt(m2/Double(n)),
                             mean:mean,clippedFraction:Double(clipped)/Double(n),integrity:integrity)
    }
    public static func write(_ frames: [BayerEvidence], directory: URL) throws -> [String:CapturedFile] {
        guard (1...32).contains(frames.count), let layout = frames.first?.layout, frames.allSatisfy({$0.layout == layout}) else {
            throw CaptureError.invalid("DNG normalization changed during burst")
        }
        var handles: [FileHandle] = []
        defer { for handle in handles { try? handle.close() } }
        for frame in frames { handles.append(try open(frame.file,layout)) }
        let moments = directory.appendingPathComponent("average.f32"), clipping = directory.appendingPathComponent("average-clips.u8")
        try Data().write(to:moments); try Data().write(to:clipping)
        let output = try FileHandle(forWritingTo:moments), clipOutput = try FileHandle(forWritingTo:clipping)
        defer { try? output.close(); try? clipOutput.close() }
        for start in stride(from:0,to:layout.height,by:32) {
            try Task.checkCancellation()
            let rows = min(32,layout.height-start), pixels = rows*layout.width
            var means = [Double](repeating:0,count:pixels*5), variance = [Double](repeating:0,count:pixels), clips = [UInt8](repeating:0,count:pixels)
            let gains: [Float]? = layout.radialGain.isEmpty ? nil : try (0..<pixels*4).map { i in
                let site=i%4, pixel=i/4
                return try layout.gain(x:2*(pixel%layout.width)+site%2,y:2*(start+pixel/layout.width)+site/2)
            }
            for (f,handle) in handles.enumerated() {
                try blocks(handle,layout,start:start,rows:rows,gains:gains) { i, values, clip in
                    if clip { clips[i] += 1 }
                    for site in 0..<5 {
                        let delta = Double(values[site])-means[i*5+site]
                        means[i*5+site] += delta/Double(f+1)
                        if site == 4 { variance[i] += delta*(Double(values[site])-means[i*5+site]) }
                    }
                }
            }
            var bytes = Data(capacity:pixels*24)
            func append(_ value: Float) { var bits = value.bitPattern.littleEndian; withUnsafeBytes(of:&bits) { bytes.append(contentsOf:$0) } }
            for i in 0..<pixels {
                for site in 0..<5 { append(Float(means[i*5+site])) }
                append(Float(max(0,variance[i])/Double(max(1,frames.count-1))))
            }
            try output.write(contentsOf:bytes); try clipOutput.write(contentsOf:Data(clips))
        }
        try output.synchronize(); try clipOutput.synchronize()
        return ["moments":try receipt(moments,media:"application/octet-stream"), "clipping":try receipt(clipping,media:"application/octet-stream")]
    }
    public static func finalize(_ selection: BayerSelection, options: AveragingOptions, frames: inout [FrameRecord], directory: URL) throws -> BayerAggregation {
        let accepted = selection.accepted, reasons = selection.reasons, reference = accepted.first ?? 0
        guard !frames.isEmpty, frames.count == selection.attempts.count,
              accepted.count == selection.target || frames.count == selection.maxAttempts else { throw CaptureError.invalid("Incomplete averaging attempts") }
        let layout = selection.attempts[reference].layout
        let retained = frames.indices.filter { options.keepOriginals || $0 == reference || reasons[$0] != nil }
        for i in frames.indices {
            try Task.checkCancellation()
            let file = directory.appendingPathComponent(frames[i].metadataFile.name)
            guard var metadata = try JSONSerialization.jsonObject(with:Data(contentsOf:file)) as? [String:Any] else { throw CaptureError.invalid("Invalid sidecar") }
            let item = selection.attempts[i]
            metadata["averagingSelection"] = ["policy":options.emptyFramePolicy,"accepted":reasons[i] == nil,"reason":reasons[i] as Any? ?? NSNull(),
                "rms":item.rms,"spatialStd":item.spatialStd,"mean":item.mean,"clippedFraction":item.clippedFraction,
                "integrity":item.integrity,"normalization":try item.layout.fields()]
            try JSONSerialization.data(withJSONObject:metadata,options:[.prettyPrinted,.sortedKeys]).write(to:file,options:.atomic)
            frames[i].metadataFile = try receipt(file,media:"application/json")
        }
        let complete = accepted.count == selection.target
        let files = complete ? try write(accepted.map {selection.attempts[$0]},directory:directory) : [:]
        return BayerAggregation(status:complete ? "complete" : "insufficient",targetFrames:selection.target,attemptedFrames:frames.count,
            acceptedIndices:accepted.map {$0+1},referenceIndex:reference+1,retainedOriginalIndices:retained.map {$0+1},
            rejected:reasons.enumerated().compactMap { i,r in r.map {BayerRejection(index:i+1,reason:$0)} },files:files,
            domain:layout.domain,rawWidth:layout.rawWidth,rawHeight:layout.rawHeight,width:layout.width,height:layout.height,
            activeArea:layout.activeArea,blackLevels:layout.blackLevels,whiteLevel:layout.whiteLevel,cfa:layout.cfa,radialGain:layout.radialGain)
    }
}
