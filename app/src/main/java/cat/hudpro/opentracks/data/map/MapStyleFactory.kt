package cat.hudpro.opentracks.data.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a MapLibre GL style JSON for a given [MapSource].
 *
 * - RASTER sources become an inline style with a single raster layer.
 * - VECTOR_STYLE sources are consumed directly by their remote style URL (see [styleUriOrNull]).
 * - Offline MBTiles are wired via [rasterStyleForMbtiles] using MapLibre's `mbtiles://` scheme.
 */
object MapStyleFactory {

    /** For vector styles, MapLibre can load the URL directly; returns null for raster sources. */
    fun styleUriOrNull(source: MapSource): String? =
        if (source.kind == MapSource.Kind.VECTOR_STYLE) source.url else null

    fun rasterStyleJson(source: MapSource): String {
        require(source.kind == MapSource.Kind.RASTER)
        return buildRasterStyle(
            tiles = listOf(source.url),
            attribution = source.attribution,
            maxZoom = source.maxZoom,
        )
    }

    /**
     * Style backed by a local MBTiles archive (offline). MapLibre Native reads the archive (metadata
     * + tiles) from the source `url` using the `mbtiles://` scheme — an absolute path, e.g.
     * `mbtiles:///data/.../file.mbtiles`. Do NOT use a `tiles` template with {z}/{x}/{y}: MapLibre
     * would try to open `.../file.mbtiles/z/x/y` as a database and crash natively.
     */
    fun rasterStyleForMbtiles(mbtilesPath: String, attribution: String, maxZoom: Int = 20): String {
        val source = JSONObject()
            .put("type", "raster")
            .put("url", "mbtiles://$mbtilesPath")
            .put("tileSize", 256)
            .put("attribution", attribution)
        return wrapRasterSource(source)
    }

    private fun buildRasterStyle(tiles: List<String>, attribution: String, maxZoom: Int): String {
        val source = JSONObject()
            .put("type", "raster")
            .put("tiles", JSONArray(tiles))
            .put("tileSize", 256)
            .put("attribution", attribution)
            .put("maxzoom", maxZoom)
        return wrapRasterSource(source)
    }

    private fun wrapRasterSource(source: JSONObject): String {
        // Neutral background so areas without tiles (e.g. edges of a small offline region) render a
        // light grey instead of black.
        val background = JSONObject()
            .put("id", "bg")
            .put("type", "background")
            .put("paint", JSONObject().put("background-color", "#E8E8E8"))
        val layer = JSONObject()
            .put("id", "base-raster")
            .put("type", "raster")
            .put("source", "base")

        return JSONObject()
            .put("version", 8)
            .put("sources", JSONObject().put("base", source))
            .put("layers", JSONArray().put(background).put(layer))
            .toString()
    }
}
