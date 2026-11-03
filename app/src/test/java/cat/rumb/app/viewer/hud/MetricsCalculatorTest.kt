package cat.rumb.app.viewer.hud

import cat.rumb.app.data.opentracks.model.GeoPoint
import cat.rumb.app.data.opentracks.model.TRACKPOINT_TYPE_TRACKPOINT
import cat.rumb.app.data.opentracks.model.TrackStatistics
import cat.rumb.app.data.opentracks.model.Trackpoint
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

    /** A runner's per-fix speed swings every second; the tile must show the average, not the swing. */
    @Test
    fun paceIsSmoothedOverATrailingWindow() {
        // 12 fixes, one per second, oscillating 2.8/3.2 m/s around 3.0 (≈5:33/km).
        val points = (1..12).map { i -> tp(41.0 + i * 0.0001, 2.0, if (i % 2 == 0) 3.2 else 2.8, i.toLong()) }
        val m = MetricsCalculator.compute(listOf(points), null, isRecording = true)

        // Raw last fix would read 3.2 m/s → 5:12/km; the smoothed mean is 3.0 → 5:33/km.
        assertThat(m.speedKmh!!).isEqualTo(11.52, within(0.001)) // SPEED stays raw for cyclists
        assertThat(m.paceMinPerKm!!).isEqualTo(60.0 / 10.8, within(0.01))
    }

    /** Only the trailing window counts: a fast start must not drag the current pace down forever. */
    @Test
    fun paceWindowDropsOldFixes() {
        // 60 s at 5 m/s, then 20 s at 2.5 m/s: the 12 s window lands entirely inside the slow part.
        val fast = (1..60).map { i -> tp(41.0 + i * 0.0001, 2.0, 5.0, i.toLong()) }
        val slow = (61..80).map { i -> tp(41.0 + i * 0.0001, 2.0, 2.5, i.toLong()) }
        val m = MetricsCalculator.compute(listOf(fast + slow), null, isRecording = true)

        assertThat(m.paceMinPerKm!!).isEqualTo(60.0 / 9.0, within(0.01)) // 2.5 m/s = 9 km/h
    }

    @Test
    fun resumedSegmentDoesNotBorrowMeasurementsFromBeforePause() {
        val beforePause = listOf(
            tp(41.0, 2.0, 10.0, 1).copy(altitude = 100.0),
            tp(41.001, 2.0, 10.0, 2).copy(altitude = 110.0),
        )
        val resumed = listOf(tp(41.002, 2.0, 1.0, 5).copy(altitude = 150.0))

        val m = MetricsCalculator.compute(listOf(beforePause, resumed), null, isRecording = true)

        assertThat(m.paceMinPerKm!!).isCloseTo(60.0 / 3.6, within(0.001))
        // A single fix has no measured leg: the movement during the pause is not part of it.
        assertThat(m.bearingDeg).isNull()
        assertThat(m.slopePercent).isNull()
        assertThat(m.vamMeterPerHour).isNull()
        assertThat(m.altitudeM).isEqualTo(150.0)
        assertThat(m.pointCount).isEqualTo(3)
    }

    @Test
    fun resumedSegmentUsesItsOwnSlopeAndVamOnceItHasTwoPoints() {
        val beforePause = listOf(tp(40.0, 2.0, 10.0, 1).copy(altitude = 100.0))
        val resumed = listOf(
            tp(41.0, 2.0, 1.0, 5).copy(altitude = 500.0),
            tp(41.0001, 2.0, 1.0, 7).copy(altitude = 502.0),
        )

        val m = MetricsCalculator.compute(listOf(beforePause, resumed), null, isRecording = true)

        assertThat(m.slopePercent!!).isCloseTo(18.0, within(0.1))
        assertThat(m.vamMeterPerHour!!).isCloseTo(3600.0, within(0.001))
        assertThat(m.paceMinPerKm!!).isCloseTo(60.0 / 3.6, within(0.001))
        assertThat(m.pointCount).isEqualTo(3)
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
