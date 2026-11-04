package com.circaso.feature.assistant.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.platform.AudioPlayer
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.assistant.data.remote.AshaRemote
import com.circaso.feature.assistant.data.remote.VoiceDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AshaVoiceUiState(
    val voices: List<VoiceDto> = emptyList(),
    val chosen: String? = null,
    val previewing: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** The voice in use: the person's choice, else the server's default. */
    val inUse get() = chosen ?: voices.firstOrNull { it.default }?.id
}

/**
 * You → Asha's voice: the voices the server offers (A12), each with a sample to hear first (A13, free of the
 * allowance). The choice is kept on this phone and sent with every voice chat and read-aloud.
 */
class AshaVoiceViewModel(private val remote: AshaRemote, private val prefs: AppPreferences, private val player: AudioPlayer) : ViewModel() {
    private val local = MutableStateFlow(AshaVoiceUiState())
    val state: StateFlow<AshaVoiceUiState> = combine(local, prefs.ashaVoice) { ui, chosen -> ui.copy(chosen = chosen) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AshaVoiceUiState())
    private var preview: Job? = null

    init {
        viewModelScope.launch {
            when (val r = remote.voices()) {
                is AppResult.Success -> {
                    local.update { it.copy(voices = r.value.voices, loading = false) }
                    // The mascot's look follows the voice in use (asha-next.md part B).
                    prefs.setAshaVoiceGender(com.circaso.feature.assistant.data.repository.AshaLookRepository.genderInUse(r.value, prefs.ashaVoice.first()))
                }
                is AppResult.Failure -> local.update { it.copy(loading = false, error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    fun onChoose(voice: VoiceDto) {
        viewModelScope.launch { prefs.setAshaVoice(if (voice.default) null else voice.id); prefs.setAshaVoiceGender(voice.gender) }
        onPreview(voice)
    }

    /** Plays the voice's sample; tapping the one playing stops it. */
    fun onPreview(voice: VoiceDto) {
        val again = local.value.previewing == voice.id
        preview?.cancel()
        player.stop()
        if (again) { local.update { it.copy(previewing = null) }; return }
        local.update { it.copy(previewing = voice.id, error = null) }
        preview = viewModelScope.launch {
            try {
                when (val clip = remote.voiceSample(voice.id)) {
                    is AppResult.Success -> player.play(clip.value, "audio/mpeg")
                    is AppResult.Failure -> local.update { it.copy(error = ErrorMessages.forError(clip.error)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(error = "Couldn't play that sample. Try again.") }
            } finally {
                local.update { if (it.previewing == voice.id) it.copy(previewing = null) else it }
            }
        }
    }

    override fun onCleared() { player.stop() }
}

@Composable
fun AshaVoiceScreen(viewModel: AshaVoiceViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    AshaVoiceContent(state, viewModel::onChoose, viewModel::onPreview, onBack)
}

@Composable
fun AshaVoiceContent(state: AshaVoiceUiState, onChoose: (VoiceDto) -> Unit, onPreview: (VoiceDto) -> Unit, onBack: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(grouped = true, topBar = { CircasoNavBar(title = "Asha's voice", onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s3, bottom = padding.calculateBottomPadding() + CircasoSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            item {
                BasicText(
                    "How Asha sounds in voice chats and when she reads an answer aloud. Tap a voice to hear it.",
                    style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            when {
                state.loading -> item { BasicText("Loading voices…", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(4.dp)) }
                state.voices.isEmpty() -> item { BasicText(state.error ?: "No voices to choose from right now.", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(4.dp)) }
                else -> item {
                    Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface)) {
                        state.voices.forEachIndexed { i, voice ->
                            if (i > 0) RowDivider(Modifier.padding(start = 64.dp))
                            VoiceRow(voice, selected = voice.id == state.inUse, playing = voice.id == state.previewing, onChoose = { onChoose(voice) }, onPreview = { onPreview(voice) })
                        }
                    }
                }
            }
            state.error?.takeIf { state.voices.isNotEmpty() }?.let { e -> item { BasicText(e, style = t.footnote.copy(color = c.critical), modifier = Modifier.padding(horizontal = 4.dp)) } }
        }
    }
}

@Composable
private fun VoiceRow(voice: VoiceDto, selected: Boolean, playing: Boolean, onChoose: () -> Unit, onPreview: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onChoose)
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        // The selection marker, filled for the voice in use.
        Box(
            Modifier.size(22.dp).clip(CircasoShapes.circle)
                .then(if (selected) Modifier.background(c.action) else Modifier.border(1.5.dp, c.inkTertiary, CircasoShapes.circle)),
            contentAlignment = Alignment.Center,
        ) { if (selected) CircasoIconImage(CircasoIcon.Check, c.bg, Modifier.size(14.dp)) }
        Column(Modifier.weight(1f)) {
            BasicText(voice.name + if (voice.default) " · default" else "", style = t.body.copy(color = c.ink))
            BasicText(listOfNotNull(voice.character, voice.gender?.replaceFirstChar { it.uppercase() }).joinToString(" · "), style = t.footnote.copy(color = c.inkSecondary))
        }
        CircasoIconButton(if (playing) CircasoIcon.Stop else CircasoIcon.Play, if (playing) "Stop the sample" else "Hear ${voice.name}", onPreview)
    }
}
