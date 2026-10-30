package com.circaso.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

@Immutable
object CircasoSpacing {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s10 = 40.dp
    val s14 = 56.dp
}

@Immutable
object CircasoShapes {
    val xs = RoundedCornerShape(6.dp)
    val sm = RoundedCornerShape(10.dp)
    val md = RoundedCornerShape(14.dp)
    val lg = RoundedCornerShape(22.dp)
    val sheet = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
    val pill = RoundedCornerShape(CornerSize(50))
    val circle = CircleShape
}

@Immutable
object CircasoSizes {
    val controlLg = 50.dp
    val controlMd = 44.dp
    val controlSm = 34.dp
    val iconButton = 36.dp
    /** The Circaso mark at small size: the wallet cards and the Chats masthead. */
    val brandMarkSm = 30.dp
    val avatarSm = 28.dp
    val avatarMd = 44.dp
    val avatarLg = 64.dp
    val avatarXl = 96.dp
}

@Immutable
object CircasoMotion {
    const val QuickMs = 150
    const val BaseMs = 280
    const val MomentMs = 900
}
