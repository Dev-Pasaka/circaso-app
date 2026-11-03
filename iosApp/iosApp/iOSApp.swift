import GoogleSignIn
import Shared
import SwiftUI

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        GoogleSignInBridge.shared.provider = GoogleSignInImpl()
        SignalBridge.shared.api = SignalCryptoImpl() // libsignal for encrypted chats; see SignalBridge.kt
        LiveAudioBridge.shared.api = LiveAudioImpl() // live voice with Asha; see IosLiveAudio.kt
        LocationBridge.shared.provider = LocationImpl() // "Share location" in chats; see Media.kt
        CallEngineBridge.shared.provider = CallEngineImpl() // WebRTC calls through Cloudflare Realtime; see CallEngine.kt
        QrScannerBridge.shared.provider = QrScannerImpl() // VisionKit QR scanning (design E4/E5); see Qr.kt
        MomentCameraBridge.shared.provider = MomentCameraImpl() // AVFoundation camera for Moments (M1); see MomentCamera.kt
        MediaLibraryBridge.shared.provider = MediaLibraryImpl() // Photos, PHPicker and Files for Moments; see MediaLibrary.kt
        WalletSignerBridge.shared.signer = CircleWalletImpl() // the Circle SDK behind the wallet; see WalletSigner.kt and the Podfile
        BackgroundSyncImpl.install() // sends the offline queue in the background once there's a network; see BackgroundSync.kt
        BackgroundUploadImpl.install() // uploads carry on when Circaso is left mid-upload; see BackgroundUploads.kt
        MapImpl.install() // Google Maps for live location, when this build has a key; see PlatformMap.ios.kt
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    // Google's sign-in comes back to the app through its URL scheme.
                    GIDSignIn.sharedInstance.handle(url)
                }
        }
    }
}
