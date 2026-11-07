import Foundation

struct AppUpdate {
    let version: String
    let build: String
    let notes: String
    let source: URL
    let bundleID: String
    var appURL: URL {
        var url = URLComponents()
        url.scheme = "altstore-classic"
        url.host = "viewApp"
        url.queryItems = [URLQueryItem(name: "bundleID", value: bundleID)]
        return url.url!
    }
    var sourceURL: URL {
        var url = URLComponents()
        url.scheme = "altstore-classic"
        url.host = "source"
        url.queryItems = [URLQueryItem(name: "url", value: source.absoluteString)]
        return url.url!
    }
}

enum UpdateService {
    static func check() async throws -> AppUpdate? {
        guard let text = Bundle.main.object(forInfoDictionaryKey: "OpenCaptureSourceURL") as? String,
              let url = URL(string: text), url.scheme == "https",
              let bundleID = Bundle.main.object(forInfoDictionaryKey: "OpenCaptureOriginalBundleID") as? String else {
            throw CaptureError.unavailable("No release source is configured")
        }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForResource = 30
        let session = URLSession(configuration: config, delegate: RedirectPolicy(httpsOnly: true), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (file, response) = try await session.download(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw CaptureError.unavailable("No published release feed is available yet")
        }
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= 1_048_576 else { throw CaptureError.invalid("Release feed is too large") }
        struct Version: Decodable { let version: String; let buildVersion: String?; let localizedDescription: String?; let minOSVersion: String? }
        struct App: Decodable { let bundleIdentifier: String; let versions: [Version] }
        struct Source: Decodable { let apps: [App] }
        let source = try JSONDecoder().decode(Source.self, from: Data(contentsOf: file))
        let os = ProcessInfo.processInfo.operatingSystemVersion
        let currentOS = "\(os.majorVersion).\(os.minorVersion).\(os.patchVersion)"
        guard let app = source.apps.first(where: { $0.bundleIdentifier == bundleID }),
              let release = app.versions.first(where: { currentOS.compare($0.minOSVersion ?? "0", options: .numeric) != .orderedAscending }) else {
            throw CaptureError.unavailable("No compatible OpenCapture version was found")
        }
        let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        guard ReleaseVersion.newer(version: release.version, build: release.buildVersion ?? "0", than: current, build: build) else { return nil }
        return AppUpdate(version: release.version, build: release.buildVersion ?? "0", notes: release.localizedDescription ?? "", source: url, bundleID: bundleID)
    }
}
