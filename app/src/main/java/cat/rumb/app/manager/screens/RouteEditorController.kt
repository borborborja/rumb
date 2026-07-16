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
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Draws the in-progress route (snapped line) and its waypoints on an editable MapLibre map. */
class RouteEditorController(private val map: MapLibreMap) {

    private var routeSource: GeoJsonSource? = null
    private var waypointSource: GeoJsonSource? = null
    private var highlightSource: GeoJsonSource? = null

    // Last drawn state, so a base-map change can restyle and redraw everything.
    private var lastRoute: List<GpxPoint> = emptyList()
    private var lastValues: List<Double?>? = null
    private var lastWaypoints: List<GeoPoint> = emptyList()
    private var lastHighlight: GeoPoint? = null

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
            setWaypoints(lastWaypoints)
            setHighlight(lastHighlight)
        }
    }

    private fun addOverlays(style: Style) {
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

        // Scrubber highlight (CircleLayer only: SymbolLayers break GeoJSON rendering on raster styles).
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
    }
}
