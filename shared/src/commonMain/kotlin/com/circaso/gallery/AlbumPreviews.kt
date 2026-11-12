package com.circaso.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.data.repository.MediaLoad
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.presentation.ThreadContent
import com.circaso.feature.messaging.presentation.ThreadMediaActions
import com.circaso.feature.messaging.presentation.ThreadUiState

/**
 * Debug previews of albums (`--es preview albums-direct|albums-group`): albums of 2, 3, 4 and 7 in a 1:1 chat and a
 * group, photos read from this phone's Pictures folder (photo1-4.jpg, test1-3.jpg), one album on its way, one failed.
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
internal fun AlbumPreview(name: String) {
    val library = org.koin.compose.koinInject<com.circaso.core.platform.MediaLibrary>()
    val group = name == "albums-group"
    val files = listOf("photo1", "photo2", "photo3", "photo4", "test1", "test2", "test3").map { "/sdcard/Pictures/$it.jpg" }
    val thumbs by androidx.compose.runtime.produceState<List<String?>?>(null) {
        value = files.map { f -> library.read(f)?.let { kotlin.io.encoding.Base64.encode(it) } }
    }
    val t = thumbs ?: return
    val now = com.circaso.core.sync.nowMillis()
    var id = 100L
    fun item(album: String, file: Int, outgoing: Boolean, minutesAgo: Int, video: Boolean = false, caption: String? = null, state: DeliveryState = DeliveryState.Read, from: Pair<String, String> = "2" to "Wanjiru"): Message.Media {
        val d = if (video) MediaDetails(MediaDetails.Video, attachmentId = "a${id}", durationMs = 37_000L + file * 9_000, thumb = t[file], album = album, caption = caption, width = 3, height = 4)
        else MediaDetails(MediaDetails.Image, attachmentId = "a${id}", file = files[file], album = album, caption = caption, width = 4, height = 3)
        id++
        return Message.Media(id, "c", now - minutesAgo * 60_000L, null, "m$id", if (outgoing) "me" else from.first, if (outgoing) "You" else from.second, outgoing, d, state, null)
    }
    fun text(s: String, outgoing: Boolean, minutesAgo: Int, from: Pair<String, String> = "2" to "Wanjiru"): Message.Text {
        id++
        return Message.Text(id, "c", now - minutesAgo * 60_000L, null, "m$id", if (outgoing) "me" else from.first, if (outgoing) "You" else from.second, outgoing, s, DeliveryState.Read, null, null)
    }
    val otieno = "4" to "Otieno"
    val messages = listOf(
        text("Send me the photos from Saturday!", false, 60),
        item("two", 0, true, 50), item("two", 1, true, 50, caption = "The view from the top"),
        item("three", 2, !group, 45, from = otieno), item("three", 3, !group, 45, video = true, from = otieno), item("three", 4, !group, 45, from = otieno),
        text("These are lovely", !group, 40),
        item("four", 5, false, 30), item("four", 6, false, 30), item("four", 0, false, 30, video = true), item("four", 1, false, 30, caption = "Graduation day"),
        item("seven", 0, true, 2, state = DeliveryState.Read), item("seven", 1, true, 2, state = DeliveryState.Sending), item("seven", 2, true, 2, video = true, state = DeliveryState.Waiting),
        item("seven", 3, true, 2, state = DeliveryState.Waiting), item("seven", 4, true, 2, state = DeliveryState.Waiting), item("seven", 5, true, 2, state = DeliveryState.Waiting),
        item("seven", 6, true, 2, caption = "Everyone at the reunion", state = DeliveryState.Waiting),
        item("failed", 2, true, 1, state = DeliveryState.Failed), item("failed", 3, true, 1, state = DeliveryState.Read),
    )
    val sending = messages.first { (it as? Message.Media)?.state == DeliveryState.Sending }.localId
    val member = { uid: String, n: String, r: MemberRole -> ChatMember(uid, n, n.lowercase(), null, r, "2026-10-20T00:00:00Z") }
    val chat = if (group) Chat("g", ChatKind.Group, "Holiday Fund", null, listOf(member("me", "Amina", MemberRole.Owner), member("2", "Wanjiru", MemberRole.Member), member("4", "Otieno", MemberRole.Member)), false, 1, 1, MemberRole.Owner, null)
    else Chat("c", ChatKind.Direct, "Wanjiru", null, listOf(member("me", "Amina", MemberRole.Member), member("2", "Wanjiru", MemberRole.Member)), false, 1, 0, MemberRole.Member, "2")
    val actions = ThreadMediaActions(
        canShareLocation = true, onCompose = {}, onLocation = {}, onVoiceStart = {}, onVoiceEnd = {},
        load = { d -> d.file?.let { library.read(it) }?.let { MediaLoad.Loaded(it) } ?: MediaLoad.Failed },
        onPlay = {}, onRetry = {}, onConfirmLocation = {}, onCancelLocation = {},
        uploading = kotlinx.coroutines.flow.MutableStateFlow(mapOf(sending to 0.6f)),
        // Their videos coming in: the download ring on the tile.
        downloading = kotlinx.coroutines.flow.MutableStateFlow(
            messages.filterIsInstance<Message.Media>().filter { it.isVideo && !it.outgoing }.mapNotNull { it.details.attachmentId }.associateWith { 0.35f },
        ),
    )
    ThreadContent(ThreadUiState(chat = chat, messages = messages), {}, {}, null, {}, {}, {}, {}, {}, {}, {}, media = actions)
}
