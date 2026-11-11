import Foundation

public struct ControllerHTTPError: LocalizedError {
    public let status: Int
    public init(status: Int) { self.status = status }
    public var errorDescription: String? {
        if status == 401 || status == 403 { return "Controller rejected the connection. Check the PC address and pairing token." }
        return "Controller HTTP \(status). Check the controller and network."
    }
}

/// Reconnection applies to the control channel, never to replaying a capture job.
public enum RemoteReconnect {
    public static func delaySeconds(failure: Int) -> Int { [1, 2, 4, 8, 15][min(4, max(0, failure))] }
    public static func retryable(_ error: Error) -> Bool {
        if let http = error as? ControllerHTTPError {
            return [408, 425, 429].contains(http.status) || (500...599).contains(http.status)
        }
        guard let network = error as? URLError else { return false }
        return [.timedOut, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost,
                .dnsLookupFailed, .notConnectedToInternet, .internationalRoamingOff,
                .dataNotAllowed, .cannotLoadFromNetwork].contains(network.code)
    }
}
