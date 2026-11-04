package com.circaso.gallery

import androidx.compose.runtime.Composable

/** Spaces core (phase 3) previews, one function per area: `-preview=<name>` with sample data. */
@Composable
internal fun SpacesPreview(name: String): Boolean =
    SpacesDiscoverPreview(name) || SpacePagePreview(name) || SpaceCreatePreview(name) || SpaceChatPreview(name)
