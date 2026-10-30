package com.circaso.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.compose.LocalPlatformContext
import coil3.request.crossfade
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoTheme

/**
 * Initials avatar, with the person's picture over it once it loads (so offline, slow or failed loads still show a
 * proper avatar). The picture link is signed for one hour and its query changes on every profile load, so the image
 * is cached under the link without its query: the same picture is never downloaded twice.
 */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = CircasoSizes.avatarMd, imageUrl: String? = null) {
    val c = CircasoTheme.colors
    val initials = name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        modifier.size(size).clip(CircasoShapes.circle).background(c.fillStrong),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(initials, style = CircasoTheme.typography.headline.copy(color = c.ink, fontSize = (size.value * 0.36f).sp))
        if (imageUrl != null) {
            val key = imageUrl.substringBefore('?')
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current).data(imageUrl).memoryCacheKey(key).diskCacheKey(key).crossfade(true).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size),
            )
        }
    }
}
