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
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = CircasoSizes.avatarMd, imageUrl: String? = null, bytes: ByteArray? = null) {
    val c = CircasoTheme.colors
    // Each word's first letter or digit: a saved name like "Shiru (cousin)" gives "SC", never "S(".
    val initials = name.trim().split(" ").mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }.take(2).joinToString("") { it.uppercase() }
    Box(
        modifier.size(size).clip(CircasoShapes.circle).background(c.fillStrong),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(initials, style = CircasoTheme.typography.headline.copy(color = c.ink, fontSize = (size.value * 0.36f).sp))
        if (bytes != null) {
            // A picture only this phone has yet (a group photo before it is sent): drawn from memory.
            AsyncImage(model = bytes, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else if (imageUrl != null) {
            val key = imageUrl.substringBefore('?')
            // One file on disk; in memory one copy per size drawn, so a small chat-list avatar is never stretched
            // into the large profile photo or the full-screen view (it looked soft).
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current).data(imageUrl).memoryCacheKey("$key@${size.value.toInt()}")
                    // While this size decodes, the chat list's copy stands in, so switching lists never flashes a photo in.
                    .placeholderMemoryCacheKey("$key@${CircasoSizes.avatarMd.value.toInt()}").diskCacheKey(key).crossfade(true).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size),
            )
        }
    }
}
