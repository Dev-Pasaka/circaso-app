package com.circaso.core.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An update always goes to the newest version on the store, never through the ones in between (Pasaka, 2026-11-15). */
class AppUpdatesLatestTest {
    @Test fun aNewerVersionThanTheOneAlreadyDownloadedIsFetchedInstead() =
        assertTrue(AppUpdates.newerThanStaged(staged = 1040, available = 1043, updateAvailable = true))

    @Test fun theDownloadedOneIsInstalledWhenItIsStillTheLatest() =
        assertFalse(AppUpdates.newerThanStaged(staged = 1043, available = 1043, updateAvailable = true))

    @Test fun nothingWasStartedSoThereIsNothingToPassOver() =
        assertFalse(AppUpdates.newerThanStaged(staged = 0, available = 1043, updateAvailable = true))

    @Test fun withoutAnUpdateOnTheStoreNothingChanges() =
        assertFalse(AppUpdates.newerThanStaged(staged = 1040, available = 1043, updateAvailable = false))
}
