import Foundation
import Combine
import Darwin

struct NearbyController: Identifiable, Equatable {
    let id: String
    let name: String
    let url: String
    let requiresToken: Bool
}

// Bonjour callbacks run on the main run loop on which this browser is started.
final class ControllerDiscovery: NSObject, ObservableObject, NetServiceBrowserDelegate, NetServiceDelegate {
    @Published private(set) var controllers: [NearbyController] = []
    @Published private(set) var message = "Looking for controllers on your local network…"
    private let browser = NetServiceBrowser()
    private var services: [String: NetService] = [:]
    private var searching = false

    func start() {
        guard !searching else { return }
        searching = true
        message = "Looking for controllers on your local network…"
        browser.delegate = self
        browser.searchForServices(ofType: "_opencapture._tcp.", inDomain: "local.")
    }

    func stop() {
        browser.stop()
        for service in services.values { service.stop(); service.delegate = nil }
        services.removeAll()
        controllers.removeAll()
        searching = false
    }

    private func key(_ service: NetService) -> String { service.name + "|" + service.type + "|" + service.domain }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard searching else { return }
        services[key(service)] = service
        service.delegate = self
        service.resolve(withTimeout: 5)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        let id = key(service)
        services.removeValue(forKey: id)?.stop()
        controllers.removeAll { $0.id == id }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        message = "Discovery is unavailable. Allow Local Network access in Settings, or enter the controller IP below."
        searching = false
    }

    func netServiceDidResolveAddress(_ service: NetService) {
        guard searching, services[key(service)] === service, (1...65535).contains(service.port) else { return }
        let txt = service.txtRecordData().map(NetService.dictionary(fromTXTRecord:)) ?? [:]
        guard txt["protocol"].flatMap({ String(data: $0, encoding: .utf8) }) == "1" else { return }
        for address in service.addresses ?? [] {
            let host: String? = address.withUnsafeBytes { raw in
                guard let pointer = raw.baseAddress?.assumingMemoryBound(to: sockaddr.self),
                      raw.count >= MemoryLayout<sockaddr_in>.size,
                      pointer.pointee.sa_family == sa_family_t(AF_INET) else { return nil }
                var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                guard getnameinfo(pointer, socklen_t(raw.count), &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 else { return nil }
                return String(cString: buffer)
            }
            guard let host, EndpointPolicy.isLocalHost(host),
                  let url = try? EndpointPolicy.controllerURL("http://\(host):\(service.port)") else { continue }
            let controller = NearbyController(id: key(service), name: service.name, url: url.absoluteString,
                requiresToken: txt["auth"].flatMap({ String(data: $0, encoding: .utf8) }) != "none")
            controllers.removeAll { $0.id == controller.id }
            controllers.append(controller)
            controllers.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            return
        }
    }
}
