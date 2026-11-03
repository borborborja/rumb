package cat.rumb.app.data.gpx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class GpxExtensionsTest {

    @Test
    fun writesSensorExtensionsWhenPresent() {
        val gpx = Gpx.write(
            "Sortida",
            listOf(
                GpxPoint(41.0, 2.0, 100.0, Instant.parse("2026-07-12T10:00:00Z"), heartRate = 150.0, cadence = 85.0, power = 210.0),
                GpxPoint(41.001, 2.0, 101.0, Instant.parse("2026-07-12T10:00:05Z")),
            ),
        )
        assertThat(gpx).contains("xmlns:gpxtpx=")
        assertThat(gpx).contains("<gpxtpx:hr>150</gpxtpx:hr>")
        assertThat(gpx).contains("<gpxtpx:cad>85</gpxtpx:cad>")
        assertThat(gpx).contains("<power>210</power>")
        // The extension-less point has no extensions block after it (only one occurrence).
        assertThat(gpx.split("<extensions>").size - 1).isEqualTo(1)
    }

    @Test
    fun plainPointsProduceNoExtensionNamespace() {
        val gpx = Gpx.write("Ruta", listOf(GpxPoint(41.0, 2.0), GpxPoint(41.001, 2.0)))
        assertThat(gpx).doesNotContain("gpxtpx")
        assertThat(gpx).doesNotContain("<extensions>")
    }

    @Test
    fun extendedGpxStillRoundTrips() {
        val gpx = Gpx.write(
            "Sortida",
            listOf(
                GpxPoint(41.0, 2.0, 100.0, Instant.parse("2026-07-12T10:00:00Z"), heartRate = 150.0),
                GpxPoint(41.001, 2.0, 101.0, Instant.parse("2026-07-12T10:00:05Z"), heartRate = 151.0),
            ),
        )
        val read = Gpx.read(gpx.byteInputStream())
        assertThat(read.name).isEqualTo("Sortida")
        assertThat(read.points).hasSize(2)
        assertThat(read.points[0].latitude).isEqualTo(41.0)
        assertThat(read.points[0].elevation).isEqualTo(100.0)
    }

    @Test
    fun sensorDataRoundTripsThroughWriteAndRead() {
        val original = listOf(
            GpxPoint(41.0, 2.0, 100.0, Instant.parse("2026-07-12T10:00:00Z"), heartRate = 150.0, cadence = 85.0, power = 210.0),
            GpxPoint(41.001, 2.0, 101.0, Instant.parse("2026-07-12T10:00:05Z"), heartRate = 152.0, cadence = 86.0, power = 215.0),
        )
        val read = Gpx.read(Gpx.write("Sortida", original).byteInputStream()).points
        assertThat(read).hasSize(2)
        assertThat(read[0].heartRate).isEqualTo(150.0)
        assertThat(read[0].cadence).isEqualTo(85.0)
        assertThat(read[0].power).isEqualTo(210.0)
        assertThat(read[1].heartRate).isEqualTo(152.0)
    }

    @Test
    fun partialExtensionsReadBackAsNulls() {
        val gpx = Gpx.write(
            "Mixta",
            listOf(
                GpxPoint(41.0, 2.0, heartRate = 140.0),
                GpxPoint(41.001, 2.0),
            ),
        )
        val read = Gpx.read(gpx.byteInputStream()).points
        assertThat(read[0].heartRate).isEqualTo(140.0)
        assertThat(read[0].cadence).isNull()
        assertThat(read[0].power).isNull()
        assertThat(read[1].heartRate).isNull()
    }

    @Test
    fun plainGpxReadsWithNullSensors() {
        val read = Gpx.read(Gpx.write("Ruta", listOf(GpxPoint(41.0, 2.0), GpxPoint(41.001, 2.0))).byteInputStream()).points
        assertThat(read).allSatisfy {
            assertThat(it.heartRate).isNull()
            assertThat(it.cadence).isNull()
            assertThat(it.power).isNull()
        }
    }

    @Test
    fun readsSensorExtensionsWithExporterChosenPrefix() {
        val gpx = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:tpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <trk><trkseg><trkpt lat="41.0" lon="2.0"><extensions>
                <tpx:TrackPointExtension><tpx:hr>150</tpx:hr><tpx:cad>85</tpx:cad></tpx:TrackPointExtension>
                <tpx:power>210</tpx:power>
              </extensions></trkpt></trkseg></trk>
            </gpx>
        """.trimIndent()

        val point = Gpx.read(gpx.byteInputStream()).points.single()

        assertThat(point.heartRate).isEqualTo(150.0)
        assertThat(point.cadence).isEqualTo(85.0)
        assertThat(point.power).isEqualTo(210.0)
    }
}
