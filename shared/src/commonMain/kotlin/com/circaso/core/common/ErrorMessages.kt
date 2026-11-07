package com.circaso.core.common

/**
 * Words for errors, keyed on the error `code` (never on the server's English `message`). Screens add their
 * own context where the design asks for it; everything here is plain and says what the person can do.
 * English only for now: the strings move to resources when the app is localised.
 */
object ErrorMessages {
    const val Offline = "No connection. Check your internet and try again."
    const val SomethingWrong = "Something went wrong on our side. Please try again."

    private val byCode = mapOf(
        // sign in
        "identity.nonce_invalid" to "That took too long. Please try again.",
        "identity.provider_token_invalid" to "We couldn't check that sign-in. Please try again.",
        "identity.recovery_pending" to "A recovery request for this account is in progress.",
        "identity.account_suspended" to "Your account is paused. Contact support to get it back.",
        "identity.account_blocked" to "This account can no longer use Circaso.",
        "identity.account_deleted" to "This account was deleted.",
        // phone
        "identity.phone_invalid" to "That number doesn't look right. Check it and try again.",
        "identity.code_invalid" to "That code isn't right. Check it and try again.",
        "identity.code_expired" to "That code has expired. We'll send you a new one.",
        "identity.code_locked" to "Too many tries. Try again in 10 minutes.",
        "identity.phone_taken" to "This number already has a Circaso account.",
        "identity.phone_mismatch" to "That isn't the number on this account.",
        "identity.sms_unavailable" to "We couldn't send the text just now. Please try again.",
        "identity.carrier_unsupported" to "We couldn't confirm the number automatically, so we'll text you a code instead.",
        // device and session
        "identity.device_proof_invalid" to "Check your phone's date and time, then try again.",
        "identity.device_attestation_failed" to "We couldn't confirm this phone is genuine.",
        "identity.session_revoked" to "You were signed out. Sign in again to carry on.",
        "request.client_outdated" to "Update Circaso to continue.",
        "request.rate_limited" to "Too many tries. Please wait a moment.",
        "request.in_progress" to "Still working on that. One moment.",
        "service.unavailable" to "Circaso is busy right now. Please try again in a moment.",
        "request.not_found" to "We couldn't find that account.",
        // profile, handles, contacts
        "users.profile_invalid" to "Those details can't be saved. Check them and try again.",
        "users.version_conflict" to "Your profile changed on another phone. We've loaded the latest, so check and save again.",
        "users.handle_invalid" to "Handles use 3 or more letters, numbers, dots or underscores.",
        "users.handle_taken" to "That handle is taken. Try another.",
        "users.handle_cooldown" to "You changed your handle recently. You can change it again later.",
        "users.handle_not_found" to "We couldn't find anyone with that handle.",
        "users.avatar_invalid" to "Pictures must be JPEG, PNG or WebP, up to 5 MB.",
        "users.not_reachable" to "You can't reach this person.",
        "users.invite_invalid" to "That invite isn't valid any more.",
        "users.cannot_block_self" to "You can't block yourself.",
        "users.contact_not_found" to "That contact is already gone.",
        "users.sync_too_large" to "That's too many contacts at once. We'll send them in smaller groups.",
        // account
        "identity.provider_in_use" to "That account is already used by someone else on Circaso.",
        "identity.provider_last" to "That's your only sign-in, so it can't be removed.",
        "identity.wallet_not_empty" to "Send your money out first, then you can delete your account.",
        "identity.recovery_not_eligible" to "We can't start a recovery for that number.",
        // assistant
        "assistant.quota_exceeded" to "You've used today's questions for Asha. They reset tomorrow.",
        "assistant.model_unavailable" to "Asha isn't available right now. Please try again in a moment.",
        "assistant.unavailable" to "Asha's taking a short break for maintenance. We'll let you know when she's back.",
        "assistant.turn_not_found" to "That question has timed out. Ask again.",
        "assistant.voice_too_long" to "Voice messages can be up to 60 seconds.",
        // wallet and payments
        "wallet.not_found" to "Set up your wallet first.",
        "wallet.already_exists" to "You already have a wallet.",
        "wallet.not_ready" to "Your wallet is still being set up. One moment.",
        "wallet.not_on_chain" to "Your wallet isn't on that network yet. Add it under Networks.",
        "wallet.account_restricted" to "Payments aren't available on this account right now.",
        "wallet.on_hold" to "Payments are paused for a short while after a restore.",
        "wallet.amount_invalid" to "That amount isn't right. Check it and try again.",
        "wallet.insufficient_funds" to "You don't have enough for this payment.",
        "wallet.limit_exceeded" to "That's over your payment limit.",
        "wallet.recipient_no_wallet" to "They haven't set up a wallet yet.",
        "wallet.recipient_not_on_chain" to "They can't receive on that network.",
        "wallet.address_invalid" to "That isn't a valid address on this network.",
        "wallet.pin_locked" to "Too many wrong PIN tries. Try again in 30 minutes.",
        "wallet.self_transfer" to "That's your own wallet.",
        "wallet.transfer_not_found" to "We couldn't find that payment.",
        "wallet.chain_unsupported" to "That network isn't supported.",
        "wallet.funds_remaining" to "Move your money out of the wallet first.",
        "wallet.custody_unavailable" to "Wallets are busy right now. Please try again in a moment.",
        "wallet.cancelled" to "Nothing was sent.",
        "wallet.sdk_unavailable" to "Signing isn't available in this build yet.",
        "wallet.sdk_failed" to "Something went wrong while signing. Nothing was sent.",
        // group wallets (phase 2)
        "wallet.group_wallets_unavailable" to "Group wallets aren't available on this server yet. Nothing was sent.",
        "wallet.group_not_found" to "You're not in this group any more.",
        "wallet.not_group_owner" to "Only the group's owner can set up its wallet.",
        // 2026-11-06: only the group's owner moves its money; everyone else adds to it.
        "wallet.not_signer" to "Only the group's owner can send from its wallet. You can still add to it.",
        "wallet.signer_left" to "The person who signs for this wallet has left the group. Nothing can be sent from it until they're back.",
        "wallet.payout_required" to "Money leaves a group wallet only for a payout the admins approved.",
        "wallet.payout_not_approved" to "This payout isn't approved, or it's already paid.",
        "wallet.payout_mismatch" to "That isn't the payee and amount the admins approved.",
        "wallet.payout_in_progress" to "This payout is already being paid. Check its card in the chat before paying again.",
        "wallet.group_funds_remaining" to "A group wallet you sign for still holds money. Pay it out first.",
        "wallet.support_account_has_no_wallet" to "Support accounts don't have wallets.",
        // chamas
        "chama.not_a_member" to "You're not in this group.",
        "calls.not_found" to "That call has ended.",
        "calls.call_ended" to "That call has ended.",
        "calls.not_a_member" to "You can only call people you chat with.",
        "calls.call_full" to "This call is full. Group calls hold 8 people.",
        "calls.not_in_call" to "You're not on this call any more.",
        "calls.already_in_call" to "There's already a call in this chat.",
        "calls.media_unavailable" to "Calls aren't available right now. Try again in a moment.",
        "chama.not_admin" to "Only the group's admins can do that.",
        "chama.not_treasurer" to "Only the group's treasurer can pay this out.",
        "chama.not_signer" to "Only the person who signs for the group wallet can pay this.",
        "chama.not_owner" to "Only the group's owner can change who approves payouts.",
        "chama.no_wallet" to "This group needs its wallet first.",
        "chama.no_goal" to "This group has no shared goal yet.",
        "chama.goal_invalid" to "Check the goal's name and target.",
        "chama.treasurer_not_member" to "The treasurer must be in the group.",
        "chama.amount_invalid" to "That amount isn't right.",
        "chama.payee_invalid" to "Say who to pay: a person or an address.",
        "chama.request_not_found" to "We couldn't find that request.",
        "chama.request_closed" to "This request is no longer open.",
        "chama.already_decided" to "You've already decided on this request.",
        "chama.not_approved" to "This request isn't approved yet.",
        "chama.transfer_mismatch" to "That payment doesn't match the request.",
        // share links (SH1–SH4)
        "share.request_invalid" to "That can't be shared as a link.",
        "share.source_not_found" to "We couldn't find that to share. It may not be yours to share.",
        "share.not_shareable" to "This can't be shared yet.",
        "share.link_not_found" to "This link is no longer available.",
        // encrypted chats
        "messaging.keys_proof_invalid" to "Check your phone's date and time, then try again.",
        "messaging.keys_not_found" to "This person can't receive encrypted messages yet.",
        "messaging.too_many_prekeys" to "Something went wrong setting up encryption. Please try again.",
        "messaging.prekey_id_exists" to "Something went wrong setting up encryption. Please try again.",
        "messaging.rate_limited" to "Too many new chats at once. Please wait a moment.",
        "messaging.chat_not_found" to "This chat is no longer available.",
        // chat media (AT1–AT3)
        "messaging.attachment_too_large" to "This is over the 32 MB limit and can't be sent.",
        "messaging.attachment_incomplete" to "The upload didn't finish. Send it again?",
        "messaging.attachment_not_found" to "The upload expired before it was sent. Send it again?",
        "messaging.not_admin" to "Only admins can do that.",
        "messaging.not_a_group" to "That only works in a group.",
        "messaging.group_full" to "This group is full.",
        "messaging.group_invalid" to "Those group details can't be saved. Check them and try again.",
        "messaging.avatar_invalid" to "Couldn't use that photo. Try another one.",
        // made on this phone: it has no key for the group's name and photo
        "messaging.meta_key_missing" to "This phone lost the group's key. We've asked the members for it, so try again soon.",
        "messaging.meta_key_asked" to "This phone lost the group's key. We've asked the members for it. If no one sends it, try again in a minute to give the group a new key.",
        "messaging.group_name_needed" to "Type the group's name to carry on.",
        "messaging.version_conflict" to "This group changed just now. We've loaded the latest, so try again.",
        "messaging.message_too_large" to "That message is too long to send.",
        "messaging.recipients_mismatch" to "The members of this chat changed. Please send again.",
        "messaging.delete_window_passed" to "Messages can be deleted for everyone within 48 hours of sending.",
        "messaging.undeliverable" to "Not sent: they changed phones before it arrived. Send it again.",
    )

    /** The message for [error]. [override] lets a screen use its own words for a specific code. */
    fun forError(error: AppError, override: Map<String, String> = emptyMap()): String = when (error) {
        AppError.Network -> Offline
        is AppError.Unexpected -> SomethingWrong
        is AppError.Api -> override[error.code] ?: byCode[error.code] ?: SomethingWrong
    }

    /** True when this code has its own wording (anything else falls back to the generic message). */
    fun isKnown(code: String) = code in byCode

    /**
     * A short reference the person can quote to support: the request id, plus the error code when we have no wording
     * for it (so support knows what happened even though the screen could only say "something went wrong").
     */
    fun reference(error: AppError): String? {
        val api = error as? AppError.Api ?: return null
        return listOfNotNull(api.requestId, api.code.takeUnless { isKnown(it) }).joinToString(" · ").ifEmpty { null }
    }
}
