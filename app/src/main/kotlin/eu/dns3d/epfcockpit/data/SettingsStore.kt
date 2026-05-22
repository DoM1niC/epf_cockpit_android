package eu.dns3d.epfcockpit.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "epf_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * App-level preferences. PIN is intentionally NOT stored encrypted — the same PIN
 * is sent in plaintext over BLE (per ZydDash analysis), so any disk encryption
 * here would be theatre. If you want stronger storage, wire EncryptedSharedPreferences
 * or `androidx.security.crypto`.
 */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {

    data class Snapshot(
        val lastScooterAddress: String?,
        val lastScooterName: String?,
        val savedPin: String?,
        val autoConnect: Boolean,
        val gear1MaxKmh: Int,
        val gear2MaxKmh: Int,
        val gear3MaxKmh: Int,
        /** Last ride mode the user picked (true = Stand Stop, false = Kick Start). */
        val rideModeStand: Boolean,
        val themeMode: ThemeMode,
        /** WebSocket-Endpoint des Node-Dashboards (z.B. ws://192.168.1.50:8787/ws/ingest). */
        val streamingUrl: String?,
        val streamingEnabled: Boolean,
        /** Shared-Token gegen [streamingUrl] (server-side INGEST_TOKEN). Null = ohne Auth. */
        val streamingToken: String?,
        /** True wenn der User den Startup-Permissions-Dialog schonmal gesehen UND beide
         *  Voraussetzungen erfüllt sind. Sonst wird er beim nächsten Start wieder angezeigt. */
        val startupPromptDone: Boolean,
    )

    val flow: Flow<Snapshot> = context.dataStore.data.map { p ->
        Snapshot(
            lastScooterAddress = p[KEY_ADDRESS],
            lastScooterName = p[KEY_NAME],
            savedPin = p[KEY_PIN],
            autoConnect = p[KEY_AUTO_CONNECT] ?: true,
            gear1MaxKmh = p[KEY_G1] ?: 6,
            gear2MaxKmh = p[KEY_G2] ?: 15,
            gear3MaxKmh = p[KEY_G3] ?: 20,
            rideModeStand = p[KEY_RIDE_MODE_STAND] ?: true,
            themeMode = (p[KEY_THEME_MODE])?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            streamingUrl = p[KEY_STREAMING_URL]?.takeIf { it.isNotBlank() },
            streamingEnabled = p[KEY_STREAMING_ENABLED] ?: false,
            streamingToken = p[KEY_STREAMING_TOKEN]?.takeIf { it.isNotBlank() },
            startupPromptDone = p[KEY_STARTUP_PROMPT_DONE] ?: false,
        )
    }

    suspend fun setRideModeStand(stand: Boolean) {
        context.dataStore.edit { p -> p[KEY_RIDE_MODE_STAND] = stand }
    }

    suspend fun rememberScooter(address: String, name: String?) {
        context.dataStore.edit { p ->
            p[KEY_ADDRESS] = address
            if (name != null) p[KEY_NAME] = name else p.remove(KEY_NAME)
        }
    }

    suspend fun savePin(pin: String) {
        context.dataStore.edit { p -> p[KEY_PIN] = pin }
    }

    suspend fun clearPin() {
        context.dataStore.edit { p -> p.remove(KEY_PIN) }
    }

    suspend fun setAutoConnect(enabled: Boolean) {
        context.dataStore.edit { p -> p[KEY_AUTO_CONNECT] = enabled }
    }

    suspend fun setGearLimits(g1: Int, g2: Int, g3: Int) {
        context.dataStore.edit { p ->
            p[KEY_G1] = g1.coerceIn(1, 25)
            p[KEY_G2] = g2.coerceIn(1, 25)
            p[KEY_G3] = g3.coerceIn(1, 25)
        }
    }

    suspend fun forgetScooter() {
        context.dataStore.edit { p ->
            p.remove(KEY_ADDRESS); p.remove(KEY_NAME); p.remove(KEY_PIN)
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { p -> p[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setStreamingUrl(url: String?) {
        context.dataStore.edit { p ->
            val trimmed = url?.trim().orEmpty()
            if (trimmed.isEmpty()) p.remove(KEY_STREAMING_URL) else p[KEY_STREAMING_URL] = trimmed
        }
    }

    suspend fun setStreamingEnabled(enabled: Boolean) {
        context.dataStore.edit { p -> p[KEY_STREAMING_ENABLED] = enabled }
    }

    suspend fun setStreamingToken(token: String?) {
        context.dataStore.edit { p ->
            val trimmed = token?.trim().orEmpty()
            if (trimmed.isEmpty()) p.remove(KEY_STREAMING_TOKEN) else p[KEY_STREAMING_TOKEN] = trimmed
        }
    }

    suspend fun setStartupPromptDone(done: Boolean) {
        context.dataStore.edit { p -> p[KEY_STARTUP_PROMPT_DONE] = done }
    }

    private companion object {
        val KEY_ADDRESS = stringPreferencesKey("scooter_address")
        val KEY_NAME = stringPreferencesKey("scooter_name")
        val KEY_PIN = stringPreferencesKey("scooter_pin")
        val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val KEY_G1 = intPreferencesKey("gear1_max_kmh")
        val KEY_G2 = intPreferencesKey("gear2_max_kmh")
        val KEY_G3 = intPreferencesKey("gear3_max_kmh")
        val KEY_RIDE_MODE_STAND = booleanPreferencesKey("ride_mode_stand")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_STREAMING_URL = stringPreferencesKey("streaming_url")
        val KEY_STREAMING_ENABLED = booleanPreferencesKey("streaming_enabled")
        val KEY_STREAMING_TOKEN = stringPreferencesKey("streaming_token")
        val KEY_STARTUP_PROMPT_DONE = booleanPreferencesKey("startup_prompt_done")
    }
}
