import AVFoundation
import ImageIO
import Photos
import PhotosUI
import Shared
import UIKit
import UniformTypeIdentifiers

/// Moments' library (WhatsApp-style composer, 2026-11-03): reads a PHAsset the recents strip showed, and runs the
/// system pickers (PHPicker for photos and videos, Files for audio, neither needs a permission). Photos leave as JPEG at
/// most MomentPhotoMaxSide on the long side (0.9), videos as MP4 (remuxed without re-encoding when the codec allows),
/// audio as the file it is. Everything comes with a tiny JPEG for the blurred placeholder; iCloud-only assets download.
/// All work runs off the main thread; completions are called on the main thread.
final class MediaLibraryImpl: NSObject, MediaLibraryProvider, PHPickerViewControllerDelegate, UIDocumentPickerDelegate {
    private static let photoMaxSide = CGFloat(MomentCameraKt.MomentPhotoMaxSide)
    private static let thumbSide = CGFloat(MomentCameraKt.MomentThumbSide)

    private static let mediaMaxBytes = Int(MomentCameraKt.MomentMediaMaxBytes)

    private var pickCompletion: ((LibraryMedia?) -> Void)?
    /// The Files picker is up for a chat's document (kind 3), not for audio: the file goes as it is, under its own name.
    private var pickingDocument = false

    // MARK: Recents

    func load(id: String, video: Bool, completion: @escaping (LibraryMedia?) -> Void) {
        let answer: (LibraryMedia?) -> Void = { media in DispatchQueue.main.async { completion(media) } }
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { answer(nil); return }
        if video {
            let options = PHVideoRequestOptions()
            options.deliveryMode = .highQualityFormat
            options.version = .current // with the person's edits (trim, filters)
            options.isNetworkAccessAllowed = true
            PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { avAsset, _, _ in
                guard let avAsset else { answer(nil); return }
                Task.detached(priority: .userInitiated) { answer(await Self.video(avAsset)) }
            }
        } else {
            let options = PHImageRequestOptions()
            options.deliveryMode = .highQualityFormat
            options.version = .current
            options.isNetworkAccessAllowed = true
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in
                guard let data else { answer(nil); return }
                DispatchQueue.global(qos: .userInitiated).async { answer(Self.photo(CGImageSourceCreateWithData(data as CFData, nil))) }
            }
        }
    }

    // MARK: Pickers

    func pick(kind: Int32, completion: @escaping (LibraryMedia?) -> Void) {
        guard pickCompletion == nil, let presenter = Self.topController() else { completion(nil); return }
        pickCompletion = completion
        if kind == 2 || kind == 3 {
            pickingDocument = kind == 3
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: kind == 3 ? [.item] : [.audio], asCopy: true)
            picker.allowsMultipleSelection = false
            picker.delegate = self
            presenter.present(picker, animated: true)
        } else {
            var config = PHPickerConfiguration()
            config.filter = kind == 1 ? .videos : .images
            config.selectionLimit = 1
            config.preferredAssetRepresentationMode = .current // no transcoding before we see it; MP4 is made below
            let picker = PHPickerViewController(configuration: config)
            picker.delegate = self
            presenter.present(picker, animated: true)
        }
    }

    func manageLimited(completion: @escaping () -> Void) {
        guard let presenter = Self.topController() else { completion(); return }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: presenter) { _ in
            DispatchQueue.main.async { completion() }
        }
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider else { finishPick(nil); return }
        if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
            // The URL lives only inside the callback, so the file is copied out first.
            provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                guard let copy = url.flatMap(Self.copyToTemp) else { self.finishPick(nil); return }
                Task.detached(priority: .userInitiated) {
                    let media = await Self.video(AVURLAsset(url: copy))
                    if media?.path != copy.path { try? FileManager.default.removeItem(at: copy) }
                    self.finishPick(media)
                }
            }
        } else if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
                // ImageIO reads the file before the callback returns; no copy needed.
                let media = url.flatMap { Self.photo(CGImageSourceCreateWithURL($0 as CFURL, nil)) }
                self.finishPick(media)
            }
        } else {
            finishPick(nil)
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { finishPick(nil); return }
        if pickingDocument {
            pickingDocument = false
            Task.detached(priority: .userInitiated) { self.finishPick(Self.document(url)) }
            return
        }
        Task.detached(priority: .userInitiated) {
            // asCopy: the file is already in our sandbox; it moves into tmp so it is ours to delete.
            let file = Self.copyToTemp(url)
            try? FileManager.default.removeItem(at: url)
            guard let file else { self.finishPick(nil); return }
            self.finishPick(await Self.audio(file))
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { pickingDocument = false; finishPick(nil) }

    /// A chat's document (2026-11-07): moved into a folder of its own in tmp so it keeps its own name, handed over by
    /// path (the shared code reads it when it is sent, and takes the name from the path).
    private nonisolated static func document(_ url: URL) -> LibraryMedia? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("doc-\(UUID().uuidString)", isDirectory: true)
        let name = url.lastPathComponent.isEmpty ? "File" : url.lastPathComponent
        let copy = dir.appendingPathComponent(name)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try FileManager.default.moveItem(at: url, to: copy)
        } catch { return nil }
        let mime = UTType(filenameExtension: copy.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        return LibraryMedia(data: Data(), thumb: nil, width: 0, height: 0, durationMs: 0, path: copy.path, mime: mime)
    }

    /// Called from the pickers' background callbacks; the completion itself runs on the main thread.
    private nonisolated func finishPick(_ media: LibraryMedia?) {
        Task { @MainActor in
            let done = self.pickCompletion
            self.pickCompletion = nil
            done?(media)
        }
    }

    /// One picked item as a moment's media: a video copied out of the picker's callback and made MP4, or a photo.
    private nonisolated static func media(from provider: NSItemProvider) async -> LibraryMedia? {
        if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
            let copy: URL? = await withCheckedContinuation { cont in
                provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in cont.resume(returning: url.flatMap(copyToTemp)) }
            }
            guard let copy else { return nil }
            let media = await video(AVURLAsset(url: copy))
            if media?.path != copy.path { try? FileManager.default.removeItem(at: copy) }
            return media
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            return await withCheckedContinuation { cont in
                provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
                    cont.resume(returning: url.flatMap { photo(CGImageSourceCreateWithURL($0 as CFURL, nil)) })
                }
            }
        }
        return nil
    }

    // MARK: Trimming (M3b)

    func filmstrip(path: String, count: Int32, completion: @escaping @Sendable ([Data]) -> Void) {
        let asset = AVURLAsset(url: URL(fileURLWithPath: path))
        Task.detached(priority: .userInitiated) {
            var frames: [Data] = []
            let seconds = (try? await asset.load(.duration)).map { CMTimeGetSeconds($0) } ?? 0
            if seconds.isFinite, seconds > 0, count > 0 {
                let generator = AVAssetImageGenerator(asset: asset)
                generator.appliesPreferredTrackTransform = true
                generator.maximumSize = CGSize(width: 160, height: 160)
                for i in 0..<Int(count) {
                    let at = CMTime(seconds: seconds * (Double(2 * i + 1) / Double(2 * Int(count))), preferredTimescale: 600)
                    if let frame = try? await generator.image(at: at).image,
                       let jpeg = UIImage(cgImage: frame).jpegData(compressionQuality: 0.6) {
                        frames.append(jpeg)
                    }
                }
            }
            let done = frames
            await MainActor.run { completion(done) }
        }
    }

    /// The clip, passthrough when it fits the moment limit, else re-encoded at 1280x720 (which always fits a minute).
    func trim(path: String, startMs: Int64, endMs: Int64, maxBytes: Int64, completion: @escaping (LibraryMedia?) -> Void) {
        let asset = AVURLAsset(url: URL(fileURLWithPath: path))
        let range = CMTimeRange(start: CMTime(value: startMs, timescale: 1000), end: CMTime(value: endMs, timescale: 1000))
        Task.detached(priority: .userInitiated) {
            let media = await Self.video(asset, range: range, maxBytes: Int(maxBytes))
            await MainActor.run { completion(media) }
        }
    }

    // MARK: Making the media

    /// ImageIO draws the long side at most MomentPhotoMaxSide straight from the file (HEIC too), upright, without
    /// decoding a 48 MP picture in full.
    private static func photo(_ source: CGImageSource?) -> LibraryMedia? {
        guard let source else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(photoMaxSide),
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let image = UIImage(cgImage: cg)
        guard let jpeg = image.jpegData(compressionQuality: 0.9) else { return nil }
        return LibraryMedia(data: jpeg, thumb: thumbnail(image), width: Int32(cg.width), height: Int32(cg.height),
                            durationMs: 0, path: nil, mime: "image/jpeg")
    }

    /// MP4 in tmp: remuxed (passthrough) when the tracks fit MP4, otherwise re-encoded at the highest quality. With
    /// [range] (the trimmer, or the shrink before posting), only that part: as it was shot when the passthrough copy
    /// fits the limit, else re-encoded by MomentVideoTranscoder (up to 1080p, the bit rate the limit allows).
    /// The file stays on disk and is handed over by path (no bytes): the shared code reads it when it is posted.
    private nonisolated static func video(_ asset: AVAsset, range: CMTimeRange? = nil, maxBytes: Int = mediaMaxBytes) async -> LibraryMedia? {
        let mp4 = FileManager.default.temporaryDirectory.appendingPathComponent("moment-\(UUID().uuidString).mp4")
        var exported = false
        let presets = range == nil ? [AVAssetExportPresetPassthrough, AVAssetExportPresetHighestQuality]
                                   : [AVAssetExportPresetPassthrough]
        for preset in presets where !exported {
            try? FileManager.default.removeItem(at: mp4)
            guard let export = AVAssetExportSession(asset: asset, presetName: preset),
                  export.supportedFileTypes.contains(.mp4) else { continue }
            export.shouldOptimizeForNetworkUse = true
            if let range { export.timeRange = range }
            exported = (try? await export.export(to: mp4, as: .mp4)) != nil
            if exported, range != nil, preset == AVAssetExportPresetPassthrough,
               let size = (try? FileManager.default.attributesOfItem(atPath: mp4.path)[.size]) as? Int, size > maxBytes {
                exported = false // too large as it is (a moment's 32 MiB, a chat's 100 MB): re-encoded below
            }
        }
        if !exported, let range {
            exported = await MomentVideoTranscoder.transcode(asset, range: range, to: mp4, maxBytes: Int64(maxBytes))
        }
        guard exported, FileManager.default.fileExists(atPath: mp4.path) else { try? FileManager.default.removeItem(at: mp4); return nil }
        let duration: Double
        if let range { duration = CMTimeGetSeconds(range.duration) } else { duration = (try? await asset.load(.duration)).map { CMTimeGetSeconds($0) } ?? 0 }
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
        if let frame = try? await generator.image(at: range?.start ?? .zero).image { thumb = thumbnail(UIImage(cgImage: frame)) }
        return LibraryMedia(data: Data(), thumb: thumb, width: Int32(size.width.rounded()), height: Int32(size.height.rounded()),
                            durationMs: Int64((duration.isFinite ? duration * 1000 : 0).rounded()), path: mp4.path, mime: "video/mp4")
    }

    /// The audio file as it is, with its length so the shared code can refuse more than a minute.
    private static func audio(_ url: URL) async -> LibraryMedia? {
        guard let data = try? Data(contentsOf: url) else { try? FileManager.default.removeItem(at: url); return nil }
        let duration = (try? await AVURLAsset(url: url).load(.duration)).map { CMTimeGetSeconds($0) } ?? 0
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "audio/mpeg"
        return LibraryMedia(data: data, thumb: nil, width: 0, height: 0,
                            durationMs: Int64((duration.isFinite ? duration * 1000 : 0).rounded()), path: url.path, mime: mime)
    }

    private nonisolated static func copyToTemp(_ url: URL) -> URL? {
        let ext = url.pathExtension.isEmpty ? "" : ".\(url.pathExtension)"
        let copy = FileManager.default.temporaryDirectory.appendingPathComponent("library-\(UUID().uuidString)\(ext)")
        do { try FileManager.default.copyItem(at: url, to: copy) } catch { return nil }
        return copy
    }

    /// MomentThumbSide on the long side, JPEG at 0.5: drawn blurred, so tiny is enough.
    private static func thumbnail(_ image: UIImage) -> Data? {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return nil }
        let scale = thumbSide / max(size.width, size.height)
        let target = CGSize(width: max(1, (size.width * scale).rounded()), height: max(1, (size.height * scale).rounded()))
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format)
            .image { _ in image.draw(in: CGRect(origin: .zero, size: target)) }
            .jpegData(compressionQuality: 0.5)
    }

    private static func topController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
