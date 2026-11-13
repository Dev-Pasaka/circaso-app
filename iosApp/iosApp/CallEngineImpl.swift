import AVFoundation
import Shared
import UIKit
import WebRTC

/// Google WebRTC (stasel/WebRTC) behind the shared `CallEngine`: one peer connection to Cloudflare's SFU per call.
/// Audio and video are both pushed from the start and the camera runs only while it is on, so a voice call can turn
/// into video without renegotiating (see CallEngine.kt).
final class CallEngineImpl: NSObject, CallEngineProvider, RTCPeerConnectionDelegate {
    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
    }()

    private var pc: RTCPeerConnection?
    private var audio: RTCAudioTrack?
    private var localVideo: RTCVideoTrack?
    private var videoSource: RTCVideoSource?
    private var capturer: RTCCameraVideoCapturer?
    private var front = true
    private var cameraOn = false
    private var remote: [String: RTCVideoTrack] = [:]
    private var remoteListener: (([String]) -> Void)?
    private var connectionListener: ((KotlinInt) -> Void)?
    private var receivingListener: (([String]) -> Void)?
    private var linkListener: ((String) -> Void)?
    /// Pulled mids whose packets have arrived; see `CallEngine.receivingMids`.
    private var receiving: Set<String> = []
    private var statsTimer: Timer?
    /// Feeds the video track while the camera is off; see `keepVideoAlive`.
    private var keepAliveTimer: Timer?
    private lazy var keepAliveCapturer = RTCVideoCapturer()
    private static let blackFrame: RTCCVPixelBuffer? = {
        var buffer: CVPixelBuffer?
        let attrs = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        guard CVPixelBufferCreate(kCFAllocatorDefault, 160, 90, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, attrs, &buffer) == kCVReturnSuccess,
              let buffer else { return nil }
        CVPixelBufferLockBaseAddress(buffer, [])
        // Video-range black: luma 16, chroma 128.
        for (plane, value) in [(0, UInt8(16)), (1, UInt8(128))] {
            if let base = CVPixelBufferGetBaseAddressOfPlane(buffer, plane) {
                memset(base, Int32(value), CVPixelBufferGetBytesPerRowOfPlane(buffer, plane) * CVPixelBufferGetHeightOfPlane(buffer, plane))
            }
        }
        CVPixelBufferUnlockBaseAddress(buffer, [])
        return RTCCVPixelBuffer(pixelBuffer: buffer)
    }()
    private let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
    /// The screen going out on the video track instead of the camera (ScreenShareImpl feeds it); see `beginScreen`.
    private(set) var screenSharing = false
    /// Whether the camera should be on once the share stops (it was on before, or was turned on during it).
    private var cameraAfterShare = false
    private lazy var screenCapturer = RTCVideoCapturer()

    func start(iceServersJson: String, video: Bool, userId: String, completion: @escaping (String?, String?) -> Void) {
        AVAudioApplication.requestRecordPermission { granted in
            DispatchQueue.main.async {
                guard granted else { completion(nil, "microphone"); return }
                self.open(iceServersJson: iceServersJson, video: video, userId: userId, completion: completion)
            }
        }
    }

    private func open(iceServersJson: String, video: Bool, userId: String, completion: @escaping (String?, String?) -> Void) {
        close()
        configureAudio(speaker: video)
        let config = RTCConfiguration()
        config.iceServers = Self.iceServers(iceServersJson)
        config.sdpSemantics = .unifiedPlan
        config.bundlePolicy = .maxBundle
        guard let connection = Self.factory.peerConnection(with: config, constraints: constraints, delegate: self) else {
            completion(nil, "connection"); return
        }
        pc = connection
        let sendOnly = RTCRtpTransceiverInit()
        sendOnly.direction = .sendOnly
        let mic = Self.factory.audioTrack(with: Self.factory.audioSource(with: constraints), trackId: "audio-\(userId)")
        audio = mic
        let audioT = connection.addTransceiver(with: mic, init: sendOnly)
        let source = Self.factory.videoSource()
        videoSource = source
        let cam = Self.factory.videoTrack(with: source, trackId: "video-\(userId)")
        cam.isEnabled = false
        localVideo = cam
        let videoT = connection.addTransceiver(with: cam, init: sendOnly)
        keepVideoAlive(true)
        if video { setCamera(on: true) { _ in } }
        connection.offer(for: constraints) { sdp, error in
            guard let sdp else { completion(nil, error?.localizedDescription ?? "offer"); return }
            connection.setLocalDescription(sdp) { error in
                DispatchQueue.main.async {
                    if let error { completion(nil, error.localizedDescription); return }
                    let tracks: [[String: String]] = [
                        ["mid": audioT?.mid ?? "0", "trackName": "audio-\(userId)", "kind": "audio"],
                        ["mid": videoT?.mid ?? "1", "trackName": "video-\(userId)", "kind": "video"],
                    ]
                    let body: [String: Any] = ["sdp": connection.localDescription?.sdp ?? sdp.sdp, "tracks": tracks]
                    let data = try? JSONSerialization.data(withJSONObject: body)
                    completion(data.flatMap { String(data: $0, encoding: .utf8) }, data == nil ? "json" : nil)
                }
            }
        }
    }

    func acceptAnswer(sdp: String, completion: @escaping (String?) -> Void) {
        guard let pc else { completion(nil); return }
        pc.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { error in
            DispatchQueue.main.async { completion(error?.localizedDescription) }
        }
    }

    func answerOffer(sdp: String, completion: @escaping (String?, String?) -> Void) {
        guard let pc else { completion(nil, "closed"); return }
        pc.setRemoteDescription(RTCSessionDescription(type: .offer, sdp: sdp)) { error in
            if let error { DispatchQueue.main.async { completion(nil, error.localizedDescription) }; return }
            pc.answer(for: self.constraints) { answer, error in
                guard let answer else { DispatchQueue.main.async { completion(nil, error?.localizedDescription ?? "answer") }; return }
                pc.setLocalDescription(answer) { error in
                    DispatchQueue.main.async {
                        if error == nil { self.watchReceiving(pc) }
                        completion(error == nil ? (pc.localDescription?.sdp ?? answer.sdp) : nil, error?.localizedDescription)
                    }
                }
            }
        }
    }

    /// Polls the inbound RTP stats while a pulled track hasn't had a packet yet, so the call only counts as connected
    /// once the other side is really heard or seen. Quiet once every pulled track is receiving.
    private func watchReceiving(_ connection: RTCPeerConnection) {
        guard statsTimer == nil else { return }
        statsTimer = Timer.scheduledTimer(withTimeInterval: 0.3, repeats: true) { [weak self] _ in
            guard let self else { return }
            guard self.pc === connection else { self.statsTimer?.invalidate(); self.statsTimer = nil; return }
            let live = connection.transceivers.filter { !$0.isStopped && $0.direction == .recvOnly }
            let pending = live.map(\.mid).filter { !self.receiving.contains($0) }
            if pending.isEmpty { return }
            var midOfTrack: [String: String] = [:]
            for t in live { if let id = t.receiver.track?.trackId { midOfTrack[id] = t.mid } }
            connection.statistics { report in
                var heard: Set<String> = []
                for stat in report.statistics.values where stat.type == "inbound-rtp" {
                    let packets = (stat.values["packetsReceived"] as? NSNumber)?.intValue ?? 0
                    guard packets > 0 else { continue }
                    if let mid = stat.values["mid"] as? String { heard.insert(mid) }
                    else if let id = stat.values["trackIdentifier"] as? String, let mid = midOfTrack[id] { heard.insert(mid) }
                }
                DispatchQueue.main.async {
                    guard self.pc === connection, !heard.isSubset(of: self.receiving) else { return }
                    self.receiving.formUnion(heard)
                    self.receivingListener?(Array(self.receiving))
                }
            }
        }
    }

    /// Audio packets received over every pulled track so far (-1 without a connection): the call controller hangs up a
    /// call whose count stops rising, since nobody is sending any more.
    func receivedPackets(completion: @escaping (KotlinLong) -> Void) {
        guard let connection = pc else { completion(KotlinLong(value: -1)); return }
        connection.statistics { report in
            var total: Int64 = 0
            for stat in report.statistics.values where stat.type == "inbound-rtp" && (stat.values["kind"] as? String) == "audio" {
                total += (stat.values["packetsReceived"] as? NSNumber)?.int64Value ?? 0
            }
            DispatchQueue.main.async { completion(KotlinLong(value: total)) }
        }
    }

    /// One stats reading for the call report (2026-11-13), as JSON: RTT from the nominated candidate pair, jitter and
    /// running totals from inbound RTP. nil without a connection.
    func stats(completion: @escaping (String?) -> Void) {
        guard let connection = pc else { completion(nil); return }
        connection.statistics { report in
            let all = Array(report.statistics.values)
            func num(_ s: RTCStatistics, _ key: String) -> NSNumber? { s.values[key] as? NSNumber }
            var out: [String: Any] = [:]
            let pair = all.first { $0.type == "candidate-pair" && (num($0, "nominated")?.boolValue ?? false) && ($0.values["state"] as? String) == "succeeded" }
            if let rtt = pair.flatMap({ num($0, "currentRoundTripTime") })?.doubleValue
                ?? all.first(where: { $0.type == "remote-inbound-rtp" }).flatMap({ num($0, "roundTripTime") })?.doubleValue {
                out["rttMs"] = rtt * 1000
            }
            let inbound = all.filter { $0.type == "inbound-rtp" }
            let audioIn = inbound.filter { ($0.values["kind"] as? String) == "audio" }
            if let jitter = audioIn.compactMap({ num($0, "jitter")?.doubleValue }).max() { out["jitterMs"] = jitter * 1000 }
            out["packetsReceived"] = audioIn.reduce(Int64(0)) { $0 + (num($1, "packetsReceived")?.int64Value ?? 0) }
            out["packetsLost"] = audioIn.reduce(Int64(0)) { $0 + (num($1, "packetsLost")?.int64Value ?? 0) }
            out["bytesReceived"] = inbound.reduce(Int64(0)) { $0 + (num($1, "bytesReceived")?.int64Value ?? 0) }
            let json = (try? JSONSerialization.data(withJSONObject: out)).flatMap { String(data: $0, encoding: .utf8) }
            DispatchQueue.main.async { completion(json) }
        }
    }

    func setLinkListener(listener: @escaping (String) -> Void) { linkListener = listener }

    private static func name(_ s: RTCIceConnectionState) -> String {
        switch s {
        case .new: "new"
        case .checking: "checking"
        case .connected: "connected"
        case .completed: "completed"
        case .failed: "failed"
        case .disconnected: "disconnected"
        case .closed: "closed"
        default: "unknown"
        }
    }

    private static func name(_ s: RTCPeerConnectionState) -> String {
        switch s {
        case .new: "new"
        case .connecting: "connecting"
        case .connected: "connected"
        case .disconnected: "disconnected"
        case .failed: "failed"
        case .closed: "closed"
        default: "unknown"
        }
    }

    func setMicrophone(on: Bool) { audio?.isEnabled = on }

    func setCamera(on: Bool, completion: @escaping (KotlinBoolean) -> Void) {
        // The screen has the track: the camera takes it back, in this state, when the share stops.
        if screenSharing { cameraAfterShare = on; completion(true); return }
        if !on {
            cameraOn = false
            localVideo?.isEnabled = false
            capturer?.stopCapture()
            capturer = nil
            keepVideoAlive(true)
            completion(true)
            return
        }
        if cameraOn { completion(true); return }
        guard let source = videoSource else { completion(false); return }
        AVCaptureDevice.requestAccess(for: .video) { granted in
            DispatchQueue.main.async {
                guard granted else { completion(false); return }
                self.keepVideoAlive(false) // the camera takes over the track from here
                self.cameraOn = true
                self.localVideo?.isEnabled = true
                let cap = RTCCameraVideoCapturer(delegate: source)
                self.capturer = cap
                self.startCapture(cap)
                completion(true)
            }
        }
    }

    private func startCapture(_ cap: RTCCameraVideoCapturer) {
        let position: AVCaptureDevice.Position = front ? .front : .back
        guard let device = RTCCameraVideoCapturer.captureDevices().first(where: { $0.position == position }) ?? RTCCameraVideoCapturer.captureDevices().first else { return }
        // The 720p format closest to what the SFU forwards well, at 30 fps.
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        let format = formats.min(by: { abs(Int(CMVideoFormatDescriptionGetDimensions($0.formatDescription).width) - 1280) < abs(Int(CMVideoFormatDescriptionGetDimensions($1.formatDescription).width) - 1280) }) ?? formats.first
        guard let format else { return }
        let fps = format.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max().map { min($0, 30) } ?? 30
        cap.startCapture(with: device, format: format, fps: Int(fps))
    }

    func flipCamera() {
        front.toggle()
        if let cap = capturer { cap.stopCapture { DispatchQueue.main.async { self.startCapture(cap) } } }
    }

    func setSpeaker(on: Bool) { configureAudio(speaker: on) }

    /// Screen sharing (2026-11-09): the broadcast's frames take the call's one video track from the camera or the
    /// keep-alive, so nothing is renegotiated. Main thread.
    func beginScreen() {
        guard !screenSharing, let source = videoSource else { return }
        cameraAfterShare = cameraOn
        if cameraOn {
            cameraOn = false
            capturer?.stopCapture()
            capturer = nil
        }
        screenSharing = true
        keepVideoAlive(false)
        // About 720p at 15 frames a second: text stays sharp and the upload modest.
        source.adaptOutputFormat(toWidth: 1280, height: 720, fps: 15)
        localVideo?.isEnabled = true
    }

    /// One frame of the shared screen, from any thread.
    func screenFrame(_ buffer: CVPixelBuffer, rotation: RTCVideoRotation) {
        guard screenSharing, let source = videoSource else { return }
        let frame = RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: buffer), rotation: rotation, timeStampNs: Int64(CACurrentMediaTime() * 1_000_000_000))
        source.capturer(screenCapturer, didCapture: frame)
    }

    /// The share is over: the camera (if it should be on) or the black keep-alive takes the track back. Main thread.
    func endScreen() {
        guard screenSharing else { return }
        screenSharing = false
        videoSource?.adaptOutputFormat(toWidth: 1280, height: 720, fps: 30)
        let camera = cameraAfterShare
        cameraAfterShare = false
        if camera {
            setCamera(on: true) { _ in }
        } else {
            localVideo?.isEnabled = false
            keepVideoAlive(true)
        }
    }

    func close() {
        screenSharing = false
        cameraAfterShare = false
        keepVideoAlive(false)
        capturer?.stopCapture()
        capturer = nil
        cameraOn = false
        pc?.close()
        pc = nil
        audio = nil
        localVideo = nil
        videoSource = nil
        remote.removeAll()
        remoteListener?([])
        statsTimer?.invalidate()
        statsTimer = nil
        receiving.removeAll()
        receivingListener?([])
        let session = RTCAudioSession.sharedInstance()
        if !CallKitVoip.shared.ownsAudio { // CallKit deactivates its own session when its call ends
            session.isAudioEnabled = false
            session.lockForConfiguration()
            try? session.setActive(false)
            session.unlockForConfiguration()
        }
    }

    /// While the camera is off, the video track gets a tiny black frame twice a second. Cloudflare's SFU needs media on
    /// a track: a pull of one with none fails after about 8.5 s (`empty_track_error`), and a track with no packets for
    /// 30 s is deleted. Without this, a camera turned on mid-call was never seen by the other side, and every pull
    /// waited 8.5 s for the silent video (2026-11-09). Receivers show the photo while `video` is false, never these.
    private func keepVideoAlive(_ on: Bool) {
        // A Timer only fires on a thread with a run loop: the main one.
        guard Thread.isMainThread else { DispatchQueue.main.async { self.keepVideoAlive(on) }; return }
        keepAliveTimer?.invalidate()
        keepAliveTimer = nil
        guard on, let source = videoSource, let black = Self.blackFrame else { return }
        let capturer = keepAliveCapturer
        keepAliveTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] timer in
            guard let self, self.videoSource === source, !self.cameraOn, !self.screenSharing else { timer.invalidate(); return }
            let frame = RTCVideoFrame(buffer: black, rotation: ._0, timeStampNs: Int64(CACurrentMediaTime() * 1_000_000_000))
            source.capturer(capturer, didCapture: frame)
        }
    }

    func videoView(source: String, mirror: Bool, fill: Bool) -> UIView {
        let view = RTCMTLVideoView(frame: .zero)
        view.videoContentMode = fill ? .scaleAspectFill : .scaleAspectFit
        view.clipsToBounds = true
        if mirror { view.transform = CGAffineTransform(scaleX: -1, y: 1) }
        let track = source == "local" ? localVideo : remote[source]
        track?.add(view)
        return view
    }

    func setRemoteVideoListener(listener: @escaping ([String]) -> Void) { remoteListener = listener }

    func setConnectionListener(listener: @escaping (KotlinInt) -> Void) { connectionListener = listener }

    func setReceivingListener(listener: @escaping ([String]) -> Void) { receivingListener = listener }

    private func configureAudio(speaker: Bool) {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        defer { session.unlockForConfiguration() }
        try? session.setCategory(.playAndRecord, mode: speaker ? .videoChat : .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP])
        // A call CallKit rang: CallKit activates the session and then turns WebRTC's audio on (CallKitVoip).
        // Any other call (one this phone started) turns it on here; WebRTC runs in manual-audio mode.
        if !CallKitVoip.shared.ownsAudio {
            try? session.setActive(true)
            session.isAudioEnabled = true
        }
        try? session.overrideOutputAudioPort(speaker ? .speaker : .none)
    }

    private static func iceServers(_ json: String) -> [RTCIceServer] {
        guard let data = json.data(using: .utf8), let list = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return list.compactMap { item in
            guard let urls = item["urls"] as? [String] else { return nil }
            return RTCIceServer(urlStrings: urls, username: item["username"] as? String, credential: item["credential"] as? String)
        }
    }

    // MARK: RTCPeerConnectionDelegate

    func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        guard let track = transceiver.receiver.track as? RTCVideoTrack else { return }
        let mid = transceiver.mid
        DispatchQueue.main.async {
            self.remote[mid] = track
            self.remoteListener?(Array(self.remote.keys))
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        let state = Self.name(newState)
        DispatchQueue.main.async { self.linkListener?("pc:" + state) }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        let state = Self.name(newState)
        DispatchQueue.main.async { self.linkListener?("ice:" + state) }
        let up: Int32? = switch newState {
        case .connected, .completed: 1
        case .disconnected, .failed: 0
        default: nil
        }
        guard let up else { return }
        DispatchQueue.main.async { self.connectionListener?(KotlinInt(int: up)) }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
