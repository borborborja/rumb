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
    /** Auto-lap every N metres of travel (runner splits). 0 = off. Mutually exclusive with the
     *  position/preset-line modes. */
    val autoLapEveryM: Double = 0.0,
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
    /**
     * How long the circuit's reference lap is (m), when racing a LAP competition. A crossing then
     * only counts as a lap once you have covered [LAP_MIN_COVERAGE] of it — [autoLapMinLapM] is
     * odometer travel, so wandering near the finish line satisfies it without going round at all.
     * 0 = unknown (an ad-hoc circuit with no reference): the old distance guard stands.
     */
    val lapRefDistanceM: Double = 0.0,
    /**
     * Detect a circuit from your movement and open laps at it, so you don't have to press the flag.
     * Rides on [autoLapByPosition] (it seeds the same machinery); a competition's preset line and
     * distance splits both win over it.
     */
    val autoDetectLoop: Boolean = false,
)

/**
 * The auto-lap fields after applying the "Lap management" master switch, so it can gate every
 * automatic-lap path from one place. A circuit competition laps at its [RecorderConfig.presetLapLine]
 * meta, which lives outside these fields, so it stays lapping even with the switch off — entering it
 * is an explicit "race laps". Pure so the prefs→config mapping is unit-testable, unlike the Service
 * method that reads it.
 */
data class AutoLapPrefs(val byPosition: Boolean, val everyM: Double, val detectLoop: Boolean) {
    companion object {
        fun resolve(
            lapManagement: Boolean,
            circuit: Boolean,
            byPosition: Boolean,
            everyM: Float,
            detectLoop: Boolean,
        ): AutoLapPrefs = if (!lapManagement) {
            AutoLapPrefs(byPosition = false, everyM = 0.0, detectLoop = false)
        } else {
            // Distance splits are off during a circuit (the meta owns the laps there); loop detection
            // too. This branch preserves the pre-switch behaviour exactly.
            AutoLapPrefs(byPosition, if (circuit) 0.0 else everyM.toDouble(), detectLoop && !circuit)
        }
    }
}

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
    /**
     * The start/finish line in force, and the radius that counts as crossing it. Null when laps
     * aren't line-driven. Published because the viewer needs it: its lap countdown used to read the
     * line from the competition prefs, which meant it could never work for laps round your own line
     * — those live only in here.
     */
    val lapLine: GeoPoint? = null,
    val lapLineRadiusM: Double = 0.0,
    /**
     * Length (m) of a loop the engine just auto-detected, on the first snapshot after it fires;
     * null otherwise. The viewer announces it once — without it the detection is invisible magic and
     * you can't tell a real 780 m loop from a spurious 1.5 km one.
     */
    val detectedLoopM: Double? = null,
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
    /** Next exact multiple of [RecorderConfig.autoLapEveryM] at which a distance split is due. */
    private var nextSplitDistanceM = Double.MAX_VALUE

    // Loop autodetection. Built lazily on the first fix that qualifies, dropped the moment it fires.
    private var loopDetector: LoopDetector? = null
    private var justDetectedLoopM: Double? = null // carried to the next snapshot, then cleared

    fun start(time: Instant) {
        check(startedAt == null) { "already started" }
        startedAt = time
        activeSince = time
        DebugLog.i(
            "Motor",
            "start · acc≤${config.maxAccuracyM}m minDist=${config.minDistanceM}m " +
                "warm-up=${config.startGoodFixes}×≤${config.startAccuracyM}m jitter=acc×${config.jitterFactor}",
        )
        // Distance splits open the lap block immediately: a runner's lap 1 is 0→1 km. Waiting for a
        // manual first press would make lap 1 span km1→km2 (startLaps rebases lapStartDistanceM).
        if (config.autoLapEveryM > 0) startLaps(time)
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
        // The loop-detected banner rides on one snapshot (the publish right after the fix that
        // fired); clear it as the next fix arrives so it announces once, not forever.
        justDetectedLoopM = null
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

        // Distance auto-lap (runner splits): a lap every N metres of travel.
        //
        // Boundaries are tracked as exact multiples in [nextSplitDistanceM] rather than rebasing on
        // each split: split() sets lapStartDistanceM to the CURRENT distance, so a fix landing at
        // 1005 m would push every later boundary to 2005, 3005... — ~5 m of drift per lap, a couple
        // of hundred metres by the end of a marathon. Only ONE split per fix: two laps between two
        // consecutive points would map to the same point index and be dropped by Laps.fromMarks, so
        // a fix that jumps over several multiples skips them instead of forging empty laps.
        if (config.autoLapEveryM > 0 && lapsActive && distanceM >= nextSplitDistanceM) {
            split(time)
            while (nextSplitDistanceM <= distanceM) nextSplitDistanceM += config.autoLapEveryM
        }

        // Loop autodetection: seed the free-lap machinery when a circuit is detected, then get out.
        // It equals "the user pressed the flag at P0, one lap ago", so everything after is the same
        // path onLapLineProximity already handles — no fourth branch.
        maybeDetectLoop(point.id, here, time)

        // The start/finish line, whichever kind it is. Two nearly identical copies of this used to
        // live here — a circuit one and a free one — and that is exactly how the free one was left
        // half-fixed: the coverage rule and the abandoned-lap handling only ever reached the circuit.
        // One line, one state machine, one set of rules.
        activeLapLine()?.let { onLapLineProximity(it, here, time) }
        return point
    }

    /**
     * Runs the loop detector when armed, and on a match seeds laps RETROACTIVELY at the point the
     * loop actually started (P0). START goes there — not "now" — so the approach leg from home stays
     * APPROACH and lap 1 is the real loop; placing it now would swallow the whole first lap into the
     * approach and lose its time. And SPLIT goes at P1: you are already a lap past P0, so lap 1 must
     * close or the next crossing would make it a double lap. [requiredLapDistanceM] then reads the
     * loop's length straight off these two marks — no reference to carry around.
     */
    private fun maybeDetectLoop(seq: Long, here: GeoPoint, time: Instant) {
        if (!config.autoDetectLoop || !config.autoLapByPosition ||
            config.presetLapLine != null || config.autoLapEveryM > 0.0 ||
            lapsActive || lapsEnded
        ) {
            return
        }
        val det = loopDetector ?: LoopDetector().also { loopDetector = it }
        val match = det.onFix(seq, here, distanceM, totalMs(time)) ?: return
        loopDetector = null
        startLapsAt(match.startSeq, match.startPoint, match.startDistM, match.startTotalMs)
        splitAt(match.closeSeq, match.closeDistM, match.closeTotalMs)
        justDetectedLoopM = match.lapLengthM
        DebugLog.i("Motor", "bucle detectat · ${fmt(match.lapLengthM)}m · línia al punt #${match.startSeq}")
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

    /**
     * The start/finish line in force, or null when laps aren't line-driven. A circuit's preset line
     * wins: it is the competition's, and it is armed even before the block opens so the first
     * crossing can open it. Otherwise it's the line captured when the lap block started, and only if
     * the user asked for position auto-lap.
     */
    private fun activeLapLine(): GeoPoint? =
        config.presetLapLine ?: lapLine?.takeIf { config.autoLapByPosition && lapsActive }

    /**
     * Proximity-gate state machine for the finish line, shared by circuits and free laps. Armed once
     * we leave the radius; a re-entry while armed closes the lap — or, if you didn't go round,
     * abandons it. The GPS filters upstream already ran, so [here] is clean.
     */
    private fun onLapLineProximity(line: GeoPoint, here: GeoPoint, time: Instant) {
        val d = MetricsCalculator.distanceMeters(here, line)
        if (d > config.autoLapRadiusM) {
            lapLineArmed = true
            return
        }
        if (!lapLineArmed) return
        if (!lapsActive) {
            // Circuit only (a free line exists only while the block is open). After an explicit
            // End-Laps, don't auto-open a new block just by crossing the line again — e.g. riding
            // out of the circuit past the meta on the way home.
            if (!lapsEnded) {
                DebugLog.i("Motor", "creuament: obre vueltas")
                startLaps(time)
                lapLineArmed = false
            }
            return
        }
        // Disarm on EVERY outcome below. Left armed after a rejected crossing, the machine sits hot
        // on the line and fires on the next fix that happens to clear the guards.
        if (totalMs(time) - lapStartTotalMs < config.autoLapMinLapMs) {
            lapLineArmed = false
            return
        }
        val sinceM = distanceM - lapStartDistanceM
        if (sinceM >= requiredLapDistanceM()) {
            DebugLog.i("Motor", "creuament meta (${fmt(d)}m)")
            split(time)
        } else {
            abortLap(time, sinceM)
        }
        lapLineArmed = false
    }

    /**
     * How far you must have travelled for a crossing to close a lap. The point is "did you go
     * round", which the flat [autoLapMinLapM] cannot tell: it counts odometer metres, so 100 m of
     * wandering by the finish line passes it and a 5% lap of a 2 km circuit counted the same as a
     * full one.
     *
     * The yardstick is the reference lap when racing one. Without it — free laps round your own
     * line — the FIRST lap you complete becomes the yardstick for the rest: exact by subtraction,
     * since every mark carries the cumulative odometer. Lap 1 itself has nothing to be measured
     * against, so it keeps the old absolute guard; that never rejects more than today does.
     */
    private fun requiredLapDistanceM(): Double {
        if (config.lapRefDistanceM > 0) return config.lapRefDistanceM * LAP_MIN_COVERAGE
        val opens = lapMarks.filter { it.type.opensLap }
        if (opens.size >= 2) {
            val firstLapM = opens[1].distanceM - opens[0].distanceM
            if (firstLapM > 0) return firstLapM * LAP_MIN_COVERAGE
        }
        return config.autoLapMinLapM
    }

    /**
     * Crossing the line without having gone round: the lap is abandoned, not completed. It doesn't
     * count and doesn't split — you're back at the start, so the lap simply begins again here. The
     * abandoned stretch is marked so it can be told apart from a real lap when the track is saved.
     */
    private fun abortLap(now: Instant, coveredM: Double) {
        lapMarks.add(LapMark(seq, distanceM, totalMs(now), LapMarkType.ABORT))
        lapStartDistanceM = distanceM
        lapStartTotalMs = totalMs(now)
        DebugLog.i(
            "Motor",
            "circuit: vuelta $lapCount abandonada · ${fmt(coveredM)}/${fmt(requiredLapDistanceM())}m · reinicia",
        )
    }

    /** Closes the current lap and opens the next, at the current fix. */
    private fun split(now: Instant) = splitAt(seq, distanceM, totalMs(now))

    /**
     * Closes the current lap and opens the next at a GIVEN boundary, which loop detection places
     * retroactively at an earlier point. `seq` here is that point's own id, whereas [split] passes
     * the field `seq` (already incremented → the next point's id); under [Laps.fromMarks]' "first
     * point with seq ≥ mark.seq" rule both land correctly, one point (~10 m) apart.
     */
    private fun splitAt(atSeq: Long, atDistM: Double, atTotalMs: Long) {
        lastLapMs = atTotalMs - lapStartTotalMs
        lapMarks.add(LapMark(atSeq, atDistM, atTotalMs, LapMarkType.SPLIT))
        lapCount++
        lapStartDistanceM = atDistM
        lapStartTotalMs = atTotalMs
        DebugLog.i("Motor", "vuelta $lapCount · última ${lastLapMs}ms")
    }

    /** Explicitly begins the lap block at the current fix (also invoked by the first [lap] press). */
    fun startLaps(now: Instant) = startLapsAt(seq, lastLatLong, distanceM, totalMs(now))

    /**
     * Begins the lap block at a GIVEN point. Loop detection calls this with P0 — the point the loop
     * started, one lap back — so the finish line and lap 1 are anchored there, not at the fix that
     * happened to confirm the loop.
     */
    private fun startLapsAt(atSeq: Long, atPoint: GeoPoint?, atDistM: Double, atTotalMs: Long) {
        if (finished || lapsActive) return
        lapsActive = true
        lapCount = 1
        lastLapMs = null
        lapStartDistanceM = atDistM
        lapStartTotalMs = atTotalMs
        lapMarks.add(LapMark(atSeq, atDistM, atTotalMs, LapMarkType.START))
        // First distance boundary, measured from where the block actually opened.
        nextSplitDistanceM = if (config.autoLapEveryM > 0) atDistM + config.autoLapEveryM else Double.MAX_VALUE
        // Position auto-lap: this spot is the start/finish line. Start disarmed so the first crossing
        // can't fire until we've left the radius (and past the min-lap guards).
        lapLine = atPoint
        lapLineArmed = false
        lapsEnded = false
        DebugLog.i("Motor", "vueltas iniciadas · vuelta 1" + if (config.autoLapByPosition) " · línia auto @${lapLine != null}" else "")
    }

    /** Ends the lap block: closes the current lap; what follows is the return (not a lap). */
    fun endLaps(now: Instant) {
        if (finished || !lapsActive) return
        // Whenever a line owns the laps — a competition's preset OR a free/detected line — a lap ends
        // by crossing the meta, not by pressing the button. Anchor END to the last crossing so the
        // stretch from meta to here is RETURN, not a lap. Manual laps (no line) end here, at "now":
        // there's no meta, your press defines them.
        if (activeLapLine() != null) { endCircuitLapsAtLastCrossing(); return }
        lastLapMs = totalMs(now) - lapStartTotalMs
        lapMarks.add(LapMark(seq, distanceM, totalMs(now), LapMarkType.END))
        lapsActive = false
        lapsEnded = true
        lapLine = null
        lapLineArmed = false
        DebugLog.i("Motor", "fin de vueltas · $lapCount vueltas")
    }

    /**
     * Circuit-only lap finalisation: the open lap was started at the LAST meta crossing (the last
     * opening mark). Placing END on that same seq drops the still-open partial (meta→here) from
     * the lap count — [Laps.fromMarks] turns it into a RETURN instead. Idempotent via [lapsActive].
     */
    private fun endCircuitLapsAtLastCrossing() {
        if (!lapsActive) return
        val lastCrossing = lapMarks.lastOrNull { it.type.opensLap }
        if (lastCrossing != null) {
            lapMarks.add(LapMark(lastCrossing.seq, lastCrossing.distanceM, lastCrossing.totalMs, LapMarkType.END))
        }
        lapsActive = false
        lapsEnded = true
        lapLine = null
        lapLineArmed = false
        DebugLog.i("Motor", "circuit: fin de vueltas en la meta · última al seq=${lastCrossing?.seq}")
    }

    /**
     * Reloads lap state after a crash (marks persisted alongside the points). Call after [restore],
     * which puts the points and the odometer back — this needs both.
     *
     * Everything the block needs to keep RUNNING has to come back here, not just what the HUD shows.
     * Restoring the counters but not the machinery left a recording that looked alive and had quietly
     * stopped lapping: `lapsActive` was true, the tile kept ticking, and no crossing ever fired again.
     */
    fun restoreLaps(marks: List<LapMark>) {
        if (marks.isEmpty()) return
        lapMarks.clear(); lapMarks.addAll(marks)
        val opens = marks.count { it.type == LapMarkType.START || it.type == LapMarkType.SPLIT }
        lapCount = opens
        val last = marks.last()
        lapsActive = last.type != LapMarkType.END
        lapsEnded = last.type == LapMarkType.END // or a crossing would re-open a block you closed
        lapStartDistanceM = last.distanceM
        lapStartTotalMs = last.totalMs
        // Recover the last COMPLETED lap's duration so the "last lap" tile isn't blank until the next
        // split: it's the gap between the last two boundary marks. An ABORT ends an abandoned attempt,
        // never a lap, so a block ending in one has no last lap to show.
        if (marks.size >= 2 && last.type != LapMarkType.ABORT) {
            lastLapMs = last.totalMs - marks[marks.size - 2].totalMs
        }
        // The free-lap finish line. It isn't in the marks — it's wherever the block opened — so find
        // the point that opened it. A mark's seq is the id the NEXT point will take, so the line is
        // the point just before it; fall back to the one just after, since points buffered when the
        // process died may never have reached the database.
        if (lapsActive) {
            val startSeq = marks.firstOrNull { it.type.opensLap }?.seq
            if (startSeq != null) {
                val pts = closedSegments.flatten() + currentSegment
                lapLine = (pts.lastOrNull { it.id < startSeq } ?: pts.firstOrNull { it.id >= startSeq })?.latLong
            }
            lapLineArmed = false // re-arms on the first fix outside the radius
        }
        // Distance splits track absolute multiples from where the block opened, so rebuild the next
        // boundary from that base rather than from here — otherwise every later split would be
        // measured from the crash point and drift.
        val base = marks.firstOrNull { it.type.opensLap }?.distanceM
        nextSplitDistanceM = if (config.autoLapEveryM > 0 && lapsActive && base != null) {
            var next = base + config.autoLapEveryM
            while (next <= distanceM) next += config.autoLapEveryM
            next
        } else {
            Double.MAX_VALUE
        }
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
        // Line-owned laps: if the user finishes without "end laps" (or a few seconds after the last
        // meta), close the block at that crossing so the trailing stretch is a RETURN, not a lap.
        if (activeLapLine() != null && lapsActive) endCircuitLapsAtLastCrossing()
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
            lapLine = activeLapLine(),
            lapLineRadiusM = config.autoLapRadiusM,
            detectedLoopM = justDetectedLoopM,
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

        /**
         * Fraction of the reference lap you must cover for a crossing to count. Not 0.9 like a ROUTE
         * attempt: a lap is raced by feel around a line you keep re-crossing, and a GPS-trimmed
         * corner shouldn't void an honest lap. Rejecting an abandoned one is what matters here.
         */
        const val LAP_MIN_COVERAGE = 0.8
    }
}
