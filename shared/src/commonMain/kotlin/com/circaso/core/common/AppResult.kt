package com.circaso.core.common

/** What went wrong, in terms the rest of the app can act on. UI text is keyed on [Api.code], never on a message. */
sealed interface AppError {
    /** The server answered with a failure envelope. */
    data class Api(
        val code: String,
        val httpStatus: Int,
        val requestId: String?,
        val retryAfterSeconds: Int?,
        val fields: Map<String, String>,
    ) : AppError

    /** No answer: offline, timeout, dropped connection. Never a reason to sign out or clear data. */
    data object Network : AppError

    /** An answer we could not understand (not an envelope, or data missing). */
    data class Unexpected(val httpStatus: Int?) : AppError
}

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value
