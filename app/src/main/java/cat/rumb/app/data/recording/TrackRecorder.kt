package cat.rumb.app.data.recording

import cat.rumb.app.data.debug.DebugLog
import cat.rumb.app.data.opentracks.model.GeoPoint
import cat.rumb.app.data.opentracks.model.Segment
import cat.rumb.app.data.opentracks.model.TRACKPOINT_TYPE_TRACKPOINT
import cat.rumb.app.data.opentracks.model.TrackStatistics
import cat.rumb.app.data.opentracks.model.Trackpoint
import cat.rumb.app.viewer.hud.MetricsCalculator
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
    /** GPS warm-up: good fixes required before the first point is accepted (not strictly consecutive). */
    val startGoodFixes: Int = 2,
    /** Safety net: after this long waiting for the warm-up, relax the gate up to [maxAccuracyM]. */
    val relaxAfterMs: Long = 20_000,
    /**
     * Stationary-jitter gate: a leg shorter than accuracy × this factor is movement within the
     * fix's own uncertainty circle and is discarded (prevents phantom distance while stopped).
     */
    val jitterFactor: Double = 0.5,
    /**
     * Position auto-lap: when true, pressing "start laps" records the current position as the
     * start/finish line, and each time the athlete crosses back within [autoLapRadiusM] a SPLIT is
     * inserted automatically (same effect as the manual flag). Manual laps still work.
     */
    val autoLapByPosition: Boolean = false,
    /** Proximity gate for crossing the start/finish line (m). */
    val autoLapRadiusM: Double = 25.0,
    /** A crossing only counts after the current lap has run at least this long (ms) — anti re-trigger. */
    val autoLapMinLapMs: Long = 20_000,
    /** …and at least this far (m). Both guards must pass. */
    val autoLapMinLapM: Double = 100.0,
    /**
     * Circuit mode: a PRESET start/finish line (from a saved circuit), armed from the start even
     * before lapsActive. The first crossing opens lap 1 (the approach stays APPROACH); later crossings
     * split. Mutually exclusive with the manual [autoLapByPosition] path.
     */
    val presetLapLine: GeoPoint? = null,
)

/** Immutable snapshot of an ongoing/finished native recording, consumed by the viewer pipeline. */
data class RecorderState(
    val segments: List<Segment> = emptyList(),
    val statistics: TrackStatistics = TrackStatistics(),
    val isPaused: Boolean = false,
    val isFinished: Boolean = false,
    /** True when the first point was accepted only after relaxing the precision gate (poor GPS). */
    val startedLowAccuracy: Boolean = false,
    /** Laps: active state, count, current-lap deltas, last completed lap, and the boundary marks. */
    val lapsActive: Boolean = false,
    val lapCount: Int = 0,
    val currentLapDistanceM: Double = 0.0,
    val currentLapTimeMs: Long = 0L,
    val lastLapMs: Long? = null,
    val lapMarks: List<LapMark> = emptyList(),
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
    private var warmupFirstFixTime: Instant? = null
    private var startedLowAccuracy = false

    // Laps (manual, orthogonal to pause-segments). The approach before the first START is not a lap.
    private var lapsActive = false
    private var lapCount = 0
    private var lapStartDistanceM = 0.0
    private var lapStartTotalMs = 0L
    private var lastLapMs: Long? = null
    private val lapMarks = mutableListOf<LapMark>()

    // Position auto-lap: the start/finish line captured at startLaps, and an "armed" flag that is only
    // true after leaving the radius since the last split (so we don't re-fire while still on the line).
    private var lapLine: GeoPoint? = null
    private var lapLineArmed = false
    // True after an explicit End-Laps, so circuit auto-lap doesn't re-open a block on the next crossing.
    private var lapsEnded = false

    fun start(time: Instant) {
        check(startedAt == null) { "already started" }
        startedAt = time
        activeSince = time
        DebugLog.i(
            "Motor",
            "start · acc≤${config.maxAccuracyM}m minDist=${config.minDistanceM}m " +
                "warm-up=${config.startGoodFixes}×≤${config.startAccuracyM}m jitter=acc×${config.jitterFactor}",
        )
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
            if (!barometric) DebugLog.i("Motor", "baròmetre actiu: pren el control del desnivell")
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
        DebugLog.i("Motor", "restore · ${all.size} punts · ${fmt(distanceM)}m · warm-up=${if (warmedUp) "obert" else "pendent"}")
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
        if (accuracyM > config.maxAccuracyM) {
            DebugLog.d("Motor", "fix refusat: precisió ${fmt(accuracyM)}m > màx ${config.maxAccuracyM}m")
            return null
        }

        // Warm-up gate: the very first point of the track needs a stable, precise fix, otherwise
        // the cold-start scatter gets recorded as a zigzag with phantom distance. The gate relaxes
        // from startAccuracyM up to maxAccuracyM after relaxAfterMs so bad-sky conditions still
        // start eventually (flagged as low accuracy) instead of never recording. The good-fix
        // counter is monotonic — a worse fix doesn't reset progress, it just doesn't count.
        if (!warmedUp) {
            val firstFix = warmupFirstFixTime ?: time.also { warmupFirstFixTime = it }
            val waitedMs = java.time.Duration.between(firstFix, time).toMillis()
            val gate = if (waitedMs >= config.relaxAfterMs) config.maxAccuracyM else config.startAccuracyM
            if (accuracyM <= gate) {
                warmupGoodFixes++
                DebugLog.d("Motor", "warm-up: fix bo $warmupGoodFixes/${config.startGoodFixes} (${fmt(accuracyM)}m, gate ${fmt(gate)}m)")
            }
            if (warmupGoodFixes < config.startGoodFixes) return null
            warmedUp = true
            startedLowAccuracy = accuracyM > config.startAccuracyM
            DebugLog.i("Motor", "warm-up complet · GPS fixat (${fmt(accuracyM)}m)" + if (startedLowAccuracy) " · precisió baixa" else "")
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
            if (dtSec > 0 && legDistance / dtSec > config.maxImpliedSpeedMs) {
                DebugLog.w("Motor", "fix refusat: salt GPS ${fmt(legDistance / dtSec)} m/s (${fmt(legDistance)}m en ${fmt(dtSec)}s)")
                return null
            }
            // Jitter gate: ignore displacements below the min interval OR within the fix's own
            // uncertainty circle — while stopped, GPS scatter must not accumulate as distance.
            val jitterGate = maxOf(config.minDistanceM, accuracyM * config.jitterFactor)
            if (legDistance < jitterGate) {
                DebugLog.d("Motor", "fix refusat: jitter ${fmt(legDistance)}m < ${fmt(jitterGate)}m (acc ${fmt(accuracyM)}m)")
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
        DebugLog.d(
            "Motor",
            "punt #${point.id} · leg=${fmt(legDistance)}m v=${fmt(speed * 3.6)}km/h acc=${fmt(accuracyM)}m" +
                (alt?.let { " alt=${fmt(it)}m" } ?: "") +
                (heartRate?.let { " fc=${it.toInt()}" } ?: "") +
                (cadence?.let { " cad=${it.toInt()}" } ?: "") +
                (power?.let { " pot=${it.toInt()}W" } ?: "") +
                " · total=${fmt(distanceM)}m",
        )

        // Position auto-lap: proximity-gate state machine. Armed once we leave the radius; a re-entry
        // while armed and past the min-lap guards inserts a SPLIT (identical to a manual flag press),
        // then disarms until we leave again. The GPS filters above already ran, so `here` is clean.
        if (config.autoLapByPosition && lapsActive && config.presetLapLine == null) {
            lapLine?.let { line ->
                val d = MetricsCalculator.distanceMeters(here, line)
                if (d > config.autoLapRadiusM) {
                    lapLineArmed = true
                } else if (lapLineArmed) {
                    val sinceMs = totalMs(time) - lapStartTotalMs
                    val sinceM = distanceM - lapStartDistanceM
                    if (sinceMs >= config.autoLapMinLapMs && sinceM >= config.autoLapMinLapM) {
                        DebugLog.i("Motor", "auto-lap: creuament línia (${fmt(d)}m) · vuelta ${lapCount + 1}")
                        split(time)
                        lapLineArmed = false
                    }
                }
            }
        }

        // Circuit mode: a FIXED preset line, armed from the start. The FIRST crossing opens lap 1
        // (approach from home stays APPROACH); later crossings split. Guards only apply once a lap is
        // open, so the first crossing always fires.
        config.presetLapLine?.let { line ->
            val d = MetricsCalculator.distanceMeters(here, line)
            if (d > config.autoLapRadiusM) {
                lapLineArmed = true
            } else if (lapLineArmed) {
                val sinceMs = if (lapsActive) totalMs(time) - lapStartTotalMs else Long.MAX_VALUE
                val sinceM = if (lapsActive) distanceM - lapStartDistanceM else Double.MAX_VALUE
                if (sinceMs >= config.autoLapMinLapMs && sinceM >= config.autoLapMinLapM) {
                    // After an explicit End-Laps, don't auto-open a new block just by crossing the
                    // line again (e.g. riding out of the circuit).
                    if (lapsActive) { DebugLog.i("Motor", "circuit: creuament meta (${fmt(d)}m)"); split(time); lapLineArmed = false } else if (!lapsEnded) { DebugLog.i("Motor", "circuit: inici per creuament"); startLaps(time); lapLineArmed = false }
                }
            }
        }
        return point
    }

    private fun totalMs(now: Instant): Long = (accumulatedActive + activeDuration(now)).inWholeMilliseconds

    /**
     * Lap button: the first press starts lap 1 (the preceding stretch is approach, not counted);
     * every later press closes the current lap and opens the next. Boundary = the next point's seq.
     */
    fun lap(now: Instant) {
        if (finished) return
        if (!lapsActive) { startLaps(now); return }
        split(now)
    }

    /** Closes the current lap and opens the next. Shared by the manual flag and position auto-lap. */
    private fun split(now: Instant) {
        lastLapMs = totalMs(now) - lapStartTotalMs
        lapMarks.add(LapMark(seq, distanceM, totalMs(now), LapMarkType.SPLIT))
        lapCount++
        lapStartDistanceM = distanceM
        lapStartTotalMs = totalMs(now)
        DebugLog.i("Motor", "vuelta $lapCount · última ${lastLapMs}ms")
    }

    /** Explicitly begins the lap block (also invoked by the first [lap] press). */
    fun startLaps(now: Instant) {
        if (finished || lapsActive) return
        lapsActive = true
        lapCount = 1
        lastLapMs = null
        lapStartDistanceM = distanceM
        lapStartTotalMs = totalMs(now)
        lapMarks.add(LapMark(seq, distanceM, totalMs(now), LapMarkType.START))
        // Position auto-lap: the current spot is the start/finish line. Start disarmed so the first
        // crossing can't fire until we've left the radius (and past the min-lap guards).
        lapLine = lastLatLong
        lapLineArmed = false
        lapsEnded = false
        DebugLog.i("Motor", "vueltas iniciadas · vuelta 1" + if (config.autoLapByPosition) " · línia auto @${lapLine != null}" else "")
    }

    /** Ends the lap block: closes the current lap; what follows is the return (not a lap). */
    fun endLaps(now: Instant) {
        if (finished || !lapsActive) return
        lastLapMs = totalMs(now) - lapStartTotalMs
        lapMarks.add(LapMark(seq, distanceM, totalMs(now), LapMarkType.END))
        lapsActive = false
        lapsEnded = true
        lapLine = null
        lapLineArmed = false
        DebugLog.i("Motor", "fin de vueltas · $lapCount vueltas")
    }

    /** Reloads lap state after a crash (marks persisted alongside the points). */
    fun restoreLaps(marks: List<LapMark>) {
        if (marks.isEmpty()) return
        lapMarks.clear(); lapMarks.addAll(marks)
        val opens = marks.count { it.type == LapMarkType.START || it.type == LapMarkType.SPLIT }
        lapCount = opens
        val last = marks.last()
        lapsActive = last.type != LapMarkType.END
        lapStartDistanceM = last.distanceM
        lapStartTotalMs = last.totalMs
        // Recover the last completed lap's duration so the "last lap" tile isn't blank until the
        // next split: it's the gap between the last two boundary marks.
        if (marks.size >= 2) lastLapMs = last.totalMs - marks[marks.size - 2].totalMs
    }

    fun pause(time: Instant) {
        if (paused || finished) return
        accumulatedActive += activeDuration(time)
        activeSince = null
        paused = true
        closeSegment()
        DebugLog.i("Motor", "pausa · ${closedSegments.sumOf { it.size }} punts · ${fmt(distanceM)}m")
    }

    fun resume(time: Instant) {
        if (!paused || finished) return
        activeSince = time
        paused = false
        DebugLog.i("Motor", "reprendre · nou segment, línia base reiniciada")
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
        DebugLog.i(
            "Motor",
            "stop · ${closedSegments.sumOf { it.size }} punts · ${fmt(distanceM)}m · " +
                "+${fmt(gainM)}/-${fmt(lossM)}m · vmàx=${fmt(maxSpeedMs * 3.6)}km/h · baròmetre=$barometric",
        )
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
        return RecorderState(
            segments = segments, statistics = stats, isPaused = paused, isFinished = finished,
            startedLowAccuracy = startedLowAccuracy,
            lapsActive = lapsActive,
            lapCount = lapCount,
            currentLapDistanceM = if (lapsActive) (distanceM - lapStartDistanceM).coerceAtLeast(0.0) else 0.0,
            currentLapTimeMs = if (lapsActive) (total.inWholeMilliseconds - lapStartTotalMs).coerceAtLeast(0L) else 0L,
            lastLapMs = lastLapMs,
            lapMarks = lapMarks.toList(),
        )
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
        DebugLog.d("Motor", "parada detectada → marcador v=0 (#${idlePoint.id})")
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
    private fun fmt(v: Float): String = fmt(v.toDouble())

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
