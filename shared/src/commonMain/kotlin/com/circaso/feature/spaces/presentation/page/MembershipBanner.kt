package com.circaso.feature.spaces.presentation.page

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.circaso.feature.spaces.domain.Space

/**
 * SM3 renewal reminder and SM4 grace: the warning banner a members-only space shows its paying member, on the space
 * page and in the space chat. Draws nothing when there is nothing to renew. [onRenew] opens the renewal card (the page
 * with its join card). Stub until the page area builds it; the signature is fixed so the chat can call it.
 */
@Composable
fun MembershipBanner(space: Space, now: Long, onRenew: () -> Unit, modifier: Modifier = Modifier) {
}
