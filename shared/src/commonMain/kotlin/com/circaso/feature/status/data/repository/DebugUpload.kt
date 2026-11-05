package com.circaso.feature.status.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.BackgroundUploads
import com.circaso.core.platform.MediaFiles
import com.circaso.core.platform.ResumableUpload
import com.circaso.core.platform.UploadKind
import com.circaso.core.platform.UploadSlot
import com.circaso.core.platform.UploadState
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.status.data.remote.MomentsRemote
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

@Serializable internal data class DebugUploadJob(val name: String, val sizeBytes: Int)

/**
 * Debug builds only (the trigger is an `adb` intent extra the activity reads under `BuildConfig.DEBUG`): a dummy
 * upload through the real pipeline (outbox, background work, ResumableUpload, ST1 → PUT → ST2) that nobody else sees.
 * It stops before ST3, so nothing is posted; the server deletes the unposted file within the hour. Used to prove on a
 * phone that an upload survives leaving Circaso and the process being killed.
 */
class DebugUploadHandler(
    private val remote: MomentsRemote,
    private val files: MediaFiles,
    private val crypto: SignalCrypto,
    private val uploads: BackgroundUploads,
    storage: HttpClient,
    private val outbox: Outbox,
    private val kickOutbox: () -> Unit,
    private val json: Json,
    private val enabled: Boolean,
    now: () -> Long,
) : OutboxHandler {
    override val type: String = Type
    private val resumable = ResumableUpload(crypto, files, uploads, storage, now)

    suspend fun queue(sizeBytes: Int) {
        if (!enabled) return
        val job = DebugUploadJob("debug-upload-" + newIdempotencyKey(), sizeBytes.coerceIn(1, 30 * 1024 * 1024))
        outbox.enqueue(Type, json.encodeToString(DebugUploadJob.serializer(), job))
        log("queued ${job.name} (${job.sizeBytes} bytes)")
        kickOutbox()
    }

    override suspend fun send(item: OutboxEntity): AppResult<Unit> {
        if (!enabled) return AppResult.Success(Unit)
        val job = runCatching { json.decodeFromString(DebugUploadJob.serializer(), item.payload) }.getOrNull() ?: return AppResult.Success(Unit)
        return uploads.keep(UploadKind.Video, "debug") {
            if (files.read(job.name) == null) files.write(job.name, crypto.randomBytes(job.sizeBytes)) // the "prepared" file
            val stateName = job.name + ".state"
            val state = files.read(stateName)?.let { runCatching { json.decodeFromString(UploadState.serializer(), it.decodeToString()) }.getOrNull() }
            log("send ${job.name}: resuming from ${state?.let { if (it.put) "put done" else if (it.slotId != null) "slot" else "sealed" } ?: "start"} (attempt ${item.attempts})")
            val r = resumable.run(
                plainFile = job.name, sealedFile = job.name + ".sealed", state = state,
                save = { s -> files.write(stateName, json.encodeToString(UploadState.serializer(), s).encodeToByteArray()); log("${job.name}: saved ${if (s.put) "put done" else if (s.slotId != null) "slot" else "sealed"}") },
                create = { size ->
                    when (val slot = remote.prepareMedia(size, "video", newIdempotencyKey())) {
                        is AppResult.Failure -> slot
                        is AppResult.Success -> AppResult.Success(UploadSlot(slot.value.mediaId, slot.value.uploadUrl, mapOf("Content-Type" to slot.value.uploadHeaders.contentType),
                            runCatching { Instant.parse(slot.value.expiresAt).toEpochMilliseconds() }.getOrNull()))
                    }
                },
                complete = { id -> when (val c = remote.completeMedia(id, newIdempotencyKey())) { is AppResult.Failure -> c; is AppResult.Success -> AppResult.Success(Unit) } },
                startOver = { e -> (e as? AppError.Api)?.code in setOf("status.media_invalid", "status.media_not_found") },
                missing = AppError.Api("status.media_missing", 400, null, null, emptyMap()),
            )
            when (r) {
                is AppResult.Success -> {
                    log("committed ${job.name}: media ${r.value.id} (${r.value.size} bytes)")
                    files.delete(job.name); files.delete(stateName)
                    AppResult.Success(Unit)
                }
                is AppResult.Failure -> { log("failed ${job.name}: ${r.error}"); r }
            }
        }
    }

    private fun log(message: String) = println("CircasoUploads debug: $message")

    companion object {
        const val Type = "debug.upload"
    }
}
