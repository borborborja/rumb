package cat.hudpro.opentracks.manager.screens

import android.graphics.PointF
import cat.hudpro.opentracks.data.map.BoundingBox
import cat.hudpro.opentracks.data.map.MapSource
import cat.hudpro.opentracks.data.map.MapStyleFactory
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Draws the selection rectangle over the map and exposes screen↔geo helpers for area picking. */
class AreaSelectController(private val map: MapLibreMap) {

    private var selSource: GeoJsonSource? = null

    fun init(onReady: () -> Unit) {
        map.setStyle(Style.Builder().fromJson(MapStyleFactory.rasterStyleJson(MapSource.ICGC_TOPO))) { style ->
            val src = GeoJsonSource(SEL_SOURCE, FeatureCollection.fromFeatures(emptyList()))
            style.addSource(src)
            style.addLayer(
                LineLayer(SEL_LAYER, SEL_SOURCE).withProperties(
                    PropertyFactory.lineColor("#E63946"),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                ),
            )
            selSource = src
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(41.7, 1.8), 7.0))
            onReady()
        }
    }

    fun showSelection(bbox: BoundingBox?) {
        val features = if (bbox != null && bbox.isValid) {
            val ring = listOf(
                Point.fromLngLat(bbox.west, bbox.south),
                Point.fromLngLat(bbox.east, bbox.south),
                Point.fromLngLat(bbox.east, bbox.north),
                Point.fromLngLat(bbox.west, bbox.north),
                Point.fromLngLat(bbox.west, bbox.south),
            )
            listOf(Feature.fromGeometry(LineString.fromLngLats(ring)))
        } else {
            emptyList()
        }
        selSource?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun fitBounds(bbox: BoundingBox) {
        val bounds = LatLngBounds.Builder()
            .include(LatLng(bbox.north, bbox.east))
            .include(LatLng(bbox.south, bbox.west))
            .build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60))
    }

    fun visibleBounds(): BoundingBox {
        val v = map.projection.visibleRegion.latLngBounds
        return BoundingBox(
            west = v.longitudeWest, south = v.latitudeSouth,
            east = v.longitudeEast, north = v.latitudeNorth,
        )
    }

    /** Converts two screen points (px) to a geographic bbox. */
    fun screenRectToBbox(x1: Float, y1: Float, x2: Float, y2: Float): BoundingBox {
        val a = map.projection.fromScreenLocation(PointF(x1, y1))
        val b = map.projection.fromScreenLocation(PointF(x2, y2))
        return BoundingBox(
            west = minOf(a.longitude, b.longitude),
            south = minOf(a.latitude, b.latitude),
            east = maxOf(a.longitude, b.longitude),
            north = maxOf(a.latitude, b.latitude),
        )
    }

    fun setGesturesEnabled(enabled: Boolean) {
        map.uiSettings.setAllGesturesEnabled(enabled)
    }

    private companion object {
        const val SEL_SOURCE = "area-sel-source"
        const val SEL_LAYER = "area-sel-layer"
    }
}
