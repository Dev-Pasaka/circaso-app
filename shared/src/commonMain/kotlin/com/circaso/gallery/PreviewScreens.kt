package com.circaso.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import com.circaso.feature.account.presentation.BiometricTurnOffSheet
import com.circaso.feature.calls.presentation.CallOverlay
import com.circaso.feature.wallet.presentation.BiometricOfferLayer
import androidx.compose.ui.unit.dp
import com.circaso.feature.chama.domain.Approval
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ChamaGoal
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.Contributor
import com.circaso.feature.chama.domain.LedgerDirection
import com.circaso.feature.chama.domain.LedgerEntry
import com.circaso.feature.chama.domain.RequestState
import com.circaso.feature.chama.presentation.ChamaContent
import com.circaso.feature.chama.presentation.ChamaUiState
import com.circaso.feature.chama.presentation.LedgerTab
import com.circaso.feature.chama.presentation.PayoutDraft
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.presentation.ChatsContent
import com.circaso.feature.messaging.presentation.ChatsUiState
import com.circaso.feature.messaging.presentation.ThreadContent
import com.circaso.feature.messaging.presentation.ThreadUiState
import androidx.compose.ui.unit.dp
import com.circaso.feature.account.domain.ActiveDevice
import com.circaso.feature.account.domain.LinkedProvider
import com.circaso.feature.account.domain.SecurityEvent
import com.circaso.feature.account.domain.SecurityOverview
import com.circaso.feature.account.presentation.SecurityContent
import com.circaso.feature.account.presentation.SecurityLogContent
import com.circaso.feature.account.presentation.SecurityLogUiState
import com.circaso.feature.account.presentation.SecurityUiState
import com.circaso.feature.assistant.domain.AshaMessage
import com.circaso.feature.assistant.presentation.AshaContent
import com.circaso.feature.assistant.presentation.AshaUiState
import com.circaso.feature.assistant.presentation.VoiceContent
import com.circaso.feature.assistant.presentation.VoiceEnding
import com.circaso.feature.assistant.presentation.VoicePhase
import com.circaso.feature.assistant.presentation.VoiceUiState
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.AssetTotal
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.presentation.TransferContent
import com.circaso.feature.wallet.presentation.TransferUiState
import com.circaso.feature.wallet.presentation.WalletActions
import com.circaso.feature.wallet.presentation.WalletContent
import com.circaso.feature.wallet.presentation.WalletUiState
import com.circaso.core.sync.nowMillis
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.contacts.presentation.BlockedContent
import com.circaso.feature.contacts.presentation.BlockedUiState
import com.circaso.feature.contacts.presentation.ContactsActions
import com.circaso.feature.contacts.presentation.ContactsContent
import com.circaso.feature.contacts.presentation.ContactsUiState
import com.circaso.feature.home.presentation.YouActions
import com.circaso.feature.home.presentation.YouContent
import com.circaso.feature.home.presentation.YouUiState
import com.circaso.feature.profile.domain.Privacy
import com.circaso.feature.profile.domain.Profile
import com.circaso.feature.profile.presentation.EditProfileContent
import com.circaso.feature.profile.presentation.EditProfileUiState
import com.circaso.feature.profile.presentation.HandleCheck

private val profile = Profile("u1", "Amina Wanjiru", "Hi there", "amina", null, null, Privacy("contacts", "contacts", true), 3, pendingSync = true)
private fun person(id: String, name: String, handle: String) = Person(id, name, handle, null, null)
private val contacts = listOf(
    Contact(person("2", "Achieng Akinyi", "achieng"), "sync", "2026-10-20"),
    Contact(person("3", "Juma Kariuki", "juma.k"), "qr", "2026-10-21"),
    Contact(person("4", "Kevin Mwangi", "kevo"), "sync", "2026-10-22"),
)
/** A fuller address book for the people screens: handles, an about line, a name without a letter. */
private val peopleContacts = listOf(
    Contact(person("2", "Achieng Akinyi", "achieng"), "sync", "2026-10-20"),
    Contact(Person("3", "Baraka Otieno", null, null, "Mombasa · football on Sundays"), "sync", "2026-10-20"),
    Contact(person("4", "Juma Kariuki", "juma.k"), "qr", "2026-10-21"),
    Contact(person("5", "Kevin Mwangi", "kevo"), "sync", "2026-10-22"),
    Contact(person("6", "Mum", "mama.wanjiku"), "sync", "2026-10-22"),
    Contact(person("7", "Otieno Odhiambo", "otieno"), "sync", "2026-10-23"),
    Contact(person("8", "Wanjiru Kamau", "wanjiru"), "sync", "2026-10-23"),
    Contact(person("9", "Wairimu N.", "wairimu"), "invite", "2026-10-24"),
)
private fun msg(id: String, role: String, text: String) = AshaMessage(id, role, text, null, null, null, "2026-10-25T10:0${id}:00Z")

private val now = com.circaso.core.sync.nowMillis()
private fun member(id: String, name: String, role: MemberRole = MemberRole.Member) = ChatMember(id, name, name.lowercase().substringBefore(' '), null, role, "2026-10-20T00:00:00Z")
private fun direct(id: String, name: String) = Chat(id, ChatKind.Direct, name, null, listOf(member("me", "Pasaka"), member(id, name)), false, 1, 0, MemberRole.Member, id)
private val supportChat = Chat("s1", ChatKind.Direct, "Circaso Support", null, listOf(member("me", "Pasaka"), ChatMember("sup", "Circaso Support", "circaso", null, MemberRole.Member, "2026-10-01T00:00:00Z")), false, 1, 0, MemberRole.Member, "sup")
private val fund = Chat("g1", ChatKind.Group, "Holiday Fund", null, listOf(member("me", "Pasaka", MemberRole.Owner), member("3", "Wanjiru Kamau"), member("4", "Otieno Odhiambo")), false, 3, 1, MemberRole.Owner, null)
private fun text(localId: Long, chatId: String, text: String, outgoing: Boolean, minutesAgo: Int, state: DeliveryState = DeliveryState.Read, sender: String = "2", senderName: String = "Mum", errorCode: String? = null) =
    Message.Text(localId, chatId, now - minutesAgo * 60_000L, null, "m$localId", if (outgoing) "me" else sender, if (outgoing) "You" else senderName, outgoing, text, state, errorCode, null)
private fun media(localId: Long, details: com.circaso.feature.messaging.data.repository.MediaDetails, outgoing: Boolean, minutesAgo: Int, state: DeliveryState = DeliveryState.Read) =
    Message.Media(localId, "2", now - minutesAgo * 60_000L, null, "m$localId", if (outgoing) "me" else "2", if (outgoing) "You" else "Mum", outgoing, details, state, if (state == DeliveryState.Failed) "messaging.attachment_too_large" else null)
private typealias D = com.circaso.feature.messaging.data.repository.MediaDetails
/** Live location: Mum's share moving now, mine moving (so the banner shows), and an older one that ended. */
private val liveThread = listOf(
    media(21, D(D.Location, latitude = -1.2864, longitude = 36.8172, liveId = "old", liveUntil = now - 3_600_000, liveAt = now - 3_700_000, liveEnded = true), false, 120),
    media(22, D(D.Location, latitude = -1.29207, longitude = 36.82195, liveId = "mum", liveUntil = now + 3_000_000, liveAt = now - 60_000), false, 20),
    media(23, D(D.Location, latitude = -1.2995, longitude = 36.7890, liveId = "me", liveUntil = now + 900_000, liveAt = now - 10_000), true, 5),
)
private val mapEntries = listOf(
    com.circaso.feature.messaging.presentation.map.LocationEntry(liveThread[1], "Mum", null, place = false),
    com.circaso.feature.messaging.presentation.map.LocationEntry(liveThread[2], "You", null, place = false),
)
private val mediaThread = listOf(
    media(11, D(D.Image, attachmentId = "a1", width = 4, height = 3, caption = "The new house"), false, 30),
    media(12, D(D.Voice, attachmentId = "a2", durationMs = 12_000), false, 25),
    media(13, D(D.Voice, attachmentId = "a3", durationMs = 7_000), true, 20),
    media(14, D(D.Location, latitude = -1.29207, longitude = 36.82195, accuracy = 18.0), true, 10),
    media(15, D(D.Image, file = "x", width = 3, height = 4), true, 2, DeliveryState.Waiting),
    media(16, D(D.Voice, file = "y", durationMs = 3_000), true, 1, DeliveryState.Failed),
    media(17, D(D.File, file = "z", label = "Lease agreement 2026.pdf", mime = "application/pdf", size = 2_480_000, caption = "Signed copy"), true, 1, DeliveryState.Sending),
    media(18, D(D.File, attachmentId = "a4", label = "Budget.xlsx", mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", size = 48_200), false, 1),
)
/**
 * Big files coming in (`--es preview chat-downloads`): a video and a document downloading, their rings filling as a
 * real download's would (MediaMessages.downloading), and one still asking for its link (the ring turning).
 */
@androidx.compose.runtime.Composable
private fun DownloadsPreview() {
    val flow = androidx.compose.runtime.remember { kotlinx.coroutines.flow.MutableStateFlow(mapOf("dv1" to 0.1f, "dd1" to 0.3f)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(400)
            flow.value = flow.value.mapValues { (_, v) -> if (v >= 0.98f) 0.05f else (v + 0.03f).coerceAtMost(0.98f) }
        }
    }
    ThreadContent(
        ThreadUiState(chat = direct("2", "Mum"), messages = listOf(
            text(40, "2", "Here's the whole recital, and the programme", false, 6),
            media(41, D(D.Video, attachmentId = "dv1", width = 1280, height = 720, durationMs = 754_000, size = 96_400_000, caption = "The whole recital"), false, 5),
            media(42, D(D.File, attachmentId = "dd1", label = "Recital programme and photos.zip", mime = "application/zip", size = 84_200_000), false, 5),
            media(43, D(D.Video, attachmentId = "dv2", width = 720, height = 1280, durationMs = 61_000), false, 4),
        ), videoLoading = 43),
        {}, {}, null, {}, {}, {}, {}, {}, {}, {},
        media = com.circaso.feature.messaging.presentation.ThreadMediaActions(
            canShareLocation = true, onCompose = {}, onLocation = {}, onVoiceStart = {}, onVoiceEnd = {}, load = { com.circaso.feature.messaging.data.repository.MediaLoad.Failed }, onPlay = {}, onRetry = {},
            onConfirmLocation = {}, onCancelLocation = {}, downloading = flow,
        ),
    )
}

/** Media actions for previews: nothing loads, so photos show their "couldn't load" state. */
private val previewMedia = com.circaso.feature.messaging.presentation.ThreadMediaActions(
    canShareLocation = true, onCompose = {}, onLocation = {}, onVoiceStart = {}, onVoiceEnd = {}, load = { com.circaso.feature.messaging.data.repository.MediaLoad.Failed }, onPlay = {}, onRetry = {},
    onConfirmLocation = {}, onCancelLocation = {}, uploading = kotlinx.coroutines.flow.MutableStateFlow(mapOf(17L to 0.42f)),
)
internal val chatList = listOf(
    ChatSummary(direct("2", "Mum"), text(1, "2", "Thank you, karibu tena!", false, 12), 2),
    ChatSummary(fund, text(2, "g1", "Term 3 is fully paid", false, 90, sender = "3", senderName = "Wanjiru"), 1),
    ChatSummary(direct("5", "Otieno"), text(3, "5", "See you on Saturday then", true, 60 * 26), 0),
    ChatSummary(direct("6", "Achieng"), text(4, "6", "Sawa, tutaonana", true, 60 * 50, state = DeliveryState.Delivered), 0),
)
private val thread = listOf(
    text(1, "2", "Habari ya leo? Did you eat?", false, 60 * 6),
    text(2, "2", "Not yet, still in a meeting", true, 60 * 5),
    text(3, "2", "Asante, Mum! I will call you tonight", true, 60 * 4),
    text(4, "2", "Sawa. Greet everyone for me", false, 30),
)

/** Debug only: screens drawn from sample data so each can be checked on a device without a signed-in session (`-preview=<name>`). */
@Composable
fun PreviewScreens(name: String) {
    when (name) {
        // Home's real frame with sample tabs: the floating tab bar, or the left rail on a short or wide window.
        "home", "home-wallet", "home-you" -> com.circaso.feature.home.presentation.HomeScreen(
            initialTab = when (name) { "home-wallet" -> 1; "home-you" -> 2; else -> 0 },
            chats = { pad -> ChatsContent(ChatsUiState(chats = chatList, loaded = true), pad, {}, {}, {}, moments = momentsSlot(sampleMoments())) },
            chatsActions = {}, chatsBanner = {},
            wallet = { pad -> WalletContent(WalletUiState(wallet = sampleWallet()), pad, noWalletActions, {}, {}, {}, {}) },
            walletActions = {},
            you = { pad -> PreviewScreens("you") },
        )
        // The split view as a wide window shows it: the chat list beside an open chat, or beside the empty pane.
        // split-you-signout: a sheet opened from the list pane (You's Sign Out) must cover the window, not the pane.
        "split", "split-wallet", "split-you-signout" -> androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize()) {
            com.circaso.designsystem.adaptive.ProvideWindowLayout(androidx.compose.ui.Modifier.width(com.circaso.designsystem.adaptive.WindowLayout.ListPaneWidth + com.circaso.designsystem.adaptive.WindowLayout.RailRoom).fillMaxHeight()) {
              androidx.compose.runtime.CompositionLocalProvider(com.circaso.designsystem.adaptive.LocalTabsAsRail provides true) {
                if (name == "split-you-signout") YouContent(
                    YouUiState(profile = profile, maskedPhone = "+254 7•• ••• 678", confirmSignOut = true),
                    YouActions({}, {}, {}, {}, {}), "1.0.0", PaddingValues(bottom = 90.dp), {}, {}, {},
                ) else PreviewScreens("home")
              }
            }
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(0.5.dp).fillMaxHeight().background(com.circaso.designsystem.theme.CircasoTheme.colors.separator))
            com.circaso.designsystem.adaptive.ProvideWindowLayout(androidx.compose.ui.Modifier.weight(1f).fillMaxHeight()) {
                androidx.compose.runtime.CompositionLocalProvider(com.circaso.core.navigation.LocalIsDetailRoot provides true) {
                    PreviewScreens(if (name == "split") "chat" else "transfer")
                }
            }
        }
        // The Moments viewer (V1, V4, V5) and its reaction picker: sample moments, nothing loads.
        "moment-text" -> MomentPreview(sampleMoment(com.circaso.feature.status.domain.MomentKind.Text))
        "moment-photo" -> MomentPreview(sampleMoment(com.circaso.feature.status.domain.MomentKind.Image), photo = true)
        // With `--es video <path>` (a file this app can read): the viewer playing that file, as a posted moment plays.
        "moment-video" -> MomentPreview(sampleMoment(com.circaso.feature.status.domain.MomentKind.Video), videoPath = launchValue("video"))
        // The real composer (its own view model): pick a video and trim it, to check the trimmer on a device.
        "moment-composer" -> com.circaso.feature.status.presentation.MomentComposerScreen(org.koin.compose.viewmodel.koinViewModel(), onClose = {}, onAudience = {})
        "moment-mine" -> MomentPreview(sampleMoment(com.circaso.feature.status.domain.MomentKind.Image).copy(mine = true, viewCount = 14, reactionCount = 3), photo = true)
        // V4: my moment with Seen by open; one viewer reacted with a heart, one with any emoji, one only looked.
        "moment-mine-viewers" -> MomentPreview(
            sampleMoment(com.circaso.feature.status.domain.MomentKind.Image).copy(mine = true, viewCount = 3, reactionCount = 2), photo = true,
            viewers = listOf(
                com.circaso.feature.status.presentation.ViewerRow(com.circaso.feature.status.domain.MomentViewer("w", now - 5 * 60_000, true, "❤️"), "Wanjiru Kamau", null),
                com.circaso.feature.status.presentation.ViewerRow(com.circaso.feature.status.domain.MomentViewer("b", now - 18 * 60_000, true, "😂"), "Baraka Mwita", null),
                com.circaso.feature.status.presentation.ViewerRow(com.circaso.feature.status.domain.MomentViewer("m", now - 31 * 60_000, false), "Mum", null),
            ),
        )
        "moment-react" ->MomentPreview(sampleMoment(com.circaso.feature.status.domain.MomentKind.Text).copy(reaction = "🔥"), picking = true)
        "reaction-picker", "reaction-picker-expanded" -> androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(com.circaso.designsystem.theme.CircasoTheme.colors.bg),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            // Mine is from the long list, so it also shows in the row (to take it back without opening the list).
            com.circaso.designsystem.components.ReactionBubble(current = "🔥", onPick = {}, onOptions = {}, initiallyExpanded = name.endsWith("expanded"))
        }
        "you", "you-notification-privacy-on" -> YouContent(
            YouUiState(
                profile = profile, maskedPhone = "+254 7•• ••• 678",
                failed = if (name == "you") listOf(com.circaso.core.sync.FailedChange(1, "Profile change", "Your profile changed on another phone. We've loaded the latest, so check and save again.")) else emptyList(),
                // The switches in their other state: privacy on, money sound off.
                notificationPrivacy = name != "you", moneySound = name == "you",
            ), YouActions({}, {}, {}, {}, {}), "1.0.0",
            PaddingValues(bottom = 90.dp), {}, {}, {},
        )
        "profile-available" -> EditProfileContent(
            EditProfileUiState(profile = profile, name = "Amina Wanjiru", about = "Hi there", handle = "amina_k_new", handleCheck = HandleCheck.Available),
            {}, {}, {}, {}, {}, {},
        )
        "profile-photo-sheet" -> EditProfileContent(
            EditProfileUiState(profile = profile, name = "Amina Wanjiru", about = "Hi there", handle = "amina_k", recentsAccess = com.circaso.core.platform.LibraryAccess.Limited),
            {}, {}, {}, {}, {}, {}, photoSheetOpen = true,
        )
        "profile-camera-denied" -> EditProfileContent(
            EditProfileUiState(profile = profile, name = "Amina Wanjiru", about = "Hi there", handle = "amina_k", camera = com.circaso.feature.profile.presentation.ProfileCamera(com.circaso.core.platform.CameraStart.PermissionDenied)),
            {}, {}, {}, {}, {}, {},
        )
        "profile-camera-unavailable" -> EditProfileContent(
            EditProfileUiState(profile = profile, name = "Amina Wanjiru", about = "Hi there", handle = "amina_k", camera = com.circaso.feature.profile.presentation.ProfileCamera(com.circaso.core.platform.CameraStart.Unavailable)),
            {}, {}, {}, {}, {}, {},
        )
        "profile" -> EditProfileContent(
            EditProfileUiState(profile = profile, name = "Amina Wanjiru", about = "Hi there", handle = "amina_k", handleCheck = HandleCheck.Taken("amina_k2")),
            {}, {}, {}, {}, {}, {},
        )
        "contacts" -> ContactsContent(ContactsUiState(contacts = peopleContacts, loaded = true), ContactsActions())
        "contacts-empty", "contacts-invite" -> ContactsContent(ContactsUiState(loaded = true), ContactsActions())
        "contacts-loading" -> ContactsContent(ContactsUiState(), ContactsActions())
        "contacts-add" -> ContactsContent(ContactsUiState(contacts = peopleContacts, loaded = true, adding = true, connectInput = "kevo"), ContactsActions())
        "contacts-sheet" -> ContactsContent(ContactsUiState(contacts = peopleContacts, selected = peopleContacts[0], loaded = true), ContactsActions())
        "find-friends" -> ContactsContent(ContactsUiState(contacts = peopleContacts, showFindFriends = true, loaded = true), ContactsActions())
        "new-chat" -> com.circaso.feature.messaging.presentation.NewChatContent(
            com.circaso.feature.messaging.presentation.NewChatUiState(contacts = peopleContacts, recentChats = chatList, loaded = true),
            com.circaso.feature.messaging.presentation.NewChatActions(),
        )
        "new-chat-empty" -> com.circaso.feature.messaging.presentation.NewChatContent(
            com.circaso.feature.messaging.presentation.NewChatUiState(loaded = true), com.circaso.feature.messaging.presentation.NewChatActions(),
        )
        "new-chat-loading" -> com.circaso.feature.messaging.presentation.NewChatContent(
            com.circaso.feature.messaging.presentation.NewChatUiState(), com.circaso.feature.messaging.presentation.NewChatActions(),
        )
        "new-chat-search" -> com.circaso.feature.messaging.presentation.NewChatContent(
            com.circaso.feature.messaging.presentation.NewChatUiState(contacts = peopleContacts, loaded = true, query = "@baraka"), com.circaso.feature.messaging.presentation.NewChatActions(),
        )
        "new-group-members" -> com.circaso.feature.messaging.presentation.NewGroupContent(
            com.circaso.feature.messaging.presentation.NewGroupUiState(contacts = peopleContacts, selected = linkedSetOf("3", "5", "2"), loaded = true),
            com.circaso.feature.messaging.presentation.NewGroupActions(),
        )
        "new-group-details", "new-group-photo-sheet" -> com.circaso.feature.messaging.presentation.NewGroupContent(
            com.circaso.feature.messaging.presentation.NewGroupUiState(
                step = com.circaso.feature.messaging.presentation.NewGroupStep.Details, name = "Holiday Fund", contacts = peopleContacts,
                selected = linkedSetOf("3", "5", "2"), loaded = true, photosAvailable = true,
                photoChoice = com.circaso.feature.profile.presentation.PhotoChoiceState(open = name == "new-group-photo-sheet", access = com.circaso.core.platform.LibraryAccess.Limited),
            ),
            com.circaso.feature.messaging.presentation.NewGroupActions(),
        )
        "new-group-photo-failed" -> com.circaso.feature.messaging.presentation.NewGroupContent(
            com.circaso.feature.messaging.presentation.NewGroupUiState(
                step = com.circaso.feature.messaging.presentation.NewGroupStep.Details, name = "Holiday Fund", contacts = peopleContacts,
                selected = linkedSetOf("3", "5"), loaded = true, photosAvailable = true, createdChatId = "g9", reference = "req_7Hq2",
                error = "The group is made, but its photo didn't upload. You're offline; try again when you're back.",
            ),
            com.circaso.feature.messaging.presentation.NewGroupActions(),
        )
        "blocked" -> BlockedContent(BlockedUiState(listOf(person("9", "Baraka Otieno", "baraka"))), {}, {})
        "security" -> SecurityContent(
            SecurityUiState(
                overview = SecurityOverview(
                    "+254 7•• ••• 678",
                    listOf(LinkedProvider("google", "a•••@gmail.com", "2026-10-01T00:00:00Z")),
                    ActiveDevice("Pixel 8", "2026-10-12T00:00:00Z"), "VERIFIED",
                ),
            ),
            {}, {}, {}, {}, {}, {}, {},
        )
        "security-biometric-off", "security-biometric-busy", "security-biometric-on", "security-biometric-error", "security-biometric-offline",
        "security-biometric-lost", "security-biometric-lost-offline", "security-biometric-turn-off" -> {
            val bio = com.circaso.feature.account.presentation.BiometricApprovalState(
                hasWallet = true,
                enabled = name.endsWith("-on") || name.endsWith("-turn-off"),
                busy = name.endsWith("-busy"),
                online = !name.endsWith("-offline"),
                error = if (name.endsWith("-error")) "Something went wrong on our side. Try again. (Ref: 7F3A)" else null,
                lost = name.contains("-lost"),
                confirmOff = name.endsWith("-turn-off"),
            )
            SecurityContent(
                SecurityUiState(overview = SecurityOverview("+254 7•• ••• 678", listOf(LinkedProvider("google", "a•••@gmail.com", "2026-10-01T00:00:00Z")), ActiveDevice("Pixel 8", "2026-10-12T00:00:00Z"), "VERIFIED")),
                {}, {}, {}, {}, {}, {}, {},
                payments = { com.circaso.feature.account.presentation.BiometricApprovalSection(bio, {}) },
                paymentsOverlay = { BiometricTurnOffSheet(bio, {}, {}) },
            )
        }
        // The one-time offer after a PIN payment (2026-11-10), personal and a group wallet's.
        "biometric-offer-after-pay", "biometric-offer-after-pay-group" -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            val subject = if (name.endsWith("-group")) com.circaso.feature.wallet.domain.SignSubject.group("gw-1", "Kilimani Chama") else com.circaso.feature.wallet.domain.SignSubject.Personal
            BiometricOfferLayer(com.circaso.feature.wallet.presentation.BiometricOfferState(subject = subject), {}, {})
        }
        // The metallic money cards in each state (C3–C6), and the coin icons on them.
        "money-cards" -> androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).padding(com.circaso.designsystem.theme.CircasoSpacing.s4),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(com.circaso.designsystem.theme.CircasoSpacing.s3),
        ) {
            com.circaso.designsystem.components.MoneyCard("Received", "KES 2,000", "15.50 USDC · 12:41", outgoing = false, note = "for lunch", symbol = "USDC")
            com.circaso.designsystem.components.MoneyCard("Sent", "KES 500", "3.88 USDC · 12:45", outgoing = true, note = "bus fare", symbol = "USDC")
            // Cards the wallet adds from its own record (an unreadable or missing encrypted card): no note.
            com.circaso.designsystem.components.MoneyCard("Received", "KES 1,000", "7.70 USDC · 10:12", outgoing = false, symbol = "USDC")
            com.circaso.designsystem.components.MoneyCard("Sent", "KES 1,000", "7.70 USDC · 10:12", outgoing = true, symbol = "USDC")
            com.circaso.designsystem.components.MoneyCard("Sent", "0.01 ETH", "09:40 · sending card", outgoing = true, pending = true, symbol = "ETH")
            com.circaso.designsystem.components.MoneyCard("Sent", "KES 140", "1.09 USDC · 09:40 · card not sent", outgoing = true, failed = true, symbol = "USDC")
        }
        "currency", "currency-automatic" -> com.circaso.feature.wallet.presentation.CurrencyContent(
            com.circaso.feature.wallet.presentation.CurrencyUiState(chosen = if (name == "currency") "UGX" else null, automatic = "KES"), {}, {},
        )
        "switches" -> androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).padding(com.circaso.designsystem.theme.CircasoSpacing.s6),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(com.circaso.designsystem.theme.CircasoSpacing.s4),
        ) {
            com.circaso.designsystem.components.CircasoSwitch(true, {})
            com.circaso.designsystem.components.CircasoSwitch(false, {})
            com.circaso.designsystem.components.CircasoSwitch(true, {}, enabled = false)
        }
        "wallet-biometric-offer", "wallet-biometric-offer-busy", "wallet-biometric-offer-error", "wallet-biometric-offer-offline" -> androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxSize().padding(com.circaso.designsystem.theme.CircasoSpacing.s6),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(com.circaso.designsystem.theme.CircasoSpacing.s4, androidx.compose.ui.Alignment.Bottom),
        ) {
            com.circaso.feature.wallet.presentation.BiometricOfferContent(
                com.circaso.feature.account.presentation.BiometricApprovalState(
                    hasWallet = true, label = "Face ID",
                    busy = name.endsWith("-busy"), online = !name.endsWith("-offline"),
                    error = if (name.endsWith("-error")) "Something went wrong on our side. Try again. (Ref: 7F3A)" else null,
                ),
                onTurnOn = {}, onNotNow = {},
            )
        }
        "log" -> SecurityLogContent(
            SecurityLogUiState(
                listOf(
                    SecurityEvent("1", "sign_in", "Pixel 8", null, "2026-10-25T09:12:00Z"),
                    SecurityEvent("2", "provider_linked", "Pixel 8", null, "2026-10-12T10:05:00Z"),
                    SecurityEvent("3", "device_added", "Pixel 8", null, "2026-10-12T10:02:00Z"),
                ),
                loaded = true,
            ),
            {},
        )
        "chats" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true), PaddingValues(0.dp), {}, {}, {})
        "chats-empty" -> ChatsContent(ChatsUiState(loaded = true), PaddingValues(0.dp), {}, {}, {})
        "chats-offline" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, online = false, waitingToSend = 2), PaddingValues(0.dp), {}, {}, {})
        "chat" -> ThreadContent(ThreadUiState(chat = direct("2", "Mum"), messages = thread, draft = ""), {}, {}, null, {}, {}, {}, {}, {}, {}, {})
        "chat-offline" -> ThreadContent(
            ThreadUiState(chat = direct("2", "Mum"), online = false, messages = thread + listOf(text(5, "2", "Not yet, on my way home", true, 2, DeliveryState.Waiting), text(6, "2", "I'll send lunch money once I have network", true, 1, DeliveryState.Waiting))),
            {}, {}, null, {}, {}, {}, {}, {}, {}, {},
        )
        "albums-direct", "albums-group" -> AlbumPreview(name)
        "share-flow", "compose-moment", "compose-chat-camera", "compose-chat-gallery", "compose-chat-document" -> MediaComposePreview(name)
        "chat-media" -> ThreadContent(ThreadUiState(chat = direct("2", "Mum"), messages = mediaThread, playing = 12, playingSince = now - 4_000), {}, {}, null, {}, {}, {}, {}, {}, {}, {}, media = previewMedia)
        "chat-video" -> ThreadContent(ThreadUiState(chat = direct("2", "Mum"), messages = listOf(
            media(31, D(D.Video, attachmentId = "v1", width = 720, height = 1280, durationMs = 42_000, caption = "Goal!"), false, 30),
            media(32, D(D.Video, file = "x", width = 1280, height = 720, durationMs = 8_000), true, 2, DeliveryState.Sending),
        )), {}, {}, null, {}, {}, {}, {}, {}, {}, {}, media = previewMedia)
        "chat-downloads" -> DownloadsPreview()
        "chat-live" -> ThreadContent(ThreadUiState(chat = direct("2", "Mum"), messages = liveThread), {}, {}, null, {}, {}, {}, {}, {}, {}, {}, media = previewMedia)
        "location-map" -> com.circaso.feature.messaging.presentation.map.LocationMapContent(
            com.circaso.feature.messaging.presentation.map.LocationMapUiState(direct("2", "Mum"), mapEntries, 22), {}, {},
        )
        "chat-recording" -> ThreadContent(ThreadUiState(chat = direct("2", "Mum"), messages = thread, recording = true, recordingSince = now - 7_000), {}, {}, null, {}, {}, {}, {}, {}, {}, {}, media = previewMedia)
        "chat-location" -> ThreadContent(ThreadUiState(chat = direct("2", "Mum"), messages = thread, locating = true, pendingLocation = com.circaso.core.platform.GeoPoint(-1.29207, 36.82195, 18.0)), {}, {}, null, {}, {}, {}, {}, {}, {}, {}, media = previewMedia)
        "chat-locating" -> ThreadContent(ThreadUiState(chat = direct("2", "Mum"), messages = thread, locating = true), {}, {}, null, {}, {}, {}, {}, {}, {}, {}, media = previewMedia)
        "chat-failed" -> ThreadContent(ThreadUiState(chat = direct("2", "Mum"), messages = thread + listOf(text(5, "2", "Did this go through?", true, 1, DeliveryState.Failed, errorCode = "messaging.message_too_large"))), {}, {}, null, {}, {}, {}, {}, {}, {}, {})
        "group" -> ThreadContent(
            ThreadUiState(chat = fund, messages = listOf(text(1, "g1", "Bus deposit is due Friday. Coast Bus wants KES 12,000", false, 70, sender = "4", senderName = "Otieno"), text(2, "g1", "I can do mine tomorrow", false, 60, sender = "3", senderName = "Wanjiru"), text(3, "g1", "Adding mine tonight", true, 5))),
            {}, {}, null, {}, {}, {}, {}, {}, {}, {},
        )
        "group-goal" -> ThreadContent(
            ThreadUiState(chat = fund, messages = groupThread, chama = sampleChama(), fiat = sampleWallet().fiat, myUserId = "me"),
            {}, {}, null, {}, {}, {}, {}, {}, {}, {}, onOpenGoal = {}, onContribute = {}, onPayRequest = {},
        )
        "group-request" -> ThreadContent(
            ThreadUiState(chat = fund, messages = groupThread, chama = sampleChama(open = true), fiat = sampleWallet().fiat, myUserId = "me"),
            {}, {}, null, {}, {}, {}, {}, {}, {}, {}, onOpenGoal = {}, onContribute = {}, onPayRequest = {},
        )
        "group-intro" -> ThreadContent(
            ThreadUiState(chat = fund, messages = groupThread, chama = sampleChama(), fiat = sampleWallet().fiat, myUserId = "me", showIntro = true),
            {}, {}, null, {}, {}, {}, {}, {}, {}, {}, onOpenGoal = {}, onContribute = {}, onPayRequest = {},
        )
        "chama" -> ChamaContent(ChamaUiState(chat = fund, chama = sampleChama(open = true), ledger = sampleLedger(), fiat = sampleWallet().fiat, loading = false, myUserId = "me"), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        "chama-payments" -> ChamaContent(ChamaUiState(chat = fund, chama = sampleChama(open = true), ledger = sampleLedger(), tab = LedgerTab.Payments, fiat = sampleWallet().fiat, loading = false, myUserId = "me"), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        "chama-empty" -> ChamaContent(ChamaUiState(chat = fund, fiat = sampleWallet().fiat, loading = false, noGoal = true, myUserId = "me"), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        "chama-offline" -> ChamaContent(ChamaUiState(chat = fund, fiat = sampleWallet().fiat, loading = false, online = false, myUserId = "me"), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        "chama-loading" -> ChamaContent(ChamaUiState(chat = fund, myUserId = "me"), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        "chama-goal-sheet" -> ChamaContent(ChamaUiState(chat = fund, fiat = sampleWallet().fiat, loading = false, noGoal = true, myUserId = "me", goalDraft = com.circaso.feature.chama.presentation.GoalDraft("Holiday Fund", "50000", by = com.circaso.feature.chama.presentation.GoalDeadline.ThreeMonths)), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        "chama-payout-outside" -> ChamaContent(ChamaUiState(chat = fund, chama = sampleChama(), fiat = sampleWallet().fiat, loading = false, myUserId = "me", draft = PayoutDraft(someoneElse = true, toName = "Coast Bus", address = "0x1234567890abcdef1234567890abcdef12345678", amountText = "12000", note = "bus deposit")), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        "chama-payout" -> ChamaContent(ChamaUiState(chat = fund, chama = sampleChama(), fiat = sampleWallet().fiat, loading = false, myUserId = "me", draft = PayoutDraft(toUserId = "4", amountText = "12000", note = "bus deposit")), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        "asha-empty" -> AshaContent(AshaUiState(online = true), {}, {}, {}, {}, {}, {})
        "asha" -> AshaContent(
            AshaUiState(
                messages = listOf(msg("1", "USER", "How do I add a friend?"), msg("2", "ASHA", "Open Contacts from the You tab, type their @handle and tap Connect, or share an invite link.")),
                draft = "", online = true,
            ),
            {}, {}, {}, {}, {}, {},
        )
        "asha-offline" -> AshaContent(
            AshaUiState(messages = listOf(msg("1", "USER", "How do I add a friend?"), msg("2", "ASHA", "Open Contacts from the You tab.")), online = false),
            {}, {}, {}, {}, {}, {},
        )
        "asha-maintenance" -> AshaContent(
            AshaUiState(messages = listOf(msg("1", "USER", "How do I add a friend?"), msg("2", "ASHA", "Open Contacts from the You tab.")), draft = "What did I spend on transport this week?", maintenance = true, toldWhenBack = true),
            {}, {}, {}, {}, {}, {},
        )
        "asha-maintenance-empty" -> AshaContent(AshaUiState(maintenance = true), {}, {}, {}, {}, {}, {})
        "asha-back" -> AshaContent(
            AshaUiState(messages = listOf(msg("1", "USER", "How do I add a friend?"), msg("2", "ASHA", "Open Contacts from the You tab.")), draft = "What did I spend on transport this week?", ashaBack = true),
            {}, {}, {}, {}, {}, {},
        )
        "voice-maintenance" -> VoiceContent(
            VoiceUiState(phase = VoicePhase.Ended, ending = VoiceEnding.Maintenance(), turns = listOf(com.circaso.feature.assistant.presentation.VoiceTurn("What's my balance?", listOf("I'm down for maintenance at the moment. I'll let you know as soon as I'm back."), done = true))),
            {}, {}, {}, {},
        )
        "voice-maintenance-empty" -> VoiceContent(VoiceUiState(phase = VoicePhase.Ended, ending = VoiceEnding.Maintenance(toldWhenBack = false)), {}, {}, {}, {})
        "voice-asha-back" -> VoiceContent(VoiceUiState(phase = VoicePhase.Ended, ending = VoiceEnding.AshaBack), {}, {}, {}, {})
        "voice" -> VoiceContent(VoiceUiState(phase = VoicePhase.Listening, heard = "What did I spend on lunch this", micLevel = 0.6f, voiceSecondsLeft = 118), {}, {}, {}, {})
        "voice-speaking" -> VoiceContent(
            VoiceUiState(phase = VoicePhase.Speaking, turns = listOf(com.circaso.feature.assistant.presentation.VoiceTurn("How does Circaso keep my chats private?", listOf("Every message is locked on your phone before it leaves.", "Only the people in the chat hold the keys, so Circaso can't read along."))), voiceLevel = 0.7f),
            {}, {}, {}, {},
        )
        "voice-thinking" -> VoiceContent(VoiceUiState(phase = VoicePhase.Thinking, turns = listOf(com.circaso.feature.assistant.presentation.VoiceTurn("Who did I chat with this week?")), status = "Reading your chats…"), {}, {}, {}, {})
        "voice-ended" -> VoiceContent(VoiceUiState(phase = VoicePhase.Ended, ending = VoiceEnding.Quiet), {}, {}, {}, {})
        "wallet-cards" -> WalletContent(
            WalletUiState(wallet = sampleWallet().copy(chains = sampleWallet().chains + listOf(
                WalletChain("MATIC-AMOY", "0x19aa0000000000000000000000000000000077c1", "ACTIVE", listOf(Asset("USDC", "0xusdc2", 6, "500000000", "500", "64500.00")), true),
                WalletChain("SOL-DEVNET", "So1AbCdEf0000000000000000000000000000002f9", "ACTIVE", listOf(Asset("USDC", "So1usdc", 6, "0", "0", "0.00")), true),
            )), missingNetworks = listOf("ETH-SEPOLIA")),
            PaddingValues(bottom = 90.dp), noWalletActions, {}, {}, {}, {},
        )
        "wallet-loading" -> WalletContent(WalletUiState(loading = true), PaddingValues(bottom = 90.dp), noWalletActions, {}, {}, {}, {})
        "wallet" -> WalletContent(WalletUiState(wallet = sampleWallet()), PaddingValues(bottom = 90.dp), noWalletActions, {}, {}, {}, {})
        "wallet-create" -> WalletContent(WalletUiState(wallet = sampleWallet().copy(status = WalletStatus.None, chains = emptyList(), totals = emptyList()), setupRequestedBy = "Amina"), PaddingValues(bottom = 90.dp), noWalletActions, {}, {}, {}, {})
        "wallet-offline" -> WalletContent(WalletUiState(wallet = sampleWallet(), online = false), PaddingValues(bottom = 90.dp), noWalletActions, {}, {}, {}, {})
        "receive" -> WalletContent(WalletUiState(wallet = sampleWallet(), receiveSheet = true, handle = "pasaka"), PaddingValues(bottom = 90.dp), noWalletActions, {}, {}, {}, {})
        "help" -> com.circaso.feature.support.presentation.HelpContent(
            com.circaso.feature.support.presentation.HelpUiState(topic = com.circaso.feature.support.Support.Topic.Payment, conversation = ChatSummary(supportChat, text(9, "s1", "It's on its way now. Anything else?", false, 60 * 20, sender = "sup", senderName = "Circaso Support"), 0)),
            {}, {}, {}, {}, null,
        )
        "support-chat" -> ThreadContent(
            ThreadUiState(chat = supportChat, myUserId = "me", messages = listOf(
                text(1, "s1", "Hi, I sent money to Otieno an hour ago and it still says pending", true, 12),
                text(2, "s1", "Habari Pasaka! I can see it. The network was slow this morning, so it's waiting to confirm.", false, 10, sender = "sup", senderName = "Circaso Support"),
                text(3, "s1", "Otieno should get it in a few minutes. Want me to stay on until it lands?", false, 9, sender = "sup", senderName = "Circaso Support"),
            )),
            {}, {}, null, {}, {}, {}, {}, {}, {}, {},
        )
        "support-ended" -> ThreadContent(ThreadUiState(chat = supportChat, myUserId = "me", supportEnding = true, messages = listOf(text(1, "s1", "That's all, thank you.", true, 1))), {}, {}, null, {}, {}, {}, {}, {}, {}, {})
        "lost-access" -> com.circaso.feature.support.presentation.LostAccessContent(com.circaso.feature.support.presentation.LostAccessUiState(phone = "+254 712 345 678", email = "amina@example.com"), {}, {}, {}, {}, {})
        "lost-access-sent" -> com.circaso.feature.support.presentation.LostAccessContent(com.circaso.feature.support.presentation.LostAccessUiState(phone = "+254 712 345 678", email = "amina@example.com", caseNumber = "CX-48213"), {}, {}, {}, {}, {})
        "call-incoming" -> CallPreview(com.circaso.feature.calls.CallUi("c1", "2", "Mum", false, false, com.circaso.feature.calls.CallPhase.Incoming, handle = "@mum", photoUrl = PreviewPhotos.Mum), null)
        // No photo: the initials, exactly where the photo would be.
        "call-incoming-initials" -> CallPreview(com.circaso.feature.calls.CallUi("c1", "2", "Mum", false, false, com.circaso.feature.calls.CallPhase.Incoming, handle = "@mum"), null)
        "call-incoming-group" -> CallPreview(com.circaso.feature.calls.CallUi("c2", "g1", "Holiday Fund", false, true, com.circaso.feature.calls.CallPhase.Incoming, caller = "Otieno Odhiambo", photoUrl = PreviewPhotos.Group), null)
        "call-connecting" -> CallPreview(com.circaso.feature.calls.CallUi("c1", "2", "Mum", false, false, com.circaso.feature.calls.CallPhase.Connecting, people = listOf(com.circaso.feature.calls.CallPerson("2", "Mum", true, true, false, false, null, live = false, photoUrl = PreviewPhotos.Mum)), handle = "@mum", photoUrl = PreviewPhotos.Mum), null)
        "call-voice" -> CallPreview(com.circaso.feature.calls.CallUi("c1", "2", "Mum", false, false, com.circaso.feature.calls.CallPhase.Active, people = listOf(com.circaso.feature.calls.CallPerson("2", "Mum", true, true, false, false, null, photoUrl = PreviewPhotos.Mum)), activeSince = now - 252_000, handle = "@mum", photoUrl = PreviewPhotos.Mum), "You sent KES 500 · for lunch")
        "call-video", "call-video-more" -> CallPreview(com.circaso.feature.calls.CallUi("c1", "2", "Mum", true, false, com.circaso.feature.calls.CallPhase.Active, camera = true, speaker = true, people = listOf(com.circaso.feature.calls.CallPerson("2", "Mum", true, true, true, false, null)), activeSince = now - 768_000), "Mum sent you KES 2,000 · for lunch", more = name == "call-video-more")
        // Money to me on a 1:1 call (2026-11-10): "Mum sent you KES 2,000" splashes and settles into the money line.
        "call-voice-received" -> CallPreview(com.circaso.feature.calls.CallUi("c1", "2", "Mum", false, false, com.circaso.feature.calls.CallPhase.Active, people = listOf(com.circaso.feature.calls.CallPerson("2", "Mum", true, true, false, false, null, photoUrl = PreviewPhotos.Mum)), activeSince = now - 252_000, handle = "@mum", photoUrl = PreviewPhotos.Mum), "Mum sent you KES 2,000 · for lunch", received = true)
        "call-video-received" -> CallPreview(com.circaso.feature.calls.CallUi("c1", "2", "Mum", true, false, com.circaso.feature.calls.CallPhase.Active, camera = true, people = listOf(com.circaso.feature.calls.CallPerson("2", "Mum", true, true, true, false, null)), activeSince = now - 768_000), "Mum sent you KES 2,000 · for lunch", received = true)
        // The More sheet on a voice call: share screen (Android), speaker, minimise, Ask Asha; no flip with the camera off.
        "call-voice-more" -> CallPreview(com.circaso.feature.calls.CallUi("c1", "2", "Mum", false, false, com.circaso.feature.calls.CallPhase.Active, people = listOf(com.circaso.feature.calls.CallPerson("2", "Mum", true, true, false, false, null, photoUrl = PreviewPhotos.Mum)), activeSince = now - 252_000, handle = "@mum", photoUrl = PreviewPhotos.Mum), null, more = true)
        "call-ended" -> CallPreview(com.circaso.feature.calls.CallUi("c1", "2", "Mum", false, false, com.circaso.feature.calls.CallPhase.Ended, endReason = "MISSED"), null)
        "call-failed" -> CallPreview(com.circaso.feature.calls.CallUi("c1", "2", "Mum", false, false, com.circaso.feature.calls.CallPhase.Ended, endReason = "FAILED", error = "Calls aren't available right now. Try again in a moment."), null)
        "call-minimised" -> CallPreview(com.circaso.feature.calls.CallUi("c1", "2", "Mum", false, false, com.circaso.feature.calls.CallPhase.Active, minimised = true, activeSince = now - 125_000, photoUrl = PreviewPhotos.Mum), null)
        // "call-group-more": its More sheet, with Add people for Juma, who missed the ring.
        "call-group", "call-group-more" -> CallPreview(
            com.circaso.feature.calls.CallUi("c2", "g1", "Holiday Fund", false, true, com.circaso.feature.calls.CallPhase.Active, activeSince = now - 1_100_000, photoUrl = PreviewPhotos.Group, people = listOf(
                com.circaso.feature.calls.CallPerson("4", "Otieno Odhiambo", true, true, false, false, null, photoUrl = PreviewPhotos.Otieno),
                com.circaso.feature.calls.CallPerson("3", "Wanjiru Kamau", true, false, false, false, null, photoUrl = PreviewPhotos.Wanjiru),
                com.circaso.feature.calls.CallPerson("5", "Achieng Akinyi", true, true, false, false, null),
                com.circaso.feature.calls.CallPerson("6", "Juma Kariuki", false, true, false, name != "call-group-more", null),
            )),
            null, sampleChama(), more = name == "call-group-more",
        )
        // The group call with contributions landing (2026-11-09): one, a coin, then three at once; again every few seconds.
        "call-group-contributions", "call-group-contributions-row" -> CallPreview(
            com.circaso.feature.calls.CallUi("c2", "g1", "Holiday Fund", false, true, com.circaso.feature.calls.CallPhase.Active, activeSince = now - 1_100_000, photoUrl = PreviewPhotos.Group, people = listOf(
                com.circaso.feature.calls.CallPerson("4", "Otieno Odhiambo", true, true, false, false, null, photoUrl = PreviewPhotos.Otieno),
                com.circaso.feature.calls.CallPerson("3", "Wanjiru Kamau", true, false, false, false, null, photoUrl = PreviewPhotos.Wanjiru),
                com.circaso.feature.calls.CallPerson("5", "Achieng Akinyi", true, true, false, false, null),
                com.circaso.feature.calls.CallPerson("6", "Juma Kariuki", true, true, false, false, null),
            )),
            null, sampleChama(),
            contributions = if (name == "call-group-contributions") sampleContributions() else kotlinx.coroutines.flow.emptyFlow(),
            preset = if (name == "call-group-contributions-row") presetContributions() else emptyList(),
        )
        "transfer" -> TransferContent(TransferUiState(transfer = sampleTransfers().first(), fiat = sampleWallet().fiat), {})
        // Transactions (E7): the list, its shape while it is read, nothing yet, offline, a failed read, a date range.
        "activity", "activity-loading", "activity-empty", "activity-offline", "activity-error", "activity-range", "activity-group" -> {
            val base = com.circaso.feature.wallet.presentation.ActivityUiState(
                transfers = sampleTransfers(), fiat = sampleWallet().fiat, loaded = true,
                today = com.circaso.core.platform.localDayIndex(com.circaso.core.sync.nowMillis()),
            )
            val state = when (name) {
                "activity-loading" -> base.copy(transfers = emptyList(), loaded = false)
                "activity-empty" -> base.copy(transfers = emptyList())
                "activity-offline" -> base.copy(transfers = emptyList(), online = false)
                "activity-error" -> base.copy(transfers = emptyList(), error = com.circaso.core.common.ErrorMessages.SomethingWrong)
                "activity-range" -> base.copy(range = com.circaso.feature.wallet.presentation.ActivityRange.Week)
                "activity-group" -> base.copy(groupWallet = true)
                else -> base
            }
            com.circaso.feature.wallet.presentation.ActivityContent(state, {}, {}, {}, {})
        }
        else -> if (!GroupWalletPreview(name) && !ChatMoneyPreview(name) && !MomentsChatsPreview(name) && !AshaPreview(name)) BasicText("Unknown preview: $name")
    }
}

private val noWalletActions = WalletActions({}, {}, {}, {}, {})

private val groupThread = listOf(
    text(1, "g1", "Bus deposit is due Friday. Coast Bus wants KES 12,000", false, 70, sender = "4", senderName = "Otieno"),
    text(2, "g1", "I can do mine tomorrow", false, 60, sender = "3", senderName = "Wanjiru"),
    text(3, "g1", "Adding mine tonight", true, 5),
)

/** Holiday Fund: KES 50,000 target, 32,400 in, Otieno is the treasurer (design D4's numbers at 1 USDC = KES 129.2). */
private fun sampleChama(open: Boolean = false) = Chama(
    goal = ChamaGoal("g1", "Holiday Fund", "386996905", "2026-12-14T00:00:00Z", "4", 1),
    memberCount = 3, contributedMinor = "250774000", paidOutMinor = "0", heldMinor = "250774000", myContributedMinor = "30960000",
    contributors = listOf(Contributor("3", "7740000"), Contributor("4", "38700000"), Contributor("me", "30960000")),
    openRequests = if (open) listOf(sampleRequest()) else emptyList(),
    isAdmin = true, isTreasurer = false, fetchedAt = now,
)

private fun sampleRequest() = ChamaRequest(
    requestId = "r1", chatId = "g1", requestedBy = "4", payeeUserId = null, payeeAddress = "0x1234567890abcdef1234567890abcdef12345678", payeeName = "Coast Bus",
    amountMinor = "92879000", note = "the bus deposit", state = RequestState.Pending, approvalsNeeded = 2,
    approvals = listOf(Approval("4", true, "2026-10-29T08:00:00Z")), transferId = null,
    expiresAt = "2026-10-31T08:00:00Z", createdAt = "2026-10-29T08:00:00Z", updatedAt = "2026-10-29T08:00:00Z",
)

private fun sampleLedger() = listOf(
    LedgerEntry(LedgerDirection.In, "2026-10-28T17:02:00Z", "7740000", "3", "t1", null),
    LedgerEntry(LedgerDirection.In, "2026-10-14T09:00:00Z", "38700000", "4", "t2", null),
    LedgerEntry(LedgerDirection.In, "2026-10-12T09:00:00Z", "30960000", "me", "t3", null),
)

private fun sampleWallet() = Wallet(
    status = WalletStatus.Active,
    chains = listOf(WalletChain("BASE-SEPOLIA", "0x7a3f0000000000000000000000000000001e4b00", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, "249460000", "249.46", "32180.34")), true)),
    totals = listOf(AssetTotal("USDC", 6, "249460000", "249.46")),
    fiat = FiatValue("KES", "32180.34", "129.00", "2026-10-29T10:00:00Z", false),
    holdUntil = null,
    fetchedAt = nowMillis() - 60_000,
)

private fun sampleTransfers() = listOf(
    Transfer("t1", TransferDirection.In, TransferState.Confirmed, "BASE-SEPOLIA", "0xmum", null, "0x7a3f", Amount("USDC", "15500000", "15.5"), null, "c1", "0xhash1", null, "Mum", "for lunch", nowMillis() - 3_600_000, nowMillis() - 3_500_000),
    Transfer("t2", TransferDirection.Out, TransferState.Confirmed, "BASE-SEPOLIA", "0x7a3f", "u3", "0xnjeri", Amount("USDC", "1085000", "1.085"), null, "c2", "0xhash2", null, "Mama Njeri", "4 tomatoes, 2 sukuma", nowMillis() - 90_000_000, nowMillis() - 89_000_000),
    Transfer("t3", TransferDirection.Out, TransferState.Submitted, "BASE-SEPOLIA", "0x7a3f", null, "0x9c21e4b0000000000000000000000000000000ab", Amount("USDC", "50000000", "50"), null, null, null, null, null, null, nowMillis() - 200_000_000, nowMillis() - 200_000_000),
)

@Composable
private fun CallPreview(
    call: com.circaso.feature.calls.CallUi,
    money: String?,
    chama: Chama? = null,
    contributions: kotlinx.coroutines.flow.Flow<com.circaso.feature.chama.domain.GroupContribution> = kotlinx.coroutines.flow.emptyFlow(),
    /** Already there when the screen opens (the row, and one still on its way), for a still capture. */
    preset: List<com.circaso.feature.chama.domain.GroupContribution> = emptyList(),
    /** The More sheet open over the call. */
    more: Boolean = false,
    /** Money to me on a 1:1 call: Mum's KES 2,000 lands a moment after the screen opens, then again every few seconds. */
    received: Boolean = false,
) {
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
        val celebrations = androidx.compose.runtime.remember { com.circaso.feature.calls.presentation.CallCelebrationsModel() }
        val fiat = sampleWallet().fiat
        androidx.compose.runtime.LaunchedEffect(Unit) {
            preset.forEach { celebrations.receive(com.circaso.feature.calls.presentation.celebrationOf(it, call.people, "me", fiat), it.confirmed, visible = false) }
            contributions.collect { celebrations.receive(com.circaso.feature.calls.presentation.celebrationOf(it, call.people, "me", fiat), it.confirmed, visible = !call.minimised) }
        }
        if (received) androidx.compose.runtime.LaunchedEffect(Unit) {
            var n = 0
            while (true) {
                kotlinx.coroutines.delay(if (n == 0) 700L else 4_500L)
                val p = com.circaso.feature.messaging.domain.Message.Payment(
                    localId = n.toLong(), chatId = call.chatId, orderAt = nowMillis(), sentAt = null, messageId = "m$n", senderId = "2", senderName = "Mum",
                    outgoing = false, transferId = "received-$n", amountMinor = "15503876", asset = "USDC", blockchain = "BASE-SEPOLIA", note = "for lunch",
                    state = com.circaso.feature.messaging.domain.DeliveryState.Delivered, errorCode = null,
                )
                celebrations.receive(com.circaso.feature.calls.presentation.receivedCelebration(p, call.people, fiat), confirmed = true, visible = true)
                n++
            }
        }
        // A money sheet that draws nothing, so the bar shows its money button as on a real call; share screen as on Android.
        val actions = com.circaso.feature.calls.presentation.CallActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, moneySheet = { _, _, _ -> }, onShareScreen = {}, onAskAsha = {})
        CallOverlay(call, actions, money, chama, fiat, celebrations, myUserId = "me", moreAtStart = more)
    }
}

/**
 * A scripted run of contributions into Holiday Fund, each sent and then confirmed a moment later: Wanjiru in USDC,
 * Otieno in ETH, then Juma, Achieng and me at once.
 */
private fun sampleContributions() = kotlinx.coroutines.flow.flow {
    var round = 0
    fun c(user: String, coin: String, amount: String, usd: String?, confirmed: Boolean) =
        com.circaso.feature.chama.domain.GroupContribution("g1", "p$round-$user", user, null, coin, amount, usd, confirmed)
    suspend fun kotlinx.coroutines.flow.FlowCollector<com.circaso.feature.chama.domain.GroupContribution>.both(vararg x: List<String?>) {
        x.forEach { emit(c(it[0]!!, it[1]!!, it[2]!!, it[3], confirmed = false)) }
        kotlinx.coroutines.delay(1_500)
        x.forEach { emit(c(it[0]!!, it[1]!!, it[2]!!, it[3], confirmed = true)) }
    }
    while (true) {
        round++
        kotlinx.coroutines.delay(1_000)
        both(listOf("3", "USDC", "7.751938", "7751938"))
        kotlinx.coroutines.delay(2_000)
        both(listOf("4", "ETH", "0.01", "30000000"))
        kotlinx.coroutines.delay(2_600)
        both(listOf("6", "USDC", "19.379845", "19379845"), listOf("5", "USDC", "3.875969", "3875969"), listOf("me", "USDC", "7.751938", "7751938"))
        kotlinx.coroutines.delay(4_000)
    }
}

/** For a still capture: Juma and Wanjiru landed, Achieng's on its way. */
private fun presetContributions() = listOf(
    com.circaso.feature.chama.domain.GroupContribution("g1", "r1", "3", null, "USDC", "7.751938", "7751938", confirmed = true),
    com.circaso.feature.chama.domain.GroupContribution("g1", "r2", "6", null, "USDC", "19.379845", "19379845", confirmed = true),
    com.circaso.feature.chama.domain.GroupContribution("g1", "r3", "5", null, "USDC", "3.875969", "3875969", confirmed = false),
)


/** One of Wanjiru's moments, posted 34 minutes ago; the photo and video carry the sample photo as their placeholder. */
private fun sampleMoment(kind: com.circaso.feature.status.domain.MomentKind): com.circaso.feature.status.domain.Moment {
    val now = com.circaso.core.sync.nowMillis()
    val media = kind != com.circaso.feature.status.domain.MomentKind.Text
    return com.circaso.feature.status.domain.Moment(
        momentId = "m-$kind", posterId = "w", kind = kind,
        text = if (media) null else "Sunday market haul: mangoes, sukuma and the best mandazi in town",
        background = "Ink",
        caption = if (media) "Evening at Ngong Hills" else null,
        media = if (media) com.circaso.feature.status.domain.MomentMedia(
            mediaId = "x", key = "", digest = "", sizeBytes = 0, mime =if (kind == com.circaso.feature.status.domain.MomentKind.Video) "video/mp4" else "image/jpeg", durationMs = if (kind == com.circaso.feature.status.domain.MomentKind.Video) 18_000 else null,
        ) else null,
        thumb = if (media) SamplePhoto else null,
        postedAt = now - 34 * 60_000, expiresAt = now + 3_600_000, mine = false, viewed = false, reacted = false,
    )
}

/**
 * The viewer over one sample moment. A photo shows the sample photo; a video can't play here, so it shows its
 * placeholder while "loading" (V5).
 */
@Composable
private fun MomentPreview(
    moment: com.circaso.feature.status.domain.Moment,
    photo: Boolean = false,
    picking: Boolean = false,
    viewers: List<com.circaso.feature.status.presentation.ViewerRow>? = null,
    videoPath: String? = null,
) {
    val bytes = androidx.compose.runtime.remember { kotlin.io.encoding.Base64.decode(SamplePhoto) }
    val ring = com.circaso.feature.status.domain.MomentRing(if (moment.mine) "me" else "w", if (moment.mine) "My moment" else "Wanjiru Kamau", null, listOf(moment, moment.copy(momentId = "next")), muted = false)
    val media = when {
        moment.kind == com.circaso.feature.status.domain.MomentKind.Text -> com.circaso.feature.status.presentation.ViewerMedia.None
        photo -> com.circaso.feature.status.presentation.ViewerMedia.Ready(bytes, null)
        videoPath != null -> com.circaso.feature.status.presentation.ViewerMedia.Ready(ByteArray(0), videoPath)
        else -> com.circaso.feature.status.presentation.ViewerMedia.Loading
    }
    com.circaso.feature.status.presentation.MomentViewerContent(
        com.circaso.feature.status.presentation.MomentViewerUiState(
            rings = listOf(ring), media = media, picking = picking,
            viewersOpen = viewers != null, viewers = viewers, paused = picking || viewers != null,
        ),
        com.circaso.feature.status.presentation.MomentViewerActions.None, onClose = {},
    )
}

/** A sunset (72×128 JPEG) standing in for a moment's photo in previews. */
private const val SamplePhoto = "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"


/** Stand-in photos for the call previews (fetched and cached like a real profile photo). */
private object PreviewPhotos {
    const val Mum = "https://picsum.photos/id/64/400/400"
    const val Otieno = "https://picsum.photos/id/1005/400/400"
    const val Wanjiru = "https://picsum.photos/id/1027/400/400"
    const val Group = "https://picsum.photos/id/177/400/400"
}
