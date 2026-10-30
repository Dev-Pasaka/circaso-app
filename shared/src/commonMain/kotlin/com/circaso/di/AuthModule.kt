package com.circaso.di

import com.circaso.feature.auth.data.remote.AuthRemote
import com.circaso.feature.auth.data.remote.KtorAuthRemote
import com.circaso.feature.auth.data.repository.AuthRepositoryImpl
import com.circaso.feature.auth.domain.AuthRepository
import com.circaso.feature.auth.domain.ForgetThisPhoneUseCase
import com.circaso.feature.auth.domain.RegisterDeviceUseCase
import com.circaso.feature.auth.domain.SignInWithSocialUseCase
import com.circaso.feature.auth.domain.SocialSignIn
import com.circaso.feature.auth.domain.UnavailableSocialSignIn
import com.circaso.feature.auth.domain.SignOutUseCase
import com.circaso.feature.auth.domain.StartPhoneVerificationUseCase
import com.circaso.feature.auth.domain.VerifyPhoneCodeUseCase
import com.circaso.feature.auth.domain.VerifyPhoneWithCarrierUseCase
import com.circaso.feature.auth.presentation.CodeViewModel
import com.circaso.feature.auth.presentation.PhoneViewModel
import com.circaso.feature.auth.presentation.RegisteringViewModel
import com.circaso.feature.auth.presentation.WelcomeViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Sign-in feature. The platform module provides `SocialSignIn` (Google/Apple on the phone). */
val authModule = module {
    single<AuthRemote> { KtorAuthRemote(get()) }
    // Replaced by the platform module once Google/Apple sign-in is implemented.
    single<SocialSignIn> { UnavailableSocialSignIn }
    single<AuthRepository> { AuthRepositoryImpl(get(), get(), get(), get()) }
    factory { SignInWithSocialUseCase(get(), get()) }
    factory { StartPhoneVerificationUseCase(get()) }
    factory { VerifyPhoneCodeUseCase(get()) }
    factory { VerifyPhoneWithCarrierUseCase(get()) }
    factory { RegisterDeviceUseCase(get()) }
    factory { SignOutUseCase(get()) }
    viewModel { WelcomeViewModel(get(), get()) }
    viewModel { PhoneViewModel(get(), get()) }
    viewModel { RegisteringViewModel(get()) }
    viewModel { params -> CodeViewModel(params.get(), params.get(), params.get(), params.get(), get(), get()) }
    factory { ForgetThisPhoneUseCase(get()) }
}
