import SwiftUI
import AVFoundation
import QuickLook

@main struct OpenCaptureApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .task { await model.startCamera() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .background { model.background() }
                    if phase == .active, model.capabilities != nil { Task { await model.startCamera() } }
                }
        }
    }
}

struct RootView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        TabView {
            CameraView(model: model).tabItem { Label("Camera", systemImage: "camera") }
            ArchiveList(model: model).tabItem { Label("Captures", systemImage: "photo.stack") }
            ConnectionView(model: model).tabItem { Label("Connection", systemImage: "network") }
        }
        .tint(.teal)
        .alert("OpenCapture", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK") { model.errorMessage = nil }
        } message: { Text(model.errorMessage ?? "") }
    }
}

struct CameraView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    CameraPreview(session: model.camera.session)
                        .aspectRatio(4.0 / 3.0, contentMode: .fit)
                        .background(.black)
                        .overlay(alignment: .bottomLeading) {
                            Text("Processed live preview").font(.caption).padding(6).foregroundStyle(.white).background(.black.opacity(0.65))
                        }
                        .listRowInsets(EdgeInsets())
                    HStack {
                        Text(model.status).font(.subheadline)
                        Spacer()
                        if model.connected { Image(systemName: "network").foregroundStyle(.teal) }
                    }
                    if model.busy { ProgressView(value: model.progress) }
                }
                Section("Capture") {
                    Picker("Camera", selection: $model.cameraID) {
                        ForEach(model.cameras) { Text($0.name).tag($0.id) }
                    }.disabled(model.busy || model.connected || model.configuring)
                        .onChange(of: model.cameraID) { old, new in
                            if old != new && new != model.capabilities?.cameraID { Task { await model.startCamera() } }
                        }
                    Picker("Mode", selection: $model.mode) {
                        Text("Bayer RAW").tag("raw")
                        Text("Fast JPEG burst").tag("fast")
                        Text("Quality JPEG").tag("quality")
                    }.disabled(model.busy || model.connected)
                    Stepper("Frames: \(model.frameCount)", value: $model.frameCount, in: 1...32)
                        .disabled(model.busy || model.connected)
                    if let caps = model.capabilities {
                        LabeledContent("Bayer RAW", value: caps.formats.contains(.bayerRAW) ? "Available" : "Unavailable")
                        LabeledContent("Photo limit", value: "\(caps.maxPhotoWidth) x \(caps.maxPhotoHeight)")
                    }
                }
                Section("Exposure") {
                    Toggle("Manual exposure", isOn: $model.manualExposure)
                        .disabled(model.capabilities?.manualExposure != true)
                    if model.manualExposure {
                        NumberField(label: "Shutter (ms)", text: $model.exposureMS)
                        NumberField(label: "ISO", text: $model.iso)
                    } else { LabeledContent("Exposure", value: "Auto, locked for burst") }
                    if let caps = model.capabilities {
                        LabeledContent("ISO range", value: "\(Int(caps.minISO)) - \(Int(caps.maxISO))").font(.caption)
                        LabeledContent("Shutter range (ms)", value: String(format: "%.3f - %.1f", caps.minExposureSeconds * 1000, caps.maxExposureSeconds * 1000)).font(.caption)
                    }
                }.disabled(model.busy || model.connected)
                Section("Focus and White Balance") {
                    Toggle("Manual focus", isOn: $model.manualFocus).disabled(model.capabilities?.manualFocus != true)
                    if model.manualFocus {
                        HStack { Image(systemName: "camera.macro"); Slider(value: $model.focus, in: 0...1); Image(systemName: "mountain.2") }
                        LabeledContent("Lens position", value: String(format: "%.3f", model.focus))
                    }
                    Toggle("Manual white balance", isOn: $model.manualWB).disabled(model.capabilities?.manualWhiteBalance != true)
                    if model.manualWB {
                        NumberField(label: "Red gain", text: $model.wbRed)
                        NumberField(label: "Green gain", text: $model.wbGreen)
                        NumberField(label: "Blue gain", text: $model.wbBlue)
                    }
                    Button { Task { await model.useCurrentControls() } } label: { Label("Use current camera settings", systemImage: "lock") }
                    Button { model.previewSettings() } label: { Label("Apply controls", systemImage: "slider.horizontal.3") }
                }.disabled(model.busy || model.connected)
                Section {
                    if model.busy {
                        Button(role: .destructive) { model.cancel() } label: { Label("Cancel", systemImage: "stop.circle") }
                    } else {
                        Button { model.captureLocal() } label: {
                            Label("Capture \(model.frameCount) frame\(model.frameCount == 1 ? "" : "s")", systemImage: "camera.shutter.button")
                        }.disabled(model.connected || model.capabilities == nil || model.configuring)
                    }
                    if model.capabilities == nil {
                        Button { Task { await model.startCamera() } } label: { Label("Retry camera", systemImage: "arrow.clockwise") }
                    }
                }
            }
            .navigationTitle("OpenCapture")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct NumberField: View {
    let label: String
    @Binding var text: String
    var body: some View {
        HStack {
            Text(label)
            Spacer()
            TextField(label, text: $text).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(maxWidth: 150)
        }
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> PreviewSurface {
        let view = PreviewSurface(); view.layerView.session = session; view.layerView.videoGravity = .resizeAspect
        return view
    }
    func updateUIView(_ uiView: PreviewSurface, context: Context) { uiView.setNeedsLayout() }
}

final class PreviewSurface: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var layerView: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard let connection = layerView.connection else { return }
        let angle: CGFloat
        switch window?.windowScene?.interfaceOrientation {
        case .landscapeLeft: angle = 0
        case .landscapeRight: angle = 180
        case .portraitUpsideDown: angle = 270
        default: angle = 90
        }
        if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
    }
}

struct ArchiveList: View {
    @ObservedObject var model: AppModel
    var body: some View {
        NavigationStack {
            List {
                if model.archives.isEmpty { ContentUnavailableView("No captures", systemImage: "photo.stack") }
                ForEach(model.archives) { archive in
                    NavigationLink {
                        ArchiveDetail(archive: archive)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            if let manifest = archive.manifest {
                                Text(manifest.request.format == .bayerRAW ? "Bayer RAW" : "JPEG / \(manifest.request.quality.rawValue)")
                                Text("\(manifest.frames.count) frames - \(manifest.startedAt)").font(.caption).foregroundStyle(.secondary)
                            } else { Text("Partial capture").foregroundStyle(.orange) }
                            Text(String(archive.id.prefix(8))).font(.caption.monospaced()).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Captures").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button { model.refreshArchives() } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel("Refresh captures") }
            .refreshable { model.refreshArchives() }
        }
    }
}

struct ArchiveDetail: View {
    let archive: CaptureArchive
    @State private var share = false
    @State private var original: URL?
    var body: some View {
        List {
            if let manifest = archive.manifest {
                Section("Capture") {
                    LabeledContent("Camera", value: manifest.camera.name)
                    LabeledContent("Format", value: manifest.request.format.rawValue)
                    LabeledContent("Priority", value: manifest.request.quality.rawValue)
                    LabeledContent("Frames", value: String(manifest.frames.count))
                }
                ForEach(manifest.frames, id: \.index) { frame in
                    Section("Frame \(frame.index)") {
                        if let file = frame.previewFile,
                           let image = UIImage(contentsOfFile: archive.directory.appendingPathComponent(file.name).path) {
                            Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
                            Text("Processed thumbnail").font(.caption).foregroundStyle(.secondary)
                        }
                        LabeledContent("Dimensions", value: "\(frame.width) x \(frame.height)")
                        LabeledContent("EXIF shutter", value: frame.actualExposureSeconds.map { String(format: "%.3f ms", $0 * 1000) } ?? "Not reported")
                        LabeledContent("EXIF ISO", value: frame.actualISO.map { String(format: "%.1f", $0) } ?? "Not reported")
                        LabeledContent("Lens position", value: String(format: "%.3f", frame.lensPosition))
                        Button { original = archive.directory.appendingPathComponent(frame.file.name) } label: {
                            Label("Open original", systemImage: "doc.viewfinder")
                        }
                    }
                }
            } else { Text("Partial capture; original files retained.").foregroundStyle(.orange) }
        }
        .navigationTitle(String(archive.id.prefix(8)))
        .toolbar { Button { share = true } label: { Image(systemName: "square.and.arrow.up") }.accessibilityLabel("Export originals and metadata") }
        .sheet(isPresented: $share) { ShareFiles(files: archive.exportFiles) }
        .quickLookPreview($original)
    }
}

struct ShareFiles: UIViewControllerRepresentable {
    let files: [URL]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: files, applicationActivities: nil) }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

struct ConnectionView: View {
    @ObservedObject var model: AppModel
    @Environment(\.openURL) private var openURL
    private var sourceLink: URL? {
        guard let text = Bundle.main.object(forInfoDictionaryKey: "OpenCaptureSourceURL") as? String else { return nil }
        var components = URLComponents(); components.scheme = "altstore-classic"; components.host = "source"
        components.queryItems = [URLQueryItem(name: "url", value: text)]
        return components.url
    }
    var body: some View {
        NavigationStack {
            Form {
                Section("Controller") {
                    TextField("Server URL", text: $model.host).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    SecureField("Pairing token", text: $model.token).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if model.host.lowercased().hasPrefix("http:") { Label("Unencrypted trusted-LAN connection", systemImage: "lock.open").foregroundStyle(.orange).font(.caption) }
                }.disabled(model.connected)
                Section {
                    if model.connected {
                        Button { model.disconnect() } label: { Label("Disconnect", systemImage: "network.slash") }
                    } else {
                        Button { model.connect() } label: { Label("Connect", systemImage: "network") }
                            .disabled(model.busy || model.capabilities == nil || model.configuring)
                    }
                    Text(model.status)
                }
                Section("Updates") {
                    LabeledContent("Version", value: "\(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?") (\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"))")
                    Button { Task { await model.checkUpdate() } } label: { Label("Check for updates", systemImage: "arrow.clockwise") }
                        .disabled(model.busy || model.checkingUpdate)
                    if !model.updateStatus.isEmpty { Text(model.updateStatus).font(.caption) }
                    if let update = model.update {
                        Text("\(update.version) (\(update.build))").font(.headline)
                        if !update.notes.isEmpty { Text(update.notes).font(.caption) }
                        Button { launch(update.appURL) } label: { Label("Update in AltStore", systemImage: "arrow.down.app") }.disabled(model.busy || model.connected)
                    }
                    if let link = sourceLink {
                        Button { launch(link) } label: { Label("Add AltStore source", systemImage: "plus.app") }.disabled(model.busy || model.connected)
                    }
                }
            }.navigationTitle("Connection").navigationBarTitleDisplayMode(.inline)
        }
    }
    private func launch(_ url: URL) {
        openURL(url) { accepted in
            if !accepted { model.errorMessage = "AltStore Classic is not available. Install it before opening this link." }
        }
    }
}
