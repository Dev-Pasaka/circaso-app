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
    // Support (SP01, SP06): a chat with Circaso's own account, and the lost-access case.
    viewModel {
        val auth = get<com.circaso.core.session.AuthManager>()
        com.circaso.feature.support.presentation.HelpViewModel(get(), get(), get(), get(), get(), myUserId = { auth.userId })
    }
    viewModel { (phone: String?) -> com.circaso.feature.support.presentation.LostAccessViewModel(phone, get()) }
    viewModel { YouViewModel(get(), get(), get(), get(), get(), get(), kickOutbox = get(named("kickOutbox")), prefs = get()) }
    viewModel { EditProfileViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ContactsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { BlockedViewModel(get(), get(), get()) }
    viewModel { SecurityViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { com.circaso.feature.home.presentation.NotificationsViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { SecurityLogViewModel(get(), get()) }
    viewModel { AshaViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), chats = get()) }
    viewModel { VoiceViewModel(get(), get(), get(), get(), getOrNull()) }
}
