package com.circaso.feature.support

import com.circaso.core.common.AppResult
import com.circaso.core.common.Diagnostics
import com.circaso.core.common.DiagnosticsLog
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.ClientInfo
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/** SU5 `POST /v1/support/diagnostics` (2026-11-13): the phone's recent technical log, already scrubbed. */
@Serializable data class DiagnosticLogRequest(
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val log: String,
)

/** The code to give support (`DL-7K3QX9`), and until when the log is kept. */
@Serializable data class DiagnosticLogReceipt(val code: String, val expiresAt: String? = null)

/**
 * You → Help → "Send diagnostic log": uploads the rolling diagnostic log ([Diagnostics.log]) so support can see what
 * went wrong on this phone. Online only (the person waits for the code); the log holds no message text, names,
 * numbers, keys or amounts, and the server keeps it 30 days.
 */
class DiagnosticLogs(private val api: CircasoApi, private val client: ClientInfo, private val log: () -> DiagnosticsLog? = { Diagnostics.log }) {
    suspend fun send(): AppResult<DiagnosticLogReceipt> {
        Diagnostics.note("support: sending a diagnostic log")
        val text = log()?.read().orEmpty().let { if (it.encodeToByteArray().size > MaxBytes) it.takeLast(MaxBytes / 2).substringAfter('\n') else it }
        val body = DiagnosticLogRequest(
            client.platform, client.appVersion, client.osVersion,
            listOf(client.manufacturer, client.model).filter { it.isNotBlank() }.joinToString(" "),
            text.ifBlank { "(no notes yet)" },
        )
        val key = newIdempotencyKey()
        return api.call<DiagnosticLogReceipt> {
            method = HttpMethod.Post; url("/v1/support/diagnostics"); idempotencyKey(key)
            contentType(ContentType.Application.Json); setBody(body)
        }.also { if (it is AppResult.Success) Diagnostics.note("support: diagnostic log sent") }
    }

    companion object {
        /** The server takes up to 2 MiB; the newest part is what matters. */
        const val MaxBytes = 2_000_000
    }
}
