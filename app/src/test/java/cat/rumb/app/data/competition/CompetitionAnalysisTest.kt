package cat.rumb.app.data.competition

import cat.rumb.app.data.gpx.GpxPoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant

class CompetitionAnalysisTest {

    private val t0: Instant = Instant.parse("2026-07-13T10:00:00Z")

    /** A straight east-going track: one point per second, [stepDeg] longitude per second. */
    private fun track(seconds: Int, stepDeg: Double, hr: Double? = null, timed: Boolean = true): List<GpxPoint> =
        (0..seconds).map { i ->
            GpxPoint(
                latitude = 41.0,
                longitude = 1.0 + i * stepDeg,
                time = if (timed) t0.plusSeconds(i.toLong()) else null,
                heartRate = hr,
            )
        }

    // --- gapOverDistance ---

    @Test
    fun identicalTracksHaveZeroGap() {
        val pts = track(seconds = 60, stepDeg = 0.0001)
        val gaps = CompetitionAnalysis.gapOverDistance(pts, pts)
        assertThat(gaps).isNotEmpty
        gaps.forEach { assertThat(it.gapSeconds).isCloseTo(0.0, within(1e-6)) }
    }

    @Test
    fun halfSpeedAttemptGrowsLinearlyAndPositive() {
        val best = track(seconds = 60, stepDeg = 0.0001)
        // Same geometry, but each step takes 2 s: half the speed.
        val attempt = (0..60).map { i ->
            GpxPoint(latitude = 41.0, longitude = 1.0 + i * 0.0001, time = t0.plusSeconds(2L * i))
        }
        val gaps = CompetitionAnalysis.gapOverDistance(best, attempt)
        assertThat(gaps).isNotEmpty
        gaps.forEach { assertThat(it.gapSeconds).isGreaterThan(0.0) }
        // At half speed the gap equals the best's elapsed time at each distance: linear in distance.
        val first = gaps.first()
        val last = gaps.last()
        val slope = (last.gapSeconds - first.gapSeconds) / (last.distM - first.distM)
        gaps.zipWithNext().forEach { (a, b) ->
            val local = (b.gapSeconds - a.gapSeconds) / (b.distM - a.distM)
            assertThat(local).isCloseTo(slope, within(slope * 0.05 + 1e-9))
        }
        // The final gap is roughly the whole best duration (60 s).
        assertThat(last.gapSeconds).isCloseTo(60.0, within(2.0))
    }

    @Test
    fun returnsAtMostBucketsSamples() {
        val pts = track(seconds = 60, stepDeg = 0.0001)
        assertThat(CompetitionAnalysis.gapOverDistance(pts, pts, buckets = 50)).hasSize(50)
        assertThat(CompetitionAnalysis.gapOverDistance(pts, pts).size).isLessThanOrEqualTo(300)
    }

    @Test
    fun waitingAtFinishDoesNotInflateTheFinalGap() {
        val best = track(seconds = 10, stepDeg = 0.0001)
        val attempt = best + best.last().copy(time = t0.plusSeconds(70))

        val gaps = CompetitionAnalysis.gapOverDistance(best, attempt)

        gaps.forEach { assertThat(it.gapSeconds).isCloseTo(0.0, within(1e-6)) }
    }

    @Test
    fun finalBucketStaysAtExactFinishDespiteFloatingPointRounding() {
        val best = (0..10).map { i ->
            GpxPoint(41.0 + i * 0.0001, 2.0, time = t0.plusSeconds(i.toLong()))
        }
        val attempt = best + best.last().copy(time = t0.plusSeconds(70))
        val total = TrackCurve.of(best)!!.totalDist
        // This geometry rounds one ULP beyond total when the last bucket is computed as d*300/300.
        assertThat(total * 300 / 300).isGreaterThan(total)

        val final = CompetitionAnalysis.gapOverDistance(best, attempt).last()

        assertThat(final.distM).isEqualTo(total)
        assertThat(final.gapSeconds).isEqualTo(0.0)
    }

    @Test
    fun emptyWhenUntimed() {
        val timed = track(seconds = 60, stepDeg = 0.0001)
        val untimed = track(seconds = 60, stepDeg = 0.0001, timed = false)
        assertThat(CompetitionAnalysis.gapOverDistance(untimed, timed)).isEmpty()
        assertThat(CompetitionAnalysis.gapOverDistance(timed, untimed)).isEmpty()
        assertThat(CompetitionAnalysis.gapOverDistance(untimed, untimed)).isEmpty()
    }

    // --- hrZones ---

    @Test
    fun constantHrLandsFullyInItsZone() {
        // 130 bpm with maxHr 190 = 68.4% → z1 (60–70%).
        val pts = track(seconds = 100, stepDeg = 0.0001, hr = 130.0)
        val zones = CompetitionAnalysis.hrZones(pts, 190)
        assertThat(zones[1]).isEqualTo(100_000L)
        assertThat(zones[0]).isZero()
        assertThat(zones[2]).isZero()
        assertThat(zones[3]).isZero()
        assertThat(zones[4]).isZero()
    }

    @Test
    fun boundaryFractionsMapToUpperZone() {
        // Exactly 60% of 200 = 120 bpm → z1; exactly 90% = 180 bpm → z4. Points 1 s apart.
        val at60 = CompetitionAnalysis.hrZones(track(seconds = 10, stepDeg = 0.0001, hr = 120.0), 200)
        assertThat(at60[1]).isEqualTo(10_000L)
        assertThat(at60[0]).isZero()

        val at90 = CompetitionAnalysis.hrZones(track(seconds = 10, stepDeg = 0.0001, hr = 180.0), 200)
        assertThat(at90[4]).isEqualTo(10_000L)
        assertThat(at90[3]).isZero()
    }

    @Test
    fun nullHrSegmentsAreIgnored() {
        val pts = (0..10).map { i ->
            GpxPoint(
                latitude = 41.0,
                longitude = 1.0 + i * 0.0001,
                time = t0.plusSeconds(i.toLong()),
                heartRate = if (i % 2 == 0) null else 130.0,
            )
        }
        val zones = CompetitionAnalysis.hrZones(pts, 190)
        // Segments whose LATER point has null HR are skipped: 5 of 10 seconds counted.
        assertThat(zones.sum()).isEqualTo(5_000L)
        assertThat(zones[1]).isEqualTo(5_000L)
    }

    @Test
    fun zonesSumToTimedDurationWhenAllHrPresent() {
        val pts = (0..30).map { i ->
            GpxPoint(
                latitude = 41.0,
                longitude = 1.0 + i * 0.0001,
                time = t0.plusSeconds(i.toLong()),
                heartRate = 100.0 + i * 3.0, // sweeps across several zones
            )
        }
        val zones = CompetitionAnalysis.hrZones(pts, 190)
        assertThat(zones.sum()).isEqualTo(30_000L)
    }
}
