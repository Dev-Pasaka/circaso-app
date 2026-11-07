package com.circaso.core.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.circaso.feature.mediacompose.IncomingShares
import com.circaso.feature.mediacompose.SharedFile
import com.circaso.feature.mediacompose.shareFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Photos, videos and files shared into Circaso from other apps (ACTION_SEND, ACTION_SEND_MULTIPLE; 2026-11-07). Each
 * item is copied into Circaso's own storage at once, while the other app's permission lasts, then handed to
 * [IncomingShares]. The list is kept on disk too, so a share survives Circaso being closed during sign-in.
 */
object AndroidShareIntake {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(context: Context) = File(context.filesDir, "incoming-shares").apply { mkdirs() }
    private fun manifest(context: Context) = File(dir(context), "pending.json")

    /** Called once at launch: brings back a share still waiting, and keeps the copy on disk in step from now on. */
    fun install(context: Context) {
        val app = context.applicationContext
        IncomingShares.onChange = { files ->
            scope.launch {
                runCatching {
                    if (files == null) manifest(app).delete()
                    else manifest(app).writeText(json.encodeToString(ListSerializer(SharedFile.serializer()), files))
                }
            }
        }
        // Copies more than a week old that nothing waits for any more (sent, or let go) are cleared.
        scope.launch {
            val keep = runCatching { manifest(app).readText() }.getOrDefault("")
            dir(app).listFiles()?.filter { it.isDirectory && it.lastModified() < System.currentTimeMillis() - 7 * 86_400_000L && it.name !in keep }?.forEach { it.deleteRecursively() }
        }
        if (IncomingShares.pending.value == null) scope.launch {
            val kept = runCatching { json.decodeFromString(ListSerializer(SharedFile.serializer()), manifest(app).readText()) }.getOrNull()
                ?.filter { File(it.path).exists() || it.size > MomentMediaMaxBytes } // an item too large was never copied: kept to say so
            if (!kept.isNullOrEmpty()) IncomingShares.offer(kept)
        }
    }

    /** True when [intent] was a share into Circaso; its items arrive in [IncomingShares] once copied. */
    fun handle(context: Context, intent: Intent?): Boolean {
        val uris = sharedUris(intent) ?: return false
        android.util.Log.i("CircasoShare", "Shared in: ${uris.size} item(s)")
        if (uris.isEmpty()) return true
        val app = context.applicationContext
        val type = intent?.type
        scope.launch {
            val files = uris.mapIndexedNotNull { i, uri -> copy(app, uri, type, i) }
            IncomingShares.offer(files)
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent?): List<Uri>? = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> null
    }

    /**
     * One shared item into Circaso's storage under its own name. Never more than the attachment limit is copied: a
     * larger one keeps its real size (from the provider) so the picker can say it can't be sent.
     */
    private fun copy(context: Context, uri: Uri, intentType: String?, index: Int): SharedFile? = runCatching {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = resolver.getType(uri) ?: intentType?.takeIf { '*' !in it } ?: ""
        val shown = shareFileName(name ?: uri.lastPathSegment, mime, index)
        val folder = File(dir(context), "s-${System.nanoTime()}").apply { mkdirs() }
        val out = File(folder, shown)
        if (size > MomentMediaMaxBytes) return@runCatching SharedFile(out.absolutePath, shown, mime, size)
        var copied = 0L
        resolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { o ->
                val buffer = ByteArray(64 * 1024)
                while (copied <= MomentMediaMaxBytes) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    o.write(buffer, 0, n)
                    copied += n
                }
            }
        } ?: run { folder.deleteRecursively(); return@runCatching null }
        SharedFile(out.absolutePath, shown, mime, if (size >= 0) maxOf(size, copied) else copied)
    }.onFailure { android.util.Log.w("CircasoShare", "A shared item couldn't be copied: ${it::class.simpleName}") }.getOrNull()
}

