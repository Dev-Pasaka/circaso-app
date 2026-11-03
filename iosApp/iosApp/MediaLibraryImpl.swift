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

    private var pickCompletion: ((LibraryMedia?) -> Void)?

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
        if kind == 2 {
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.audio], asCopy: true)
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
        Task.detached(priority: .userInitiated) {
            // asCopy: the file is already in our sandbox; it moves into tmp so it is ours to delete.
            let file = Self.copyToTemp(url)
            try? FileManager.default.removeItem(at: url)
            guard let file else { self.finishPick(nil); return }
            self.finishPick(await Self.audio(file))
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finishPick(nil) }

    /// Called from the pickers' background callbacks; the completion itself runs on the main thread.
    private nonisolated func finishPick(_ media: LibraryMedia?) {
        Task { @MainActor in
            let done = self.pickCompletion
            self.pickCompletion = nil
            done?(media)
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

    /// MP4 in tmp: remuxed (passthrough) when the tracks fit MP4, otherwise re-encoded at the highest quality.
    private static func video(_ asset: AVAsset) async -> LibraryMedia? {
        let mp4 = FileManager.default.temporaryDirectory.appendingPathComponent("moment-\(UUID().uuidString).mp4")
        var exported = false
        for preset in [AVAssetExportPresetPassthrough, AVAssetExportPresetHighestQuality] where !exported {
            try? FileManager.default.removeItem(at: mp4)
            guard let export = AVAssetExportSession(asset: asset, presetName: preset),
                  export.supportedFileTypes.contains(.mp4) else { continue }
            export.shouldOptimizeForNetworkUse = true
            exported = (try? await export.export(to: mp4, as: .mp4)) != nil
        }
        guard exported, let data = try? Data(contentsOf: mp4) else { try? FileManager.default.removeItem(at: mp4); return nil }
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
        return LibraryMedia(data: data, thumb: thumb, width: Int32(size.width.rounded()), height: Int32(size.height.rounded()),
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
