package cat.rumb.app.data.gpx

import cat.rumb.app.data.tracks.LapKind
import cat.rumb.app.data.tracks.LapRange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant

class TrackExportTest {

    private val pts = listOf(
        GpxPoint(41.0, 2.0, elevation = 100.0, time = Instant.parse("2026-07-14T10:00:00Z")),
        GpxPoint(41.001, 2.001, elevation = 110.0, time = Instant.parse("2026-07-14T10:01:00Z")),
    )

    @Test
    fun kmlRoundTrips() {
        val kml = Kml.write("Ruta", pts)
        assertThat(kml).contains("<LineString>").contains("<coordinates>")
        val route = Kml.read(kml.byteInputStream())
        assertThat(route.points).hasSize(2)
        assertThat(route.points[0].latitude).isEqualTo(41.0)
        assertThat(route.points[0].longitude).isEqualTo(2.0)
    }

    @Test
    fun exportPicksExtensionAndMimePerFormat() {
        val laps = listOf(LapRange(1, 0, 2, LapKind.LAP))
        val gpx = TrackExport.build(ExportFormat.GPX, "act", pts, laps, null)
        assertThat(gpx.fileName).endsWith(".gpx"); assertThat(gpx.mime).isEqualTo("application/gpx+xml")
        val tcx = TrackExport.build(ExportFormat.TCX, "act", pts, laps, null)
        assertThat(tcx.fileName).endsWith(".tcx"); assertThat(tcx.mime).contains("tcx")
        val kml = TrackExport.build(ExportFormat.KML, "act", pts, laps, null)
        assertThat(kml.fileName).endsWith(".kml"); assertThat(kml.mime).contains("kml")
        // AUTO with laps + timestamps → TCX.
        assertThat(TrackExport.build(ExportFormat.AUTO, "act", pts, laps, null).fileName).endsWith(".tcx")
    }

    @Test
    fun sniffDetectsFormats() {
        assertThat(sniffFormat("""<?xml version="1.0"?><gpx>""".toByteArray())).isEqualTo(TrackFormat.GPX)
        assertThat(sniffFormat("<TrainingCenterDatabase>".toByteArray())).isEqualTo(TrackFormat.TCX)
        assertThat(sniffFormat("<kml xmlns=".toByteArray())).isEqualTo(TrackFormat.KML)
        assertThat(sniffFormat(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4))).isEqualTo(TrackFormat.KMZ)
        assertThat(sniffFormat("hello".toByteArray())).isEqualTo(TrackFormat.UNSUPPORTED)
    }

    @Test
    fun sniffDetectsNamespacePrefixedRoots() {
        assertThat(sniffFormat("""<route:gpx xmlns:route="http://www.topografix.com/GPX/1/1">""".toByteArray()))
            .isEqualTo(TrackFormat.GPX)
        assertThat(sniffFormat("""<tcx:TrainingCenterDatabase xmlns:tcx="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">""".toByteArray()))
            .isEqualTo(TrackFormat.TCX)
        assertThat(sniffFormat("<earth:kml >".toByteArray())).isEqualTo(TrackFormat.KML)
    }

    @Test
    fun sniffUsesTheRootInsteadOfTagsInsideCommentsOrOtherElements() {
        val xml = "\uFEFF<?xml version=\"1.0\"?>\n<!-- <gpx> example -->\n<tcx:TrainingCenterDatabase >"
        assertThat(sniffFormat(xml.toByteArray())).isEqualTo(TrackFormat.TCX)
        assertThat(sniffFormat("<!-- <gpx> unfinished comment".toByteArray())).isEqualTo(TrackFormat.UNSUPPORTED)
        assertThat(sniffFormat("<other><gpx/></other>".toByteArray())).isEqualTo(TrackFormat.UNSUPPORTED)
        assertThat(sniffFormat("<gpxBackup/>".toByteArray())).isEqualTo(TrackFormat.UNSUPPORTED)
    }

    @Test
    fun sniffSkipsDoctypeDeclarationsAndTheirQuotedExamples() {
        val xml = """<!DOCTYPE gpx [<!ENTITY example "<kml>">]><route:gpx >"""
        assertThat(sniffFormat(xml.toByteArray())).isEqualTo(TrackFormat.GPX)
    }

    @Test
    fun headerReadHandlesLongXmlPreamblesAndShortProviderReads() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><!-- ${"x".repeat(100)} --><tcx:TrainingCenterDatabase >"""
        val input = object : ByteArrayInputStream(xml.toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 7))
        }
        val head = readTrackFormatHeader(input)
        assertThat(head).isEqualTo(xml.toByteArray())
        assertThat(sniffFormat(head)).isEqualTo(TrackFormat.TCX)
    }

    @Test
    fun headerReadIsBoundedForLargeTrackFiles() {
        val input = ByteArrayInputStream(ByteArray(10_000))
        assertThat(readTrackFormatHeader(input)).hasSize(4096)
        assertThat(input.available()).isEqualTo(10_000 - 4096)
    }
}
