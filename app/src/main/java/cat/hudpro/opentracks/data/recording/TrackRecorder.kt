package cat.hudpro.opentracks.data.recording

import cat.hudpro.opentracks.data.opentracks.model.GeoPoint
import cat.hudpro.opentracks.data.opentracks.model.Segment
import cat.hudpro.opentracks.data.opentracks.model.TRACKPOINT_TYPE_TRACKPOINT
import cat.hudpro.opentracks.data.opentracks.model.TrackStatistics
import cat.hudpro.opentracks.data.opentracks.model.Trackpoint
import cat.hudpro.opentracks.viewer.hud.MetricsCalculator
import java.time.Instant
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/** Tunables of the native recording engine. Defaults follow OpenTracks' recording behavior. */
data class RecorderConfig(
    /** Reject fixes with a reported accuracy worse than this (m). */
    val maxAccuracyM: Float = 25f,
    /** Minimum distance between recorded points (m); closer fixes are skipped (idle jitter). */
    val minDistanceM: Double = 3.0,
    /** Reject fixes implying a speed above this (m/s) — GPS jumps. */
    val maxImpliedSpeedMs: Double = 50.0,
    /** Below this speed (m/s) the athlete counts as idle (no moving-time accrual). */
    val idleSpeedMs: Double = 0.5,
    /** Smoothed-altitude change accumulated before it counts toward gain/loss (noise gate, m). */
    val elevationHysteresisM: Double = 2.0,
    /** EMA factor for altitude smoothing (0..1; higher follows GPS faster). */
    val altitudeSmoothing: Double = 0.3,
    /** GPS warm-up: the track's first point requires fixes at least this precise (m). */
    val startAccuracyM: Float = 12f,
    /** GPS warm-up: consecutive precise fixes required before the first point is accepted. */
    val startGoodFixes: Int = 2,
    /**
     * Stationary-jitter gate: a leg shorter than accuracy × this factor is movement within the
     * fix's own uncertainty circle and is discarded (prevents phantom distance while stopped).
     */
    val jitterFactor: Double = 0.5,
)

/** Immutable snapshot of an ongoing/finished native recording, consumed by the viewer pipeline. */
data class RecorderState(
    val segments: List<Segment> = emptyList(),
    val statistics: TrackStatistics = TrackStatistics(),
    val isPaused: Boolean = false,
    val isFinished: Boolean = false,
) {
    val isRecording: Boolean get() = !isFinished
    fun points(): List<Trackpoint> = segments.flatten()
}

/**
 * Pure (Android-free, JVM-testable) native recording engine. Feed it GPS fixes and sensor samples;
 * it filters noise, accumulates live [TrackStatistics] and builds pause-aware segments in the same
 * model the OpenTracks dashboard produces, so the whole viewer pipeline works unchanged.
 *
 * Filtering/statistics logic follows OpenTracks' TrackPointCreator/TrackStatisticsUpdater
 * (Apache-2.0, see NOTICE), with extra guards: accuracy gate, impossible-jump gate and a smoothed
 * altitude with hysteresis so GPS noise doesn't inflate elevation gain.
 */
class TrackRecorder(private val config: RecorderConfig = RecorderConfig()) {

    private val closedSegments = mutableListOf<Segment>()
    private val currentSegment = mutableListOf<Trackpoint>()
    private var seq = 0L

    private var startedAt: Instant? = null
    private var lastPointTime: Instant? = null
    private var lastLatLong: GeoPoint? = null

    // Active (non-paused) wall time bookkeeping.
    private var activeSince: Instant? = null
    private var accumulatedActive: Duration = Duration.ZERO
    private var paused = false
    private var finished = false

    // Statistics accumulators.
    private var distanceM = 0.0
    private var movingTime: Duration = Duration.ZERO
    private var maxSpeedMs = 0.0
    private var smoothedAlt: Double? = null
    private var pendingElevDelta = 0.0
    private var gainM = 0.0
    private var lossM = 0.0
    private var minElevM: Double? = null
    private var maxElevM: Double? = null

    // Latest sensor samples, attached to the next accepted point.
    private var heartRate: Double? = null
    private var cadence: Double? = null
    private var power: Double? = null

    // GPS warm-up: no point is accepted until the fix quality stabilizes (see RecorderConfig).
    private var warmedUp = false
    private var warmupGoodFixes = 0

    fun start(time: Instant) {
        check(startedAt == null) { "already started" }
        startedAt = time
        activeSince = time
    }

    fun onHeartRate(bpm: Double?) { heartRate = bpm }
    fun onCadence(rpm: Double?) { cadence = rpm }
    fun onPower(watts: Double?) { power = watts }

    // Barometric gain/loss (much steadier than GPS): once pressure samples arrive, they own
    // elevation gain/loss and the GPS-altitude path stops accumulating (it keeps min/max/points).
    private var barometric = false
    private var smoothedPressureAlt: Double? = null
    private var pendingPressureDelta = 0.0

    /** Feeds a barometric pressure sample (hPa). */
    fun onPressure(hPa: Float) {
        if (paused || finished) return
        // Standard-atmosphere pressure altitude; only DELTAS are used, so no calibration needed.
        val alt = 44330.0 * (1.0 - Math.pow(hPa / 1013.25, 0.190284))
        val prev = smoothedPressureAlt
        val sm = if (prev == null) alt else prev + PRESSURE_SMOOTHING * (alt - prev)
        smoothedPressureAlt = sm
        if (prev != null) {
            barometric = true
            pendingPressureDelta += sm - prev
            if (abs(pendingPressureDelta) >= PRESSURE_HYSTERESIS_M) {
                if (pendingPressureDelta > 0) gainM += pendingPressureDelta else lossM += -pendingPressureDelta
                pendingPressureDelta = 0.0
            }
        }
    }

    /**
     * Rebuilds the recorder from persisted points (crash recovery). Filters are not re-applied
     * (points were already accepted); statistics are recomputed from the data.
     */
    fun restore(segments: List<Segment>, startedAt: Instant, resumeAt: Instant) {
        check(this.startedAt == null) { "already started" }
        this.startedAt = startedAt
        warmedUp = segments.any { it.isNotEmpty() } // the pre-crash track already locked GPS
        closedSegments.addAll(segments.filter { it.isNotEmpty() })
        val all = closedSegments.flatten()
        seq = (all.maxOfOrNull { it.id } ?: -1L) + 1
        for (segment in closedSegments) {
            for (i in 1 until segment.size) {
                val a = segment[i - 1]; val b = segment[i]
                if (a.latLong != null && b.latLong != null) {
                    distanceM += MetricsCalculator.distanceMeters(a.latLong, b.latLong)
                    val dt = java.time.Duration.between(a.time, b.time).toKotlinDuration()
                    if (b.speed >= config.idleSpeedMs) movingTime += dt
                }
            }
        }
        maxSpeedMs = all.maxOfOrNull { it.speed } ?: 0.0
        all.mapNotNull { it.altitude }.let { alts ->
            if (alts.isNotEmpty()) {
                minElevM = alts.min(); maxElevM = alts.max(); smoothedAlt = alts.last()
            }
        }
        lastPointTime = all.lastOrNull()?.time
        // Active time before the crash ≈ span of recorded points (pauses within it are unknown).
        accumulatedActive = all.lastOrNull()?.let { java.time.Duration.between(startedAt, it.time).toKotlinDuration() }
            ?: Duration.ZERO
        activeSince = resumeAt
        // New segment after the gap; reset the leg baseline so the crash gap adds no distance.
        lastLatLong = null
        lastPointTime = all.lastOrNull()?.time
    }

    /** Feeds a GPS fix. Returns the accepted [Trackpoint], or null if a filter rejected it. */
    fun onLocation(
        latitude: Double,
        longitude: Double,
        altitude: Double?,
        speedMs: Double?,
        bearingDeg: Double?,
        accuracyM: Float,
        time: Instant,
    ): Trackpoint? {
        if (paused || finished || startedAt == null) return null
        if (accuracyM > config.maxAccuracyM) return null

        // Warm-up gate: the very first point of the track needs a stable, precise fix, otherwise
        // the cold-start scatter gets recorded as a zigzag with phantom distance.
        if (!warmedUp) {
            warmupGoodFixes = if (accuracyM <= config.startAccuracyM) warmupGoodFixes + 1 else 0
            if (warmupGoodFixes < config.startGoodFixes) return null
            warmedUp = true
        }

        val here = GeoPoint(latitude, longitude)
        val prevLatLong = lastLatLong
        val prevTime = lastPointTime
        var legDistance = 0.0
        var dt = Duration.ZERO
        if (prevLatLong != null && prevTime != null) {
            legDistance = MetricsCalculator.distanceMeters(prevLatLong, here)
            dt = java.time.Duration.between(prevTime, time).toKotlinDuration()
            val dtSec = dt.inWholeMilliseconds / 1000.0
            if (dtSec > 0 && legDistance / dtSec > config.maxImpliedSpeedMs) return null // GPS jump
            // Jitter gate: ignore displacements below the min interval OR within the fix's own
            // uncertainty circle — while stopped, GPS scatter must not accumulate as distance.
            if (legDistance < maxOf(config.minDistanceM, accuracyM * config.jitterFactor)) {
                markIdleIfStopped(speedMs, time)
                return null
            }
        }

        val dtSec = dt.inWholeMilliseconds / 1000.0
        val rawSpeed = speedMs ?: if (dtSec > 0) legDistance / dtSec else 0.0
        // Below the idle threshold the GPS speed is noise — record a clean 0.
        val speed = if (rawSpeed < config.idleSpeedMs) 0.0 else rawSpeed

        // Altitude: EMA smoothing + hysteresis gate for gain/loss (skipped once the barometer owns it).
        val alt = altitude?.let { raw ->
            val prev = smoothedAlt
            val sm = if (prev == null) raw else prev + config.altitudeSmoothing * (raw - prev)
            if (prev != null && !barometric) {
                pendingElevDelta += sm - prev
                if (abs(pendingElevDelta) >= config.elevationHysteresisM) {
                    if (pendingElevDelta > 0) gainM += pendingElevDelta else lossM += -pendingElevDelta
                    pendingElevDelta = 0.0
                }
            }
            smoothedAlt = sm
            minElevM = minOf(minElevM ?: sm, sm)
            maxElevM = maxOf(maxElevM ?: sm, sm)
            sm
        }

        distanceM += legDistance
        if (speed >= config.idleSpeedMs) movingTime += dt
        maxSpeedMs = maxOf(maxSpeedMs, speed)

        val point = Trackpoint(
            trackId = 0L,
            id = seq++,
            latLong = here,
            type = TRACKPOINT_TYPE_TRACKPOINT,
            speed = speed,
            time = time,
            altitude = alt,
            heartRate = heartRate,
            cadence = cadence,
            power = power,
            bearing = bearingDeg,
        )
        currentSegment.add(point)
        lastLatLong = here
        lastPointTime = time
        return point
    }

    fun pause(time: Instant) {
        if (paused || finished) return
        accumulatedActive += activeDuration(time)
        activeSince = null
        paused = true
        closeSegment()
    }

    fun resume(time: Instant) {
        if (!paused || finished) return
        activeSince = time
        paused = false
        // New segment starts on the next accepted fix; reset the leg baseline so the gap
        // between pause and resume doesn't count as distance.
        lastLatLong = null
        lastPointTime = null
    }

    fun stop(time: Instant) {
        if (finished) return
        accumulatedActive += activeDuration(time)
        activeSince = null
        finished = true
        closeSegment()
    }

    fun snapshot(now: Instant): RecorderState {
        val total = accumulatedActive + activeDuration(now)
        val totalSec = total.inWholeMilliseconds / 1000.0
        val movingSec = movingTime.inWholeMilliseconds / 1000.0
        val stats = TrackStatistics(
            startTime = startedAt,
            stopTime = lastPointTime ?: startedAt,
            totalDistanceMeter = distanceM,
            totalTime = total,
            movingTime = movingTime,
            avgSpeedMeterPerSecond = if (totalSec > 0) distanceM / totalSec else null,
            avgMovingSpeedMeterPerSecond = if (movingSec > 0) distanceM / movingSec else null,
            maxSpeedMeterPerSecond = maxSpeedMs,
            minElevationMeter = minElevM ?: 0.0,
            maxElevationMeter = maxElevM ?: 0.0,
            elevationGainMeter = gainM,
            elevationLossMeter = lossM,
        )
        val segments = buildList {
            addAll(closedSegments)
            if (currentSegment.isNotEmpty()) add(currentSegment.toList())
        }
        return RecorderState(segments = segments, statistics = stats, isPaused = paused, isFinished = finished)
    }

    /**
     * When a fix is discarded by the jitter gate and the athlete is actually stopped, appends one
     * zero-speed point at the previous location so the HUD/charts show 0 instead of the last
     * moving speed. Adds no distance; emitted once per stop.
     */
    private fun markIdleIfStopped(speedMs: Double?, time: Instant) {
        if ((speedMs ?: 0.0) >= config.idleSpeedMs) return
        val prev = lastLatLong ?: return
        val last = currentSegment.lastOrNull() ?: return
        if (last.speed == 0.0) return
        val idlePoint = Trackpoint(
            trackId = 0L,
            id = seq++,
            latLong = prev,
            type = TRACKPOINT_TYPE_TRACKPOINT,
            speed = 0.0,
            time = time,
            altitude = smoothedAlt,
            heartRate = heartRate,
            cadence = cadence,
            power = power,
            bearing = null,
        )
        currentSegment.add(idlePoint)
        lastPointTime = time
    }

    private fun activeDuration(now: Instant): Duration =
        activeSince?.let { java.time.Duration.between(it, now).toKotlinDuration() } ?: Duration.ZERO

    private fun closeSegment() {
        if (currentSegment.isNotEmpty()) {
            closedSegments.add(currentSegment.toList())
            currentSegment.clear()
        }
    }

    private companion object {
        const val PRESSURE_SMOOTHING = 0.3
        const val PRESSURE_HYSTERESIS_M = 1.5
    }
}
