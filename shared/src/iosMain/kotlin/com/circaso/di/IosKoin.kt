package com.circaso.di

import com.circaso.core.common.map
import com.circaso.core.network.call
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.RoomDatabase
import com.circaso.core.database.CircasoDatabase
import com.circaso.core.database.DatabaseName
import com.circaso.core.datastore.PreferencesFileName
import com.circaso.core.datastore.createPreferencesDataStore
import com.circaso.core.crypto.IosSignalCrypto
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.ClientInfo
import com.circaso.core.platform.ImagePicker
import com.circaso.core.platform.NoPushToken
import com.circaso.core.platform.PushTokenProvider
import com.circaso.core.platform.IosImagePicker
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.DeviceKeyStore
import com.circaso.core.platform.ContactsReader
import com.circaso.core.platform.IosAudioPlayer
import com.circaso.core.platform.IosLiveMicrophone
import com.circaso.core.platform.IosLiveSpeaker
import com.circaso.core.platform.LiveMicrophone
import com.circaso.core.platform.LiveSpeaker
import com.circaso.core.platform.IosAudioRecorder
import com.circaso.core.platform.IosClientInfo
import com.circaso.core.platform.IosContactsReader
import com.circaso.core.platform.IosConnectivityMonitor
import com.circaso.core.platform.IosDeviceKeyStore
import com.circaso.core.platform.IosLocalSealer
import com.circaso.core.platform.IosSecureStorage
import com.circaso.core.platform.ChatNotifications
import com.circaso.core.platform.LocalSealer
import com.circaso.core.platform.NoChatNotifications
import com.circaso.core.platform.IosSocialSignIn
import com.circaso.feature.auth.domain.SocialSignIn
import com.circaso.core.platform.IosStoreLauncher
import com.circaso.core.platform.SecureStorage
import com.circaso.core.platform.StoreLauncher
import com.circaso.core.platform.applicationSupportPath
import org.koin.dsl.module
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/** Called once from `MainViewController` before any UI is created. */
@OptIn(ExperimentalNativeApi::class)
fun initKoinIos() {
    // Debug breadcrumbs (outcomes only, never tokens or accounts) go to the console of the run.
    if (Platform.isDebugBinary) com.circaso.core.common.Diagnostics.sink = { println("CircasoNet: $it") }
    initKoin(
    module {
        single<ClientInfo> { IosClientInfo() }
        single<ConnectivityMonitor> { IosConnectivityMonitor() }
        single<StoreLauncher> { IosStoreLauncher() }
        single<com.circaso.core.platform.Biometrics> { com.circaso.core.platform.IosBiometrics() }
        single<SecureStorage> { IosSecureStorage() }
        single<SocialSignIn> { IosSocialSignIn() }
        single<com.circaso.core.platform.PasskeyPrf> { com.circaso.core.platform.IosPasskeyPrf() } // the PIN Vault; see PasskeyBridge
        single<ContactsReader> { IosContactsReader() }
        single<DeviceKeyStore> { IosDeviceKeyStore() }
        single<SignalCrypto> { IosSignalCrypto() } // libsignal lives on the Swift side; see SignalBridge
        single<LocalSealer> { IosLocalSealer(get(), get()) }
        single<ChatNotifications> { NoChatNotifications } // the server's APNs alert shows as is until the Notification Service Extension (M7)
        single<AudioRecorder> { IosAudioRecorder() }
        single<AudioPlayer> { IosAudioPlayer() }
        single<com.circaso.core.qr.QrScanner> { com.circaso.core.qr.IosQrScanner() }
        single<com.circaso.core.calls.CallEngine> { com.circaso.core.calls.IosCallEngine() }
        single<com.circaso.core.platform.LocationProvider> { com.circaso.core.platform.IosLocation() }
        single<com.circaso.feature.messaging.data.repository.LiveTracker> { com.circaso.core.platform.IosLiveTracker() }
        single<com.circaso.core.platform.BackgroundUploads> { com.circaso.core.platform.IosBackgroundUploads() }
        single<com.circaso.core.sync.BackgroundSync> { com.circaso.core.sync.IosBackgroundSync() }
        single<com.circaso.core.platform.MediaFiles> { com.circaso.core.platform.IosMediaFiles() }
        single<LiveMicrophone> { IosLiveMicrophone() } // AVAudioEngine and Apple's Opus encoder live on the Swift side; see LiveAudioBridge
        single<LiveSpeaker> { IosLiveSpeaker() }
        single<com.circaso.feature.assistant.voice.VadEngine> { com.circaso.core.platform.IosVadEngine() } // Silero VAD on ONNX Runtime, Swift side; see VadBridge
        single<ImagePicker> { IosImagePicker() }
        single<com.circaso.core.platform.GroupPhotoEncoder> { com.circaso.core.platform.IosGroupPhotoEncoder() }
        single<com.circaso.core.platform.MomentCamera> { com.circaso.core.platform.IosMomentCamera() } // AVFoundation lives on the Swift side; see MomentCameraBridge
        single<com.circaso.core.platform.MediaLibrary> { com.circaso.core.platform.IosMediaLibrary() } // Photos recents here; loading and pickers on the Swift side, see MediaLibraryBridge
        // APNs needs the Push Notifications capability and a server-side key; until then there is no address to send.
        single<PushTokenProvider> { NoPushToken }
        // Calls ring through PushKit (VoIP pushes, sent by the server straight to APNs) and CallKit, even with Circaso
        // closed: this phone's VoIP token goes to `PUT /v1/devices/current/voip-token`. See IosCallKit.kt.
        single(org.koin.core.qualifier.named("voip"), createdAtStart = true) {
            val auth = get<com.circaso.core.session.AuthManager>(); val api = get<com.circaso.core.network.CircasoApi>()
            val connection = get<com.circaso.core.sync.ConnectionState>()
            com.circaso.core.push.PushTokenRegistrar(
                ready = kotlinx.coroutines.flow.combine(auth.state, connection.status) { a, c ->
                    a == com.circaso.core.session.AuthState.Active && c == com.circaso.core.platform.ConnectionStatus.Online
                },
                userId = { auth.userId },
                provider = com.circaso.core.calls.IosVoipTokenProvider,
                send = { token ->
                    api.call<com.circaso.core.network.NoBody> {
                        method = HttpMethod.Put
                        url("/v1/devices/current/voip-token")
                        contentType(ContentType.Application.Json)
                        setBody(com.circaso.core.calls.VoipTokenRequest(token))
                    }.map { }
                },
                scope = get(),
            ).also { registrar ->
                registrar.start()
                com.circaso.core.calls.IosCallKitBridge.onToken = registrar::onNewToken
            }
        }
        single<RoomDatabase.Builder<CircasoDatabase>> {
            Room.databaseBuilder<CircasoDatabase>(name = applicationSupportPath(DatabaseName))
        }
        single<DataStore<Preferences>> { createPreferencesDataStore(applicationSupportPath(PreferencesFileName)) }
    },
    )
}
