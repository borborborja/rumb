package cat.rumb.app.data.gpx

import cat.rumb.app.data.tracks.LapRange
import cat.rumb.app.data.tracks.TrackStatsCalculator
import cat.rumb.app.viewer.hud.MetricsCalculator
import org.w3c.dom.Element
import java.io.InputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal TCX (Training Center XML) reader/writer. The writer emits one `<Lap>` per [LapRange] so
 * lap-aware consumers (Endurain, Strava, Garmin) get real laps — GPX has no lap structure. DOM-based
 * reader so it runs identically on Android and the plain JVM.
 */
object Tcx {

    /**
     * Serializes a track with laps to a TCX v2 document. Each [laps] range becomes a `<Lap>` with its
     * slice's stats; an empty [laps] yields a single lap over all points. Requires the first point to
     * carry a time (TCX Id/StartTime are xsd:dateTime) — callers should fall back to GPX otherwise.
     */
    fun write(
        name: String,
        points: List<GpxPoint>,
        laps: List<LapRange>,
        activityType: String?,
        weightKg: Int = 0,
        ageYears: Int = 0,
        sex: String? = null,
    ): String {
        val sport = cat.rumb.app.data.tracks.ActivityTypes.tcxSport(activityType)
        val startTime = points.firstOrNull()?.time?.toString() ?: "1970-01-01T00:00:00Z"
        // Cumulative distance (m) per point, for each Trackpoint's DistanceMeters.
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + MetricsCalculator.distanceMeters(points[i - 1].toGeoPoint(), points[i].toGeoPoint())
        }
        val ranges = laps.ifEmpty { listOf(LapRange(0, 0, points.size, cat.rumb.app.data.tracks.LapKind.LAP)) }

        val sw = StringBuilder()
        sw.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sw.append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" ")
        sw.append("xmlns:ns3=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\">\n")
        sw.append("  <Activities>\n    <Activity Sport=\"").append(sport).append("\">\n")
        sw.append("      <Id>").append(startTime).append("</Id>\n")

        for (r in ranges) {
            val s = r.startIdx.coerceIn(0, points.size)
            val e = r.endIdx.coerceIn(s, points.size)
            val slice = points.subList(s, e)
            if (slice.isEmpty()) continue
            val st = TrackStatsCalculator.compute(slice)
            val lapStart = slice.first().time?.toString() ?: startTime
            val secs = (st.movingTime ?: st.totalTime)?.seconds ?: 0L
            sw.append("      <Lap StartTime=\"").append(lapStart).append("\">\n")
            sw.append("        <TotalTimeSeconds>").append(secs).append("</TotalTimeSeconds>\n")
            sw.append(fmt("        <DistanceMeters>%.1f</DistanceMeters>%n", st.distanceM))
            st.maxSpeedKmh?.let { sw.append(fmt("        <MaximumSpeed>%.2f</MaximumSpeed>%n", it / 3.6)) }
            val kcal = if (weightKg > 0) {
                cat.rumb.app.data.tracks.Calories.kcal(
                    activityType, weightKg, st.movingTime ?: st.totalTime, st.avgHr, ageYears, sex,
                )
            } else {
                0
            }
            sw.append("        <Calories>").append(kcal).append("</Calories>\n")
            st.avgHr?.let { sw.append("        <AverageHeartRateBpm><Value>").append(it.toInt()).append("</Value></AverageHeartRateBpm>\n") }
            st.maxHr?.let { sw.append("        <MaximumHeartRateBpm><Value>").append(it.toInt()).append("</Value></MaximumHeartRateBpm>\n") }
            sw.append("        <Intensity>Active</Intensity>\n")
            sw.append("        <TriggerMethod>Manual</TriggerMethod>\n")
            sw.append("        <Track>\n")
            for (i in s until e) {
                val p = points[i]
                sw.append("          <Trackpoint>\n")
                p.time?.let { sw.append("            <Time>").append(it.toString()).append("</Time>\n") }
                sw.append(fmt("            <Position><LatitudeDegrees>%.7f</LatitudeDegrees><LongitudeDegrees>%.7f</LongitudeDegrees></Position>%n", p.latitude, p.longitude))
                p.elevation?.let { sw.append(fmt("            <AltitudeMeters>%.1f</AltitudeMeters>%n", it)) }
                sw.append(fmt("            <DistanceMeters>%.1f</DistanceMeters>%n", cum[i]))
                p.heartRate?.let { sw.append("            <HeartRateBpm><Value>").append(it.toInt()).append("</Value></HeartRateBpm>\n") }
                p.cadence?.let { sw.append("            <Cadence>").append(it.toInt().coerceIn(0, 254)).append("</Cadence>\n") }
                p.power?.let {
                    sw.append("            <Extensions><ns3:TPX><ns3:Watts>").append(it.toInt()).append("</ns3:Watts></ns3:TPX></Extensions>\n")
                }
                sw.append("          </Trackpoint>\n")
            }
            sw.append("        </Track>\n      </Lap>\n")
        }
        if (name.isNotBlank()) sw.append("      <Notes>").append(escape(name)).append("</Notes>\n")
        sw.append("    </Activity>\n  </Activities>\n</TrainingCenterDatabase>\n")
        return sw.toString()
    }

    private fun fmt(pattern: String, vararg args: Any) = String.format(Locale.US, pattern, *args)

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun read(input: InputStream): GpxRoute {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(input)
        doc.documentElement.normalize()

        // Activity Sport attribute or Notes as a poor-man's name; usually absent.
        val name = doc.getElementsByTagNameNS("*", "Activity")
            .let { if (it.length > 0) (it.item(0) as? Element)?.getAttribute("Sport")?.takeIf { s -> s.isNotBlank() } else null }

        val points = mutableListOf<GpxPoint>()
        val trackpoints = doc.getElementsByTagNameNS("*", "Trackpoint")
        for (i in 0 until trackpoints.length) {
            val tp = trackpoints.item(i) as? Element ?: continue
            val position = tp.getElementsByTagNameNS("*", "Position").let { if (it.length > 0) it.item(0) as? Element else null }
                ?: continue
            val lat = position.text("LatitudeDegrees")?.toDoubleOrNull() ?: continue
            val lon = position.text("LongitudeDegrees")?.toDoubleOrNull() ?: continue
            val ele = tp.text("AltitudeMeters")?.toDoubleOrNull()
            val time = tp.text("Time")?.let { parseTrackTime(it) }
            val hr = tp.getElementsByTagNameNS("*", "HeartRateBpm")
                .let { if (it.length > 0) (it.item(0) as? Element)?.text("Value")?.toDoubleOrNull() else null }
            val cadence = tp.text("Cadence")?.toDoubleOrNull()
            val power = tp.text("Watts")?.toDoubleOrNull()
            points.add(GpxPoint(lat, lon, ele, time, heartRate = hr, cadence = cadence, power = power))
        }
        return GpxRoute(name, points)
    }

    private fun Element.text(tag: String): String? {
        val nodes = getElementsByTagNameNS("*", tag)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim() else null
    }
}
