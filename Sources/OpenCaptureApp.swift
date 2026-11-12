import SwiftUI
import AVFoundation

@main struct OpenCaptureApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            CameraScreen(model: model)
                .task { await model.foreground() }
                .onChange(of: phase) { _, value in
                    if value == .background { model.background() }
                    if value == .inactive { model.inactive() }
                    if value == .active { Task { await model.foreground() } }
                }
        }
    }
}

struct CameraScreen: View {
    @ObservedObject var model: AppModel
    @State private var about = false
    var body: some View {
        GeometryReader { geometry in
            let landscape = geometry.size.width > geometry.size.height && geometry.size.width >= 600
            let layout = landscape ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
            layout {
                CameraPreview(session: model.camera.session)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityIdentifier("cameraPreview")
                toolbar(landscape: landscape)
                    .frame(width: landscape ? 168 : nil)
                    .frame(maxWidth: landscape ? nil : .infinity, maxHeight: landscape ? .infinity : nil)
                    .background(Color(white: 0.09))
            }
        }.background(.black).preferredColorScheme(.dark)
            .sheet(isPresented: $about) { AboutView(model: model) }
    }
    private func toolbar(landscape: Bool) -> some View {
        let layout = landscape ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12)) : AnyLayout(HStackLayout(spacing: 12))
        return layout {
            VStack(alignment: .leading, spacing: 4) {
                Text(model.cameraName).font(.headline).lineLimit(landscape ? 3 : 1)
                Text(model.status).font(.subheadline).lineLimit(landscape ? 4 : 2)
                    .accessibilityIdentifier("cameraStatus")
            }.frame(maxWidth: .infinity, alignment: .leading)
            if landscape { Spacer(minLength: 0) }
            Button { Task { await model.toggle() } } label: {
                Image(systemName: model.enabled ? "pause.fill" : "play.fill").frame(width: 44, height: 44)
            }.accessibilityLabel(model.enabled ? "Stop camera server" : "Resume camera server")
                .accessibilityIdentifier("serverToggle")
                .frame(maxWidth: landscape ? .infinity : nil)
            Button { about = true } label: { Image(systemName: "info.circle").frame(width: 44, height: 44) }
                .accessibilityLabel("About OpenCapture").accessibilityIdentifier("aboutButton")
                .frame(maxWidth: landscape ? .infinity : nil)
        }.padding(12).foregroundStyle(.white).buttonStyle(.plain)
    }
}

struct AboutView: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    private var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?" }
    private var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?" }
    var body: some View {
        NavigationStack {
            List {
                LabeledContent("Version", value: "\(version) (build \(build))")
                Button { Task { await model.checkUpdate() } } label: { Label("Check for updates", systemImage: "arrow.clockwise") }
                    .disabled(model.checkingUpdate)
                if !model.updateStatus.isEmpty { Text(model.updateStatus).font(.subheadline) }
                if let update = model.update {
                    Text("\(update.version) (build \(update.build))")
                    Button { openURL(update.appURL) } label: { Label("Update in AltStore", systemImage: "arrow.down.app") }
                    Button { openURL(update.sourceURL) } label: { Label("Add AltStore source", systemImage: "plus.app") }
                }
                Link("Source / GPLv3", destination: URL(string: "https://github.com/aron7awol/OpenCapture")!)
                NavigationLink("Third-party licenses") {
                    ScrollView { Text(notices).font(.caption.monospaced()).padding() }.navigationTitle("Licenses")
                }
            }.navigationTitle("OpenCapture").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
    private var notices: String {
        guard let url = Bundle.main.url(forResource: "ThirdPartyNotices", withExtension: "txt") else { return "No third-party notices found" }
        return (try? String(contentsOf: url, encoding: .utf8)) ?? "No third-party notices found"
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> PreviewSurface {
        let view = PreviewSurface(); view.layerView.session = session; view.layerView.videoGravity = .resizeAspect
        return view
    }
    func updateUIView(_ uiView: PreviewSurface, context: Context) { uiView.setNeedsLayout() }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: PreviewSurface, context: Context) -> CGSize? {
        guard let width = proposal.width, let height = proposal.height else { return nil }
        return CGSize(width: width, height: height)
    }
}

final class PreviewSurface: UIView {
    let layerView = AVCaptureVideoPreviewLayer()
    private let guides = CAShapeLayer()
    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        layer.addSublayer(layerView); layer.addSublayer(guides)
        guides.fillColor = UIColor.clear.cgColor; guides.strokeColor = UIColor(white: 0.5, alpha: 1).cgColor
        guides.allowsEdgeAntialiasing = false
    }
    required init?(coder: NSCoder) { fatalError("PreviewSurface is created programmatically") }
    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin(); CATransaction.setDisableActions(true)
        defer { CATransaction.commit() }
        layerView.frame = bounds; guides.frame = bounds
        if let connection = layerView.connection {
            let angle: CGFloat
            switch window?.windowScene?.interfaceOrientation {
            case .landscapeLeft: angle = 180
            case .landscapeRight: angle = 0
            case .portraitUpsideDown: angle = 270
            default: angle = 90
            }
            if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
        }
        let scale = window?.screen.scale ?? UIScreen.main.scale
        let pixel = 1 / scale
        let frame = layerView.layerRectConverted(fromMetadataOutputRect: CGRect(x: 0, y: 0, width: 1, height: 1)).intersection(bounds)
        guard !frame.isNull, !frame.isEmpty else { guides.path = nil; return }
        func edge(_ value: CGFloat) -> CGFloat { (value * scale).rounded() / scale }
        let fitted = CGRect(x: edge(frame.minX) + pixel / 2, y: edge(frame.minY) + pixel / 2,
            width: edge(frame.maxX) - edge(frame.minX) - pixel, height: edge(frame.maxY) - edge(frame.minY) - pixel)
        let path = UIBezierPath(rect: fitted)
        let x = floor(fitted.midX * scale) / scale + pixel / 2
        let y = floor(fitted.midY * scale) / scale + pixel / 2
        path.move(to: CGPoint(x: x, y: fitted.minY)); path.addLine(to: CGPoint(x: x, y: fitted.maxY))
        path.move(to: CGPoint(x: fitted.minX, y: y)); path.addLine(to: CGPoint(x: fitted.maxX, y: y))
        guides.contentsScale = scale; guides.lineWidth = pixel; guides.path = path.cgPath
    }
}
