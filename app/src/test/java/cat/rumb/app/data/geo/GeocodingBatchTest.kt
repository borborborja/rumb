package cat.rumb.app.data.geo

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GeocodingBatchTest {
    private val pending = listOf(41.0 to 2.0, 42.0 to 3.0, 43.0 to 4.0)

    @Test
    fun disabledGeocodingDoesNotSendAnyStartCoordinates() = runTest {
        val sent = mutableListOf<Pair<Double, Double>>()

        GeocodingBatch.run(pending, isEnabled = { false }) { sent += it }

        assertThat(sent).isEmpty()
    }

    @Test
    fun revokingConsentStopsTheRestOfAnAlreadyQueuedBatch() = runTest {
        var enabled = true
        val sent = mutableListOf<Pair<Double, Double>>()

        GeocodingBatch.run(pending, isEnabled = { enabled }) {
            sent += it
            enabled = false
        }

        assertThat(sent).containsExactly(pending.first())
    }

    @Test
    fun enabledGeocodingProcessesEveryPendingItemInOrder() = runTest {
        val sent = mutableListOf<Pair<Double, Double>>()

        GeocodingBatch.run(pending, isEnabled = { true }) { sent += it }

        assertThat(sent).containsExactlyElementsOf(pending)
    }
}
