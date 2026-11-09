import CoreImage
import ReplayKit
import UIKit

/// Circaso's Broadcast Upload Extension (2026-11-09): screen sharing on a call, also after the person leaves Circaso.
///
/// ReplayKit hands this extension the whole screen; it sends each frame (at most 15 a second, the long side at most
/// 1280, JPEG) over a Unix socket in the App Group container to the app, which feeds the call's video track
/// (`iosApp/ScreenShareImpl.swift`). Nothing leaves the phone from here: the call sends it, through its own encrypted
/// connection. Each message is a 16-byte header (JPEG length, width, height, CGImagePropertyOrientation, each a
/// big-endian UInt32) and then the JPEG. The app asks it to finish with the Darwin notification
/// `com.circaso.app.broadcast.stop` (the call's Stop, or the call ending).
///
/// NOT YET IN THE XCODE PROJECT. Until it is, the app hides Share screen on iOS (ScreenShareImpl.available). To add it
/// (Xcode 16, open iosApp.xcworkspace, never the .xcodeproj):
///  1. File > New > Target… > iOS > Broadcast Upload Extension. Product Name `CircasoBroadcast`, Team as iosApp,
///     Language Swift, untick "Include UI Extension", Embed in Application `iosApp`. When Xcode offers to activate the
///     scheme, choose Cancel.
///  2. Delete the files Xcode generated for the target (SampleHandler.swift, Info.plist) and add this folder's three
///     files to the CircasoBroadcast target instead (target membership: CircasoBroadcast only).
///  3. CircasoBroadcast target > Build Settings: Info.plist File = `CircasoBroadcast/Info.plist`; Code Signing
///     Entitlements = `CircasoBroadcast/CircasoBroadcast.entitlements`; iOS Deployment Target = the app's; Bundle
///     Identifier = `<app bundle id>.broadcast` (the app preselects exactly this id in the picker).
///  4. Signing & Capabilities: App Groups with `group.com.circaso.app` on BOTH iosApp and CircasoBroadcast (iosApp's
///     entitlements file gains `com.apple.security.application-groups`; the Share Extension uses the same group), and
///     register the group and the extension's App ID in the developer portal so the profiles include them.
///  5. Podfile: the extension links no pods; if `pod install` complains, add an empty `target 'CircasoBroadcast' do end`.
///  6. Build iosApp for a device from the workspace (ReplayKit broadcasts don't run in the simulator), start a call and
///     tap Share screen: the system picker opens with Circaso selected, Start Broadcast, and the other phone sees it.
final class SampleHandler: RPBroadcastSampleHandler {
    private static let appGroup = "group.com.circaso.app"
    private static let socketName = "screen.sock"
    private static let stopNotification = "com.circaso.app.broadcast.stop"
    private static let maxFps: Double = 15
    private static let longSide: CGFloat = 1280

    private var fd: Int32 = -1
    private let context = CIContext(options: [.useSoftwareRenderer: false])
    private let lock = NSLock()
    private var lastSent: CFTimeInterval = 0
    private var sending = false

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        observeStop()
        guard connect() else {
            finish("Start sharing your screen from a Circaso call.")
            return
        }
    }

    override func broadcastFinished() {
        disconnect()
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let now = CACurrentMediaTime()
        lock.lock()
        // One frame at a time and at most maxFps: a slow moment drops frames instead of piling them up in memory
        // (an extension is stopped past about 50 MB).
        guard fd >= 0, !sending, now - lastSent >= 1 / Self.maxFps else { lock.unlock(); return }
        sending = true
        lastSent = now
        lock.unlock()
        defer { lock.lock(); sending = false; lock.unlock() }

        let orientation = (CMGetAttachment(sampleBuffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber)?.uint32Value ?? 1
        var image = CIImage(cvPixelBuffer: pixels)
        let scale = min(1, Self.longSide / max(image.extent.width, image.extent.height))
        if scale < 1 { image = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale)) }
        let options = [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.6]
        guard let jpeg = context.jpegRepresentation(of: image, colorSpace: CGColorSpaceCreateDeviceRGB(), options: options) else { return }
        var message = Data(capacity: 16 + jpeg.count)
        for value in [UInt32(jpeg.count), UInt32(image.extent.width), UInt32(image.extent.height), orientation] {
            var big = value.bigEndian
            withUnsafeBytes(of: &big) { message.append(contentsOf: $0) }
        }
        message.append(jpeg)
        if !write(message) {
            finish("Screen sharing stopped because the call ended.")
        }
    }

    private func connect() -> Bool {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup) else { return false }
        let path = container.appendingPathComponent(Self.socketName).path
        let socketFD = socket(AF_UNIX, SOCK_STREAM, 0)
        guard socketFD >= 0 else { return false }
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let bytes = Array(path.utf8)
        guard bytes.count < MemoryLayout.size(ofValue: addr.sun_path) else { Darwin.close(socketFD); return false }
        withUnsafeMutableBytes(of: &addr.sun_path) { raw in
            raw.copyBytes(from: bytes)
            raw[bytes.count] = 0
        }
        var on: Int32 = 1
        setsockopt(socketFD, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        let size = socklen_t(MemoryLayout<sockaddr_un>.size)
        let connected = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(socketFD, $0, size) }
        }
        guard connected == 0 else { Darwin.close(socketFD); return false }
        lock.lock()
        fd = socketFD
        lock.unlock()
        return true
    }

    /// Writes all of [data]; false once the app's end is gone.
    private func write(_ data: Data) -> Bool {
        lock.lock()
        let socketFD = fd
        lock.unlock()
        guard socketFD >= 0 else { return false }
        return data.withUnsafeBytes { raw -> Bool in
            guard var p = raw.baseAddress else { return false }
            var left = raw.count
            while left > 0 {
                let n = Darwin.write(socketFD, p, left)
                if n <= 0 { return false }
                left -= n
                p = p.advanced(by: n)
            }
            return true
        }
    }

    private func disconnect() {
        lock.lock()
        let socketFD = fd
        fd = -1
        lock.unlock()
        if socketFD >= 0 { Darwin.close(socketFD) }
        CFNotificationCenterRemoveEveryObserver(CFNotificationCenterGetDarwinNotifyCenter(), Unmanaged.passUnretained(self).toOpaque())
    }

    private func finish(_ reason: String) {
        disconnect()
        finishBroadcastWithError(NSError(domain: "com.circaso.broadcast", code: 0, userInfo: [NSLocalizedDescriptionKey: reason]))
    }

    /// The app's Stop: ReplayKit only ends a broadcast from inside the extension.
    private func observeStop() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let observer = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterAddObserver(center, observer, { _, observer, _, _, _ in
            guard let observer else { return }
            let handler = Unmanaged<SampleHandler>.fromOpaque(observer).takeUnretainedValue()
            handler.finish("You stopped sharing your screen.")
        }, Self.stopNotification as CFString, nil, .deliverImmediately)
    }
}
