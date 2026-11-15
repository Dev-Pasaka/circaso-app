package com.circaso.feature.status

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.circaso.core.datastore.AppPreferences
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.status.presentation.MomentsPrivacyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** You → Moments → Download automatically (Pasaka, 2026-11-15): on unless turned off, kept on the phone. */
@OptIn(ExperimentalCoroutinesApi::class)
class MomentsAutoDownloadSettingTest {
    private class Memory : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }

    private val prefs = AppPreferences(Memory())

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = MomentsPrivacyViewModel(ViewerFakeMoments(), ObserveContactsUseCase(FakeContacts()), prefs)

    @Test fun itIsOnUntilSomeoneTurnsItOff() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(true, viewModel().state.value.autoDownload)
    }

    @Test fun turningItOffIsKeptAndShownTheNextTimeTheScreenOpens() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.onAutoDownload(false)
        assertEquals(false, vm.state.value.autoDownload)
        assertEquals(true, prefs.flag(AppPreferences.MomentAutoDownloadOff).first())
        assertEquals(false, viewModel().state.value.autoDownload, "a new visit opens with it off")
        vm.onAutoDownload(true)
        assertEquals(false, prefs.flag(AppPreferences.MomentAutoDownloadOff).first())
    }
}
