package com.circaso.di

import com.circaso.feature.home.presentation.YouViewModel
import com.circaso.feature.account.presentation.SecurityLogViewModel
import com.circaso.feature.account.presentation.SecurityViewModel
import com.circaso.feature.assistant.presentation.AshaViewModel
import com.circaso.feature.assistant.presentation.VoiceViewModel
import com.circaso.feature.contacts.presentation.BlockedViewModel
import com.circaso.feature.contacts.presentation.ContactsViewModel
import com.circaso.feature.profile.presentation.EditProfileViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val homeModule = module {
    // Support tickets (SU1–SU4): one copy in memory for Help, the list and each ticket; the `support` frames update it.
    single { com.circaso.feature.support.SupportTickets(get()) }
    // Support (SP01, SP06): tickets, a chat with Circaso's own account once it exists, and the lost-access case.
    viewModel {
        val auth = get<com.circaso.core.session.AuthManager>()
        val logs = com.circaso.feature.support.DiagnosticLogs(get(), get())
        com.circaso.feature.support.presentation.HelpViewModel(get(), get(), get(), get(), get(), myUserId = { auth.userId }, sendLog = logs::send, tickets = get())
    }
    viewModel { com.circaso.feature.support.presentation.TicketsViewModel(get(), get()) }
    viewModel { (category: String?) -> com.circaso.feature.support.presentation.NewTicketViewModel(category, get(), get()) }
    viewModel { (ticketId: String) -> com.circaso.feature.support.presentation.TicketViewModel(ticketId, get(), get()) }
    viewModel { (phone: String?) -> com.circaso.feature.support.presentation.LostAccessViewModel(phone, get()) }
    viewModel { YouViewModel(get(), get(), get(), get(), get(), get(), kickOutbox = get(named("kickOutbox")), prefs = get()) }
    viewModel { EditProfileViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ContactsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), editName = com.circaso.feature.names.presentation.EditNameController(get(), get())) }
    viewModel { BlockedViewModel(get(), get(), get()) }
    viewModel { SecurityViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { com.circaso.feature.home.presentation.NotificationsViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { SecurityLogViewModel(get(), get()) }
    viewModel { AshaViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), chats = get(), usage = get(), availability = get()) }
    viewModel { VoiceViewModel(get(), get(), get(), get(), getOrNull(), connection = get<com.circaso.core.sync.ConnectionState>().status, availability = get(), vadLoader = getOrNull<com.circaso.feature.assistant.voice.VadEngine>()?.let { com.circaso.feature.assistant.voice.VadLoader(it) }) }
}
