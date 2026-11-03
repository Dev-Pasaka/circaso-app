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
    /** Asha's voice, chosen in You → Asha's voice; null is the server's default. */
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
            prefs.asMap().keys.filter { it.name.startsWith("rt.seq.") || it.name == "flag.$BiometricApproval" || it.name.startsWith("flag.$BiometricApproval.") }.forEach { prefs.remove(it) }
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

    /** Fingerprint / Face ID approval for one wallet ([walletId] null: the personal wallet). Each wallet has its own. */
    fun walletBiometrics(walletId: String?): Flow<Boolean> = flag(biometricFlag(walletId))
    suspend fun setWalletBiometrics(walletId: String?, on: Boolean) = setFlag(biometricFlag(walletId), on)

    suspend fun setDark(value: Boolean?) = store.edit { prefs ->
        if (value == null) prefs.remove(darkKey) else prefs[darkKey] = value.toString()
    }

    suspend fun setHighContrast(value: Boolean?) = store.edit { prefs ->
        if (value == null) prefs.remove(contrastKey) else prefs[contrastKey] = value.toString()
    }

    companion object {
        /** Payments approve with Face ID / fingerprint on this phone; belongs to the signed-in account, so cleared on sign-out. */
        const val BiometricApproval = "biometric_approval"

        /** The coins sound for money received is off (You → Money sound); it is on unless turned off. */
        const val MoneySoundOff = "money_sound_off"

        private const val GroupWalletsKey = "flag.override.group_wallets"

        /** The flag that says a wallet approves with biometrics: the personal one keeps its old name. */
        fun biometricFlag(walletId: String?) = if (walletId == null) BiometricApproval else "$BiometricApproval.$walletId"
    }
}
