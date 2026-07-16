package cat.rumb.app.manager.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import cat.rumb.app.data.map.MapSource
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cat.rumb.app.RumbApplication
import cat.rumb.app.R
import cat.rumb.app.data.gpx.GpxShare
import cat.rumb.app.data.opentracks.model.GeoPoint
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.data.tracks.ActivityTypes
import cat.rumb.app.data.tracks.Calories
import cat.rumb.app.data.tracks.Difficulty
import cat.rumb.app.data.tracks.DifficultyCalculator
import cat.rumb.app.data.tracks.FollowTrackEntity
import cat.rumb.app.data.tracks.TrackSample
import cat.rumb.app.data.tracks.TrackStats
import cat.rumb.app.data.tracks.TrackStatsCalculator
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Statistics-focused detail screen for a saved training: map with scrubber, stats grid, stacked charts. */
@Composable
fun TrainingDetailScreen(trackId: Long, onBack: () -> Unit, onCompare: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    val prefs = remember { ViewerPreferences.get(context) }
    val scope = rememberCoroutineScope()
    val mapView = rememberMapViewWithLifecycle()
    val custom = remember(prefs.customActivityTypesJson) { ActivityTypes.decodeCustom(prefs.customActivityTypesJson) }

    var entity by remember { mutableStateOf<FollowTrackEntity?>(null) }
    var stats by remember { mutableStateOf<TrackStats?>(null) }
    var samples by remember { mutableStateOf<List<TrackSample>>(emptyList()) }
    var points by remember { mutableStateOf<List<cat.rumb.app.data.gpx.GpxPoint>>(emptyList()) }
    var laps by remember { mutableStateOf<List<cat.rumb.app.data.tracks.LapRange>>(emptyList()) }
    var controller by remember { mutableStateOf<RouteEditorController?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    var showMenu by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showViewMenu by remember { mutableStateOf(false) }
    var mapSourceId by remember { mutableStateOf(prefs.statsMapSourceId) }
    var trackPaint by remember { mutableStateOf(prefs.statsTrackPaint) }
    var showRename by remember { mutableStateOf(false) }
    var showTypePicker by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<Float?>(null) }
    var editingLaps by remember { mutableStateOf(false) }

    LaunchedEffect(trackId, reloadTick) {
        val e = app.trackRepository.get(trackId)
        entity = e
        val pts = app.trackRepository.loadGpxRoute(trackId)
        val (s, smp) = withContext(Dispatchers.Default) {
            TrackStatsCalculator.compute(pts) to TrackStatsCalculator.samples(pts)
        }
        stats = s
        samples = smp
        points = pts
        laps = cat.rumb.app.data.tracks.Laps.decode(e?.laps)
    }
    // Draw the track once both the map and the data are ready; repaint when the paint mode changes.
    var framed by remember(trackId) { mutableStateOf(false) }
    LaunchedEffect(controller, samples, trackPaint) {
        val c = controller ?: return@LaunchedEffect
        if (samples.size >= 2) {
            val line = samples.map { cat.rumb.app.data.gpx.GpxPoint(it.lat, it.lon) }
            val values = when (trackPaint) {
                "ALTITUDE" -> samples.map { it.elevation?.toDouble() }
                "HR" -> samples.map { it.hr?.toDouble() }
                "SPEED" -> samples.map { it.speedKmh?.toDouble() }
                else -> null
            }
            c.setRoute(line, values)
            if (!framed) { c.frame(line); framed = true }
        }
    }
    // Mark where each lap begins (red dots), so a track with laps shows its lap starts on the map.
    LaunchedEffect(controller, points, laps) {
        val c = controller ?: return@LaunchedEffect
        val starts = laps.filter { it.kind == cat.rumb.app.data.tracks.LapKind.LAP }
            .mapNotNull { points.getOrNull(it.startIdx) }
            .map { GeoPoint(it.latitude, it.longitude) }
        c.setWaypoints(starts)
    }

    fun nearestSample(fraction: Float): TrackSample? {
        if (samples.isEmpty()) return null
        val target = fraction * samples.last().distM
        return samples.minByOrNull { kotlin.math.abs(it.distM - target) }
    }

    fun scrub(fraction: Float?) {
        highlight = fraction
        val sample = fraction?.let(::nearestSample)
        controller?.setHighlight(sample?.let { GeoPoint(it.lat, it.lon) })
    }

    DetailScaffold(
        title = entity?.name ?: stringResource(R.string.training_fallback_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showShare = true }) {
                Icon(Icons.Filled.IosShare, contentDescription = stringResource(R.string.training_action_export))
            }
            if (entity?.archived == true) {
                // Archived: stats stay visible, management is reduced to unarchive.
                IconButton(onClick = {
                    scope.launch { app.trackRepository.setArchived(trackId, false); reloadTick++ }
                }) {
                    Icon(Icons.Filled.Unarchive, contentDescription = stringResource(R.string.home_unarchive))
                }
            } else {
                IconButton(onClick = { showRename = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.training_action_rename))
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.training_cd_more_actions))
                }
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.training_action_duplicate_route)) },
                    onClick = {
                        showMenu = false
                        scope.launch {
                            app.trackRepository.duplicateAsRoute(trackId)
                            Toast.makeText(context, context.getString(R.string.training_duplicated_toast), Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.training_action_compare)) },
                    onClick = { showMenu = false; onCompare(trackId) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.training_action_type)) },
                    onClick = { showMenu = false; showTypePicker = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.training_action_move)) },
                    onClick = { showMenu = false; showMove = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.training_action_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { showMenu = false; showDelete = true },
                )
            }
        },
    ) { modifier ->
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                AndroidView(
                    factory = {
                        mapView.getMapAsync { map ->
                            val c = RouteEditorController(map)
                            controller = c
                            c.init(
                                source = MapSource.byId(mapSourceId),
                                config = cat.rumb.app.data.map.MapDisplayStore.load(prefs, mapSourceId ?: ""),
                            ) { }
                        }
                        mapView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Eye: base map + track paint selector, overlaid on the map corner.
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    IconButton(
                        onClick = { showViewMenu = true },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x99000000)),
                    ) {
                        Icon(
                            Icons.Filled.Layers,
                            contentDescription = stringResource(R.string.training_cd_view_options),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(expanded = showViewMenu, onDismissRequest = { showViewMenu = false }) {
                        Text(
                            stringResource(R.string.training_view_map),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        BaseMapMenu(mapSourceId) { src ->
                            mapSourceId = src.id
                            prefs.statsMapSourceId = src.id
                            controller?.setBaseMap(src, cat.rumb.app.data.map.MapDisplayStore.load(prefs, src.id))
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text(
                            stringResource(R.string.training_view_track),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        val paints = buildList {
                            add("SOLID" to R.string.training_paint_solid)
                            if (samples.any { it.elevation != null }) add("ALTITUDE" to R.string.training_paint_altitude)
                            if (samples.any { it.hr != null }) add("HR" to R.string.training_paint_hr)
                            if (samples.any { it.speedKmh != null }) add("SPEED" to R.string.training_paint_speed)
                        }
                        paints.forEach { (id, res) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(res)) },
                                leadingIcon = {
                                    if (trackPaint == id) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                                },
                                onClick = {
                                    trackPaint = id
                                    prefs.statsTrackPaint = id
                                },
                            )
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                entity?.let { e -> TrainingHeader(e, custom) }

                stats?.let { s ->
                    val kcal = Calories.kcal(
                        entity?.activityType, prefs.userWeightKg, s.movingTime ?: s.totalTime,
                        s.avgHr, prefs.userAge, prefs.userSex,
                    )
                    StatsCard(s, kcal)
                }

                if (cat.rumb.app.data.sync.SyncTargets.anyConfigured(context)) {
                    TrainingSyncRow(trackId, entity?.name ?: "", points, laps, entity?.activityType)
                }

                if (samples.size >= 2) {
                    Card {
                        StackedTrackChart(
                            samples = samples,
                            highlightFraction = highlight,
                            onScrub = ::scrub,
                            modifier = Modifier.fillMaxWidth().height(240.dp).padding(vertical = 8.dp),
                        )
                    }
                }

                val h = highlight
                if (h != null) {
                    nearestSample(h)?.let { sample -> ScrubInfoCard(sample, samples.firstOrNull()?.time) }
                }

                val lapList = laps.filter { it.kind == cat.rumb.app.data.tracks.LapKind.LAP }
                if (lapList.isNotEmpty() && points.size >= 2) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.training_laps_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { editingLaps = !editingLaps }) {
                            Icon(
                                if (editingLaps) Icons.Filled.Close else Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.laps_edit),
                            )
                        }
                    }
                    if (editingLaps) {
                        LapBoundaryEditor(
                            samples = samples,
                            points = points,
                            ranges = laps,
                            onCancel = { editingLaps = false },
                            onSave = { newRanges ->
                                scope.launch {
                                    app.trackRepository.setLaps(trackId, cat.rumb.app.data.tracks.Laps.encode(newRanges))
                                    editingLaps = false
                                    reloadTick++
                                }
                            },
                        )
                    } else {
                        LapsSection(
                            laps = lapList,
                            points = points,
                            showTitle = false,
                            onExportLap = { lap ->
                                val slice = points.subList(lap.startIdx.coerceIn(0, points.size), lap.endIdx.coerceIn(0, points.size))
                                if (slice.size >= 2) {
                                    val lapName = "${entity?.name ?: "track"} · ${context.getString(R.string.training_lap_n, lap.index)}"
                                    scope.launch { GpxShare.share(context, lapName, cat.rumb.app.data.gpx.Gpx.write(lapName, slice)) }
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showShare) {
        ShareFormatDialog(
            onDismiss = { showShare = false },
            onPick = { format ->
                showShare = false
                scope.launch {
                    val built = cat.rumb.app.data.gpx.TrackExport.build(
                        format, cat.rumb.app.data.sync.SyncTargets.safeName(entity?.name ?: "activitat"),
                        points, laps, entity?.activityType, prefs.userWeightKg, prefs.userAge, prefs.userSex,
                    )
                    GpxShare.shareFile(context, built.fileName, built.content, built.mime, entity?.name ?: "")
                }
            },
        )
    }
    if (showRename) {
        TextDialog(
            title = stringResource(R.string.training_action_rename),
            initial = entity?.name ?: "",
            confirm = stringResource(R.string.training_save),
            onDismiss = { showRename = false },
        ) { name ->
            showRename = false
            scope.launch { app.trackRepository.rename(trackId, name); reloadTick++ }
        }
    }
    if (showTypePicker) {
        val options = rememberActivityTypeOptions(prefs)
        AlertDialog(
            onDismissRequest = { showTypePicker = false },
            title = { Text(stringResource(R.string.training_action_type)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showTypePicker = false
                                    scope.launch { app.trackRepository.setActivityType(trackId, option.id); reloadTick++ }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(option.icon, contentDescription = null)
                            Text(option.label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTypePicker = false }) { Text(stringResource(R.string.training_cancel)) }
            },
        )
    }
    if (showMove) {
        MoveToFolderDialog(
            folders = prefs.foldersTraining.toList().sorted(),
            current = entity?.collection ?: ROOT,
            onDismiss = { showMove = false },
            onMove = { folder ->
                showMove = false
                scope.launch {
                    app.trackRepository.setCollection(trackId, folder)
                    if (folder != ROOT) prefs.foldersTraining = prefs.foldersTraining + folder
                    reloadTick++
                }
            },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.training_action_delete)) },
            text = { Text(stringResource(R.string.training_delete_confirm, entity?.name ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch { app.trackRepository.delete(trackId); onBack() }
                }) { Text(stringResource(R.string.training_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.training_cancel)) }
            },
        )
    }
}

/** Per-service sync status chips for this training + a manual sync/retry action. */
@Composable
private fun TrainingSyncRow(
    trackId: Long,
    name: String,
    points: List<cat.rumb.app.data.gpx.GpxPoint>,
    laps: List<cat.rumb.app.data.tracks.LapRange>,
    activityType: String?,
) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    val scope = rememberCoroutineScope()
    val rows by app.database.syncStatusDao().forTrack(trackId)
        .collectAsState(initial = emptyList())
    var confirmResync by remember { mutableStateOf(false) }

    fun doSync() {
        scope.launch {
            val up = ViewerPreferences.get(context)
            val built = cat.rumb.app.data.gpx.ActivityFile.build(
                cat.rumb.app.data.sync.SyncTargets.safeName(name), points, laps, activityType,
                up.userWeightKg, up.userAge, up.userSex,
            )
            cat.rumb.app.data.sync.SyncTargets.enqueueAll(context, trackId, built.fileName, built.content)
        }
    }
    val services = buildList {
        if (cat.rumb.app.data.prefs.EndurainPreferences.get(context).isConfigured) {
            add(cat.rumb.app.data.tracks.SyncService.ENDURAIN to "Endurain")
        }
        if (cat.rumb.app.data.prefs.WebDavPreferences.get(context).isConfigured) {
            add(cat.rumb.app.data.tracks.SyncService.WEBDAV to "WebDAV")
        }
        if (cat.rumb.app.data.prefs.FolderExportPreferences.get(context).isEnabled) {
            add(cat.rumb.app.data.tracks.SyncService.FOLDER to context.getString(R.string.settings_sync_folder))
        }
    }
    if (services.isEmpty()) return
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.training_sync_title), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                services.forEach { (svc, label) ->
                    val st = rows.firstOrNull { it.service == svc }?.status
                    val (mark, color) = when (st) {
                        cat.rumb.app.data.tracks.SyncState.UPLOADED -> "✓" to Color(0xFF2A9D8F)
                        cat.rumb.app.data.tracks.SyncState.PENDING -> "…" to MaterialTheme.colorScheme.onSurfaceVariant
                        cat.rumb.app.data.tracks.SyncState.FAILED -> "!" to MaterialTheme.colorScheme.error
                        else -> "—" to MaterialTheme.colorScheme.outline
                    }
                    Text("$label $mark", style = MaterialTheme.typography.bodyMedium, color = color)
                }
            }
            TextButton(onClick = {
                val alreadyUploaded = rows.any { it.status == cat.rumb.app.data.tracks.SyncState.UPLOADED }
                if (alreadyUploaded) confirmResync = true else doSync()
            }) { Text(stringResource(R.string.training_sync_now)) }
        }
    }

    if (confirmResync) {
        AlertDialog(
            onDismissRequest = { confirmResync = false },
            title = { Text(stringResource(R.string.training_resync_title)) },
            text = { Text(stringResource(R.string.training_resync_message)) },
            confirmButton = {
                TextButton(onClick = { confirmResync = false; doSync() }) { Text(stringResource(R.string.training_sync_now)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResync = false }) { Text(stringResource(R.string.training_cancel)) }
            },
        )
    }
}

@Composable
private fun TrainingHeader(e: FollowTrackEntity, custom: List<cat.rumb.app.data.tracks.CustomActivityType>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(activityTypeIcon(e.activityType, custom), contentDescription = activityTypeLabel(e.activityType, custom), modifier = Modifier.size(32.dp))
        Column {
            Text(e.name, style = MaterialTheme.typography.titleMedium)
            val date = remember(e.createdAt) {
                DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(e.createdAt))
            }
            val difficulty = DifficultyCalculator.bandOf(e.distanceMeters, e.ascentM)
            val parts = mutableListOf<String>()
            e.municipality?.takeIf { it.isNotBlank() }?.let(parts::add)
            parts.add(date)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    parts.joinToString(" · ") + " · ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(difficultyLabelRes(difficulty)),
                    style = MaterialTheme.typography.bodySmall,
                    color = difficultyColor(difficulty),
                )
            }
        }
    }
}

private fun difficultyLabelRes(d: Difficulty): Int = when (d) {
    Difficulty.EASY -> R.string.difficulty_easy
    Difficulty.MODERATE -> R.string.difficulty_moderate
    Difficulty.HARD -> R.string.difficulty_hard
    Difficulty.VERY_HARD -> R.string.difficulty_very_hard
}

private fun difficultyColor(d: Difficulty): Color = when (d) {
    Difficulty.EASY -> Color(0xFF2A9D8F)
    Difficulty.MODERATE -> Color(0xFFF4A261)
    Difficulty.HARD -> Color(0xFFE76F51)
    Difficulty.VERY_HARD -> Color(0xFFE63946)
}

/** Per-lap subtracks: mini-stats of each lap (a slice of the parent track) + export its GPX. */
@Composable
private fun LapsSection(
    laps: List<cat.rumb.app.data.tracks.LapRange>,
    points: List<cat.rumb.app.data.gpx.GpxPoint>,
    onExportLap: (cat.rumb.app.data.tracks.LapRange) -> Unit,
    showTitle: Boolean = true,
) {
    val perLap = remember(points, laps) {
        laps.map { it to TrackStatsCalculator.compute(points.subList(it.startIdx.coerceIn(0, points.size), it.endIdx.coerceIn(0, points.size))) }
    }
    if (showTitle) {
        Text(
            stringResource(R.string.training_laps_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    perLap.forEach { (lap, s) ->
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.training_lap_n, lap.index),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        lapStatsLine(s),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onExportLap(lap) }) {
                    Icon(Icons.Filled.IosShare, contentDescription = stringResource(R.string.training_action_export))
                }
            }
        }
    }
}

private fun lapStatsLine(s: TrackStats): String {
    val km = s.distanceM / 1000.0
    val dur = s.movingTime ?: s.totalTime
    val secs = dur?.seconds ?: 0
    val time = if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60)
    else "%d:%02d".format(secs / 60, secs % 60)
    val speed = s.avgSpeedKmh
    return buildString {
        append("%.2f km".format(km))
        append(" · ").append(time)
        if (speed != null) append(" · ").append("%.1f km/h".format(speed))
        // A split without a pace is useless to a runner.
        formatPace(speed)?.let { append(" · ").append(it) }
    }
}

/** Pace as m:ss /km from an average speed, or null when stopped. */
private fun formatPace(speedKmh: Double?): String? {
    val pace = cat.rumb.app.viewer.hud.MetricsCalculator.paceFromSpeedKmh(speedKmh) ?: return null
    val totalSec = Math.round(pace * 60).toInt()
    return "%d:%02d /km".format(totalSec / 60, totalSec % 60)
}

@Composable
private fun StatsCard(s: TrackStats, kcal: Int) {
    val cells = buildList {
        add(stringResource(R.string.training_stat_distance) to String.format("%.1f km", s.distanceM / 1000.0))
        add(stringResource(R.string.training_stat_total_time) to (s.totalTime?.let(::formatDuration) ?: "—"))
        add(stringResource(R.string.training_stat_moving_time) to (s.movingTime?.let(::formatDuration) ?: "—"))
        add(stringResource(R.string.training_stat_avg_speed) to (s.avgSpeedKmh?.let { String.format("%.1f km/h", it) } ?: "—"))
        add(stringResource(R.string.training_stat_avg_pace) to (formatPace(s.avgSpeedKmh) ?: "—"))
        add(stringResource(R.string.training_stat_max_speed) to (s.maxSpeedKmh?.let { String.format("%.1f km/h", it) } ?: "—"))
        add(stringResource(R.string.training_stat_ascent) to "${s.ascentM.toInt()} m")
        add(stringResource(R.string.training_stat_descent) to "${s.descentM.toInt()} m")
        s.avgHr?.let { add(stringResource(R.string.training_stat_avg_hr) to "${it.toInt()} bpm") }
        s.maxHr?.let { add(stringResource(R.string.training_stat_max_hr) to "${it.toInt()} bpm") }
        s.avgCadence?.let { add(stringResource(R.string.training_stat_avg_cadence) to "${it.toInt()} rpm") }
        s.avgPower?.let { add(stringResource(R.string.training_stat_avg_power) to "${it.toInt()} W") }
        if (kcal > 0) add(stringResource(R.string.records_stat_calories) to "$kcal kcal")
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cells.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                            Text(value, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ScrubInfoCard(sample: TrackSample, startTime: Instant?) {
    val cells = buildList {
        if (sample.time != null && startTime != null) {
            add(stringResource(R.string.training_scrub_time) to formatDuration(Duration.between(startTime, sample.time)))
        }
        sample.elevation?.let { add(stringResource(R.string.training_scrub_altitude) to "${it.toInt()} m") }
        sample.speedKmh?.let { add(stringResource(R.string.training_scrub_speed) to String.format("%.1f km/h", it)) }
        sample.hr?.let { add(stringResource(R.string.training_scrub_hr) to "${it.toInt()} bpm") }
    }
    if (cells.isEmpty()) return
    Card {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            cells.forEach { (label, value) ->
                Column {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    Text(value, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun formatDuration(d: Duration): String {
    val totalSeconds = d.seconds.coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return String.format("%d:%02d:%02d", h, m, s)
}
