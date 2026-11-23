package com.circaso.feature.keybackup.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.core.sync.nowMillis
import com.circaso.feature.keybackup.data.BackupPromptMemory
import com.circaso.feature.keybackup.data.KeyBackupProblem
import com.circaso.feature.keybackup.data.KeyBackupRepository
import com.circaso.feature.keybackup.data.KeyBackupState
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.GroupNameState
import com.circaso.feature.profile.domain.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The one backup sheet that can show by itself (2026-11-23). */
enum class BackupSheet {
    /** The account has no backup yet: make the passkey and turn backup on, in one step. */
    SetUp,
    /** The account has a backup and this phone doesn't hold it (a new phone, a fresh install, signed in again). */
    Restore,
    /** This phone holds the backup, but it is locked with a passkey the account no longer uses. */
    Repair,
}

/** Where the sheet is. */
enum class BackupStep {
    Ask,
    /** A restore couldn't reach the passkey that locked the backup: another device, or a new backup. */
    PasskeyNotHere,
    /** "Start a new backup?" with what is lost, before anything is deleted. */
    ConfirmNew,
    Done,
}

data class BackupPromptUi(
    /** The sheet due (or under way); null when none is. */
    val sheet: BackupSheet? = null,
    val step: BackupStep = BackupStep.Ask,
    /** On screen now: a sheet is due and the screen under it is calm (Home's Chats, nothing else open). */
    val visible: Boolean = false,
    val online: Boolean = true,
    /** A passkey sheet or the server is answering. */
    val busy: Boolean = false,
    /** The person backed out of the passkey during a restore: quietly ask whether the passkey isn't on this phone. */
    val askNotHere: Boolean = false,
    val error: String? = null,
    val errorRef: String? = null,
    /** After a restore, groups the backup didn't have (their names come from a member's phone instead). */
    val leftAfterRestore: Int = 0,
    /** Done after starting a new backup, rather than restoring. */
    val startedNew: Boolean = false,
    /** Chats' quiet card: a backup is waiting to be restored on this phone and its sheet was put away. */
    val resumeRestore: Boolean = false,
    /** Groups whose names this phone can't read yet. */
    val unreadableGroups: Int = 0,
)

/**
 * The encrypted chat backup, set up from the word go (Pasaka, 2026-11-23): right after signing in, one sheet offers
 * what this phone needs, so nobody has to find it in Security.
 *
 * - **No backup on the account:** [BackupSheet.SetUp]. One button makes the account's passkey (one system sheet and a
 *   fingerprint or face) and turns backup on in the same step ([KeyBackupRepository.turnOn]); the PIN Vault then uses
 *   the same passkey ([com.circaso.core.platform.AccountPasskey]).
 * - **A backup this phone doesn't hold:** [BackupSheet.Restore], with the passkey that locked it. When that passkey isn't
 *   here the same sheet says so and offers another device or a new backup ([KeyBackupRepository.startNew], after a
 *   confirm); Security is never needed.
 * - **A backup under a passkey the account no longer uses:** healed by itself the next time the passkey answers (the
 *   vault's [KeyBackupRepository.onPrfSecret]); until then, [BackupSheet.Repair].
 *
 * Only one sheet at a time, only when the screen is calm ([onCalm]: Home's Chats with nothing else open, never over a
 * money flow, a call or a secret), and a moment after it got calm. "Not Now" keeps a sheet quiet for [QuietMillis]
 * on this phone; a restore put away stays on offer as a quiet card on Chats.
 */
class BackupPromptViewModel(
    private val backup: KeyBackupRepository,
    chats: ChatsRepository,
    private val profile: ProfileRepository,
    connection: ConnectionState,
    private val memory: BackupPromptMemory,
    private val userId: () -> String?,
    private val now: () -> Long = { nowMillis() },
) : ViewModel() {
    private data class Local(
        val supported: Boolean = false,
        /** The quiet times are read (a sheet waits for them, so a "Not Now" is never ignored at launch). */
        val loaded: Boolean = false,
        val quiet: Map<BackupSheet, Long> = emptyMap(),
        /** Closed this session; asked again on the next launch unless quiet. */
        val putAway: Set<BackupSheet> = emptySet(),
        /** Opened again from Chats' card: shown whatever the quiet time says. */
        val reopened: BackupSheet? = null,
        /** The sheet held while it is under way (past [BackupStep.Ask] or busy), whatever the backup's state does meanwhile. */
        val held: BackupSheet? = null,
        val step: BackupStep = BackupStep.Ask,
        val busy: Boolean = false,
        val askNotHere: Boolean = false,
        val error: String? = null,
        val errorRef: String? = null,
        val leftAfterRestore: Int = 0,
        val startedNew: Boolean = false,
        val calm: Boolean = false,
        /** Calm for [SettleMillis]: the screen has landed (Home just drew, a sheet just closed). */
        val settled: Boolean = false,
        val cardDismissed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private var settle: Job? = null

    val state: StateFlow<BackupPromptUi> = combine(
        local, backup.status, connection.status.map { it == ConnectionStatus.Online }.distinctUntilChanged(),
        chats.chats.map { list -> list.count { it.chat.kind == ChatKind.Group && it.chat.nameState != GroupNameState.Known } }.distinctUntilChanged(),
    ) { l, b, online, unreadable -> l.toUi(b, online, unreadable) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackupPromptUi())

    init {
        viewModelScope.launch {
            val supported = backup.supported()
            val person = userId()
            val quiet = if (person == null || !supported) emptyMap() else BackupSheet.entries.associateWith { runCatching { memory.quietUntil(person, it.name) }.getOrDefault(0L) }
            local.update { it.copy(supported = supported, loaded = true, quiet = quiet) }
        }
    }

    /** The sheet that is due by itself: what this phone needs, unless put away or quiet. */
    private fun due(l: Local, b: KeyBackupState): BackupSheet? {
        if (!l.supported || !l.loaded) return null
        val wanted = wanted(b) ?: return null
        if (l.reopened == wanted) return wanted
        if (wanted in l.putAway || (l.quiet[wanted] ?: 0L) > now()) return null
        return wanted
    }

    private fun Local.toUi(b: KeyBackupState, online: Boolean, unreadable: Int): BackupPromptUi {
        val underWay = step != BackupStep.Ask || busy
        val sheet = if (underWay) held else due(this, b)
        val visible = sheet != null && calm && settled
        return BackupPromptUi(
            sheet = sheet, step = if (sheet == null) BackupStep.Ask else step, visible = visible, online = online, busy = busy,
            askNotHere = askNotHere, error = error, errorRef = errorRef, leftAfterRestore = leftAfterRestore, startedNew = startedNew,
            resumeRestore = supported && wanted(b) == BackupSheet.Restore && sheet == null && !cardDismissed,
            unreadableGroups = unreadable,
        )
    }

    /**
     * Whether the screen under the sheet is calm: Home on Chats, on top, with no call, no secret input, no money step
     * and no other sheet. The sheet waits [SettleMillis] after it gets calm, and hides (keeping its place) when it isn't.
     */
    fun onCalm(calm: Boolean) {
        if (local.value.calm == calm) return
        settle?.cancel()
        local.update { it.copy(calm = calm, settled = false) }
        if (calm) settle = viewModelScope.launch { delay(SettleMillis); local.update { it.copy(settled = true) } }
    }

    /** The sheet's main button. */
    fun onPrimary() {
        val s = state.value
        val sheet = s.sheet ?: return
        when {
            s.step == BackupStep.ConfirmNew -> onStartNew()
            s.step == BackupStep.Done -> onClose()
            sheet == BackupSheet.Restore -> onRestore()
            else -> onTurnOn(sheet)
        }
    }

    private fun onTurnOn(sheet: BackupSheet) = act(sheet) {
        val (handle, name) = names()
        val problem = backup.turnOn(handle, name)
        if (problem == null) local.update { it.copy(step = BackupStep.Done) }
        problem
    }

    /** Restore, and "Use Another Device" (the same passkey sheet, where the other device's QR code is offered). */
    private fun onRestore() = act(BackupSheet.Restore) {
        when (val problem = backup.restore()) {
            null -> {
                val left = state.value.unreadableGroups
                local.update { it.copy(step = BackupStep.Done, leftAfterRestore = left, askNotHere = false) }
                null
            }
            KeyBackupProblem.PasskeyMissing -> { local.update { it.copy(step = BackupStep.PasskeyNotHere, askNotHere = false) }; null }
            // Backing out is a choice, but it is also all a phone without the passkey can do with the system sheet
            // (only "use another device" on offer, and iOS says no more than "cancelled"), so ask without claiming it.
            KeyBackupProblem.Cancelled -> { local.update { it.copy(askNotHere = it.step == BackupStep.Ask) }; null }
            // Turned off on another phone meanwhile: what's due now is setting one up.
            KeyBackupProblem.TurnedOff -> { backup.refresh(); local.update { it.copy(step = BackupStep.Ask, held = null) }; null }
            else -> problem
        }
    }

    /** "Passkey not on this phone?" after backing out of a restore. */
    fun onNotHere() = local.update { it.copy(step = BackupStep.PasskeyNotHere, held = BackupSheet.Restore, askNotHere = false, error = null, errorRef = null) }

    fun onStartNewRequested() = local.update { it.copy(step = BackupStep.ConfirmNew, held = BackupSheet.Restore, error = null, errorRef = null) }

    /** Cancel on the confirm: back to the choice. */
    fun onStartNewCancelled() = local.update { it.copy(step = BackupStep.PasskeyNotHere, error = null, errorRef = null) }

    private fun onStartNew() = act(BackupSheet.Restore) {
        val (handle, name) = names()
        when (val problem = backup.startNew(handle, name)) {
            null -> { local.update { it.copy(step = BackupStep.Done, startedNew = true) }; null }
            KeyBackupProblem.Cancelled -> null
            else -> problem
        }
    }

    /**
     * "Not Now", Close, a drag down or Back. Before anything is done the sheet stays quiet for [QuietMillis] (a restore
     * stays on offer on Chats); once done it simply closes.
     */
    fun onClose() {
        val s = state.value
        val sheet = s.sheet ?: return
        if (s.busy) return
        if (s.step != BackupStep.Done) {
            val until = now() + QuietMillis
            local.update { it.copy(quiet = it.quiet + (sheet to until)) }
            userId()?.let { person -> viewModelScope.launch { runCatching { memory.quiet(person, sheet.name, until) } } }
        }
        local.update { it.copy(putAway = it.putAway + sheet, reopened = null, held = null, step = BackupStep.Ask, askNotHere = false, error = null, errorRef = null, leftAfterRestore = 0, startedNew = false) }
    }

    /** Chats' card: the restore, now, in its sheet (offline the sheet opens and waits for the connection). */
    fun onResume() {
        local.update { it.copy(reopened = BackupSheet.Restore, putAway = it.putAway - BackupSheet.Restore, error = null, errorRef = null) }
        onRestore()
    }

    /** Chats' card "Not Now": gone for this session (the restore is still in Security). */
    fun onCardDismissed() = local.update { it.copy(cardDismissed = true) }

    private fun act(sheet: BackupSheet, block: suspend () -> KeyBackupProblem?) {
        if (local.value.busy || !state.value.online) return
        local.update { it.copy(held = sheet, busy = true, error = null, errorRef = null) }
        viewModelScope.launch {
            val problem = try { block() } finally { local.update { it.copy(busy = false) } }
            val words = problem?.let(::problemWords)
            local.update { it.copy(error = words?.first, errorRef = words?.second) }
            if (local.value.step == BackupStep.Done) closeSoon()
        }
    }

    /** A short success, then the sheet closes by itself; longer when there is more to read. */
    private fun closeSoon() = viewModelScope.launch {
        delay(if (local.value.leftAfterRestore > 0) DoneLongMillis else DoneMillis)
        if (local.value.step == BackupStep.Done) onClose()
    }

    private suspend fun names(): Pair<String, String> {
        val me = profile.profile.first()
        return (me?.handle?.let { "@$it" } ?: me?.displayName ?: "Circaso") to "${me?.displayName ?: "Circaso"} · Circaso key"
    }

    companion object {
        /** How long "Not Now" keeps a sheet from asking again by itself. */
        const val QuietMillis = 7L * 24 * 60 * 60 * 1000
        /** How long the screen is calm before a sheet rises: Home has drawn, nothing is mid-animation. */
        const val SettleMillis = 1_200L
        const val DoneMillis = 2_400L
        const val DoneLongMillis = 6_000L

        /** What this phone needs from the backup, whatever the person said before. */
        fun wanted(b: KeyBackupState): BackupSheet? = when {
            b.onServer == false && !b.onPhone -> BackupSheet.SetUp
            b.onServer == true && !b.onPhone -> BackupSheet.Restore
            b.onPhone && b.needsRepair -> BackupSheet.Repair
            else -> null
        }
    }
}
