import Shared
import UIKit
import VisionKit

/// VisionKit's live scanner (iOS 16+, a phone with a camera). Shown full screen with a Cancel; the first QR code it
/// recognises closes it and goes back to the shared code as text.
final class QrScannerImpl: NSObject, QrScannerProvider, DataScannerViewControllerDelegate {
    private var completion: ((String?) -> Void)?
    private weak var scanner: DataScannerViewController?

    var supported: Bool { DataScannerViewController.isSupported && DataScannerViewController.isAvailable }

    func scan(completion: @escaping (String?) -> Void) {
        guard supported, let presenter = Self.topController() else { completion(nil); return }
        self.completion = completion
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            isHighlightingEnabled: true
        )
        controller.delegate = self
        controller.navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .cancel, primaryAction: UIAction { [weak self] _ in self?.finish(nil) })
        let nav = UINavigationController(rootViewController: controller)
        nav.modalPresentationStyle = .fullScreen
        scanner = controller
        presenter.present(nav, animated: true) { try? controller.startScanning() }
    }

    func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        for item in addedItems {
            if case let .barcode(code) = item, let text = code.payloadStringValue {
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                finish(text)
                return
            }
        }
    }

    private func finish(_ text: String?) {
        let done = completion
        completion = nil
        scanner?.stopScanning()
        (scanner?.navigationController ?? scanner)?.dismiss(animated: true) { done?(text) }
        if scanner == nil { done?(text) }
    }

    private static func topController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
