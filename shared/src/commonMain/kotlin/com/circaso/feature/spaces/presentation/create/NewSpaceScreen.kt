package com.circaso.feature.spaces.presentation.create

import androidx.compose.runtime.Composable
import com.circaso.core.navigation.NewSpaceRoute
import com.circaso.core.navigation.SpaceDetailsRoute
import com.circaso.core.navigation.SpaceJoinSettingsRoute
import com.circaso.core.navigation.SpaceSettingsRoute
import com.circaso.core.navigation.SpaceWalletRoute
import com.circaso.core.navigation.SpaceWalletSetupRoute
import com.circaso.feature.spaces.presentation.SpaceStub
import com.circaso.feature.spaces.presentation.SpacesNav

// Stubs until the create area is built: SN1–SN8b and SW1 pushed.

@Composable fun NewSpaceEntry(route: NewSpaceRoute, nav: SpacesNav) = SpaceStub("New Space", nav.back)
@Composable fun SpaceWalletSetupEntry(route: SpaceWalletSetupRoute, nav: SpacesNav) = SpaceStub("Space wallet", nav.back)
@Composable fun SpaceSettingsEntry(route: SpaceSettingsRoute, nav: SpacesNav) = SpaceStub("Space Settings", nav.back)
@Composable fun SpaceDetailsEntry(route: SpaceDetailsRoute, nav: SpacesNav) = SpaceStub("Details", nav.back)
@Composable fun SpaceJoinSettingsEntry(route: SpaceJoinSettingsRoute, nav: SpacesNav) = SpaceStub("Who Can Join", nav.back)
@Composable fun SpaceWalletEntry(route: SpaceWalletRoute, nav: SpacesNav) = SpaceStub("Space wallet", nav.back)
