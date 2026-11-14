package com.circaso.di

import com.circaso.core.database.CircasoDatabase
import com.circaso.core.session.AuthManager
import com.circaso.core.sync.nowMillis
import com.circaso.feature.names.data.KtorSavedNamesRemote
import com.circaso.feature.names.data.SavedNamesRemote
import com.circaso.feature.names.data.SavedNamesRepository
import com.circaso.feature.names.domain.PeopleNames
import com.circaso.feature.names.domain.SavedNames
import org.koin.dsl.bind
import com.circaso.feature.keybackup.data.KeyBackupRemote
import com.circaso.feature.keybackup.data.KeyBackupRepository
import com.circaso.feature.keybackup.data.KtorKeyBackupRemote
import com.circaso.feature.keybackup.presentation.KeyBackupViewModel
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.vault.data.VaultRemote
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The group key backup (2026-11-11): group names survive signing out and new phones; see [KeyBackupRepository]. */
@OptIn(ExperimentalEncodingApi::class)
val keyBackupModule = module {
    single<KeyBackupRemote> { KtorKeyBackupRemote(get()) }
    single {
        val groupKeys = get<GroupKeys>()
        KeyBackupRepository(
            remote = get(), passkeys = get(), crypto = get(), secure = get(),
            userId = { get<AuthManager>().userId },
            localKeys = { groupKeys.allKeys() },
            keepKey = { chatId, version, key -> groupKeys.rememberMetaKey(chatId, version, key) },
            vaultCredential = {
                (get<VaultRemote>().status() as? com.circaso.core.common.AppResult.Success)?.value
                    ?.takeIf { it.exists }?.credentialId
                    ?.let { runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(it) }.getOrNull() }
            },
            refreshChats = { get<ChatsRepository>().refresh() },
        ).also { backup ->
            groupKeys.onKeyKept = { chatId, version, key -> backup.onKeyKept(chatId, version, key) }
            // Saved names ride on the same root: pulled and merged as soon as this phone holds it.
            backup.onRootReady = { get<SavedNamesRepository>().sync() }
        }
    }
    // Saved names (2026-11-12): on the phone first, backed up sealed with the root above.
    single { get<CircasoDatabase>().savedNamesDao() }
    single<SavedNamesRemote> { KtorSavedNamesRemote(get()) }
    single {
        SavedNamesRepository(
            dao = get(), remote = get(), crypto = get(), userId = { get<AuthManager>().userId },
            root = { get<KeyBackupRepository>().rootOnPhone() }, scope = get(), now = { nowMillis() },
        )
    } bind SavedNames::class
    single { get<SavedNames>().let { s -> PeopleNames(s.names, (s as? SavedNamesRepository)?.loaded ?: kotlinx.coroutines.flow.MutableStateFlow(true)) } }
    viewModel { KeyBackupViewModel(get(), get(), get(), get()) }
}
