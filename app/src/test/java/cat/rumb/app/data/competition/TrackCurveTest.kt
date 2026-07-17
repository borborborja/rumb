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
