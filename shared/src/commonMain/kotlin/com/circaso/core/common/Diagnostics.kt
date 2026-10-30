package com.circaso.core.common

/**
 * Outcome-only breadcrumbs ("refresh ok", "socket closed") so a build can be checked on a real phone. Never pass a token,
 * key, code, phone number or message text. The platform sets [sink] for debug builds; release builds leave it null.
 */
object Diagnostics {
    var sink: ((String) -> Unit)? = null
    fun note(message: String) { sink?.invoke(message) }
}
