package com.circaso.feature.spaces.presentation.chat

import androidx.compose.runtime.Composable
import com.circaso.core.navigation.SpaceChatRoute
import com.circaso.core.navigation.SpaceInviteRoute
import com.circaso.core.navigation.SpaceInvitesRoute
import com.circaso.core.navigation.SpaceMembersRoute
import com.circaso.core.navigation.SpaceReportRoute
import com.circaso.core.navigation.SpaceReportsRoute
import com.circaso.feature.spaces.presentation.SpaceStub
import com.circaso.feature.spaces.presentation.SpacesNav

// Stubs until the chat and admin area is built: SH1, SR1–SR4, SI1, SI4.

@Composable fun SpaceChatEntry(route: SpaceChatRoute, nav: SpacesNav) = SpaceStub("Space chat", nav.back)
@Composable fun SpaceMembersEntry(route: SpaceMembersRoute, nav: SpacesNav) = SpaceStub("Members", nav.back)
@Composable fun SpaceRolesEntry(nav: SpacesNav) = SpaceStub("Roles", nav.back)
@Composable fun SpaceReportEntry(route: SpaceReportRoute, nav: SpacesNav) = SpaceStub("Report", nav.back)
@Composable fun SpaceReportsEntry(route: SpaceReportsRoute, nav: SpacesNav) = SpaceStub("Reports", nav.back)
@Composable fun SpaceInviteEntry(route: SpaceInviteRoute, nav: SpacesNav) = SpaceStub("Invite", nav.back)
@Composable fun SpaceInvitesEntry(route: SpaceInvitesRoute, nav: SpacesNav) = SpaceStub("Invites and Requests", nav.back)
