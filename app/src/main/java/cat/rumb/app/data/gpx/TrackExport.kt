package cat.rumb.app.data.gpx

import cat.rumb.app.data.tracks.ActivityTypes
import cat.rumb.app.data.tracks.LapRange

/** User-selectable export formats. AUTO = best available (TCX with laps, else GPX). */
enum class ExportFormat { AUTO, GPX, TCX, KML }

/** Serializes a track to a chosen [ExportFormat], returning the filename, content and MIME to share. */
object TrackExport {

    data class Built(val fileName: String, val content: String, val mime: String)

    fun build(
        format: ExportFormat,
        baseName: String,
        points: List<GpxPoint>,
        laps: List<LapRange>,
        activityType: String?,
        weightKg: Int = 0,
        ageYears: Int = 0,
        sex: String? = null,
    ): Built = when (format) {
        ExportFormat.AUTO -> {
            val f = ActivityFile.build(baseName, points, laps, activityType, weightKg, ageYears, sex)
            Built(f.fileName, f.content, mimeFor(formatFor(f.fileName)))
        }
        ExportFormat.GPX ->
            Built("$baseName.gpx", Gpx.write(baseName, points, ActivityTypes.gpxType(activityType)), mimeFor(TrackFormat.GPX))
        ExportFormat.TCX ->
            Built("$baseName.tcx", Tcx.write(baseName, points, laps, activityType, weightKg, ageYears, sex), mimeFor(TrackFormat.TCX))
        ExportFormat.KML ->
            Built("$baseName.kml", Kml.write(baseName, points), mimeFor(TrackFormat.KML))
    }
}
