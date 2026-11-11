import AuthenticationServices
import CryptoKit
import Foundation
import Shared
import UIKit

/// PRF passkeys for the PIN Vault (2026-11-11): AuthenticationServices' platform passkeys (iCloud Keychain) with the
/// WebAuthn PRF extension, iOS 18+. The shared Kotlin code turns the PRF secret into the vault's key; nothing here is
/// checked by our server, so challenges are random and local. Needs the `webcredentials:circaso.com` associated domain.
final class PasskeyImpl: NSObject, PasskeyPrfProvider {
    private var pending: [ObjectIdentifier: Request] = [:]

    func isSupported() -> Bool {
        if #available(iOS 18.0, *) { return true }
        return false
    }

    func create(userId: String, userName: String, displayName: String, relyingParty: String, prfSalt: Data,
                completion: @escaping (Data?, Data?, String?) -> Void) {
        guard #available(iOS 18.0, *) else { completion(nil, nil, "unsupported"); return }
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: relyingParty)
        let request = provider.createCredentialRegistrationRequest(
            challenge: Self.random(32), name: userName, userID: Data(userId.utf8))
        request.displayName = displayName
        request.userVerificationPreference = .required
        request.prf = .inputValues(.init(saltInput1: prfSalt))
        perform(request) { authorization, reason in
            guard let credential = authorization?.credential as? ASAuthorizationPlatformPublicKeyCredentialRegistration else {
                completion(nil, nil, reason ?? "failed"); return
            }
            guard let prf = credential.prf, prf.isSupported else { completion(nil, nil, "unsupported"); return }
            completion(credential.credentialID, prf.first.map { $0.withUnsafeBytes { Data($0) } }, nil)
        }
    }

    func secret(credentialId: Data, relyingParty: String, prfSalt: Data, completion: @escaping (Data?, Data?, String?) -> Void) {
        guard #available(iOS 18.0, *) else { completion(nil, nil, "unsupported"); return }
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: relyingParty)
        let request = provider.createCredentialAssertionRequest(challenge: Self.random(32))
        request.allowedCredentials = [ASAuthorizationPlatformPublicKeyCredentialDescriptor(credentialID: credentialId)]
        request.userVerificationPreference = .required
        request.prf = .inputValues(.init(saltInput1: prfSalt))
        perform(request) { authorization, reason in
            guard let credential = authorization?.credential as? ASAuthorizationPlatformPublicKeyCredentialAssertion else {
                completion(nil, nil, reason ?? "failed"); return
            }
            guard let first = credential.prf?.first else { completion(nil, nil, "unsupported"); return }
            completion(credential.credentialID, first.withUnsafeBytes { Data($0) }, nil)
        }
    }

    // MARK: - one ASAuthorizationController per request, kept alive until it answers

    private final class Request: NSObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
        let done: (ASAuthorization?, String?) -> Void
        init(done: @escaping (ASAuthorization?, String?) -> Void) { self.done = done }

        func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
            done(authorization, nil)
        }

        func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
            let code = (error as? ASAuthorizationError)?.code
            done(nil, code == .canceled ? "cancelled" : "failed:\((error as NSError).code)")
        }

        func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
            let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
            return scene?.windows.first(where: { $0.isKeyWindow }) ?? ASPresentationAnchor()
        }
    }

    private func perform(_ request: ASAuthorizationRequest, done: @escaping (ASAuthorization?, String?) -> Void) {
        DispatchQueue.main.async {
            let controller = ASAuthorizationController(authorizationRequests: [request])
            var holder: Request?
            holder = Request { [weak self] authorization, reason in
                if let controller = holder.map(ObjectIdentifier.init) { self?.pending[controller] = nil }
                done(authorization, reason)
            }
            guard let delegate = holder else { return }
            self.pending[ObjectIdentifier(delegate)] = delegate
            controller.delegate = delegate
            controller.presentationContextProvider = delegate
            controller.performRequests()
        }
    }

    private static func random(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }
}
