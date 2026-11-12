package com.circaso.feature.names

import com.circaso.feature.names.domain.SavedNames
import com.circaso.feature.names.presentation.EditName
import com.circaso.feature.names.presentation.EditNameController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Edit name sheet: what Save does, Use Their Name, the limit, and the way back to the person's sheet. */
class EditNameControllerTest {
    private class Names : SavedNames {
        override val names = MutableStateFlow<Map<String, String>>(emptyMap())
        var fail = false
        override suspend fun set(userId: String, name: String) { if (fail) error("disk"); names.value = names.value + (userId to SavedNames.normalise(name)!!) }
        override suspend fun clear(userId: String) { names.value = names.value - userId }
    }

    private val names = Names()
    private fun TestScope.controller() = EditNameController(names, this, returnDelayMillis = 10)

    @Test
    fun `save is only offered for a change, and a blank name only clears a saved one`() {
        val fresh = EditName("a", "Amina Wanjiru", "Amina Wanjiru", hasSaved = false)
        assertFalse(fresh.canSave, "unchanged")
        assertFalse(fresh.copy(draft = "  ").canSave, "blank with nothing saved")
        assertTrue(fresh.copy(draft = "Shiru").canSave)
        val saved = EditName("a", "Amina Wanjiru", "Shiru", hasSaved = true)
        assertTrue(saved.copy(draft = "").canSave, "blank clears the saved name")
        assertNull(fresh.copy(draft = "x".repeat(29)).counter)
        assertEquals("30/40", fresh.copy(draft = "x".repeat(30)).counter)
    }

    @Test
    fun `saving keeps the name, and the person's sheet comes back after`() = runTest {
        var back = 0
        val c = controller()
        c.open("a", "Amina Wanjiru", "Amina Wanjiru", returnTo = { back++ })
        c.onDraft("Shiru " + "x".repeat(60))
        assertEquals(SavedNames.MaxLength, c.state.value!!.draft.length)
        c.onDraft("Shiru")
        c.onSave()
        assertNull(c.state.value, "the sheet closes at once")
        advanceUntilIdle()
        assertEquals("Shiru", names.names.value["a"])
        assertEquals(1, back)
    }

    @Test
    fun `typing their own name back, or Use Their Name, clears the saved one`() = runTest {
        names.names.value = mapOf("a" to "Shiru", "b" to "Bri")
        val c = controller()
        c.open("a", "Amina Wanjiru", "Shiru")
        assertTrue(c.state.value!!.hasSaved)
        c.onDraft("Amina Wanjiru ")
        c.onSave()
        advanceUntilIdle()
        assertFalse("a" in names.names.value)

        c.open("b", "Brian Otieno", "Bri")
        c.onUseTheirs()
        advanceUntilIdle()
        assertFalse("b" in names.names.value)
    }

    @Test
    fun `a failed save is said, never pretended`() = runTest {
        names.fail = true
        var said: String? = null
        val c = controller()
        c.onProblem = { said = it }
        c.open("a", "Amina Wanjiru", "Amina Wanjiru")
        c.onDraft("Shiru")
        c.onSave()
        advanceUntilIdle()
        assertEquals(EditNameController.CouldNotSave, said)
        assertTrue(names.names.value.isEmpty())
    }

    @Test
    fun `cancel saves nothing`() = runTest {
        val c = controller()
        c.open("a", "Amina Wanjiru", "Amina Wanjiru")
        c.onDraft("Shiru")
        c.onDismiss()
        advanceUntilIdle()
        assertNull(c.state.value)
        assertTrue(names.names.value.isEmpty())
    }
}
