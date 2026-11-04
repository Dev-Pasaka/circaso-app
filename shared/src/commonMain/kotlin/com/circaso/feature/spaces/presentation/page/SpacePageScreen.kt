package com.circaso.feature.spaces.presentation.page

import androidx.compose.runtime.Composable
import com.circaso.core.navigation.SpaceRoute
import com.circaso.feature.spaces.presentation.SpaceStub
import com.circaso.feature.spaces.presentation.SpacesNav

/** SS1–SS6e, SM1–SM6, SI2b: the space page (stub until the page area is built). */
@Composable
fun SpacePageEntry(route: SpaceRoute, nav: SpacesNav) = SpaceStub("Space", nav.back)
