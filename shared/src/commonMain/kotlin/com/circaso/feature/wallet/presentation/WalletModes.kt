package com.circaso.feature.wallet.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Wallet tab's two places (Pasaka, 2026-11-05): the person's own wallet, or their groups' wallets. A segmented
 * control at the top picks one; under Groups an overflow menu picks which group. It replaced the corner popover,
 * which listed every wallet at once and didn't scale to someone in many groups.
 */
enum class WalletMode { Personal, Groups }

/** What the tab remembers between launches: the mode, and the group wallet last shown under Groups. */
data class SavedWalletChoice(val mode: WalletMode = WalletMode.Personal, val groupWalletId: String? = null)

/** Where [SavedWalletChoice] lives (the app's preferences; memory in tests and previews). Not sensitive: ids only. */
interface WalletChoiceStore {
    val walletChoice: Flow<SavedWalletChoice>
    suspend fun saveWalletChoice(choice: SavedWalletChoice)

    companion object {
        fun memory(start: SavedWalletChoice = SavedWalletChoice()): WalletChoiceStore = object : WalletChoiceStore {
            private val value = MutableStateFlow(start)
            override val walletChoice: Flow<SavedWalletChoice> = value
            override suspend fun saveWalletChoice(choice: SavedWalletChoice) { value.value = choice }
        }

        /** On the app's small text preferences ([read] / [write] by name; null removes). */
        fun of(read: (String) -> Flow<String?>, write: suspend (String, String?) -> Unit): WalletChoiceStore = object : WalletChoiceStore {
            override val walletChoice: Flow<SavedWalletChoice> = kotlinx.coroutines.flow.combine(read(ModeKey), read(GroupKey)) { mode, group ->
                SavedWalletChoice(if (mode == "groups") WalletMode.Groups else WalletMode.Personal, group)
            }
            override suspend fun saveWalletChoice(choice: SavedWalletChoice) {
                write(ModeKey, if (choice.mode == WalletMode.Groups) "groups" else "personal")
                write(GroupKey, choice.groupWalletId)
            }
        }

        const val ModeKey = "wallet.mode"
        const val GroupKey = "wallet.group"
    }
}

/**
 * Which wallet the tab shows, from the mode and the group last picked: Personal shows the person's own; Groups the
 * remembered group while it is still one of theirs, else their first, else none (the calm empty state). The flag off
 * or no list yet means Personal, whatever is remembered, so nothing is lost when it comes back.
 */
internal fun WalletUiState.reselected(): WalletUiState {
    val groups = groupWallets
    // The person's last group wallet went away (the list is known and holds none): back to Personal for good, so a
    // group wallet that arrives later never turns the tab to Groups without a tap (Pasaka, 2026-11-06). The group
    // itself stays remembered for when they choose Groups again.
    val mode = if (groupsEnabled && wallets.isNotEmpty() && groups.isEmpty()) WalletMode.Personal else mode
    val id = if (mode == WalletMode.Personal || !groupsEnabled) null
    else (groups.firstOrNull { it.walletId == lastGroupWalletId } ?: groups.firstOrNull())?.walletId
    return copy(mode = mode, selectedWalletId = id, switching = switching && id != null, allGroups = allGroups && groupsEnabled && groups.isNotEmpty())
}

/** How many groups the overflow menu lists before "All Group Wallets…" opens the searchable list. */
internal const val MenuGroupLimit = 5

/**
 * The groups the menu lists: the first [MenuGroupLimit] in the server's order, always including the one on screen
 * (it takes the last place when it would have fallen past the limit), so its tick is never hidden.
 */
internal fun menuGroups(groups: List<NamedWallet>, selectedId: String?): List<NamedWallet> {
    if (groups.size <= MenuGroupLimit) return groups
    val top = groups.take(MenuGroupLimit)
    val current = groups.firstOrNull { it.walletId == selectedId }
    return if (current == null || current in top) top else top.dropLast(1) + current
}

/** Search over the person's group wallets by name, ignoring case: every typed word, in any order. */
internal fun filterGroups(groups: List<NamedWallet>, query: String): List<NamedWallet> {
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return groups
    return groups.filter { w -> val name = w.name.lowercase(); words.all { it in name } }
}

/** One choice in a [NativeMoreMenu]. [avatarName] draws the group's picture where the platform's menus have room. */
data class NativeMenuItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val checked: Boolean = false,
    val icon: NativeMenuIcon? = null,
    val avatarName: String? = null,
    val avatarUrl: String? = null,
    /** Read out in place of title and subtitle (the balance hidden, for example). */
    val accessibilityLabel: String? = null,
)

/** The few action glyphs the menu uses: drawn by Circaso on Android, SF Symbols on iOS. */
enum class NativeMenuIcon { Chat, Info, List, Send, External }

/**
 * The platform's own overflow button and menu. Android: a vertical three-dot button (Material "more vert") and an
 * anchored Material dropdown. iOS: an `ellipsis.circle` button that opens a real UIKit pull-down menu (UIMenu), with
 * its title, sections, ticks and subtitles. [sections] are separated by the platform's own separator.
 *
 * [expanded] is the menu's state on Android; on iOS UIKit owns it, and [expanded] true only asks for it to open once
 * (previews), after which [onExpandedChange] says it is closed again.
 */
@Composable
internal expect fun NativeMoreMenu(
    title: String,
    sections: List<List<NativeMenuItem>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
)
