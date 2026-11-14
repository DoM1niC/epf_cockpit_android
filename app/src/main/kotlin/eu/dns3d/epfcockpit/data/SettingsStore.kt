package eu.dns3d.epfcockpit.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "epf_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Dashboard-Layout-Variante, vom User in den Einstellungen wählbar.
 *  STANDARD = das bisherige Layout (Default — bestehende Nutzer sehen keine Änderung).
 *  KOMPAKT  = alle Werte auf einem Screen ohne Scrollen.
 *  FAHRT    = riesiger Tacho + Akku, minimale Kacheln (Glance während der Fahrt).
 *  DATEN    = nur Zahlen, keine runden Anzeigen (Diagnose/Technik).
 */
enum class DashboardLayout { STANDARD, KOMPAKT, FAHRT, DATEN }

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
        /** Nach einem Verbindungsabbruch auch bei gesperrtem Handy / Display aus
         *  selbst neu verbinden (Default an). Aus = Reconnect erst beim App-Start. */
        val reconnectWhileLocked: Boolean,
        val gear1MaxKmh: Int,
        val gear2MaxKmh: Int,
        val gear3MaxKmh: Int,
        /** Last ride mode the user picked (true = Stand Stop, false = Kick Start). */
        val rideModeStand: Boolean,
        val themeMode: ThemeMode,
        /** Vom User gewählte Dashboard-Layout-Variante. Default STANDARD. */
        val dashboardLayout: DashboardLayout,
        /** WebSocket-Endpoint des Node-Dashboards (z.B. ws://192.168.1.50:8787/ws/ingest). */
        val streamingUrl: String?,
        val streamingEnabled: Boolean,
        /** Shared-Token gegen [streamingUrl] (server-side INGEST_TOKEN). Null = ohne Auth. */
        val streamingToken: String?,
        /** True wenn der User den Startup-Permissions-Dialog schonmal gesehen UND beide
         *  Voraussetzungen erfüllt sind. Sonst wird er beim nächsten Start wieder angezeigt. */
        val startupPromptDone: Boolean,
        /** Optionales GPS-Tracking pro Trip. Default OFF — wird erst aktiv wenn
         *  der User es in den Settings einschaltet UND ACCESS_FINE_LOCATION
         *  genehmigt hat. Siehe [eu.dns3d.epfcockpit.data.LocationCollector]. */
        val trackLocation: Boolean,
        /** Wenn true, sendet die App auch im Idle (keine Fahrt, oder geparkt)
         *  GPS-Punkte an das Dashboard — nur live, nicht in Room. Nur wirksam
         *  wenn [trackLocation] ebenfalls true ist. */
        val trackLocationIdle: Boolean,
        /** Vom User vergebener Anzeigename des Rollers (z.B. "Pulse Plus von Tom").
         *  Wird im Hello/State-Frame ans Dashboard geschickt — null = Dashboard
         *  zeigt "E-Scooter" als Fallback. */
        val scooterFriendlyName: String?,
    )

    val flow: Flow<Snapshot> = context.dataStore.data.map { p ->
        Snapshot(
            lastScooterAddress = p[KEY_ADDRESS],
            lastScooterName = p[KEY_NAME],
            savedPin = p[KEY_PIN],
            autoConnect = p[KEY_AUTO_CONNECT] ?: true,
            reconnectWhileLocked = p[KEY_RECONNECT_WHILE_LOCKED] ?: true,
            gear1MaxKmh = p[KEY_G1] ?: 6,
            gear2MaxKmh = p[KEY_G2] ?: 15,
            gear3MaxKmh = p[KEY_G3] ?: 20,
            rideModeStand = p[KEY_RIDE_MODE_STAND] ?: true,
            themeMode = (p[KEY_THEME_MODE])?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dashboardLayout = (p[KEY_DASHBOARD_LAYOUT])?.let { runCatching { DashboardLayout.valueOf(it) }.getOrNull() }
                ?: DashboardLayout.STANDARD,
            streamingUrl = p[KEY_STREAMING_URL]?.takeIf { it.isNotBlank() },
            streamingEnabled = p[KEY_STREAMING_ENABLED] ?: false,
            streamingToken = p[KEY_STREAMING_TOKEN]?.takeIf { it.isNotBlank() },
            startupPromptDone = p[KEY_STARTUP_PROMPT_DONE] ?: false,
            trackLocation = p[KEY_TRACK_LOCATION] ?: false,
            trackLocationIdle = p[KEY_TRACK_LOCATION_IDLE] ?: false,
            scooterFriendlyName = p[KEY_SCOOTER_FRIENDLY_NAME]?.takeIf { it.isNotBlank() },
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

    suspend fun setReconnectWhileLocked(enabled: Boolean) {
        context.dataStore.edit { p -> p[KEY_RECONNECT_WHILE_LOCKED] = enabled }
    }

    suspend fun setGearLimits(g1: Int, g2: Int, g3: Int) {
        context.dataStore.edit { p ->
            p[KEY_G1] = g1.coerceIn(1, 30)
            p[KEY_G2] = g2.coerceIn(1, 30)
            // g3 bekommt 30 Cap (statt 25) — wir wollen Slider bis 30 km/h
            // erlauben um zu sehen ob der Controller den experimentellen
            // Headroom annimmt (Hersteller-Default ist 20).
            p[KEY_G3] = g3.coerceIn(1, 30)
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

    suspend fun setDashboardLayout(mode: DashboardLayout) {
        context.dataStore.edit { p -> p[KEY_DASHBOARD_LAYOUT] = mode.name }
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

    suspend fun setTrackLocation(enabled: Boolean) {
        context.dataStore.edit { p -> p[KEY_TRACK_LOCATION] = enabled }
    }

    suspend fun setTrackLocationIdle(enabled: Boolean) {
        context.dataStore.edit { p -> p[KEY_TRACK_LOCATION_IDLE] = enabled }
    }

    /**
     * Zuletzt gewünschter Licht-Zustand bei entsperrtem Roller samt Zeitpunkt
     * (Wall-Clock ms). Bewusst nicht im [Snapshot]: er ändert sich bei jedem
     * Schalten und soll nicht jedes Mal alle Settings-Beobachter wecken.
     */
    suspend fun lastLightState(): Pair<Boolean, Long>? {
        val p = context.dataStore.data.first()
        val on = p[KEY_LAST_LIGHT] ?: return null
        return on to (p[KEY_LAST_LIGHT_AT] ?: 0L)
    }

    suspend fun setLastLightState(on: Boolean, atMillis: Long) {
        context.dataStore.edit { p ->
            p[KEY_LAST_LIGHT] = on
            p[KEY_LAST_LIGHT_AT] = atMillis
            // Früheres Setting „Licht beim Entsperren einschalten“ — entfällt.
            p.remove(KEY_AUTO_LIGHT_ON_UNLOCK)
        }
    }

    suspend fun setScooterFriendlyName(name: String?) {
        context.dataStore.edit { p ->
            val trimmed = name?.trim().orEmpty()
            if (trimmed.isEmpty()) p.remove(KEY_SCOOTER_FRIENDLY_NAME)
            else p[KEY_SCOOTER_FRIENDLY_NAME] = trimmed
        }
    }

    private companion object {
        val KEY_ADDRESS = stringPreferencesKey("scooter_address")
        val KEY_NAME = stringPreferencesKey("scooter_name")
        val KEY_PIN = stringPreferencesKey("scooter_pin")
        val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val KEY_RECONNECT_WHILE_LOCKED = booleanPreferencesKey("reconnect_while_locked")
        val KEY_G1 = intPreferencesKey("gear1_max_kmh")
        val KEY_G2 = intPreferencesKey("gear2_max_kmh")
        val KEY_G3 = intPreferencesKey("gear3_max_kmh")
        val KEY_RIDE_MODE_STAND = booleanPreferencesKey("ride_mode_stand")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DASHBOARD_LAYOUT = stringPreferencesKey("dashboard_layout")
        val KEY_STREAMING_URL = stringPreferencesKey("streaming_url")
        val KEY_STREAMING_ENABLED = booleanPreferencesKey("streaming_enabled")
        val KEY_STREAMING_TOKEN = stringPreferencesKey("streaming_token")
        val KEY_STARTUP_PROMPT_DONE = booleanPreferencesKey("startup_prompt_done")
        val KEY_TRACK_LOCATION = booleanPreferencesKey("track_location")
        val KEY_TRACK_LOCATION_IDLE = booleanPreferencesKey("track_location_idle")
        val KEY_SCOOTER_FRIENDLY_NAME = stringPreferencesKey("scooter_friendly_name")
        val KEY_AUTO_LIGHT_ON_UNLOCK = booleanPreferencesKey("auto_light_on_unlock")
        val KEY_LAST_LIGHT = booleanPreferencesKey("last_light_on")
        val KEY_LAST_LIGHT_AT = longPreferencesKey("last_light_at")
    }
}
