package com.circaso.core.network

/**
 * Public identifiers for Google sign-in (not secrets). The Web client id is the audience the backend checks
 * (`INTEGRATIONS_SOCIAL_GOOGLE_CLIENT_IDS`); both platforms pass it as the "server client id".
 * Dev and production may differ later: this moves to a build setting with the base URL.
 */
object SocialConfig {
    const val GoogleWebClientId = "177338416308-v1adcpuoj7cn8ofid7rj89gr7a9dqp97.apps.googleusercontent.com"
}
