package com.circaso.core.navigation

import com.circaso.feature.status.presentation.SpacesOverlay

import androidx.compose.foundation.layout.Box
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
import com.circaso.feature.assistant.presentation.VoiceScreen
import com.circaso.feature.wallet.presentation.ActivityScreen
import com.circaso.feature.wallet.presentation.HoldBanner
import com.circaso.feature.home.presentation.NotificationsScreen
import com.circaso.feature.wallet.presentation.NetworksScreen
import com.circaso.feature.wallet.presentation.PayScreen
import com.circaso.feature.wallet.presentation.PayViewModel
import com.circaso.feature.wallet.presentation.SendToWalletScreen
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
    val backStack = rememberNavBackStack(SavedStateConfiguration { serializersModule = routeSerializers }, SplashRoute)

    val root: Route = when (state) {
        AuthState.Restoring -> SplashRoute
        AuthState.SignedOut -> WelcomeRoute
        AuthState.Onboarding -> if (stored.onboardingStep == "REGISTER_DEVICE") RegisteringRoute else PhoneRoute
        AuthState.Active -> HomeRoute
    }
    val preview = remember { launchValue("-preview") }
    LaunchedEffect(root, forceUpdate) {
        val target: Route = if (forceUpdate) ForceUpdateRoute else if (preview != null) PreviewRoute(preview) else root
        if (backStack.firstOrNull() != target) {
            backStack.clear()
            backStack.add(target)
        }
    }

    fun pop() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    // From the list in a split, a chat replaces what the detail pane shows (iPad Messages): Home stays the only
    // thing under it, so Back from the chat returns to the empty pane, not through every chat opened before.
    fun openChatFromList(id: String) {
        if (twoPaneNow) while (backStack.size > 1 && backStack.last() != HomeRoute) backStack.removeAt(backStack.lastIndex)
        backStack.add(ChatRoute(id))
    }
    val calls = koinInject<com.circaso.feature.calls.CallController>()
    // Typing /asha in any text field opens Asha, but only with a full session (she needs the server) and never twice.
    val asha = if (state == AuthState.Active) AshaLauncher { if (backStack.lastOrNull() != AshaRoute) backStack.add(AshaRoute) } else null
    val announcements: SyncAnnouncements = koinInject()
    val toast = rememberToastState()
    LaunchedEffect(Unit) {
        announcements.sentAfterOffline.collect { n -> toast.show("Back online · $n ${if (n == 1) "change" else "changes"} sent") }
    }
    CompositionLocalProvider(LocalAshaLauncher provides asha) {
    Box {
    val twoPane = com.circaso.designsystem.adaptive.LocalWindowLayout.current.twoPane
    val still = com.circaso.designsystem.theme.CircasoTheme.reduceMotion
    // Pop or push, decided once per change of depth (not on every recomposition mid-animation).
    val lastDepth = remember { IntArray(1) { backStack.size } }
    val popping = remember(backStack.size) { (backStack.size < lastDepth[0]).also { lastDepth[0] = backStack.size } }
    CompositionLocalProvider(LocalNavPopping provides popping) {
    NavDisplay(
        backStack = backStack,
        onBack = ::pop,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator(), rememberDimNavEntryDecorator()),
        // On a wide window Home is a list beside whatever it opened (docs/adaptive-layout-plan.md).
        sceneStrategies = listOf(remember(twoPane) { ListDetailSceneStrategy(twoPane) }),
        // The same screen on top before and after (a rotation or resize into or out of the split) only reflows.
        transitionSpec = { if (sameTop()) EnterTransition.None togetherWith ExitTransition.None else pushTransition(still) },
        popTransitionSpec = { if (sameTop()) EnterTransition.None togetherWith ExitTransition.None else popTransition(still) },
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
                val spacesViewModel: com.circaso.feature.status.presentation.SpacesViewModel = koinViewModel()
                val spacesActions = com.circaso.feature.status.presentation.SpacesActions(
                    onOpenRing = { id -> backStack.add(MomentViewerRoute(id)) },
                    onOpenMine = { backStack.add(MomentViewerRoute(null)) },
                    onCompose = { backStack.add(MomentComposerRoute) },
                    onFindPeople = { backStack.add(ContactsRoute) },
                )
                HomeScreen(
                    chats = { bottomPadding -> ChatsTab(chatsViewModel, bottomPadding, onAsha = { backStack.add(AshaRoute) }, onVoice = { backStack.add(AshaRoute); backStack.add(VoiceRoute) }, onNewChat = { backStack.add(NewChatRoute) }, selectedChatId = if (twoPane) backStack.filterIsInstance<ChatRoute>().lastOrNull()?.chatId else null) { id -> openChatFromList(id) } },
                    chatsActions = {},
                    chatsBanner = {
                        val s by chatsViewModel.state.collectAsState()
                        val w by walletViewModel.state.collectAsState()
                        androidx.compose.foundation.layout.Column {
                            HoldBanner(w.wallet?.holdUntil, onNotMe = { backStack.add(SecurityRoute) })
                            ChatsBanner(s)
                        }
                    },
                    spaces = { bottomPadding -> com.circaso.feature.status.presentation.SpacesTab(spacesViewModel, bottomPadding, spacesActions) },
                    spacesOverlay = { SpacesOverlay(spacesViewModel, spacesActions) },
                    spacesBanner = { com.circaso.feature.status.presentation.SpacesBanner() },
                    wallet = { bottomPadding ->
                        WalletTab(
                            walletViewModel, bottomPadding,
                            WalletActions(
                                onPay = { backStack.add(PayRoute()) },
                                onSendToWallet = { backStack.add(SendToWalletRoute) },
                                onTransfer = { id -> backStack.add(TransferRoute(id)) },
                                onActivity = { backStack.add(ActivityRoute) },
                                onNetworks = { backStack.add(NetworksRoute) },
                                onAsset = { chain, symbol -> backStack.add(AssetActivityRoute(chain, symbol)) },
                                onAssets = { chain -> backStack.add(NetworkAssetsRoute(chain)) },
                                onNetworkActivity = { chain -> backStack.add(NetworkActivityRoute(chain)) },
                            ),
                        )
                    },
                    walletActions = { WalletTitleActions(walletViewModel) },
                    chatsOverlay = { ChatsOverlay(chatsViewModel, onAsha = { backStack.add(AshaRoute) }, onVoice = { backStack.add(AshaRoute); backStack.add(VoiceRoute) }) },
                    walletOverlay = {
                        WalletOverlay(
                            walletViewModel,
                            WalletActions(
                                onPay = { backStack.add(PayRoute()) },
                                onSendToWallet = { backStack.add(SendToWalletRoute) },
                                onTransfer = { id -> backStack.add(TransferRoute(id)) },
                                onActivity = { backStack.add(ActivityRoute) },
                                onNetworks = { backStack.add(NetworksRoute) },
                                onAsset = { chain, symbol -> backStack.add(AssetActivityRoute(chain, symbol)) },
                                onAssets = { chain -> backStack.add(NetworkAssetsRoute(chain)) },
                                onNetworkActivity = { chain -> backStack.add(NetworkActivityRoute(chain)) },
                            ),
                            biometrics = koinViewModel(),
                        )
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
                            onAsha = { backStack.add(AshaRoute) },
                            onSecretMenu = { backStack.add(GalleryRoute) },
                            onNeedsAttention = { backStack.add(NeedsAttentionRoute) },
                            onCurrency = { backStack.add(CurrencyRoute) },
                            onAshaVoice = { backStack.add(AshaVoiceRoute) },
                            onMoneySound = youViewModel::onMoneySound,
                            onMomentsPrivacy = { backStack.add(MomentsPrivacyRoute) },
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
            entry<MomentComposerRoute> { com.circaso.feature.status.presentation.MomentComposerScreen(koinViewModel(), onClose = ::pop, onAudience = { backStack.add(MomentsPrivacyRoute) }) }
            entry<MomentViewerRoute> { key -> com.circaso.feature.status.presentation.MomentViewerScreen(koinViewModel(key = "moments-${key.posterId}", parameters = { parametersOf(key.posterId) }), onClose = ::pop) }
            entry<MomentsPrivacyRoute> { com.circaso.feature.status.presentation.MomentsPrivacyScreen(koinViewModel(), onBack = ::pop, onFindPeople = { backStack.add(ContactsRoute) }) }
            entry<BlockedRoute> { BlockedScreen(koinViewModel(), onBack = ::pop) }
            entry<SecurityRoute> {
                SecurityScreen(
                    koinViewModel(), onBack = ::pop, onActivity = { backStack.add(SecurityLogRoute) }, onSendMoney = { backStack.add(PayRoute()) },
                    payments = { BiometricApprovalSection(koinViewModel()) },
                )
            }
            entry<NotificationsRoute> { NotificationsScreen(koinViewModel(), onBack = ::pop, onOpenTransfer = { id -> backStack.add(TransferRoute(id)) }) }
            entry<SecurityLogRoute> { SecurityLogScreen(koinViewModel(), onBack = ::pop) }
            entry<AshaRoute> {
                // The mic opens the live conversation where the phone can encode Opus; elsewhere it records a clip (A3).
                val live = koinInject<LiveMicrophone>().supported
                AshaScreen(koinViewModel(), onBack = ::pop, onVoice = if (live) ({ if (backStack.lastOrNull() != VoiceRoute) backStack.add(VoiceRoute) }) else null)
            }
            entry<VoiceRoute>(metadata = Panes.FullScreen) { VoiceScreen(koinViewModel(), onBack = ::pop) }
            entry<ChatRoute> { route ->
                val threadViewModel = koinViewModel<ThreadViewModel>(key = route.chatId, parameters = { parametersOf(route.chatId) })
                val thread by threadViewModel.state.collectAsState()
                val peer = thread.chat?.peerId
                val goal = thread.chama?.goal
                val callNow by calls.state.collectAsState()
                ThreadScreen(
                    threadViewModel,
                    onBack = ::pop,
                    onInfo = { backStack.add(ChatInfoRoute(route.chatId)) },
                    onAsha = { if (backStack.lastOrNull() != AshaRoute) backStack.add(AshaRoute) },
                    onPay = if (peer != null) ({ amount -> backStack.add(PayRoute(userId = peer, chatId = route.chatId, displayName = thread.chat?.title, amount = amount)) }) else null,
                    onOpenTransfer = { id -> backStack.add(TransferRoute(id)) },
                    onOpenGoal = { backStack.add(ChamaRoute(route.chatId)) },
                    // D3: a contribution is a payment to the treasurer from this chat; D5: the treasurer pays the approved request.
                    onContribute = if (goal != null) ({ amount -> backStack.add(PayRoute(userId = goal.treasurerUserId, chatId = route.chatId, displayName = thread.memberName(goal.treasurerUserId), amount = amount, goalName = goal.name)) }) else null,
                    onPayRequest = { r -> backStack.add(payRequestRoute(route.chatId, goal?.name, r, thread::memberName)) },
                    onCall = if (calls.available) ({ video -> calls.start(route.chatId, video) }) else null,
                    ongoingCall = callNow?.takeIf { it.chatId == route.chatId && it.minimised && it.phase != com.circaso.feature.calls.CallPhase.Ended }?.let { if (it.video || it.camera) "Ongoing video call" else "Ongoing voice call" },
                    onReturnToCall = { calls.minimise(false) },
                    focusLocalId = route.focusLocalId,
                    onOpenLocation = { m -> backStack.add(LocationMapRoute(route.chatId, m.localId)) },
                    // C3: `#500` pays the other person without leaving the chat; one per chat, reset after each payment.
                    quickPay = peer?.let { id ->
                        koinViewModel<com.circaso.feature.wallet.presentation.PayViewModel>(
                            key = "quickpay-${route.chatId}",
                            parameters = { parametersOf(com.circaso.feature.wallet.presentation.PayIntent(userId = id, chatId = route.chatId, displayName = thread.chat?.title)) },
                        )
                    },
                    onCallSupport = if (calls.available) ({ calls.start(route.chatId, false) }) else null,
                )
            }
            entry<NewChatRoute> {
                NewChatScreen(koinViewModel(), onBack = ::pop, onOpened = { id -> pop(); openChatFromList(id) }, onNewGroup = { backStack.add(NewGroupRoute) }, onInvite = { backStack.add(ContactsRoute) })
            }
            entry<NewGroupRoute> { NewGroupScreen(koinViewModel(), onBack = ::pop, onCreated = { id -> pop(); pop(); openChatFromList(id) }) }
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
                    infoViewModel, onBack = ::pop, onLeft = { pop(); pop() }, onGroupWallet = { backStack.add(ChamaRoute(route.chatId)) },
                    onCall = if (calls.available) ({ video -> calls.start(route.chatId, video) }) else null,
                    onPay = if (peer != null) ({ backStack.add(PayRoute(userId = peer, chatId = route.chatId, displayName = info.chat?.title)) }) else null,
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
                    koinViewModel<PayViewModel>(key = "pay-${route.userId}-${route.chatId}-${route.requestId}", parameters = { parametersOf(PayIntent(route.userId, route.chatId, route.displayName, route.amount, route.address, route.goalName, route.requestId)) }),
                    onBack = ::pop,
                    onOpenTransfer = { id -> backStack.add(TransferRoute(id)) },
                    onOpenChat = if (route.chatId == null) ({ id -> pop(); openChatFromList(id) }) else null,
                )
            }
            entry<SendToWalletRoute>(metadata = Panes.FullScreen) { SendToWalletScreen(koinViewModel(), onBack = ::pop, onOpenTransfer = { id -> pop(); backStack.add(TransferRoute(id)) }) }
            entry<TransferRoute> { route -> TransferScreen(koinViewModel<TransferViewModel>(key = route.transferId, parameters = { parametersOf(route.transferId) }), onBack = ::pop) }
            entry<NetworksRoute> { NetworksScreen(koinViewModel(), onBack = ::pop) }
            entry<ActivityRoute> { ActivityScreen(koinViewModel(parameters = { parametersOf(null, null) }), onBack = ::pop, onOpen = { id -> backStack.add(TransferRoute(id)) }) }
            entry<AshaVoiceRoute> { com.circaso.feature.assistant.presentation.AshaVoiceScreen(koinViewModel(), onBack = ::pop) }
            entry<CurrencyRoute> { com.circaso.feature.wallet.presentation.CurrencyScreen(koinViewModel(), onBack = ::pop) }
            entry<AssetActivityRoute> { route ->
                ActivityScreen(
                    koinViewModel(key = route.blockchain + route.symbol, parameters = { parametersOf(route.blockchain, route.symbol) }),
                    onBack = ::pop, onOpen = { id -> backStack.add(TransferRoute(id)) },
                )
            }
            entry<NetworkActivityRoute> { route ->
                ActivityScreen(
                    koinViewModel(key = "activity-" + route.blockchain, parameters = { parametersOf(route.blockchain, null) }),
                    onBack = ::pop, onOpen = { id -> backStack.add(TransferRoute(id)) },
                )
            }
            entry<NetworkAssetsRoute> { route ->
                com.circaso.feature.wallet.presentation.NetworkAssetsScreen(
                    koinViewModel(key = "assets-" + route.blockchain, parameters = { parametersOf(route.blockchain) }),
                    onBack = ::pop, onAsset = { chain, symbol -> backStack.add(AssetActivityRoute(chain, symbol)) },
                )
            }
            entry<PreviewRoute> { route -> PreviewScreens(route.name) }
            entry<GalleryRoute> { home() }
            entry<ForceUpdateRoute> { ForceUpdateScreen() }
        },
    )
    }
    ToastHost(toast, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
    // Calls (designs H1–H5) live above every screen: one can ring anywhere, and it keeps going while money is sent from it.
    if (state == AuthState.Active) CallLayer(backStack)
    }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CallLayer(backStack: MutableList<NavKey>) {
    val controller = koinInject<com.circaso.feature.calls.CallController>()
    val call by controller.state.collectAsState()
    val chatId = call?.chatId
    val chamas = koinInject<com.circaso.feature.chama.domain.ChamaRepository>()
    val chama by remember(chatId) { if (chatId != null) chamas.chama(chatId) else kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val wallet by koinInject<com.circaso.feature.wallet.domain.WalletRepository>().wallet.collectAsState(null)
    val messages = koinInject<com.circaso.feature.messaging.domain.MessagesRepository>()
    val thread by remember(chatId) { if (chatId != null) messages.thread(chatId) else kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(emptyList())
    val fiat = wallet?.fiat
    // H2/H3: the newest money said in this chat sits under the name ("You sent KES 500 · for lunch").
    // Only money sent during this call, and only once its card has gone: never last month's payment, never a failed one.
    val since = call?.activeSince
    val money = thread.lastOrNull {
        it is com.circaso.feature.messaging.domain.Message.Payment && since != null && it.orderAt >= since &&
            it.state in setOf(com.circaso.feature.messaging.domain.DeliveryState.Sent, com.circaso.feature.messaging.domain.DeliveryState.Delivered, com.circaso.feature.messaging.domain.DeliveryState.Read)
    }?.let { m ->
        val p = m as com.circaso.feature.messaging.domain.Message.Payment
        val amount = com.circaso.feature.chama.presentation.amountText(p.amountMinor, fiat)
        (if (p.outgoing) "You sent $amount" else "${p.senderName.substringBefore(' ')} sent you $amount") + (p.note?.let { " · $it" } ?: "")
    }
    val c = call
    val peer = c?.people?.singleOrNull()
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
            onMessage = { val id = chatId; controller.decline(); if (id != null) backStack.add(ChatRoute(id)) },
            // H1's "Send money instead" declines the call first; mid-call (H2/H3) the call stays on, minimised.
            onSendMoney = if (c != null && !c.group && peer != null) ({
                if (c.phase == com.circaso.feature.calls.CallPhase.Incoming) controller.decline() else controller.minimise(true)
                backStack.add(PayRoute(userId = peer.userId, chatId = c.chatId, displayName = peer.name))
            }) else null,
            onContribute = chama?.goal?.let { g -> { amount: String? -> controller.minimise(true); backStack.add(PayRoute(userId = g.treasurerUserId, chatId = g.chatId, displayName = c?.people?.firstOrNull { it.userId == g.treasurerUserId }?.name, amount = amount, goalName = g.name)) } },
        ),
        money = money,
        chama = chama,
        fiat = fiat,
    )
}

/** The treasurer paying an approved request (design D5): a member, or an outside address with its name. */
private fun payRequestRoute(chatId: String, goalName: String?, r: ChamaRequest, nameOf: (String) -> String): PayRoute {
    val fiatFree = com.circaso.feature.wallet.domain.MinorUnits.toDisplay(r.amountMinor, 6).replace(",", "")
    return PayRoute(
        userId = r.payeeUserId, chatId = chatId, displayName = r.payeeUserId?.let(nameOf) ?: r.payeeName, amount = fiatFree,
        address = r.payeeAddress?.takeIf { r.payeeUserId == null }, goalName = goalName, requestId = r.requestId,
    )
}
