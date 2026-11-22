import Foundation
import Shared
import SwiftUI
#if canImport(DiditSDK)
import DiditSDK
#endif

/// Didit's identity verification SDK behind the shared `IdentityVerifierBridge` (guide "Identity verification (KYC)"):
/// the ID photo, the selfie and the face match run in Didit's own screens, started with the session token Circaso's
/// backend made (R0). No Didit key is in the app. The SDK comes from CocoaPods (`pod 'DiditSDK/Core'`, see the
/// Podfile); until `pod install` has run, this compiles without it and answers "not available", and the shared code
/// opens Didit's hosted page instead.
///
/// What the SDK reports is turned into a `VerifierOutcome` and nothing more: the decision is the backend's to read
/// from Didit. Nothing is logged here and the SDK's own logging stays off: a token opens one person's identity check.
final class DiditKycImpl: NSObject, IdentityVerifier {
    static let shared = DiditKycImpl()

    #if canImport(DiditSDK)
    var available: Bool { true }

    /// The check under way: answered once, when Didit's screens have closed (`finished`, from `identityVerification()`).
    private var waiting: CheckedContinuation<VerifierOutcome, Never>?

    /// The close button and its "are you sure?" stay (leaving is always the person's choice, and the check can be
    /// continued); the SDK closes itself when the last step is done, so Circaso's own "Checking your details" follows
    /// at once instead of a second "done" screen to tap through. Language: the phone's, English where Didit has none.
    /// Colours come from Didit's white-label settings, set to Circaso's at Didit.
    private static let configuration = DiditSdk.Configuration(
        loggingEnabled: false,
        showCloseButton: true,
        showExitConfirmation: true,
        closeOnComplete: true
    )

    func verify(sessionToken: String) async throws -> VerifierOutcome {
        await withCheckedContinuation { continuation in
            DispatchQueue.main.async {
                // One check at a time: an earlier one still waiting (it shouldn't be) is let go as closed.
                self.waiting?.resume(returning: VerifierOutcomeCancelled.shared)
                self.waiting = continuation
                // Presented by the `.diditVerification` modifier on the app's root view (`identityVerification()`).
                DiditSdk.shared.startVerification(token: sessionToken, configuration: Self.configuration)
            }
        }
    }

    /// Didit's screens have closed, however they closed.
    @MainActor
    func finished(_ result: VerificationResult) {
        let outcome: VerifierOutcome
        switch result {
        case .completed:
            outcome = VerifierOutcomeFinished.shared
        case .cancelled:
            outcome = VerifierOutcomeCancelled.shared
        case .failed(let error, _):
            switch error {
            case .cameraAccessDenied: outcome = VerifierOutcomeCameraDenied.shared
            case .sessionExpired, .retryBlocked: outcome = VerifierOutcomeEnded.shared
            case .networkError: outcome = VerifierOutcomeOffline.shared
            case .unknown: outcome = VerifierOutcomeFailed(code: "unknown")
            @unknown default: outcome = VerifierOutcomeFailed(code: "unknown")
            }
        }
        waiting?.resume(returning: outcome)
        waiting = nil
    }
    #else
    var available: Bool { false }

    func verify(sessionToken: String) async throws -> VerifierOutcome { VerifierOutcomeUnavailable.shared }
    #endif
}

extension View {
    /// Where Didit's screens are presented from: the app's root view carries the SDK's own SwiftUI modifier (its only
    /// way in), and every result goes to `DiditKycImpl`. Without the pod this is the view itself.
    @ViewBuilder
    func identityVerification() -> some View {
        #if canImport(DiditSDK)
        self.diditVerification { result in DiditKycImpl.shared.finished(result) }
        #else
        self
        #endif
    }
}
