package com.circaso.feature.spaces.presentation.page

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The page area's ViewModels (included by `spacesModule`). */
val spacePageModule = module {
    viewModel { params ->
        SpacePageViewModel(
            initialSpaceId = params.getOrNull<String>(),
            handle = params.values.getOrNull(1) as? String,
            openJoin = params.values.getOrNull(2) as? Boolean ?: false,
            spaces = get(), chats = get(), connection = get(),
            wallet = get<com.circaso.feature.wallet.domain.WalletRepository>().wallet,
            observeTransfer = { id -> get<com.circaso.feature.wallet.domain.WalletRepository>().transfer(id) },
        )
    }
}
