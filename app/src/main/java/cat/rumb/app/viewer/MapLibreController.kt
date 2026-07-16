package cat.rumb.app.viewer

import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.map.MapStyleFactory
import cat.rumb.app.data.map.TrackColorMode
import cat.rumb.app.data.opentracks.model.GeoPoint
import cat.rumb.app.data.opentracks.model.Segment
import cat.rumb.app.data.opentracks.model.Waypoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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
    private var followArrowSource: GeoJsonSource? = null
    private var followLayer: LineLayer? = null
    private var followDoneLayer: LineLayer? = null
    private var followPoints: List<GeoPoint> = emptyList()
    private var followColorHex: String = FOLLOW_COLOR
    private var followWidth: Float = 6f
    private var followArrows: Boolean = true
    private var followArrowColorHex: String = FOLLOW_COLOR
    private var followArrowSize: Float = 0.7f
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
        const val FOLLOW_ARROW_SOURCE = "follow-arrow-source"
        const val FOLLOW_ARROW_ICON = "follow-arrow-icon"
        /** Cap the chevrons on long routes; spacing widens instead of the count growing. */
        const val MAX_FOLLOW_ARROWS = 200
        const val MIN_FOLLOW_ARROW_SPACING_M = 80.0
        const val GHOST_SOURCE = "ghost-source"
        const val GHOST_LAYER = "ghost-layer"
        const val TRACKING_SOURCE = "tracking-source"
        const val TRACKING_DOT_LAYER = "tracking-dot-layer"
        const val TRACKING_ARROW_LAYER = "tracking-arrow-layer"
        const val TRACKING_ARROW_ICON = "tracking-arrow-icon"
        const val TRACK_COLOR = "#E63946"
        const val FOLLOW_COLOR = "#3A86FF"
        const val MAX_COLOR_SEGMENTS = 500
    }

    fun setBaseMap(
        source: MapSource,
        config: cat.rumb.app.data.map.MapDisplayConfig = cat.rumb.app.data.map.MapDisplayConfig.DEFAULT,
        onReady: () -> Unit = {},
    ) {
        val styleUri = MapStyleFactory.styleUriOrNull(source)
        val builder = if (styleUri != null) {
            Style.Builder().fromUri(styleUri)
        } else {
            Style.Builder().fromJson(MapStyleFactory.rasterStyleJson(source, config))
        }
        applyStyle(builder, onReady)
    }

    /** Sets an offline base map backed by a local MBTiles archive. */
    fun setOfflineMbtiles(
        path: String,
        attribution: String,
        config: cat.rumb.app.data.map.MapDisplayConfig = cat.rumb.app.data.map.MapDisplayConfig.DEFAULT,
        onReady: () -> Unit = {},
    ) {
        applyStyle(Style.Builder().fromJson(MapStyleFactory.rasterStyleForMbtiles(path, attribution, config)), onReady)
    }

    private fun applyStyle(builder: Style.Builder, onReady: () -> Unit) {
        map.setStyle(builder) { style ->
            addOverlayLayers(style)
            cat.rumb.app.data.debug.DebugLog.i("Map", "estil aplicat · capes=${style.layers.size}")
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

        // Direction chevrons along the followed route (which way to ride it — it matters when racing).
        // ICON symbol layer only: the icon is a code-drawn bitmap registered via addImage, so this
        // needs NO glyphs and is safe on a raster style (same proven recipe as the tracking arrow).
        val followArrow = GeoJsonSource(FOLLOW_ARROW_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(followArrow)
        style.addImage(FOLLOW_ARROW_ICON, arrowBitmap(followArrowColorHex))
        style.addLayer(
            SymbolLayer(FOLLOW_ARROW_LAYER, FOLLOW_ARROW_SOURCE).withProperties(
                PropertyFactory.iconImage(FOLLOW_ARROW_ICON),
                PropertyFactory.iconSize(followArrowSize),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                // Let MapLibre cull colliding chevrons: dense when zoomed in, sparse when zoomed out.
                PropertyFactory.iconAllowOverlap(false),
                PropertyFactory.iconIgnorePlacement(false),
                PropertyFactory.visibility(if (followArrows) Property.VISIBLE else Property.NONE),
            ),
        )
        followArrowSource = followArrow

        // NOTE: no TEXT symbol layers here. A raster style has no `glyphs`, and a symbol layer that
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

        // Ghost (competition opponent) dot. CircleLayer only — a text SymbolLayer would need glyphs,
        // which raster styles lack (and would break every GeoJSON layer; see note above).
        val ghost = GeoJsonSource(GHOST_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(ghost)
        style.addLayer(
            CircleLayer(GHOST_LAYER, GHOST_SOURCE).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor("#9B5DE5"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        // Re-apply the last known ghost position so a base-map switch keeps the dot.
        lastGhost?.let { setGhost(it) }

        // Tracking point (user position): a custom marker so colour/size/shape are configurable
        // (the native puck can't be scaled). Dot = CircleLayer; arrow = SymbolLayer with a code-drawn
        // icon that rotates to the travel bearing. Only one is visible per the chosen style.
        val tracking = GeoJsonSource(TRACKING_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(tracking)
        style.addImage(TRACKING_ARROW_ICON, arrowBitmap(trackingColorHex))
        style.addLayer(
            CircleLayer(TRACKING_DOT_LAYER, TRACKING_SOURCE).withProperties(
                PropertyFactory.circleRadius(7f * trackingSize),
                PropertyFactory.circleColor(trackingColorHex),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.visibility(if (trackingStyle == "ARROW") Property.NONE else Property.VISIBLE),
            ),
        )
        style.addLayer(
            SymbolLayer(TRACKING_ARROW_LAYER, TRACKING_SOURCE).withProperties(
                PropertyFactory.iconImage(TRACKING_ARROW_ICON),
                PropertyFactory.iconSize(trackingSize),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.visibility(if (trackingStyle == "ARROW") Property.VISIBLE else Property.NONE),
            ),
        )
        lastTrackingPoint?.let { setTrackingMarker(it, lastTrackingBearing) }
    }

    private var lastGhost: GeoPoint? = null

    // Tracking-point marker state.
    private var trackingStyle: String = "DOT"
    private var trackingColorHex: String = FOLLOW_COLOR
    private var trackingSize: Float = 1.0f
    private var lastTrackingPoint: GeoPoint? = null
    private var lastTrackingBearing: Double? = null

    /** A navigation arrow (pointing north) drawn in [colorHex], to be rotated by the travel bearing. */
    private fun arrowBitmap(colorHex: String): android.graphics.Bitmap {
        val s = 72
        val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = runCatching { android.graphics.Color.parseColor(colorHex) }.getOrDefault(android.graphics.Color.parseColor(FOLLOW_COLOR))
            style = android.graphics.Paint.Style.FILL
        }
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 5f
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val p = android.graphics.Path().apply {
            moveTo(s * 0.5f, s * 0.10f)
            lineTo(s * 0.86f, s * 0.90f)
            lineTo(s * 0.5f, s * 0.66f)
            lineTo(s * 0.14f, s * 0.90f)
            close()
        }
        c.drawPath(p, fill)
        c.drawPath(p, stroke)
        return bmp
    }

    /** Applies the tracking-point style; regenerates the arrow icon for the chosen colour/size. */
    fun setTrackingPointStyle(style: String, colorHex: String, size: Float) {
        val clamped = size.coerceIn(0.4f, 2.5f)
        // Nothing changed (e.g. a plain onResume) → skip the bitmap regen + layer writes.
        if (style == trackingStyle && colorHex == trackingColorHex && clamped == trackingSize && map.style?.getLayer(TRACKING_DOT_LAYER) != null) return
        trackingStyle = style
        trackingColorHex = colorHex
        trackingSize = clamped
        val s = map.style ?: return
        s.addImage(TRACKING_ARROW_ICON, arrowBitmap(colorHex))
        s.getLayer(TRACKING_DOT_LAYER)?.setProperties(
            PropertyFactory.circleColor(colorHex),
            PropertyFactory.circleRadius(7f * trackingSize),
            PropertyFactory.visibility(if (style == "ARROW") Property.NONE else Property.VISIBLE),
        )
        s.getLayer(TRACKING_ARROW_LAYER)?.setProperties(
            PropertyFactory.iconSize(trackingSize),
            PropertyFactory.visibility(if (style == "ARROW") Property.VISIBLE else Property.NONE),
        )
    }

    /** Moves the tracking marker to [point] (null clears it), carrying [bearingDeg] for the arrow. */
    fun setTrackingMarker(point: GeoPoint?, bearingDeg: Double?) {
        lastTrackingPoint = point
        if (bearingDeg != null) lastTrackingBearing = bearingDeg
        val src = map.style?.getSourceAs<GeoJsonSource>(TRACKING_SOURCE) ?: return
        if (point == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        } else {
            val f = Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
            f.addNumberProperty("bearing", (lastTrackingBearing ?: 0.0))
            src.setGeoJson(FeatureCollection.fromFeatures(listOf(f)))
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun lastLocation(): android.location.Location? =
        runCatching { map.locationComponent.lastKnownLocation }.getOrNull()

    /** Moves the ghost dot to [point], or clears it when null. */
    fun setGhost(point: GeoPoint?) {
        lastGhost = point
        // Fetch from the CURRENT style by id (field refs go stale after a restyle; see drawFollow).
        val src = map.style?.getSourceAs<GeoJsonSource>(GHOST_SOURCE) ?: return
        if (point == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        } else {
            src.setGeoJson(
                FeatureCollection.fromFeatures(
                    listOf(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))),
                ),
            )
        }
    }

    private var trackUpdateCount = 0

    fun updateTrack(segments: List<Segment>, frame: Boolean) {
        lastTrackSegments = segments
        // High-frequency (1/s while recording): log a heartbeat every 30 updates.
        if (trackUpdateCount++ % 30 == 0) {
            cat.rumb.app.data.debug.DebugLog.d(
                "Map",
                "updateTrack #$trackUpdateCount · ${segments.size} segments · ${segments.sumOf { it.size }} punts · mode=$trackColorMode",
            )
        }
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
                // With step > 1 the loop stops short of the end; connect the last sampled point to the
                // real final point so the colored track doesn't end early on long segments.
                val lastConnected = i - step
                if (lastConnected in 0 until pts.size - 1) {
                    val a = pts[lastConnected].latLong!!
                    val b = pts.last().latLong!!
                    val v = trackColorMode.valueOf(pts.last()) ?: trackColorMode.valueOf(pts[lastConnected])
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
        cat.rumb.app.data.debug.DebugLog.d("Map", "setTrackColorMode · $mode · $colorHex")
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
    fun setFollowRouteStyle(
        colorHex: String,
        width: Float,
        arrows: Boolean,
        progress: Boolean,
        arrowColorHex: String = FOLLOW_COLOR,
        arrowSize: Float = 0.7f,
    ) {
        cat.rumb.app.data.debug.DebugLog.d("Map", "setFollowRouteStyle · $colorHex w=$width fletxes=$arrows progrés=$progress")
        // Guard against a malformed stored color (would make the line fail to render).
        followColorHex = colorHex.takeIf { it.startsWith("#") && (it.length == 7 || it.length == 9) } ?: FOLLOW_COLOR
        followWidth = width
        followArrows = arrows
        followProgress = progress
        followArrowColorHex = arrowColorHex.takeIf { it.startsWith("#") && (it.length == 7 || it.length == 9) } ?: FOLLOW_COLOR
        followArrowSize = arrowSize
        followLayer?.setProperties(
            PropertyFactory.lineColor(followColorHex),
            PropertyFactory.lineWidth(width),
            PropertyFactory.visibility(org.maplibre.android.style.layers.Property.VISIBLE),
        )
        followDoneLayer?.setProperties(PropertyFactory.lineWidth((width - 2f).coerceAtLeast(2f)))
        // Re-register the chevron icon in its own colour, then apply size + show/hide.
        map.style?.let { s ->
            runCatching { s.addImage(FOLLOW_ARROW_ICON, arrowBitmap(followArrowColorHex)) }
            (s.getLayer(FOLLOW_ARROW_LAYER) as? SymbolLayer)?.setProperties(
                PropertyFactory.iconSize(followArrowSize),
                PropertyFactory.visibility(if (arrows) Property.VISIBLE else Property.NONE),
            )
        }
        if (followPoints.isNotEmpty()) drawFollow(followPoints.size) // refresh remaining/done split
    }

    /** Draws the preloaded route to follow. Pass an empty list to clear it. */
    fun setFollowRoute(points: List<GeoPoint>) {
        followPoints = points
        // Draw once the style is ready so the source update isn't dropped on a not-yet-loaded style.
        map.getStyle {
            drawFollow(points.size)
            cat.rumb.app.data.debug.DebugLog.d("Map", "setFollowRoute · ${points.size} punts · ${followDebug()}")
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
        cat.rumb.app.data.debug.DebugLog.d("Map", "frameFollowRoute · ${pts.size} punts")
    }

    private var lastLoggedProgressIdx = -100

    /** Splits the route into traveled ("done") and remaining at [nearestIndex] when progress is on. */
    fun updateFollowProgress(nearestIndex: Int) {
        if (followProgress && followPoints.isNotEmpty()) {
            // Log progress in ~25-point strides to keep the debug log readable.
            if (kotlin.math.abs(nearestIndex - lastLoggedProgressIdx) >= 25) {
                cat.rumb.app.data.debug.DebugLog.d("Map", "progrés ruta · punt $nearestIndex/${followPoints.size}")
                lastLoggedProgressIdx = nearestIndex
            }
            drawFollow(nearestIndex.coerceIn(0, followPoints.size))
        }
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
            // Clear the chevrons too: returning early here left the previous competition's arrows
            // painted over the map after the route itself was gone.
            drawFollowArrows()
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
        drawFollowArrows()
    }

    /**
     * Direction chevrons spaced evenly ALONG the route (by distance, not by point index — GPX point
     * density varies wildly), each carrying the bearing of the leg it sits on so the icon rotates to
     * point the way the route runs.
     */
    private fun drawFollowArrows() {
        val src = map.style?.getSourceAs<GeoJsonSource>(FOLLOW_ARROW_SOURCE) ?: followArrowSource
        val pts = followPoints
        if (!followArrows || pts.size < 2) {
            src?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }
        val legs = (1 until pts.size).map { cat.rumb.app.viewer.hud.MetricsCalculator.distanceMeters(pts[it - 1], pts[it]) }
        val totalM = legs.sum()
        if (totalM <= 0.0) {
            src?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }
        val spacing = maxOf(MIN_FOLLOW_ARROW_SPACING_M, totalM / MAX_FOLLOW_ARROWS)
        val features = mutableListOf<Feature>()
        var travelled = 0.0
        var next = spacing / 2 // half a step in, so the first chevron isn't buried under the start
        for (i in 1 until pts.size) {
            val legM = legs[i - 1]
            if (legM <= 0.0) continue
            val a = pts[i - 1]
            val b = pts[i]
            val bearing = cat.rumb.app.viewer.hud.MetricsCalculator.bearing(a, b)
            while (next <= travelled + legM) {
                val t = (next - travelled) / legM
                features.add(
                    Feature.fromGeometry(
                        Point.fromLngLat(
                            a.longitude + (b.longitude - a.longitude) * t,
                            a.latitude + (b.latitude - a.latitude) * t,
                        ),
                    ).also { it.addNumberProperty("bearing", bearing) },
                )
                next += spacing
            }
            travelled += legM
        }
        src?.setGeoJson(FeatureCollection.fromFeatures(features))
        cat.rumb.app.data.debug.DebugLog.d("Map", "fletxes de sentit · ${features.size} cada ${"%.0f".format(spacing)} m")
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
            // newLatLngBounds needs the map measured; fall back to centering if it isn't yet.
            runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
                .onFailure { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(all[0].latitude, all[0].longitude), 13.0)) }
        }
        hasFramedTrack = true
        cat.rumb.app.data.debug.DebugLog.d("Map", "frameTrack · ${all.size} punts")
    }

    /**
     * Recenter on the most recent point (used while recording to follow the user). When [bearingDeg]
     * is provided and heading-up is on, the camera also rotates so the travel direction points up.
     */
    fun follow(segments: List<Segment>, bearingDeg: Double? = null, zoom: Double? = null) {
        val last = segments.lastOrNull()?.lastOrNull { it.latLong != null }?.latLong ?: return
        val target = LatLng(last.latitude, last.longitude)
        val needsPos = (headingUp && bearingDeg != null) || zoom != null
        if (needsPos) {
            val builder = org.maplibre.android.camera.CameraPosition.Builder(map.cameraPosition).target(target)
            if (headingUp && bearingDeg != null) builder.bearing(bearingDeg)
            if (zoom != null) builder.zoom(zoom)
            map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLng(target))
        }
    }

    /** Current camera zoom (for adaptive-zoom hysteresis). */
    val currentZoom: Double get() = map.cameraPosition.zoom

    /** Whether the camera rotates to the travel direction (heading-up) instead of staying north-up. */
    var headingUp: Boolean = false
        private set

    fun setHeadingUp(enabled: Boolean) {
        if (headingUp != enabled) cat.rumb.app.data.debug.DebugLog.d("Map", "orientació · ${if (enabled) "segons direcció" else "nord amunt"}")
        headingUp = enabled
    }

    // --- Interactive map controls (HUD) ---

    fun zoomIn() = map.animateCamera(CameraUpdateFactory.zoomIn())
    fun zoomOut() = map.animateCamera(CameraUpdateFactory.zoomOut())

    /** Enables the device-location blue dot (caller must hold the location permission). */
    @android.annotation.SuppressLint("MissingPermission")
    fun enableLocation(context: android.content.Context) {
        map.getStyle { style ->
            val lc = map.locationComponent
            if (!lc.isLocationComponentActivated) {
                // Hide the built-in puck (transparent drawables + no accuracy ring): the location
                // engine still runs and feeds lastKnownLocation, but our own marker is what's drawn.
                val opts = org.maplibre.android.location.LocationComponentOptions.builder(context.applicationContext)
                    .foregroundDrawable(cat.rumb.app.R.drawable.ic_transparent)
                    .backgroundDrawable(cat.rumb.app.R.drawable.ic_transparent)
                    .bearingDrawable(cat.rumb.app.R.drawable.ic_transparent)
                    .accuracyAlpha(0f)
                    .build()
                lc.activateLocationComponent(
                    org.maplibre.android.location.LocationComponentActivationOptions
                        .builder(context.applicationContext, style)
                        .useDefaultLocationEngine(true)
                        .locationComponentOptions(opts)
                        .build(),
                )
            }
            lc.isLocationComponentEnabled = true
            lc.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
            lc.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
            // Predictive prefetch: proactively fetch surrounding parent tiles into the ambient cache
            // so panning/moving (without a followed track) shows the next area from cache.
            runCatching { map.setPrefetchZoomDelta(4) }
        }
    }

    /** Centers on the user's current GPS location if available. Returns false if unknown. */
    @android.annotation.SuppressLint("MissingPermission")
    fun recenterOnLocation(context: android.content.Context): Boolean {
        val loc = runCatching { map.locationComponent.lastKnownLocation }.getOrNull()
            ?: lastKnownFromSystem(context)
        cat.rumb.app.data.debug.DebugLog.d("Map", "recentrar · fix=${loc != null}" + (loc?.let { " acc=${it.accuracy}m" } ?: ""))
        if (loc == null) return false
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0))
        return true
    }

    /** Centers the camera on an explicit point at [zoom] (used for the initial "where am I" framing). */
    fun centerOn(lat: Double, lon: Double, zoom: Double = 15.0) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom))
    }

    /** Current GPS accuracy (m) from the location component or the OS providers, or null. */
    @android.annotation.SuppressLint("MissingPermission")
    fun currentAccuracyM(context: android.content.Context): Float? {
        val loc = runCatching { map.locationComponent.lastKnownLocation }.getOrNull()
            ?: lastKnownFromSystem(context) ?: return null
        return if (loc.hasAccuracy()) loc.accuracy else null
    }

    /** Last known position as a GeoPoint (location component, then OS providers), or null. */
    @android.annotation.SuppressLint("MissingPermission")
    fun lastKnownGeoPoint(context: android.content.Context): cat.rumb.app.data.opentracks.model.GeoPoint? {
        val loc = runCatching { map.locationComponent.lastKnownLocation }.getOrNull()
            ?: lastKnownFromSystem(context) ?: return null
        return cat.rumb.app.data.opentracks.model.GeoPoint(loc.latitude, loc.longitude)
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
        runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 40)) }
            .onFailure { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng((north + south) / 2, (east + west) / 2), 12.0)) }
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
