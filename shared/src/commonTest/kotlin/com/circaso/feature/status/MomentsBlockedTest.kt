package com.circaso.feature.status

import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.ObserveBlocksUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.status.presentation.withoutBlocked
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MomentsBlockedTest {
    private fun contact(id: String) = Contact(Person(id, id, null, null, null), "phone", "2026-10-01")
    private val contacts = flowOf(listOf(contact("wanjiru"), contact("juma"), contact("faith")))

    @Test fun blockedPeopleLeaveTheContactsMomentsAreShownFor() = runTest {
        val blocked = MutableStateFlow(listOf(Person("juma", "Juma", null, null, null)))
        val blocks = ObserveBlocksUseCase(object : ContactsRepository by FakeContacts() { override val blocks = blocked })
        assertEquals(listOf("wanjiru", "faith"), contacts.withoutBlocked(blocks).first().map { it.person.userId })
        blocked.value = emptyList() // unblocked: their Moments come back
        assertEquals(listOf("wanjiru", "juma", "faith"), contacts.withoutBlocked(blocks).first().map { it.person.userId })
    }

    @Test fun withoutABlockListNothingIsFiltered() = runTest {
        assertEquals(3, contacts.withoutBlocked(null).first().size)
    }
}
