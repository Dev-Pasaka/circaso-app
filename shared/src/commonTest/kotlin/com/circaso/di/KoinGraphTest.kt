package com.circaso.di

import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Builds the graph the app starts with ([appModules], as `initKoin` does), so a launch crash is a failing test. The
 * Spaces merge crashed in `Application.onCreate` because `spacesModule` included `spacesViewModels` before that
 * top-level val was initialised: the list held a null and Koin threw while loading it. Unit tests never started Koin
 * whole, so nothing caught it. Definitions stay lazy here (none is created at start), so no platform service is needed.
 */
class KoinGraphTest {
    @OptIn(org.koin.core.annotation.KoinInternalApi::class) // reading includedModules: the null the crash was
    @Test fun everyModuleAndEveryIncludedModuleIsInitialised() {
        val seen = mutableSetOf<Module>()
        fun walk(path: String, m: Module?) {
            assertNotNull(m, "$path is null: declare it above the module that uses it (top-level vals initialise in file order)")
            if (!seen.add(m)) return
            m.includedModules.forEachIndexed { i, inner -> walk("$path > included[$i]", inner as Module?) }
        }
        appModules(module { }).forEachIndexed { i, m -> walk("appModules[$i]", m as Module?) }
        assertTrue(seen.size >= 15, "every feature module is in the list")
    }

    /**
     * Loads every module exactly as `startKoin` does at launch (overrides allowed, as in the app: several handlers bind
     * the same `OutboxHandler` type on purpose, collected with `getAll`).
     */
    @Test fun theWholeGraphLoads() {
        val app = koinApplication { modules(appModules(module { })) }
        app.close()
    }
}
