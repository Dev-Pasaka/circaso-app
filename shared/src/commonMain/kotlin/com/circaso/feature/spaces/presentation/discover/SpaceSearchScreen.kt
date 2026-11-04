package com.circaso.feature.spaces.presentation.discover

import androidx.compose.runtime.Composable
import com.circaso.feature.spaces.presentation.SpaceStub
import com.circaso.feature.spaces.presentation.SpacesNav

/** SD2: search (stub until the discover area is built). */
@Composable
fun SpaceSearchEntry(nav: SpacesNav) = SpaceStub("Search", nav.back)

/** SD3: Suggested, See All. */
@Composable
fun SpacesSuggestedEntry(nav: SpacesNav) = SpaceStub("Suggested", nav.back)
