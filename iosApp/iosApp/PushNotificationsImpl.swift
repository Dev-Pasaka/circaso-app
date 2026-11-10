import FirebaseCore
import FirebaseMessaging
import Shared
import UIKit
import UserNotifications

/// Message, payment and missed-call notifications (2026-11-10). The server sends them through FCM with an APNs block,
/// so this only gets the FCM token to it: Firebase Messaging, fed the APNs token by hand (FirebaseAppDelegateProxyEnabled
/// is off in Info.plist, so Firebase never swizzles AppDelegate or the notification delegate). Calls don't come this
/// way: they ring through PushKit and CallKit (CallKitVoip.swift).
///
/// A build without GoogleService-Info.plist (kept out of git, like Android's google-services.json) has no push.
final class PushNotificationsImpl: NSObject, IosPushApi, MessagingDelegate {
    static let shared = PushNotificationsImpl()

    /// Called from AppDelegate's didFinishLaunching, before anything asks for a token.
    static func install() {
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else { return }
        FirebaseApp.configure()
        Messaging.messaging().delegate = shared
        IosPushBridge.shared.api = shared
        // Already allowed earlier: register at every launch, so APNs and Firebase are ready before the first token ask.
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
            DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    /// Asked by the shared code once someone is signed in and online (PushTokenRegistrar): the permission sheet the
    /// first time, then the token. Nil while APNs hasn't answered yet; `didReceiveRegistrationToken` sends it then.
    func fetchToken(completion: @escaping (String?) -> Void) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            guard granted else { completion(nil); return }
            DispatchQueue.main.async {
                UIApplication.shared.registerForRemoteNotifications()
                Messaging.messaging().token { token, _ in completion(token) }
            }
        }
    }

    /// AppDelegate hands over APNs' device token; Firebase maps it to the FCM token.
    func apnsTokenReceived(_ deviceToken: Data) {
        guard FirebaseApp.app() != nil else { return }
        Messaging.messaging().apnsToken = deviceToken
    }

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let fcmToken, !fcmToken.isEmpty else { return }
        IosPushBridge.shared.tokenRefreshed(token: fcmToken)
    }
}
