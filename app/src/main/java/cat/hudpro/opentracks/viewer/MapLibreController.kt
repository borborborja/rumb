package cat.hudpro.opentracks.viewer

import cat.hudpro.opentracks.data.map.MapSource
import cat.hudpro.opentracks.data.map.MapStyleFactory
import cat.hudpro.opentracks.data.map.TrackColorMode
import cat.hudpro.opentracks.data.opentracks.model.GeoPoint
import cat.hudpro.opentracks.data.opentracks.model.Segment
import cat.hudpro.opentracks.data.opentracks.model.Waypoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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

/**
 * Owns the MapLibre style and the overlay layers that render the live OpenTracks track and markers.
 * Base map comes from a [MapSource]; the recorded track and waypoints are drawn as GeoJSON overlays
 * on top of it so switching base layers never loses the track.
 */
class MapLibreController(private val map: MapLibreMap) {

    private var trackSource: GeoJsonSource? = null
    private var trackLayer: LineLayer? = null
    private var waypointSource: GeoJsonSource? = null
    private var followSource: GeoJsonSource? = null
    private var followDoneSource: GeoJsonSource? = null
    private var followLayer: LineLayer? = null
    private var followDoneLayer: LineLayer? = null
    private var followPoints: List<GeoPoint> = emptyList()
    private var followColorHex: String = FOLLOW_COLOR
    private var followWidth: Float = 6f
    private var followArrows: Boolean = true
    private var followProgress: Boolean = true
    private var hasFramedTrack = false

    private var trackColorMode: TrackColorMode = TrackColorMode.SPEED
    private var trackColorHex: String = TRACK_COLOR
    private var lastTrackSegments: List<Segment> = emptyList()

    private companion object {
        const val TRACK_SOURCE = "track-source"
        const val TRACK_LAYER = "track-layer"
        const val WAYPOINT_SOURCE = "waypoint-source"
        const val WAYPOINT_LAYER = "waypoint-layer"
        const val FOLLOW_SOURCE = "follow-source"
        const val FOLLOW_LAYER = "follow-layer"
        const val FOLLOW_DONE_SOURCE = "follow-done-source"
        const val FOLLOW_DONE_LAYER = "follow-done-layer"
        const val FOLLOW_ARROW_LAYER = "follow-arrow-layer"
        const val TRACK_COLOR = "#E63946"
        const val FOLLOW_COLOR = "#3A86FF"
        const val MAX_COLOR_SEGMENTS = 500
    }

    fun setBaseMap(source: MapSource, onReady: () -> Unit = {}) {
        val styleUri = MapStyleFactory.styleUriOrNull(source)
        val builder = if (styleUri != null) {
            Style.Builder().fromUri(styleUri)
        } else {
            Style.Builder().fromJson(MapStyleFactory.rasterStyleJson(source))
        }
        applyStyle(builder, onReady)
    }

    /** Sets an offline base map backed by a local MBTiles archive. */
    fun setOfflineMbtiles(path: String, attribution: String, onReady: () -> Unit = {}) {
        applyStyle(Style.Builder().fromJson(MapStyleFactory.rasterStyleForMbtiles(path, attribution)), onReady)
    }

    private fun applyStyle(builder: Style.Builder, onReady: () -> Unit) {
        map.setStyle(builder) { style ->
            addOverlayLayers(style)
            cat.hudpro.opentracks.data.debug.DebugLog.i("Map", "estil aplicat · capes=${style.layers.size}")
            onReady()
        }
    }

    private fun addOverlayLayers(style: Style) {
        // Follow route drawn first, so the live recorded track renders on top of it.
        // Traveled ("done") portion underneath, dimmed grey.
        val followDone = GeoJsonSource(FOLLOW_DONE_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(followDone)
        val doneLayer = LineLayer(FOLLOW_DONE_LAYER, FOLLOW_DONE_SOURCE).withProperties(
            PropertyFactory.lineColor("#9AA5AD"),
            PropertyFactory.lineWidth((followWidth - 2f).coerceAtLeast(2f)),
            PropertyFactory.lineOpacity(0.6f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        )
        style.addLayer(doneLayer)
        followDoneSource = followDone
        followDoneLayer = doneLayer

        val follow = GeoJsonSource(FOLLOW_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(follow)
        val flLayer = LineLayer(FOLLOW_LAYER, FOLLOW_SOURCE).withProperties(
            PropertyFactory.lineColor(followColorHex),
            PropertyFactory.lineWidth(followWidth),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        )
        style.addLayer(flLayer)
        followSource = follow
        followLayer = flLayer

        // NOTE: no text/symbol layers here. A raster style has no `glyphs`, and a symbol layer that
        // needs glyphs makes MapLibre fail to render the GeoJSON layers (the route line vanished).
        val track = GeoJsonSource(TRACK_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(track)
        val layer = LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor(trackColorHex),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        )
        style.addLayer(layer)
        trackSource = track
        trackLayer = layer

        val waypoints = GeoJsonSource(WAYPOINT_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(waypoints)
        // CircleLayer instead of a text SymbolLayer — no glyphs needed (see note above).
        style.addLayer(
            CircleLayer(WAYPOINT_LAYER, WAYPOINT_SOURCE).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor("#1D3557"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        waypointSource = waypoints
    }

    fun updateTrack(segments: List<Segment>, frame: Boolean) {
        lastTrackSegments = segments
        if (trackColorMode == TrackColorMode.SINGLE) {
            // One feature per segment; constant color.
            val features = segments.mapNotNull { segment ->
                val points = segment.mapNotNull { tp -> tp.latLong?.let { Point.fromLngLat(it.longitude, it.latitude) } }
                if (points.size >= 2) Feature.fromGeometry(LineString.fromLngLats(points)) else null
            }
            trackSource?.setGeoJson(FeatureCollection.fromFeatures(features))
            trackLayer?.setProperties(PropertyFactory.lineColor(trackColorHex))
        } else {
            // 2-point segments, each carrying the color value; data-driven lineColor.
            val features = mutableListOf<Feature>()
            var min = Double.MAX_VALUE
            var max = -Double.MAX_VALUE
            segments.forEach { segment ->
                val pts = segment.filter { it.latLong != null }
                val step = (pts.size / MAX_COLOR_SEGMENTS + 1).coerceAtLeast(1)
                var i = step
                while (i < pts.size) {
                    val a = pts[i - step].latLong!!
                    val b = pts[i].latLong!!
                    val v = trackColorMode.valueOf(pts[i]) ?: trackColorMode.valueOf(pts[i - step])
                    if (v != null) {
                        min = minOf(min, v); max = maxOf(max, v)
                        features.add(
                            Feature.fromGeometry(
                                LineString.fromLngLats(
                                    listOf(Point.fromLngLat(a.longitude, a.latitude), Point.fromLngLat(b.longitude, b.latitude)),
                                ),
                            ).also { it.addNumberProperty("v", v) },
                        )
                    }
                    i += step
                }
            }
            trackSource?.setGeoJson(FeatureCollection.fromFeatures(features))
            if (features.isEmpty() || max - min < 1e-6) {
                trackLayer?.setProperties(PropertyFactory.lineColor(trackColorHex))
            } else {
                trackLayer?.setProperties(PropertyFactory.lineColor(colorRamp(min, max)))
            }
        }

        if (frame && !hasFramedTrack) {
            frameTrack(segments)
        }
    }

    /** Sets the coloring mode and single-mode color, re-rendering the current track. */
    fun setTrackColorMode(mode: TrackColorMode, colorHex: String) {
        trackColorMode = mode
        trackColorHex = colorHex
        if (trackSource != null) updateTrack(lastTrackSegments, frame = false)
    }

    private fun colorRamp(min: Double, max: Double) = org.maplibre.android.style.expressions.Expression.interpolate(
        org.maplibre.android.style.expressions.Expression.linear(),
        org.maplibre.android.style.expressions.Expression.get("v"),
        stop(min, "#2C7BB6"),
        stop(min + (max - min) * 0.25, "#00A6CA"),
        stop(min + (max - min) * 0.5, "#A6D96A"),
        stop(min + (max - min) * 0.75, "#FDAE61"),
        stop(max, "#D7191C"),
    )

    private fun stop(value: Double, hex: String) = org.maplibre.android.style.expressions.Expression.stop(
        value,
        org.maplibre.android.style.expressions.Expression.color(android.graphics.Color.parseColor(hex)),
    )

    /** Applies the followed-route appearance (color, width, direction arrows, progress split). */
    fun setFollowRouteStyle(colorHex: String, width: Float, arrows: Boolean, progress: Boolean) {
        // Guard against a malformed stored color (would make the line fail to render).
        followColorHex = colorHex.takeIf { it.startsWith("#") && (it.length == 7 || it.length == 9) } ?: FOLLOW_COLOR
        followWidth = width
        followArrows = arrows
        followProgress = progress
        followLayer?.setProperties(
            PropertyFactory.lineColor(followColorHex),
            PropertyFactory.lineWidth(width),
            PropertyFactory.visibility(org.maplibre.android.style.layers.Property.VISIBLE),
        )
        followDoneLayer?.setProperties(PropertyFactory.lineWidth((width - 2f).coerceAtLeast(2f)))
        if (followPoints.isNotEmpty()) drawFollow(followPoints.size) // refresh remaining/done split
    }

    /** Draws the preloaded route to follow. Pass an empty list to clear it. */
    fun setFollowRoute(points: List<GeoPoint>) {
        followPoints = points
        // Draw once the style is ready so the source update isn't dropped on a not-yet-loaded style.
        map.getStyle {
            drawFollow(points.size)
            cat.hudpro.opentracks.data.debug.DebugLog.d("Map", "setFollowRoute · ${points.size} punts · ${followDebug()}")
        }
    }

    /** Debug string describing the follow-route render state (layer presence, order vs base raster). */
    fun followDebug(): String {
        val style = map.style
        val ids = style?.layers?.map { it.id } ?: emptyList()
        val followLyr = style?.getLayer(FOLLOW_LAYER)
        val vis = (followLyr as? LineLayer)?.visibility?.value ?: "?"
        val idx = ids.indexOf(FOLLOW_LAYER)
        val rendered = runCatching {
            map.queryRenderedFeatures(android.graphics.RectF(0f, 0f, map.width, map.height), FOLLOW_LAYER).size
        }.getOrDefault(-1)
        return "pts=${followPoints.size} lyr=${followLyr != null} vis=$vis idx=$idx n=${ids.size} rendered=$rendered"
    }

    /** Frames the camera to enclose the current followed route (used when the user picks one). */
    fun frameFollowRoute() {
        val pts = followPoints
        if (pts.size < 2) return
        val bounds = LatLngBounds.Builder()
            .includes(pts.map { LatLng(it.latitude, it.longitude) })
            .build()
        // newLatLngBounds needs the map measured; fall back to centering on the route's start.
        runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
            .onFailure { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pts[0].latitude, pts[0].longitude), 13.0)) }
        // Claim the "already framed" flag so the track observer doesn't steal the camera back.
        hasFramedTrack = true
    }

    /** Splits the route into traveled ("done") and remaining at [nearestIndex] when progress is on. */
    fun updateFollowProgress(nearestIndex: Int) {
        if (followProgress && followPoints.isNotEmpty()) drawFollow(nearestIndex.coerceIn(0, followPoints.size))
    }

    private fun drawFollow(splitIndex: Int) {
        // Fetch the sources from the CURRENT style by id — the field references can go stale after a
        // restyle, so setGeoJson on them would update an orphaned source that nothing renders.
        val style = map.style
        val followSrc = style?.getSourceAs<GeoJsonSource>(FOLLOW_SOURCE) ?: followSource
        val doneSrc = style?.getSourceAs<GeoJsonSource>(FOLLOW_DONE_SOURCE) ?: followDoneSource
        val points = followPoints
        if (points.size < 2) {
            followSrc?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            doneSrc?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }
        fun line(sub: List<GeoPoint>) = if (sub.size >= 2) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(sub.map { Point.fromLngLat(it.longitude, it.latitude) })))
        } else {
            emptyList()
        }
        if (followProgress && splitIndex in 1 until points.size) {
            doneSrc?.setGeoJson(FeatureCollection.fromFeatures(line(points.subList(0, splitIndex + 1))))
            followSrc?.setGeoJson(FeatureCollection.fromFeatures(line(points.subList(splitIndex, points.size))))
        } else {
            doneSrc?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            followSrc?.setGeoJson(FeatureCollection.fromFeatures(line(points)))
        }
    }

    fun updateWaypoints(waypoints: List<Waypoint>) {
        val features = waypoints.map { wp ->
            Feature.fromGeometry(Point.fromLngLat(wp.latLong.longitude, wp.latLong.latitude))
        }
        waypointSource?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun frameTrack(segments: List<Segment>) {
        val all = segments.flatten().mapNotNull { it.latLong }
        if (all.isEmpty()) return
        if (all.size == 1) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(all[0].latitude, all[0].longitude), 15.0))
        } else {
            val bounds = LatLngBounds.Builder()
                .includes(all.map { LatLng(it.latitude, it.longitude) })
                .build()
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
        }
        hasFramedTrack = true
    }

    /**
     * Recenter on the most recent point (used while recording to follow the user). When [bearingDeg]
     * is provided and heading-up is on, the camera also rotates so the travel direction points up.
     */
    fun follow(segments: List<Segment>, bearingDeg: Double? = null) {
        val last = segments.lastOrNull()?.lastOrNull { it.latLong != null }?.latLong ?: return
        val target = LatLng(last.latitude, last.longitude)
        if (headingUp && bearingDeg != null) {
            val pos = org.maplibre.android.camera.CameraPosition.Builder(map.cameraPosition)
                .target(target).bearing(bearingDeg).build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(pos))
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLng(target))
        }
    }

    /** Whether the camera rotates to the travel direction (heading-up) instead of staying north-up. */
    var headingUp: Boolean = false
        private set

    fun setHeadingUp(enabled: Boolean) { headingUp = enabled }

    // --- Interactive map controls (HUD) ---

    fun zoomIn() = map.animateCamera(CameraUpdateFactory.zoomIn())
    fun zoomOut() = map.animateCamera(CameraUpdateFactory.zoomOut())

    /** Enables the device-location blue dot (caller must hold the location permission). */
    @android.annotation.SuppressLint("MissingPermission")
    fun enableLocation(context: android.content.Context) {
        map.getStyle { style ->
            val lc = map.locationComponent
            if (!lc.isLocationComponentActivated) {
                lc.activateLocationComponent(
                    org.maplibre.android.location.LocationComponentActivationOptions
                        .builder(context.applicationContext, style)
                        .useDefaultLocationEngine(true)
                        .build(),
                )
            }
            lc.isLocationComponentEnabled = true
            lc.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
            lc.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
        }
    }

    /** Centers on the user's current GPS location if available. Returns false if unknown. */
    @android.annotation.SuppressLint("MissingPermission")
    fun recenterOnLocation(context: android.content.Context): Boolean {
        val loc = runCatching { map.locationComponent.lastKnownLocation }.getOrNull()
            ?: lastKnownFromSystem(context) ?: return false
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0))
        return true
    }

    /** Fallback: the most recent fix from the OS location providers (independent of MapLibre's engine). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnownFromSystem(context: android.content.Context): android.location.Location? {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return null
        for (provider in listOf("fused", android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Frames the camera on a bounding box [w,s,e,n] (used for offline map coverage). */
    fun frameBounds(west: Double, south: Double, east: Double, north: Double) {
        val bounds = LatLngBounds.Builder()
            .include(LatLng(north, east))
            .include(LatLng(south, west))
            .build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 40))
    }

    /** Reset orientation to north-up. */
    fun northUp() {
        val pos = org.maplibre.android.camera.CameraPosition.Builder(map.cameraPosition).bearing(0.0).build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(pos))
    }

    /** Invokes [onUserGesture] when the user pans/zooms the map by hand (to disable follow mode). */
    fun onUserMoved(onUserGesture: () -> Unit) {
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture()
        }
    }
}
