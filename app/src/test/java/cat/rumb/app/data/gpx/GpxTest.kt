package cat.rumb.app.data.gpx

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant

class GpxTest {

    @Test
    fun writeThenReadRoundTrips() {
        val points = listOf(
            GpxPoint(41.3874, 2.1686, elevation = 12.0, time = Instant.parse("2026-07-11T10:00:00Z")),
            GpxPoint(41.4036, 2.1744, elevation = 30.5, time = Instant.parse("2026-07-11T10:05:00Z")),
        )
        val xml = Gpx.write("Barcelona test", points)
        val route = Gpx.read(xml.byteInputStream())

        assertThat(route.name).isEqualTo("Barcelona test")
        assertThat(route.points).hasSize(2)
        assertThat(route.points[0].latitude).isEqualTo(41.3874, within(1e-6))
        assertThat(route.points[0].longitude).isEqualTo(2.1686, within(1e-6))
        assertThat(route.points[1].elevation).isEqualTo(30.5, within(1e-6))
        assertThat(route.points[0].time).isEqualTo(Instant.parse("2026-07-11T10:00:00Z"))
    }

    @Test
    fun readsStandardTrkpt() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1"><trk><name>R</name><trkseg>
              <trkpt lat="42.5" lon="1.5"><ele>1800</ele></trkpt>
              <trkpt lat="42.6" lon="1.6"></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val route = Gpx.read(gpx.byteInputStream())
        assertThat(route.points).hasSize(2)
        assertThat(route.points[0].elevation).isEqualTo(1800.0)
    }

    @Test
    fun readsPrefixedGpxElements() {
        val gpx = """
            <route:gpx xmlns:route="http://www.topografix.com/GPX/1/1" version="1.1">
              <route:trk><route:name>Prefixed route</route:name><route:trkseg>
                <route:trkpt lat="42.5" lon="1.5">
                  <route:ele>1800</route:ele><route:time>2026-07-11T10:00:00Z</route:time>
                </route:trkpt>
              </route:trkseg></route:trk>
            </route:gpx>
        """.trimIndent()

        val route = Gpx.read(gpx.byteInputStream())

        assertThat(route.name).isEqualTo("Prefixed route")
        assertThat(route.points).containsExactly(
            GpxPoint(42.5, 1.5, elevation = 1800.0, time = Instant.parse("2026-07-11T10:00:00Z")),
        )
    }
}
