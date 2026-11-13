import AVFoundation
import QuartzCore
import Shared

/// Live voice with Asha: the microphone as Opus packets (16 kHz mono, 20 ms, 24 kbit/s) and her voice played back as
/// it streams. One `AVAudioEngine` carries both, with voice processing on so her voice from the speaker is cancelled
/// out of the microphone (talking over her must not hear her). Installed at launch; see LiveAudioBridge.kt.
final class LiveAudioImpl: NSObject, LiveAudioBridgeApi {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let work = DispatchQueue(label: "com.circaso.asha.audio", qos: .userInteractive)

    private let pcm16k = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
    private let opusFormat: AVAudioFormat? = AVAudioFormat(settings: [
        AVFormatIDKey: kAudioFormatOpus,
        AVSampleRateKey: 16_000,
        AVNumberOfChannelsKey: 1,
    ])
    private var resampler: AVAudioConverter?
    private var encoder: AVAudioConverter?
    private var pending: [Float] = []
    private var micRunning = false
    private var muted = false
    private var onPacket: ((Data) -> Void)?
    private var onMicLevel: ((Float) -> Void)?
    private var onMicFrame: ((Data) -> Void)?
    private var sinceLevel = 0

    private var speakerFormat: AVAudioFormat?
    private var speakerRunning = false
    private var queued = 0
    private var onPlaying: ((Bool) -> Void)?
    private var onVoiceLevel: ((Float) -> Void)?
    /// The echo guard's reference: each 20 ms of her voice, when it will be heard (ms from now) and for how long.
    private var onBlock: ((Float, Int, Int) -> Void)?
    private var onRoute: ((Int) -> Void)?
    private var routeObserver: NSObjectProtocol?
    /// When what is scheduled on the player finishes (CACurrentMediaTime), so the next buffer's start is known.
    private var queuedUntil: CFTimeInterval = 0
    private var playerVolume: Float = 1
    /// The first chunk of her voice since the speaker started or was flushed: when it came, and whether it was heard.
    private var firstChunkAt: CFTimeInterval?
    private var firstHeard = false
    /// When the engine was last rewired, so a run of configuration changes can't loop forever.
    private var rewires: [CFTimeInterval] = []
    private var engineObservers: [NSObjectProtocol] = []
    private var lastOutputs = ""

    private static let frame = 320 // 20 ms at 16 kHz: one Opus packet

    override init() {
        super.init()
        observeEngine()
    }

    var opusSupported: Bool {
        guard let opus = opusFormat else { return false }
        return AVAudioConverter(from: pcm16k, to: opus) != nil
    }

    // MARK: microphone

    func startMicrophone(onPacket: @escaping (Data) -> Void, onLevel: @escaping (KotlinFloat) -> Void, completion: @escaping @Sendable (KotlinInt) -> Void) {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: beginMicrophone(onPacket: onPacket, onLevel: onLevel, completion: completion)
        case .denied: completion(1)
        default:
            AVAudioApplication.requestRecordPermission { allowed in
                DispatchQueue.main.async {
                    if allowed { self.beginMicrophone(onPacket: onPacket, onLevel: onLevel, completion: completion) } else { completion(1) }
                }
            }
        }
    }

    private func beginMicrophone(onPacket: @escaping (Data) -> Void, onLevel: @escaping (KotlinFloat) -> Void, completion: (KotlinInt) -> Void) {
        guard let opus = opusFormat, let encoder = AVAudioConverter(from: pcm16k, to: opus) else { return completion(2) }
        encoder.bitRate = 24_000
        do {
            try prepareEngine()
        } catch {
            note("microphone: session failed (\((error as NSError).code))")
            return completion(3)
        }
        work.sync {
            self.encoder = encoder
            self.onPacket = onPacket
            self.onMicLevel = { onLevel(KotlinFloat(value: $0)) }
        }
        guard installMicTap() else { return completion(3) }
        micRunning = true
        do {
            if !engine.isRunning { try engine.start() }
            // The engine may have been stopped under the speaker (a configuration change): its player plays again.
            if speakerRunning && !player.isPlaying { player.play() }
        } catch {
            engine.inputNode.removeTap(onBus: 0)
            micRunning = false
            note("microphone: engine failed (\((error as NSError).code))")
            return completion(3)
        }
        completion(0)
    }

    /// The tap on the (echo-cancelled) input in the format it has now, and a resampler from that format to 16 kHz.
    private func installMicTap() -> Bool {
        let input = engine.inputNode
        let tapFormat = input.outputFormat(forBus: 0)
        guard tapFormat.sampleRate > 0, let resampler = AVAudioConverter(from: tapFormat, to: pcm16k) else {
            note("microphone: no usable input format (\(tapFormat.sampleRate) Hz)")
            return false
        }
        work.sync {
            self.resampler = resampler
            self.pending.removeAll()
        }
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1_024, format: tapFormat) { [weak self] buffer, _ in
            self?.work.async { self?.capture(buffer) }
        }
        return true
    }

    /// Resamples what the microphone gives to 16 kHz, cuts it into 20 ms frames and encodes each one.
    private func capture(_ buffer: AVAudioPCMBuffer) {
        guard micRunning, let resampler = resampler else { return }
        let ratio = pcm16k.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 32
        guard let out = AVAudioPCMBuffer(pcmFormat: pcm16k, frameCapacity: capacity) else { return }
        var served = false
        var error: NSError?
        resampler.convert(to: out, error: &error) { _, status in
            if served { status.pointee = .noDataNow; return nil }
            served = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, out.frameLength > 0, let channel = out.floatChannelData?[0] else { return }
        pending.append(contentsOf: UnsafeBufferPointer(start: channel, count: Int(out.frameLength)))
        while pending.count >= Self.frame {
            let frame = Array(pending[0..<Self.frame])
            pending.removeFirst(Self.frame)
            sinceLevel += 1
            if sinceLevel >= 3 { sinceLevel = 0; onMicLevel?(Self.level(frame)) }
            if let onMicFrame = onMicFrame { onMicFrame(Self.pcm16(frame)) }
            encode(frame)
        }
    }

    private func encode(_ samples: [Float]) {
        guard let encoder = encoder, let opus = opusFormat, let input = AVAudioPCMBuffer(pcmFormat: pcm16k, frameCapacity: AVAudioFrameCount(samples.count)) else { return }
        input.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { src in input.floatChannelData?[0].update(from: src.baseAddress!, count: samples.count) }
        let out = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: encoder.maximumOutputPacketSize)
        var served = false
        var error: NSError?
        let status = encoder.convert(to: out, error: &error) { _, outStatus in
            if served { outStatus.pointee = .noDataNow; return nil }
            served = true
            outStatus.pointee = .haveData
            return input
        }
        guard status != .error, out.packetCount > 0, out.byteLength > 0, !muted else { return }
        onPacket?(Data(bytes: out.data, count: Int(out.byteLength)))
    }

    func setMicrophoneMuted(muted: Bool) { work.async { self.muted = muted } }

    /// The voice activity detector's copy of the echo-cancelled microphone (16 kHz, 16-bit), muted or not.
    func setMicrophoneFrames(onFrame: ((Data) -> Void)?) { work.async { self.onMicFrame = onFrame } }

    private static func pcm16(_ samples: [Float]) -> Data {
        var out = [Int16](repeating: 0, count: samples.count)
        for i in 0..<samples.count { out[i] = Int16(max(-1, min(1, samples[i])) * 32_767) }
        return out.withUnsafeBufferPointer { Data(buffer: $0) }
    }

    func stopMicrophone() {
        work.async {
            self.micRunning = false
            self.engine.inputNode.removeTap(onBus: 0)
            self.encoder = nil
            self.resampler = nil
            self.pending.removeAll()
            self.onPacket = nil
            self.onMicLevel = nil
            self.onMicFrame = nil
            self.stopEngineIfIdle()
        }
    }

    // MARK: speaker

    func startSpeaker(sampleRate: Int32, onPlaying: @escaping (KotlinBoolean) -> Void, onLevel: @escaping (KotlinFloat) -> Void) -> Bool {
        guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(sampleRate), channels: 1, interleaved: false) else { return false }
        do {
            try prepareEngine()
        } catch {
            note("speaker: session failed (\((error as NSError).code))")
            return false
        }
        if player.engine == nil { engine.attach(player) }
        engine.disconnectNodeOutput(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        speakerFormat = format
        self.onPlaying = { onPlaying(KotlinBoolean(value: $0)) }
        self.onVoiceLevel = { onLevel(KotlinFloat(value: $0)) }
        queued = 0
        queuedUntil = 0
        firstChunkAt = nil
        firstHeard = false
        player.volume = 1
        playerVolume = 1
        do {
            if !engine.isRunning { try engine.start() }
        } catch {
            note("speaker: engine failed (\((error as NSError).code))")
            return false
        }
        player.play()
        speakerRunning = true
        lastOutputs = AVAudioSession.sharedInstance().currentRoute.outputs.map { $0.portType.rawValue }.joined(separator: "+")
        note("speaker on, \(sampleRate) Hz: \(describe())")
        return true
    }

    func playSamples(pcm16: Data) {
        guard speakerRunning, let format = speakerFormat else { return }
        let count = pcm16.count / 2
        guard count > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(count)) else { return }
        buffer.frameLength = AVAudioFrameCount(count)
        var sum: Double = 0
        pcm16.withUnsafeBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            if let channel = buffer.floatChannelData?[0] {
                for i in 0..<count {
                    let s = Float(Int16(littleEndian: samples[i])) / 32768
                    channel[i] = s
                    sum += Double(s * s)
                }
            }
        }
        let level = Self.level(rms: sqrt(sum / Double(count)))
        // 20 ms slices of her voice for the echo guard: their loudness, as played (after the volume)
        let step = max(1, Int(format.sampleRate) / 50)
        var slices: [Float] = []
        if let channel = buffer.floatChannelData?[0] {
            var at = 0
            while at < count {
                let n = min(step, count - at)
                var e: Double = 0
                for i in at..<(at + n) { e += Double(channel[i] * channel[i]) }
                slices.append(Float(20 * log10(max(sqrt(e / Double(n)), 1e-5))))
                at += n
            }
        }
        let duration = Double(count) / format.sampleRate
        work.async {
            let first = self.firstChunkAt == nil
            if first {
                let at = CACurrentMediaTime()
                self.firstChunkAt = at
                self.note("her first chunk in: \(self.describe())")
                // Scheduled but never heard is the silent failure: say so, and wire the engine up again.
                self.work.asyncAfter(deadline: .now() + 1.5) {
                    guard self.speakerRunning, self.firstChunkAt == at, !self.firstHeard else { return }
                    self.note("her first chunk not heard after 1.5 s (\(self.queued) queued): \(self.describe())")
                    DispatchQueue.main.async { self.rewire("nothing heard") }
                }
            }
            if let onBlock = self.onBlock {
                let now = CACurrentMediaTime()
                let session = AVAudioSession.sharedInstance()
                let start = max(now + session.outputLatency + session.ioBufferDuration, self.queuedUntil)
                let gain = Float(20 * log10(Double(max(self.playerVolume, 0.01))))
                for (i, db) in slices.enumerated() {
                    onBlock(db + gain, Int(((start - now) + Double(i * step) / format.sampleRate) * 1_000), 20)
                }
                self.queuedUntil = start + duration
            }
            self.queued += 1
            self.onPlaying?(true)
            self.onVoiceLevel?(level)
            self.player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                self?.work.async {
                    guard let self = self else { return }
                    if first, !self.firstHeard, let at = self.firstChunkAt {
                        self.firstHeard = true
                        self.note("her first chunk heard after \(Int((CACurrentMediaTime() - at) * 1_000)) ms")
                    }
                    self.queued = max(0, self.queued - 1)
                    if self.queued == 0 { self.onPlaying?(false); self.onVoiceLevel?(0) }
                }
            }
        }
    }

    func flushSpeaker() {
        work.async {
            guard self.speakerRunning else { return }
            self.player.stop()
            self.queued = 0
            self.queuedUntil = 0
            self.firstChunkAt = nil // the next reply's first chunk is reported again
            self.firstHeard = false
            self.onPlaying?(false)
            self.onVoiceLevel?(0)
            self.player.play()
        }
    }

    /// Ducking while the person may be talking over her. The shared code ramps it in ~16 ms steps; the player's
    /// volume applies to what is already scheduled, so the dip is heard at once.
    func setSpeakerVolume(volume: Float) {
        work.async {
            self.playerVolume = min(max(volume, 0), 1)
            self.player.volume = self.playerVolume
        }
    }

    func stopSpeaker() {
        work.async {
            self.speakerRunning = false
            self.player.stop()
            self.queued = 0
            self.queuedUntil = 0
            self.onPlaying = nil
            self.onVoiceLevel = nil
            self.onBlock = nil
            self.onRoute = nil
            if let observer = self.routeObserver { NotificationCenter.default.removeObserver(observer) }
            self.routeObserver = nil
            self.stopEngineIfIdle()
        }
    }

    /// The echo guard's reference and the audio route (0 loudspeaker, 1 receiver, 2 headphones or a headset), the
    /// route at once and again on every change (headphones plugged in or a Bluetooth headset connected mid-call).
    func observeSpeaker(onBlock: @escaping (KotlinFloat, KotlinInt, KotlinInt) -> Void, onRoute: @escaping (KotlinInt) -> Void) {
        work.async {
            self.onBlock = { onBlock(KotlinFloat(value: $0), KotlinInt(value: Int32($1)), KotlinInt(value: Int32($2))) }
            let report: () -> Void = { onRoute(KotlinInt(value: Int32(Self.route()))) }
            self.onRoute = { _ in report() }
            if let observer = self.routeObserver { NotificationCenter.default.removeObserver(observer) }
            self.routeObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { [weak self] _ in
                self?.work.async { self?.onRoute?(0) }
            }
            report()
        }
    }

    private static func route() -> Int {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        let headsets: Set<AVAudioSession.Port> = [.headphones, .bluetoothA2DP, .bluetoothHFP, .bluetoothLE, .usbAudio]
        if outputs.contains(where: { headsets.contains($0.portType) }) { return 2 }
        if outputs.contains(where: { $0.portType == .builtInReceiver }) { return 1 }
        return 0
    }

    // MARK: engine and session

    /// A call-style session: play and record, voice processing (echo cancellation), the loudspeaker by default.
    private func prepareEngine() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setPreferredSampleRate(16_000)
        try session.setActive(true)
        if !engine.isRunning && !engine.inputNode.isVoiceProcessingEnabled {
            try? engine.inputNode.setVoiceProcessingEnabled(true)
        }
        if player.engine == nil { engine.attach(player) }
    }

    private func stopEngineIfIdle() {
        guard !micRunning, !speakerRunning else { return }
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// What the next live test needs to explain itself: the session, the route and the engine, no personal data.
    private func describe() -> String {
        let session = AVAudioSession.sharedInstance()
        let outputs = session.currentRoute.outputs.map { $0.portType.rawValue }.joined(separator: "+")
        let category = session.category.rawValue.replacingOccurrences(of: "AVAudioSessionCategory", with: "")
        let mode = session.mode.rawValue.replacingOccurrences(of: "AVAudioSessionMode", with: "")
        let out = engine.outputNode.outputFormat(forBus: 0)
        return "\(category)/\(mode) route=\(outputs) hw=\(Int(session.sampleRate)) Hz engine=\(engine.isRunning ? "on" : "off") player=\(player.isPlaying ? "on" : "off") out=\(Int(out.sampleRate)) Hz/\(out.channelCount) ch"
    }

    private func note(_ message: String) {
        Diagnostics.shared.note(message: "voice audio: \(message)")
    }

    /// A configuration change (the hardware format, voice processing coming on, a route with another sample rate) stops
    /// the engine and every player on it, and nothing scheduled after is ever heard. It happens on the very first
    /// start: the engine is made at launch while the session is still SoloAmbient at 44.1 kHz, and voice processing
    /// brings the hardware up at 48 kHz (seen on the iPhone SE, iOS 26.5, 2026-11-14). An interruption that ends (a
    /// phone call, Siri) and a media services reset need the same: wire the engine up again and carry on.
    private func observeEngine() {
        let center = NotificationCenter.default
        engineObservers = [
            center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
                self?.rewire("configuration changed")
            },
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
                guard let self, self.micRunning || self.speakerRunning else { return }
                let type = (n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
                if type == .began { self.note("interrupted") } else { self.rewire("interruption ended") }
            },
            center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
                self?.rewire("media services reset")
            },
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] n in
                guard let self, self.micRunning || self.speakerRunning else { return }
                // only a different set of outputs (AirPods connected, the receiver): category changes repeat a lot
                let outputs = AVAudioSession.sharedInstance().currentRoute.outputs.map { $0.portType.rawValue }.joined(separator: "+")
                guard outputs != self.lastOutputs else { return }
                self.lastOutputs = outputs
                let reason = (n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) ?? 0
                self.note("route changed (reason \(reason)): \(self.describe())")
            },
        ]
    }

    /// Stops the engine, connects the output chain, her player and the microphone's tap in the formats the hardware
    /// has now, and starts it all again. On the main thread, like the starts. What was queued of her voice is dropped.
    private func rewire(_ reason: String) {
        guard micRunning || speakerRunning else { return }
        let now = CACurrentMediaTime()
        rewires = rewires.filter { now - $0 < 10 } + [now]
        guard rewires.count <= 5 else { return note("\(reason): not rewired, too many changes in 10 s") }
        engine.stop()
        do {
            try prepareEngine()
            engine.disconnectNodeInput(engine.outputNode)
            engine.connect(engine.mainMixerNode, to: engine.outputNode, format: nil)
            if speakerRunning, let format = speakerFormat {
                engine.disconnectNodeOutput(player)
                engine.connect(player, to: engine.mainMixerNode, format: format)
            }
            if micRunning, !installMicTap() { micRunning = false }
            engine.prepare()
            try engine.start()
            if speakerRunning { player.play() }
            work.async {
                self.queued = 0
                self.queuedUntil = 0
                self.firstChunkAt = nil
                self.firstHeard = false
                self.onPlaying?(false)
                self.onVoiceLevel?(0)
            }
            note("\(reason), engine rewired: \(describe())")
        } catch {
            note("\(reason), rewiring failed (\((error as NSError).code)): \(describe())")
        }
    }

    private static func level(_ samples: [Float]) -> Float {
        var sum: Double = 0
        for s in samples { sum += Double(s * s) }
        return level(rms: sqrt(sum / Double(max(samples.count, 1))))
    }

    /// The same mapping as the shared `AudioLevel`: -50 dBFS is silence, -10 dBFS fills the halo.
    private static func level(rms: Double) -> Float {
        let db = 20 * log10(max(rms, 1e-6))
        return Float(min(max((db + 50) / 40, 0), 1))
    }
}
