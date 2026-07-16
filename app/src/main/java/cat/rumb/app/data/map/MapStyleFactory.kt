package cat.rumb.app.data.map

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

    /**
     * [config] is the user's per-map display options (detail/grayscale/opacity). Its default is the
     * identity, so callers that don't pass one — previews, the heatmap's own desaturate — get exactly
     * the JSON they got before. [desaturate] stays independent (the heatmap forces it regardless).
     */
    fun rasterStyleJson(
        source: MapSource,
        config: MapDisplayConfig = MapDisplayConfig.DEFAULT,
        desaturate: Boolean = false,
    ): String {
        require(source.kind == MapSource.Kind.RASTER)
        return buildRasterStyle(
            tiles = expandTiles(source),
            attribution = source.attribution,
            maxZoom = config.effectiveMaxZoom(source.maxZoom),
            grayscale = config.grayscale || desaturate,
            opacity = config.opacity,
        )
    }

    /** Expands `{s}` into one URL per subdomain (MapLibre's way to spread load); else a single URL. */
    private fun expandTiles(source: MapSource): List<String> {
        val subs = source.subdomains
        return if (subs.isNullOrEmpty()) {
            listOf(source.url)
        } else {
            subs.map { source.url.replace("{s}", it.toString()) }
        }
    }

    /**
     * Style backed by a local MBTiles archive (offline). MapLibre Native reads the archive (metadata
     * + tiles) from the source `url` using the `mbtiles://` scheme — an absolute path, e.g.
     * `mbtiles:///data/.../file.mbtiles`. Do NOT use a `tiles` template with {z}/{x}/{y}: MapLibre
     * would try to open `.../file.mbtiles/z/x/y` as a database and crash natively.
     */
    fun rasterStyleForMbtiles(
        mbtilesPath: String,
        attribution: String,
        config: MapDisplayConfig = MapDisplayConfig.DEFAULT,
    ): String {
        val source = JSONObject()
            .put("type", "raster")
            .put("url", "mbtiles://$mbtilesPath")
            .put("tileSize", 256)
            .put("attribution", attribution)
        // The archive carries its own maxzoom; capping it below that still overzooms for less detail.
        if (config.detailReduction > 0) source.put("maxzoom", config.effectiveMaxZoom(MBTILES_ASSUMED_MAX))
        return wrapRasterSource(source, config.grayscale, config.opacity)
    }

    private fun buildRasterStyle(
        tiles: List<String>,
        attribution: String,
        maxZoom: Int,
        grayscale: Boolean = false,
        opacity: Float = 1f,
    ): String {
        val source = JSONObject()
            .put("type", "raster")
            .put("tiles", JSONArray(tiles))
            .put("tileSize", 256)
            .put("attribution", attribution)
            .put("maxzoom", maxZoom)
        return wrapRasterSource(source, grayscale, opacity)
    }

    private fun wrapRasterSource(source: JSONObject, grayscale: Boolean = false, opacity: Float = 1f): String {
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
        // Grayscale (-1 saturation) calms a busy map / makes a coloured overlay pop; opacity fades it
        // toward the background so the HUD and route dominate. Omit both when neutral so an unedited
        // map produces byte-identical JSON to before.
        val paint = JSONObject()
        if (grayscale) paint.put("raster-saturation", -1)
        if (opacity < 1f) paint.put("raster-opacity", opacity.toDouble())
        if (paint.length() > 0) layer.put("paint", paint)

        return JSONObject()
            .put("version", 8)
            .put("sources", JSONObject().put("base", source))
            .put("layers", JSONArray().put(background).put(layer))
            .toString()
    }

    /** MBTiles archives don't advertise their max here; assume a typical 16 for the detail cap. */
    private const val MBTILES_ASSUMED_MAX = 16
}
