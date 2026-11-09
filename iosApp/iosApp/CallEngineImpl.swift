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
    /// Pulled mids whose packets have arrived; see `CallEngine.receivingMids`.
    private var receiving: Set<String> = []
    private var statsTimer: Timer?
    private let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)

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

    func setMicrophone(on: Bool) { audio?.isEnabled = on }

    func setCamera(on: Bool, completion: @escaping (KotlinBoolean) -> Void) {
        if !on {
            cameraOn = false
            localVideo?.isEnabled = false
            capturer?.stopCapture()
            capturer = nil
            completion(true)
            return
        }
        if cameraOn { completion(true); return }
        guard let source = videoSource else { completion(false); return }
        AVCaptureDevice.requestAccess(for: .video) { granted in
            DispatchQueue.main.async {
                guard granted else { completion(false); return }
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

    func close() {
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
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
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
