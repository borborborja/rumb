package cat.rumb.app.manager.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import cat.rumb.app.RumbApplication
import cat.rumb.app.R
import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.data.tracks.FollowTrackEntity
import cat.rumb.app.data.tracks.TrackRepository
import cat.rumb.app.viewer.hud.ElevationProfile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun RouteDetailScreen(
    trackId: Long,
    onBack: () -> Unit,
    onEditTrace: (Long) -> Unit,
    onDownloadMap: (cat.rumb.app.data.map.BoundingBox) -> Unit,
    onOpenTraining: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    val prefs = remember { ViewerPreferences.get(context) }
    val scope = rememberCoroutineScope()
    val mapView = rememberMapViewWithLifecycle()

    var entity by remember { mutableStateOf<FollowTrackEntity?>(null) }
    var gpx by remember { mutableStateOf<List<GpxPoint>>(emptyList()) }
    var controller by remember { mutableStateOf<RouteEditorController?>(null) }
    var mapSourceId by remember { mutableStateOf(prefs.statsMapSourceId) }
    var history by remember { mutableStateOf<List<FollowTrackEntity>>(emptyList()) }
    var reloadTick by remember { mutableStateOf(0) }
    var showRename by remember { mutableStateOf(false) }
    var showCollection by remember { mutableStateOf(false) }
    var showType by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(trackId, reloadTick) {
        entity = app.trackRepository.get(trackId)
        gpx = app.trackRepository.loadGpxRoute(trackId)
        history = app.trackRepository.trainingsForRoute(trackId)
    }
    // Draw the route once both the map and the data are ready.
    LaunchedEffect(controller, gpx) {
        val c = controller ?: return@LaunchedEffect
        if (gpx.size >= 2) { c.setRoute(gpx); c.frame(gpx) }
    }

    DetailScaffold(
        title = entity?.name ?: stringResource(R.string.routes_route_fallback_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { onEditTrace(trackId) }) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.routes_edit_trace))
            }
        },
    ) { modifier ->
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                AndroidView(
                    factory = {
                        mapView.getMapAsync { map ->
                            val c = RouteEditorController(map)
                            controller = c
                            c.init(source = MapSource.byId(mapSourceId)) { }
                        }
                        mapView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                BaseMapPicker(
                    currentId = mapSourceId,
                    onSelect = { src -> mapSourceId = src.id; prefs.statsMapSourceId = src.id; controller?.setBaseMap(src) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }

            val e = entity
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat(
                            stringResource(R.string.routes_stat_distance),
                            stringResource(R.string.routes_distance_km_format, (e?.distanceMeters ?: 0.0) / 1000.0),
                        )
                        Stat(
                            stringResource(R.string.routes_stat_ascent),
                            stringResource(R.string.routes_ascent_m_format, TrackRepository.ascent(gpx).toInt()),
                        )
                        Stat(stringResource(R.string.routes_stat_points), "${e?.pointCount ?: gpx.size}")
                    }
                }
                Text(
                    stringResource(R.string.routes_source_collection, e?.source?.name ?: "—", e?.collection ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                )

                // A route is a template: only its elevation profile is meaningful (no time/speed/HR).
                ElevationProfile(
                    profile = gpx.mapNotNull { it.elevation?.toFloat() },
                    progress = 1f,
                    scale = 1f,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )

                // History of trainings recorded while following this route, each linking to its stats.
                if (history.isNotEmpty()) {
                    Text(
                        stringResource(R.string.routes_history_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    history.forEach { t -> RouteHistoryRow(t, onClick = { onOpenTraining(t.id) }) }
                }

                if (entity?.archived == true) {
                    Button(
                        onClick = { scope.launch { app.trackRepository.setArchived(trackId, false); reloadTick++ } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.home_unarchive)) }
                }
                if (entity?.archived != true) {
                Button(onClick = {
                    prefs.activeFollowTrackId = trackId
                    Toast.makeText(context, context.getString(R.string.routes_active_follow_toast), Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.routes_follow_this_route)) }

                OutlinedButton(onClick = { onEditTrace(trackId) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Text("  " + stringResource(R.string.routes_edit_trace))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showRename = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.routes_rename)) }
                    OutlinedButton(onClick = { showCollection = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.routes_collection)) }
                    OutlinedButton(onClick = { showType = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.routes_type)) }
                }

                OutlinedButton(
                    onClick = {
                        scope.launch { app.trackRepository.routeBoundingBox(trackId)?.let(onDownloadMap) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Text("  " + stringResource(R.string.routes_download_route_map))
                }

                OutlinedButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text("  " + stringResource(R.string.routes_delete), color = MaterialTheme.colorScheme.error)
                }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showRename) {
        TextFieldDialog(
            title = stringResource(R.string.routes_rename_route_title),
            initial = entity?.name ?: "",
            onConfirm = { newName ->
                scope.launch { app.trackRepository.rename(trackId, newName); reloadTick++ }
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }
    if (showCollection) {
        TextFieldDialog(
            title = stringResource(R.string.routes_collection),
            initial = entity?.collection ?: "General",
            onConfirm = { c ->
                scope.launch { app.trackRepository.setCollection(trackId, c); reloadTick++ }
                showCollection = false
            },
            onDismiss = { showCollection = false },
        )
    }
    if (showType) {
        val typeOptions = rememberActivityTypeOptions(prefs)
        AlertDialog(
            onDismissRequest = { showType = false },
            title = { Text(stringResource(R.string.routes_assign_type_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    typeOptions.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.trackRepository.setActivityType(trackId, option.id); reloadTick++ }
                                    showType = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Icon(
                                option.icon,
                                contentDescription = null,
                                tint = if (entity?.activityType == option.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                            Text("  " + option.label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showType = false }) { Text(stringResource(R.string.routes_cancel)) } },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.routes_delete_route_title)) },
            text = { Text(stringResource(R.string.routes_delete_confirm, entity?.name ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.trackRepository.delete(trackId); onBack() }
                }) { Text(stringResource(R.string.routes_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.routes_cancel)) } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** One follow-history entry: date · distance · time, tapping opens that training's statistics. */
@Composable
private fun RouteHistoryRow(t: FollowTrackEntity, onClick: () -> Unit) {
    val date = remember(t.createdAt) {
        DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(t.createdAt))
    }
    val km = t.distanceMeters / 1000.0
    val secs = (t.durationMs ?: 0L) / 1000L
    val time = when {
        secs <= 0L -> "—"
        secs >= 3600 -> "%d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60)
        else -> "%d:%02d".format(secs / 60, secs % 60)
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(date, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.routes_distance_km_format, km) + " · " + time,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TextFieldDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(stringResource(R.string.routes_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.routes_cancel)) } },
    )
}
