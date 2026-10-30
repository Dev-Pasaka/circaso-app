package com.circaso.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import circaso.shared.generated.resources.Res
import circaso.shared.generated.resources.circaso_mark
import org.jetbrains.compose.resources.painterResource

/** The Circaso mark (metallic coin). Bundled with the app: it must show instantly and offline. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 184.dp) {
    Image(painterResource(Res.drawable.circaso_mark), contentDescription = "Circaso", modifier = modifier.size(size))
}
