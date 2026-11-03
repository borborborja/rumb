package cat.rumb.app.viewer.hud

import cat.rumb.app.data.opentracks.model.GeoPoint
import cat.rumb.app.data.opentracks.model.Segment
import cat.rumb.app.data.opentracks.model.TrackStatistics
import cat.rumb.app.data.opentracks.model.Trackpoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val MS_TO_KMH = 3.6
private const val EARTH_RADIUS_M = 6_371_000.0
private const val SLOPE_WINDOW = 10 // trailing points used to smooth slope/VAM
private const val PACE_WINDOW_MS = 12_000L // trailing window used to smooth pace (see smoothedSpeedKmh)

/**
 * Pure, unit-testable derivation of [LiveMetrics] from OpenTracks segments + track statistics.
 * Kept free of Android/MapLibre types.
 */
object MetricsCalculator {

    fun compute(
        segments: List<Segment>,
        statistics: TrackStatistics?,
        isRecording: Boolean,
        elevationProvider: ElevationProvider? = null,
    ): LiveMetrics {
        val points = segments.flatten().filter { it.latLong != null }
        val lastPoint = points.lastOrNull()
        // Pauses and separate tracks break continuity. Do not derive an imaginary leg or mix
        // pre-pause speeds into the first fixes after resuming.
        val currentSegment = segments.lastOrNull { segment -> segment.any { it.latLong != null } }
            ?.filter { it.latLong != null }.orEmpty()

        val currentSpeedKmh = lastPoint?.speed?.takeIf { it >= 0.0 }?.times(MS_TO_KMH)
        val bearing = lastPoint?.bearing ?: bearingOfLastLeg(currentSegment)
        // Prefer OpenTracks' per-point altitude (column "elevation"); fall back to a DEM provider.
        val altitude = lastPoint?.altitude
            ?: lastPoint?.latLong?.let { elevationProvider?.altitudeAt(it.latitude, it.longitude) }
        val (slope, vam) = slopeAndVam(currentSegment)

        return LiveMetrics(
            speedKmh = currentSpeedKmh,
            avgMovingSpeedKmh = statistics?.avgMovingSpeedMeterPerSecond?.times(MS_TO_KMH),
            maxSpeedKmh = statistics?.maxSpeedMeterPerSecond?.takeIf { it > 0.0 }?.times(MS_TO_KMH),
            distanceKm = (statistics?.totalDistanceMeter ?: 0.0) / 1000.0,
            totalTime = statistics?.totalTime ?: kotlin.time.Duration.ZERO,
            movingTime = statistics?.movingTime ?: kotlin.time.Duration.ZERO,
            // Pace uses a smoothed speed, not the raw fix: see [smoothedSpeedKmh]. SPEED stays raw.
            paceMinPerKm = paceFromSpeedKmh(smoothedSpeedKmh(currentSegment) ?: currentSpeedKmh),
            bearingDeg = bearing,
            elevationGainM = statistics?.elevationGainMeter,
            minElevationM = statistics?.minElevationMeter,
            maxElevationM = statistics?.maxElevationMeter,
            altitudeM = altitude,
            slopePercent = slope,
            vamMeterPerHour = vam,
            heartRateBpm = lastPoint?.heartRate,
            cadenceRpm = lastPoint?.cadence,
            powerW = lastPoint?.power,
            pointCount = points.size,
            isRecording = isRecording,
        )
    }

    /**
     * Instantaneous slope (%) and VAM (vertical ascent meters/hour) from a trailing window of points
     * that carry altitude. Uses the oldest and newest point in the window to smooth GPS noise.
     */
    fun slopeAndVam(points: List<Trackpoint>): Pair<Double?, Double?> {
        val withAlt = points.filter { it.latLong != null && it.altitude != null }
        if (withAlt.size < 2) return null to null
        val window = withAlt.takeLast(SLOPE_WINDOW)
        val first = window.first()
        val last = window.last()
        val dAlt = (last.altitude!! - first.altitude!!)
        var horiz = 0.0
        for (i in 1 until window.size) {
            horiz += distanceMeters(window[i - 1].latLong!!, window[i].latLong!!)
        }
        val dtSeconds = (last.time.toEpochMilli() - first.time.toEpochMilli()) / 1000.0
        val slope = if (horiz > 1.0) dAlt / horiz * 100.0 else null
        val vam = if (dtSeconds > 1.0) dAlt / dtSeconds * 3600.0 else null
        return slope to vam
    }

    /**
     * Speed (km/h) averaged over a trailing time window, used for pace.
     *
     * The raw per-fix speed is fine on a bike at 25 km/h, but a runner at 5:00/km watches it swing
     * between 4:30 and 5:40 every second and never settle. This averages the REPORTED speeds rather
     * than differentiating positions: the GPS figure is Doppler-derived and more accurate than
     * position differencing, which would trade jitter for worse jitter. Windowed by time, not by
     * point count, because fix density varies. Stateless, like [slopeAndVam].
     */
    fun smoothedSpeedKmh(points: List<Trackpoint>): Double? {
        val usable = points.filter { it.latLong != null && it.speed >= 0.0 }
        if (usable.isEmpty()) return null
        val cutoff = usable.last().time.toEpochMilli() - PACE_WINDOW_MS
        val window = usable.filter { it.time.toEpochMilli() >= cutoff }.ifEmpty { listOf(usable.last()) }
        return window.sumOf { it.speed } / window.size * MS_TO_KMH
    }

    /** Pace in minutes per km from speed; null for non-positive speed. */
    fun paceFromSpeedKmh(speedKmh: Double?): Double? {
        if (speedKmh == null || speedKmh <= 0.1) return null
        return 60.0 / speedKmh
    }

    private fun bearingOfLastLeg(points: List<Trackpoint>): Double? {
        if (points.size < 2) return null
        val b = points[points.size - 1].latLong ?: return null
        val a = points[points.size - 2].latLong ?: return null
        return bearing(a, b)
    }

    /** Initial bearing from [from] to [to] in degrees [0,360). */
    fun bearing(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Great-circle distance in meters between two coordinates (Haversine). */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        // max(0.0, …) guards against a NaN when FP error pushes h just above 1 (near-antipodal points).
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(maxOf(0.0, 1 - h)))
    }
}
