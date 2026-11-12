package com.circaso.designsystem

import com.circaso.designsystem.components.avatarInitials
import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarInitialsTest {
    @Test fun plainNames() {
        assertEquals("JK", avatarInitials("John Kamau"))
        assertEquals("J", avatarInitials("john"))
        assertEquals("JK", avatarInitials("  John   Kamau Otieno "))
    }

    @Test fun emojiAroundLettersIsSkipped() {
        assertEquals("WK", avatarInitials("🌸 Wanjiru Kamau"))
        assertEquals("K", avatarInitials("Kevin 🔥"))
        assertEquals("K", avatarInitials("🔥Kevin"))
        assertEquals("SC", avatarInitials("Shiru (cousin)"))
        assertEquals("MD", avatarInitials("Mum ❤️ Doe"))
    }

    @Test fun onlyEmojiShowsTheWholeFirstEmoji() {
        assertEquals("🔥", avatarInitials("🔥🔥"))
        assertEquals("❤️", avatarInitials("❤️"))
        assertEquals("👍🏽", avatarInitials("👍🏽 👍"))
        assertEquals("👩‍💻", avatarInitials("👩‍💻"))
        assertEquals("🇰🇪", avatarInitials("🇰🇪🇰🇪"))
    }

    @Test fun keycapCountsAsItsDigit() {
        assertEquals("1", avatarInitials("1️⃣"))
    }

    @Test fun fancyLettersShowWhole() {
        assertEquals("𝓙", avatarInitials("𝓙𝓪𝓷𝓮"))
    }

    @Test fun blank() {
        assertEquals("", avatarInitials("   "))
    }
}

class PeopleListLettersTest {
    @Test fun sections() {
        kotlin.test.assertEquals("W", com.circaso.designsystem.components.sectionLetter("🌸 Wanjiru Kamau"))
        kotlin.test.assertEquals("E", com.circaso.designsystem.components.sectionLetter("Émile"))
        kotlin.test.assertEquals("J", com.circaso.designsystem.components.sectionLetter("john"))
        kotlin.test.assertEquals("#", com.circaso.designsystem.components.sectionLetter("🔥🔥"))
        kotlin.test.assertEquals("#", com.circaso.designsystem.components.sectionLetter("123"))
    }

    @Test fun firstWordAndSort() {
        kotlin.test.assertEquals("Wanjiru", com.circaso.designsystem.components.firstNameWord("🌸 Wanjiru Kamau"))
        kotlin.test.assertEquals("🔥", com.circaso.designsystem.components.firstNameWord("🔥"))
        kotlin.test.assertEquals("wanjiru", com.circaso.designsystem.components.sortKey("🌸 Wanjiru"))
    }
}
