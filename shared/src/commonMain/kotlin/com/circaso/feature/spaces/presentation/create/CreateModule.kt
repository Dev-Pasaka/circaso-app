package com.circaso.feature.spaces.presentation.create

import com.circaso.feature.spaces.domain.SpacesRepository
import com.circaso.feature.wallet.presentation.SpaceWalletName
import com.circaso.feature.wallet.presentation.SpaceWalletNames
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The create area's ViewModels (included by `spacesModule`), and the space names the wallet screens use. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
val spaceCreateModule = module {
    // SW1: a space wallet is named after its space (and its owner, for an admin's caption), from the phone's copy.
    single<SpaceWalletNames> {
        val spaces = get<SpacesRepository>()
        val wallet = get<com.circaso.feature.wallet.domain.WalletRepository>()
        SpaceWalletNames {
            val known = spaces.mySpaces.flatMapLatest { mine ->
                if (mine.isEmpty()) flowOf(emptyMap())
                else combine(mine.map { m -> spaces.space(m.space.spaceId).map { s -> m to s } }) { pairs ->
                    pairs.associate { (m, s) -> m.space.spaceId to SpaceWalletName(s?.name ?: m.space.name, s?.summary?.photoUrl ?: m.space.photoUrl, s?.owner?.displayName) }
                }
            }
            // Names heard from `payment.received` (keyed by wallet id) until Room has the space; the phone's spaces win.
            combine(wallet.walletNamesHeard, known) { heard, mine -> heard.mapValues { SpaceWalletName(it.value, null) } + mine }
        }
    }
    viewModel { (name: String?) ->
        NewSpaceViewModel(name, get(), get(), get(), get(), getOrNull<com.circaso.core.platform.ImagePicker>())
    }
    viewModel { (spaceId: String) ->
        val bio = get<com.circaso.core.platform.Biometrics>()
        SpaceWalletSetupViewModel(spaceId, get(), get(), get(), get(), get(), get(), get(), biometricLabel = bio.name.takeIf { bio.available() })
    }
    viewModel { (spaceId: String) -> SpaceSettingsViewModel(spaceId, get(), get(), get(), get()) }
    viewModel { (spaceId: String, mode: SpaceEditMode) -> SpaceEditViewModel(spaceId, mode, get(), get(), get(), getOrNull<com.circaso.core.platform.ImagePicker>()) }
    viewModel { (spaceId: String) -> SpaceWalletViewModel(spaceId, get(), get(), get(), get(), get(), spaceNames = get()) }
}
