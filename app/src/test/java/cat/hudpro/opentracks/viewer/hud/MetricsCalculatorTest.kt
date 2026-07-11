package cat.hudpro.opentracks.viewer.hud

import cat.hudpro.opentracks.data.opentracks.model.GeoPoint
import cat.hudpro.opentracks.data.opentracks.model.TRACKPOINT_TYPE_TRACKPOINT
import cat.hudpro.opentracks.data.opentracks.model.TrackStatistics
import cat.hudpro.opentracks.data.opentracks.model.Trackpoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

class MetricsCalculatorTest {

    private fun tp(lat: Double, lon: Double, speed: Double, id: Long) = Trackpoint(
        trackId = 1L, id = id, latLong = GeoPoint(lat, lon),
        type = TRACKPOINT_TYPE_TRACKPOINT, speed = speed, time = Instant.ofEpochMilli(id * 1000),
    )

    @Test
    fun convertsSpeedAndDerivesPace() {
        val segments = listOf(listOf(tp(41.0, 2.0, 5.0, 1), tp(41.001, 2.0, 5.0, 2)))
        val stats = TrackStatistics(
            totalDistanceMeter = 2500.0,
            movingTime = 10.minutes,
            avgMovingSpeedMeterPerSecond = 4.0,
            maxSpeedMeterPerSecond = 6.0,
            elevationGainMeter = 120.0,
        )
        val m = MetricsCalculator.compute(segments, stats, isRecording = true)

        assertThat(m.speedKmh).isEqualTo(18.0, within(0.001)) // 5 m/s
        assertThat(m.avgMovingSpeedKmh).isEqualTo(14.4, within(0.001))
        assertThat(m.maxSpeedKmh).isEqualTo(21.6, within(0.001))
        assertThat(m.distanceKm).isEqualTo(2.5, within(0.001))
        assertThat(m.elevationGainM).isEqualTo(120.0)
        // pace = 60/18 = 3.333 min/km
        assertThat(m.paceMinPerKm!!).isEqualTo(3.3333, within(0.001))
        assertThat(m.bearingDeg).isNotNull()
    }

    @Test
    fun slopeAndVamFromAltitudePoints() {
        // 100 m climb over 1000 m horizontal in 200 s → slope 10%, VAM 1800 m/h
        val base = Instant.ofEpochMilli(0)
        val points = listOf(
            Trackpoint(1L, 1L, GeoPoint(41.0, 2.0), TRACKPOINT_TYPE_TRACKPOINT, 3.0, base, altitude = 100.0),
            Trackpoint(1L, 2L, GeoPoint(41.009, 2.0), TRACKPOINT_TYPE_TRACKPOINT, 3.0, base.plusSeconds(200), altitude = 200.0),
        )
        val (slope, vam) = MetricsCalculator.slopeAndVam(points)
        assertThat(slope!!).isEqualTo(10.0, within(0.6)) // ~1001 m horizontal
        assertThat(vam!!).isEqualTo(1800.0, within(1.0))
    }

    @Test
    fun sensorFieldsFlowThrough() {
        val p = Trackpoint(
            1L, 1L, GeoPoint(41.0, 2.0), TRACKPOINT_TYPE_TRACKPOINT, 5.0, Instant.ofEpochMilli(0),
            altitude = 950.0, heartRate = 152.0, cadence = 88.0, power = 210.0,
        )
        val m = MetricsCalculator.compute(listOf(listOf(p)), null, isRecording = true)
        assertThat(m.heartRateBpm).isEqualTo(152.0)
        assertThat(m.cadenceRpm).isEqualTo(88.0)
        assertThat(m.powerW).isEqualTo(210.0)
        assertThat(m.altitudeM).isEqualTo(950.0)
    }

    @Test
    fun bearingNorthIsZero() {
        val b = MetricsCalculator.bearing(GeoPoint(41.0, 2.0), GeoPoint(41.5, 2.0))
        assertThat(b).isEqualTo(0.0, within(0.5))
    }

    @Test
    fun distanceMatchesKnownValue() {
        // ~111.2 km per degree of latitude
        val d = MetricsCalculator.distanceMeters(GeoPoint(41.0, 2.0), GeoPoint(42.0, 2.0))
        assertThat(d).isEqualTo(111_195.0, within(500.0))
    }

    @Test
    fun formatsMissingValuesAsDash() {
        assertThat(HudMetric.SLOPE.value(LiveMetrics())).isEqualTo("—")
        assertThat(HudMetric.SPEED.value(LiveMetrics(speedKmh = 12.34))).isEqualTo("12.3")
        assertThat(HudMetric.PACE.value(LiveMetrics(paceMinPerKm = 4.5))).isEqualTo("4:30")
    }
}
