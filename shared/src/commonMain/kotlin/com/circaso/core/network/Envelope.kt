package com.circaso.core.network

import kotlinx.serialization.Serializable

/** Every answer from the API except health, the Asha event stream and speech audio. */
@Serializable
data class Envelope<T>(
    val httpStatusCode: Int = 0,
    val status: Boolean = false,
    val message: String? = null,
    val code: String? = null,
    val details: ErrorDetails? = null,
    val requestId: String? = null,
    val data: T? = null,
)

@Serializable
data class ErrorDetails(
    val retryAfterSeconds: Int? = null,
    val fields: List<FieldProblem>? = null,
) {
    /** Field name to problem, as [com.circaso.core.common.AppError.Api] keeps them. */
    fun fieldProblems(): Map<String, String> = fields.orEmpty().associate { it.field to it.problem }
}

/** One wrong field in a failure: `{ "field": "displayName", "problem": "required" }`. */
@Serializable
data class FieldProblem(val field: String, val problem: String)

/** Body type of routes that answer with an empty `data`. */
@Serializable
data class NoBody(val unused: String? = null)
