package eu.dns3d.epfcockpit.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP-Client fuer GET/PUT `/api/scooter-name` auf dem Node-Dashboard.
 * Wird vom [eu.dns3d.epfcockpit.ui.settings.SettingsViewModel] genutzt
 * um den vom User vergebenen Anzeigenamen bidirektional zu syncen:
 * neues Smartphone holt den Namen via GET, jeder Edit pusht via PUT.
 *
 * Die Base-URL leitet sich aus der WebSocket-`streamingUrl` ab
 * (ws[s] -> http[s], `/ws/ingest` -> `/api/scooter-name`). Wir
 * versuchen NICHT, zwischen INGEST_TOKEN und VIEW_TOKEN zu unterscheiden
 * — der Server akzeptiert beides auf diesem Endpoint.
 */
@Singleton
class ScooterNameApi @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Serializable
    data class ScooterName(
        val address: String,
        val name: String,
        val updatedAt: Long,
    )

    @Serializable
    private data class PutBody(val address: String, val name: String)

    /**
     * Holt den serverseitig hinterlegten Namen.
     * Returns null wenn:
     *   - die URL nicht abgeleitet werden kann
     *   - der Server 404 antwortet (noch nie gespeichert)
     *   - irgendein I/O- oder Parse-Fehler auftritt (geswallowt + geloggt)
     */
    suspend fun fetch(streamingUrl: String, token: String?, address: String): ScooterName? =
        withContext(Dispatchers.IO) {
            val base = deriveBaseUrl(streamingUrl) ?: return@withContext null
            val safeAddr = address.trim()
            if (safeAddr.isEmpty()) return@withContext null
            val url = "$base?address=" + java.net.URLEncoder.encode(safeAddr, "UTF-8")
            val req = Request.Builder().url(url).get().apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                header("Accept", "application/json")
            }.build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 404) return@withContext null
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "fetch: HTTP ${resp.code}")
                        return@withContext null
                    }
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@withContext null
                    json.decodeFromString(ScooterName.serializer(), body)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "fetch failed: ${t.message}")
                null
            }
        }

    /**
     * Schiebt den Namen auf den Server. Fire-and-forget aus UI-Sicht —
     * wir geben true/false zurueck, aber Caller ignoriert das aktuell.
     */
    suspend fun push(streamingUrl: String, token: String?, address: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            val base = deriveBaseUrl(streamingUrl) ?: return@withContext false
            val safeAddr = address.trim()
            val safeName = name.trim()
            if (safeAddr.isEmpty() || safeName.isEmpty()) return@withContext false
            val bodyJson = json.encodeToString(
                PutBody.serializer(),
                PutBody(address = safeAddr, name = safeName),
            )
            val req = Request.Builder().url(base).apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                header("Accept", "application/json")
            }.put(bodyJson.toRequestBody(jsonMediaType)).build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "push: HTTP ${resp.code}")
                    }
                    resp.isSuccessful
                }
            } catch (t: Throwable) {
                Log.w(TAG, "push failed: ${t.message}")
                false
            }
        }

    /**
     * `ws://host:port/ws/ingest` -> `http://host:port/api/scooter-name`
     * `wss://host:port/ws/ingest` -> `https://host:port/api/scooter-name`
     * Beliebige Pfade hinter `/ws/...` werden auf `/api/scooter-name` ersetzt
     * — wir nehmen nur Scheme+Authority. Bei Parse-Fehlern oder unbekanntem
     * Scheme: null.
     */
    private fun deriveBaseUrl(streamingUrl: String): String? {
        if (streamingUrl.isBlank()) return null
        return try {
            val uri = URI(streamingUrl.trim())
            val scheme = when (uri.scheme?.lowercase()) {
                "ws", "http" -> "http"
                "wss", "https" -> "https"
                else -> return null
            }
            val authority = uri.authority ?: return null
            "$scheme://$authority/api/scooter-name"
        } catch (t: Throwable) {
            Log.w(TAG, "deriveBaseUrl failed: ${t.message}")
            null
        }
    }

    private companion object {
        const val TAG = "ScooterNameApi"
    }
}
