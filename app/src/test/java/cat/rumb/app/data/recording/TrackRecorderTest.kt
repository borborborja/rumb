package cat.rumb.app.data.recording

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant

class TrackRecorderTest {

    private val t0: Instant = Instant.parse("2026-07-12T10:00:00Z")
    private fun at(sec: Long): Instant = t0.plusSeconds(sec)

    /** Feeds one precise fix so the warm-up gate (2 consecutive good fixes) opens on the next one. */
    private fun TrackRecorder.warmUp(lat: Double = 41.0, lon: Double = 2.0) {
        onLocation(lat, lon, 100.0, null, null, 5f, t0.minusSeconds(1))
    }

    /** ~0.001° of latitude ≈ 111 m; walk north at ~11 m/s with good accuracy. */
    private fun recorderWithLegs(count: Int, stepDeg: Double = 0.0001, stepSec: Long = 1): TrackRecorder {
        val r = TrackRecorder()
        r.start(t0)
        r.warmUp()
        for (i in 0..count) {
            r.onLocation(41.0 + i * stepDeg, 2.0, 100.0, null, null, 5f, at(i * stepSec))
        }
        return r
    }

    @Test
    fun acceptsGoodFixesAndAccumulatesDistance() {
        val r = recorderWithLegs(10) // 10 legs of ~11.1 m
        val s = r.snapshot(at(10))
        assertThat(s.points()).hasSize(11)
        assertThat(s.statistics.totalDistanceMeter).isEqualTo(111.3, within(2.0))
        assertThat(s.statistics.totalTime.inWholeSeconds).isEqualTo(10)
    }

    @Test
    fun rejectsInaccurateFixes() {
        val r = TrackRecorder()
        r.start(t0)
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 80f, at(0))).isNull() // accuracy 80 m > 25 m
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 10f, at(1))).isNull() // warm-up fix #1
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 10f, at(2))).isNotNull() // warm-up done
    }

    @Test
    fun warmupCountsGoodFixesMonotonically() {
        val r = TrackRecorder()
        r.start(t0)
        // A worse fix between two good ones no longer resets progress — it just doesn't count.
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 8f, at(0))).isNull() // good #1
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 20f, at(1))).isNull() // ignored, not reset
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 8f, at(2))).isNotNull() // good #2 → locked
        assertThat(r.snapshot(at(2)).points()).hasSize(1)
        assertThat(r.snapshot(at(2)).statistics.totalDistanceMeter).isEqualTo(0.0)
        assertThat(r.snapshot(at(2)).startedLowAccuracy).isFalse()
    }

    @Test
    fun safetyNetRelaxesGateAfter20sAndFlagsLowAccuracy() {
        val r = TrackRecorder()
        r.start(t0)
        // Only ~18 m fixes: rejected while the gate is 12 m (before 20 s).
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 18f, at(0))).isNull()
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 18f, at(5))).isNull()
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 18f, at(19))).isNull()
        // Past 20 s the gate relaxes to maxAccuracy (25 m): two 18 m fixes now warm up.
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 18f, at(21))).isNull() // good #1 (relaxed)
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 18f, at(22))).isNotNull() // good #2 → locked
        assertThat(r.snapshot(at(22)).startedLowAccuracy).isTrue()
    }

    @Test
    fun fixesWorseThanMaxAccuracyNeverEnterEvenRelaxed() {
        val r = TrackRecorder()
        r.start(t0)
        // 40 m fixes exceed maxAccuracy (25 m) — rejected outright, even long after the relax window.
        for (s in 0..40 step 2) assertThat(r.onLocation(41.0, 2.0, null, null, null, 40f, at(s.toLong()))).isNull()
        assertThat(r.snapshot(at(40)).points()).isEmpty()
    }

    @Test
    fun stationaryScatterWithinAccuracyAddsNoDistance() {
        val r = TrackRecorder()
        r.start(t0)
        r.warmUp()
        r.onLocation(41.0, 2.0, null, null, null, 5f, at(0)) // locked start
        // Accuracy then degrades to 20 m while stopped.
        // Jitter legs of ~5-9 m with 20 m accuracy: inside the uncertainty circle → all discarded.
        r.onLocation(41.00005, 2.0, null, null, null, 20f, at(1)) // ~5.6 m
        r.onLocation(41.00001, 2.0002 * 0 + 2.00008, null, null, null, 20f, at(2)) // ~7 m east-ish
        r.onLocation(41.00006, 2.0, null, null, null, 20f, at(3))
        val s = r.snapshot(at(3))
        assertThat(s.statistics.totalDistanceMeter).isEqualTo(0.0)
        // A real 15 m move (> 20 m × 0.5) is accepted.
        assertThat(r.onLocation(41.000135, 2.0, null, null, null, 20f, at(10))).isNotNull()
    }

    @Test
    fun idleMarkerZeroesSpeedWhenStopped() {
        val r = TrackRecorder()
        r.start(t0)
        r.warmUp()
        r.onLocation(41.0, 2.0, null, 5.0, null, 5f, at(0))
        r.onLocation(41.0001, 2.0, null, 5.0, null, 5f, at(1)) // moving at 5 m/s
        // Stopped: jitter fix with noise speed below idle → one zero-speed marker appears.
        r.onLocation(41.00010, 2.00001, null, 0.3, null, 5f, at(5))
        val pts = r.snapshot(at(5)).points()
        assertThat(pts.last().speed).isEqualTo(0.0)
        val count = pts.size
        // Further stationary fixes don't stack more markers.
        r.onLocation(41.00010, 2.00001, null, 0.2, null, 5f, at(6))
        assertThat(r.snapshot(at(6)).points()).hasSize(count)
    }

    @Test
    fun rejectsImpossibleJumps() {
        val r = TrackRecorder()
        r.start(t0)
        r.warmUp()
        r.onLocation(41.0, 2.0, null, null, null, 5f, at(0))
        // 0.01° ≈ 1113 m in 1 s → >50 m/s → GPS jump, rejected.
        assertThat(r.onLocation(41.01, 2.0, null, null, null, 5f, at(1))).isNull()
        assertThat(r.snapshot(at(2)).points()).hasSize(1)
    }

    @Test
    fun skipsIdleJitterByMinDistance() {
        val r = TrackRecorder()
        r.start(t0)
        r.warmUp()
        r.onLocation(41.0, 2.0, null, null, null, 5f, at(0))
        // ~1 m away → below the 3 m minimum → skipped.
        assertThat(r.onLocation(41.00001, 2.0, null, null, null, 5f, at(1))).isNull()
        assertThat(r.snapshot(at(1)).points()).hasSize(1)
    }

    @Test
    fun movingTimeExcludesIdle() {
        val r = TrackRecorder()
        r.start(t0)
        r.warmUp()
        r.onLocation(41.0, 2.0, null, null, null, 5f, at(0))
        r.onLocation(41.0001, 2.0, null, null, null, 5f, at(1)) // ~11 m/s → moving
        // 60 s later, 4 m away → implied 0.067 m/s → idle (below 0.5), accepted but not moving.
        r.onLocation(41.000135, 2.0, null, null, null, 5f, at(61))
        val s = r.snapshot(at(61))
        assertThat(s.statistics.movingTime.inWholeSeconds).isEqualTo(1)
        assertThat(s.statistics.totalTime.inWholeSeconds).isEqualTo(61)
    }

    @Test
    fun pauseSplitsSegmentsAndFreezesTime() {
        val r = recorderWithLegs(5)
        r.pause(at(5))
        r.resume(at(65)) // 60 s paused
        r.onLocation(41.001, 2.0, 100.0, null, null, 5f, at(66))
        r.onLocation(41.0011, 2.0, 100.0, null, null, 5f, at(67))
        val s = r.snapshot(at(67))
        assertThat(s.segments).hasSize(2) // pause split
        assertThat(s.statistics.totalTime.inWholeSeconds).isEqualTo(7) // 5 active + 2 after resume
        // The pause→resume gap must not count as distance (leg baseline reset).
        assertThat(s.statistics.totalDistanceMeter).isEqualTo(55.7 + 11.1, within(3.0))
    }

    @Test
    fun elevationGainUsesHysteresis() {
        val r = TrackRecorder(RecorderConfig(altitudeSmoothing = 1.0)) // no smoothing → direct deltas
        r.start(t0)
        r.warmUp()
        var alt = 100.0
        for (i in 0..9) {
            r.onLocation(41.0 + i * 0.0001, 2.0, alt, null, null, 5f, at(i.toLong()))
            alt += 1.0 // +1 m per point → accumulates past the 2 m gate
        }
        val s = r.snapshot(at(10))
        assertThat(s.statistics.elevationGainMeter).isEqualTo(8.0, within(2.1))
        assertThat(s.statistics.elevationLossMeter).isEqualTo(0.0, within(0.01))
    }

    @Test
    fun noiseBelowHysteresisDoesNotInflateGain() {
        val r = TrackRecorder(RecorderConfig(altitudeSmoothing = 1.0))
        r.start(t0)
        r.warmUp()
        // Alternating ±0.5 m noise around 100 m.
        for (i in 0..19) {
            val alt = if (i % 2 == 0) 100.0 else 100.5
            r.onLocation(41.0 + i * 0.0001, 2.0, alt, null, null, 5f, at(i.toLong()))
        }
        val s = r.snapshot(at(20))
        assertThat(s.statistics.elevationGainMeter).isEqualTo(0.0, within(0.01))
    }

    @Test
    fun sensorsAttachToPoints() {
        val r = TrackRecorder()
        r.start(t0)
        r.warmUp()
        r.onHeartRate(150.0)
        r.onCadence(85.0)
        r.onLocation(41.0, 2.0, null, null, null, 5f, at(0))
        val p = r.snapshot(at(0)).points().single()
        assertThat(p.heartRate).isEqualTo(150.0)
        assertThat(p.cadence).isEqualTo(85.0)
        assertThat(p.power).isNull()
    }

    @Test
    fun stopFinalizesState() {
        val r = recorderWithLegs(3)
        r.stop(at(3))
        val s = r.snapshot(at(100))
        assertThat(s.isFinished).isTrue()
        assertThat(s.isRecording).isFalse()
        assertThat(s.statistics.totalTime.inWholeSeconds).isEqualTo(3) // frozen at stop
        assertThat(r.onLocation(41.1, 2.0, null, null, null, 5f, at(101))).isNull()
    }

    @Test
    fun barometricGainOverridesGpsGain() {
        val r = TrackRecorder(RecorderConfig(altitudeSmoothing = 1.0))
        r.start(t0)
        // Prime the barometer: steady climb of ~8.4 m per hPa near sea level.
        r.onPressure(1013.25f)
        r.onPressure(1012.25f) // ≈ +8.4 m
        r.onPressure(1011.25f) // ≈ +8.4 m more (smoothed EMA, partial)
        // GPS altitude wildly noisy — must NOT count once barometric mode is on.
        r.warmUp()
        r.onLocation(41.0, 2.0, 100.0, null, null, 5f, at(0))
        r.onLocation(41.0001, 2.0, 150.0, null, null, 5f, at(1))
        val s = r.snapshot(at(2))
        // Gain comes from pressure only (a few metres), not the +50 m GPS spike.
        assertThat(s.statistics.elevationGainMeter).isLessThan(20.0)
        assertThat(s.statistics.elevationGainMeter).isGreaterThan(0.0)
    }

    @Test
    fun restoreRebuildsStatsAndContinues() {
        val original = recorderWithLegs(5) // 6 points, ~55.7 m
        val persisted = original.snapshot(at(5)).segments

        val restored = TrackRecorder()
        restored.restore(persisted, t0, resumeAt = at(120))
        val s0 = restored.snapshot(at(120))
        assertThat(s0.points()).hasSize(6)
        assertThat(s0.statistics.totalDistanceMeter).isEqualTo(55.7, within(2.0))

        // Continue recording: the crash gap adds no distance (baseline reset).
        restored.onLocation(41.01, 2.0, 100.0, null, null, 5f, at(121))
        restored.onLocation(41.0101, 2.0, 100.0, null, null, 5f, at(122))
        val s1 = restored.snapshot(at(122))
        assertThat(s1.points()).hasSize(8)
        assertThat(s1.statistics.totalDistanceMeter).isEqualTo(55.7 + 11.1, within(3.0))
    }

    // --- Position auto-lap ---

    /** Records an out-and-back loop around a line captured at startLaps. 0.0002° lat ≈ 22 m/step. */
    private fun autoLapLoop(cfg: RecorderConfig, outSteps: Int, stepDeg: Double = 0.0002): TrackRecorder {
        val r = TrackRecorder(cfg)
        r.start(t0)
        r.warmUp()
        r.onLocation(41.0, 2.0, 100.0, null, null, 5f, at(0)) // the line point
        r.startLaps(at(0))
        var sec = 1L
        for (i in 1..outSteps) { r.onLocation(41.0 + i * stepDeg, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        for (i in (outSteps - 1) downTo 0) { r.onLocation(41.0 + i * stepDeg, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        return r
    }

    @Test
    fun autoLapSplitsOnceWhenCrossingTheStartLine() {
        // Out ~333 m over 15 s, back to the line by ~30 s → both guards (20 s / 100 m) pass.
        val r = autoLapLoop(RecorderConfig(autoLapByPosition = true), outSteps = 15)
        val s = r.snapshot(at(60))
        assertThat(s.lapMarks.count { it.type == LapMarkType.SPLIT }).isEqualTo(1) // exactly one crossing
        assertThat(s.lapCount).isEqualTo(2)
        assertThat(s.lapMarks.none { it.type == LapMarkType.END }).isTrue()
    }

    @Test
    fun autoLapDoesNotFireBelowTheMinLapGuards() {
        // Out ~44 m over 2 s, back → leaves the 25 m radius (arms) but the lap is under 100 m / 20 s.
        val r = autoLapLoop(RecorderConfig(autoLapByPosition = true), outSteps = 2)
        assertThat(r.snapshot(at(30)).lapMarks.none { it.type == LapMarkType.SPLIT }).isTrue()
    }

    @Test
    fun autoLapOffLeavesLapsManualOnly() {
        val r = autoLapLoop(RecorderConfig(autoLapByPosition = false), outSteps = 15)
        assertThat(r.snapshot(at(60)).lapMarks.none { it.type == LapMarkType.SPLIT }).isTrue()
        assertThat(r.snapshot(at(60)).lapCount).isEqualTo(1)
    }

    /** Free laps around the line captured at startLaps: one out-and-back per entry of [outSteps]. */
    private fun autoLapLaps(cfg: RecorderConfig, vararg outSteps: Int): TrackRecorder {
        val r = TrackRecorder(cfg)
        r.start(t0)
        r.warmUp()
        r.onLocation(41.0, 2.0, 100.0, null, null, 5f, at(0)) // the line point
        r.startLaps(at(0))
        var sec = 1L
        outSteps.forEach { steps ->
            for (i in 1..steps) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
            for (i in (steps - 1) downTo 0) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        }
        return r
    }

    /** Free laps, isolating the coverage guard from the anti-re-trigger time guard. */
    private fun freeCfg() = RecorderConfig(autoLapByPosition = true, autoLapMinLapMs = 0)

    @Test
    fun freeLapsAbandonedLapDoesNotCountEither() {
        // The reported bug, away from any competition. Lap 1 is ~667 m and becomes the yardstick
        // (bar: ~534 m). Lap 2 gives up 6 steps out and comes back: ~267 m of travel — which CLEARS
        // the old flat 100 m odometer guard, so the old code counted it as a full lap. That is the
        // whole point of the yardstick: 40% of a lap is not a lap.
        val r = autoLapLaps(freeCfg(), 15, 6)
        val s = r.snapshot(at(120))
        assertThat(s.lapCount).isEqualTo(2) // still on lap 2, not counted into lap 3
        assertThat(s.lapMarks.count { it.type == LapMarkType.SPLIT }).isEqualTo(1)
        assertThat(s.lapMarks.count { it.type == LapMarkType.ABORT }).isEqualTo(1) // exactly one: it disarms
    }

    @Test
    fun freeLapsSecondLapThatGoesRoundCounts() {
        val r = autoLapLaps(freeCfg(), 15, 15)
        val s = r.snapshot(at(120))
        assertThat(s.lapCount).isEqualTo(3)
        assertThat(s.lapMarks.count { it.type == LapMarkType.SPLIT }).isEqualTo(2)
        assertThat(s.lapMarks.none { it.type == LapMarkType.ABORT }).isTrue()
    }

    @Test
    fun freeLapsFirstLapHasNoYardstickSoItKeepsTheOldGuard() {
        // Nothing to measure lap 1 against, so it falls back to autoLapMinLapM (100 m): ~220 m counts.
        // This is what stops the new rule from ever rejecting more than today's did.
        val r = autoLapLaps(freeCfg(), 5)
        assertThat(r.snapshot(at(60)).lapCount).isEqualTo(2)
        assertThat(r.snapshot(at(60)).lapMarks.none { it.type == LapMarkType.ABORT }).isTrue()
    }

    @Test
    fun theActiveLineIsPublishedForFreeLapsToo() {
        // The viewer's 3-2-1 needs the line, and a free lap's only exists in here. Before this it
        // read the competition prefs, which are (0,0) outside a competition — the Atlantic.
        val r = TrackRecorder(freeCfg())
        r.start(t0)
        r.warmUp()
        assertThat(r.snapshot(at(0)).lapLine).isNull() // no block open yet: nothing to count into
        r.onLocation(41.0, 2.0, 100.0, null, null, 5f, at(0))
        r.startLaps(at(0))
        val s = r.snapshot(at(1))
        assertThat(s.lapLine?.latitude).isEqualTo(41.0, within(0.0001))
        assertThat(s.lapLineRadiusM).isEqualTo(freeCfg().autoLapRadiusM)
        r.endLaps(at(2))
        assertThat(r.snapshot(at(3)).lapLine).isNull() // block closed: the countdown must stop
    }

    @Test
    fun theActiveLineIsTheCompetitionsWhenRacingOne() {
        // A circuit's preset line wins, and is published from the very start — before the block
        // opens — because the first crossing is what opens it.
        val line = cat.rumb.app.data.opentracks.model.GeoPoint(41.0, 2.0)
        val r = TrackRecorder(RecorderConfig(presetLapLine = line, autoLapByPosition = true))
        r.start(t0)
        r.warmUp()
        assertThat(r.snapshot(at(0)).lapLine).isEqualTo(line)
    }

    @Test
    fun manualFlagAlwaysCountsHoweverShort() {
        // The coverage rule lives in the line's proximity gate, never in split(): the flag is the
        // user saying "a lap ends here", and a 40 m sprint marker is a legitimate thing to want.
        val r = TrackRecorder(freeCfg())
        r.start(t0)
        r.warmUp()
        r.onLocation(41.0, 2.0, 100.0, null, null, 5f, at(0))
        r.lap(at(0)) // first press opens the block
        r.onLocation(41.0002, 2.0, 100.0, null, null, 5f, at(1)) // ~22 m
        r.onLocation(41.0004, 2.0, 100.0, null, null, 5f, at(2)) // ~44 m
        r.lap(at(3)) // second press closes it, whatever the odometer says
        val s = r.snapshot(at(10))
        assertThat(s.lapCount).isEqualTo(2)
        assertThat(s.lapMarks.count { it.type == LapMarkType.SPLIT }).isEqualTo(1)
        assertThat(s.lapMarks.none { it.type == LapMarkType.ABORT }).isTrue()
    }

    @Test
    fun circuitDoesNotReopenLapsAfterEndLaps() {
        val line = cat.rumb.app.data.opentracks.model.GeoPoint(41.0, 2.0)
        val r = TrackRecorder(RecorderConfig(presetLapLine = line))
        r.start(t0)
        r.warmUp()
        var sec = 0L
        // Approach + first crossing opens lap 1.
        for (i in 15 downTo 0) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        // End the block, then ride out and cross the line again.
        r.endLaps(at(sec))
        val lapsAtEnd = r.snapshot(at(sec)).lapCount
        for (i in 1..15) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        for (i in 14 downTo 0) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        val s = r.snapshot(at(sec))
        assertThat(s.lapsActive).isFalse()
        assertThat(s.lapCount).isEqualTo(lapsAtEnd) // no new block reopened
        assertThat(s.lapMarks.count { it.type == LapMarkType.START }).isEqualTo(1)
    }

    @Test
    fun circuitPresetLineFirstCrossingOpensLapOneThenSplits() {
        // Fixed finish line at (41.0, 2.0). Start far north (armed), approach and cross → START (lap 1).
        val line = cat.rumb.app.data.opentracks.model.GeoPoint(41.0, 2.0)
        val r = TrackRecorder(RecorderConfig(presetLapLine = line))
        r.start(t0)
        r.warmUp()
        var sec = 0L
        for (i in 15 downTo 0) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        val s1 = r.snapshot(at(sec))
        assertThat(s1.lapsActive).isTrue()
        assertThat(s1.lapCount).isEqualTo(1)
        assertThat(s1.lapMarks.count { it.type == LapMarkType.START }).isEqualTo(1)
        assertThat(s1.lapMarks.none { it.type == LapMarkType.SPLIT }).isTrue()
        // A full lap: leave (re-arm) and come back to the line → one SPLIT (lap 2).
        for (i in 1..15) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        for (i in 14 downTo 0) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        val s2 = r.snapshot(at(sec))
        assertThat(s2.lapCount).isEqualTo(2)
        assertThat(s2.lapMarks.count { it.type == LapMarkType.SPLIT }).isEqualTo(1)
    }

    /**
     * Circuit out-and-back: approach from the north and cross (opens lap 1), then ride [outSteps]
     * out and back to the line. 0.0002° lat ≈ 22 m/step, so a lap is ~44·outSteps metres.
     */
    private fun circuitLap(cfg: RecorderConfig, outSteps: Int): TrackRecorder {
        val r = TrackRecorder(cfg)
        r.start(t0)
        r.warmUp()
        var sec = 0L
        for (i in 15 downTo 0) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        for (i in 1..outSteps) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        for (i in (outSteps - 1) downTo 0) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        return r
    }

    /** ~666 m lap, reference 600 m → 111% covered, well past the 80% bar. */
    private fun circuitCfg(refM: Double) = RecorderConfig(
        presetLapLine = cat.rumb.app.data.opentracks.model.GeoPoint(41.0, 2.0),
        lapRefDistanceM = refM,
        autoLapMinLapMs = 0, // isolate the coverage guard from the anti-re-trigger time guard
    )

    @Test
    fun circuitAbandonedLapDoesNotCountAndRestarts() {
        // The reported bug: start lap 3, give up ~44 m out, come back to the line → it counted lap 4.
        // 88 m of travel against a 600 m reference is 15% of a lap, so it is not a lap.
        val r = circuitLap(circuitCfg(refM = 600.0), outSteps = 2)
        val s = r.snapshot(at(60))
        assertThat(s.lapCount).isEqualTo(1) // still on lap 1, not counted into lap 2
        assertThat(s.lapMarks.none { it.type == LapMarkType.SPLIT }).isTrue()
        assertThat(s.lapMarks.count { it.type == LapMarkType.ABORT }).isEqualTo(1)
    }

    @Test
    fun circuitLapThatGoesRoundCounts() {
        val r = circuitLap(circuitCfg(refM = 600.0), outSteps = 15)
        val s = r.snapshot(at(60))
        assertThat(s.lapCount).isEqualTo(2)
        assertThat(s.lapMarks.count { it.type == LapMarkType.SPLIT }).isEqualTo(1)
        assertThat(s.lapMarks.none { it.type == LapMarkType.ABORT }).isTrue()
    }

    @Test
    fun circuitAbortedLapRestartsFromTheCrossing() {
        // After an abort you are back at the start, so the retry is measured from the crossing — not
        // carrying the abandoned attempt, which would poison the lap time you actually race.
        // ~22 m here: like every crossing, it lands on the fix that ENTERS the 25 m radius, one step
        // short of the line itself. What matters is that the ~88 m given up are gone.
        val r = circuitLap(circuitCfg(refM = 600.0), outSteps = 2)
        assertThat(r.snapshot(at(60)).currentLapDistanceM).isLessThan(30.0)
    }

    @Test
    fun restoredFreeLapsStillCrossTheLine() {
        // The invisible one. restoreLaps brought the counters back but not the finish line, so after
        // a crash `lapsActive` was true, the tile kept ticking — and no crossing ever fired again.
        // The recording only LOOKED alive. Nothing could recover it: startLaps early-returns while
        // a block is open.
        val cfg = freeCfg()
        val snap = autoLapLaps(cfg, 15).snapshot(at(60)) // one lap done, block open, line at (41, 2)

        val restored = TrackRecorder(cfg)
        restored.restore(snap.segments, t0, resumeAt = at(120))
        restored.restoreLaps(snap.lapMarks)
        assertThat(restored.snapshot(at(120)).lapLine).isNotNull()

        var sec = 121L
        for (i in 1..15) { restored.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        for (i in 14 downTo 0) { restored.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        assertThat(restored.snapshot(at(sec)).lapCount).isEqualTo(3) // the lap after the crash counted
    }

    @Test
    fun restoredDistanceSplitsKeepFallingOnTheSameMultiples() {
        // Same class of bug: nextSplitDistanceM isn't in the marks, so km splits died at the crash.
        // And they must resume on the ORIGINAL multiples, not restart from wherever the crash was.
        val cfg = RecorderConfig(autoLapEveryM = 1000.0)
        val snap = runStraight(cfg, 1500).snapshot(at(200)) // START at 0, SPLIT at 1000

        val restored = TrackRecorder(cfg)
        restored.restore(snap.segments, t0, resumeAt = at(300))
        restored.restoreLaps(snap.lapMarks)

        var sec = 301L
        repeat(60) { restored.onLocation(41.0 + (135 + it) * 0.0001, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        val s = restored.snapshot(at(sec))
        assertThat(s.lapMarks.count { it.type == LapMarkType.SPLIT }).isEqualTo(2) // the 2 km one fell
        val second = s.lapMarks.last { it.type == LapMarkType.SPLIT }
        assertThat(second.distanceM).isBetween(2000.0, 2015.0) // on the multiple, not rebased
    }

    @Test
    fun restoreAfterEndLapsDoesNotReopenOnACrossing() {
        // lapsEnded isn't in the marks either: without it a circuit crossing would re-open a block
        // the user explicitly closed — the very thing circuitDoesNotReopenLapsAfterEndLaps pins live.
        val cfg = circuitCfg(refM = 600.0)
        val original = circuitLap(cfg, outSteps = 15)
        original.endLaps(at(60))
        val snap = original.snapshot(at(60))

        val restored = TrackRecorder(cfg)
        restored.restore(snap.segments, t0, resumeAt = at(120))
        restored.restoreLaps(snap.lapMarks)
        val startsBefore = restored.snapshot(at(120)).lapMarks.count { it.type == LapMarkType.START }

        var sec = 121L
        for (i in 1..15) { restored.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        for (i in 14 downTo 0) { restored.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        val s = restored.snapshot(at(sec))
        assertThat(s.lapsActive).isFalse()
        assertThat(s.lapMarks.count { it.type == LapMarkType.START }).isEqualTo(startsBefore)
    }

    @Test
    fun restoreAfterAnAbortHasNoLastLapToShow() {
        // Crash-recovery reads the last completed lap off the last two marks. An ABORT ends an
        // abandoned attempt, so its duration must not surface as "last lap" in the HUD.
        val r = TrackRecorder(circuitCfg(refM = 600.0))
        r.restoreLaps(
            listOf(
                LapMark(10, 100.0, 60_000, LapMarkType.START),
                LapMark(20, 150.0, 90_000, LapMarkType.ABORT),
            ),
        )
        assertThat(r.snapshot(at(120)).lastLapMs).isNull()
    }

    @Test
    fun circuitWithoutAReferenceKeepsTheOldDistanceGuard() {
        // An ad-hoc circuit has no lap to compare against: fall back to autoLapMinLapM (100 m), so a
        // ~666 m lap still counts and nothing regresses for people not racing a competition.
        val r = circuitLap(circuitCfg(refM = 0.0), outSteps = 15)
        assertThat(r.snapshot(at(60)).lapCount).isEqualTo(2)
    }

    // --- Distance auto-lap (runner splits) ---

    /** Runs [meters] north in ~11 m steps (0.0001° lat), one fix per second. */
    private fun runStraight(cfg: RecorderConfig, meters: Int): TrackRecorder {
        val r = TrackRecorder(cfg)
        r.start(t0)
        r.warmUp()
        var sec = 0L
        val steps = meters / 11
        for (i in 0..steps) { r.onLocation(41.0 + i * 0.0001, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        return r
    }

    @Test
    fun distanceAutoLapSplitsEveryKilometre() {
        val r = runStraight(RecorderConfig(autoLapEveryM = 1000.0), meters = 3_100)
        val s = r.snapshot(at(400))
        // Lap 1 opens at start (0 m), then a split at each km: 3 splits by 3.1 km.
        assertThat(s.lapMarks.count { it.type == LapMarkType.START }).isEqualTo(1)
        assertThat(s.lapMarks.count { it.type == LapMarkType.SPLIT }).isEqualTo(3)
        // Lap 1 must be 0→1 km, not 1→2 km: the block opens at start(), not on the first crossing,
        // so there is no APPROACH stretch.
        val ranges = cat.rumb.app.data.tracks.Laps.fromMarks(s.lapMarks, s.points().map { it.id })
        assertThat(ranges.none { it.kind == cat.rumb.app.data.tracks.LapKind.APPROACH }).isTrue()
        // 3 full km + the trailing 100 m, which a runner DOES want as a final partial lap (any watch
        // shows it). Note this is the opposite of circuit mode, where the stretch after the last meta
        // is a RETURN, not a lap — there the finish line owns the boundary; here distance does.
        val laps = ranges.filter { it.kind == cat.rumb.app.data.tracks.LapKind.LAP }
        assertThat(laps).hasSize(4)
        assertThat(laps.first().startIdx).isEqualTo(0)
    }

    /** Boundaries are exact multiples, so a split landing past the mark can't drift the next one. */
    @Test
    fun distanceAutoLapBoundariesDoNotDrift() {
        val r = runStraight(RecorderConfig(autoLapEveryM = 1000.0), meters = 5_100)
        val splits = r.snapshot(at(600)).lapMarks.filter { it.type == LapMarkType.SPLIT }
        assertThat(splits).hasSize(5)
        // Each split fires just past its own km, never accumulating the previous overshoot.
        splits.forEachIndexed { i, mark ->
            val expected = (i + 1) * 1000.0
            assertThat(mark.distanceM).isBetween(expected, expected + 15.0)
        }
    }

    @Test
    fun distanceAutoLapOffChangesNothing() {
        val r = runStraight(RecorderConfig(autoLapEveryM = 0.0), meters = 3_100)
        val s = r.snapshot(at(400))
        assertThat(s.lapMarks).isEmpty()
        assertThat(s.lapsActive).isFalse()
    }

    @Test
    fun circuitStopAfterMetaEndsLapAtTheMetaNotAtStop() {
        // Two full laps (START + 2 crossings), then ride PAST the meta and stop a bit later. The lap
        // count must end at that last meta: the trailing meta→stop stretch is a RETURN, not a phantom lap.
        val line = cat.rumb.app.data.opentracks.model.GeoPoint(41.0, 2.0)
        val r = TrackRecorder(RecorderConfig(presetLapLine = line))
        r.start(t0)
        r.warmUp()
        var sec = 0L
        fun outAndBack() {
            for (i in 1..15) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
            for (i in 14 downTo 0) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        }
        // Approach + first crossing → START (lap 1 opens).
        for (i in 15 downTo 0) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        outAndBack() // crossing 2 → lap 1 done, lap 2 opens
        outAndBack() // crossing 3 → lap 2 done, lap 3 opens
        // Ride out past the meta without returning, then stop (the "finish a few seconds later" case).
        for (i in 1..8) { r.onLocation(41.0 + i * 0.0002, 2.0, 100.0, null, null, 5f, at(sec)); sec++ }
        r.stop(at(sec))

        val s = r.snapshot(at(sec))
        assertThat(s.lapsActive).isFalse()
        // Exactly one END, anchored to the last meta crossing (the last SPLIT), not the stop point.
        val ends = s.lapMarks.filter { it.type == LapMarkType.END }
        assertThat(ends).hasSize(1)
        val lastCrossing = s.lapMarks.last { it.type == LapMarkType.START || it.type == LapMarkType.SPLIT }
        assertThat(ends.first().seq).isEqualTo(lastCrossing.seq)
        // Two completed laps + one RETURN; the meta→stop partial is NOT counted as a lap.
        val ranges = cat.rumb.app.data.tracks.Laps.fromMarks(s.lapMarks, s.points().map { it.id })
        assertThat(ranges.count { it.kind == cat.rumb.app.data.tracks.LapKind.LAP }).isEqualTo(2)
        assertThat(ranges.count { it.kind == cat.rumb.app.data.tracks.LapKind.RETURN }).isEqualTo(1)
    }

    // --- Loop autodetection ---

    private val mPerDegLat = 111_320.0
    private fun eastDeg(m: Double) = m / (mPerDegLat * Math.cos(Math.toRadians(41.0)))
    private fun northDeg(m: Double) = m / mPerDegLat

    /** ~11 m-spaced (lat,lon) fixes along a straight leg between two (northM, eastM) offsets. */
    private fun legFixes(from: Pair<Double, Double>, to: Pair<Double, Double>): List<Pair<Double, Double>> {
        val distM = Math.hypot(to.first - from.first, to.second - from.second)
        val steps = maxOf(1, (distM / 11.0).toInt())
        return (1..steps).map { i ->
            val n = from.first + (to.first - from.first) * i / steps
            val e = from.second + (to.second - from.second) * i / steps
            (41.0 + northDeg(n)) to (2.0 + eastDeg(e))
        }
    }

    /** Fixes for one clockwise lap of a [side]-metre square from its SW corner. */
    private fun squareFixes(side: Double): List<Pair<Double, Double>> =
        legFixes(0.0 to 0.0, side to 0.0) + legFixes(side to 0.0, side to side) +
            legFixes(side to side, 0.0 to side) + legFixes(0.0 to side, 0.0 to 0.0)

    /** Feeds [fixes] one per second from [sec], returning the recorder (for chaining assertions). */
    private fun TrackRecorder.feed(fixes: List<Pair<Double, Double>>, sec: LongArray): TrackRecorder {
        for ((lat, lon) in fixes) { onLocation(lat, lon, 100.0, null, null, 5f, at(sec[0])); sec[0]++ }
        return this
    }

    private fun TrackRecorder.leg(from: Pair<Double, Double>, to: Pair<Double, Double>, sec: LongArray) =
        feed(legFixes(from, to), sec)

    private fun TrackRecorder.square(side: Double, sec: LongArray) = feed(squareFixes(side), sec)

    private fun loopCfg() = RecorderConfig(autoLapByPosition = true, autoDetectLoop = true, autoLapMinLapMs = 0)

    /** Feeds fixes until the loop is detected; returns the snapshot at that fix. Fails if never. */
    private fun TrackRecorder.feedUntilDetected(fixes: List<Pair<Double, Double>>, sec: LongArray): RecorderState {
        for ((lat, lon) in fixes) {
            onLocation(lat, lon, 100.0, null, null, 5f, at(sec[0])); sec[0]++
            val s = snapshot(at(sec[0]))
            if (s.detectedLoopM != null) return s
        }
        error("loop never detected")
    }

    @Test
    fun detectingALoopOpensLapOneRetroactivelyAtTheStart() {
        // An approach from "home" (500 m south) then two laps of a 400 m square. Detection fires on
        // lap 2 and back-dates START to where the loop began, so the approach stays approach.
        val r = TrackRecorder(loopCfg())
        r.start(t0)
        r.warmUp()
        val sec = longArrayOf(0)
        r.leg(-500.0 to 0.0, 0.0 to 0.0, sec) // approach: 500 m south → SW corner
        r.square(400.0, sec)
        val s = r.feedUntilDetected(squareFixes(400.0), sec) // second lap, stop the instant it fires

        assertThat(s.lapCount).isEqualTo(2) // START(P0) + SPLIT(P1): you're a lap in
        assertThat(s.lapMarks.count { it.type == LapMarkType.START }).isEqualTo(1)
        assertThat(s.lapMarks.count { it.type == LapMarkType.SPLIT }).isEqualTo(1)
        // The user's own words: the approach must not be swallowed into lap 1.
        val ranges = cat.rumb.app.data.tracks.Laps.fromMarks(s.lapMarks, s.points().map { it.id })
        assertThat(ranges.first().kind).isEqualTo(cat.rumb.app.data.tracks.LapKind.APPROACH)
        assertThat(ranges.first { it.kind == cat.rumb.app.data.tracks.LapKind.LAP }.startIdx).isGreaterThan(0)
    }

    @Test
    fun aDetectedLoopFeedsTheCoverageGuard() {
        // After detection, the loop's length (~1600 m) is the yardstick. Two full squares detect
        // mid-lap-2 and END continuously back at the SW line, so an out-and-back FROM there is a
        // clean crossing. It is 400 m — chosen to sit BETWEEN the old flat 100 m guard and 80% of
        // the 1600 m loop (1280 m): it would SPLIT under the old guard, but must ABORT under the
        // detected yardstick. That gap is what makes this test actually distinguish the two.
        val r = TrackRecorder(loopCfg())
        r.start(t0)
        r.warmUp()
        val sec = longArrayOf(0)
        r.square(400.0, sec)
        r.square(400.0, sec) // detected mid-way; ends continuously back at the SW line
        r.leg(0.0 to 0.0, 200.0 to 0.0, sec) // continuous from SW: out 200 m…
        r.leg(200.0 to 0.0, 0.0 to 0.0, sec) // …and back to the line → 400 m
        val s = r.snapshot(at(sec[0]))
        // START · SPLIT(detection) · SPLIT(the real lap 2) · ABORT(the 400 m out-and-back). Two
        // splits not three, and the last mark an ABORT, proves the yardstick is the detected loop.
        assertThat(s.lapMarks.count { it.type == LapMarkType.SPLIT }).isEqualTo(2)
        assertThat(s.lapMarks.last().type).isEqualTo(LapMarkType.ABORT)
    }

    @Test
    fun outAndBackIsNeverDetectedAsALoop() {
        // The star false-positive to avoid: 2 km out and back is a retrace, not a lap.
        val r = TrackRecorder(loopCfg())
        r.start(t0)
        r.warmUp()
        val sec = longArrayOf(0)
        r.leg(0.0 to 0.0, 2000.0 to 0.0, sec)
        r.leg(2000.0 to 0.0, 0.0 to 0.0, sec)
        assertThat(r.snapshot(at(sec[0])).lapMarks).isEmpty()
        assertThat(r.snapshot(at(sec[0])).lapsActive).isFalse()
    }

    @Test
    fun aManualFlagStandsDetectionDown() {
        // If you declare your own line, detection must not also fire and open a second block.
        val r = TrackRecorder(loopCfg())
        r.start(t0)
        r.warmUp()
        val sec = longArrayOf(0)
        r.onLocation(41.0, 2.0, 100.0, null, null, 5f, at(sec[0])); sec[0]++
        r.lap(at(sec[0])) // manual START at the SW corner
        r.square(400.0, sec)
        r.square(400.0, sec)
        assertThat(r.snapshot(at(sec[0])).lapMarks.count { it.type == LapMarkType.START }).isEqualTo(1)
    }

    @Test
    fun distanceSplitsDisableDetection() {
        // Splits own the laps: start() opens the block immediately, so detection is gated out and the
        // whole track is laps with no approach.
        val cfg = RecorderConfig(autoLapByPosition = true, autoDetectLoop = true, autoLapEveryM = 1000.0)
        val r = TrackRecorder(cfg)
        r.start(t0)
        r.warmUp()
        val sec = longArrayOf(0)
        r.square(400.0, sec)
        r.square(400.0, sec)
        val ranges = cat.rumb.app.data.tracks.Laps.fromMarks(
            r.snapshot(at(sec[0])).lapMarks, r.snapshot(at(sec[0])).points().map { it.id },
        )
        assertThat(ranges.none { it.kind == cat.rumb.app.data.tracks.LapKind.APPROACH }).isTrue()
    }

    @Test
    fun endLapsStopsRedetection() {
        // End-Laps means "done with laps"; re-detecting on the next loop would be obnoxious.
        val r = TrackRecorder(loopCfg())
        r.start(t0)
        r.warmUp()
        val sec = longArrayOf(0)
        r.square(400.0, sec)
        r.square(400.0, sec) // detected
        r.endLaps(at(sec[0]))
        r.square(400.0, sec)
        r.square(400.0, sec) // must not re-detect
        assertThat(r.snapshot(at(sec[0])).lapsActive).isFalse()
    }

    @Test
    fun theDetectedLoopLengthIsAnnouncedOnceThenCleared() {
        val r = TrackRecorder(loopCfg())
        r.start(t0)
        r.warmUp()
        val sec = longArrayOf(0)
        r.square(400.0, sec)
        val s = r.feedUntilDetected(squareFixes(400.0), sec)
        assertThat(s.detectedLoopM).isNotNull() // the snapshot at the detecting fix carries it…
        assertThat(s.detectedLoopM!!).isBetween(1400.0, 1800.0) // …and it's the loop, not two
        // …the next fix clears it, so the banner shows once.
        r.onLocation(41.0, 2.0, 100.0, null, null, 5f, at(sec[0])); sec[0]++
        assertThat(r.snapshot(at(sec[0])).detectedLoopM).isNull()
    }
}
