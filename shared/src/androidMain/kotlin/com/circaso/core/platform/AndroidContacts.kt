package com.circaso.core.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridges the Activity Result API (which must be registered while the activity is being created) to a suspending call.
 * `MainActivity` calls [register] in `onCreate`.
 */
object PermissionBridge {
    private var launcher: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Boolean>? = null
    private var many: ActivityResultLauncher<Array<String>>? = null
    private var pendingMany: CompletableDeferred<Map<String, Boolean>>? = null

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> pending?.complete(granted) }
        many = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> pendingMany?.complete(result) }
    }

    /**
     * Several permissions in one system prompt (Android 14's photo and video access is asked with
     * READ_MEDIA_VISUAL_USER_SELECTED alongside, so "Select photos and videos" can be offered). What was granted.
     */
    suspend fun requestAll(permissions: Array<String>): Map<String, Boolean> {
        val l = many ?: return emptyMap()
        val answer = CompletableDeferred<Map<String, Boolean>>().also { pendingMany = it }
        l.launch(permissions)
        return answer.await()
    }

    suspend fun request(permission: String): Boolean {
        val l = launcher ?: return false
        val answer = CompletableDeferred<Boolean>().also { pending = it }
        l.launch(permission)
        return answer.await()
    }
}

/** Reads only the number column of the phone book; names are never queried. */
class AndroidContactsReader(private val context: Context) : ContactsReader {
    override suspend fun hasAccess() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override suspend fun requestAccess(): Boolean = hasAccess() || PermissionBridge.request(Manifest.permission.READ_CONTACTS)

    override suspend fun readNumbers(): List<String> = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext emptyList()
        val numbers = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), // the number only
            null, null, null,
        )?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) cursor.getString(column)?.let(numbers::add)
        }
        numbers
    }
}
