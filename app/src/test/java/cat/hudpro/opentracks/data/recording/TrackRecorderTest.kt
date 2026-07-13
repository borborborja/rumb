package cat.hudpro.opentracks.data.recording

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
    fun warmupRequiresConsecutivePreciseFixes() {
        val r = TrackRecorder()
        r.start(t0)
        // Precise fix, then a mediocre one (within maxAccuracy but above startAccuracy) resets the streak.
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 8f, at(0))).isNull()
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 20f, at(1))).isNull() // resets warm-up
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 8f, at(2))).isNull() // good #1 again
        assertThat(r.onLocation(41.0, 2.0, null, null, null, 8f, at(3))).isNotNull() // good #2 → accepted
        // Cold-start scatter before the lock is not in the track.
        assertThat(r.snapshot(at(3)).points()).hasSize(1)
        assertThat(r.snapshot(at(3)).statistics.totalDistanceMeter).isEqualTo(0.0)
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
}
