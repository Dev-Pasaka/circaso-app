import UIKit
import Foundation
import Shared
#if canImport(CircleProgrammableWalletSDK)
import CircleProgrammableWalletSDK
#endif

/// The Circle user-controlled wallets SDK behind the shared `WalletSignerBridge`: the person sets a PIN, answers
/// security questions, or signs a payment in Circle's screens, themed as Circaso (CirclePWTheme.json and
/// CirclePWLocalizable.strings in the app bundle). The SDK comes from CocoaPods (`pod 'CircleProgrammableWalletSDK'`,
/// see the Podfile); until `pod install` has run, this compiles without it and answers "not available".
final class CircleWalletImpl: NSObject, WalletSigner {
    private static let endpoint = "https://enduser-sdk.circle.com/v1/w3s"
    private var configuredAppId: String?
    private var configuredBiometrics: Bool?

    #if canImport(CircleProgrammableWalletSDK)
    var available: Bool { true }

    /// UNTESTED on iOS (2026-11-10; written without Xcode, mirrors the Android module, verify on a device).
    /// Circle's "biometrics instead of PIN" switch follows the wallet: on only for a wallet whose biometric approval
    /// the person turned on (`session.biometrics`) and for the set-up itself. On, Circle's PIN screen asks Face ID /
    /// Touch ID first and keeps its PIN pad if that fails or is cancelled. Off, there is no prompt and none of Circle's
    /// own "Allow biometrics" pages after a PIN. Circle refuses it on a phone with no biometrics set up (and in the
    /// Simulator): then it stays off and the PIN approves.
    private func configure(appId: String, biometrics: Bool) throws {
        if configuredAppId == appId && configuredBiometrics == biometrics { return }
        if biometrics {
            do {
                try WalletSdk.shared.setConfiguration(
                    WalletSdk.Configuration(endPoint: Self.endpoint, appId: appId, settingsManagement: WalletSdk.SettingsManagement(enableBiometricsPin: true))
                )
                configuredBiometrics = true
            } catch {
                print("CircasoNet: wallet: biometric PIN unavailable (\(error.localizedDescription)); PIN only")
                try WalletSdk.shared.setConfiguration(WalletSdk.Configuration(endPoint: Self.endpoint, appId: appId))
                configuredBiometrics = false
            }
        } else {
            try WalletSdk.shared.setConfiguration(
                WalletSdk.Configuration(endPoint: Self.endpoint, appId: appId, settingsManagement: WalletSdk.SettingsManagement(enableBiometricsPin: false))
            )
            configuredBiometrics = false
        }
        WalletSdk.shared.setLayoutProvider(CircasoLayout())
        configuredAppId = appId
    }

    /// Face ID for payments: the person confirms with the PIN once; Circle keeps the key in the Secure Enclave.
    func enableBiometrics(session: SdkSession) async throws -> SignOutcome {
        do {
            try configure(appId: session.appId, biometrics: true)
        } catch {
            return SignOutcomeFailed(code: "init_failed", message: error.localizedDescription)
        }
        if configuredBiometrics != true { return SignOutcomeFailed(code: "deviceNotSupportBiometrics", message: "Face ID isn't set up on this phone.") }
        return await withCheckedContinuation { continuation in
            DispatchQueue.main.async {
                WalletSdk.shared.setBiometricsPin(userToken: session.userToken, encryptionKey: session.encryptionKey) { response in
                    switch response.result {
                    case .success:
                        // Skip / "Don't ask me again" on Circle's page: the person's choice, nothing was set up.
                        if let warning = response.onWarning,
                           warning.warningType == .biometricsUserSkip || warning.warningType == .biometricsUserDisableForPin {
                            continuation.resume(returning: SignOutcomeCancelled.shared)
                            return
                        }
                        continuation.resume(returning: SignOutcomeDone.shared)
                    case .failure(let error):
                        response.onErrorController?.dismiss(animated: true)
                        if error.errorCode == .userCanceled {
                            continuation.resume(returning: SignOutcomeCancelled.shared)
                        } else {
                            continuation.resume(returning: SignOutcomeFailed(code: "\(error.errorCode)", message: error.displayString))
                        }
                    }
                }
            }
        }
    }

    /// Face ID re-enrolled, turned off or not allowed for this wallet: it falls back to its PIN, so stop showing it as on.
    private static func noteBiometrics(_ code: String, _ session: SdkSession) {
        if BiometricSignals.shared.lost(code: code, usedBiometrics: session.biometrics) {
            WalletSignerBridge.shared.reportBiometricsLost(walletId: session.subject.walletId)
        }
    }

    func execute(session: SdkSession, challengeId: String) async throws -> SignOutcome {
        do {
            try configure(appId: session.appId, biometrics: session.biometrics)
        } catch {
            return SignOutcomeFailed(code: "init_failed", message: error.localizedDescription)
        }
        return await withCheckedContinuation { continuation in
            DispatchQueue.main.async {
                WalletSdk.shared.execute(userToken: session.userToken, encryptionKey: session.encryptionKey, challengeIds: [challengeId]) { response in
                    if let warning = response.onWarning { Self.noteBiometrics("\(warning.warningType)", session) }
                    switch response.result {
                    case .success:
                        continuation.resume(returning: SignOutcomeDone.shared)
                    case .failure(let error):
                        response.onErrorController?.dismiss(animated: true)
                        Self.noteBiometrics("\(error.errorCode)", session)
                        if error.errorCode == .userCanceled {
                            continuation.resume(returning: SignOutcomeCancelled.shared)
                        } else {
                            continuation.resume(returning: SignOutcomeFailed(code: "\(error.errorCode)", message: error.displayString))
                        }
                    }
                }
            }
        }
    }

    /// Circaso's security questions and the SDK's own images; colours come from CirclePWTheme.json.
    private final class CircasoLayout: NSObject, WalletSdkLayoutProvider {
        func securityQuestions() -> [SecurityQuestion] {
            [
                SecurityQuestion(title: "What was the name of your first school?", inputType: .text),
                SecurityQuestion(title: "What is the name of the street you grew up on?", inputType: .text),
                SecurityQuestion(title: "What was your first pet called?", inputType: .text),
                SecurityQuestion(title: "What is your mother's first name?", inputType: .text),
                SecurityQuestion(title: "Which town were you born in?", inputType: .text),
                SecurityQuestion(title: "What was your childhood nickname?", inputType: .text),
            ]
        }
        func securityQuestionsRequiredCount() -> Int { 2 }
        func securityConfirmItems() -> [SecurityConfirmItem] {
            [
                SecurityConfirmItem(image: nil, text: "If I forget my PIN, my answers are the only way back into my wallet."),
                SecurityConfirmItem(image: nil, text: "Nobody at Circaso can see my answers or reset my PIN."),
            ]
        }
        func displayDateFormat() -> String { "dd/MM/yyyy" }
        /// Every icon Circle's screens draw (back, close, the PIN eye, checks, its three illustrations), as SF Symbols in
        /// Circaso's ink. An empty store leaves those buttons blank and untappable, which is how back broke on iOS.
        func imageStore() -> ImageStore {
            let ink = UIColor(red: 0x0B / 255, green: 0x0B / 255, blue: 0x0C / 255, alpha: 1)
            func symbol(_ name: String, _ size: CGFloat, _ weight: UIImage.SymbolWeight = .semibold) -> UIImage? {
                UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: weight))?
                    .withTintColor(ink, renderingMode: .alwaysOriginal)
            }
            let icons: [ImageStore.Img: UIImage?] = [
                .naviBack: symbol("chevron.left", 18),
                .naviClose: symbol("xmark", 17),
                .dropdownArrow: symbol("chevron.down", 14),
                .selectCheckMark: symbol("checkmark", 16),
                .errorInfo: symbol("exclamationmark.circle", 16),
                .showPin: symbol("eye", 18, .regular),
                .hidePin: symbol("eye.slash", 18, .regular),
                .showLessDetailArrow: symbol("chevron.up", 14),
                .showMoreDetailArrow: symbol("chevron.down", 14),
                .networkFeeTipIcon: symbol("info.circle", 15, .regular),
                .transactionTokenIcon: symbol("dollarsign.circle.fill", 28, .regular),
                .requestIcon: symbol("arrow.down.circle.fill", 28, .regular),
                .securityIntroMain: symbol("lock.shield", 96, .light),
                .securityConfirmMain: symbol("checkmark.shield", 96, .light),
                .biometricsAllowMain: symbol("faceid", 96, .light),
            ]
            return ImageStore(local: icons.compactMapValues { $0 }, remote: [:])
        }
        func themeFont() -> ThemeConfig.ThemeFont? { nil }
    }
    #else
    var available: Bool { false }

    func execute(session: SdkSession, challengeId: String) async throws -> SignOutcome { SignOutcomeUnavailable.shared }

    func enableBiometrics(session: SdkSession) async throws -> SignOutcome { SignOutcomeUnavailable.shared }
    #endif
}
