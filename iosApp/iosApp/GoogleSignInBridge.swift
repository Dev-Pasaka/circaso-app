import GoogleSignIn
import Shared
import UIKit

/// Google's SDK lives here; the shared Kotlin code only sees an ID token. The client ids come from Info.plist
/// (`GIDClientID` is the iOS one, `GIDServerClientID` the Web one the server accepts).
final class GoogleSignInImpl: NSObject, GoogleSignInProvider {
    func signIn(nonce: String, completion: @escaping (String?, String?) -> Void) {
        guard let presenter = Self.topController() else {
            completion(nil, "failed")
            return
        }
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter, hint: nil, additionalScopes: nil, nonce: nonce) { result, error in
            if let error = error as NSError? {
                completion(nil, error.code == GIDSignInError.canceled.rawValue ? "cancelled" : "failed")
                return
            }
            if let token = result?.user.idToken?.tokenString {
                completion(token, nil)
            } else {
                completion(nil, "failed")
            }
        }
    }

    private static func topController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
