package cat.hudpro.opentracks.data.map

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/** Geographic bounding box (WGS84 degrees). */
data class BoundingBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    val isValid get() = west < east && south < north
}

data class TileRange(val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int) {
    val count: Long get() = (xMax - xMin + 1).toLong() * (yMax - yMin + 1).toLong()
}

/**
 * Web-Mercator (EPSG:3857) slippy-tile math, NW origin (standard XYZ, as used by OSM and ICGC's
 * MON3857NW grid). Pure and unit-testable.
 */
object TileMath {

    fun lonToTileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        val x = floor((lon + 180.0) / 360.0 * n).toInt()
        return x.coerceIn(0, n - 1)
    }

    fun latToTileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        // y = (1 - asinh(tan(lat))/PI) / 2 * n
        val y = floor((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt()
        return y.coerceIn(0, n - 1)
    }

    fun tileRangeForBbox(bbox: BoundingBox, z: Int): TileRange {
        val xMin = lonToTileX(bbox.west, z)
        val xMax = lonToTileX(bbox.east, z)
        // Latitude grows downward in tile-Y, so north maps to the smaller y.
        val yMin = latToTileY(bbox.north, z)
        val yMax = latToTileY(bbox.south, z)
        return TileRange(minOf(xMin, xMax), maxOf(xMin, xMax), minOf(yMin, yMax), maxOf(yMin, yMax))
    }

    /** Total tile count for a bbox over an inclusive zoom range. */
    fun tileCount(bbox: BoundingBox, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) total += tileRangeForBbox(bbox, z).count
        return total
    }

    /** Converts an XYZ tile row (NW origin) to the MBTiles/TMS row (SW origin). */
    fun xyzToTmsRow(y: Int, z: Int): Int = (1 shl z) - 1 - y
}
