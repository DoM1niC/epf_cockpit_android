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
        val parking: Boolean,
        val rideModeStand: Boolean?,
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
    ) : StreamMessage
}
