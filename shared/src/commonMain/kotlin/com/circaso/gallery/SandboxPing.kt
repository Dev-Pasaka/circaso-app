package com.circaso.gallery

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.auth.domain.AuthRepository

/**
 * Debug check against the real sandbox: one call that must succeed (nonce, no token) and one that must be
 * refused (phone start without a token, so no SMS can go out). Proves headers, envelope and error codes line up.
 */
suspend fun pingSandbox(auth: AuthRepository): List<String> {
    val lines = mutableListOf<String>()
    when (val nonce = auth.requestNonce()) {
        is AppResult.Success -> lines += "OK   nonce received (${nonce.value.length} characters)"
        is AppResult.Failure -> lines += "FAIL nonce: ${describe(nonce.error)}"
    }
    when (val phone = auth.startPhoneVerification("+254700000000")) {
        is AppResult.Success -> lines += "FAIL phone start worked without a token (it must not)"
        is AppResult.Failure -> {
            val e = phone.error
            lines += if (e is AppError.Api && e.httpStatus == 401) "OK   no token is refused: ${e.code}" else "FAIL expected a 401, got ${describe(e)}"
        }
    }
    return lines
}

private fun describe(error: AppError) = when (error) {
    is AppError.Api -> "${error.code} (HTTP ${error.httpStatus}, request ${error.requestId})"
    AppError.Network -> "no connection"
    is AppError.Unexpected -> "unexpected answer (HTTP ${error.httpStatus})"
}
