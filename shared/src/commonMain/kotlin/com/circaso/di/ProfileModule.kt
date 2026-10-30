package com.circaso.di

import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.profile.data.remote.AvatarUploader
import com.circaso.feature.profile.data.remote.KtorAvatarUploader
import com.circaso.feature.profile.data.remote.KtorProfileRemote
import com.circaso.feature.profile.data.remote.ProfileRemote
import com.circaso.feature.profile.data.repository.ProfileRepositoryImpl
import com.circaso.feature.profile.data.repository.ProfileUpdateHandler
import com.circaso.feature.profile.domain.CheckHandleUseCase
import com.circaso.feature.profile.domain.ClaimHandleUseCase
import com.circaso.feature.profile.domain.FindByHandleUseCase
import com.circaso.feature.profile.domain.GetPublicProfileUseCase
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.profile.domain.RefreshProfileUseCase
import com.circaso.feature.profile.domain.RemoveAvatarUseCase
import com.circaso.feature.profile.domain.UpdateProfileUseCase
import com.circaso.feature.profile.domain.UploadAvatarUseCase
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val profileModule = module {
    single { get<com.circaso.core.database.CircasoDatabase>().profileDao() }
    single<ProfileRemote> { KtorProfileRemote(get()) }
    single<AvatarUploader> { KtorAvatarUploader(get(named("plain"))) }
    single { ProfileUpdateHandler(get(), get(), get(), get()) } bind OutboxHandler::class
    single<ProfileRepository> { ProfileRepositoryImpl(get(), get(), get(), get(), get(), kickOutbox = get(named("kickOutbox"))) }
    factory { ObserveProfileUseCase(get()) }
    factory { RefreshProfileUseCase(get()) }
    factory { UpdateProfileUseCase(get()) }
    factory { CheckHandleUseCase(get()) }
    factory { ClaimHandleUseCase(get()) }
    factory { UploadAvatarUseCase(get()) }
    factory { RemoveAvatarUseCase(get()) }
    factory { FindByHandleUseCase(get()) }
    factory { GetPublicProfileUseCase(get()) }
}
