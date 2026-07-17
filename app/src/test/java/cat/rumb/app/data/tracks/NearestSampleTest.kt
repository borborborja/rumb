package cat.rumb.app.data.tracks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NearestSampleTest {

    private fun sample(distM: Double) = TrackSample(
        distM = distM, lat = 41.0 + distM / 100_000, lon = 2.0,
        elevation = null, speedKmh = null, hr = null, time = null,
    )

    private val track = listOf(0.0, 100.0, 200.0, 300.0, 400.0).map(::sample)

    @Test
    fun picksTheClosestSample() {
        assertThat(nearestSampleAt(track, 0.0)?.distM).isEqualTo(0.0)
        assertThat(nearestSampleAt(track, 140.0)?.distM).isEqualTo(100.0)
        assertThat(nearestSampleAt(track, 160.0)?.distM).isEqualTo(200.0)
        assertThat(nearestSampleAt(track, 400.0)?.distM).isEqualTo(400.0)
    }

    @Test
    fun clampsOutsideTheTrack() {
        // A rival planted past its own end still points somewhere sensible.
        assertThat(nearestSampleAt(track, -500.0)?.distM).isEqualTo(0.0)
        assertThat(nearestSampleAt(track, 9_999.0)?.distM).isEqualTo(400.0)
    }

    @Test
    fun nothingToPointAt() {
        assertThat(nearestSampleAt(emptyList(), 100.0)).isNull()
        assertThat(nearestSampleAtFraction(emptyList(), 0.5f)).isNull()
    }

    @Test
    fun oneSampleIsAlwaysTheAnswer() {
        val single = listOf(sample(50.0))
        assertThat(nearestSampleAt(single, 0.0)?.distM).isEqualTo(50.0)
        assertThat(nearestSampleAtFraction(single, 0.5f)?.distM).isEqualTo(50.0)
    }

    @Test
    fun fractionSpansTheWholeTrack() {
        assertThat(nearestSampleAtFraction(track, 0f)?.distM).isEqualTo(0.0)
        assertThat(nearestSampleAtFraction(track, 0.5f)?.distM).isEqualTo(200.0)
        assertThat(nearestSampleAtFraction(track, 1f)?.distM).isEqualTo(400.0)
    }
}
