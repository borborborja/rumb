package cat.rumb.app.manager.screens

import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.map.MapStyleFactory
import cat.rumb.app.data.opentracks.model.GeoPoint
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** An extra line drawn over the main route, in its own colour. */
data class MapTrack(val points: List<GpxPoint>, val colorHex: String)

/** A numbered badge pinned to a point, in a track's colour. */
data class MapLabel(val point: GeoPoint, val number: Int, val colorHex: String)

/** Draws the in-progress route (snapped line) and its waypoints on an editable MapLibre map. */
class RouteEditorController(private val map: MapLibreMap) {

    private var routeSource: GeoJsonSource? = null
    private var waypointSource: GeoJsonSource? = null
    private var highlightSource: GeoJsonSource? = null
    private var trackSource: GeoJsonSource? = null
    private var labelSource: GeoJsonSource? = null

    // Last drawn state, so a base-map change can restyle and redraw everything.
    private var lastRoute: List<GpxPoint> = emptyList()
    private var lastValues: List<Double?>? = null
    private var lastWaypoints: List<GeoPoint> = emptyList()
    private var lastHighlight: GeoPoint? = null
    private var lastTracks: List<MapTrack> = emptyList()
    private var lastLabels: List<MapLabel> = emptyList()

    /** Badge bitmaps already registered on the CURRENT style. Images die with the style, so a
     *  restyle clears this — otherwise the badges would silently stop appearing. */
    private val registeredIcons = mutableSetOf<String>()

    // The route EDITOR passes DEFAULT (full detail — you're drawing precisely); the read-only detail
    // screens pass the user's saved config so the map matches the viewer.
    fun init(
        source: MapSource = MapSource.ICGC_TOPO,
        config: cat.rumb.app.data.map.MapDisplayConfig = cat.rumb.app.data.map.MapDisplayConfig.DEFAULT,
        onReady: () -> Unit,
    ) {
        map.setStyle(Style.Builder().fromJson(MapStyleFactory.rasterStyleJson(source, config))) { style ->
            addOverlays(style)
            // Center on Catalonia by default.
            map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(41.65, 1.95), 9.0))
            onReady()
        }
    }

    /** Swaps the base raster map, re-adding the overlay layers and redrawing the current data. */
    fun setBaseMap(source: MapSource, config: cat.rumb.app.data.map.MapDisplayConfig = cat.rumb.app.data.map.MapDisplayConfig.DEFAULT) {
        cat.rumb.app.data.debug.DebugLog.d("Map", "detall · mapa base → ${source.id}")
        map.setStyle(Style.Builder().fromJson(MapStyleFactory.rasterStyleJson(source, config))) { style ->
            addOverlays(style)
            setRoute(lastRoute, lastValues)
            setTracks(lastTracks)
            setWaypoints(lastWaypoints)
            setLabels(lastLabels)
            setHighlight(lastHighlight)
        }
    }

    private fun addOverlays(style: Style) {
        registeredIcons.clear() // a new style starts with no images
        val route = GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(route)
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor("#3A86FF"),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        routeSource = route

        // Extra tracks (competition attempts). ONE source + ONE layer for all of them: the colour
        // rides on each feature, so N tracks cost N features rather than N layers.
        val tracks = GeoJsonSource(TRACK_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(tracks)
        style.addLayer(
            LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
                PropertyFactory.lineColor(org.maplibre.android.style.expressions.Expression.get(COLOR_PROP)),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        trackSource = tracks

        val wp = GeoJsonSource(WP_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(wp)
        style.addLayer(
            CircleLayer(WP_LAYER, WP_SOURCE).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor("#E63946"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        waypointSource = wp

        // Numbered badges, so you can tell which drawn track is which. A TEXT SymbolLayer is what
        // raster styles can't do (no glyphs) — an ICON one is fine, and the number is painted into
        // a bitmap here rather than typed. Same proven recipe as the viewer's arrows and ghost.
        val labels = GeoJsonSource(LABEL_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(labels)
        style.addLayer(
            SymbolLayer(LABEL_LAYER, LABEL_SOURCE).withProperties(
                PropertyFactory.iconImage(org.maplibre.android.style.expressions.Expression.get(ICON_PROP)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        labelSource = labels

        // Scrubber highlight (a plain dot; the numbered badges above carry the identity).
        val hl = GeoJsonSource(HL_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(hl)
        style.addLayer(
            CircleLayer(HL_LAYER, HL_SOURCE).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor("#FFD166"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        highlightSource = hl
    }

    fun onMapClick(listener: (GeoPoint) -> Unit) {
        map.addOnMapClickListener { latLng ->
            listener(GeoPoint(latLng.latitude, latLng.longitude))
            true
        }
    }

    fun setWaypoints(points: List<GeoPoint>) {
        lastWaypoints = points
        val features = points.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
        waypointSource?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Frames the camera to enclose [points] (used when opening an existing route). No-op if empty. */
    fun frame(points: List<GpxPoint>) {
        if (points.size < 2) return
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
            .includes(points.map { LatLng(it.latitude, it.longitude) })
            .build()
        // newLatLngBounds throws if the map isn't measured yet; fall back to centering on the start.
        runCatching { map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
            .onFailure {
                map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(points[0].latitude, points[0].longitude), 13.0))
            }
    }

    /**
     * Draws the route. With [values] (one per point: altitude, HR or speed), the line renders as
     * 2-point segments colored along a blue→red ramp over the value range; null values fall back to
     * neighbors, and an all-null list renders solid.
     */
    fun setRoute(points: List<GpxPoint>, values: List<Double?>? = null) {
        lastRoute = points
        lastValues = values
        val nonNull = values?.filterNotNull() ?: emptyList()
        if (values == null || nonNull.size < 2 || points.size < 2) {
            val feature = if (points.size >= 2) {
                listOf(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })))
            } else {
                emptyList()
            }
            routeSource?.setGeoJson(FeatureCollection.fromFeatures(feature))
            styleLayer { it.setProperties(PropertyFactory.lineColor("#3A86FF")) }
            return
        }
        val min = nonNull.min()
        val max = nonNull.max()
        val features = mutableListOf<Feature>()
        for (i in 1 until points.size) {
            val v = values.getOrNull(i) ?: values.getOrNull(i - 1) ?: continue
            features.add(
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        listOf(
                            Point.fromLngLat(points[i - 1].longitude, points[i - 1].latitude),
                            Point.fromLngLat(points[i].longitude, points[i].latitude),
                        ),
                    ),
                ).also { it.addNumberProperty("v", v) },
            )
        }
        routeSource?.setGeoJson(FeatureCollection.fromFeatures(features))
        if (max - min < 1e-6) {
            styleLayer { it.setProperties(PropertyFactory.lineColor("#3A86FF")) }
        } else {
            styleLayer { it.setProperties(PropertyFactory.lineColor(colorRamp(min, max))) }
        }
        cat.rumb.app.data.debug.DebugLog.d(
            "Map",
            "detall · track gradient · ${features.size} trams · v=[${"%.1f".format(min)}..${"%.1f".format(max)}]",
        )
    }

    private fun styleLayer(block: (LineLayer) -> Unit) {
        (map.style?.getLayer(ROUTE_LAYER) as? LineLayer)?.let(block)
    }

    private fun colorRamp(min: Double, max: Double) = org.maplibre.android.style.expressions.Expression.interpolate(
        org.maplibre.android.style.expressions.Expression.linear(),
        org.maplibre.android.style.expressions.Expression.get("v"),
        rampStop(min, "#2C7BB6"),
        rampStop(min + (max - min) * 0.25, "#00A6CA"),
        rampStop(min + (max - min) * 0.5, "#A6D96A"),
        rampStop(min + (max - min) * 0.75, "#FDAE61"),
        rampStop(max, "#D7191C"),
    )

    private fun rampStop(value: Double, hex: String) = org.maplibre.android.style.expressions.Expression.stop(
        value,
        org.maplibre.android.style.expressions.Expression.color(android.graphics.Color.parseColor(hex)),
    )

    /** Draws [tracks] over the main route, each in its own colour. Empty clears them. */
    fun setTracks(tracks: List<MapTrack>) {
        lastTracks = tracks
        val features = tracks.filter { it.points.size >= 2 }.map { t ->
            Feature.fromGeometry(
                LineString.fromLngLats(t.points.map { Point.fromLngLat(it.longitude, it.latitude) }),
            ).apply { addStringProperty(COLOR_PROP, t.colorHex) }
        }
        trackSource?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /**
     * Pins a numbered badge at each of [labels]. The bitmaps are registered on demand and keyed by
     * number+colour, so re-labelling the same attempts costs nothing after the first draw.
     */
    fun setLabels(labels: List<MapLabel>) {
        lastLabels = labels
        val style = map.style
        val features = labels.map { l ->
            val icon = "$LABEL_ICON_PREFIX${l.number}-${l.colorHex}"
            if (style != null && registeredIcons.add(icon)) {
                style.addImage(icon, numberBitmap(l.number, l.colorHex))
            }
            Feature.fromGeometry(Point.fromLngLat(l.point.longitude, l.point.latitude))
                .apply { addStringProperty(ICON_PROP, icon) }
        }
        labelSource?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** A filled circle carrying [number], drawn rather than typed (raster styles have no glyphs). */
    private fun numberBitmap(number: Int, colorHex: String): android.graphics.Bitmap {
        val s = 64
        val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = runCatching { android.graphics.Color.parseColor(colorHex) }
                .getOrDefault(android.graphics.Color.parseColor("#3A86FF"))
            style = android.graphics.Paint.Style.FILL
        }
        val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
        }
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = s * 0.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val r = s / 2f - 3f
        c.drawCircle(s / 2f, s / 2f, r, fill)
        c.drawCircle(s / 2f, s / 2f, r, ring)
        // Centre the digits on the circle: baseline sits half a glyph below the middle.
        val bounds = android.graphics.Rect()
        val label = number.toString()
        text.getTextBounds(label, 0, label.length, bounds)
        c.drawText(label, s / 2f, s / 2f + bounds.height() / 2f, text)
        return bmp
    }

    /** Shows the scrub marker at [point], or hides it when null. */
    fun setHighlight(point: GeoPoint?) {
        lastHighlight = point
        val features = if (point != null) {
            listOf(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)))
        } else {
            emptyList()
        }
        highlightSource?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private companion object {
        const val ROUTE_SOURCE = "editor-route-source"
        const val ROUTE_LAYER = "editor-route-layer"
        const val WP_SOURCE = "editor-wp-source"
        const val WP_LAYER = "editor-wp-layer"
        const val HL_SOURCE = "editor-hl-source"
        const val HL_LAYER = "editor-hl-layer"
        const val TRACK_SOURCE = "editor-track-source"
        const val TRACK_LAYER = "editor-track-layer"
        const val LABEL_SOURCE = "editor-label-source"
        const val LABEL_LAYER = "editor-label-layer"
        const val LABEL_ICON_PREFIX = "editor-label-"
        /** Feature properties the layers read: the line's colour and the badge's image id. */
        const val COLOR_PROP = "color"
        const val ICON_PROP = "icon"
    }
}
