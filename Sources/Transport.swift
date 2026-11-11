import Foundation
import Security

final class RedirectPolicy: NSObject, URLSessionTaskDelegate {
    let httpsOnly: Bool
    init(httpsOnly: Bool = false) { self.httpsOnly = httpsOnly }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        // Capture credentials never follow redirects. Update downloads contain no credentials.
        completionHandler(httpsOnly && request.url?.scheme == "https" ? request : nil)
    }
}

final class CaptureTransport {
    let base: URL
    private let token: String
    private let session: URLSession
    init(url: String, token: String) throws {
        base = try EndpointPolicy.controllerURL(url)
        try EndpointPolicy.validatePairingToken(token, for: base)
        self.token = token
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 180
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.httpMaximumConnectionsPerHost = 3
        session = URLSession(configuration: configuration, delegate: RedirectPolicy(), delegateQueue: nil)
    }

    private func request(_ path: String, method: String = "GET", lease: String? = nil) -> URLRequest {
        var request = URLRequest(url: base.appendingPathComponent(path))
        request.httpMethod = method
        if !token.isEmpty { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if let lease { request.setValue(lease, forHTTPHeaderField: "X-OpenCapture-Lease") }
        return request
    }

    private func check(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            throw ControllerHTTPError(status: status)
        }
        guard data.count <= 1_048_576 else { throw CaptureError.transport("Controller response is too large") }
    }

    @discardableResult
    func post<T: Encodable>(_ value: T, to path: String, lease: String? = nil) async throws -> Data {
        var req = request(path, method: "POST", lease: lease)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(value)
        let (data, response) = try await session.data(for: req)
        try check(response, data: data)
        return data
    }

    func lease() async throws -> CaptureLease {
        let (data, response) = try await session.data(for: request("oc/v1/lease"))
        try check(response, data: data)
        guard data.count <= 32768 else { throw CaptureError.transport("Lease is too large") }
        let result = try JSONDecoder().decode(CaptureLease.self, from: data)
        guard result.protocolVersion == 1 else { throw CaptureError.transport("Unsupported controller protocol") }
        if result.request != nil {
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any], let raw = object["request"],
                  let secret = result.leaseToken, secret.count >= 24, secret.count <= 128 else {
                throw CaptureError.transport("Malformed lease")
            }
            _ = try CaptureRequest.decode(JSONSerialization.data(withJSONObject: raw))
        }
        return result
    }

    func heartbeat(id: String, lease: String) async throws -> Bool {
        struct Heartbeat: Codable { var protocolVersion = 1; var cancelled: Bool }
        let data = try await post(["protocolVersion": 1], to: "oc/v1/jobs/\(id)/heartbeat", lease: lease)
        return try JSONDecoder().decode(Heartbeat.self, from: data).cancelled
    }

    func upload(_ file: CapturedFile, from directory: URL, id: String, lease: String) async throws {
        guard (1...(128 * 1024 * 1024)).contains(file.bytes) else {
            throw CaptureError.transport("Original exceeds the controller's 128 MiB file limit; the local original is retained for export.")
        }
        var req = request("oc/v1/jobs/\(id)/files/\(file.name)", method: "PUT", lease: lease)
        req.setValue(file.mediaType, forHTTPHeaderField: "Content-Type")
        req.setValue(file.sha256, forHTTPHeaderField: "X-Content-SHA256")
        req.setValue(String(file.bytes), forHTTPHeaderField: "Content-Length")
        for attempt in 0..<3 {
            do {
                try Task.checkCancellation()
                let (data, response) = try await session.upload(for: req, fromFile: directory.appendingPathComponent(file.name))
                try check(response, data: data)
                struct Receipt: Decodable { var name: String; var sha256: String; var bytes: Int }
                let receipt = try JSONDecoder().decode(Receipt.self, from: data)
                guard receipt.name == file.name, receipt.sha256 == file.sha256, receipt.bytes == file.bytes else {
                    throw CaptureError.transport("Upload receipt mismatch")
                }
                return
            } catch {
                if attempt == 2 || Task.isCancelled { throw error }
                try await Task.sleep(nanoseconds: UInt64(attempt + 1) * 1_000_000_000)
            }
        }
    }

    func preview(_ jpeg: Data) async throws {
        var req = request("oc/v1/preview", method: "POST")
        req.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
        req.httpBody = jpeg
        let (data, response) = try await session.data(for: req)
        try check(response, data: data)
    }

    func finish(id: String, lease: String, state: String, message: String) async {
        struct Failure: Encodable { var protocolVersion = 1; let status: String; let message: String }
        _ = try? await post(Failure(status: state, message: String(message.prefix(256))), to: "oc/v1/jobs/\(id)/failure", lease: lease)
    }

    func close() { session.invalidateAndCancel() }
}

enum TokenStore {
    static func load() -> String {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "OpenCapture.controller", kSecReturnData as String: true]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }
    static func save(_ value: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "OpenCapture.controller"]
        SecItemDelete(query as CFDictionary)
        guard !value.isEmpty else { return }
        var item = query
        item[kSecValueData as String] = Data(value.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw CaptureError.unavailable("Could not store pairing token in Keychain") }
    }
}
