package com.circaso.gallery

/** Debug only: true when the app was started with this launch argument (lets scripts run the gallery checks without tapping). */
internal expect fun launchFlag(name: String): Boolean

/** Debug only: the value of a launch argument like `-preview=you`, or null. */
internal expect fun launchValue(name: String): String?
