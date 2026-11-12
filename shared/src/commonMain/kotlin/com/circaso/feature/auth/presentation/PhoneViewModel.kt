package com.circaso.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.session.AuthManager
import com.circaso.feature.auth.domain.Countries
import com.circaso.feature.auth.domain.Country
import com.circaso.feature.auth.domain.PhoneNumber
import com.circaso.feature.auth.domain.PhoneVerification
import com.circaso.feature.auth.domain.StartPhoneVerificationUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Design ID-04 (add your number) and ID-07 (number already registered). */
class PhoneViewModel(
    private val start: StartPhoneVerificationUseCase,
    private val auth: AuthManager,
) : ViewModel() {
    data class UiState(
        /** The digits after the calling code; or, while a "+code" is still being typed, "+" and what's typed so far. */
        val input: String = "",
        /** The country the number is in: Kenya unless the person picks another (2026-11-12). */
        val country: Country = Countries.Kenya,
        /** The country picker sheet is open. */
        val picking: Boolean = false,
        /** What's typed in the picker's search. */
        val query: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        /** True after the server says this number belongs to another account (ID-07). */
        val numberTaken: Boolean = false,
        /** The account's masked number when a known account is verifying on a new phone. */
        val accountNumberHint: String? = null,
    ) {
        /**
         * The picker's list for [query]: Kenya, then the chosen country if it's another, then A to Z; or the matches.
         */
        val countries: List<Country> get() {
            if (query.isNotBlank()) return Countries.search(query)
            val top = listOf(Countries.Kenya, country).distinct()
            return top + Countries.all.filter { it !in top }
        }

        /** The number as the server will see it once complete, for "Lost access" before a code is sent. */
        val typed: String get() = if (input.startsWith("+")) input else PhoneNumber.toE164(input, country, pasted = false) ?: "+${country.dialCode}$input"
    }

    sealed interface Event {
        data class CodeSent(val phone: String, val verification: PhoneVerification) : Event
    }

    private val _state = MutableStateFlow(
        UiState(accountNumberHint = auth.stored.value.maskedPhone.takeIf { auth.stored.value.onboardingStep == "REVERIFY_PHONE" }),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val events = Channel<Event>(Channel.BUFFERED)
    val eventFlow: Flow<Event> = events.receiveAsFlow()

    fun onInput(text: String) {
        _state.update { s ->
            if (s.busy) return@update s
            val trimmed = text.trim()
            val digits = trimmed.filter { it.isDigit() }
            // An international number, pasted whole or typed key by key after "+" (or "00"): it picks its own country
            // once the calling code is certain (+44 7700… is the UK; "+1" waits, since +1268 is Antigua).
            val international = when {
                trimmed.startsWith("+") -> digits
                trimmed.startsWith("00") && digits.length > 2 -> digits.drop(2)
                else -> null
            }
            if (international != null) {
                val resolved = Countries.resolveTyped(international)
                return@update if (resolved == null) {
                    s.copy(input = "+" + international.take(MaxDigits), error = null)
                } else {
                    val (country, rest) = resolved
                    s.copy(country = country, input = PhoneNumber.nationalDigits(rest, country, pasted = false), error = null)
                }
            }
            // A paste (more than one new character) may carry the calling code; a key at a time never strips it.
            val pasted = text.length > s.input.length + 1
            s.copy(input = PhoneNumber.nationalDigits(text, s.country, pasted), error = null)
        }
    }

    fun onPickCountry(open: Boolean) = _state.update { if (it.busy) it else it.copy(picking = open, query = "") }
    fun onQuery(text: String) = _state.update { it.copy(query = text.take(40)) }

    /** A country from the picker: the digits typed so far stay, re-read for that country. */
    fun onCountry(country: Country) = _state.update {
        if (it.busy) return@update it
        val digits = if (it.input.startsWith("+")) "" else it.input
        it.copy(country = country, picking = false, query = "", input = PhoneNumber.nationalDigits(digits, country, pasted = false), error = null)
    }

    fun onSendCode() {
        val current = _state.value
        if (current.busy) return
        val country = current.country
        if (current.input.startsWith("+")) {
            _state.update { it.copy(error = "Finish the country code, or pick your country.") }
            return
        }
        val phone = PhoneNumber.toE164(current.input, country, pasted = false)
        if (phone == null) {
            val short = PhoneNumber.looksTooShort(current.input, country, pasted = false) || current.input.isEmpty()
            val exact = country.lengths.first == country.lengths.last
            _state.update {
                it.copy(
                    error = when {
                        // Design ID-04d, word for word.
                        country == Countries.Kenya -> "That number looks too short. Kenyan numbers have ${PhoneNumber.NationalDigits} digits after ${PhoneNumber.CountryCode}."
                        short && exact -> "That number looks too short. Numbers with +${country.dialCode} have ${country.lengths.first} digits after the code."
                        short -> "That number looks too short. Check it and try again."
                        else -> "That number doesn't look right. Check it and try again."
                    },
                )
            }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = start(phone)) {
                is AppResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    events.send(Event.CodeSent(phone, result.value))
                }
                is AppResult.Failure -> _state.update { it.copy(busy = false).withError(result.error) }
            }
        }
    }

    private fun UiState.withError(error: AppError): UiState {
        if (error is AppError.Api && error.code == "identity.phone_taken") return copy(numberTaken = true)
        if (error is AppError.Api && error.code == "request.rate_limited" && error.retryAfterSeconds != null) {
            val minutes = (error.retryAfterSeconds + 59) / 60
            return copy(error = "Too many codes sent. Try again in $minutes ${if (minutes == 1) "minute" else "minutes"}.")
        }
        return copy(error = ErrorMessages.forError(error))
    }

    private companion object {
        /** E.164's longest number: the most a "+…" being typed can hold. */
        const val MaxDigits = 15
    }

    /** "Use a different number" on ID-07. */
    fun onUseDifferentNumber() = _state.update { it.copy(numberTaken = false, input = "", error = null) }

    /** Back out of sign-up: the person signs in again from the start. */
    fun onCancel() {
        viewModelScope.launch { auth.signOutLocally() }
    }
}
