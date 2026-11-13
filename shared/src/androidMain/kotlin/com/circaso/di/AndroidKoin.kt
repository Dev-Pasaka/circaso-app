package com.circaso.di

import kotlinx.coroutines.launch

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.RoomDatabase
import com.circaso.core.database.CircasoDatabase
import com.circaso.core.database.DatabaseName
import com.circaso.core.datastore.PreferencesFileName
import com.circaso.core.datastore.createPreferencesDataStore
import com.circaso.core.platform.AndroidClientInfo
import com.circaso.core.platform.AndroidConnectivityMonitor
import com.circaso.core.platform.AndroidAudioPlayer
import com.circaso.core.platform.AndroidAudioRecorder
import com.circaso.core.platform.AndroidContactsReader
import com.circaso.core.platform.AndroidImagePicker
import com.circaso.core.platform.AndroidPushTokenProvider
import com.circaso.core.platform.PushTokenProvider
import com.circaso.core.platform.AudioPlayer
import com.circaso.core.platform.ImagePicker
import com.circaso.core.platform.AudioRecorder
import com.circaso.core.platform.AndroidDeviceKeyStore
import com.circaso.core.platform.ContactsReader
import com.circaso.core.platform.AndroidSecureStorage
import com.circaso.core.network.SocialConfig
import com.circaso.core.platform.AndroidSocialSignIn
import com.circaso.core.platform.AndroidStoreLauncher
import com.circaso.core.platform.CurrentActivity
import com.circaso.feature.auth.domain.SocialSignIn
import com.circaso.core.platform.ClientInfo
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.DeviceKeyStore
import com.circaso.core.platform.SecureStorage
import com.circaso.core.platform.StoreLauncher
import com.circaso.core.crypto.AndroidSignalCrypto
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.platform.AndroidChatNotifications
import com.circaso.core.platform.AndroidLiveMicrophone
import com.circaso.core.platform.AndroidLiveSpeaker
import com.circaso.core.platform.AndroidLocalSealer
import com.circaso.core.platform.LiveMicrophone
import com.circaso.core.platform.LiveSpeaker
import com.circaso.core.platform.ChatNotifications
import com.circaso.core.platform.LocalSealer
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import java.io.File

/** Called once from `CircasoApplication.onCreate`. */
fun initKoinAndroid(context: Context) {
    val app = context.applicationContext
    if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        com.circaso.core.common.Diagnostics.sink = { android.util.Log.i("CircasoNet", it) }
    }
    // Every build keeps a rolling, scrubbed diagnostic log in its private files (You → Help → Send diagnostic log).
    com.circaso.core.common.Diagnostics.log = com.circaso.core.common.DiagnosticsLog(okio.FileSystem.SYSTEM, okio.Path.Companion.run { File(app.filesDir, "diagnostics").absolutePath.toPath() })
    com.circaso.core.common.Diagnostics.note("app: started ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
    initKoin(
        module {
            single<ClientInfo> { AndroidClientInfo(app) }
            single<ConnectivityMonitor> { AndroidConnectivityMonitor(app) }
            single<StoreLauncher> { AndroidStoreLauncher(app) }
            single<com.circaso.core.platform.Biometrics> { com.circaso.core.platform.AndroidBiometrics(app) }
            single<SecureStorage> { AndroidSecureStorage(app) }
            single { CurrentActivity(app as Application) }
            single<ContactsReader> { AndroidContactsReader(app) }
            single<com.circaso.core.platform.PasskeyPrf> { com.circaso.core.platform.AndroidPasskeyPrf(get()) } // the PIN Vault
            single<AudioRecorder> { AndroidAudioRecorder(app) }
            single<AudioPlayer> { AndroidAudioPlayer(app) }
            single<LiveMicrophone> { AndroidLiveMicrophone(app) }
            single<com.circaso.feature.assistant.voice.VadEngine> { com.circaso.core.platform.AndroidVadEngine() } // Silero VAD for barge-in
            single<com.circaso.core.qr.QrScanner> { com.circaso.core.qr.AndroidQrScanner(currentActivityProvider()) }
            single<com.circaso.core.calls.CallEngine> { com.circaso.core.calls.AndroidCallEngine(app) }
            single { com.circaso.core.calls.AndroidCallNotifier(app, get()).also { n -> ringing = { id -> n.clear(id) } } }
            single(createdAtStart = true) { com.circaso.core.calls.CallKeepAlive(app, get(), get()).also { it.start() } }
            single(createdAtStart = true) {
                val notices = get<com.circaso.core.notifications.PushNotices>()
                com.circaso.core.calls.CallRinger(app, get(), get(), get(), get(), words = { name, video -> notices.incomingCall(name, video) }).also { it.start() }
            }
            single<com.circaso.core.platform.LocationProvider> { com.circaso.core.platform.AndroidLocation(app) }
            single<com.circaso.feature.messaging.data.repository.LiveTracker> { com.circaso.core.platform.AndroidLiveTracker(app) }
            single<com.circaso.core.sync.BackgroundSync> { com.circaso.core.sync.AndroidBackgroundSync(app) }
            single<com.circaso.core.platform.BackgroundUploads> { com.circaso.core.platform.AndroidBackgroundUploads(app) }
            single<com.circaso.core.platform.MediaFiles> { com.circaso.core.platform.AndroidMediaFiles(app) }
            single<LiveSpeaker> { AndroidLiveSpeaker(app) }
            single<ImagePicker> { AndroidImagePicker(app) }
            single<com.circaso.core.platform.GroupPhotoEncoder> { com.circaso.core.platform.AndroidGroupPhotoEncoder() }
            single<com.circaso.core.platform.MomentCamera> { com.circaso.core.platform.AndroidMomentCamera(app) }
            single<com.circaso.core.platform.MediaLibrary> { com.circaso.core.platform.AndroidMediaLibrary(app) }
            single<PushTokenProvider> { AndroidPushTokenProvider() }
            single<SocialSignIn> { AndroidSocialSignIn(get(), SocialConfig.GoogleWebClientId) }
            single<DeviceKeyStore> { AndroidDeviceKeyStore() }
            single<SignalCrypto> { AndroidSignalCrypto() }
            single<LocalSealer> { AndroidLocalSealer() }
            single<ChatNotifications> { AndroidChatNotifications(app) }
            single<RoomDatabase.Builder<CircasoDatabase>> {
                Room.databaseBuilder<CircasoDatabase>(app, app.getDatabasePath(DatabaseName).absolutePath)
            }
            single<DataStore<Preferences>> {
                createPreferencesDataStore(File(app.filesDir, "datastore/$PreferencesFileName").absolutePath)
            }
        },
    )
    // Created now, not at the first tap: it has to be watching before the first activity resumes, or it never sees one.
    KoinPlatform.getKoin().get<CurrentActivity>()
}

/** For modules outside `shared` that must present over the Activity in front (the Circle SDK's PIN screens). */
fun currentActivityProvider(): () -> android.app.Activity? {
    val activities = KoinPlatform.getKoin().get<com.circaso.core.platform.CurrentActivity>()
    return { activities.get() }
}

/** Whether a call is ringing or on: the activity shows over the lock screen while it is (see MainActivity). */
fun callOnScreen(): kotlinx.coroutines.flow.Flow<Boolean> =
    runCatching { org.koin.mp.KoinPlatform.getKoin().get<com.circaso.feature.calls.CallController>().state }.getOrNull()
        ?.let { state -> kotlinx.coroutines.flow.flow { state.collect { s -> emit(s != null && s.phase != com.circaso.feature.calls.CallPhase.Ended) } } }
        ?: kotlinx.coroutines.flow.flowOf(false)

/** The incoming-call notification was tapped: load that call and show its answer screen (design H1). */
fun openRingingCall(callId: String) {
    runCatching {
        val koin = org.koin.mp.KoinPlatform.getKoin()
        koin.get<com.circaso.core.calls.AndroidCallNotifier>().clear(callId)
        val scope = koin.get<kotlinx.coroutines.CoroutineScope>()
        scope.launch {
            koin.get<com.circaso.feature.calls.CallController>().onFrame(
                "call.ringing",
                kotlinx.serialization.json.buildJsonObject { put("callId", kotlinx.serialization.json.JsonPrimitive(callId)) },
            )
        }
    }
}

/** Debug builds only (the handler checks): queues a dummy upload of [megabytes] through the real pipeline. */
fun queueDebugUpload(megabytes: Int) {
    val koin = runCatching { KoinPlatform.getKoin() }.getOrNull() ?: return
    koin.get<kotlinx.coroutines.CoroutineScope>().launch {
        koin.get<com.circaso.feature.status.data.repository.DebugUploadHandler>().queue(megabytes * 1024 * 1024)
    }
}
