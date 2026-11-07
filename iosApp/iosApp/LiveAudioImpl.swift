import AVFoundation
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
    private var sinceLevel = 0

    private var speakerFormat: AVAudioFormat?
    private var speakerRunning = false
    private var queued = 0
    private var onPlaying: ((Bool) -> Void)?
    private var onVoiceLevel: ((Float) -> Void)?

    private static let frame = 320 // 20 ms at 16 kHz: one Opus packet

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
            return completion(3)
        }
        let input = engine.inputNode
        let tapFormat = input.outputFormat(forBus: 0)
        guard tapFormat.sampleRate > 0, let resampler = AVAudioConverter(from: tapFormat, to: pcm16k) else { return completion(3) }
        self.encoder = encoder
        self.resampler = resampler
        self.onPacket = onPacket
        self.onMicLevel = { onLevel(KotlinFloat(value: $0)) }
        pending.removeAll()
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1_024, format: tapFormat) { [weak self] buffer, _ in
            self?.work.async { self?.capture(buffer) }
        }
        micRunning = true
        do {
            if !engine.isRunning { try engine.start() }
        } catch {
            input.removeTap(onBus: 0)
            micRunning = false
            return completion(3)
        }
        completion(0)
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

    func stopMicrophone() {
        work.async {
            self.micRunning = false
            self.engine.inputNode.removeTap(onBus: 0)
            self.encoder = nil
            self.resampler = nil
            self.pending.removeAll()
            self.onPacket = nil
            self.onMicLevel = nil
            self.stopEngineIfIdle()
        }
    }

    // MARK: speaker

    func startSpeaker(sampleRate: Int32, onPlaying: @escaping (KotlinBoolean) -> Void, onLevel: @escaping (KotlinFloat) -> Void) -> Bool {
        guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(sampleRate), channels: 1, interleaved: false) else { return false }
        do {
            try prepareEngine()
        } catch {
            return false
        }
        if player.engine == nil { engine.attach(player) }
        engine.disconnectNodeOutput(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        speakerFormat = format
        self.onPlaying = { onPlaying(KotlinBoolean(value: $0)) }
        self.onVoiceLevel = { onLevel(KotlinFloat(value: $0)) }
        queued = 0
        player.volume = 1
        do {
            if !engine.isRunning { try engine.start() }
        } catch {
            return false
        }
        player.play()
        speakerRunning = true
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
        work.async {
            self.queued += 1
            self.onPlaying?(true)
            self.onVoiceLevel?(level)
            self.player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                self?.work.async {
                    guard let self = self else { return }
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
            self.onPlaying?(false)
            self.onVoiceLevel?(0)
            self.player.play()
        }
    }

    /// Ducking while the person may be talking over her. The shared code ramps it in ~16 ms steps; the player's
    /// volume applies to what is already scheduled, so the dip is heard at once.
    func setSpeakerVolume(volume: Float) {
        work.async { self.player.volume = min(max(volume, 0), 1) }
    }

    func stopSpeaker() {
        work.async {
            self.speakerRunning = false
            self.player.stop()
            self.queued = 0
            self.onPlaying = nil
            self.onVoiceLevel = nil
            self.stopEngineIfIdle()
        }
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
