package cat.rumb.app.manager.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.map.MapStyleFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * A MapLibre [MapView] whose lifecycle is driven by the current composition's lifecycle owner.
 * [textureMode] renders on a TextureView so rounded-corner clipping works (decorative maps).
 */
@Composable
fun rememberMapViewWithLifecycle(textureMode: Boolean = false): MapView {
    val context = LocalContext.current
    val mapView = remember {
        if (textureMode) {
            MapView(context, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(context).textureMode(true))
        } else {
            MapView(context)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        // Destroy the native MapView exactly once. Inside a NavHost the lifecycle owner is the
        // NavBackStackEntry, so popping this screen fires ON_DESTROY on the observer AND runs
        // onDispose — calling MapView.onDestroy() twice crashes on the already-freed native peer.
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> if (!destroyed) { destroyed = true; mapView.onDestroy() }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) {
                destroyed = true
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }
    return mapView
}

/** Non-interactive preview map (ICGC topogràfic over Catalonia) used behind the HUD designer. */
@Composable
fun MapPreview(modifier: Modifier = Modifier) {
    val mapView = rememberMapViewWithLifecycle()
    AndroidView(
        factory = {
            mapView.getMapAsync { map ->
                map.setStyle(Style.Builder().fromJson(MapStyleFactory.rasterStyleJson(MapSource.ICGC_TOPO)))
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(41.65, 1.95))
                    .zoom(10.5)
                    .build()
                map.uiSettings.setAllGesturesEnabled(false)
            }
            mapView
        },
        modifier = modifier,
    )
}
