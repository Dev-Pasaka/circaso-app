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

    /// A connection `prepare` built ahead of the call (2026-11-13): who for, with which ICE servers, and its offer.
    private struct Prepared { let connection: RTCPeerConnection; let userId: String; let ice: String; let offer: String }
    private var prepared: Prepared?
    /// Called once when the connection being built may stop gathering candidates ("complete", "srflx" or "timeout").
    private var gatherWaiter: ((String) -> Void)?

    func start(iceServersJson: String, video: Bool, userId: String, completion: @escaping (String?, String?) -> Void) {
        AVAudioApplication.requestRecordPermission { granted in
            DispatchQueue.main.async {
                guard granted else { completion(nil, "microphone"); return }
                if let ready = self.prepared, ready.userId == userId, ready.ice == iceServersJson, self.pc === ready.connection {
                    self.prepared = nil
                    self.linkListener?("offer:prepared")
                    self.begin(video: video)
                    completion(ready.offer, nil)
                    return
                }
                let t0 = CACurrentMediaTime()
                self.build(iceServersJson: iceServersJson, userId: userId) { offer, error in
                    if offer != nil {
                        self.linkListener?("offer:fresh \(Int((CACurrentMediaTime() - t0) * 1000))ms")
                        self.begin(video: video)
                    }
                    completion(offer, error)
                }
            }
        }
    }

    /// Gets a call ready while it rings or while CL1 is on its way (see CallEngine.prepare): the connection and its
    /// offer, with no audio session, camera or permission prompt. Main thread.
    func prepare(iceServersJson: String, video: Bool, userId: String, completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard AVAudioApplication.shared.recordPermission == .granted else { completion("microphone"); return }
            if let ready = self.prepared, ready.userId == userId, ready.ice == iceServersJson, self.pc === ready.connection { completion(nil); return }
            self.build(iceServersJson: iceServersJson, userId: userId) { _, error in completion(error) }
        }
    }

    /// The call really starts: its audio session and, for video, the camera.
    private func begin(video: Bool) {
        configureAudio(speaker: video)
        if video { setCamera(on: true) { _ in } }
    }

    /// A fresh connection with the microphone's and the camera's tracks (camera off, keep-alive on), its offer set and
    /// its candidates gathered. Nothing is captured (WebRTC's audio is manual) or sent until the SFU answers.
    private func build(iceServersJson: String, userId: String, completion: @escaping (String?, String?) -> Void) {
        close()
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
        connection.offer(for: constraints) { sdp, error in
            guard let sdp else { DispatchQueue.main.async { completion(nil, error?.localizedDescription ?? "offer") }; return }
            connection.setLocalDescription(sdp) { error in
                DispatchQueue.main.async {
                    if let error { completion(nil, error.localizedDescription); return }
                    self.awaitCandidates {
                        guard self.pc === connection else { completion(nil, "closed"); return }
                        let tracks: [[String: String]] = [
                            ["mid": audioT?.mid ?? "0", "trackName": "audio-\(userId)", "kind": "audio"],
                            ["mid": videoT?.mid ?? "1", "trackName": "video-\(userId)", "kind": "video"],
                        ]
                        let body: [String: Any] = ["sdp": connection.localDescription?.sdp ?? sdp.sdp, "tracks": tracks]
                        let data = try? JSONSerialization.data(withJSONObject: body)
                        let offer = data.flatMap { String(data: $0, encoding: .utf8) }
                        if let offer { self.prepared = Prepared(connection: connection, userId: userId, ice: iceServersJson, offer: offer) }
                        completion(offer, offer == nil ? "json" : nil)
                    }
                }
            }
        }
    }

    /// Cloudflare's order is to gather candidates before the offer goes; the offer used to go with none and every
    /// connection took ~3.1 s to come up (2026-11-13). Waits for gathering to complete or a server-reflexive
    /// candidate, at most half a second. Main thread.
    private func awaitCandidates(_ then: @escaping () -> Void) {
        let t0 = CACurrentMediaTime()
        var fired = false
        let fire: (String) -> Void = { how in
            guard !fired else { return }
            fired = true
            self.gatherWaiter = nil
            self.linkListener?("gather:\(how) \(Int((CACurrentMediaTime() - t0) * 1000))ms")
            then()
        }
        if pc?.iceGatheringState == .complete { fire("complete"); return }
        gatherWaiter = fire
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { fire("timeout") }
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

    /// The liveness reading (2026-11-13): RTP packets per pulled mid, and everything the connection received (the
    /// transports' bytes plus the ICE answers on every candidate pair), as JSON. nil without a connection.
    func link(completion: @escaping (String?) -> Void) {
        guard let connection = pc else { completion(nil); return }
        var midOfTrack: [String: String] = [:]
        for t in connection.transceivers { if let id = t.receiver.track?.trackId { midOfTrack[id] = t.mid } }
        connection.statistics { report in
            var mids: [String: Int64] = [:]
            var transport: Int64 = 0
            var any = false
            for stat in report.statistics.values {
                switch stat.type {
                case "inbound-rtp":
                    let mid = (stat.values["mid"] as? String) ?? (stat.values["trackIdentifier"] as? String).flatMap { midOfTrack[$0] }
                    guard let mid else { continue }
                    mids[mid, default: 0] += (stat.values["packetsReceived"] as? NSNumber)?.int64Value ?? 0
                case "transport":
                    if let n = stat.values["bytesReceived"] as? NSNumber { transport += n.int64Value; any = true }
                case "candidate-pair":
                    if let n = stat.values["responsesReceived"] as? NSNumber { transport += n.int64Value; any = true }
                default: break
                }
            }
            var out: [String: Any] = ["mids": mids]
            if any { out["transport"] = transport }
            let json = (try? JSONSerialization.data(withJSONObject: out)).flatMap { String(data: $0, encoding: .utf8) }
            DispatchQueue.main.async { completion(json) }
        }
    }

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

    private func startCapture(_ cap: RTCCameraVideoCapturer, started: (() -> Void)? = nil) {
        let position: AVCaptureDevice.Position = front ? .front : .back
        guard let device = RTCCameraVideoCapturer.captureDevices().first(where: { $0.position == position }) ?? RTCCameraVideoCapturer.captureDevices().first else { return }
        // The 720p format closest to what the SFU forwards well, at 30 fps.
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        let format = formats.min(by: { abs(Int(CMVideoFormatDescriptionGetDimensions($0.formatDescription).width) - 1280) < abs(Int(CMVideoFormatDescriptionGetDimensions($1.formatDescription).width) - 1280) }) ?? formats.first
        guard let format else { return }
        let fps = format.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max().map { min($0, 30) } ?? 30
        cap.startCapture(with: device, format: format, fps: Int(fps)) { _ in
            if let started { DispatchQueue.main.async(execute: started) }
        }
    }

    func flipCamera() {
        front.toggle()
        // The self-view stays blurred over the last frame until the other camera is capturing (IosCallEngine's tile).
        if let cap = capturer {
            cap.stopCapture {
                DispatchQueue.main.async {
                    self.startCapture(cap) { NotificationCenter.default.post(name: Notification.Name("CircasoCallCameraStarted"), object: nil) }
                }
            }
        }
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
        prepared = nil
        gatherWaiter = nil
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
        let view = CallVideoView(frame: .zero)
        view.videoContentMode = fill ? .scaleAspectFill : .scaleAspectFit
        view.clipsToBounds = true
        // No backdrop of its own: the tile around it is rounded, and anything square here would show at its corners.
        view.backgroundColor = .clear
        view.isOpaque = false
        if mirror { view.transform = CGAffineTransform(scaleX: -1, y: 1) }
        let track = source == "local" ? localVideo : remote[source]
        view.track = track
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
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        guard newState == .complete else { return }
        DispatchQueue.main.async { if self.pc === peerConnection { self.gatherWaiter?("complete") } }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        guard candidate.sdp.contains(" typ srflx") else { return }
        DispatchQueue.main.async { if self.pc === peerConnection { self.gatherWaiter?("srflx") } }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    /// When ICE picked its path (2026-11-14), to tell ICE's share of `ice:checking` → `pc:connected` (~3.1 s) from DTLS's.
    func peerConnection(_ peerConnection: RTCPeerConnection, didChangeLocalCandidate local: RTCIceCandidate, remoteCandidate remote: RTCIceCandidate, lastReceivedMs: Int32, changeReason reason: String) {
        let parts = local.sdp.components(separatedBy: " ")
        let type = parts.firstIndex(of: "typ").flatMap { $0 + 1 < parts.count ? parts[$0 + 1] : nil } ?? "?"
        let proto = parts.count > 2 ? parts[2].lowercased() : "?"
        DispatchQueue.main.async { self.linkListener?("pair:\(type) \(proto)") }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

/// A call tile's renderer. It takes frames from its track only while it is on screen, so a tile Compose has let go of
/// stops drawing instead of rendering every frame into nowhere until the call ends.
private final class CallVideoView: RTCMTLVideoView {
    weak var track: RTCVideoTrack?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { track?.remove(self) } else { track?.add(self) }
    }
}
