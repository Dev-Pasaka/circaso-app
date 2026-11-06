package com.circaso.core.platform

/**
 * Message notifications. The phone shows one when a message arrives for a chat that is not on screen; a push wake
 * (guide, section 8) decrypts first and then calls [show] with the real sender and text, never the placeholder.
 */
interface ChatNotifications {
    /** The chat whose thread is on screen right now: no notification is shown for it. */
    var activeChatId: String?

    /** Asks for permission the first time the Chats tab is shown (Android 13+), never at launch. */
    suspend fun ensurePermission()

    fun show(chatId: String, title: String, text: String, messageId: String?)
    fun clear(chatId: String)

    /** Notification privacy was just turned on: take down the ones already showing names, amounts or messages. */
    fun clearShown() {}
}

object NoChatNotifications : ChatNotifications {
    override var activeChatId: String? = null
    override suspend fun ensurePermission() {}
    override fun show(chatId: String, title: String, text: String, messageId: String?) {}
    override fun clear(chatId: String) {}
}
