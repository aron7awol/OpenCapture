import Foundation
import Darwin
import NIOCore
import NIOPosix
import NIOHTTP1

struct CameraHTTPRequest: Sendable {
    let method: String
    let path: String
    let session: String?
    let data: Data
}

struct CameraHTTPResponse: Sendable {
    var code = 200
    var type = "application/json"
    var data = Data()
    var file: URL?
    static func json<T: Encodable>(_ value: T, code: Int = 200) throws -> Self {
        Self(code: code, data: try JSONEncoder().encode(value))
    }
}

private final class CameraChannels: @unchecked Sendable {
    private let lock = NSLock()
    private var channels: [ObjectIdentifier: Channel] = [:]
    func add(_ channel: Channel) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard channels.count < 8 else { return false }
        channels[ObjectIdentifier(channel)] = channel
        channel.closeFuture.whenComplete { [weak self] _ in self?.remove(channel) }
        return true
    }
    private func remove(_ channel: Channel) {
        lock.lock(); defer { lock.unlock() }; channels.removeValue(forKey: ObjectIdentifier(channel))
    }
    func close() {
        lock.lock(); let active = Array(channels.values); lock.unlock()
        for channel in active { channel.close(promise: nil) }
    }
}

@MainActor final class LANHTTPServer {
    typealias Route = @MainActor @Sendable (CameraHTTPRequest) async -> CameraHTTPResponse
    private let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
    private let clients = CameraChannels()
    private var channel: Channel?
    private var stopped = false
    func start(address: String, route: @escaping Route) async throws {
        let clients = clients
        channel = try await ServerBootstrap(group: group)
            .serverChannelOption(ChannelOptions.backlog, value: 8)
            .serverChannelOption(ChannelOptions.socketOption(.so_reuseaddr), value: 1)
            .childChannelInitializer { channel in
                guard clients.add(channel) else { return channel.close() }
                return channel.pipeline.configureHTTPServerPipeline(withPipeliningAssistance: false).flatMap {
                    channel.pipeline.addHandler(CameraHTTPHandler(address: address, route: route))
                }
            }.bind(host: address, port: 8767).get()
    }
    func stop() async {
        guard !stopped else { return }; stopped = true
        try? await channel?.close().get(); clients.close()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            group.shutdownGracefully { _ in continuation.resume() }
        }
    }
    nonisolated static func privateIPv4(_ text: String) -> Bool {
        let segments = text.split(separator: ".", omittingEmptySubsequences: false)
        let p = segments.compactMap { UInt8($0) }
        guard p.count == 4, segments.count == 4 else { return false }
        return p[0] == 10 || p[0] == 127 || p[0] == 172 && (16...31).contains(p[1]) ||
            p[0] == 192 && p[1] == 168 || p[0] == 169 && p[1] == 254
    }
    static func wifiAddress() -> String? {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0 else { return nil }
        defer { freeifaddrs(first) }
        var item = first
        while let current = item {
            defer { item = current.pointee.ifa_next }
            guard String(cString: current.pointee.ifa_name) == "en0", let address = current.pointee.ifa_addr,
                  Int32(address.pointee.sa_family) == AF_INET else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let value = String(cString: host)
            if privateIPv4(value) { return value }
        }
        return nil
    }
}

private final class CameraHTTPHandler: ChannelInboundHandler {
    typealias InboundIn = HTTPServerRequestPart
    typealias OutboundOut = HTTPServerResponsePart
    private let address: String
    private let route: LANHTTPServer.Route
    private var head: HTTPRequestHead?
    private var body = Data()
    private var length = 0
    private var complete = false
    private var timeout: Scheduled<Void>?
    init(address: String, route: @escaping LANHTTPServer.Route) { self.address = address; self.route = route }
    func handlerAdded(context: ChannelHandlerContext) {
        timeout = context.eventLoop.scheduleTask(in: .seconds(15)) { context.close(promise: nil) }
    }
    func handlerRemoved(context: ChannelHandlerContext) { timeout?.cancel() }
    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        guard !complete else { context.close(promise: nil); return }
        switch unwrapInboundIn(data) {
        case .head(let request):
            guard head == nil, LANHTTPServer.privateIPv4(context.channel.remoteAddress?.ipAddress ?? ""),
                  request.headers["host"] == ["\(address):8767"], request.headers["origin"].isEmpty,
                  request.headers["sec-fetch-site"].isEmpty, request.headers["transfer-encoding"].isEmpty,
                  request.headers["content-length"].count <= 1,
                  request.method == .GET || request.method == .POST,
                  !request.uri.contains("?"), !request.uri.contains("%"), request.uri.count <= 256 else {
                reject(context, "Invalid trusted-LAN request"); return
            }
            length = Int(request.headers.first(name: "content-length") ?? "0") ?? -1
            guard (0...16384).contains(length), request.method != .GET || length == 0,
                  request.method != .POST || (length >= 2 && request.headers.first(name: "content-type")?.split(separator: ";").first == "application/json") else {
                reject(context, "Invalid JSON body length"); return
            }
            head = request
        case .body(var buffer):
            guard head != nil, body.count + buffer.readableBytes <= length else { reject(context, "Request too large"); return }
            body.append(contentsOf: buffer.readBytes(length: buffer.readableBytes) ?? [])
        case .end:
            guard let head, body.count == length else { reject(context, "Incomplete request"); return }
            complete = true; timeout?.cancel()
            timeout = context.eventLoop.scheduleTask(in: .seconds(180)) { context.close(promise: nil) }
            let request = CameraHTTPRequest(method: head.method.rawValue, path: head.uri,
                session: head.headers.first(name: "x-opencapture-session"), data: body)
            let route = route
            Task { @MainActor in
                let response = await route(request)
                context.eventLoop.execute { self.send(context, response) }
            }
        }
    }
    private func reject(_ context: ChannelHandlerContext, _ message: String) {
        complete = true; timeout?.cancel()
        send(context, (try? .json(["error": message], code: 400)) ?? CameraHTTPResponse(code: 400))
    }
    private func send(_ context: ChannelHandlerContext, _ response: CameraHTTPResponse) {
        guard context.channel.isActive else { return }
        var file: NIOFileHandle?
        do {
            let region: FileRegion?
            if let url = response.file {
                let handle = try NIOFileHandle(path: url.path)
                file = handle; region = try FileRegion(fileHandle: handle)
            } else { region = nil }
            var headers = HTTPHeaders()
            headers.add(name: "Content-Type", value: response.type)
            headers.add(name: "Content-Length", value: String(region?.readableBytes ?? response.data.count))
            headers.add(name: "Connection", value: "close")
            headers.add(name: "Cache-Control", value: "no-store")
            headers.add(name: "X-Content-Type-Options", value: "nosniff")
            context.write(wrapOutboundOut(.head(HTTPResponseHead(version: .http1_1, status: HTTPResponseStatus(statusCode: response.code), headers: headers))), promise: nil)
            if let region { context.write(wrapOutboundOut(.body(.fileRegion(region))), promise: nil) }
            else {
                var buffer = context.channel.allocator.buffer(capacity: response.data.count)
                buffer.writeBytes(response.data); context.write(wrapOutboundOut(.body(.byteBuffer(buffer))), promise: nil)
            }
            let promise = context.eventLoop.makePromise(of: Void.self)
            let opened = file
            promise.futureResult.whenComplete { _ in try? opened?.close(); context.close(promise: nil) }
            context.writeAndFlush(wrapOutboundOut(.end(nil)), promise: promise)
        } catch { try? file?.close(); context.close(promise: nil) }
    }
    func errorCaught(context: ChannelHandlerContext, error: Error) { context.close(promise: nil) }
}
