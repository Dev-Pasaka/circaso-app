package com.circaso.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

const val PreferencesFileName = "circaso.preferences_pb"

/** One DataStore per file for the whole process. [path] is the full file path from the platform. */
fun createPreferencesDataStore(path: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })

/** `null` means "follow the system". */
data class ThemeOverrides(val dark: Boolean? = null, val highContrast: Boolean? = null)

/**
 * Small non-sensitive settings. Tokens, keys and anything secret never go here: DataStore is not encrypted.
 * Room is for data we can refetch; this is for preferences and small markers (sync ids, last-seen seq, flags).
 */
class AppPreferences(private val store: DataStore<Preferences>) : com.circaso.core.realtime.RealtimeCursors, com.circaso.feature.chama.domain.PendingLinks {
    private val darkKey = stringPreferencesKey("theme.dark")
    private val currencyKey = stringPreferencesKey("money.currency")

    /** The currency money is shown in, chosen in You → Currency; null follows the phone's region. */
    val currency: Flow<String?> = store.data.map { it[currencyKey] }
    /** Asha's voice, chosen in Asha's menu → Settings → Voice; null is the server's default. */
    val ashaVoice: Flow<String?> = store.data.map { it[stringPreferencesKey("asha.voice")] }
    suspend fun setAshaVoice(id: String?) { store.edit { if (id == null) it.remove(stringPreferencesKey("asha.voice")) else it[stringPreferencesKey("asha.voice")] = id } }

    suspend fun setCurrency(code: String?) { store.edit { if (code == null) it.remove(currencyKey) else it[currencyKey] = code } }
    private val contrastKey = stringPreferencesKey("theme.highContrast")

    val themeOverrides: Flow<ThemeOverrides> = store.data.map {
        ThemeOverrides(dark = it[darkKey]?.toBooleanStrictOrNull(), highContrast = it[contrastKey]?.toBooleanStrictOrNull())
    }

    /** The last realtime sequence number seen for a module, so a reconnect replays only what was missed. 0 when none. */
    override suspend fun lastSeq(module: String): Long = store.data.map { it[longPreferencesKey("rt.seq.$module")] ?: 0L }.first()

    override suspend fun setLastSeq(module: String, seq: Long) { store.edit { it[longPreferencesKey("rt.seq.$module")] = seq } }

    override suspend fun clear() {
        store.edit { prefs ->
            // The Wallet tab's remembered mode and group belong to the account too, and so do its group invites, the
            // reactions on my moments already shown and the security codes I've checked.
            prefs.asMap().keys.filter { it.name.startsWith("rt.seq.") || it.name.startsWith("text.wallet.") || it.name.startsWith("text.vault.") || it.name.startsWith("text.verified.") || it.name.startsWith("text.keybackup.") || it.name == "text.$GroupInvitesText" || it.name == "text.$GroupLinksText" || it.name == "text.moments.reactions_seen" || it.name == "flag.$BiometricApproval" || it.name.startsWith("flag.$BiometricApproval.") || it.name.startsWith("flag.biometric_lost.") }.forEach { prefs.remove(it) }
        }
    }

    /** When the inbox was last opened; notices after it are unread. */
    val notificationsSeenAt: Flow<Long> = store.data.map { it[longPreferencesKey("notifications.seenAt")] ?: 0L }
    suspend fun setNotificationsSeenAt(at: Long) { store.edit { it[longPreferencesKey("notifications.seenAt")] = at } }

    /** A treasurer's payment waiting to be linked to its request (CH6): request id → "chatId|transferId". Kept until it lands. */
    override val pendingLinks: Flow<Map<String, Pair<String, String>>> = store.data.map { prefs ->
        prefs.asMap().mapNotNull { (k, v) ->
            if (!k.name.startsWith("chama.link.")) return@mapNotNull null
            val parts = (v as? String)?.split('|')?.takeIf { it.size == 2 } ?: return@mapNotNull null
            k.name.removePrefix("chama.link.") to (parts[0] to parts[1])
        }.toMap()
    }
    override suspend fun putLink(requestId: String, chatId: String, transferId: String) { store.edit { it[stringPreferencesKey("chama.link.$requestId")] = "$chatId|$transferId" } }
    override suspend fun removeLink(requestId: String) { store.edit { it.remove(stringPreferencesKey("chama.link.$requestId")) } }

    /** Small text settings by name ("wallet.mode"); null when unset. Not for anything secret. */
    fun text(name: String): Flow<String?> = store.data.map { it[stringPreferencesKey("text.$name")] }
    suspend fun setText(name: String, value: String?) { store.edit { if (value == null) it.remove(stringPreferencesKey("text.$name")) else it[stringPreferencesKey("text.$name")] = value } }

    /** The selected wallet network and the server's last list, written together so a restart never reads half of it. */
    suspend fun setWalletNetworks(selected: String?, list: String?) {
        store.edit { prefs ->
            val s = stringPreferencesKey("text.$WalletNetworkText"); val l = stringPreferencesKey("text.$WalletNetworksText")
            if (selected == null) prefs.remove(s) else prefs[s] = selected
            if (list == null) prefs.remove(l) else prefs[l] = list
        }
    }

    /** Small one-time flags ("asha.met"). */
    fun flag(name: String): Flow<Boolean> = store.data.map { it[booleanPreferencesKey("flag.$name")] ?: false }
    suspend fun setFlag(name: String, value: Boolean) { store.edit { it[booleanPreferencesKey("flag.$name")] = value } }

    /**
     * Group wallets on or off on this phone, over the build's default (debug on, release off); null follows the build.
     * Set from the `-group-wallets=on|off|default` launch argument in debug builds. See `GroupWalletsFlag`.
     */
    val groupWalletsOverride: Flow<Boolean?> = store.data.map { prefs -> prefs[stringPreferencesKey(GroupWalletsKey)]?.let { it == "on" } }
    suspend fun setGroupWalletsOverride(value: String?) {
        store.edit { if (value == "on" || value == "off") it[stringPreferencesKey(GroupWalletsKey)] = value else it.remove(stringPreferencesKey(GroupWalletsKey)) }
    }


    /**
     * Fingerprint / Face ID approval for one wallet ([walletId] null: the personal wallet). Each wallet has its own,
     * and so does each wallet network ([network] `MAINNET` or `TESTNET`; null is Testnet, as before networks): the
     * Mainnet wallet is another Circle user with another PIN.
     */
    fun walletBiometrics(walletId: String?, network: String? = null): Flow<Boolean> = flag(biometricFlag(walletId, network))
    suspend fun setWalletBiometrics(walletId: String?, on: Boolean, network: String? = null) = setFlag(biometricFlag(walletId, network), on)

    /** Circle said this wallet's biometrics are gone (its row says why until they're turned on again), per network. */
    fun walletBiometricsLost(walletId: String?, network: String? = null): Flow<Boolean> = flag(biometricLostFlag(walletId, network))
    suspend fun setWalletBiometricsLost(walletId: String?, lost: Boolean, network: String? = null) = setFlag(biometricLostFlag(walletId, network), lost)

    suspend fun setDark(value: Boolean?) = store.edit { prefs ->
        if (value == null) prefs.remove(darkKey) else prefs[darkKey] = value.toString()
    }

    suspend fun setHighContrast(value: Boolean?) = store.edit { prefs ->
        if (value == null) prefs.remove(contrastKey) else prefs[contrastKey] = value.toString()
    }

    companion object {
        /** Payments approve with Face ID / fingerprint on this phone; belongs to the signed-in account, so cleared on sign-out. */
        const val BiometricApproval = "biometric_approval"

        /**
         * Other people's moments aren't fetched ahead (You → Moments → Download automatically, Pasaka 2026-11-15);
         * they are unless this is turned off. A phone setting, like the money sound.
         */
        const val MomentAutoDownloadOff = "moment_auto_download_off"

        /**
         * The money sounds are off (You → Money sound): the coins for money received and the chime when a top-up's USDC
         * lands (2026-11-23). On unless turned off; the phone's silent mode silences them as well.
         */
        const val MoneySoundOff = "money_sound_off"

        /**
         * Notification privacy (You → Notification privacy, Pasaka 2026-11-06): off unless turned on. When on, money
         * says only "You received money", chats "New message" and calls "Incoming call". A phone setting, kept on
         * sign-out; iOS mirrors it where its notification extension can read it (see `NotificationPrivacyMirror`).
         */
        const val NotificationPrivacy = "notification_privacy"

        private const val GroupWalletsKey = "flag.override.group_wallets"

        /**
         * My open group invites as the server last listed them (sealed names, as the server sends them), so they show
         * offline; the account's, so cleared on sign-out.
         */
        const val GroupInvitesText = "group_invites"

        /** The join links of the groups I administer, as the server last gave them, so a link shows offline; the account's. */
        const val GroupLinksText = "group_links"

        /**
         * The flag that says a wallet approves with biometrics: the personal one keeps its old name, and Testnet keeps
         * the names from before networks, so nobody's setting is lost; Mainnet's carry `mainnet`.
         */
        fun biometricFlag(walletId: String?, network: String? = null): String {
            val scope = if (network == "MAINNET") "$BiometricApproval.mainnet" else BiometricApproval
            return if (walletId == null) scope else "$scope.$walletId"
        }

        fun biometricLostFlag(walletId: String?, network: String? = null): String =
            "biometric_lost." + (if (network == "MAINNET") "mainnet." else "") + (walletId ?: "personal")

        /** The selected wallet network and the server's last list of them (`text.wallet.*`: wiped at sign-out). */
        /**
         * The top-ups whose landing chime has rung on this phone (order ids, comma separated, the newest 50), so it rings
         * once per order. Under `text.wallet.`, so it goes at sign-out with the rest of the wallet's.
         */
        const val RampChimedText = "wallet.ramp.chimed"
        const val WalletNetworkText = "wallet.network"
        const val WalletNetworksText = "wallet.networks"
    }
}
