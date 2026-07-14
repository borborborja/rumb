package cat.rumb.app.data.gpx

import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal KML/KMZ reader (DOM, JVM-testable): extracts the first track-like geometry — every
 * `LineString/coordinates` (lon,lat[,ele] triplets) plus `gx:Track` `gx:coord` points — into a
 * [GpxRoute]. KMZ is a zip whose first `*.kml` entry is the document.
 */
object Kml {

    fun read(input: InputStream): GpxRoute {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val doc = factory.newDocumentBuilder().parse(input)
        doc.documentElement.normalize()

        val name = doc.getElementsByTagName("name")
            .let { if (it.length > 0) it.item(0).textContent?.trim() else null }

        val points = mutableListOf<GpxPoint>()

        // <LineString><coordinates>lon,lat[,ele] lon,lat[,ele] …</coordinates></LineString>
        val coordNodes = doc.getElementsByTagName("coordinates")
        for (i in 0 until coordNodes.length) {
            val parent = coordNodes.item(i).parentNode?.nodeName ?: ""
            if (!parent.contains("LineString") && !parent.contains("LinearRing")) continue
            coordNodes.item(i).textContent
                ?.trim()
                // Collapse spaces around the commas so a non-standard "lon, lat, ele" tuple isn't
                // shredded into separate tokens by the whitespace split (which would drop every point).
                ?.replace(Regex("\\s*,\\s*"), ",")
                ?.split(Regex("\\s+"))
                ?.forEach { triplet ->
                    val parts = triplet.split(",")
                    val lon = parts.getOrNull(0)?.toDoubleOrNull()
                    val lat = parts.getOrNull(1)?.toDoubleOrNull()
                    if (lon != null && lat != null) {
                        points.add(GpxPoint(lat, lon, parts.getOrNull(2)?.toDoubleOrNull()))
                    }
                }
        }

        // <gx:Track><gx:coord>lon lat ele</gx:coord>…</gx:Track>
        if (points.isEmpty()) {
            val gxCoords = doc.getElementsByTagName("gx:coord")
            for (i in 0 until gxCoords.length) {
                val parts = gxCoords.item(i).textContent?.trim()?.split(Regex("\\s+")) ?: continue
                val lon = parts.getOrNull(0)?.toDoubleOrNull()
                val lat = parts.getOrNull(1)?.toDoubleOrNull()
                if (lon != null && lat != null) {
                    points.add(GpxPoint(lat, lon, parts.getOrNull(2)?.toDoubleOrNull()))
                }
            }
        }

        return GpxRoute(name, points)
    }

    /**
     * Serializes points to a KML document with a single `<LineString>` (lon,lat,ele). KML carries only
     * geometry here — no laps or sensor data — which is enough for Google Earth / Maps.
     */
    fun write(name: String, points: List<GpxPoint>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n  <Document>\n")
        sb.append("    <name>").append(escape(name)).append("</name>\n")
        sb.append("    <Placemark>\n      <name>").append(escape(name)).append("</name>\n")
        sb.append("      <LineString>\n        <tessellate>1</tessellate>\n        <coordinates>\n")
        for (p in points) {
            sb.append(String.format(Locale.US, "          %.7f,%.7f,%.1f%n", p.longitude, p.latitude, p.elevation ?: 0.0))
        }
        sb.append("        </coordinates>\n      </LineString>\n    </Placemark>\n  </Document>\n</kml>\n")
        return sb.toString()
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Reads a KMZ archive: the first `*.kml` entry is parsed as KML. */
    fun readKmz(input: InputStream): GpxRoute {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".kml", ignoreCase = true)) {
                    // DOM parser closes the stream; shield the zip with a no-close wrapper.
                    return read(object : InputStream() {
                        override fun read() = zip.read()
                        override fun read(b: ByteArray, off: Int, len: Int) = zip.read(b, off, len)
                        override fun close() {} // keep the zip open
                    })
                }
                entry = zip.nextEntry
            }
        }
        return GpxRoute(null, emptyList())
    }
}
