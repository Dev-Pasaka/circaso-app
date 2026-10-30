package com.circaso.feature.support.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.AshaMark
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.account.domain.RequestRecoveryUseCase
import com.circaso.feature.support.Support
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LostAccessUiState(
    val phone: String = "",
    val email: String = "",
    val note: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** The case number once it is open (the backend's recovery request id, shortened). */
    val caseNumber: String? = null,
) {
    val emailOk get() = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    val canSend get() = !busy && phone.isNotBlank() && emailOk
}

/**
 * Design SP06, where the need really comes up: the number belongs to an account the person can no longer sign in to
 * (they lost the Google or Apple login). It opens a support case (guide I15); the team checks it's them and writes back
 * by email. No chat: without the old account there is nothing to encrypt a chat to.
 */
class LostAccessViewModel(phone: String?, private val request: RequestRecoveryUseCase) : ViewModel() {
    private val _state = MutableStateFlow(LostAccessUiState(phone = phone.orEmpty()))
    val state: StateFlow<LostAccessUiState> = _state.asStateFlow()

    fun onPhone(v: String) = _state.update { it.copy(phone = v.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20), error = null) }
    fun onEmail(v: String) = _state.update { it.copy(email = v.trim().take(120), error = null) }
    fun onNote(v: String) = _state.update { it.copy(note = v.take(500)) }

    fun onSend() {
        val s = _state.value
        if (!s.canSend) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val phone = com.circaso.feature.auth.domain.PhoneNumber.toE164(s.phone) ?: s.phone.filterNot { it == ' ' }
            when (val r = request(phone, s.email.trim(), s.note.trim().takeIf { it.isNotEmpty() })) {
                is AppResult.Success -> _state.update { it.copy(busy = false, caseNumber = caseNumber(r.value.requestId)) }
                is AppResult.Failure -> _state.update { it.copy(busy = false, error = ErrorMessages.forError(r.error)) }
            }
        }
    }
}

/**
 * "CIR-1A2B-3C4D" from the request id's last 8 characters, grouped so it can be read out; the team finds the case by
 * that suffix of its id (UUIDv7 ids end in random bits, so 8 characters don't collide in practice).
 */
internal fun caseNumber(requestId: String): String {
    val tail = requestId.filter { it.isLetterOrDigit() }.takeLast(8).uppercase()
    return "CIR-" + tail.take(4) + "-" + tail.drop(4)
}

@Composable
fun LostAccessScreen(viewModel: LostAccessViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LostAccessContent(state, onBack, viewModel::onPhone, viewModel::onEmail, viewModel::onNote, viewModel::onSend)
}

@Composable
fun LostAccessContent(state: LostAccessUiState, onBack: () -> Unit, onPhone: (String) -> Unit, onEmail: (String) -> Unit, onNote: (String) -> Unit, onSend: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(topBar = { CircasoNavBar(title = "", onBack = onBack) }) { padding ->
        val case = state.caseNumber
        if (case != null) {
            Column(Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s6).padding(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically)) {
                CircasoIconImage(CircasoIcon.Check, CircasoTheme.colors.action, Modifier.size(CircasoSizes.avatarLg))
                BasicText("We're on it", style = t.title1.copy(color = c.ink, textAlign = TextAlign.Center), modifier = Modifier.semantics { heading() })
                BasicText("Case $case is open. Someone from the team will check it's you and email ${state.email.trim()}. That's usually within a day.", style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                BasicText(Support.NeverAsk, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                Spacer(Modifier.size(CircasoSpacing.s2))
                CircasoButton("Done", onBack, size = ButtonSize.Large, block = true)
            }
            return@CircasoScaffold
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), modifier = Modifier.padding(top = CircasoSpacing.s2)) {
                BasicText("Get help without signing in", style = t.title1.copy(color = c.ink), modifier = Modifier.semantics { heading() })
                BasicText("Tell us the number on your Circaso account and where we can reach you. A person checks it's you, then helps you back in.", style = t.body.copy(color = c.inkSecondary))
            }
            CircasoTextField(state.phone, onPhone, label = "Phone number", placeholder = "+254 7…", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            CircasoTextField(state.email, onEmail, label = "Email for our reply", placeholder = "you@example.com", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), error = if (state.email.isNotEmpty() && !state.emailOk) "Check the email address." else null)
            CircasoTextField(state.note, onNote, label = "What happened (optional)", placeholder = "I lost the Google account I signed in with", singleLine = false)
            state.error?.let { BasicText(it, style = t.footnote.copy(color = c.critical)) }
            Row(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface).padding(CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                CircasoIconImage(CircasoIcon.Warning, c.warning, Modifier.size(20.dp))
                BasicText(Support.NeverAsk, style = t.footnote.copy(color = c.ink))
            }
            CircasoButton("Send to Support", onSend, size = ButtonSize.Large, block = true, enabled = state.canSend, busy = state.busy)
            CircasoButton("Back", onBack, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
        }
    }
}
