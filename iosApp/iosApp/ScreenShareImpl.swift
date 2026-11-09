import CoreImage
import Foundation
import ReplayKit
import Shared
import UIKit
import WebRTC

/// Screen sharing on a call (2026-11-09). iOS only lets another app's screen be captured by a ReplayKit Broadcast
/// Upload Extension (`iosApp/CircasoBroadcast`), which keeps running after the person leaves Circaso. The extension
/// sends each frame, JPEG-compressed, over a Unix socket in the App Group container; this side listens on it, turns
/// the frames back into pixel buffers and hands them to the call's video track (`CallEngineImpl.screenFrame`).
///
/// `available` is false until the extension is a target embedded in the app and the App Group is in its entitlements
/// (the steps are at the top of `CircasoBroadcast/SampleHandler.swift`), so the Share screen button stays hidden.
final class ScreenShareImpl: NSObject, ScreenShareProvider {
    static let appGroup = "group.com.circaso.app"
    /// The Darwin notification that asks the extension to finish (the call's Stop, or the call ending).
    static let stopNotification = "com.circaso.app.broadcast.stop"
    private static let extensionName = "CircasoBroadcast.appex"
    private static let socketName = "screen.sock"

    private weak var engine: CallEngineImpl?
    private var server: ScreenFrameServer?
    private var stoppedListener: (() -> Void)?
    private var pendingStart: ((KotlinBoolean) -> Void)?
    private var startTimer: Timer?
    private var picker: RPSystemBroadcastPickerView?

    init(engine: CallEngineImpl) {
        self.engine = engine
    }

    var available: Bool {
        guard FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup) != nil,
              let plugins = Bundle.main.builtInPlugInsURL else { return false }
        return FileManager.default.fileExists(atPath: plugins.appendingPathComponent(Self.extensionName).path)
    }

    func start(completion: @escaping (KotlinBoolean) -> Void) {
        DispatchQueue.main.async {
            self.finishPending(false)
            self.stopServer()
            guard self.available,
                  let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup) else {
                completion(false); return
            }
            let server = ScreenFrameServer(path: container.appendingPathComponent(Self.socketName).path)
            server.onConnected = { [weak self] in DispatchQueue.main.async { self?.connected() } }
            server.onFrame = { [weak self] buffer, rotation in self?.engine?.screenFrame(buffer, rotation: rotation) }
            server.onClosed = { [weak self] in DispatchQueue.main.async { self?.ended() } }
            guard server.listen() else { completion(false); return }
            self.server = server
            self.pendingStart = completion
            self.showPicker()
            // The picker says nothing when it is dismissed: no broadcast within this long counts as "not now".
            self.startTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: false) { [weak self] _ in
                guard let self, self.pendingStart != nil else { return }
                self.stopServer()
                self.finishPending(false)
            }
        }
    }

    func stop() {
        DispatchQueue.main.async {
            Self.postStop()
            self.stopServer()
            self.finishPending(false)
            self.engine?.endScreen()
        }
    }

    func setStoppedListener(listener: @escaping () -> Void) {
        stoppedListener = listener
    }

    private func connected() {
        startTimer?.invalidate()
        startTimer = nil
        removePicker()
        engine?.beginScreen()
        finishPending(true)
    }

    /// The extension went away (the red status bar's Stop, Control Center, or it failed).
    private func ended() {
        let wasStarting = pendingStart != nil
        stopServer()
        engine?.endScreen()
        if wasStarting { finishPending(false) } else { stoppedListener?() }
    }

    private func finishPending(_ ok: Bool) {
        startTimer?.invalidate()
        startTimer = nil
        let pending = pendingStart
        pendingStart = nil
        pending?(KotlinBoolean(value: ok))
    }

    private func stopServer() {
        server?.stopServing()
        server = nil
        removePicker()
    }

    /// The system's own broadcast picker, opened for the person: Circaso's extension preselected, no microphone
    /// (the call already sends it).
    private func showPicker() {
        let view = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
        if let id = Bundle.main.bundleIdentifier { view.preferredExtension = id + ".broadcast" }
        view.showsMicrophoneButton = false
        view.alpha = 0.01
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        window?.addSubview(view)
        picker = view
        for case let button as UIButton in view.subviews {
            button.sendActions(for: .touchUpInside)
        }
    }

    private func removePicker() {
        picker?.removeFromSuperview()
        picker = nil
    }

    private static func postStop() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        CFNotificationCenterPostNotification(center, CFNotificationName(stopNotification as CFString), nil, nil, true)
    }
}

/// The app's end of the broadcast socket: one extension at a time. Each message is a 16-byte header (JPEG length,
/// width, height, CGImagePropertyOrientation, each a big-endian UInt32) followed by the JPEG.
final class ScreenFrameServer {
    var onConnected: (() -> Void)?
    var onFrame: ((CVPixelBuffer, RTCVideoRotation) -> Void)?
    var onClosed: (() -> Void)?

    private let path: String
    private let queue = DispatchQueue(label: "com.circaso.screen.frames")
    private var listenFD: Int32 = -1
    private var clientFD: Int32 = -1
    private var acceptSource: DispatchSourceRead?
    private var readSource: DispatchSourceRead?
    private var pending = Data()
    private var finished = false
    private let ciContext = CIContext()

    init(path: String) {
        self.path = path
    }

    func listen() -> Bool {
        unlink(path)
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { return false }
        guard var addr = Self.address(path) else { Darwin.close(fd); return false }
        let size = socklen_t(MemoryLayout<sockaddr_un>.size)
        let bound = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, size) }
        }
        guard bound == 0, Darwin.listen(fd, 1) == 0 else { Darwin.close(fd); return false }
        listenFD = fd
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in self?.acceptClient() }
        source.setCancelHandler { Darwin.close(fd) }
        source.resume()
        acceptSource = source
        return true
    }

    func stopServing() {
        onConnected = nil
        onFrame = nil
        onClosed = nil
        // At once, not later: a new server on the same path must never have its socket unlinked by this one.
        queue.sync { self.finish() }
    }

    static func address(_ path: String) -> sockaddr_un? {
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let bytes = Array(path.utf8)
        guard bytes.count < MemoryLayout.size(ofValue: addr.sun_path) else { return nil }
        withUnsafeMutableBytes(of: &addr.sun_path) { raw in
            raw.copyBytes(from: bytes)
            raw[bytes.count] = 0
        }
        return addr
    }

    private func acceptClient() {
        let fd = Darwin.accept(listenFD, nil, nil)
        guard fd >= 0 else { return }
        if clientFD >= 0 { Darwin.close(fd); return }
        clientFD = fd
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in self?.readAvailable() }
        source.setCancelHandler { Darwin.close(fd) }
        source.resume()
        readSource = source
        onConnected?()
    }

    private func readAvailable() {
        var chunk = [UInt8](repeating: 0, count: 65_536)
        let count = Darwin.read(clientFD, &chunk, chunk.count)
        if count <= 0 { finish(); return }
        pending.append(contentsOf: chunk[0..<count])
        parse()
    }

    private func parse() {
        while pending.count >= 16 {
            let length = Int(uint32(at: 0))
            guard length > 0, length < 16_000_000 else { finish(); return }
            guard pending.count >= 16 + length else { return }
            let orientation = uint32(at: 12)
            let start = pending.startIndex
            let jpeg = pending.subdata(in: (start + 16)..<(start + 16 + length))
            pending.removeSubrange(start..<(start + 16 + length))
            if let pixels = decode(jpeg) { onFrame?(pixels, Self.rotation(orientation)) }
        }
    }

    private func uint32(at offset: Int) -> UInt32 {
        let s = pending.startIndex + offset
        return (UInt32(pending[s]) << 24) | (UInt32(pending[s + 1]) << 16) | (UInt32(pending[s + 2]) << 8) | UInt32(pending[s + 3])
    }

    private func decode(_ jpeg: Data) -> CVPixelBuffer? {
        guard let image = CIImage(data: jpeg) else { return nil }
        let width = Int(image.extent.width)
        let height = Int(image.extent.height)
        guard width > 0, height > 0 else { return nil }
        var out: CVPixelBuffer?
        let attrs = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        guard CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, attrs, &out) == kCVReturnSuccess,
              let out else { return nil }
        ciContext.render(image, to: out)
        return out
    }

    /// ReplayKit's orientation (CGImagePropertyOrientation) as WebRTC's frame rotation.
    private static func rotation(_ orientation: UInt32) -> RTCVideoRotation {
        switch orientation {
        case 3: return ._180 // down
        case 8: return ._90 // left
        case 6: return ._270 // right
        default: return ._0 // up
        }
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        readSource?.cancel()
        readSource = nil
        acceptSource?.cancel()
        acceptSource = nil
        clientFD = -1
        listenFD = -1
        unlink(path)
        let closed = onClosed
        onClosed = nil
        closed?()
    }
}
