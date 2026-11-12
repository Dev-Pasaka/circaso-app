package com.circaso.di

import com.circaso.feature.share.data.remote.KtorShareLinksRemote
import com.circaso.feature.share.data.remote.ShareLinksRemote
import com.circaso.feature.share.data.repository.ShareLinksRepositoryImpl
import com.circaso.feature.share.domain.CreateShareLinkUseCase
import com.circaso.feature.share.domain.ListShareLinksUseCase
import com.circaso.feature.share.domain.OpenShareLinkUseCase
import com.circaso.feature.share.domain.RevokeShareLinkUseCase
import com.circaso.feature.share.domain.ShareLinksRepository
import com.circaso.feature.share.presentation.ShareLinkViewModel
import com.circaso.feature.share.presentation.SharedLinkViewModel
import com.circaso.feature.share.presentation.SharedLinksViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Share links (SH1–SH4, decision 2026-11-06): making, opening and turning off the links every share now sends. */
val shareModule = module {
    single<ShareLinksRemote> { KtorShareLinksRemote(get()) }
    single<ShareLinksRepository> { ShareLinksRepositoryImpl(get(), names = get()) }
    factory { CreateShareLinkUseCase(get()) }
    factory { ListShareLinksUseCase(get()) }
    factory { RevokeShareLinkUseCase(get()) }
    factory { OpenShareLinkUseCase(get()) }
    // One for the app: the share layer sits over every screen, outside the navigation's view-model stores.
    single { ShareLinkViewModel(get(), get()) }
    viewModel { (code: String) -> SharedLinkViewModel(code, get(), get(), get(), get()) }
    viewModel { SharedLinksViewModel(get(), get(), get()) }
}
