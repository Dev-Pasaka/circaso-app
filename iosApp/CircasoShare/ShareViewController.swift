import UIKit
import UniformTypeIdentifiers

/// Circaso's Share Extension (2026-11-07): photos, videos and files shared from any app go to Circaso, where the person
/// chooses My moment or chats and sends them through the same media editor as everything else.
///
/// It only hands things over: each item is copied into the App Group container under `IncomingShares/<batch>/`, with
/// `items.json` listing them (file, name, mime, size), then Circaso is opened with `circaso://share`. The app moves them
/// into its own storage (`IosShareInbox.kt`) and keeps them through sign-in. Nothing is uploaded or decrypted here.
/// A file over the attachment limit is listed with its size and an empty placeholder, so the app can say it can't go.
///
/// NOT YET IN THE XCODE PROJECT. To add it (Xcode 16, open iosApp.xcworkspace, never the .xcodeproj):
///  1. File > New > Target… > iOS > Share Extension. Product Name `CircasoShare`, Team as iosApp, Language Swift,
///     Embed in Application `iosApp`. When Xcode offers to activate the scheme, choose Cancel.
///  2. Delete the files Xcode generated for the target (ShareViewController.swift, MainInterface.storyboard, Info.plist)
///     and add this folder's three files to the CircasoShare target instead (target membership: CircasoShare only).
///  3. CircasoShare target > Build Settings: Info.plist File = `CircasoShare/Info.plist`; Code Signing Entitlements =
///     `CircasoShare/CircasoShare.entitlements`; iOS Deployment Target = the app's (18.2); Bundle Identifier =
///     `<app bundle id>.share`.
///  4. Signing & Capabilities: add App Groups with `group.com.circaso.app` to BOTH iosApp and CircasoShare (iosApp's
///     entitlements file gains `com.apple.security.application-groups`), and register the group and the extension's App
///     ID in the developer portal so the profiles include them.
///  5. Podfile: the extension links no pods; if `pod install` complains, add an empty `target 'CircasoShare' do end`.
///  6. Build iosApp for a simulator from the workspace and share a photo from Photos to check it opens Circaso.
final class ShareViewController: UIViewController {
    private static let appGroup = "group.com.circaso.app"
    private static let maxItems = 10
    /// The attachment limit (MomentMediaMaxBytes): larger files aren't copied, only listed with their size.
    private static let maxBytes: Int64 = 32 * 1024 * 1024 - 64

    private struct Item: Codable { let file: String; let name: String; let mime: String; let size: Int64 }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        Task { await handOver() }
    }

    private func handOver() async {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup) else { finish(); return }
        let batch = container.appendingPathComponent("IncomingShares/\(Int(Date().timeIntervalSince1970 * 1000))-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: batch, withIntermediateDirectories: true)
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        var items: [Item] = []
        for (index, provider) in providers.prefix(Self.maxItems).enumerated() {
            if let item = await copy(provider, into: batch, index: index) { items.append(item) }
        }
        if items.isEmpty { try? FileManager.default.removeItem(at: batch); finish(); return }
        if let data = try? JSONEncoder().encode(items) { try? data.write(to: batch.appendingPathComponent("items.json"), options: .atomic) }
        openApp()
        finish()
    }

    /// One attachment as a file: a movie, an image, or any other file, in that order of preference.
    private func copy(_ provider: NSItemProvider, into batch: URL, index: Int) async -> Item? {
        let type = [UTType.movie, UTType.image, UTType.item].first { provider.hasItemConformingToTypeIdentifier($0.identifier) } ?? .item
        return await withCheckedContinuation { cont in
            provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                guard let url else { cont.resume(returning: nil); return }
                let name = provider.suggestedName.map { n in url.pathExtension.isEmpty || n.hasSuffix(".\(url.pathExtension)") ? n : "\(n).\(url.pathExtension)" } ?? url.lastPathComponent
                let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? ""
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? -1
                let file = "\(index)-\(UUID().uuidString)"
                let target = batch.appendingPathComponent(file)
                if size > Self.maxBytes { FileManager.default.createFile(atPath: target.path, contents: Data()) }
                else { try? FileManager.default.copyItem(at: url, to: target) }
                cont.resume(returning: FileManager.default.fileExists(atPath: target.path) ? Item(file: file, name: name, mime: mime, size: size) : nil)
            }
        }
    }

    /// Extensions can't open their app directly; the responder chain reaches UIApplication's openURL.
    private func openApp() {
        guard let url = URL(string: "circaso://share") else { return }
        var responder: UIResponder? = self
        while let r = responder {
            if let app = r as? UIApplication { app.open(url); return }
            responder = r.next
        }
    }

    private func finish() {
        DispatchQueue.main.async { self.extensionContext?.completeRequest(returningItems: nil) }
    }
}
