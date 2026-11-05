package com.circaso.feature.spaces.presentation.chat

import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** The chat and admin area's ViewModels (included by `spacesModule`). Each takes the space id as its parameter. */
val spaceChatModule = module {
    viewModel { p -> SpaceChatViewModel(p.get(), get(), get(), get(), get(named("ownUserId"))) }
    viewModel { p -> SpaceMembersViewModel(p.get(), get(), get(), get(), get(named("ownUserId"))) }
    viewModel { p -> SpaceReportViewModel(p[0], p.values.getOrNull(1) as? String, get(), get(), get()) }
    viewModel { p -> SpaceReportsViewModel(p.get(), get(), get()) }
    viewModel { p -> SpaceInviteViewModel(p.get(), get(), get(), get(), get()) }
    viewModel { p -> SpaceInvitesViewModel(p.get(), get(), get()) }
}
