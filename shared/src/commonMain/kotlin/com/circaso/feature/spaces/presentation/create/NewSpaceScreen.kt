package com.circaso.feature.spaces.presentation.create

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.navigation.NewSpaceRoute
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SegmentedControl
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.PostingMode
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.presentation.PopoverHead
import com.circaso.feature.spaces.presentation.PopoverItem
import com.circaso.feature.spaces.presentation.SpacePopover
import com.circaso.feature.spaces.presentation.SpacesNav
import com.circaso.feature.spaces.presentation.fiatText
import com.circaso.feature.spaces.presentation.shortDate
import com.circaso.feature.spaces.presentation.visibilityIcon
import com.circaso.feature.wallet.presentation.RadioMark
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** SN1–SN3, SN7: a new space in three short full-screen steps; then the wallet's PIN (SN4). */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun NewSpaceEntry(route: NewSpaceRoute, nav: SpacesNav) {
    val vm = koinViewModel<NewSpaceViewModel>(key = "new-space", parameters = { parametersOf(route.name) })
    val state by vm.state.collectAsState()
    val created by vm.created.collectAsState()
    LaunchedEffect(created) {
        // The space exists from here (SETTING_UP); its wallet is next, over the place this flow was opened from.
        created?.let { id -> vm.onCreatedHandled(); nav.back(); nav.walletSetup(id, true) }
    }
    androidx.compose.ui.backhandler.BackHandler(enabled = state.step > 1 || state.picking) { vm.onBack() }
    NewSpaceContent(
        state, NewSpaceCallbacks(
            onClose = { if (!vm.onBack()) nav.back() }, onCancel = nav.back, onNext = vm::onNext, onTryAgain = vm::onTryAgain,
            onPickPhoto = vm::onPickPhoto, onName = vm::onName, onHandle = vm::onHandle, onSuggestion = vm::onSuggestion, onAbout = vm::onAbout,
            onTopic = vm::onTopic, onVisibility = vm::onVisibility, onPrice = vm::onPrice, onPeriod = vm::onPeriod,
            onPostingMode = vm::onPostingMode, onPicking = vm::onPicking, onQuery = vm::onQuery, onToggle = vm::onToggle, onRole = vm::onRole,
            onFindSpaces = { nav.back(); nav.search() },
        ),
    )
}

/** Everything the steps can do, so [NewSpaceContent] stays stateless for previews. */
class NewSpaceCallbacks(
    val onClose: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onTryAgain: () -> Unit = {},
    val onPickPhoto: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onHandle: (String) -> Unit = {},
    val onSuggestion: (String) -> Unit = {},
    val onAbout: (String) -> Unit = {},
    val onTopic: (com.circaso.feature.spaces.domain.SpaceTopic) -> Unit = {},
    val onVisibility: (SpaceVisibility) -> Unit = {},
    val onPrice: (String) -> Unit = {},
    val onPeriod: (PricePeriod) -> Unit = {},
    val onPostingMode: (PostingMode) -> Unit = {},
    val onPicking: (Boolean) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onToggle: (com.circaso.feature.spaces.domain.SpacePerson) -> Unit = {},
    val onRole: (String, SpaceRole?) -> Unit = { _, _ -> },
    val onFindSpaces: () -> Unit = {},
)

@Composable
fun NewSpaceContent(state: NewSpaceUiState, cb: NewSpaceCallbacks) {
    val notYet = state.problem as? CreateProblem.NotYet
    CircasoScaffold(
        grouped = true,
        topBar = {
            Column {
                CircasoNavBar(
                    title = if (state.picking) "Add People" else "New Space",
                    onBack = if (state.picking || state.step > 1) cb.onClose else cb.onCancel,
                    trailing = { if (state.picking) CircasoButton("Done", { cb.onPicking(false) }, style = ButtonStyle.Plain, size = ButtonSize.Small) },
                )
                if (notYet == null && !state.picking) StepsBar(state.step, modifier = Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1))
            }
        },
    ) { padding ->
        when {
            // SN7c: said once, with the date, in place of the form.
            notYet != null -> Column(Modifier.fillMaxSize().padding(top = 160.dp)) {
                CenterMessage(
                    CircasoIcon.Clock,
                    notYet.availableAt?.let { "You can create a space from ${shortDate(it)}" } ?: "You can’t create a space yet",
                    "New Circaso accounts wait 7 days before starting a space. It keeps spaces free of spam. You can follow and join spaces now.",
                ) { CircasoButton("Find Spaces", cb.onFindSpaces, style = ButtonStyle.Secondary, size = ButtonSize.Small) }
            }
            state.picking -> TeamPicker(state, cb, padding)
            else -> Column(Modifier.fillMaxSize()) {
                AnimatedContent(
                    state.step, Modifier.weight(1f),
                    transitionSpec = { fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) }, label = "step",
                ) { step ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4).padding(top = 14.dp, bottom = CircasoSpacing.s6),
                        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
                    ) {
                        Problems(state)
                        when (step) {
                            1 -> DetailsStep(state, cb)
                            2 -> JoinStep(state, cb)
                            else -> RolesStep(state, cb)
                        }
                    }
                }
                Footer(state, cb, padding)
            }
        }
    }
}

@Composable
private fun Problems(state: NewSpaceUiState) {
    // SN7: offline, said before the tap; what was typed stays. SN7b: nothing was made, with the reference.
    if (!state.online) Banner("You’re offline. Creating a space needs a connection.", CircasoIcon.Offline)
    (state.problem as? CreateProblem.Failed)?.let { f ->
        Banner(f.message + (f.reference?.let { " Ref $it" } ?: ""), CircasoIcon.Warning, tone = BannerTone.Critical)
    }
}

@Composable
private fun Footer(state: NewSpaceUiState, cb: NewSpaceCallbacks, padding: androidx.compose.foundation.layout.PaddingValues) {
    val t = CircasoTheme.typography
    val c = CircasoTheme.colors
    val failed = state.problem is CreateProblem.Failed && state.step == 3
    Column(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4).padding(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        val label = when {
            failed -> "Try Again"
            state.step < 3 -> "Next"
            else -> "Create Space and Set Up Wallet"
        }
        CircasoButton(label, if (failed) cb.onTryAgain else cb.onNext, size = ButtonSize.Large, block = true, enabled = state.canContinue, busy = state.creating)
        if (!state.online) BasicText("Needs a connection", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
}

// ---------------------------------------------------------------- SN1 details

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsStep(state: NewSpaceUiState, cb: NewSpaceCallbacks) {
    val c = CircasoTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier.size(72.dp).clip(CircasoShapes.lg).background(c.fill)
                .pressable(role = Role.Button, onClick = cb.onPickPhoto)
                .semantics(mergeDescendants = true) { contentDescription = if (state.photo == null) "Add a space photo" else "Change the space photo" },
            contentAlignment = Alignment.Center,
        ) {
            if (state.photo != null) com.circaso.feature.spaces.presentation.SpaceTile(state.name.ifBlank { "Space" }, null, 72.dp)
            CircasoIconImage(if (state.photo == null) CircasoIcon.Camera else CircasoIcon.Check, c.inkSecondary, Modifier.size(26.dp), strokeWidth = 1.8f)
        }
        CircasoTextField(
            state.name, cb.onName, "Space name", Modifier.weight(1f), placeholder = "Kilimani Yoga Collective",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
    }
    HandleField(state.handle, state.handleState, cb.onHandle, cb.onSuggestion)
    AboutField(state.about, cb.onAbout)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("Topic")
        TopicChips(state.topic, cb.onTopic)
    }
}

/** The @handle with its live check (SN1, SN1b): checking, available with the link, or taken with three free ones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HandleField(handle: String, check: HandleState, onHandle: (String) -> Unit, onSuggestion: (String) -> Unit, enabled: Boolean = true) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val bad = check is HandleState.Taken || check is HandleState.Invalid
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CircasoTextField(
            handle, onHandle, "Handle", placeholder = "kilimaniyoga", enabled = enabled,
            error = if (bad) "" else null,
            leading = { BasicText("@", style = t.body.copy(color = c.inkSecondary)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
        )
        Box(Modifier.semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) {
            when (check) {
                HandleState.Empty -> FieldHint("This becomes the space’s link.")
                HandleState.Checking -> Row(Modifier.padding(horizontal = CircasoSpacing.s1), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(c.inkSecondary, Modifier.size(12.dp))
                    BasicText("Checking…", style = t.footnote.copy(color = c.inkSecondary))
                }
                HandleState.Available -> FieldHint("Available · circaso.app/s/$handle", HintTone.Good, CircasoIcon.Check)
                HandleState.Unknown -> FieldHint("We’ll check it when you’re back online.")
                is HandleState.Invalid -> FieldHint(check.reason, HintTone.Critical)
                is HandleState.Taken -> FieldHint(if (check.suggestions.isEmpty()) "@$handle is taken." else "@$handle is taken. Try one of these:", HintTone.Critical)
            }
        }
        if (check is HandleState.Taken && check.suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = CircasoSpacing.s1)) {
                check.suggestions.forEach { s -> SpaceChip("@$s", false, { onSuggestion(s) }, role = Role.Button, label = "Use @$s") }
            }
        }
    }
}

/** About, up to 300 characters with its count (SN1). */
@Composable
fun AboutField(about: String, onAbout: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("About")
        BasicTextField(
            about, { if (it.length <= 300) onAbout(it) }, singleLine = false, minLines = 3,
            textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "About" },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(CircasoShapes.md).background(c.fill).padding(horizontal = CircasoSpacing.s4, vertical = 14.dp)) {
                    if (about.isEmpty()) BasicText("What it’s for, when people meet, what members get.", style = t.body.copy(color = c.inkTertiary))
                    inner()
                }
            },
        )
        BasicText("${about.length} / 300", Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s1), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.End))
    }
}

// ---------------------------------------------------------------- SN2 who can join

@Composable
private fun JoinStep(state: NewSpaceUiState, cb: NewSpaceCallbacks) {
    val name = state.name.trim().ifEmpty { "your space" }
    BasicText("Who can join $name", Modifier.padding(horizontal = 4.dp).semantics { heading() }, style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary))
    JoinOptions(state.visibility, cb.onVisibility, priceBody = {
        PriceFields(state.priceText, state.priceProblem, state.period, state.fiat, cb.onPrice, cb.onPeriod)
        InfoLine("You receive the full ${state.priceMinor?.let { com.circaso.feature.spaces.presentation.usdcText(it) } ?: "price"}; members pay Circaso’s 1% fee on top. Renewals are never taken automatically: members are reminded and pay with their PIN, with 3 days’ grace.")
    })
}

/** SN2 and SN8b: the three choices as cards, each saying what it means; members only opens [priceBody]. */
@Composable
fun JoinOptions(selected: SpaceVisibility, onSelect: (SpaceVisibility) -> Unit, enabled: Boolean = true, priceBody: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OptionCard(visibilityIcon(SpaceVisibility.Public), "Public", "Anyone can find it and follow. For shops and open communities.", selected == SpaceVisibility.Public, { onSelect(SpaceVisibility.Public) }, enabled)
        OptionCard(visibilityIcon(SpaceVisibility.MembersOnly), "Members only", "Shows in search with its price. People pay to join.", selected == SpaceVisibility.MembersOnly, { onSelect(SpaceVisibility.MembersOnly) }, enabled, body = priceBody)
        OptionCard(visibilityIcon(SpaceVisibility.InviteOnly), "Invite only", "Hidden from search. Admins invite people or accept requests.", selected == SpaceVisibility.InviteOnly, { onSelect(SpaceVisibility.InviteOnly) }, enabled)
    }
}

/** The price in USDC (the unit members pay in) with the person's currency beside it, and the period. */
@Composable
fun PriceFields(priceText: String, problem: String?, period: PricePeriod, fiat: com.circaso.feature.wallet.domain.FiatValue?, onPrice: (String) -> Unit, onPeriod: (PricePeriod) -> Unit, hintPrefix: String? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val minor = com.circaso.feature.wallet.domain.MinorUnits.fromDisplay(priceText.trim(), 6)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CircasoTextField(
            priceText, onPrice, "Price to join", placeholder = "10.00", error = problem,
            leading = { BasicText("USDC", style = t.body.copy(color = c.inkSecondary)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        if (problem == null) {
            val local = minor?.let { fiatText(it, fiat) }
            FieldHint(listOfNotNull(hintPrefix, local?.let { "About $it today." }, "Members see it in their own currency.").joinToString(" "))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("Members pay")
        SegmentedControl(PricePeriod.entries.map { it.label }, period.ordinal, { onPeriod(PricePeriod.entries[it]) })
    }
}

// ---------------------------------------------------------------- SN3 chat and roles

@Composable
private fun RolesStep(state: NewSpaceUiState, cb: NewSpaceCallbacks) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("Who can post in the chat")
        SegmentedControl(listOf("Everyone", "Admins only"), state.postingMode.ordinal, { cb.onPostingMode(PostingMode.entries[it]) })
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("Team (optional)", Modifier.padding(horizontal = 4.dp).semantics { heading() }, style = t.footnote.copy(color = c.inkSecondary))
        ListGroup {
            ListRow("You", sub = null, leading = { Avatar("You", size = CircasoSizes.avatarMd) }, trailing = { com.circaso.feature.spaces.presentation.RoleBadge("Owner") })
            state.team.forEach { pick ->
                RowDivider()
                ListRow(pick.person.displayName, sub = pick.person.handle?.let { "@$it" }, leading = { Avatar(pick.person.displayName, size = CircasoSizes.avatarMd, imageUrl = pick.person.avatarUrl) }, trailing = {
                    RoleChip(pick.person.displayName, pick.role) { role -> cb.onRole(pick.person.userId, role) }
                })
            }
            RowDivider()
            ListRow("Add People", leading = {
                Box(Modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
                    CircasoIconImage(CircasoIcon.UserPlus, c.ink, Modifier.size(18.dp), strokeWidth = 1.8f)
                }
            }, trailing = { RowChevron() }, onClick = { cb.onPicking(true) })
        }
        BasicText("Admins manage members, invites and reports. Moderators remove posts and mute people.", Modifier.padding(horizontal = 4.dp), style = t.footnote.copy(color = c.inkSecondary))
    }
    InfoLine("Every space has its own wallet. Only you can send from it, with its own PIN; admins can see it but not move it.", CircasoIcon.Lock)
}

/** A team member's role (SN3): a chip that opens a popover, never a sheet. */
@Composable
private fun RoleChip(name: String, role: SpaceRole, onRole: (SpaceRole?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SpaceChip(role.label, false, { open = true }, role = Role.Button, trailing = CircasoIcon.ChevronDown, label = "$name role: ${role.label}, change")
        SpacePopover(open, { open = false }, label = "$name’s role", alignEnd = true, width = 220.dp) {
            PopoverHead(name)
            PopoverItem("Admin", { onRole(SpaceRole.Admin); open = false }, checked = role == SpaceRole.Admin)
            PopoverItem("Moderator", { onRole(SpaceRole.Moderator); open = false }, checked = role == SpaceRole.Moderator)
            PopoverItem("Remove from Team", { onRole(null); open = false }, icon = CircasoIcon.UserMinus, critical = true, divider = true)
        }
    }
}

/** SN3's Add People: contacts, ticked in place; a page of the flow (not a sheet). Up to 20. */
@Composable
private fun TeamPicker(state: NewSpaceUiState, cb: NewSpaceCallbacks, padding: androidx.compose.foundation.layout.PaddingValues) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val picked = state.team.map { it.person.userId }.toSet()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4).padding(top = CircasoSpacing.s2, bottom = padding.calculateBottomPadding() + CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        CircasoTextField(state.query, cb.onQuery, "Search contacts", placeholder = "Name or @username", leading = { CircasoIconImage(CircasoIcon.Search, c.inkSecondary, Modifier.size(16.dp)) })
        val list = state.filteredContacts
        if (list.isEmpty()) BasicText(if (state.contacts.isEmpty()) "Your contacts on Circaso show here. You can add people to the team later too." else "No contacts match.", style = t.subhead.copy(color = c.inkSecondary))
        else ListGroup {
            list.forEachIndexed { i, p ->
                if (i > 0) RowDivider()
                val on = p.userId in picked
                ListRow(p.displayName, sub = p.handle?.let { "@$it" }, leading = { Avatar(p.displayName, size = CircasoSizes.avatarMd, imageUrl = p.avatarUrl) }, trailing = { RadioMark(on) }, onClick = { cb.onToggle(p) })
            }
        }
        if (state.team.size >= 20) BasicText("A team can have up to 20 people to begin with.", style = t.footnote.copy(color = c.inkSecondary))
        Spacer(Modifier.height(CircasoSpacing.s2))
    }
}
