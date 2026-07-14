package cat.rumb.app.manager.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cat.rumb.app.R
import cat.rumb.app.data.map.BoundingBox
import cat.rumb.app.data.map.CatalanRegions
import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.map.RegionDownloadWorker
import cat.rumb.app.data.map.TileMath
import kotlin.math.roundToLong

private enum class AreaMode { PROVINCE, VISIBLE, DRAW, ROUTE }

private const val TILE_LIMIT = 60_000L
private const val KB_PER_TILE = 25.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadAreaScreen(onBack: () -> Unit, initialBbox: BoundingBox? = null) {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle()
    val requestNotif = rememberNotificationPermission()

    var controller by remember { mutableStateOf<AreaSelectController?>(null) }
    var mode by remember { mutableStateOf(if (initialBbox != null) AreaMode.ROUTE else AreaMode.PROVINCE) }
    var bbox by remember { mutableStateOf(initialBbox) }
    var source by remember { mutableStateOf(MapSource.ICGC_TOPO) }
    var zoom by remember { mutableStateOf(9f..14f) }

    // Rectangle-drawing overlay state.
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(RegionDownloadWorker.WORK_NAME)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val active = workInfos.firstOrNull { !it.state.isFinished }
    val succeeded = workInfos.any { it.state == WorkInfo.State.SUCCEEDED }

    val rasterSources = remember { MapSource.entries.filter { it.kind == MapSource.Kind.RASTER && it.offlineAllowed } }
    val minZoom = zoom.start.roundToLong().toInt()
    val maxZoom = zoom.endInclusive.roundToLong().toInt()
    val tiles = bbox?.let { TileMath.tileCount(it, minZoom, maxZoom) } ?: 0L
    val overLimit = tiles > TILE_LIMIT

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.maps_download_area)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.maps_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = {
                        mapView.getMapAsync { map ->
                            val c = AreaSelectController(map)
                            controller = c
                            c.init {
                                // Pre-select and frame the route's area when opened from a route.
                                initialBbox?.let { c.showSelection(it); c.fitBounds(it) }
                            }
                        }
                        mapView
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Rectangle drawing overlay (only in DRAW mode; captures the box gesture).
                if (mode == AreaMode.DRAW) {
                    Box(
                        Modifier.fillMaxSize().pointerInput(controller) {
                            detectDragGestures(
                                onDragStart = { dragStart = it; dragCurrent = it },
                                onDrag = { change, _ -> dragCurrent = change.position },
                                onDragEnd = {
                                    val s = dragStart; val e = dragCurrent
                                    if (s != null && e != null) {
                                        controller?.screenRectToBbox(s.x, s.y, e.x, e.y)?.let {
                                            bbox = it; controller?.showSelection(it)
                                        }
                                    }
                                    dragStart = null; dragCurrent = null
                                },
                            )
                        },
                    ) {
                        val s = dragStart; val e = dragCurrent
                        if (s != null && e != null) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(
                                    color = Color(0x33E63946),
                                    topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                                    size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)),
                                )
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Selection mode.
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (initialBbox != null) {
                            FilterChip(mode == AreaMode.ROUTE, {
                                mode = AreaMode.ROUTE; bbox = initialBbox
                                controller?.let { it.showSelection(initialBbox); it.fitBounds(initialBbox) }
                            }, label = { Text(stringResource(R.string.maps_mode_route)) })
                        }
                        FilterChip(mode == AreaMode.PROVINCE, { mode = AreaMode.PROVINCE }, label = { Text(stringResource(R.string.maps_mode_province)) })
                        FilterChip(mode == AreaMode.VISIBLE, {
                            mode = AreaMode.VISIBLE
                            controller?.let { bbox = it.visibleBounds(); it.showSelection(bbox) }
                        }, label = { Text(stringResource(R.string.maps_mode_visible)) })
                        FilterChip(mode == AreaMode.DRAW, { mode = AreaMode.DRAW }, label = { Text(stringResource(R.string.maps_mode_draw)) })
                    }
                    controller?.setGesturesEnabled(mode != AreaMode.DRAW)
                    if (mode == AreaMode.ROUTE) {
                        Text(stringResource(R.string.maps_route_hint),
                            style = MaterialTheme.typography.bodySmall)
                    }

                    if (mode == AreaMode.PROVINCE) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CatalanRegions.all.forEach { region ->
                                FilterChip(
                                    selected = bbox == region.bbox,
                                    onClick = { bbox = region.bbox; controller?.showSelection(region.bbox); controller?.fitBounds(region.bbox) },
                                    label = { Text(region.name) },
                                )
                            }
                        }
                    }
                    if (mode == AreaMode.VISIBLE) {
                        Text(stringResource(R.string.maps_visible_hint),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (mode == AreaMode.DRAW) {
                        Text(stringResource(R.string.maps_draw_hint),
                            style = MaterialTheme.typography.bodySmall)
                    }

                    // Source.
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rasterSources.forEach { s ->
                            FilterChip(source == s, {
                                source = s
                                if (zoom.endInclusive > s.maxZoom) zoom = zoom.start..s.maxZoom.toFloat()
                            }, label = { Text(s.displayName) })
                        }
                    }

                    Text(stringResource(R.string.maps_zoom_range, minZoom, maxZoom), style = MaterialTheme.typography.labelLarge)
                    RangeSlider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        valueRange = 6f..source.maxZoom.toFloat(),
                        steps = (source.maxZoom - 6 - 1).coerceAtLeast(0),
                    )

                    if (bbox != null) {
                        val mb = tiles * KB_PER_TILE / 1024.0
                        Text(
                            stringResource(R.string.maps_estimate, tiles, mb),
                            color = if (overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        if (overLimit) Text(stringResource(R.string.maps_over_limit, TILE_LIMIT / 1000),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (source == MapSource.OSM) Text(
                            stringResource(R.string.maps_osm_warning),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (active != null) {
                        val done = active.progress.getLong(RegionDownloadWorker.KEY_DONE, 0)
                        val total = active.progress.getLong(RegionDownloadWorker.KEY_TOTAL, 1).coerceAtLeast(1)
                        Text(stringResource(R.string.maps_downloading, done, total))
                        LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        if (succeeded) Text(stringResource(R.string.maps_download_done))
                        Button(
                            onClick = {
                                bbox?.let {
                                    requestNotif()
                                    RegionDownloadWorker.enqueue(
                                        context, source.id, context.getString(R.string.maps_area_name, source.displayName), it, minZoom, maxZoom,
                                    )
                                }
                            },
                            enabled = bbox != null && !overLimit,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.maps_download)) }
                    }
                }
            }
        }
    }
}
