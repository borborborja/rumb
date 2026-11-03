package cat.rumb.app.data.gpx

import cat.rumb.app.data.tracks.LapKind
import cat.rumb.app.data.tracks.LapRange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class TcxWriteTest {

    private fun pts(n: Int): List<GpxPoint> = (0 until n).map { i ->
        GpxPoint(41.0 + i * 0.001, 2.0 + i * 0.001, elevation = 100.0 + i, time = Instant.parse("2026-07-14T10:0$i:00Z"), heartRate = 140.0)
    }

    @Test
    fun twoLapsEmitTwoLapElements() {
        val points = pts(6)
        val laps = listOf(
            LapRange(1, 0, 3, LapKind.LAP),
            LapRange(2, 3, 6, LapKind.LAP),
        )
        val tcx = Tcx.write("Sortida", points, laps, cat.rumb.app.data.tracks.ActivityTypes.RUN, weightKg = 70, ageYears = 30, sex = "M")
        assertThat(countOf(tcx, "<Lap ")).isEqualTo(2)
        assertThat(tcx).contains("<TotalTimeSeconds>").contains("<DistanceMeters>")
        assertThat(tcx).contains("Sport=\"Running\"")
        // Calories come from weight+HR (HR=140 present in the points), so must be > 0.
        assertThat(tcx).doesNotContain("<Calories>0</Calories>")
    }

    @Test
    fun noLapsEmitsSingleLap() {
        val tcx = Tcx.write("Sortida", pts(4), emptyList(), null)
        assertThat(countOf(tcx, "<Lap ")).isEqualTo(1)
        assertThat(tcx).contains("Sport=\"Other\"")
    }

    @Test
    fun activityFilePicksTcxWhenLapped() {
        val points = pts(4)
        val laps = listOf(LapRange(1, 0, 4, LapKind.LAP))
        assertThat(ActivityFile.build("act", points, laps, cat.rumb.app.data.tracks.ActivityTypes.MTB).fileName).endsWith(".tcx")
        assertThat(ActivityFile.build("act", points, emptyList(), cat.rumb.app.data.tracks.ActivityTypes.MTB).fileName).endsWith(".gpx")
    }

    @Test
    fun activityFileFallsBackToGpxWithoutTimestamps() {
        val untimed = listOf(GpxPoint(41.0, 2.0), GpxPoint(41.001, 2.001))
        val laps = listOf(LapRange(1, 0, 2, LapKind.LAP))
        assertThat(ActivityFile.build("act", untimed, laps, null).fileName).endsWith(".gpx")
    }

    @Test
    fun sensorDataSurvivesTcxExportAndImport() {
        val points = listOf(
            GpxPoint(41.0, 2.0, 100.0, Instant.parse("2026-07-14T10:00:00Z"), heartRate = 150.0, cadence = 85.0, power = 210.0),
            GpxPoint(41.001, 2.001, 101.0, Instant.parse("2026-07-14T10:01:00Z")),
        )

        val read = Tcx.read(Tcx.write("Sortida", points, emptyList(), null).byteInputStream()).points

        assertThat(read).containsExactlyElementsOf(points)
    }

    @Test
    fun readsPowerWithExporterChosenPrefix() {
        val tcx = """
            <tcx:TrainingCenterDatabase xmlns:tcx="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
                                       xmlns:ae="http://www.garmin.com/xmlschemas/ActivityExtension/v2">
              <tcx:Activities><tcx:Activity Sport="Biking"><tcx:Lap><tcx:Track><tcx:Trackpoint>
                <tcx:Position><tcx:LatitudeDegrees>41.0</tcx:LatitudeDegrees><tcx:LongitudeDegrees>2.0</tcx:LongitudeDegrees></tcx:Position>
                <tcx:Cadence>85</tcx:Cadence>
                <tcx:Extensions><ae:TPX><ae:Watts>210</ae:Watts></ae:TPX></tcx:Extensions>
              </tcx:Trackpoint></tcx:Track></tcx:Lap></tcx:Activity></tcx:Activities>
            </tcx:TrainingCenterDatabase>
        """.trimIndent()

        val route = Tcx.read(tcx.byteInputStream())

        assertThat(route.name).isEqualTo("Biking")
        assertThat(route.points).containsExactly(GpxPoint(41.0, 2.0, cadence = 85.0, power = 210.0))
    }

    private fun countOf(s: String, sub: String) = s.split(sub).size - 1
}
