package cat.hudpro.opentracks.data.opentracks.model

import java.time.Instant
import kotlin.time.Duration

/**
 * Render-agnostic geographic point (WGS84). Kept independent from MapLibre so the
 * OpenTracks reader layer stays unit-testable on the plain JVM. The rendering layer
 * converts these to `org.maplibre.android.geometry.LatLng`.
 */
data class GeoPoint(val latitude: Double, val longitude: Double)

const val TRACKPOINT_TYPE_TRACKPOINT = 0
const val TRACKPOINT_TYPE_PAUSE = 3

data class Trackpoint(
    val trackId: Long,
    val id: Long,
    val latLong: GeoPoint?,
    val type: Int,
    val speed: Double,
    val time: Instant,
    // Per-point sensor/measurement data (all optional; populated only when present).
    val altitude: Double? = null, // m (OpenTracks column "elevation")
    val heartRate: Double? = null, // bpm ("sensor_heartrate")
    val cadence: Double? = null, // rpm ("sensor_cadence")
    val power: Double? = null, // watts ("sensor_power")
    val bearing: Double? = null, // degrees
) {
    val isPause: Boolean get() = type == TRACKPOINT_TYPE_PAUSE
}

typealias Segment = List<Trackpoint>

data class TrackpointsDebug(
    var trackpointsReceived: Int = 0,
    var trackpointsInvalid: Int = 0,
    var trackpointsPause: Int = 0,
    var segments: Int = 0,
    var protocolVersion: Int = 1,
)

data class TrackpointsBySegments(
    val segments: List<Segment>,
    val debug: TrackpointsDebug,
) {
    fun isEmpty() = segments.isEmpty()
    fun isNotEmpty() = segments.isNotEmpty()
    fun last() = segments.last()

    /** All valid (non-pause, non-null) points flattened, in order. */
    fun allPoints(): List<Trackpoint> = segments.flatten()
}

data class Track(
    val id: Long,
    val name: String?,
    val description: String?,
    val activityType: String?,
    val activityTypeLocalized: String? = null,
    val timeStart: Instant,
    val timeStop: Instant,
    val distance: Double,
    val durationTotal: Duration,
    val durationMoving: Duration,
    val avgSpeedMeterPerSecond: Double? = null,
    val avgMovingSpeedMeterPerSecond: Double? = null,
    val maxSpeedMeterPerSecond: Double = 0.0,
    val minAltitudeMeter: Double = 0.0,
    val maxAltitudeMeter: Double = 0.0,
    val altitudeGainMeter: Double = 0.0,
    val altitudeLossMeter: Double = 0.0,
)

data class Waypoint(
    val id: Long,
    val name: String?,
    val description: String?,
    val typeLocalized: String?,
    val trackId: Long,
    val latLong: GeoPoint,
    val photoUrl: String?,
)

/**
 * Aggregated statistics of one or more tracks, used to drive the HUD/speedbar.
 */
data class TrackStatistics(
    val category: String? = null,
    val startTime: Instant? = null,
    val stopTime: Instant? = null,
    val totalDistanceMeter: Double = 0.0,
    val totalTime: Duration = Duration.ZERO,
    val movingTime: Duration = Duration.ZERO,
    val avgSpeedMeterPerSecond: Double? = null,
    val avgMovingSpeedMeterPerSecond: Double? = null,
    val maxSpeedMeterPerSecond: Double = 0.0,
    val minElevationMeter: Double = 0.0,
    val maxElevationMeter: Double = 0.0,
    val elevationGainMeter: Double = 0.0,
    val elevationLossMeter: Double = 0.0,
) {
    companion object {
        fun fromTracks(tracks: List<Track>): TrackStatistics? {
            if (tracks.isEmpty()) return null
            val first = tracks.first()
            return TrackStatistics(
                category = first.activityTypeLocalized ?: first.activityType,
                startTime = tracks.minByOrNull { it.timeStart }?.timeStart,
                stopTime = tracks.maxByOrNull { it.timeStop }?.timeStop,
                totalDistanceMeter = tracks.sumOf { it.distance },
                totalTime = tracks.fold(Duration.ZERO) { acc, t -> acc + t.durationTotal },
                movingTime = tracks.fold(Duration.ZERO) { acc, t -> acc + t.durationMoving },
                avgSpeedMeterPerSecond = first.avgSpeedMeterPerSecond,
                avgMovingSpeedMeterPerSecond = first.avgMovingSpeedMeterPerSecond,
                maxSpeedMeterPerSecond = tracks.maxOf { it.maxSpeedMeterPerSecond },
                minElevationMeter = tracks.minOf { it.minAltitudeMeter },
                maxElevationMeter = tracks.maxOf { it.maxAltitudeMeter },
                elevationGainMeter = tracks.sumOf { it.altitudeGainMeter },
                elevationLossMeter = tracks.sumOf { it.altitudeLossMeter },
            )
        }
    }
}
