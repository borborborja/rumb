package cat.rumb.app.manager.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cat.rumb.app.R
import cat.rumb.app.data.map.MapDisplayConfig
import cat.rumb.app.data.map.MapDisplayStore
import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.map.MapStyleFactory
import cat.rumb.app.data.map.OfflineMap
import cat.rumb.app.data.map.OfflineMapStore
import cat.rumb.app.data.prefs.ViewerPreferences
import java.io.File

@Composable
fun MapLayersScreen(
    onBack: () -> Unit,
    onDownloadArea: () -> Unit = {},
    onOpenSectors: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { ViewerPreferences.get(context) }
    val store = remember { OfflineMapStore.get(context) }
    var tab by remember { mutableIntStateOf(0) }
    var baseMapId by remember { mutableStateOf(prefs.baseMapId) }

    DetailScaffold(title = stringResource(R.string.maps_title), onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.maps_tab_online)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.maps_tab_offline)) })
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (tab == 0) {
                    OnlineTab(current = baseMapId) { id -> baseMapId = id; prefs.baseMapId = id }
                } else {
                    OfflineTab(
                        store = store,
                        current = baseMapId,
                        onUse = { id -> baseMapId = id; prefs.baseMapId = id },
                        onDownloadArea = onDownloadArea,
                        onOpenSectors = onOpenSectors,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineTab(current: String?, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ViewerPreferences.get(context) }
    var editing by remember { mutableStateOf<MapSource?>(null) }

    Text(stringResource(R.string.maps_base_map_title), style = MaterialTheme.typography.titleSmall)
    MapSource.entries.forEach { source ->
        Card {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = current == source.id, onClick = { onSelect(source.id) })
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == source.id, onClick = null)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(source.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(source.attribution, style = MaterialTheme.typography.bodySmall)
                }
                // The pencil configures how THIS map is shown (detail/grayscale/dim), independent of
                // which map is selected — you can tune one you're not currently using.
                IconButton(onClick = { editing = source }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.maps_display_edit))
                }
            }
        }
    }

    editing?.let { source ->
        MapDisplayDialog(
            source = source,
            initial = MapDisplayStore.load(prefs, source.id),
            onDismiss = { editing = null },
            onSave = { config -> MapDisplayStore.save(prefs, source.id, config); editing = null },
        )
    }
}

@Composable
private fun OfflineTab(
    store: OfflineMapStore,
    current: String?,
    onUse: (String) -> Unit,
    onDownloadArea: () -> Unit,
    onOpenSectors: (String) -> Unit,
) {
    val context = LocalContext.current
    var maps by remember { mutableStateOf(store.list()) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.maps_default_map_name)
            runCatching { store.import(context.contentResolver, uri, name) }
            maps = store.list()
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onDownloadArea, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Map, contentDescription = null)
            Text("  " + stringResource(R.string.maps_download_area))
        }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Text("  " + stringResource(R.string.maps_import_mbtiles))
        }
    }

    if (maps.isEmpty()) {
        Text(
            stringResource(R.string.maps_empty_offline),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }

    maps.forEach { map ->
        OfflineRow(
            map = map,
            sectorCount = store.sectorsOf(map).size,
            isActive = current == map.selectionId,
            onUse = { onUse(map.selectionId) },
            onManage = { onOpenSectors(map.path) },
            onDelete = { store.delete(map); maps = store.list() },
        )
    }
}

/**
 * Per-map display options with a live preview. Detail caps the source maxzoom (overzoom → fewer,
 * larger features); the preview sits at a high fixed zoom so that upscaling is visible. Grayscale
 * and dim are raster paint. The detail slider's range is each source's own headroom.
 */
@Composable
private fun MapDisplayDialog(
    source: MapSource,
    initial: MapDisplayConfig,
    onDismiss: () -> Unit,
    onSave: (MapDisplayConfig) -> Unit,
) {
    var detail by remember { mutableIntStateOf(initial.detailReduction) }
    var grayscale by remember { mutableStateOf(initial.grayscale) }
    var opacity by remember { mutableFloatStateOf(initial.opacity) }
    val config = MapDisplayConfig(detail, grayscale, opacity)
    val maxDetail = MapDisplayConfig.maxDetailReductionFor(source.maxZoom)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(source.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapDisplayPreview(
                    source, config,
                    Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)),
                )
                if (maxDetail > 0) {
                    Text(stringResource(R.string.maps_display_detail), style = MaterialTheme.typography.labelMedium)
                    // A slider with one notch per overzoom level — honest about the integer steps.
                    // Left = more detail (native), right = less. Stored as a reduction, so invert.
                    Slider(
                        value = (maxDetail - detail).toFloat(),
                        onValueChange = { detail = maxDetail - it.toInt() },
                        valueRange = 0f..maxDetail.toFloat(),
                        steps = (maxDetail - 1).coerceAtLeast(0),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.maps_display_detail_less), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.maps_display_detail_more), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.maps_display_grayscale), Modifier.weight(1f))
                    Switch(checked = grayscale, onCheckedChange = { grayscale = it })
                }
                Text(stringResource(R.string.maps_display_dim), style = MaterialTheme.typography.labelMedium)
                // opacity 1 = no dim; slider runs "none → strong", i.e. opacity 1.0 → 0.4.
                Slider(
                    value = 1f - opacity,
                    onValueChange = { opacity = 1f - it },
                    valueRange = 0f..0.6f,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(config) }) { Text(stringResource(R.string.maps_display_save)) } },
        dismissButton = {
            TextButton(onClick = { onSave(MapDisplayConfig.DEFAULT) }) {
                Text(stringResource(R.string.maps_display_reset))
            }
        },
    )
}

/** A small non-interactive MapLibre map that re-renders whenever [config] changes. */
@Composable
private fun MapDisplayPreview(source: MapSource, config: MapDisplayConfig, modifier: Modifier) {
    val mapView = rememberMapViewWithLifecycle(textureMode = true)
    AndroidView(
        factory = {
            mapView.getMapAsync { map ->
                map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                    .target(org.maplibre.android.geometry.LatLng(41.3874, 2.1686)) // dense: central Barcelona
                    .zoom(16.5) // above typical caps, so reduced detail visibly upscales
                    .build()
                map.uiSettings.setAllGesturesEnabled(false)
            }
            mapView
        },
        // update runs on every recomposition (i.e. every slider move) → restyle live.
        update = {
            it.getMapAsync { map ->
                map.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(MapStyleFactory.rasterStyleJson(source, config)))
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun OfflineRow(
    map: OfflineMap,
    sectorCount: Int,
    isActive: Boolean,
    onUse: () -> Unit,
    onManage: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isActive, onClick = onUse)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(map.name, style = MaterialTheme.typography.bodyLarge)
                    val mb = File(map.path).length() / (1024.0 * 1024.0)
                    Text(
                        stringResource(R.string.maps_size_sectors, mb, sectorCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.maps_delete)) }
            }
            OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(stringResource(R.string.maps_manage_sectors))
            }
        }
    }
}
