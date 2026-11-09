package com.circaso.core.calls

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android's own "Start recording or casting?" prompt for screen sharing on a call. Registered with the activity while
 * it is created, as PermissionBridge is; the answer is the consent the projection is made from, or null for no.
 */
object ScreenCaptureBridge {
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pending: CompletableDeferred<Intent?>? = null

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pending?.complete(if (result.resultCode == Activity.RESULT_OK) result.data else null)
            pending = null
        }
    }

    suspend fun request(context: Context): Intent? = withContext(Dispatchers.Main) {
        val l = launcher ?: return@withContext null
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return@withContext null
        // A second tap while the prompt is up never leaves the first one waiting forever.
        pending?.complete(null)
        val answer = CompletableDeferred<Intent?>().also { pending = it }
        runCatching { l.launch(manager.createScreenCaptureIntent()) }.onFailure { answer.complete(null); pending = null }
        answer.await()
    }
}
