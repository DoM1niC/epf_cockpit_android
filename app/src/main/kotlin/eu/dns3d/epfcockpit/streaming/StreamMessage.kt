package eu.dns3d.epfcockpit.streaming

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire-format for the App → Node-Server WebSocket stream.
 * The shape MUST match `web/README.md` ("Message contract") — any change
 * here needs the server side updated in lockstep.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface StreamMessage {

    @Serializable
    @SerialName("hello")
    data class Hello(
        val scooterName: String?,
        val scooterAddress: String?,
        val authenticated: Boolean,
        val app: String,
        val appVersion: String,
    ) : StreamMessage

    @Serializable
    @SerialName("telemetry")
    data class Telemetry(
        val ts: Long,
        val speedKmh: Double,
        val batteryPercent: Int,
        val voltageV: Double,
        val currentA: Double,
        val powerW: Double,
        val controllerTempC: Double,
        val tripKm: Double,
        val totalKm: Double,
        /** Park-Zustand wie in der App angezeigt (Intent-Latch, nicht das mehrdeutige Roh-Bit). */
        val parking: Boolean,
        val rideModeStand: Boolean?,
        val lightOn: Boolean? = null,
        val motorTempC: Double? = null,
    ) : StreamMessage

    @Serializable
    @SerialName("state")
    data class State(
        val connected: Boolean,
        val authenticated: Boolean,
        val scooterName: String?,
    ) : StreamMessage

    @Serializable
    @SerialName("trip_start")
    data class TripStart(
        val tripId: String,
        val ts: Long,
    ) : StreamMessage

    @Serializable
    @SerialName("trip_end")
    data class TripEnd(
        val tripId: String,
        val startedAt: Long,
        val ts: Long,                  // = endedAt
        val distanceKm: Double,
        val energyWh: Double,
        val durationS: Long,
        val maxSpeedKmh: Double,
        val avgSpeedKmh: Double,
        val scooterAddress: String?,
        /**
         * Optional alle aufgenommenen GPS-Waypoints des Trips. Wird vom
         * TripCoordinator beim Trip-End mitgeschickt (Live-Finalisierung) und
         * vom TripSyncer beim Backfill — damit der Server auch dann an die
         * Waypoints kommt, wenn die Live-`location`-Frames verloren gingen
         * (Mobilfunkloch, Server down, Streaming-Switch aus).
         */
        val locations: List<LocationPoint>? = null,
    ) : StreamMessage

    /**
     * Live-Waypoint vom [eu.dns3d.epfcockpit.data.LocationCollector]
     * (1s-Intervall). `tripId == null` heißt Idle-Fix (nur Dashboard-Marker,
     * kein Trip-Persist). Der Server validiert die Frame gegen INGEST_TOKEN
     * wie jedes andere Ingest-Event auch.
     */
    @Serializable
    @SerialName("location")
    data class Location(
        val tripId: String?,
        val ts: Long,
        val lat: Double,
        val lon: Double,
        @SerialName("accuracy_m") val accuracyM: Float? = null,
        @SerialName("speed_mps") val speedMps: Float? = null,
    ) : StreamMessage

    /** Inline-Variante für [TripEnd.locations] — kein eigener Top-Level-Type. */
    @Serializable
    data class LocationPoint(
        val ts: Long,
        val lat: Double,
        val lon: Double,
        @SerialName("accuracy_m") val accuracyM: Float? = null,
        @SerialName("speed_mps") val speedMps: Float? = null,
    )
}
