package com.circaso.gallery

import circaso.shared.generated.resources.Res

/**
 * Debug only: profile photos for the made-up people in the preview screens (`-preview=<name>`), so the demos and the
 * store screenshots look like a lived-in social app. Unsplash stock photos (2026-11-12), credited in
 * `composeResources/files/demo-avatars/CREDITS.md`. Only gallery code may use these: a real person's avatar always
 * comes from their own profile.
 *
 * Each is a Compose resource URI, which Coil loads like any picture link (so the screens draw them exactly as they
 * draw a real profile photo).
 */
internal object DemoPhotos {
    private fun photo(file: String) = Res.getUri("files/demo-avatars/$file.jpg")

    val Mum = photo("mum")
    val Wanjiru = photo("wanjiru")
    val Otieno = photo("otieno")
    val Achieng = photo("achieng")
    val Juma = photo("juma")
    val Baraka = photo("baraka")
    val Mwita = photo("mwita")
    val Kevin = photo("kevin")
    val Faith = photo("faith")
    val Njeri = photo("njeri")
    /** The signed-in person in the demos ("You", user id `me`). */
    val You = photo("you")
    /** Holiday Fund's group photo: a beach on the Kenyan coast. */
    val HolidayFund = photo("holiday-fund")

    /** The demo people by the exact names the sample data gives them (a new name shows initials until it's added here). */
    private val byName by lazy {
        mapOf(
            "mum" to Mum,
            "wanjiru kamau" to Wanjiru, "wanjiru" to Wanjiru, "shiru (cousin)" to Wanjiru, "🌸 wanjiru kamau" to Wanjiru,
            "otieno odhiambo" to Otieno, "otieno" to Otieno,
            "achieng akinyi" to Achieng, "achieng" to Achieng,
            "juma kariuki" to Juma, "juma" to Juma,
            "baraka otieno" to Baraka, "baraka mwita" to Mwita,
            "kevin mwangi" to Kevin, "faith chebet" to Faith, "njeri" to Njeri,
            // The demo self (user id `me`), never a real person's name.
            "amina wanjiru" to You, "amina" to You,
            "holiday fund" to HolidayFund,
        )
    }

    /** The photo for a demo person or group by name; null for anyone else (they keep their initials). */
    fun of(name: String): String? = byName[name.trim().lowercase()]

    /** A member's photo: [You] for me, else by name. */
    fun member(userId: String, name: String): String? = if (userId == "me") You else of(name)
}
