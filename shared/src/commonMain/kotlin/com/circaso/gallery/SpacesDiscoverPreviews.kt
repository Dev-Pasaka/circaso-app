package com.circaso.gallery

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.presentation.ChatFilter
import com.circaso.feature.messaging.presentation.ChatsContent
import com.circaso.feature.messaging.presentation.ChatsUiState
import com.circaso.feature.spaces.domain.Known
import com.circaso.feature.spaces.domain.LastPost
import com.circaso.feature.spaces.domain.Membership
import com.circaso.feature.spaces.domain.MembershipState
import com.circaso.feature.spaces.domain.MySpace
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.ReceivedInvite
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.SpaceMe
import com.circaso.feature.spaces.domain.SpacePerson
import com.circaso.feature.spaces.domain.SpacePrice
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceSummary
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.Suggestion
import com.circaso.feature.spaces.domain.SuggestionReason
import com.circaso.feature.spaces.presentation.discover.ChatsSpaces
import com.circaso.feature.spaces.presentation.discover.ChatsSpacesUiState
import com.circaso.feature.spaces.presentation.discover.FollowState
import com.circaso.feature.spaces.presentation.discover.SearchPerson
import com.circaso.feature.spaces.presentation.discover.SpaceSearchActions
import com.circaso.feature.spaces.presentation.discover.SpaceSearchContent
import com.circaso.feature.spaces.presentation.discover.SpaceSearchUiState
import com.circaso.feature.spaces.presentation.discover.SpacesHomeActions
import com.circaso.feature.spaces.presentation.discover.SpacesHomeUiState
import com.circaso.feature.spaces.presentation.discover.SpacesSuggestedContent
import com.circaso.feature.spaces.presentation.discover.SpacesSuggestedUiState
import com.circaso.feature.status.presentation.SpacesActions
import com.circaso.feature.status.presentation.SpacesContent
import com.circaso.feature.status.presentation.SpacesUiState
import com.circaso.feature.wallet.domain.FiatValue

private val day = 86_400_000L
private val at = nowMillis()
private val kes = FiatValue("KES", "0", "129.20", "", false)

private fun sp(id: String, name: String, handle: String, vis: SpaceVisibility = SpaceVisibility.Public, topic: SpaceTopic = SpaceTopic.Shopping, members: Int = 1204, price: SpacePrice? = null) =
    SpaceSummary(id, handle, name, null, topic, null, null, vis, price, members)

private val sneaker = sp("s1", "Nairobi Sneaker Drop", "sneakerdrop")
private val books = sp("s2", "Nairobi Book Club", "nairobibooks", SpaceVisibility.MembersOnly, SpaceTopic.Books, 214, SpacePrice("5000000", PricePeriod.Monthly))
private val chamaHub = sp("s3", "Kilimani Chama Hub", "chamahub", topic = SpaceTopic.Chamas, members = 340)
private val coffee = sp("s4", "Coffee Farmers Kenya", "coffeefarmers", topic = SpaceTopic.Farming, members = 4800)
private val runners = sp("s5", "Karura Runners", "karurarunners", topic = SpaceTopic.Sport, members = 910)
private val worship = sp("s6", "Jubilee Youth Worship", "jubileeyouth", SpaceVisibility.MembersOnly, SpaceTopic.Faith, 3180, SpacePrice("1000000", PricePeriod.Monthly))
private val swap = sp("s7", "Kisumu Book Swap", "kisumubooks", topic = SpaceTopic.Books, members = 1100)
private val founders = sp("s8", "Nairobi Founders Circle", "founderscircle", SpaceVisibility.InviteOnly, SpaceTopic.Business, 48)

private val member = SpaceMe(Relation.Member, SpaceRole.Member)
private val mine = listOf(
    MySpace(sneaker, member.copy(role = SpaceRole.Owner), LastPost("p1", "Amina", "Size 42 Jordans back in stock at 6 pm", "TEXT", at - 3_600_000, false), 12, at - 3_600_000),
    MySpace(books, member.copy(membership = Membership(MembershipState.Active, PricePeriod.Monthly, paidUntil = at + 3 * day, renewFrom = at - day)),
        LastPost("p2", "Njeri", "This month’s pick is Kintu", "TEXT", at - 2 * day, false), 0, at - 2 * day),
    MySpace(chamaHub, member, LastPost("p3", "Wanjiru", "Weekly meet moved to Saturday", "TEXT", at - 4 * day, false), 0, at - 4 * day),
)
private val suggested = listOf(
    Suggestion(coffee, SuggestionReason.Contacts, Known(listOf("Juma"), 1)),
    Suggestion(runners, SuggestionReason.Contacts, Known(emptyList(), 3)),
    Suggestion(worship, SuggestionReason.PopularInCountry, Known()),
)

private val home = SpacesHomeUiState(enabled = true, mine = mine, mineLoaded = true, mineAsOf = at - 600_000, suggestions = suggested, suggestionsLoaded = true, fiat = kes, now = at)
private val homeActions = SpacesHomeActions({}, {}, {}, {}, {}, {}, {})
private val moments = SpacesUiState(myName = "Pasaka Mutuku", loaded = true, refreshed = true)
private val momentActions = SpacesActions({}, {}, {}, {})

@Composable
private fun Tab(state: SpacesHomeUiState) =
    SpacesContent(moments.copy(online = state.online), PaddingValues(bottom = 90.dp), momentActions, {}, {}, spaces = state, spacesActions = homeActions, onSearch = {}, onAsha = {})

private val searchActions = SpaceSearchActions({}, {}, {}, {}, {}, {}, {}, {}, {})

private val invite = ReceivedInvite("i1", founders, SpacePerson("w", "Wanjiru Kamau"), at - 2 * day, at + 12 * day, Known(listOf("Wanjiru"), 1))

private fun cm(id: String, name: String) = ChatMember(id, name, null, null, MemberRole.Member, "2026-10-20T00:00:00Z")
private fun chat(id: String, title: String, via: String? = null, kind: ChatKind = ChatKind.Direct) =
    Chat(id, kind, title, null, listOf(cm("me", "Baraka"), cm("u$id", title)), false, 1, 0, MemberRole.Member, if (kind == ChatKind.Direct) "u$id" else null, via)
private fun msg(chatId: String, text: String, minutesAgo: Int, from: String) =
    Message.Text(minutesAgo.toLong(), chatId, at - minutesAgo * 60_000L, null, "m$chatId", "u$chatId", from, false, text, DeliveryState.Read, null, null)
private val inbox = listOf(
    ChatSummary(chat("c1", "Pasaka", via = "s1"), msg("c1", "Paid KES 2,000 · deposit, size 42 Jordans", 60, "Pasaka"), 1),
    ChatSummary(chat("c2", "Juma", via = "s1"), msg("c2", "Is the 44 Chicago still there?", 90, "Juma"), 2),
    ChatSummary(chat("c3", "Mum"), msg("c3", "Did you eat?", 180, "Mum"), 0),
)

@Composable
private fun Chats(state: ChatsUiState, spaces: ChatsSpacesUiState) = ChatsContent(
    state, PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {},
    spaces = ChatsSpaces(spaces, {}, {}, {}, {}, {}, at),
)

/** `-preview=<name>` screens of the spaces discover area; false when the name is not one of them. */
@Composable
internal fun SpacesDiscoverPreview(name: String): Boolean {
    when (name) {
        "sd-tab" -> Tab(home)
        "sd-empty" -> Tab(home.copy(mine = emptyList(), mineAsOf = at))
        "sd-loading" -> Tab(SpacesHomeUiState(enabled = true, mineLoaded = true, now = at))
        "sd-offline" -> Tab(home.copy(online = false))
        "sd-error" -> Tab(home.copy(mine = mine.take(1), suggestions = emptyList(), suggestionsLoaded = false, suggestionsFailed = true, requestId = "req_D2kq7Z"))
        "sd-following" -> Tab(home.copy(follow = mapOf("s4" to FollowState.Busy, "s5" to FollowState.Following)))
        "sd-search" -> SpaceSearchContent(SpaceSearchUiState(query = "book", spaces = listOf(books, swap), answered = "book", fiat = kes,
            people = listOf(SearchPerson("f", "Faith Chebet", null, "faithc", "Book haul from Prestige"))), searchActions, autoFocus = false)
        "sd-search-handle" -> SpaceSearchContent(SpaceSearchUiState(query = "@sneakerdrop", exact = sneaker, answered = "@sneakerdrop",
            spaces = listOf(sp("s9", "Sneaker Drop Mombasa", "sneakerdropmsa", members = 86))), searchActions, autoFocus = false)
        "sd-search-empty" -> SpaceSearchContent(SpaceSearchUiState(query = "mitumba thika", answered = "mitumba thika"), searchActions, autoFocus = false)
        "sd-search-offline" -> SpaceSearchContent(SpaceSearchUiState(query = "chama", online = false, mine = mine.drop(2)), searchActions, autoFocus = false)
        "sd-suggested" -> SpacesSuggestedContent(
            SpacesSuggestedUiState(topic = SpaceTopic.Business, items = suggested + Suggestion(sneaker, SuggestionReason.Popular, Known()), loading = false, fiat = kes,
                follow = mapOf("s4" to FollowState.Following)),
            {}, {}, {}, {}, {}, {},
        )
        "si-invite" -> Chats(ChatsUiState(chats = inbox.drop(2), loaded = true), ChatsSpacesUiState(enabled = true, invites = listOf(invite)))
        "si-declined" -> Chats(ChatsUiState(chats = inbox.drop(2), loaded = true), ChatsSpacesUiState(enabled = true, invites = listOf(invite), declining = setOf("i1")))
        "si-invite-offline" -> Chats(ChatsUiState(chats = inbox.drop(2), loaded = true, online = false), ChatsSpacesUiState(enabled = true, invites = listOf(invite), online = false))
        "sh-owner-inbox" -> Chats(ChatsUiState(chats = inbox, loaded = true, filter = ChatFilter.Spaces),
            ChatsSpacesUiState(enabled = true, spaceNames = mapOf("s1" to "Nairobi Sneaker Drop"), mine = mine.take(1)))
        "sh-inbox-labels" -> Chats(ChatsUiState(chats = inbox, loaded = true), ChatsSpacesUiState(enabled = true, spaceNames = mapOf("s1" to "Nairobi Sneaker Drop"), mine = mine.take(1)))
        else -> return false
    }
    return true
}
