package cat.hudpro.opentracks.viewer

import cat.hudpro.opentracks.data.map.MapSource
import cat.hudpro.opentracks.data.map.MapStyleFactory
import cat.hudpro.opentracks.data.opentracks.model.Segment
import cat.hudpro.opentracks.data.opentracks.model.Waypoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
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
    private var waypointSource: GeoJsonSource? = null
    private var followSource: GeoJsonSource? = null
    private var hasFramedTrack = false

    private companion object {
        const val TRACK_SOURCE = "track-source"
        const val TRACK_LAYER = "track-layer"
        const val WAYPOINT_SOURCE = "waypoint-source"
        const val WAYPOINT_LAYER = "waypoint-layer"
        const val FOLLOW_SOURCE = "follow-source"
        const val FOLLOW_LAYER = "follow-layer"
        const val TRACK_COLOR = "#E63946"
        const val FOLLOW_COLOR = "#3A86FF"
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
            onReady()
        }
    }

    private fun addOverlayLayers(style: Style) {
        // Follow route drawn first, so the live recorded track renders on top of it.
        val follow = GeoJsonSource(FOLLOW_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(follow)
        style.addLayer(
            LineLayer(FOLLOW_LAYER, FOLLOW_SOURCE).withProperties(
                PropertyFactory.lineColor(FOLLOW_COLOR),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOpacity(0.7f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        followSource = follow

        val track = GeoJsonSource(TRACK_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(track)
        style.addLayer(
            LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
                PropertyFactory.lineColor(TRACK_COLOR),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        trackSource = track

        val waypoints = GeoJsonSource(WAYPOINT_SOURCE, FeatureCollection.fromFeatures(emptyList()))
        style.addSource(waypoints)
        style.addLayer(
            SymbolLayer(WAYPOINT_LAYER, WAYPOINT_SOURCE).withProperties(
                PropertyFactory.textField("●"),
                PropertyFactory.textColor("#1D3557"),
                PropertyFactory.textSize(18f),
                PropertyFactory.textAllowOverlap(true),
            ),
        )
        waypointSource = waypoints
    }

    fun updateTrack(segments: List<Segment>, frame: Boolean) {
        val features = segments.mapNotNull { segment ->
            val points = segment.mapNotNull { tp -> tp.latLong?.let { Point.fromLngLat(it.longitude, it.latitude) } }
            if (points.size >= 2) Feature.fromGeometry(LineString.fromLngLats(points)) else null
        }
        trackSource?.setGeoJson(FeatureCollection.fromFeatures(features))

        if (frame && !hasFramedTrack) {
            frameTrack(segments)
        }
    }

    /** Draws the preloaded route to follow (static). Pass an empty list to clear it. */
    fun setFollowRoute(points: List<cat.hudpro.opentracks.data.opentracks.model.GeoPoint>) {
        val features = if (points.size >= 2) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })))
        } else {
            emptyList()
        }
        followSource?.setGeoJson(FeatureCollection.fromFeatures(features))
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

    /** Recenter on the most recent point (used while recording to follow the user). */
    fun follow(segments: List<Segment>) {
        val last = segments.lastOrNull()?.lastOrNull { it.latLong != null }?.latLong ?: return
        map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(last.latitude, last.longitude)))
    }

    // --- Interactive map controls (HUD) ---

    fun zoomIn() = map.animateCamera(CameraUpdateFactory.zoomIn())
    fun zoomOut() = map.animateCamera(CameraUpdateFactory.zoomOut())

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
