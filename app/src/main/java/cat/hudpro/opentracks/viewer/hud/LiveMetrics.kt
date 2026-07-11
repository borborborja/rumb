package cat.hudpro.opentracks.viewer.hud

import kotlin.time.Duration

/**
 * Snapshot of all values the HUD can display, derived from the live OpenTracks data.
 *
 * Note on elevation: the OpenTracks Dashboard trackpoint projection does NOT include per-point
 * altitude, so instantaneous altitude/slope/VAM require an external [ElevationProvider] (e.g. the
 * ICGC DEM). Aggregate elevation (gain, min, max) comes from the track statistics and is always set.
 */
data class LiveMetrics(
    val speedKmh: Double? = null,
    val avgMovingSpeedKmh: Double? = null,
    val maxSpeedKmh: Double? = null,
    val distanceKm: Double = 0.0,
    val totalTime: Duration = Duration.ZERO,
    val movingTime: Duration = Duration.ZERO,
    val paceMinPerKm: Double? = null,
    val bearingDeg: Double? = null,
    val elevationGainM: Double? = null,
    val minElevationM: Double? = null,
    val maxElevationM: Double? = null,
    val altitudeM: Double? = null,
    val slopePercent: Double? = null,
    val vamMeterPerHour: Double? = null,
    // Sensor data (from paired BLE sensors via OpenTracks)
    val heartRateBpm: Double? = null,
    val cadenceRpm: Double? = null,
    val powerW: Double? = null,
    val pointCount: Int = 0,
    val isRecording: Boolean = false,
    // Follow-route metrics (populated when a track is being followed; see follow package)
    val remainingDistanceKm: Double? = null,
    val offRouteMeters: Double? = null,
    val bearingToRouteDeg: Double? = null,
)

/** Optional source of instantaneous altitude for a coordinate (e.g. a DEM tile sampler). */
fun interface ElevationProvider {
    /** Altitude in meters at the given position, or null if unknown. */
    fun altitudeAt(latitude: Double, longitude: Double): Double?
}
