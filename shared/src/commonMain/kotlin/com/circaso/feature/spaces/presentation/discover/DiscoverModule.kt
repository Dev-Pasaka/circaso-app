package com.circaso.feature.spaces.presentation.discover

import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.messaging.domain.ObserveChatsUseCase
import com.circaso.feature.status.domain.MomentsRepository
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.koin.core.module.dsl.viewModel
import org.koin.core.scope.Scope
import org.koin.dsl.module

private fun Scope.online() = get<ConnectionState>().status.map { it == ConnectionStatus.Online }
private fun Scope.fiat() = get<ObserveWalletUseCase>()().map { it?.fiat }

/** The discover area's ViewModels (included by `spacesModule`): the Spaces tab sections, search, suggested, Chats. */
val spacesDiscoverModule = module {
    viewModel { SpacesHomeViewModel(get(), online(), fiat()) }
    viewModel {
        // SD2's People: contacts with a live moment I can see, found on the phone (never sent to the server).
        val people = combine(get<MomentsRepository>().moments, get<ObserveContactsUseCase>()()) { moments, contacts ->
            val byId = contacts.associateBy { it.person.userId }
            moments.filter { !it.mine }.groupBy { it.posterId }.mapNotNull { (id, list) ->
                val p = byId[id]?.person ?: return@mapNotNull null
                val latest = list.maxByOrNull { it.postedAt }
                SearchPerson(id, p.displayName, p.avatarUrl, p.handle, latest?.text ?: latest?.caption)
            }
        }
        SpaceSearchViewModel(get(), people, online(), fiat())
    }
    viewModel { SpacesSuggestedViewModel(get(), online(), fiat()) }
    viewModel { ChatsSpacesViewModel(get(), get<ObserveChatsUseCase>()().map { list -> list.mapNotNull { it.chat.viaSpaceId }.toSet() }, online()) }
}
