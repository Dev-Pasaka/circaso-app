import AVFoundation
import Shared
import UIKit

/// The in-app camera for Moments (M1), AVFoundation only (decided 2026-11-03). One capture session with a photo
/// output and a movie output; the session is configured, started and stopped on its own serial queue, never the main
/// thread. Videos are recorded as H.264/AAC QuickTime and remuxed (passthrough, no re-encode) to MP4. Every shot comes
/// back with a tiny JPEG for the blurred placeholder. Where there is no camera (the simulator) `open` answers 2.
final class MomentCameraImpl: NSObject, MomentCameraProvider, AVCapturePhotoCaptureDelegate, AVCaptureFileOutputRecordingDelegate {
    private static let photoMaxSide = CGFloat(MomentCameraKt.MomentPhotoMaxSide)
    private static let thumbSide = CGFloat(MomentCameraKt.MomentThumbSide)
    private static let videoMaxSeconds = Double(MomentCameraKt.MomentVideoMaxMs) / 1000

    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "com.circaso.momentcamera")
    private let photoOutput = AVCapturePhotoOutput()
    private let movieOutput = AVCaptureMovieFileOutput()
    private var videoInput: AVCaptureDeviceInput?
    private var audioInput: AVCaptureDeviceInput?
    private var configured = false
    private var front = false
    private var flash = false
    private var rotation: AVCaptureDevice.RotationCoordinator?

    private var photoCompletion: ((MomentShot?) -> Void)?
    /// The shot in flight is a profile photo: handed over as the sensor gave it, not scaled to a moment's size.
    private var photoFull = false
    private var stopCompletion: ((MomentShot?) -> Void)?
    /// A clip that finished by itself (the 60 s limit) before anyone asked for it.
    private var finishedShot: MomentShot?
    private var finishing = false

    // MARK: Opening

    /// [photoOnly]: the profile camera. No microphone prompt (a still has no sound), the photo preset for the sharpest
    /// 4:3 still, and the front camera first (a selfie is the usual profile photo). Moments get the video preset back.
    func open(photoOnly: Bool, completion: @escaping (KotlinInt) -> Void) {
        let answer: (Int32) -> Void = { code in DispatchQueue.main.async { completion(KotlinInt(int: code)) } }
        guard (Self.camera(front: false) ?? Self.camera(front: true)) != nil else { answer(2); return }
        AVCaptureDevice.requestAccess(for: .video) { granted in
            guard granted else { answer(1); return }
            let start = {
                self.queue.async {
                    let ok = self.configure()
                    if ok {
                        self.usePreset(photoOnly ? .photo : .high)
                        if photoOnly && !self.front { self.useCamera(front: true) }
                    }
                    if ok && !self.session.isRunning { self.session.startRunning() }
                    answer(ok ? 0 : 2)
                }
            }
            // The microphone is only for the sound of a video; a refusal still leaves photos and silent clips.
            if photoOnly { start() } else { AVCaptureDevice.requestAccess(for: .audio) { _ in start() } }
        }
    }

    /// Runs on [queue]. The new format is only active after the commit, so the camera's settings follow in a second pass.
    private func usePreset(_ preset: AVCaptureSession.Preset) {
        guard session.sessionPreset != preset, session.canSetSessionPreset(preset) else { return }
        session.beginConfiguration()
        session.sessionPreset = preset
        session.commitConfiguration()
        if let device = videoInput?.device {
            session.beginConfiguration()
            deviceChanged(device)
            session.commitConfiguration()
        }
    }

    /// Runs on [queue].
    private func configure() -> Bool {
        if configured { return videoInput != nil }
        configured = true
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .high
        guard let device = Self.camera(front: front) ?? Self.camera(front: !front),
              let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return false }
        session.addInput(input)
        videoInput = input
        front = device.position == .front
        if AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
           let mic = AVCaptureDevice.default(for: .audio), let audio = try? AVCaptureDeviceInput(device: mic), session.canAddInput(audio) {
            session.addInput(audio)
            audioInput = audio
        }
        if session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
        if session.canAddOutput(movieOutput) { session.addOutput(movieOutput) }
        movieOutput.maxRecordedDuration = CMTime(seconds: Self.videoMaxSeconds, preferredTimescale: 600)
        deviceChanged(device)
        return true
    }

    /// Runs on [queue] inside a configuration: everything that depends on the active camera.
    private func deviceChanged(_ device: AVCaptureDevice) {
        rotation = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: nil)
        if let largest = device.activeFormat.supportedMaxPhotoDimensions.max(by: { Int($0.width) * Int($0.height) < Int($1.width) * Int($1.height) }) {
            photoOutput.maxPhotoDimensions = largest
        }
        if let connection = movieOutput.connection(with: .video) {
            if movieOutput.availableVideoCodecTypes.contains(.h264) {
                var settings: [String: Any] = [AVVideoCodecKey: AVVideoCodecType.h264]
                // MomentVideoBitRate (8 Mbit/s, 1080p30's rate), H.264 High: about 30 s fits the 32 MiB limit as
                // recorded; a longer clip is re-encoded to fit before posting (MomentVideoTranscoder).
                if movieOutput.supportedOutputSettingsKeys(for: connection).contains(AVVideoCompressionPropertiesKey) {
                    settings[AVVideoCompressionPropertiesKey] = [
                        AVVideoAverageBitRateKey: Int(MomentCameraKt.MomentVideoBitRate),
                        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
                    ]
                }
                movieOutput.setOutputSettings(settings, for: connection)
            }
            if connection.isVideoMirroringSupported { connection.automaticallyAdjustsVideoMirroring = false; connection.isVideoMirrored = front }
        }
        if let connection = photoOutput.connection(with: .video), connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = front // the shot looks like the picture the person framed
        }
    }

    private static func camera(front: Bool) -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: front ? .front : .back)
    }

    // MARK: Photo

    func takePhoto(completion: @escaping (MomentShot?) -> Void) { capture(full: false, completion: completion) }

    /// A profile photo: the full still; Kotlin crops it to the guide's square and encodes it with the avatar rules.
    func takeFullPhoto(completion: @escaping (MomentShot?) -> Void) { capture(full: true, completion: completion) }

    private func capture(full: Bool, completion: @escaping (MomentShot?) -> Void) {
        queue.async {
            guard self.videoInput != nil, self.photoCompletion == nil, self.session.isRunning else { DispatchQueue.main.async { completion(nil) }; return }
            self.photoCompletion = completion
            self.photoFull = full
            self.applyCaptureRotation(self.photoOutput.connection(with: .video))
            let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            settings.maxPhotoDimensions = self.photoOutput.maxPhotoDimensions
            if self.flash && self.photoOutput.supportedFlashModes.contains(.on) { settings.flashMode = .on }
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let data = error == nil ? photo.fileDataRepresentation() : nil
        queue.async {
            let full = self.photoFull
            DispatchQueue.global(qos: .userInitiated).async {
                let shot = full
                    ? data.map { MomentShot(data: $0, thumb: nil, width: 0, height: 0, durationMs: 0, path: nil) }
                    : data.flatMap(Self.photoShot)
                self.queue.async {
                    let done = self.photoCompletion
                    self.photoCompletion = nil
                    DispatchQueue.main.async { done?(shot) }
                }
            }
        }
    }

    /// Long side at most MomentPhotoMaxSide, JPEG at 0.9; drawing through UIKit bakes in the rotation.
    private static func photoShot(_ data: Data) -> MomentShot? {
        guard let image = UIImage(data: data) else { return nil }
        let size = image.size
        let scale = min(1, photoMaxSide / max(size.width, size.height))
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let drawn = draw(image, at: target)
        guard let jpeg = drawn.jpegData(compressionQuality: 0.9) else { return nil }
        return MomentShot(data: jpeg, thumb: thumbnail(drawn), width: Int32(target.width), height: Int32(target.height), durationMs: 0, path: nil)
    }

    // MARK: Video

    func startVideo(completion: @escaping (KotlinBoolean) -> Void) {
        queue.async {
            guard self.videoInput != nil, self.session.isRunning, !self.movieOutput.isRecording, !self.finishing else {
                DispatchQueue.main.async { completion(KotlinBoolean(bool: false)) }; return
            }
            self.finishedShot = nil
            self.applyCaptureRotation(self.movieOutput.connection(with: .video))
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("moment-\(UUID().uuidString).mov")
            self.setTorch(self.flash)
            self.movieOutput.startRecording(to: url, recordingDelegate: self)
            DispatchQueue.main.async { completion(KotlinBoolean(bool: true)) }
        }
    }

    func stopVideo(completion: @escaping (MomentShot?) -> Void) {
        queue.async {
            if let shot = self.finishedShot {
                self.finishedShot = nil
                DispatchQueue.main.async { completion(shot) }
            } else if self.movieOutput.isRecording || self.finishing {
                self.stopCompletion = completion
                if self.movieOutput.isRecording { self.movieOutput.stopRecording() }
            } else {
                DispatchQueue.main.async { completion(nil) }
            }
        }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didStartRecordingTo fileURL: URL, from connections: [AVCaptureConnection]) {}

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        // Reaching the 60 s limit reports an error but the file is complete.
        let finished = error == nil || ((error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool ?? false)
        queue.sync { self.finishing = true } // so a stopVideo arriving now waits for this clip
        queue.async { self.setTorch(false) }
        Task.detached(priority: .userInitiated) {
            let shot = finished ? await Self.videoShot(outputFileURL) : nil
            try? FileManager.default.removeItem(at: outputFileURL)
            self.queue.async {
                self.finishing = false
                if let done = self.stopCompletion {
                    self.stopCompletion = nil
                    DispatchQueue.main.async { done(shot) }
                } else {
                    self.finishedShot = shot
                }
            }
        }
    }

    /// Remuxes the QuickTime clip to MP4 without re-encoding and takes the first frame for the placeholder.
    private static func videoShot(_ mov: URL) async -> MomentShot? {
        let asset = AVURLAsset(url: mov)
        let mp4 = mov.deletingPathExtension().appendingPathExtension("mp4")
        try? FileManager.default.removeItem(at: mp4)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else { return nil }
        do { try await export.export(to: mp4, as: .mp4) } catch { return nil }
        guard let data = try? Data(contentsOf: mp4) else { return nil }
        let duration = (try? await asset.load(.duration)).map { CMTimeGetSeconds($0) } ?? 0
        var size = CGSize.zero
        if let track = try? await asset.loadTracks(withMediaType: .video).first,
           let loaded = try? await track.load(.naturalSize, .preferredTransform) {
            let r = CGRect(origin: .zero, size: loaded.0).applying(loaded.1)
            size = CGSize(width: abs(r.width), height: abs(r.height))
        }
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 256, height: 256)
        var thumb: Data?
        if let frame = try? await generator.image(at: .zero).image { thumb = thumbnail(UIImage(cgImage: frame)) }
        return MomentShot(data: data, thumb: thumb, width: Int32(size.width), height: Int32(size.height),
                          durationMs: Int64((duration * 1000).rounded()), path: mp4.path)
    }

    // MARK: Controls

    func flip() {
        queue.async {
            guard self.configured, !self.movieOutput.isRecording else { return }
            self.useCamera(front: !self.front)
        }
    }

    /// Runs on [queue]: swaps to the front or back camera, keeping the current one if the other can't be added.
    private func useCamera(front wanted: Bool) {
        guard let device = Self.camera(front: wanted), let input = try? AVCaptureDeviceInput(device: device) else { return }
        session.beginConfiguration()
        if let old = videoInput { session.removeInput(old) }
        if session.canAddInput(input) {
            session.addInput(input)
            videoInput = input
            front = wanted
        } else if let old = videoInput {
            session.addInput(old)
        }
        if let current = videoInput?.device { deviceChanged(current) }
        session.commitConfiguration()
        DispatchQueue.main.async { NotificationCenter.default.post(name: MomentPreviewView.deviceChanged, object: self) }
    }

    func setFlash(on: Bool) {
        queue.async {
            self.flash = on
            if self.movieOutput.isRecording { self.setTorch(on) }
        }
    }

    /// Runs on [queue]. The front camera has no torch, so this does nothing there.
    private func setTorch(_ on: Bool) {
        guard let device = videoInput?.device, device.hasTorch, device.isTorchModeSupported(on ? .on : .off) else { return }
        guard (try? device.lockForConfiguration()) != nil else { return }
        device.torchMode = on ? .on : .off
        device.unlockForConfiguration()
    }

    func close() {
        queue.async {
            if self.movieOutput.isRecording { self.movieOutput.stopRecording() }
            self.setTorch(false)
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    func previewView() -> UIView {
        let view = MomentPreviewView()
        view.attach(session: session, camera: { [weak self] in self?.videoInput?.device })
        return view
    }

    // MARK: Helpers

    /// Runs on [queue]: the shot is upright for how the phone is held right now.
    private func applyCaptureRotation(_ connection: AVCaptureConnection?) {
        guard let connection, let angle = rotation?.videoRotationAngleForHorizonLevelCapture,
              connection.isVideoRotationAngleSupported(angle) else { return }
        connection.videoRotationAngle = angle
    }

    private static func draw(_ image: UIImage, at size: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1 // exactly that many pixels, not times the screen's scale
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }

    /// MomentThumbSide on the long side, JPEG at 0.5: drawn blurred, so tiny is enough.
    private static func thumbnail(_ image: UIImage) -> Data? {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return nil }
        let scale = thumbSide / max(size.width, size.height)
        let target = CGSize(width: max(1, (size.width * scale).rounded()), height: max(1, (size.height * scale).rounded()))
        return draw(image, at: target).jpegData(compressionQuality: 0.5)
    }
}

/// The viewfinder: a preview layer that fills its bounds (aspect fill) and stays level as the phone or iPad turns.
final class MomentPreviewView: UIView {
    static let deviceChanged = Notification.Name("MomentCameraDeviceChanged")

    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    private var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    private var coordinator: AVCaptureDevice.RotationCoordinator?
    private var observation: NSKeyValueObservation?
    private var camera: (() -> AVCaptureDevice?)?

    func attach(session: AVCaptureSession, camera: @escaping () -> AVCaptureDevice?) {
        backgroundColor = .black
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.session = session
        self.camera = camera
        NotificationCenter.default.addObserver(self, selector: #selector(follow), name: Self.deviceChanged, object: nil)
        follow()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { follow() }
    }

    @objc private func follow() {
        guard let device = camera?() else { return }
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: previewLayer)
        self.coordinator = coordinator
        apply(coordinator.videoRotationAngleForHorizonLevelPreview)
        observation = coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.new]) { [weak self] c, _ in
            let angle = c.videoRotationAngleForHorizonLevelPreview
            DispatchQueue.main.async { self?.apply(angle) }
        }
    }

    private func apply(_ angle: CGFloat) {
        guard let connection = previewLayer.connection, connection.isVideoRotationAngleSupported(angle) else { return }
        connection.videoRotationAngle = angle
    }

    deinit { NotificationCenter.default.removeObserver(self) }
}
