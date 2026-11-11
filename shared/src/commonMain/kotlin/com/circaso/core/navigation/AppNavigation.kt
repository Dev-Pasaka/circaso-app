package com.circaso.core.navigation

import com.circaso.feature.share.presentation.ShareLinkLayer
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.circaso.core.sync.SyncAnnouncements
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.savedstate.serialization.SavedStateConfiguration
import com.circaso.core.platform.ClientInfo
import com.circaso.core.session.AuthManager
import com.circaso.core.session.AuthState
import com.circaso.feature.auth.presentation.CodeScreen
import com.circaso.feature.auth.presentation.CodeViewModel
import com.circaso.feature.auth.presentation.PhoneScreen
import com.circaso.feature.auth.presentation.RegisteringScreen
import com.circaso.feature.auth.presentation.SplashScreen
import com.circaso.feature.auth.presentation.WelcomeScreen
import com.circaso.designsystem.components.AshaLauncher
import com.circaso.designsystem.components.LocalAshaLauncher
import com.circaso.feature.account.presentation.BiometricApprovalSection
import com.circaso.feature.account.presentation.SecurityLogScreen
import com.circaso.feature.account.presentation.SecurityScreen
import com.circaso.core.platform.LiveMicrophone
import com.circaso.feature.assistant.presentation.AshaScreen
import com.circaso.feature.assistant.presentation.capturable
import com.circaso.feature.assistant.presentation.AshaBubbleLayer
import com.circaso.feature.assistant.presentation.VoiceScreen
import com.circaso.feature.wallet.presentation.ActivityScreen
import com.circaso.feature.wallet.presentation.HoldBanner
import com.circaso.feature.home.presentation.NotificationsScreen
import com.circaso.feature.wallet.presentation.NetworksScreen
import com.circaso.feature.wallet.presentation.PayScreen
import com.circaso.feature.wallet.presentation.PayViewModel
import com.circaso.feature.wallet.presentation.SendToWalletScreen
import com.circaso.feature.wallet.presentation.SendToWalletViewModel
import com.circaso.feature.wallet.presentation.TransferScreen
import com.circaso.feature.wallet.presentation.TransferViewModel
import com.circaso.feature.wallet.presentation.WalletActions
import com.circaso.feature.wallet.presentation.WalletTab
import com.circaso.feature.wallet.presentation.WalletTitleActions
import com.circaso.feature.wallet.presentation.WalletViewModel
import com.circaso.feature.contacts.presentation.BlockedScreen
import com.circaso.feature.contacts.presentation.ContactsScreen
import com.circaso.feature.forceupdate.ForceUpdateScreen
import com.circaso.feature.profile.presentation.EditProfileScreen
import com.circaso.feature.home.presentation.HomeScreen
import com.circaso.feature.messaging.presentation.ChatInfoScreen
import com.circaso.feature.messaging.presentation.ChatInfoViewModel
import com.circaso.feature.messaging.presentation.ChatsBanner
import com.circaso.feature.messaging.presentation.ChatsTab
import com.circaso.feature.messaging.presentation.ChatsViewModel
import com.circaso.feature.messaging.presentation.NewChatScreen
import com.circaso.feature.messaging.presentation.NewGroupScreen
import com.circaso.feature.messaging.presentation.ThreadScreen
import com.circaso.feature.messaging.presentation.ThreadViewModel
import com.circaso.feature.messaging.presentation.ChatMoneySheet
import com.circaso.feature.home.presentation.YouActions
import com.circaso.feature.home.presentation.YouScreen
import com.circaso.gallery.PreviewScreens
import com.circaso.gallery.launchValue
import org.koin.compose.koinInject
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.presentation.ChamaScreen
import com.circaso.feature.chama.presentation.ChamaViewModel
import com.circaso.feature.wallet.presentation.PayIntent
import com.circaso.feature.calls.presentation.CallOverlay
import com.circaso.feature.messaging.presentation.ChatsOverlay
import com.circaso.feature.wallet.presentation.WalletOverlay
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The single navigation host. The first screen is decided by the session, never by the screens themselves:
 * no session shows Welcome, a half-finished sign-up resumes at its step, a full session opens the app.
 * The saved back stack survives the app being killed.
 *
 * [forceUpdate] replaces the whole stack with the force-update screen: nothing else may be used on an old build.
 */
@Composable
fun AppNavigation(forceUpdate: Boolean, home: @Composable () -> Unit) {
    val auth: AuthManager = koinInject()
    val state by auth.state.collectAsState()
    val stored by auth.stored.collectAsState()
    val twoPaneNow = com.circaso.designsystem.adaptive.LocalWindowLayout.current.twoPane
    // Home's tab (see switchTab below). The Wallet tab is a dashboard: on a tablet it takes the whole window.
    var homeTab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val splitHome = twoPaneNow && homeTab != com.circaso.feature.home.presentation.Tab.Wallet.ordinal
    val backStack = rememberNavBackStack(SavedStateConfiguration { serializersModule = routeSerializers }, SplashRoute)

    val root: Route = when (state) {
        AuthState.Restoring -> SplashRoute
        AuthState.SignedOut -> WelcomeRoute
        AuthState.Onboarding -> if (stored.onboardingStep == "REGISTER_DEVICE") RegisteringRoute else PhoneRoute
        AuthState.Active -> HomeRoute
    }
    val client: com.circaso.core.platform.ClientInfo = koinInject()
    // Debug builds only: on Android launch arguments are extras any app could put on the launcher intent.
    val preview = remember { launchValue("-preview")?.takeIf { client.debugBuild } }
    // Debug builds: `-group-wallets=on|off|default` (Android: `--es group-wallets off`) overrides the build's default.
    val groupWalletsArg = remember { launchValue("-group-wallets") }
    val prefs: com.circaso.core.datastore.AppPreferences = koinInject()
    LaunchedEffect(groupWalletsArg) { if (groupWalletsArg != null && client.debugBuild) prefs.setGroupWalletsOverride(groupWalletsArg) }
    LaunchedEffect(root, forceUpdate) {
        val target: Route = if (forceUpdate) ForceUpdateRoute else if (preview != null) PreviewRoute(preview) else root
        if (backStack.firstOrNull() != target) {
            backStack.clear()
            backStack.add(target)
        }
    }

    // The depth a full-screen viewer was dragged down to (Moments): that pop has no sideways slide, the viewer just
    // fades over the screen underneath while it carries on down by itself. -1 when the last pop was an ordinary one.
    val droppedTo = remember { IntArray(1) { -1 } }
    fun pop() { droppedTo[0] = -1; if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    fun dropDown() { if (backStack.size > 1) { droppedTo[0] = backStack.size - 1; backStack.removeAt(backStack.lastIndex) } }
    // From the list in a split, a chat replaces what the detail pane shows (iPad Messages): Home stays the only
    // thing under it, so Back from the chat returns to the empty pane, not through every chat opened before.
    fun openChatFromList(id: String) {
        if (splitHome) while (backStack.size > 1 && backStack.last() != HomeRoute) backStack.removeAt(backStack.lastIndex)
        backStack.add(ChatRoute(id))
    }
    // The Groups wallet's sheet actions (one place for the tab's header and its sheet).
    fun groupMenuActions() = com.circaso.feature.wallet.presentation.GroupMenuActions(
        onOpenChat = ::openChatFromList, onGroupInfo = { backStack.add(ChatInfoRoute(it)) },
        onPay = { wid -> backStack.add(PayRoute(fromWalletId = wid)) }, onSendToWallet = { wid -> backStack.add(SendToWalletRoute(wid)) },
        onNetwork = { wid, chain -> backStack.add(NetworkAssetsRoute(chain, wid)) },
    )
    fun backToHome() { droppedTo[0] = -1; while (backStack.size > 1 && backStack.last() != HomeRoute) backStack.removeAt(backStack.lastIndex) }
    // Home's tab lives here, with the screens each tab opened (TabStacks): changing tab swaps the detail pane's
    // screens for the new tab's, so another tab's sub-screens never show, not even on Back.
    val tabStacks = remember { TabStacks<NavKey>() }
    // Bumped on each tab change, so the detail pane swaps to the new tab's screen in place (iPadOS) rather than
    // sliding as if pushed or popped.
    var tabEpoch by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // The back stack a tab change left behind: the scene change it causes is the tab swapping in, not a push or a back.
    // It counts only while the stack is still exactly that, worked out during composition so it can't race a frame.
    var tabSwapStack by remember { androidx.compose.runtime.mutableStateOf<List<NavKey>?>(null) }
    val swapPending = tabSwapStack != null && tabSwapStack == backStack.toList()
    // Forgotten the first time the stack moves on, so coming back to the same stack later is an ordinary back.
    if (tabSwapStack != null && !swapPending) androidx.compose.runtime.SideEffect { tabSwapStack = null }
    fun switchTab(to: Int, restore: Boolean = true) {
        if (to == homeTab) return
        if (!restore) tabStacks.forget(to)
        tabStacks.switch(backStack, HomeRoute, from = homeTab, to = to, keep = twoPaneNow)
        homeTab = to
        tabEpoch++
        tabSwapStack = backStack.toList()
    }
    // Signed out (Home gone from the stack): no tab's screens may come back after the next sign-in, and the next
    // person starts on Chats.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { HomeRoute in backStack }.collect { if (!it) { tabStacks.clear(); homeTab = 0 } }
    }
    // A tapped notification (Pasaka, 2026-11-06): once signed in and Home is underneath, open where it points, over
    // Home, whether Circaso was cold, in the background or on screen. Signed out, the tap is dropped.
    val notificationLookups: com.circaso.core.notifications.NotificationLookups = koinInject()
    // Notification privacy is mirrored where iOS's notification extension reads it, from every launch on.
    // Turning it on also takes down what's already showing, so the promise holds for those too.
    val shownNotifications: com.circaso.core.platform.ChatNotifications = koinInject()
    LaunchedEffect(Unit) {
        var was: Boolean? = null
        prefs.flag(com.circaso.core.datastore.AppPreferences.NotificationPrivacy).collect { on ->
            com.circaso.core.notifications.mirrorNotificationPrivacy(on)
            if (on && was == false) shownNotifications.clearShown()
            was = on
        }
    }
    LaunchedEffect(state, forceUpdate) {
        val taps = com.circaso.core.notifications.NotificationTaps
        if (forceUpdate || preview != null) return@LaunchedEffect
        if (state == AuthState.SignedOut) { taps.pending.value?.let(taps::consumed); return@LaunchedEffect }
        if (state != AuthState.Active) return@LaunchedEffect
        taps.pending.filterNotNull().collect { destination ->
            androidx.compose.runtime.snapshotFlow { backStack.firstOrNull() }.first { it == HomeRoute }
            val opening = com.circaso.core.notifications.NotificationRoutes.resolve(destination, notificationLookups)
            // The tab first: the current tab's screens are set aside for it (not lost), the new tab's are not brought
            // back; then what the notification opens, on that tab.
            opening.tab?.let { switchTab(com.circaso.feature.home.presentation.Tab.valueOf(it.name).ordinal, restore = false) }
            backToHome()
            opening.routes.forEach { backStack.add(it) }
            taps.consumed(destination)
        }
    }
    val calls = koinInject<com.circaso.feature.calls.CallController>()
    // A Pay tapped on a chat's profile starts the payment in that chat's composer (money starts in the chat).
    val moneyPrompts = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
    // Typing /asha in any text field summons Asha's floating bubble over the screen (2026-11-09), only with a full
    // session (she needs the server). The bubble lives here, above every screen and, since 2026-11-10, above calls.
    val ashaBubble = remember { com.circaso.feature.assistant.presentation.AshaBubbleState() }
    val asha = if (state == AuthState.Active) AshaLauncher { ashaBubble.summon() } else null
    // Secret inputs on screen (PIN pads, codes, passwords): the screen is never shown to Asha while one is.
    val secretsOnScreen = remember { com.circaso.designsystem.components.SecretsOnScreen() }
    val screenCapture = com.circaso.feature.assistant.presentation.rememberScreenCapture()
    val announcements: SyncAnnouncements = koinInject()
    val toast = rememberToastState()
    // Every share is a link (decision 2026-11-06): one layer over the whole app makes them and opens the share sheet.
    val shareLinks: com.circaso.feature.share.presentation.ShareLinkViewModel = koinInject()
    val shareLauncher = remember(shareLinks) { com.circaso.feature.share.presentation.ShareLinkLauncher(shareLinks::start) }
    // A share link opened from outside (App Links, Universal Links, circaso://s/…) waits until someone is signed in.
    val pendingLink by com.circaso.feature.share.domain.IncomingLinks.pending.collectAsState()
    LaunchedEffect(state, pendingLink, forceUpdate) {
        if (state == AuthState.Active && !forceUpdate && pendingLink != null) {
            // Wait for the root swap above to land first, so the link opens over Home rather than being cleared by it,
            // and is only taken once it can open (an effect restarted while waiting leaves it pending).
            androidx.compose.runtime.snapshotFlow { backStack.firstOrNull() }.first { it == HomeRoute }
            com.circaso.feature.share.domain.IncomingLinks.take()?.let { code -> backStack.add(SharedLinkRoute(code)) }
        }
    }
    // Something shared in from another app waits the same way (kept through sign-in), then opens where it goes.
    val pendingShare by com.circaso.feature.mediacompose.IncomingShares.pending.collectAsState()
    LaunchedEffect(state, pendingShare, forceUpdate) {
        if (state == AuthState.Active && !forceUpdate && pendingShare != null) {
            androidx.compose.runtime.snapshotFlow { backStack.firstOrNull() }.first { it == HomeRoute }
            // A newer share replaces the one being chosen for: the picker starts again on top.
            backStack.removeAll { it == ShareTargetRoute }
            backStack.add(ShareTargetRoute)
        }
    }
    val shareUi by shareLinks.state.collectAsState()
    val shareStatus = com.circaso.feature.share.presentation.ShareLinksStatus(
        busy = shareUi.target?.kind?.takeIf { shareUi.creating && !shareUi.sheetOpen },
        online = shareUi.online,
    )
    LaunchedEffect(Unit) {
        announcements.sentAfterOffline.collect { n -> toast.show("Back online · $n ${if (n == 1) "change" else "changes"} sent") }
    }
    CompositionLocalProvider(
        LocalAshaLauncher provides asha,
        com.circaso.designsystem.components.LocalSecretsOnScreen provides secretsOnScreen,
        com.circaso.feature.share.presentation.LocalShareLinks provides (if (state == AuthState.Active) shareLauncher else null),
        com.circaso.feature.share.presentation.LocalShareLinksStatus provides shareStatus,
    ) {
    Box {
    val twoPane = com.circaso.designsystem.adaptive.LocalWindowLayout.current.twoPane
    val still = com.circaso.designsystem.theme.CircasoTheme.reduceMotion
    // Whether a scene change is a tab change swapping in: its target is the stack switchTab left (pure, so the
    // several times AnimatedContent asks during one change all agree).
    fun <S> androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation3.scene.Scene<S>>.tabSwap(): Boolean where S : Any =
        swapPending && tabSwapStack?.lastOrNull()?.let { top -> targetState.entries.lastOrNull()?.contentKey == "$top:${top::class}" } == true
    val lastDepth = remember { IntArray(1) { backStack.size } }
    val popping = remember(backStack.size) { (backStack.size < lastDepth[0]).also { lastDepth[0] = backStack.size } }
    // Sheets and photo viewers draw over the whole window, not inside the pane that opened them (WindowOverlay.kt);
    // only over the screens: the share sheet, toasts and calls below stay above them.
    // "Show Asha my screen" pictures the screens and their sheets (this box), never the bubble, a call or a toast.
    Box(Modifier.capturable(screenCapture)) {
    com.circaso.designsystem.chrome.WindowOverlayLayer(enabled = launchValue("-window-overlay")?.takeIf { client.debugBuild } != "off") {
    // A tab change that shortens the stack isn't a back: the screen it shows must not start dimmed.
    CompositionLocalProvider(LocalNavPopping provides (popping && !swapPending), LocalNavCut provides swapPending) {
    NavDisplay(
        backStack = backStack,
        onBack = ::pop,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator(), rememberDimNavEntryDecorator()),
        // On a wide window Home is a list beside whatever it opened (docs/adaptive-layout-plan.md).
        // The Wallet tab is a dashboard, not a list (Pasaka, 2026-11-08: the card looked shrunk in the narrow list
        // pane): on a tablet it takes the whole window, its card centred at a card's size, and what it opens covers it
        // with a Back, as on a phone. Chats and You keep the list beside the detail.
        // The full-window Wallet stays in the same scene as the split (listFull), so Home is never handed between
        // scenes on a tab change: both drew it for a frame and the app aborted (TestFlight on Mac, 2026-11-10).
        sceneStrategies = listOf(remember(twoPaneNow, splitHome, tabEpoch) { ListDetailSceneStrategy(twoPaneNow, tabEpoch, listFull = twoPaneNow && !splitHome) }),
        // The same screen on top before and after (a rotation or resize into or out of the split) only reflows; a
        // change of Home's tab swaps in place (iPadOS), never slides as a push or a back.
        transitionSpec = { if (tabSwap() || sameTop()) EnterTransition.None togetherWith ExitTransition.None else pushTransition(still) },
        popTransitionSpec = {
            when {
                tabSwap() || sameTop() -> EnterTransition.None togetherWith ExitTransition.None
                backStack.size == droppedTo[0] -> dropTransition(still)
                else -> popTransition(still)
            }
        },
        predictivePopTransitionSpec = { popTransition(still) },
        entryProvider = entryProvider<NavKey> {
            entry<SplashRoute> { SplashScreen() }
            entry<WelcomeRoute> { WelcomeScreen(koinViewModel()) }
            entry<PhoneRoute> {
                PhoneScreen(koinViewModel(), onLostAccess = { phone -> backStack.add(LostAccessRoute(phone)) }) { phone, verification ->
                    backStack.add(CodeRoute(phone, verification.verificationId, verification.codeLength, verification.expiresInSeconds, verification.resendAfterSeconds))
                }
            }
            entry<CodeRoute> { route ->
                val viewModel = koinViewModel<CodeViewModel>(
                    key = route.verificationId,
                    parameters = { parametersOf(route.phone, route.verificationId, route.codeLength, route.resendAfterSeconds) },
                )
                CodeScreen(viewModel, route.codeLength, onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) })
            }
            entry<RegisteringRoute> { RegisteringScreen(koinViewModel()) }
            entry<HomeRoute>(metadata = Panes.List) {
                val info: ClientInfo = koinInject()
                val chatsViewModel = koinViewModel<ChatsViewModel>()
                val walletViewModel = koinViewModel<WalletViewModel>()
                // Moments (Spaces stopped 2026-11-05): the Moments chip in Chats and the rings on chat avatars.
                val momentsViewModel: com.circaso.feature.status.presentation.MomentsViewModel = koinViewModel()
                val momentsActions = com.circaso.feature.status.presentation.MomentsActions(
                    onOpenRing = { id -> backStack.add(MomentViewerRoute(id)) },
                    onOpenMine = { backStack.add(MomentViewerRoute(null)) },
                    onCompose = { backStack.add(MomentComposerRoute) },
                    onFindPeople = { backStack.add(ContactsRoute) },
                    onRetry = { momentsViewModel.refresh(force = true) },
                    onRetryPost = momentsViewModel::onRetryPost,
                    onDeletePost = momentsViewModel::onDeletePost,
                )
                val chatsMoments = remember(momentsViewModel) { com.circaso.feature.messaging.presentation.ChatsMoments(momentsViewModel, momentsActions) }
                // First time on Home: the contacts prompt once, then the phone book syncs, so New chat isn't empty.
                val phoneBook: com.circaso.feature.contacts.data.repository.PhoneBookAutoSync = koinInject()
                androidx.compose.runtime.LaunchedEffect(phoneBook) { phoneBook.onHome() }
                HomeScreen(
                    selectedTab = homeTab,
                    onSelectTab = { switchTab(it) },
                    onBackToTab = { switchTab(it, restore = false) },
                    chats = { bottomPadding -> ChatsTab(chatsViewModel, bottomPadding, onAsha = { backStack.add(AshaRoute) }, onVoice = { backStack.add(AshaRoute); backStack.add(VoiceRoute) }, onNewChat = { backStack.add(NewChatRoute) }, selectedChatId = if (twoPane) backStack.filterIsInstance<ChatRoute>().lastOrNull()?.chatId else null, moments = chatsMoments) { id -> openChatFromList(id) } },
                    chatsActions = {},
                    chatsBanner = {
                        val s by chatsViewModel.state.collectAsState()
                        val w by walletViewModel.state.collectAsState()
                        androidx.compose.foundation.layout.Column {
                            HoldBanner(w.wallet?.holdUntil, onNotMe = { backStack.add(SecurityRoute) }, reason = w.wallet?.holdReason)
                            ChatsBanner(s)
                            val bio: com.circaso.core.platform.Biometrics = koinInject()
                            com.circaso.feature.keybackup.presentation.RestoreGroupNamesCard(koinViewModel(), unlock = if (bio.available()) bio.name else "Screen Lock")
                        }
                    },
                    wallet = { bottomPadding ->
                        val groupMenu = groupMenuActions()
                        // GW16 (Pasaka, 2026-11-04): one wallet design for every wallet; the corner switcher picks which.
                        WalletTab(
                            walletViewModel, bottomPadding,
                            actions = { walletId -> walletActions(walletId, backStack::add) },
                            groupActions = { key, gw -> groupWalletActions(key, gw, backStack::add) },
                            groupMenu = groupMenu,
                        )
                    },
                    walletActions = { WalletTitleActions(walletViewModel) },
                    chatsOverlay = { ChatsOverlay(chatsViewModel, onAsha = { backStack.add(AshaRoute) }, onVoice = { backStack.add(AshaRoute); backStack.add(VoiceRoute) }) },
                    walletOverlay = {
                        WalletOverlay(walletViewModel, walletActions(null, backStack::add), biometrics = koinViewModel(), groupMenu = groupMenuActions())
                    },
                ) { bottomPadding ->
                    val youViewModel: com.circaso.feature.home.presentation.YouViewModel = koinViewModel()
                    YouScreen(
                        viewModel = youViewModel,
                        actions = YouActions(
                            onEditProfile = { backStack.add(EditProfileRoute) },
                            onContacts = { backStack.add(ContactsRoute) },
                            onBlocked = { backStack.add(BlockedRoute) },
                            onSecurity = { backStack.add(SecurityRoute) },
                            onNotifications = { backStack.add(NotificationsRoute) },
                            onHelp = { backStack.add(HelpRoute) },
                            onSecretMenu = { backStack.add(GalleryRoute) },
                            onNeedsAttention = { backStack.add(NeedsAttentionRoute) },
                            onCurrency = { backStack.add(CurrencyRoute) },
                            onMoneySound = youViewModel::onMoneySound,
                            onNotificationPrivacy = youViewModel::onNotificationPrivacy,
                            onMomentsPrivacy = { backStack.add(MomentsPrivacyRoute) },
                            onSharedLinks = { backStack.add(SharedLinksRoute) },
                        ),
                        appVersion = info.appVersion,
                        bottomPadding = bottomPadding,
                    )
                }
            }
            entry<EditProfileRoute> { EditProfileScreen(koinViewModel(), onBack = ::pop) }
            entry<NeedsAttentionRoute> { com.circaso.feature.home.presentation.NeedsAttentionScreen(koinViewModel(), onBack = ::pop) }
            entry<HelpRoute> {
                com.circaso.feature.support.presentation.HelpScreen(
                    koinViewModel(), onBack = ::pop,
                    onOpenChat = { id -> openChatFromList(id) },
                    onAsha = { backStack.add(AshaRoute) },
                    onCallChat = if (calls.available) ({ id -> calls.start(id, false) }) else null,
                )
            }
            entry<LostAccessRoute> { route ->
                com.circaso.feature.support.presentation.LostAccessScreen(koinViewModel(key = "lost-" + route.phone, parameters = { parametersOf(route.phone) }), onBack = ::pop)
            }
            entry<ContactsRoute> { ContactsScreen(koinViewModel(), onBack = ::pop) }
            // A chat's photos, videos and documents: the moment composer's screens, sent to this chat (2026-11-07).
            entry<ChatMediaRoute>(metadata = Panes.FullScreen) { route ->
                com.circaso.feature.messaging.presentation.ChatMediaComposerScreen(
                    koinViewModel(key = "chat-media-${route.chatId}-${route.start}-${route.more.joinToString()}", parameters = { parametersOf(route.chatId, route.start, route.more) }),
                    onClose = {
                        // A share sent to one chat lands in that chat (WhatsApp-style); to several, back where it started.
                        pop()
                        if (route.start == com.circaso.feature.messaging.presentation.ChatComposeStart.Shared.name && route.more.isEmpty() && backStack.lastOrNull() !is ChatRoute) backStack.add(ChatRoute(route.chatId))
                    },
                )
            }
            entry<ShareTargetRoute> {
                com.circaso.feature.messaging.presentation.ShareTargetScreen(
                    koinViewModel(), onClose = { com.circaso.feature.mediacompose.IncomingShares.clear(); pop() },
                    onGo = { to ->
                        pop()
                        when (to) {
                            com.circaso.feature.mediacompose.ShareDestination.Moment -> backStack.add(SharedMomentRoute)
                            is com.circaso.feature.mediacompose.ShareDestination.Chats -> backStack.add(
                                ChatMediaRoute(to.chatIds.first(), com.circaso.feature.messaging.presentation.ChatComposeStart.Shared.name, to.chatIds.drop(1)),
                            )
                        }
                    },
                )
            }
            entry<SharedMomentRoute>(metadata = Panes.FullScreen) {
                com.circaso.feature.status.presentation.MomentComposerScreen(
                    koinViewModel(key = "moment-shared", parameters = { parametersOf(true) }), onClose = ::pop,
                    onAudience = { backStack.add(MomentsPrivacyRoute) }, onFindPeople = { backStack.add(ContactsRoute) },
                )
            }
            entry<MomentComposerRoute>(metadata = Panes.FullScreen) { com.circaso.feature.status.presentation.MomentComposerScreen(koinViewModel(), onClose = ::pop, onAudience = { backStack.add(MomentsPrivacyRoute) }, onFindPeople = { backStack.add(ContactsRoute) }) }
            entry<MomentViewerRoute>(metadata = Panes.FullScreen) { key ->
                val chats = koinInject<com.circaso.feature.messaging.domain.ChatsRepository>()
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                com.circaso.feature.status.presentation.MomentViewerScreen(
                    koinViewModel(key = "moments-${key.posterId}-${key.momentId}", parameters = { parametersOf(key.posterId, key.momentId) }), onClose = ::pop,
                    onDragClose = ::dropDown,
                    // V1b "Message": their chat on this phone if there is one, else opened on the server.
                    onMessage = { posterId ->
                        scope.launch {
                            val local = chats.chats.first().firstOrNull { it.chat.peerId == posterId && it.chat.viaSpaceId == null }?.chat?.chatId
                            val chatId = local ?: (chats.openDirect(posterId) as? com.circaso.core.common.AppResult.Success)?.value?.chatId
                            if (chatId != null) { pop(); openChatFromList(chatId) }
                        }
                    },
                )
            }
            entry<MomentsPrivacyRoute> { com.circaso.feature.status.presentation.MomentsPrivacyScreen(koinViewModel(), onBack = ::pop, onFindPeople = { backStack.add(ContactsRoute) }) }
            entry<BlockedRoute> { BlockedScreen(koinViewModel(), onBack = ::pop) }
            entry<SecurityRoute> {
                SecurityScreen(
                    koinViewModel(), onBack = ::pop, onActivity = { backStack.add(SecurityLogRoute) }, onSendMoney = { backStack.add(PayRoute()) },
                    payments = {
                        BiometricApprovalSection(koinViewModel())
                        val walletRepo: com.circaso.feature.wallet.domain.WalletRepository = koinInject()
                        val myWallet by walletRepo.wallet.collectAsState(null)
                        val bio: com.circaso.core.platform.Biometrics = koinInject()
                        com.circaso.feature.vault.presentation.VaultRow(
                            repository = koinInject(),
                            hasWallet = myWallet?.status == com.circaso.feature.wallet.domain.WalletStatus.Active,
                            unlock = if (bio.available()) bio.name else "Screen Lock",
                            onOpen = { backStack.add(VaultRoute) },
                        )
                        com.circaso.feature.keybackup.presentation.GroupBackupSection(koinViewModel())
                    },
                )
            }
            entry<VaultRoute> { com.circaso.feature.vault.presentation.VaultScreen(koinViewModel(), onBack = ::pop) }
            entry<NotificationsRoute> { NotificationsScreen(koinViewModel(), onBack = ::pop, onOpenTransfer = { id -> backStack.add(TransferRoute(id)) }) }
            entry<SecurityLogRoute> { SecurityLogScreen(koinViewModel(), onBack = ::pop) }
            entry<AshaRoute> {
                // The mic opens the live conversation where the phone can encode Opus; elsewhere it records a clip (A3).
                val live = koinInject<LiveMicrophone>().supported
                // In Asha's own chat, `/asha` is just words to her.
                CompositionLocalProvider(LocalAshaLauncher provides null) {
                AshaScreen(
                    koinViewModel(), onBack = ::pop,
                    onVoice = if (live) ({ if (backStack.lastOrNull() != VoiceRoute) backStack.add(VoiceRoute) }) else null,
                    // Asha's settings live in her menu (asha-next.md part A), pushed on top of her chat.
                    onVoiceSettings = { if (backStack.lastOrNull() != AshaVoiceRoute) backStack.add(AshaVoiceRoute) },
                )
                }
            }
            entry<VoiceRoute>(metadata = Panes.FullScreen) { VoiceScreen(koinViewModel(), onBack = ::pop) }
            entry<ChatRoute> { route ->
                val threadViewModel = koinViewModel<ThreadViewModel>(key = route.chatId, parameters = { parametersOf(route.chatId) })
                val thread by threadViewModel.state.collectAsState()
                val peer = thread.chat?.peerId
                val goal = thread.chama?.goal
                // Group wallets (GW9a2, GW9): a group whose wallet is active takes contributions into it; its signer pays payouts from it.
                val groupMoney = thread.groupMoney
                val intoGroup = groupMoney?.walletActive == true
                val callNow by calls.state.collectAsState()
                var payingRequest by androidx.compose.runtime.remember(route.chatId) { androidx.compose.runtime.mutableStateOf<ChamaRequest?>(null) }
                ThreadScreen(
                    threadViewModel,
                    onBack = ::pop,
                    onOpenMoment = { posterId, momentId -> backStack.add(MomentViewerRoute(posterId, momentId)) },
                    onInfo = { backStack.add(ChatInfoRoute(route.chatId)) },
                    onAsha = { if (backStack.lastOrNull() != AshaRoute) backStack.add(AshaRoute) },
                    onPay = if (peer != null) ({ amount -> backStack.add(PayRoute(userId = peer, chatId = route.chatId, displayName = thread.chat?.title, amount = amount)) }) else null,
                    onOpenTransfer = { id -> backStack.add(TransferRoute(id)) },
                    onOpenGoal = { backStack.add(if (groupMoney != null) GroupWalletRoute(route.chatId) else ChamaRoute(route.chatId)) },
                    // D3: a contribution is a payment to the treasurer from this chat; D5: the treasurer pays the approved request.
                    onContribute = when {
                        intoGroup -> ({ amount -> backStack.add(PayRoute(groupId = route.chatId, chatId = route.chatId, displayName = thread.chat?.title, amount = amount, goalName = goal?.name)) })
                        goal != null && groupMoney == null -> ({ amount -> backStack.add(PayRoute(userId = goal.treasurerUserId, chatId = route.chatId, displayName = thread.memberName(goal.treasurerUserId), amount = amount, goalName = goal.name)) })
                        else -> null
                    },
                    // D5/GW9: an approved request's Pay opens its money card over this chat; the PIN is the only step after it.
                    onPayRequest = { r -> payingRequest = r },
                    payout = payingRequest?.let { r ->
                        val pr = payRequestRoute(route.chatId, goal?.name, r, thread::memberName, fromWalletId = groupMoney?.wallet?.walletId)
                        koinViewModel<com.circaso.feature.wallet.presentation.PayViewModel>(
                            key = "payout-${route.chatId}-${r.requestId}",
                            parameters = { parametersOf(PayIntent(pr.userId, pr.chatId, pr.displayName, pr.amount, pr.address, pr.goalName, pr.requestId, fromWalletId = pr.fromWalletId, inChat = true)) },
                        )
                    },
                    onPayoutClose = { payingRequest = null },
                    moneyPrompt = moneyPrompts[route.chatId],
                    onMoneyPromptHandled = { moneyPrompts.remove(route.chatId) },
                    onCall = if (calls.available) ({ video -> calls.start(route.chatId, video) }) else null,
                    ongoingCall = callNow?.takeIf { it.chatId == route.chatId && it.minimised && it.phase != com.circaso.feature.calls.CallPhase.Ended }?.let { if (it.video || it.camera) "Ongoing video call" else "Ongoing voice call" },
                    onReturnToCall = { calls.minimise(false) },
                    focusLocalId = route.focusLocalId,
                    onOpenLocation = { m -> backStack.add(LocationMapRoute(route.chatId, m.localId)) },
                    onComposeMedia = { start -> backStack.add(ChatMediaRoute(route.chatId, start.name)) },
                    quickPay = chatQuickPay(route.chatId, thread),
                    onCallSupport = if (calls.available) ({ calls.start(route.chatId, false) }) else null,
                )
            }
            entry<NewChatRoute> {
                NewChatScreen(koinViewModel(), onBack = ::pop, onOpened = { id -> pop(); openChatFromList(id) }, onNewGroup = { backStack.add(NewGroupRoute) })
            }
            entry<NewGroupRoute> {
                NewGroupScreen(koinViewModel(), onBack = ::pop, onCreated = { id, plan ->
                    pop(); pop(); openChatFromList(id)
                    // GW1b → GW2: the group exists; its wallet is set up over the new chat, so a failure lands in it.
                    if (plan != null) backStack.add(
                        GroupWalletSetupRoute(id, plan.approvalPolicy?.name, plan.targetMinor, plan.targetName, plan.deadline, plan.thenTarget),
                    )
                })
            }
            entry<GroupWalletSetupRoute>(metadata = Panes.FullScreen) { route ->
                val plan = com.circaso.feature.chama.presentation.GroupWalletPlan(
                    approvalPolicy = route.approvalPolicy?.let { runCatching { com.circaso.feature.chama.domain.ApprovalPolicy.valueOf(it) }.getOrNull() },
                    targetMinor = route.targetMinor, targetName = route.targetName, deadline = route.deadline, thenTarget = route.thenTarget,
                )
                com.circaso.feature.chama.presentation.GroupWalletSetupScreen(
                    koinViewModel(key = "gw-setup-" + route.chatId, parameters = { parametersOf(route.chatId, plan) }),
                    onOpenGroup = ::pop,
                    onDone = { pop(); if (route.thenTarget) backStack.add(TargetRoute(route.chatId, walletReady = true)) },
                )
            }

            entry<GroupWalletRoute> { route ->
                val vm = koinViewModel<com.circaso.feature.chama.presentation.GroupWalletViewModel>(key = "group-wallet-" + route.chatId, parameters = { parametersOf(route.chatId) })
                val gw by vm.state.collectAsState()
                com.circaso.feature.chama.presentation.GroupWalletScreen(
                    vm, onBack = ::pop,
                    actions = groupWalletActions(route.chatId, gw, backStack::add),
                    walletActions = walletActions(gw.walletId, backStack::add),
                )
            }
            entry<TargetRoute> { route ->
                com.circaso.feature.chama.presentation.TargetScreen(koinViewModel(key = "target-" + route.chatId, parameters = { parametersOf(route.chatId, route.walletReady) }), onBack = ::pop)
            }
            entry<LocationMapRoute>(metadata = Panes.FullScreen) { route ->
                com.circaso.feature.messaging.presentation.map.LocationMapScreen(
                    koinViewModel<com.circaso.feature.messaging.presentation.map.LocationMapViewModel>(
                        key = "map-${route.chatId}-${route.focusLocalId}", parameters = { parametersOf(route.chatId, route.focusLocalId) },
                    ),
                    onBack = ::pop,
                )
            }
            entry<ChatInfoRoute> { route ->
                val infoViewModel = koinViewModel<ChatInfoViewModel>(key = route.chatId, parameters = { parametersOf(route.chatId) })
                val info by infoViewModel.state.collectAsState()
                val peer = info.chat?.peerId
                ChatInfoScreen(
                    infoViewModel, onBack = ::pop, onLeft = { pop(); pop() }, onGroupWallet = { backStack.add(if (info.money.enabled) GroupWalletRoute(route.chatId) else ChamaRoute(route.chatId)) },
                    onSetUpWallet = { thenTarget -> backStack.add(GroupWalletSetupRoute(route.chatId, thenTarget = thenTarget)) },
                    onTarget = { backStack.add(TargetRoute(route.chatId)) },
                    onPayFromWallet = { wid -> backStack.add(PayRoute(fromWalletId = wid)) },
                    onSendToWallet = { wid -> backStack.add(SendToWalletRoute(wid)) },
                    onCall = if (calls.available) ({ video -> calls.start(route.chatId, video) }) else null,
                    // Money starts in the chat: Pay goes back to it with `#` in the composer, ready for the amount.
                    onPay = if (peer != null) ({
                        moneyPrompts[route.chatId] = com.circaso.core.sync.nowMillis()
                        pop()
                        if (backStack.lastOrNull().let { it !is ChatRoute || it.chatId != route.chatId }) backStack.add(ChatRoute(route.chatId))
                    }) else null,
                    onOpenLocation = { m -> backStack.add(LocationMapRoute(route.chatId, m.localId)) },
                    onOpenMessage = { id ->
                        pop()
                        // The chat under the profile reopens at that message.
                        val at = backStack.indexOfLast { it is ChatRoute && it.chatId == route.chatId }
                        if (at >= 0) backStack[at] = ChatRoute(route.chatId, focusLocalId = id) else backStack.add(ChatRoute(route.chatId, focusLocalId = id))
                    },
                )
            }
            entry<ChamaRoute> { route ->
                val chamaViewModel = koinViewModel<ChamaViewModel>(key = "chama-" + route.chatId, parameters = { parametersOf(route.chatId) })
                val chama by chamaViewModel.state.collectAsState()
                ChamaScreen(
                    chamaViewModel,
                    onBack = ::pop,
                    onContribute = { chama.chama?.goal?.let { g -> backStack.add(PayRoute(userId = g.treasurerUserId, chatId = route.chatId, displayName = chama.memberName(g.treasurerUserId), goalName = g.name)) } },
                    onPayRequest = { r -> backStack.add(payRequestRoute(route.chatId, chama.chama?.goal?.name, r, chama.memberName)) },
                    onOpenTransfer = { id -> backStack.add(TransferRoute(id)) },
                )
            }
            entry<PayRoute>(metadata = Panes.FullScreen) { route ->
                PayScreen(
                    koinViewModel<PayViewModel>(
                        key = "pay-${route.userId}-${route.chatId}-${route.requestId}-${route.groupId}-${route.fromWalletId}",
                        parameters = { parametersOf(PayIntent(route.userId, route.chatId, route.displayName, route.amount, route.address, route.goalName, route.requestId, groupId = route.groupId, fromWalletId = route.fromWalletId, note = route.note)) },
                    ),
                    onBack = ::pop,
                    onOpenTransfer = { id -> backStack.add(TransferRoute(id)) },
                    onOpenChat = if (route.chatId == null) ({ id -> pop(); openChatFromList(id) }) else null,
                    // The picker's "A Wallet Address" (not in a frame; added 2026-11-06 so withdrawing to an outside
                    // wallet is findable from Send, personal or group): E5 from the same wallet, Back returns here.
                    onToAddress = { from -> backStack.add(SendToWalletRoute(from)) },
                )
            }
            entry<SendToWalletRoute>(metadata = Panes.FullScreen) { route ->
                SendToWalletScreen(
                    koinViewModel<SendToWalletViewModel>(key = "send-to-wallet-${route.fromWalletId}", parameters = { parametersOf(route.fromWalletId) }),
                    onBack = ::pop, onOpenTransfer = { id -> pop(); backStack.add(TransferRoute(id)) },
                )
            }
            entry<TransferRoute> { route -> TransferScreen(koinViewModel<TransferViewModel>(key = route.transferId, parameters = { parametersOf(route.transferId) }), onBack = ::pop) }
            entry<NetworksRoute> { NetworksScreen(koinViewModel(), onBack = ::pop) }
            entry<ActivityRoute> { route -> ActivityScreen(koinViewModel(key = "activity-all-" + route.walletId, parameters = { parametersOf(null, null, route.walletId) }), onBack = ::pop, onOpen = { id -> backStack.add(TransferRoute(id)) }) }
            entry<AshaVoiceRoute> { com.circaso.feature.assistant.presentation.AshaVoiceScreen(koinViewModel(), onBack = ::pop) }
            entry<CurrencyRoute> { com.circaso.feature.wallet.presentation.CurrencyScreen(koinViewModel(), onBack = ::pop) }
            entry<AssetActivityRoute> { route ->
                ActivityScreen(
                    koinViewModel(key = route.blockchain + route.symbol + route.walletId, parameters = { parametersOf(route.blockchain, route.symbol, route.walletId) }),
                    onBack = ::pop, onOpen = { id -> backStack.add(TransferRoute(id)) },
                )
            }
            entry<NetworkActivityRoute> { route ->
                ActivityScreen(
                    koinViewModel(key = "activity-" + route.blockchain + route.walletId, parameters = { parametersOf(route.blockchain, null, route.walletId) }),
                    onBack = ::pop, onOpen = { id -> backStack.add(TransferRoute(id)) },
                )
            }
            entry<NetworkAssetsRoute> { route ->
                com.circaso.feature.wallet.presentation.NetworkAssetsScreen(
                    koinViewModel(key = "assets-" + route.blockchain + route.walletId, parameters = { parametersOf(route.blockchain, route.walletId) }),
                    onBack = ::pop, onAsset = { chain, symbol -> backStack.add(AssetActivityRoute(chain, symbol, route.walletId)) },
                )
            }
            entry<SharedLinkRoute> { route ->
                com.circaso.feature.share.presentation.SharedLinkScreen(
                    koinViewModel(key = "shared-link-${route.code}", parameters = { parametersOf(route.code) }),
                    onBack = ::pop,
                ) { destination ->
                    when (destination) {
                        is com.circaso.feature.share.presentation.SharedLinkDestination.Transfer -> backStack.add(TransferRoute(destination.transferId))
                        is com.circaso.feature.share.presentation.SharedLinkDestination.Chama -> backStack.add(ChamaRoute(destination.chatId))
                        is com.circaso.feature.share.presentation.SharedLinkDestination.Chat -> { pop(); openChatFromList(destination.chatId) }
                        is com.circaso.feature.share.presentation.SharedLinkDestination.Pay -> backStack.add(PayRoute(userId = destination.userId, displayName = destination.displayName))
                        is com.circaso.feature.share.presentation.SharedLinkDestination.PayAddress -> backStack.add(PayRoute(address = destination.address, displayName = destination.displayName))
                    }
                }
            }
            entry<SharedLinksRoute> { com.circaso.feature.share.presentation.SharedLinksScreen(koinViewModel(), onBack = ::pop) }
            entry<PreviewRoute> { route -> PreviewScreens(route.name) }
            entry<GalleryRoute> { home() }
            entry<ForceUpdateRoute> { ForceUpdateScreen() }
        },
    )
    }
    }
    }
    if (state == AuthState.Active) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        ShareLinkLayer(shareLinks) { text: String -> scope.launch { toast.show(text) } }
    }
    ToastHost(toast, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
    // Calls (designs H1–H5) live above every screen: one can ring anywhere, and it keeps going while money is sent from it.
    if (state == AuthState.Active) CallLayer(backStack, onAskAsha = asha?.let { { ashaBubble.summon() } }) { id ->
        // The chat's own Pay: `#` in its composer once it opens (ThreadScreen's money prompt).
        moneyPrompts[id] = com.circaso.core.sync.nowMillis()
        if (backStack.lastOrNull().let { it !is ChatRoute || it.chatId != id }) backStack.add(ChatRoute(id))
    }
    // Asha's bubble (2026-11-09): over every screen and, since 2026-11-10, over calls too (1:1 and group, voice and
    // video), so `/asha` in the call's money sheet or the More sheet's Ask Asha opens her there. Under system dialogs
    // and Circle's PIN, which are windows of their own. Made on the first summon, then kept for the session.
    var ashaBubbleMade by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (ashaBubble.open && !ashaBubbleMade) androidx.compose.runtime.SideEffect { ashaBubbleMade = true }
    if (state != AuthState.Active && ashaBubble.open) androidx.compose.runtime.SideEffect { ashaBubble.close() }
    if (ashaBubbleMade && state == AuthState.Active) {
        val bubbleViewModel = koinViewModel<com.circaso.feature.assistant.presentation.AshaViewModel>(key = "asha-bubble")
        val top = backStack.lastOrNull()
        val callNow by calls.state.collectAsState()
        val liveVoice = koinInject<LiveMicrophone>().supported
        val callState = com.circaso.feature.calls.presentation.ashaCallState(callNow)
        // On a call she is asked about the call ("On this call"), not the screen hidden under it.
        val onCallScreen = callState == com.circaso.feature.assistant.presentation.AshaCallState.OnScreen || callState == com.circaso.feature.assistant.presentation.AshaCallState.Ringing
        // Over the black call, in its dark colours in every theme, as the call's own sheets are (high contrast kept).
        val highContrast = com.circaso.designsystem.platform.rememberIsHighContrast()
        val colors = if (onCallScreen) com.circaso.designsystem.theme.circasoColors(dark = true, highContrast = highContrast) else com.circaso.designsystem.theme.LocalCircasoColors.current
        CompositionLocalProvider(com.circaso.designsystem.theme.LocalCircasoColors provides colors) {
        AshaBubbleLayer(
            ashaBubble, bubbleViewModel, if (onCallScreen) "this call" else screenName(top, homeTab),
            // Asha's own screens are her already (unless a call covers them); a call keeps its microphone and its
            // controls, and other people's faces stay out of her pictures.
            mode = com.circaso.feature.assistant.presentation.ashaBubbleMode(ashaOnTop = top == AshaRoute || top == VoiceRoute || top == AshaVoiceRoute, call = callState),
            actions = com.circaso.feature.assistant.presentation.AshaBubbleActions(
                onVoice = if (liveVoice) ({ if (backStack.lastOrNull() != VoiceRoute) backStack.add(VoiceRoute) }) else null,
                onOpenFull = { ashaBubble.close(); if (backStack.lastOrNull() != AshaRoute) backStack.add(AshaRoute) },
                capture = { screenCapture.take() },
            ),
        )
        }
    }
    }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CallLayer(backStack: MutableList<NavKey>, onAskAsha: (() -> Unit)?, onMoneyInChat: (chatId: String) -> Unit) {
    val controller = koinInject<com.circaso.feature.calls.CallController>()
    val call by controller.state.collectAsState()
    val chatId = call?.chatId
    val chamas = koinInject<com.circaso.feature.chama.domain.ChamaRepository>()
    // Group wallets: a group call's contribute preset pays into the group's own wallet (CH7), not a treasurer (CH1).
    val groupWalletsOn by koinInject<com.circaso.core.flags.GroupWalletsFlag>().enabled.collectAsState()
    val treasurerChama by remember(chatId) { if (chatId != null) chamas.chama(chatId) else kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val groupMoney by remember(chatId, groupWalletsOn) { if (chatId != null && groupWalletsOn) chamas.money(chatId) else kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val chama = if (groupWalletsOn) groupMoney?.asChama() else treasurerChama
    // A group call's money is reloaded when the call opens, each time the live socket comes back and on a `chama`
    // resync (2026-11-09): a `money.updated` missed while the socket was down then heals in seconds, not never.
    val feed = koinInject<com.circaso.feature.chama.data.repository.ContributionFeed>()
    val realtime = koinInject<com.circaso.core.realtime.RealtimeClient>()
    val isGroupCall = call?.group == true
    LaunchedEffect(chatId, isGroupCall, groupWalletsOn) {
        if (chatId == null || !isGroupCall) return@LaunchedEffect
        val reload = suspend { if (groupWalletsOn) chamas.refreshMoney(chatId) else chamas.refresh(chatId) }
        kotlinx.coroutines.flow.merge(
            realtime.connected.let { c -> kotlinx.coroutines.flow.flow { c.collect { if (it) emit(Unit) } } },
            feed.resyncs,
        ).collect { reload() }
    }
    val profiles = koinInject<com.circaso.feature.profile.domain.ObserveProfileUseCase>()
    val me by remember(profiles) { profiles() }.collectAsState(null)
    val wallets = koinInject<com.circaso.feature.wallet.domain.WalletRepository>()
    val wallet by wallets.wallet.collectAsState(null)
    val messages = koinInject<com.circaso.feature.messaging.domain.MessagesRepository>()
    val thread by remember(chatId) { if (chatId != null) messages.thread(chatId) else kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(emptyList())
    val fiat = wallet?.fiat
    // Contributions landing on a group call (2026-11-09), held for the whole call so minimising keeps them: "on its way"
    // from `contribution.submitted`, the landing from the confirmed `money.updated`. Read with the latest people, rate
    // and my own id each time, never the ones the call started with.
    val celebrations = remember(call?.callId, chatId) { com.circaso.feature.calls.presentation.CallCelebrationsModel() }
    val latestCall by androidx.compose.runtime.rememberUpdatedState(call)
    val latestFiat by androidx.compose.runtime.rememberUpdatedState(wallet?.fiat)
    val latestMe by androidx.compose.runtime.rememberUpdatedState(me)
    LaunchedEffect(celebrations, isGroupCall) {
        if (!isGroupCall) return@LaunchedEffect
        feed.contributions.collect { g ->
            val now = latestCall ?: return@collect
            if (g.chatId != now.chatId) return@collect
            val c = com.circaso.feature.calls.presentation.celebrationOf(g, now.people, latestMe?.userId, latestFiat, now.myPhotoUrl)
            celebrations.receive(c, g.confirmed, visible = !now.minimised)
        }
    }
    // Money to me on a 1:1 call (2026-11-10): the receiver's splash, "Juma sent you KES 500". The payment's card reaches
    // the call's chat end to end, and the wallet's `payment.received` frame marks its transfer confirmed; only then does
    // it splash. What was already in the chat when the call went live is the baseline, never celebrated.
    val directLive = call?.let { !it.group && it.activeSince != null && it.phase != com.circaso.feature.calls.CallPhase.Ended } == true
    LaunchedEffect(celebrations, chatId, directLive) {
        val id = chatId ?: return@LaunchedEffect
        if (!directLive) return@LaunchedEffect
        val baseline = messages.thread(id).first().filterIsInstance<com.circaso.feature.messaging.domain.Message.Payment>().map { it.transferId }.toSet()
        // A card whose transfer this phone hasn't heard of yet is read once (W5), in case its frame was missed.
        val asked = mutableSetOf<String>()
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        messages.thread(id).flatMapLatest { list ->
            val now = latestCall
            val waiting = com.circaso.feature.calls.presentation.incomingOnCall(list, now?.people?.singleOrNull()?.userId, now?.activeSince, baseline)
            wallets.transfers(waiting.map { it.transferId }).map { list to it.associate { t -> t.transferId to t.state } }
        }.collect { (list, states) ->
            val now = latestCall ?: return@collect
            val peerId = now.people.singleOrNull()?.userId
            com.circaso.feature.calls.presentation.incomingOnCall(list, peerId, now.activeSince, baseline)
                .filter { it.transferId !in states && asked.add(it.transferId) }
                .forEach { p -> launch { wallets.loadTransfer(p.transferId) } }
            com.circaso.feature.calls.presentation.receivedOnCall(list, states, peerId, now.activeSince, baseline, celebrations.state.seen).forEach { p ->
                celebrations.receive(com.circaso.feature.calls.presentation.receivedCelebration(p, now.people, latestFiat), confirmed = true, visible = !now.minimised)
            }
        }
    }
    // One still "on its way" after a couple of minutes never confirmed (or its frame was lost; the reload has the truth).
    celebrations.state.pending.forEach { p ->
        androidx.compose.runtime.key(p.id) {
            LaunchedEffect(p.id) { kotlinx.coroutines.delay(com.circaso.feature.calls.presentation.CelebrationState.PendingMillis); celebrations.expire(p.id) }
        }
    }
    // H2/H3: the newest money said in this chat sits under the name ("You sent KES 500 · for lunch").
    // Only money sent during this call, and only once its card has gone: never last month's payment, never a failed one.
    // Money to me on a 1:1 call shows only once it has landed (the moment it splashes), in the splash's own words, so
    // its coin settles into a line that has just changed, never one that said it before the chain did.
    val since = call?.activeSince
    val landedForMe = celebrations.state.seen
    val money = thread.lastOrNull {
        it is com.circaso.feature.messaging.domain.Message.Payment && since != null && it.orderAt >= since &&
            it.state in setOf(com.circaso.feature.messaging.domain.DeliveryState.Sent, com.circaso.feature.messaging.domain.DeliveryState.Delivered, com.circaso.feature.messaging.domain.DeliveryState.Read) &&
            (it.outgoing || call?.group == true || it.transferId in landedForMe)
    }?.let { m ->
        val p = m as com.circaso.feature.messaging.domain.Message.Payment
        val said = if (p.outgoing) "You sent ${com.circaso.feature.chama.presentation.amountText(p.amountMinor, fiat)}"
            else com.circaso.feature.calls.presentation.receivedCelebration(p, call?.people.orEmpty(), fiat).text
        said + (p.note?.let { " · $it" } ?: "")
    }
    val c = call
    val peer = c?.people?.singleOrNull()
    // Money from a call goes where the chat's `#` would: the other person (never Circaso support, which takes none), or
    // a group that takes contributions (its active wallet, or a goal whose treasurer isn't me).
    val directMoney = c != null && !c.group && peer != null && peer.userId != com.circaso.feature.support.Support.UserId
    val groupMoneyOn = c != null && c.group && (if (groupWalletsOn) groupMoney?.walletActive == true else chama?.goal != null && treasurerChama?.isTreasurer != true)
    // The money sheet's view models, one set per call. They outlive the sheet and the call screen (minimised, hung up)
    // while a payment is being signed or sent, so a payment started on the call always finishes and its card always
    // reaches the chat; once a call is over and nothing is in flight, they are let go.
    val callMoney = remember { mutableMapOf<String, CallMoneyHolder>() }
    // Held under the call's chat (a call has one from its first ring, before the server gives it an id); a new call
    // in the same chat gets a fresh set, since the last one's is let go when it ends.
    val callIdNow = c?.chatId?.takeIf { c.phase != com.circaso.feature.calls.CallPhase.Ended }
    LaunchedEffect(callIdNow) {
        while (callMoney.keys.any { it != callIdNow }) {
            callMoney.entries.filter { (id, h) -> id != callIdNow && !h.inFlight() }.forEach { (id, h) -> h.store.clear(); callMoney.remove(id) }
            kotlinx.coroutines.delay(CallMoneyCheckMillis)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { callMoney.values.forEach { it.store.clear() }; callMoney.clear() } }
    CallOverlay(
        call = call,
        actions = com.circaso.feature.calls.presentation.CallActions(
            onAccept = controller::accept,
            onDecline = controller::decline,
            onEnd = controller::leave,
            onMute = controller::toggleMic,
            onSpeaker = controller::toggleSpeaker,
            onCamera = controller::toggleCamera,
            onFlip = controller::flipCamera,
            onMinimise = controller::minimise,
            onAddPeople = controller::ringOthers,
            onShareScreen = if (controller.canShareScreen) controller::toggleScreenShare else null,
            onAskAsha = onAskAsha,
            onMessage = { val id = chatId; controller.decline(); if (id != null) backStack.add(ChatRoute(id)) },
            // H1's "Send money instead": decline, then the chat with `#` in its composer (money starts in the chat).
            onSendMoneyInstead = if (directMoney && c != null) ({ controller.decline(); onMoneyInChat(c.chatId) }) else null,
            // H2/H3/H4: the call chat's composer on a sheet over the call, the same `#500` flow as the chat
            // (Pasaka, 2026-11-09). A group's goes into its wallet (GW9a2) or to the treasurer (D3), as its chat does.
            moneySheet = if (c != null && (directMoney || groupMoneyOn)) ({ open, onClose, onPaid ->
                CallMoneySheet(open, c.chatId, callMoney.getOrPut(c.chatId) { CallMoneyHolder() }, if (c.group) "Contribute to ${chama?.goal?.name ?: c.title}" else "Send to ${peer?.name?.substringBefore(' ') ?: c.title}",
                    // While this phone shares its screen, everyone on the call sees this sheet too: say so first.
                    if (c.screen) "You're sharing your screen. Everyone on the call can see this."
                    else if (c.group) "Everyone on the call sees it land" else "It goes in your chat with ${peer?.name?.substringBefore(' ') ?: c.title}", onClose, onPaid)
            }) else null,
        ),
        money = money,
        chama = chama,
        fiat = fiat,
        celebrations = celebrations,
        myUserId = me?.userId,
    )
}

/**
 * C3: the chat's in-line payment, so `#500` pays without leaving the chat and the chat's money card is the review. One
 * per chat in the store it is made in, reset after each payment. The thread and a call's money sheet both use it.
 */
@Composable
private fun chatQuickPay(chatId: String, thread: com.circaso.feature.messaging.presentation.ThreadUiState): com.circaso.feature.wallet.presentation.PayViewModel? {
    val peer = thread.chat?.peerId
    val goal = thread.chama?.goal
    val groupMoney = thread.groupMoney
    return when {
        peer != null -> koinViewModel<com.circaso.feature.wallet.presentation.PayViewModel>(
            key = "quickpay-$chatId",
            parameters = { parametersOf(PayIntent(userId = peer, chatId = chatId, displayName = thread.chat?.title, inChat = true)) },
        )
        // GW9a2: `#1000` in a group with a wallet is a contribution into it.
        groupMoney?.walletActive == true -> koinViewModel<com.circaso.feature.wallet.presentation.PayViewModel>(
            key = "quickpay-group-$chatId",
            parameters = { parametersOf(PayIntent(chatId = chatId, displayName = thread.chat?.title, groupId = chatId, goalName = goal?.name, inChat = true)) },
        )
        // D3, treasurer model (a server without group wallets): `#1000` is a contribution to the treasurer.
        goal != null && groupMoney == null && thread.canContribute -> koinViewModel<com.circaso.feature.wallet.presentation.PayViewModel>(
            key = "quickpay-chama-$chatId",
            parameters = { parametersOf(PayIntent(userId = goal.treasurerUserId, chatId = chatId, displayName = thread.memberName(goal.treasurerUserId), goalName = goal.name, inChat = true)) },
        )
        else -> null
    }
}

/** One call's money: the store its sheet's view models live in, and the payment it drives (to know when one is in flight). */
private class CallMoneyHolder : androidx.lifecycle.ViewModelStoreOwner {
    override val viewModelStore = androidx.lifecycle.ViewModelStore()
    val store get() = viewModelStore
    var pay: com.circaso.feature.wallet.presentation.PayViewModel? = null
    /** Being signed (Circle's PIN is up) or sent: letting go now would cut the payment off halfway. */
    fun inFlight(): Boolean = pay?.state?.value?.step.let {
        it == com.circaso.feature.wallet.presentation.PayStep.Signing || it == com.circaso.feature.wallet.presentation.PayStep.Sending
    }
}

/** How often an ended call's money is checked for a payment still in flight before it is let go. */
private const val CallMoneyCheckMillis = 1_000L

/**
 * The call's money sheet (2026-11-09): the call chat's composer and money, from view models of the call's own
 * ([holder]). They are made when the sheet first opens, and the thread is one with no conversation on screen (nothing
 * marked read, no notifications cleared, no presence or "typing…"); a payment started here never mixes with the chat
 * screen's own.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CallMoneySheet(
    open: Boolean,
    chatId: String,
    holder: CallMoneyHolder,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    onPaid: (com.circaso.feature.wallet.presentation.PayUiState) -> Unit,
) {
    var started by remember(holder) { androidx.compose.runtime.mutableStateOf(false) }
    if (open && !started) started = true
    if (!started) return
    // Shown a frame after it is first composed, so the sheet slides in (and the scrim fades) like every other sheet.
    var shown by remember(holder) { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(open) { shown = open }
    CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides holder) {
        val thread = koinViewModel<ThreadViewModel>(key = "call-money-$chatId", parameters = { parametersOf(chatId, false) })
        val threadState by thread.state.collectAsState()
        val pay = chatQuickPay(chatId, threadState)
        androidx.compose.runtime.SideEffect { holder.pay = pay }
        ChatMoneySheet(shown, thread, pay, title, subtitle, onClose, onPaid)
    }
}

/** The treasurer paying an approved request (design D5): a member, or an outside address with its name. */
/** The group wallet's actions (GW16): Add contributes, Payout asks the admins, a request's Pay leaves the group wallet. */
/**
 * One wallet's links ([walletId] null: the personal wallet): its payments, its assets and their statements open for
 * that wallet. Send, Send to a wallet and Networks are the personal wallet's (a group's own row replaces them).
 */
internal fun walletActions(walletId: String?, open: (Route) -> Unit) = WalletActions(
    onPay = { open(PayRoute()) },
    onSendToWallet = { open(SendToWalletRoute(walletId)) },
    onTransfer = { id -> open(TransferRoute(id)) },
    onActivity = { open(ActivityRoute(walletId)) },
    onNetworks = { open(NetworksRoute) },
    onAsset = { chain, symbol -> open(AssetActivityRoute(chain, symbol, walletId)) },
    onAssets = { chain -> open(NetworkAssetsRoute(chain, walletId)) },
    onNetworkActivity = { chain -> open(NetworkActivityRoute(chain, walletId)) },
)

internal fun groupWalletActions(chatId: String, state: com.circaso.feature.chama.presentation.GroupWalletUiState, open: (Route) -> Unit) =
    com.circaso.feature.chama.presentation.GroupWalletActions(
        onAdd = { open(PayRoute(groupId = chatId, chatId = chatId, displayName = state.chat?.title, goalName = state.money?.goal?.name)) },
        // 2026-11-06: the owner pays straight from the group wallet (with approvals on, this would be Request Payout).
        onPayout = { state.walletId?.let { open(if (com.circaso.core.flags.GroupPayoutApprovals.Enabled) PayRoute(chatId = chatId, fromWalletId = it) else PayRoute(fromWalletId = it)) } },
        onPayRequest = { r -> open(payRequestRoute(chatId, state.money?.goal?.name, r, state::memberName, fromWalletId = state.walletId)) },
        onOpenTransfer = { id -> open(TransferRoute(id)) },
        onTarget = { open(TargetRoute(chatId)) },
    )

/**
 * Paying an approved request (design D5): a member, or an outside address with its name. With group wallets it leaves
 * the group's wallet ([fromWalletId], GW9) and W4 carries the request; otherwise the treasurer pays it.
 */
private fun payRequestRoute(chatId: String, goalName: String?, r: ChamaRequest, nameOf: (String) -> String, fromWalletId: String? = null): PayRoute {
    val fiatFree = com.circaso.feature.wallet.domain.MinorUnits.toDisplay(r.amountMinor, 6).replace(",", "")
    return PayRoute(
        userId = r.payeeUserId, chatId = chatId, displayName = r.payeeUserId?.let(nameOf) ?: r.payeeName, amount = fiatFree,
        address = r.payeeAddress?.takeIf { r.payeeUserId == null }, goalName = goalName, requestId = r.requestId, fromWalletId = fromWalletId,
    )
}
