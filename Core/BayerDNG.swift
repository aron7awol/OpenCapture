import Foundation

public struct DNGRadialGain: Codable, Equatable, Sendable {
    public var id = 3
    public var version = "1.3.0.0"
    public var flags: Int
    public var coefficients: [Double]
    public var center: [Double]
}

public struct BayerLayout: Codable, Equatable, Sendable {
    public var domain = "black_subtracted_dng_corrected_bayer_site_relative_signal"
    public var rawWidth: Int
    public var rawHeight: Int
    public var activeArea: [Int]
    public var blackLevels: [Double]
    public var whiteLevel: Double
    public var cfa: [Int]
    public var radialGain: [DNGRadialGain]
    public var width: Int { (activeArea[3] - activeArea[1]) / 2 }
    public var height: Int { (activeArea[2] - activeArea[0]) / 2 }
    public func fields() throws -> [String: Any] {
        var value = try JSONSerialization.jsonObject(with: JSONEncoder().encode(self)) as! [String: Any]
        value["width"] = width; value["height"] = height
        return value
    }
    public func gain(x: Int, y: Int) throws -> Float {
        var gain = 1.0
        for op in radialGain {
            let cx = op.center[0] * Double(width * 2 - 1), cy = op.center[1] * Double(height * 2 - 1)
            let dx = max(cx, Double(width * 2 - 1) - cx), dy = max(cy, Double(height * 2 - 1) - cy)
            let denominator = dx * dx + dy * dy
            guard denominator > 0 else { throw CaptureError.invalid("Invalid DNG radial bounds") }
            let xx = Double(x) - cx, yy = Double(y) - cy
            let r2 = (xx * xx + yy * yy) / denominator
            var polynomial = op.coefficients[4]
            for c in op.coefficients.prefix(4).reversed() { polynomial = polynomial * r2 + c }
            gain *= 1 + r2 * polynomial
        }
        guard gain.isFinite, (0.125...16).contains(gain) else { throw CaptureError.invalid("DNG radial gain outside supported range") }
        return Float(gain)
    }
}

/// Bounded TIFF metadata only. Compressed pixels are NOT decoded or rendered here.
public enum BayerDNG {
    public static func layout(_ data: Data) throws -> BayerLayout {
        func invalid() -> CaptureError { .invalid("Unsupported or malformed Bayer DNG metadata") }
        guard (8...134_217_728).contains(data.count), [Data([73,73]), Data([77,77])].contains(data.prefix(2)) else { throw invalid() }
        let little = data[0] == 73
        func uint(_ offset: Int, _ count: Int, big: Bool = false) throws -> UInt64 {
            guard offset >= 0, count >= 0, count <= 8, offset <= data.count - count else { throw invalid() }
            var value: UInt64 = 0
            for i in 0..<count {
                let shift = (big || !little) ? count - 1 - i : i
                value |= UInt64(data[offset+i]) << (shift*8)
            }
            return value
        }
        guard try uint(2,2) == 42 else { throw invalid() }
        var pending = [Int(try uint(4,4))], seen = Set<Int>()
        var raws: [[Int:[Double]]] = [], opcodeMaps: [[Int:Data]] = []
        var version: [Double]?
        var totalMetadata = 0
        let tags: Set<Int> = [254,256,257,258,259,262,277,284,330,339,33421,33422,50706,50710,50711,50712,50713,50714,50715,50716,50717,50718,50829,51008,51009,51022]
        while let offset = pending.popLast() {
            if offset == 0 { continue }
            guard seen.insert(offset).inserted, seen.count <= 32 else { throw invalid() }
            let count = Int(try uint(offset,2))
            guard count <= 1024, offset <= data.count - 6 - count*12 else { throw invalid() }
            var fields: [Int:[Double]] = [:], ops: [Int:Data] = [:]
            for i in 0..<count {
                let e = offset + 2 + i*12, tag = Int(try uint(e,2))
                if !tags.contains(tag) { continue }
                guard fields[tag] == nil, ops[tag] == nil else { throw invalid() }
                let kind = Int(try uint(e+2,2)), n = Int(try uint(e+4,4))
                guard let size = [1:1,3:2,4:4,5:8,7:1,13:4][kind], (1...65536).contains(n), n*size <= 65536 else { throw invalid() }
                totalMetadata += n*size
                guard totalMetadata <= 524288 else { throw invalid() }
                let start = n*size <= 4 ? e+8 : Int(try uint(e+8,4))
                guard start >= 0, start <= data.count - n*size else { throw invalid() }
                if [51008,51009,51022].contains(tag) {
                    guard kind == 7 else { throw invalid() }
                    ops[tag] = data.subdata(in: start..<start+n)
                } else {
                    fields[tag] = try (0..<n).map { j in
                        let p = start+j*size
                        if kind == 5 {
                            let denominator = try uint(p+4,4)
                            guard denominator != 0 else { throw invalid() }
                            return Double(try uint(p,4)) / Double(denominator)
                        }
                        return Double(try uint(p,size))
                    }
                }
            }
            if let v = fields[50706] {
                guard version == nil || version == v else { throw invalid() }
                version = v
            }
            if fields[262] == [32803], fields[254, default:[0]] == [0] { raws.append(fields); opcodeMaps.append(ops) }
            pending += fields[330, default:[]].map(Int.init)
            pending.append(Int(try uint(offset+2+count*12,4)))
        }
        guard version?.count == 4, version?.first == 1, raws.count == 1 else { throw invalid() }
        let f = raws[0]
        func scalar(_ tag: Int, _ fallback: Double? = nil) throws -> Double {
            guard let values = f[tag] ?? fallback.map({[$0]}), values.count == 1 else { throw invalid() }
            return values[0]
        }
        let w = Int(try scalar(256)), h = Int(try scalar(257))
        guard (8...16384).contains(w), (8...16384).contains(h), w*h <= 13_000_000,
              try scalar(277,1) == 1, try scalar(284,1) == 1, try scalar(339,1) == 1,
              [1.0,7.0].contains(try scalar(259)), (8...16).contains(try scalar(258)),
              f[50718, default:[1,1]] == [1,1], f[50710, default:[0,1,2]] == [0,1,2],
              f[50711, default:[1]] == [1], [50712,50715,50716].allSatisfy({f[$0] == nil}),
              f[33421] == [2,2], let pattern = f[33422], pattern.sorted() == [0,1,1,2] else { throw invalid() }
        let coordinates = f[50829, default:[0,0,Double(h),Double(w)]]
        guard coordinates.allSatisfy({$0.rounded() == $0}) else { throw invalid() }
        let area = coordinates.map(Int.init)
        guard area.count == 4, area[0] >= 0, area[1] >= 0, area[2] <= h, area[3] <= w,
              area[2]-area[0] >= 8, area[3]-area[1] >= 8, (area[2]-area[0]) % 2 == 0, (area[3]-area[1]) % 2 == 0 else { throw invalid() }
        let repeatSize = f[50713, default:[1,1]], levels = f[50714, default:[0]]
        guard (repeatSize == [1,1] && levels.count == 1) || (repeatSize == [2,2] && levels.count == 4) else { throw invalid() }
        let white = try scalar(50717)
        guard (1...65535).contains(white), levels.allSatisfy({$0.isFinite && $0 >= 0 && $0 < white}) else { throw invalid() }
        let cfa = (0..<4).map { Int(pattern[(($0/2+area[0])%2)*2+($0%2+area[1])%2]) }
        var gains: [DNGRadialGain] = []
        for (tag, bytes) in opcodeMaps[0] {
            func number(_ offset: Int, _ n: Int) throws -> UInt64 {
                guard offset >= 0, offset <= bytes.count-n else { throw invalid() }
                return bytes[offset..<offset+n].reduce(0) { ($0 << 8) | UInt64($1) }
            }
            let count = Int(try number(0,4)); guard count <= 16 else { throw invalid() }
            var offset = 4
            for _ in 0..<count {
                let id = try number(offset,4), v = try number(offset+4,4), flags = Int(try number(offset+8,4)), length = Int(try number(offset+12,4))
                offset += 16
                guard tag == 51022, v == 0x01030000, flags & ~3 == 0, offset <= bytes.count-length else { throw invalid() }
                if id == 3, length == 56 {
                    let values = try (0..<7).map { Double(bitPattern: try number(offset+$0*8,8)) }
                    guard values.allSatisfy(\.isFinite), values.prefix(5).allSatisfy({abs($0)<=1000}), values.suffix(2).allSatisfy({(0...1).contains($0)}) else { throw invalid() }
                    gains.append(DNGRadialGain(flags:flags, coefficients:Array(values.prefix(5)), center:Array(values.suffix(2))))
                } else if id == 1, flags & 1 == 1, count == 1, length == 68, try number(offset,4) == 1 {
                    let p = try (0..<8).map { Double(bitPattern: try number(offset+4+$0*8,8)) }
                    guard p.allSatisfy(\.isFinite), p[0] > 0, p[0] <= 16, p[1..<6].allSatisfy({abs($0)<=1000}), p.suffix(2).allSatisfy({(0...1).contains($0)}) else { throw invalid() }
                } else { throw invalid() }
                offset += length
            }
            guard offset == bytes.count else { throw invalid() }
        }
        return BayerLayout(rawWidth:w, rawHeight:h, activeArea:area, blackLevels:levels.count == 1 ? Array(repeating:levels[0],count:4) : levels,
                           whiteLevel:white, cfa:cfa, radialGain:gains)
    }
}
