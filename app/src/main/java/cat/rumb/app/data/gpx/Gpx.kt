package cat.rumb.app.data.gpx

import cat.rumb.app.data.opentracks.model.GeoPoint
import org.w3c.dom.Element
import java.io.InputStream
import java.io.StringWriter
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** A single GPX track point with optional elevation/time and sensor extensions. */
data class GpxPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val time: Instant? = null,
    // Garmin TrackPointExtension sensor data (written as gpxtpx extensions when present).
    val heartRate: Double? = null,
    val cadence: Double? = null,
    val power: Double? = null,
) {
    fun toGeoPoint() = GeoPoint(latitude, longitude)

    val hasExtensions: Boolean get() = heartRate != null || cadence != null || power != null
}

/** An imported/exported route: an ordered list of points and a name. */
data class GpxRoute(val name: String?, val points: List<GpxPoint>)

/**
 * Minimal GPX 1.1 reader/writer. Uses DOM so it runs identically on Android and the plain JVM
 * (Android lacks StAX). Reads trkpt (and rtept/wpt as fallback) coordinates + ele + time.
 */
object Gpx {

    fun read(input: InputStream): GpxRoute {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(input)
        doc.documentElement.normalize()

        val name = doc.getElementsByTagNameNS("*", "name").let { if (it.length > 0) it.item(0).textContent?.trim() else null }

        val points = mutableListOf<GpxPoint>()
        // Prefer trkpt, then rtept, then wpt.
        for (tag in listOf("trkpt", "rtept", "wpt")) {
            val nodes = doc.getElementsByTagNameNS("*", tag)
            if (nodes.length == 0) continue
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
                val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
                val ele = childText(el, "ele")?.toDoubleOrNull()
                val time = childText(el, "time")?.let { parseTrackTime(it) }
                // Namespace prefixes are chosen by the exporter; match local names so sensor
                // extensions work with any prefix, including the unprefixed variants.
                val hr = childText(el, "hr")?.toDoubleOrNull()
                val cad = childText(el, "cad")?.toDoubleOrNull()
                val power = childText(el, "power")?.toDoubleOrNull()
                points.add(GpxPoint(lat, lon, ele, time, heartRate = hr, cadence = cad, power = power))
            }
            if (points.isNotEmpty()) break
        }
        return GpxRoute(name, points)
    }

    private fun childText(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagNameNS("*", tag)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim() else null
    }

    /**
     * Serializes points to a GPX 1.1 track document. Sensor data (heart rate, cadence, power), when
     * present, is written as Garmin TrackPointExtension elements — the de-facto standard consumed by
     * Strava/Garmin/Endurain.
     */
    fun write(name: String, points: List<GpxPoint>, type: String? = null): String {
        val hasExt = points.any { it.hasExtensions }
        val sw = StringWriter()
        sw.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sw.append("""<gpx version="1.1" creator="Rumb" xmlns="http://www.topografix.com/GPX/1/1"""")
        if (hasExt) sw.append(""" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"""")
        sw.append(">").append('\n')
        sw.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        // <type> hints the activity type to consumers (Strava/Endurain) so they don't have to guess.
        if (!type.isNullOrBlank()) sw.append("    <type>").append(escape(type)).append("</type>\n")
        sw.append("    <trkseg>\n")
        for (p in points) {
            sw.append(String.format(Locale.US, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">", p.latitude, p.longitude))
            sw.append('\n')
            p.elevation?.let { sw.append(String.format(Locale.US, "        <ele>%.1f</ele>%n", it)) }
            p.time?.let { sw.append("        <time>").append(it.toString()).append("</time>\n") }
            if (p.hasExtensions) {
                sw.append("        <extensions>\n          <gpxtpx:TrackPointExtension>\n")
                p.heartRate?.let { sw.append("            <gpxtpx:hr>").append(it.toInt().toString()).append("</gpxtpx:hr>\n") }
                p.cadence?.let { sw.append("            <gpxtpx:cad>").append(it.toInt().toString()).append("</gpxtpx:cad>\n") }
                sw.append("          </gpxtpx:TrackPointExtension>\n")
                p.power?.let { sw.append("          <power>").append(it.toInt().toString()).append("</power>\n") }
                sw.append("        </extensions>\n")
            }
            sw.append("      </trkpt>\n")
        }
        sw.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sw.toString()
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
