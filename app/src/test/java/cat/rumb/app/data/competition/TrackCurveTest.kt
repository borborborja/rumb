package cat.rumb.app.data.competition

import cat.rumb.app.data.gpx.GpxPoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant

class TrackCurveTest {

    private val t0: Instant = Instant.parse("2026-07-17T10:00:00Z")

    /** North at a steady pace: [secondsPerStep] per ~11.1 m step. */
    private fun steady(steps: Int, secondsPerStep: Long): List<GpxPoint> =
        (0..steps).map {
            GpxPoint(41.0 + it * 0.0001, 2.0, time = t0.plusSeconds(it * secondsPerStep))
        }

    /**
     * Same 20 steps, but the first half crawled (4 s each) and the second half sprinted (1 s each):
     * 50 s total, and the half-distance mark falls at 40 s — 80 % of the time.
     *
     * A steady track is useless for testing anything that maps time to distance, because both
     * fractions coincide and a wrong implementation passes. This one prises them apart.
     */
    private fun uneven(): List<GpxPoint> {
        val pts = mutableListOf(GpxPoint(41.0, 2.0, time = t0))
        var secs = 0L
        for (i in 1..20) {
            secs += if (i <= 10) 4 else 1
            pts.add(GpxPoint(41.0 + i * 0.0001, 2.0, time = t0.plusSeconds(secs)))
        }
        return pts
    }

    @Test
    fun needsTwoTimedPoints() {
        assertThat(TrackCurve.of(emptyList())).isNull()
        assertThat(TrackCurve.of(listOf(GpxPoint(41.0, 2.0, time = t0)))).isNull()
        assertThat(TrackCurve.of(listOf(GpxPoint(41.0, 2.0), GpxPoint(41.1, 2.0)))).isNull()
        assertThat(TrackCurve.of(steady(1, 1))).isNotNull()
    }

    @Test
    fun theTwoAxesAreInverses() {
        val c = TrackCurve.of(steady(20, 1))!!
        listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0).forEach { f ->
            val d = c.totalDist * f
            assertThat(c.distanceAt(c.timeAt(d))).isCloseTo(d, within(0.5))
        }
    }

    @Test
    fun theAnchorLandsUnderTheFinger() {
        // The invariant the whole screen leans on: drag a chart to fraction f, turn that into an
        // instant, ask the same curve where it was — and get f back. Without it the crosshair and
        // the map marker drift apart on every chart that isn't the reference's.
        // On an uneven track, a fractionAt that reported TIME instead of ground covered fails here.
        val c = TrackCurve.of(uneven())!!
        listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f).forEach { f ->
            val t = c.timeAt(f * c.totalDist)
            assertThat(c.fractionAt(t)).isCloseTo(f, within(0.02f))
        }
    }

    @Test
    fun fractionIsGroundCoveredNotTimeElapsed() {
        // Half the distance of `uneven` is reached at 40 of its 50 s. Distance says 0.5; time would
        // say 0.8. The chart's x-axis is distance, so 0.5 is the only right answer.
        val c = TrackCurve.of(uneven())!!
        assertThat(c.totalTime).isEqualTo(50.0)
        assertThat(c.fractionAt(40.0)).isCloseTo(0.5f, within(0.02f))
        assertThat(c.fractionAt(40.0)).isNotCloseTo(0.8f, within(0.05f))
    }

    @Test
    fun fractionStaysInsideTheChart() {
        val c = TrackCurve.of(steady(10, 1))!!
        assertThat(c.fractionAt(-500.0)).isEqualTo(0f)
        assertThat(c.fractionAt(c.totalTime + 500.0)).isEqualTo(1f)
    }

    @Test
    fun aSlowerRivalsCrosshairSitsBehindTheAnchors() {
        // Same instant, two paces: the rival's own chart marks it further back, exactly like its
        // marker on the map. That agreement is the point.
        val leader = TrackCurve.of(steady(20, 1))!!
        val rival = TrackCurve.of(uneven())!!
        val t = leader.timeAt(leader.totalDist) // the leader finished at 20 s
        // 20 s into `uneven` is half way through its slow half → a quarter of the ground.
        assertThat(rival.fractionAt(t)).isCloseTo(0.25f, within(0.02f))
        assertThat(leader.fractionAt(t)).isEqualTo(1f)
    }

    @Test
    fun bothEndsClamp() {
        val c = TrackCurve.of(steady(10, 1))!!
        assertThat(c.timeAt(-100.0)).isEqualTo(0.0)
        assertThat(c.timeAt(c.totalDist + 100.0)).isEqualTo(c.totalTime)
        assertThat(c.distanceAt(-100.0)).isEqualTo(0.0)
        assertThat(c.distanceAt(c.totalTime + 100.0)).isEqualTo(c.totalDist)
    }

    @Test
    fun aShorterAttemptSimplyStopsWhereItEnded() {
        // The rival gave up half way: asked where it was long after it stopped, it stays at its end
        // rather than running off the track. That's the marker planting itself, not a hang.
        val quit = TrackCurve.of(steady(5, 1))!!
        assertThat(quit.distanceAt(9_999.0)).isEqualTo(quit.totalDist)
    }

    @Test
    fun standingStillHoldsTheDistance() {
        // Moves 5 s, stands still (same spot) until t=65, then moves again.
        val moving1 = (0..5).map { GpxPoint(41.0 + it * 0.0001, 2.0, time = t0.plusSeconds(it.toLong())) }
        val still = GpxPoint(41.0005, 2.0, time = t0.plusSeconds(65))
        val moving2 = (1..5).map { GpxPoint(41.0005 + it * 0.0001, 2.0, time = t0.plusSeconds(65 + it.toLong())) }
        val c = TrackCurve.of(moving1 + still + moving2)!!

        val atPause = c.distanceAt(5.0)
        assertThat(c.distanceAt(30.0)).isCloseTo(atPause, within(0.01))
        assertThat(c.distanceAt(64.0)).isCloseTo(atPause, within(0.01))
        assertThat(c.distanceAt(70.0)).isGreaterThan(atPause + 30)
        // Distance repeats through the pause, so time-at-that-distance is the FIRST moment there.
        assertThat(c.timeAt(atPause)).isCloseTo(5.0, within(0.01))
    }

    @Test
    fun timeAtFinishUsesFirstArrivalBeforeAStationaryTail() {
        val moving = steady(10, 1)
        val stationary = moving.last().copy(time = t0.plusSeconds(70))
        val c = TrackCurve.of(moving + stationary)!!

        assertThat(c.totalTime).isEqualTo(70.0)
        assertThat(c.timeAt(c.totalDist)).isEqualTo(10.0)
        assertThat(c.distanceAt(c.timeAt(c.totalDist))).isEqualTo(c.totalDist)
        assertThat(c.timeAt(c.totalDist + 1.0)).isEqualTo(70.0)
    }

    @Test
    fun theRivalIsWhereItWasAtTheLeadersMoment() {
        // THE test this whole screen rests on. Two attempts over the same line, the rival at half
        // pace. When the leader has covered its lap, the rival must be ~halfway — NOT at the same
        // distance. Comparing them at the same distance stacks both markers on one spot and the
        // gap becomes invisible on the map.
        val leader = TrackCurve.of(steady(20, 1))!!  // 20 steps, 1 s each
        val rival = TrackCurve.of(steady(20, 2))!!   // same line, 2 s per step → half the pace

        val d = leader.totalDist
        val t = leader.timeAt(d) // the moment the leader finished
        assertThat(rival.distanceAt(t)).isCloseTo(leader.totalDist / 2, within(1.0))

        // And half way round, the rival is a quarter of the way.
        val half = leader.totalDist / 2
        assertThat(rival.distanceAt(leader.timeAt(half))).isCloseTo(leader.totalDist / 4, within(1.0))
    }

    @Test
    fun aFasterRivalShowsUpAhead() {
        // Not a bug when it happens on screen: the rival really was further along at that moment.
        val leader = TrackCurve.of(steady(20, 2))!!
        val rival = TrackCurve.of(steady(20, 1))!!
        val half = leader.totalDist / 2
        assertThat(rival.distanceAt(leader.timeAt(half))).isGreaterThan(half)
    }

    @Test
    fun totalsMatchTheTrack() {
        val c = TrackCurve.of(steady(10, 3))!!
        assertThat(c.totalTime).isEqualTo(30.0)
        assertThat(c.totalDist).isCloseTo(111.2, within(2.0))
    }
}
