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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cat.rumb.app.R
import cat.rumb.app.data.map.MapDisplayConfig
import cat.rumb.app.data.map.MapDisplayStore
import cat.rumb.app.data.map.MapKeyVerifier
import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.map.MapStyleFactory
import cat.rumb.app.data.map.OfflineMap
import cat.rumb.app.data.map.OfflineMapStore
import cat.rumb.app.data.map.TileApiKeys
import cat.rumb.app.data.prefs.ViewerPreferences
import kotlinx.coroutines.launch
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
    var keyDialogFor by remember { mutableStateOf<MapSource?>(null) }
    // Bumped after a key is saved/cleared so the affected row re-reads prefs and enables/disables.
    var keyVersion by remember { mutableIntStateOf(0) }

    Text(stringResource(R.string.maps_base_map_title), style = MaterialTheme.typography.titleSmall)
    MapSource.entries.forEach { source ->
        val provider = source.apiKeyProvider
        val storedKey = remember(keyVersion, provider) { provider?.let { prefs.mapApiKeyFor(it) } }
        // Keyed maps stay disabled until a key is stored (only stored after a successful verify).
        val enabled = provider == null || storedKey != null
        val labelColor =
            if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Card {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (enabled) {
                            Modifier.selectable(selected = current == source.id, onClick = { onSelect(source.id) })
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = enabled && current == source.id, onClick = null, enabled = enabled)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(source.displayName, style = MaterialTheme.typography.bodyLarge, color = labelColor)
                    Text(
                        if (enabled) source.attribution else stringResource(R.string.maps_needs_api_key),
                        style = MaterialTheme.typography.bodySmall,
                        color = labelColor,
                    )
                }
                // Keyed maps: a key icon to enter/manage the API key (the only affordance until keyed).
                if (provider != null) {
                    IconButton(onClick = { keyDialogFor = source }) {
                        Icon(Icons.Filled.Key, contentDescription = stringResource(R.string.maps_manage_key))
                    }
                }
                // The pencil configures how THIS map is shown (detail/grayscale/dim), independent of
                // which map is selected — you can tune one you're not currently using.
                if (enabled) {
                    IconButton(onClick = { editing = source }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.maps_display_edit))
                    }
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

    keyDialogFor?.let { source ->
        val provider = source.apiKeyProvider!!
        ApiKeyDialog(
            source = source,
            initial = prefs.mapApiKeyFor(provider),
            onDismiss = { keyDialogFor = null },
            onSaved = { key ->
                prefs.setMapApiKeyFor(provider, key)
                TileApiKeys.set(provider, key)
                if (key == null && current == source.id) onSelect(MapSource.DEFAULT.id) // don't leave a disabled map selected
                keyVersion++
                keyDialogFor = null
            },
        )
    }
}

/**
 * Enters/verifies a tile-provider API key. The key is only handed back (and thus stored + the map
 * enabled) once [MapKeyVerifier] confirms it fetches a tile; a bad key shows an error and stays.
 */
@Composable
private fun ApiKeyDialog(
    source: MapSource,
    initial: String?,
    onDismiss: () -> Unit,
    onSaved: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var key by remember { mutableStateOf(initial.orEmpty()) }
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val getKeyUrl = when (source.apiKeyProvider) {
        "tracestrack" -> "https://tracestrack.com/"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(source.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.maps_api_key_help), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; failed = false },
                    singleLine = true,
                    label = { Text(stringResource(R.string.maps_api_key_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (failed) {
                    Text(
                        stringResource(R.string.maps_api_key_bad),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (getKeyUrl != null) {
                    TextButton(onClick = { uriHandler.openUri(getKeyUrl) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(stringResource(R.string.maps_api_key_get))
                    }
                }
                if (initial != null) {
                    TextButton(onClick = { onSaved(null) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(stringResource(R.string.maps_api_key_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = key.isNotBlank() && !checking,
                onClick = {
                    checking = true
                    failed = false
                    scope.launch {
                        val ok = MapKeyVerifier.verify(source, key.trim())
                        checking = false
                        if (ok) onSaved(key.trim()) else failed = true
                    }
                },
            ) {
                Text(stringResource(if (checking) R.string.maps_api_key_verifying else R.string.maps_api_key_verify))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.maps_cancel)) } },
    )
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
    // Only re-style when the styling inputs actually change, not on every recomposition.
    val styleJson = remember(source, config) { MapStyleFactory.rasterStyleJson(source, config) }
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
        update = {
            it.getMapAsync { map ->
                map.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(styleJson))
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
