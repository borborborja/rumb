package cat.rumb.app.manager.screens

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cat.rumb.app.RumbApplication
import cat.rumb.app.R
import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.data.gpx.GpxShare
import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.map.MapStyleFactory
import cat.rumb.app.data.opentracks.model.GeoPoint
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.data.tracks.FollowTrackEntity
import cat.rumb.app.data.tracks.CompetitionType
import cat.rumb.app.data.tracks.LapKind
import cat.rumb.app.data.tracks.Laps
import cat.rumb.app.data.tracks.PolylineSimplifier
import cat.rumb.app.data.tracks.TrackKind
import cat.rumb.app.data.tracks.TrackStats
import cat.rumb.app.data.tracks.TrackStatsCalculator
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style

internal const val ROOT = "General"

/** Virtual folder id for archived tracks (not a real collection; the stored folder is preserved). */
private const val ARCHIVED_FOLDER = "\u0000arxivats"

/**
 * Home = route manager: the always-visible «Entrenament» button (map background) on top, then the
 * Registrades/Per seguir tabs with three view modes (list / detailed / tiles-with-track), folders,
 * per-route actions (open/export/edit/download-maps/delete) and multi-format import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenViewer: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenLayers: () -> Unit = {},
    onOpenRoute: (Long) -> Unit = {},
    onOpenTraining: (Long) -> Unit = {},
    onOpenCompare: (Long) -> Unit = {},
    onEditRoute: (Long) -> Unit = {},
    onCreateRoute: () -> Unit = {},
    onDownloadRouteMap: (cat.rumb.app.data.map.BoundingBox) -> Unit = {},
    onOpenCompetition: (Long) -> Unit = {},
    onStartCompetition: (Long) -> Unit = {},
    onOpenRecords: () -> Unit = {},
    onOpenHeatmap: () -> Unit = {},
    onOpenDesktop: () -> Unit = {},
    importUri: android.net.Uri? = null,
    onImportHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    val prefs = remember { ViewerPreferences.get(context) }
    val scope = rememberCoroutineScope()

    val all by remember { app.trackRepository.observeSummaries() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val competitions by remember { app.competitionRepository.observeCompetitions() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var tab by remember { mutableIntStateOf(0) }
    val kind = if (tab == 0) TrackKind.TRAINING else TrackKind.ROUTE

    var viewMode by remember { mutableStateOf(prefs.routeViewMode) }
    var currentFolder by remember { mutableStateOf<String?>(null) }
    // Sort/filter, persisted per tab.
    var sort by remember(tab) {
        mutableStateOf(cat.rumb.app.data.tracks.TrackSort.byName(if (tab == 0) prefs.trackSortTraining else prefs.trackSortRoute))
    }
    var filterType by remember(tab) {
        mutableStateOf(if (tab == 0) prefs.trackFilterTypeTraining else prefs.trackFilterTypeRoute)
    }
    val customTypes = remember(prefs.customActivityTypesJson) {
        cat.rumb.app.data.tracks.ActivityTypes.decodeCustom(prefs.customActivityTypesJson)
    }
    val typeOptions = rememberActivityTypeOptions(prefs)
    val tracks = cat.rumb.app.data.tracks.TrackSortFilter.apply(all.filter { it.kind == kind && !it.archived }, sort, filterType)
    val archivedTracks = all.filter { it.kind == kind && it.archived }.sortedByDescending { it.createdAt }
    // Tracks belonging to an ACTIVE competition (reference or attempt) show the trophy icon.
    // Library trophy badge: a track appears in some competition (as a source of an attempt).
    val compSourceIds by remember { app.competitionRepository.observeSourceTrackIds() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val compMemberIds = compSourceIds.toSet()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // Per-row expansion of the lap dropdown (keyed by track id), independent of the folder expand.
    val expandedLaps = remember { mutableStateMapOf<Long, Boolean>() }

    // Folders = user-created set ∪ collections present on this tab's tracks.
    fun folderSet(): Set<String> = if (kind == TrackKind.TRAINING) prefs.foldersTraining else prefs.foldersRoute
    fun saveFolderSet(set: Set<String>) {
        if (kind == TrackKind.TRAINING) prefs.foldersTraining = set else prefs.foldersRoute = set
    }
    val folders = (folderSet() + tracks.map { it.collection }.filter { it != ROOT }).distinct().sorted()

    // Dialog state.
    var pendingImport by remember { mutableStateOf<Pair<android.net.Uri, String?>?>(null) }
    var pendingTrainingImport by remember { mutableStateOf<Pair<android.net.Uri, String?>?>(null) }
    var moveFor by remember { mutableStateOf<FollowTrackEntity?>(null) }
    var renameFor by remember { mutableStateOf<FollowTrackEntity?>(null) }
    var deleteFor by remember { mutableStateOf<FollowTrackEntity?>(null) }
    var shareFor by remember { mutableStateOf<FollowTrackEntity?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var folderRename by remember { mutableStateOf<String?>(null) }
    var folderDelete by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
            pendingImport = uri to name
        }
    }

    // A track file opened/shared into Rumb (VIEW/SEND): resolve its format and route it into the
    // same import dialog as the file picker.
    androidx.compose.runtime.LaunchedEffect(importUri) {
        val uri = importUri ?: return@LaunchedEffect
        val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val display = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
            cat.rumb.app.data.gpx.resolveTrackFileName(context.contentResolver, uri, display)
        }
        if (name != null) {
            pendingImport = uri to name
        } else {
            android.widget.Toast.makeText(context, context.getString(R.string.home_import_unsupported), android.widget.Toast.LENGTH_LONG).show()
        }
        onImportHandled()
    }

    // In tile mode, being inside a folder captures the back gesture (step out, don't exit).
    BackHandler(enabled = currentFolder != null) { currentFolder = null }

    fun exportTrack(t: FollowTrackEntity) { shareFor = t }

    val routeActions = RouteActions(
        onOpen = { if (it.kind == TrackKind.TRAINING) onOpenTraining(it.id) else onOpenRoute(it.id) },
        onOpenCompare = { onOpenCompare(it.id) },
        onCreateCircuit = { t -> createCompetition(scope, context, app, t, CompetitionType.LAP, onOpenCompetition) },
        onExport = ::exportTrack,
        onEdit = { t -> if (kind == TrackKind.ROUTE) onEditRoute(t.id) else renameFor = t },
        onMove = { moveFor = it },
        onDownloadMap = { t ->
            scope.launch { app.trackRepository.routeBoundingBox(t.id)?.let(onDownloadRouteMap) }
        },
        onDelete = { deleteFor = it },
        onCompetition = { t -> createCompetition(scope, context, app, t, CompetitionType.ROUTE, onOpenCompetition) },
        onArchive = { t -> scope.launch { app.trackRepository.setArchived(t.id, !t.archived) } },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenDesktop) { Icon(Icons.Filled.Computer, contentDescription = stringResource(R.string.home_cd_desktop)) }
                    IconButton(onClick = { tab = 1; currentFolder = null }) {
                        Icon(
                            Icons.Filled.Route,
                            contentDescription = stringResource(R.string.home_tab_to_follow),
                            tint = if (tab == 1) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = onOpenLayers) { Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.home_cd_map_layers)) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_cd_settings)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            ViewerMapButton(onClick = onOpenViewer)

            // Routes-to-follow (tab 1) lives in the top bar now; the tab strip shows the other three
            // sections as text. In routes mode, a header with a back-to-tabs affordance replaces it.
            if (tab == 1) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { tab = 0; currentFolder = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.home_cd_exit_folder))
                    }
                    Text(stringResource(R.string.home_tab_to_follow), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                val homeTabs = listOf(
                    0 to R.string.home_tab_recorded,
                    2 to R.string.home_tab_competition,
                    3 to R.string.home_tab_progress,
                )
                TabRow(
                    selectedTabIndex = homeTabs.indexOfFirst { it.first == tab }.coerceAtLeast(0),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    homeTabs.forEach { (index, labelRes) ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index; currentFolder = null },
                            text = { Text(stringResource(labelRes)) },
                        )
                    }
                }
            }

            if (tab < 2) {
                // Toolbar: view mode, folders, import, create route.
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = {
                        viewMode = when (viewMode) {
                            "LIST" -> "DETAILED"; "DETAILED" -> "TILES"; else -> "LIST"
                        }
                        prefs.routeViewMode = viewMode
                    }) {
                        Icon(
                            when (viewMode) {
                                "LIST" -> Icons.AutoMirrored.Filled.ViewList
                                "DETAILED" -> Icons.AutoMirrored.Filled.ViewQuilt
                                else -> Icons.Filled.GridView
                            },
                            contentDescription = stringResource(R.string.home_cd_view_mode),
                        )
                    }
                    IconButton(onClick = { newFolder = true }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.home_new_folder))
                    }
                    SortMenuButton(sort) { s ->
                        sort = s
                        if (kind == TrackKind.TRAINING) prefs.trackSortTraining = s.name else prefs.trackSortRoute = s.name
                    }
                    FilterMenuButton(filterType, typeOptions) { f ->
                        filterType = f
                        if (kind == TrackKind.TRAINING) prefs.trackFilterTypeTraining = f else prefs.trackFilterTypeRoute = f
                    }
                    if (currentFolder != null) {
                        AssistChip(
                            onClick = { currentFolder = null },
                            label = { Text(if (currentFolder == ARCHIVED_FOLDER) stringResource(R.string.home_archived_folder) else currentFolder ?: "") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.home_cd_exit_folder), Modifier.size(16.dp)) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.home_import))
                    }
                    if (kind == TrackKind.ROUTE) {
                        IconButton(onClick = onCreateRoute) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.home_cd_create_route)) }
                    }
                }

                if (tracks.isEmpty() && folders.isEmpty() && archivedTracks.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (kind == TrackKind.TRAINING) {
                                stringResource(R.string.home_empty_trainings)
                            } else {
                                stringResource(R.string.home_empty_routes)
                            },
                        )
                    }
                } else if (viewMode == "TILES") {
                    TilesView(
                        tracks = tracks, archived = archivedTracks, folders = folders, currentFolder = currentFolder,
                        kind = kind, compIds = compMemberIds, actions = routeActions,
                        onEnterFolder = { currentFolder = it },
                        onFolderMenu = { name, action -> if (action == "rename") folderRename = name else folderDelete = name },
                    )
                } else {
                    ListView(
                        tracks = tracks, archived = archivedTracks, folders = folders, detailed = viewMode == "DETAILED",
                        kind = kind, compIds = compMemberIds, actions = routeActions,
                        expanded = expanded, expandedLaps = expandedLaps,
                        onFolderMenu = { name, action -> if (action == "rename") folderRename = name else folderDelete = name },
                    )
                }
            } else if (tab == 2) {
                CompetitionTab(competitions = competitions, onOpen = onOpenCompetition, onPlay = onStartCompetition)
            } else {
                ProgressTab(
                    all = all,
                    onOpenRecords = onOpenRecords,
                    onOpenHeatmap = onOpenHeatmap,
                    onOpenTraining = onOpenTraining,
                )
            }
        }
    }

    // --- Dialogs ---

    pendingImport?.let { (uri, fileName) ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.home_import_dialog_title, fileName ?: stringResource(R.string.home_file_fallback))) },
            text = { Text(stringResource(R.string.home_import_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    doImport(context, scope, app, uri, fileName, TrackKind.ROUTE); pendingImport = null
                }) { Text(stringResource(R.string.home_import_as_route)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingTrainingImport = uri to fileName; pendingImport = null
                }) { Text(stringResource(R.string.home_training)) }
            },
        )
    }

    // Training import: same save dialog as when finishing a recording (name + folder + type).
    pendingTrainingImport?.let { (uri, fileName) ->
        val trainingFolders = (prefs.foldersTraining +
            all.filter { it.kind == TrackKind.TRAINING }.map { it.collection })
            .filter { it != ROOT }.distinct().sorted()
        TrackSaveDialog(
            title = stringResource(R.string.home_import_save_training_title),
            statsLine = null,
            defaultName = fileName?.substringBeforeLast('.') ?: stringResource(R.string.home_imported_default_name),
            folders = trainingFolders,
            activityTypes = typeOptions,
            confirmLabel = stringResource(R.string.home_import),
            dismissLabel = stringResource(R.string.home_cancel),
            onConfirm = { name, folder, typeId ->
                doImport(context, scope, app, uri, fileName, TrackKind.TRAINING, folder, typeId, name)
                if (folder != ROOT) prefs.foldersTraining = prefs.foldersTraining + folder
                pendingTrainingImport = null
            },
            onDismiss = { pendingTrainingImport = null },
        )
    }

    if (newFolder) {
        TextDialog(title = stringResource(R.string.home_new_folder), initial = "", confirm = stringResource(R.string.home_create), onDismiss = { newFolder = false }) { name ->
            saveFolderSet(folderSet() + name)
            newFolder = false
        }
    }

    moveFor?.let { track ->
        MoveToFolderDialog(
            folders = folders,
            current = track.collection,
            onDismiss = { moveFor = null },
            onMove = { folder ->
                scope.launch { app.trackRepository.setCollection(track.id, folder) }
                if (folder != ROOT) saveFolderSet(folderSet() + folder)
                moveFor = null
            },
        )
    }

    shareFor?.let { track ->
        ShareFormatDialog(
            onDismiss = { shareFor = null },
            onPick = { format ->
                shareFor = null
                scope.launch {
                    val points = app.trackRepository.loadGpxRoute(track.id)
                    val laps = cat.rumb.app.data.tracks.Laps.decode(track.laps)
                    val built = cat.rumb.app.data.gpx.TrackExport.build(
                        format, cat.rumb.app.data.sync.SyncTargets.safeName(track.name),
                        points, laps, track.activityType, prefs.userWeightKg, prefs.userAge, prefs.userSex,
                    )
                    GpxShare.shareFile(context, built.fileName, built.content, built.mime, track.name)
                }
            },
        )
    }

    renameFor?.let { track ->
        TextDialog(title = stringResource(R.string.home_rename), initial = track.name, confirm = stringResource(R.string.home_save), onDismiss = { renameFor = null }) { name ->
            scope.launch { app.trackRepository.rename(track.id, name) }
            renameFor = null
        }
    }

    deleteFor?.let { track ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.home_delete_confirm, track.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.trackRepository.delete(track.id) }
                    deleteFor = null
                }) { Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }

    folderRename?.let { old ->
        TextDialog(title = stringResource(R.string.home_rename_folder), initial = old, confirm = stringResource(R.string.home_save), onDismiss = { folderRename = null }) { new ->
            scope.launch { app.trackRepository.renameCollection(old, new, kind) }
            saveFolderSet(folderSet() - old + new)
            if (currentFolder == old) currentFolder = new
            folderRename = null
        }
    }

    folderDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { folderDelete = null },
            title = { Text(stringResource(R.string.home_delete_folder_confirm, name)) },
            text = { Text(stringResource(R.string.home_delete_folder_text)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.trackRepository.renameCollection(name, ROOT, kind) }
                    saveFolderSet(folderSet() - name)
                    if (currentFolder == name) currentFolder = null
                    folderDelete = null
                }) { Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { folderDelete = null }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
}

private fun doImport(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    app: RumbApplication,
    uri: android.net.Uri,
    fileName: String?,
    kind: String,
    collection: String = ROOT,
    activityType: String? = null,
    nameOverride: String? = null,
) {
    scope.launch {
        runCatching {
            val id = app.trackRepository.importAny(
                uri, fileName,
                nameOverride ?: fileName?.substringBeforeLast('.') ?: context.getString(R.string.home_imported_default_name),
                kind, collection = collection, activityType = activityType,
            )
            // The file's own <name> wins inside importAny; the user's typed name must win over both.
            nameOverride?.let { app.trackRepository.rename(id, it) }
            cat.rumb.app.data.tracks.TrackMetadataBackfillWorker.enqueue(context)
        }.onFailure {
            android.widget.Toast.makeText(context, it.message ?: context.getString(R.string.home_import_error), android.widget.Toast.LENGTH_LONG).show()
        }.onSuccess {
            android.widget.Toast.makeText(context, context.getString(R.string.home_imported), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun SortMenuButton(current: cat.rumb.app.data.tracks.TrackSort, onPick: (cat.rumb.app.data.tracks.TrackSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = mapOf(
        cat.rumb.app.data.tracks.TrackSort.DATE_DESC to R.string.home_sort_date_desc,
        cat.rumb.app.data.tracks.TrackSort.DATE_ASC to R.string.home_sort_date_asc,
        cat.rumb.app.data.tracks.TrackSort.DISTANCE_DESC to R.string.home_sort_distance_desc,
        cat.rumb.app.data.tracks.TrackSort.DISTANCE_ASC to R.string.home_sort_distance_asc,
        cat.rumb.app.data.tracks.TrackSort.MUNICIPALITY to R.string.home_sort_municipality,
        cat.rumb.app.data.tracks.TrackSort.DIFFICULTY to R.string.home_sort_difficulty,
        cat.rumb.app.data.tracks.TrackSort.TYPE to R.string.home_sort_type,
    )
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.home_cd_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            labels.forEach { (s, res) ->
                DropdownMenuItem(
                    text = { Text(stringResource(res)) },
                    leadingIcon = { if (s == current) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) },
                    onClick = { open = false; onPick(s) },
                )
            }
        }
    }
}

@Composable
private fun FilterMenuButton(current: String?, options: List<ActivityTypeOption>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.FilterList,
                contentDescription = stringResource(R.string.home_cd_filter),
                tint = if (current != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_filter_all_types)) },
                leadingIcon = { if (current == null) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) },
                onClick = { open = false; onPick(null) },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = {
                        Icon(
                            option.icon, contentDescription = null, Modifier.size(18.dp),
                            tint = if (current == option.id) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    },
                    onClick = { open = false; onPick(option.id) },
                )
            }
        }
    }
}

/**
 * Creates a competition from [t]: ROUTE (whole-track reference) or LAP (fastest-lap reference + fixed
 * line). Opens the new competition on success; a track without usable timing/laps gives feedback.
 */
private fun createCompetition(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    app: RumbApplication,
    t: FollowTrackEntity,
    type: String,
    onOpenCompetition: (Long) -> Unit,
) {
    scope.launch {
        val id = app.competitionRepository.createFromTrack(t.id, t.name, t.activityType, type, System.currentTimeMillis())
        if (id != null) {
            onOpenCompetition(id)
        } else {
            android.widget.Toast.makeText(context, context.getString(R.string.home_competition_untimed), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/** Per-route callbacks bundled to keep signatures sane. */
private data class RouteActions(
    val onOpen: (FollowTrackEntity) -> Unit,
    val onOpenCompare: (FollowTrackEntity) -> Unit,
    val onCreateCircuit: (FollowTrackEntity) -> Unit,
    val onExport: (FollowTrackEntity) -> Unit,
    val onEdit: (FollowTrackEntity) -> Unit,
    val onMove: (FollowTrackEntity) -> Unit,
    val onDownloadMap: (FollowTrackEntity) -> Unit,
    val onDelete: (FollowTrackEntity) -> Unit,
    val onCompetition: (FollowTrackEntity) -> Unit,
    val onArchive: (FollowTrackEntity) -> Unit,
)

// --- List / detailed modes (folders collapse/expand) ---

@Composable
private fun ListView(
    tracks: List<FollowTrackEntity>,
    archived: List<FollowTrackEntity>,
    folders: List<String>,
    detailed: Boolean,
    kind: String,
    compIds: Set<Long>,
    actions: RouteActions,
    expanded: MutableMap<String, Boolean>,
    expandedLaps: MutableMap<Long, Boolean>,
    onFolderMenu: (String, String) -> Unit,
) {
    val root = tracks.filter { it.collection == ROOT }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(root, key = { it.id }) { t ->
            RouteRow(t, detailed, kind, t.id in compIds, actions, expandedLaps)
        }
        folders.forEach { folder ->
            val children = tracks.filter { it.collection == folder }
            item(key = "folder-$folder") {
                FolderHeader(
                    name = folder,
                    count = children.size,
                    expanded = expanded[folder] == true,
                    onToggle = { expanded[folder] = !(expanded[folder] == true) },
                    onMenu = { action -> onFolderMenu(folder, action) },
                )
            }
            if (expanded[folder] == true) {
                items(children, key = { it.id }) { t ->
                    Box(Modifier.padding(start = 16.dp)) { RouteRow(t, detailed, kind, t.id in compIds, actions, expandedLaps) }
                }
            }
        }
        // Fixed "Archived" pseudo-folder: always last, cannot be renamed or deleted.
        if (archived.isNotEmpty()) {
            item(key = "folder-archived") {
                ArchivedFolderHeader(
                    count = archived.size,
                    expanded = expanded[ARCHIVED_FOLDER] == true,
                    onToggle = { expanded[ARCHIVED_FOLDER] = !(expanded[ARCHIVED_FOLDER] == true) },
                )
            }
            if (expanded[ARCHIVED_FOLDER] == true) {
                items(archived, key = { it.id }) { t ->
                    Box(Modifier.padding(start = 16.dp)) { RouteRow(t, detailed, kind, false, actions, expandedLaps) }
                }
            }
        }
        if (kind == TrackKind.ROUTE) {
            item(key = "route-follow-hint") { RouteFollowHint() }
        }
    }
}

/** Trailing hint below the routes list: following a route is done from the viewer settings. */
@Composable
private fun RouteFollowHint() {
    Text(
        stringResource(R.string.home_route_follow_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

@Composable
private fun ArchivedFolderHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Card {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            Text("  " + stringResource(R.string.home_archived_folder), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("$count  ", style = MaterialTheme.typography.bodySmall)
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
    }
}

@Composable
private fun FolderHeader(name: String, count: Int, expanded: Boolean, onToggle: () -> Unit, onMenu: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("  $name", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("$count  ", style = MaterialTheme.typography.bodySmall)
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_cd_folder_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_rename)) }, onClick = { menu = false; onMenu("rename") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_delete_folder)) }, onClick = { menu = false; onMenu("delete") })
                }
            }
        }
    }
}

@Composable
private fun RouteRow(
    t: FollowTrackEntity,
    detailed: Boolean,
    kind: String,
    inCompetition: Boolean,
    actions: RouteActions,
    expandedLaps: MutableMap<Long, Boolean>,
) {
    // Recorded laps within this track (approach/return excluded). ≥2 → offer the lap dropdown.
    val lapCount = remember(t.laps) { Laps.decode(t.laps).count { it.kind == LapKind.LAP } }
    val hasLaps = kind == TrackKind.TRAINING && lapCount >= 2
    val expanded = expandedLaps[t.id] == true
    Card {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { actions.onOpen(t) }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (t.activityType != null) {
                    Icon(
                        ActivityTypeCatalog.iconFor(t.activityType),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (inCompetition) {
                    Icon(
                        Icons.Filled.EmojiEvents,
                        contentDescription = stringResource(R.string.home_tab_competition),
                        tint = Color(0xFFF4A261),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    if (detailed) {
                        val difficulty = cat.rumb.app.data.tracks.DifficultyCalculator.bandOf(t.distanceMeters, t.ascentM)
                        val extra = buildString {
                            t.municipality?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                            append(" · ").append(stringResource(difficultyLabel(difficulty)))
                        }
                        Text(
                            stringResource(
                                R.string.home_route_detail_format,
                                t.distanceMeters / 1000.0, t.pointCount, t.source.name,
                            ) + extra,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    } else {
                        Text(stringResource(R.string.home_distance_km, t.distanceMeters / 1000.0), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (hasLaps) {
                    // Lap badge + chevron; the chevron only toggles the dropdown, the row still opens the track.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("$lapCount", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { expandedLaps[t.id] = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(R.string.home_laps_count, lapCount),
                        )
                    }
                }
                RouteMenu(t, kind, actions)
            }
            if (hasLaps && expanded) {
                LapsDropdown(
                    t,
                    onOpenCompare = { actions.onOpenCompare(t) },
                    onCreateCircuit = { actions.onCreateCircuit(t) },
                )
            }
        }
    }
}

/**
 * Ranked lap list shown under a training row: laps of this track presented like competition
 * "attempts" (best highlighted, gap vs best). Points are loaded lazily on expand; tapping a lap
 * opens the shared lap comparison (CompareScreen LAPS mode).
 */
@Composable
private fun LapsDropdown(t: FollowTrackEntity, onOpenCompare: () -> Unit, onCreateCircuit: () -> Unit) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    val laps = remember(t.laps) { Laps.decode(t.laps).filter { it.kind == LapKind.LAP } }
    val points by produceState<List<GpxPoint>?>(initialValue = null, t.id) {
        value = runCatching { app.trackRepository.loadGpxRoute(t.id) }.getOrDefault(emptyList())
    }
    val pts = points
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 6.dp)) {
        if (pts == null) {
            Text(stringResource(R.string.debug_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
        // Per-lap stats (sliced on the fly) + best time for the gap column.
        val rows = laps.mapIndexedNotNull { i, lap ->
            val from = lap.startIdx.coerceIn(0, pts.size)
            val to = lap.endIdx.coerceIn(from, pts.size)
            if (to - from < 2) return@mapIndexedNotNull null
            val stats = TrackStatsCalculator.compute(pts.subList(from, to))
            val secs = (stats.movingTime ?: stats.totalTime)?.seconds ?: 0L
            Triple(i + 1, stats, secs)
        }
        val bestSecs = rows.filter { it.third > 0 }.minOfOrNull { it.third }
        rows.forEach { (lapNo, stats, secs) ->
            val isBest = bestSecs != null && secs == bestSecs
            Row(
                Modifier.fillMaxWidth().clickable { onOpenCompare() }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.training_lap_n, lapNo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(64.dp),
                )
                Text(lapStatsLine(stats), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                val gap = if (bestSecs != null && secs > 0) secs - bestSecs else 0L
                Text(
                    if (isBest) stringResource(R.string.home_laps_best) else "+%d:%02d".format(gap / 60, gap % 60),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onCreateCircuit) { Text(stringResource(R.string.circuit_create_from_laps)) }
        }
    }
}

/** km · time · km/h for one lap slice (mirrors the training detail's per-lap line). */
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
    }
}

@Composable
private fun RouteMenu(t: FollowTrackEntity, kind: String, actions: RouteActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_cd_actions)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.home_open)) }, onClick = { open = false; actions.onOpen(t) })
            DropdownMenuItem(text = { Text(stringResource(R.string.home_export_gpx)) }, onClick = { open = false; actions.onExport(t) })
            if (t.archived) {
                // Archived tracks: stats and export only, plus unarchive/delete.
                DropdownMenuItem(text = { Text(stringResource(R.string.home_unarchive)) }, onClick = { open = false; actions.onArchive(t) })
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(if (kind == TrackKind.ROUTE) R.string.home_edit_track else R.string.home_rename)) },
                    onClick = { open = false; actions.onEdit(t) },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.home_move_to_folder_ellipsis)) }, onClick = { open = false; actions.onMove(t) })
                if (kind == TrackKind.TRAINING) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_send_to_competition)) },
                        onClick = { open = false; actions.onCompetition(t) },
                    )
                }
                if (kind == TrackKind.ROUTE) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_download_maps)) }, onClick = { open = false; actions.onDownloadMap(t) })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.home_archive)) }, onClick = { open = false; actions.onArchive(t) })
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; actions.onDelete(t) },
            )
        }
    }
}

// --- Tiles mode (folders are entered; back chip/gesture exits) ---

@Composable
private fun TilesView(
    tracks: List<FollowTrackEntity>,
    archived: List<FollowTrackEntity>,
    folders: List<String>,
    currentFolder: String?,
    kind: String,
    compIds: Set<Long>,
    actions: RouteActions,
    onEnterFolder: (String) -> Unit,
    onFolderMenu: (String, String) -> Unit,
) {
    val visible = when (currentFolder) {
        null -> tracks.filter { it.collection == ROOT }
        ARCHIVED_FOLDER -> archived
        else -> tracks.filter { it.collection == currentFolder }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (currentFolder == null) {
            gridItems(folders, key = { "folder-$it" }) { folder ->
                FolderTile(
                    name = folder,
                    count = tracks.count { it.collection == folder },
                    onOpen = { onEnterFolder(folder) },
                    onMenu = { action -> onFolderMenu(folder, action) },
                )
            }
            if (archived.isNotEmpty()) {
                item(key = "folder-archived") {
                    ArchivedTile(count = archived.size, onOpen = { onEnterFolder(ARCHIVED_FOLDER) })
                }
            }
        }
        gridItems(visible, key = { it.id }) { t ->
            RouteTile(t, kind, t.id in compIds, actions)
        }
        if (kind == TrackKind.ROUTE) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "route-follow-hint") { RouteFollowHint() }
        }
    }
}

@Composable
private fun ArchivedTile(count: Int, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.height(110.dp)) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(Icons.Filled.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            Column {
                Text(stringResource(R.string.home_archived_folder), fontWeight = FontWeight.Bold, maxLines = 1)
                Text(stringResource(R.string.home_folder_routes_count, count), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FolderTile(name: String, count: Int, onOpen: () -> Unit, onMenu: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onOpen, modifier = Modifier.height(110.dp)) {
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_cd_folder_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_rename)) }, onClick = { menu = false; onMenu("rename") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_delete_folder)) }, onClick = { menu = false; onMenu("delete") })
                }
            }
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(name, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(stringResource(R.string.home_folder_routes_count, count), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RouteTile(t: FollowTrackEntity, kind: String, inCompetition: Boolean, actions: RouteActions) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    // Lazily load + simplify the geometry for the background thumbnail.
    val points by produceState<List<GeoPoint>>(initialValue = emptyList(), t.id) {
        value = runCatching {
            PolylineSimplifier.simplify(app.trackRepository.loadRoute(t.id), epsilonMeters = 30.0, maxPoints = 80)
        }.getOrDefault(emptyList())
    }
    Card(onClick = { actions.onOpen(t) }, modifier = Modifier.height(120.dp)) {
        Box(Modifier.fillMaxSize()) {
            // Track polyline as the tile background.
            Canvas(Modifier.fillMaxSize().background(Color(0xFF22303C))) {
                if (points.size >= 2) {
                    val minLat = points.minOf { it.latitude }; val maxLat = points.maxOf { it.latitude }
                    val minLon = points.minOf { it.longitude }; val maxLon = points.maxOf { it.longitude }
                    val spanLat = (maxLat - minLat).takeIf { it > 1e-9 } ?: 1e-9
                    val spanLon = (maxLon - minLon).takeIf { it > 1e-9 } ?: 1e-9
                    val pad = 14f
                    val w = size.width - 2 * pad
                    val h = size.height - 2 * pad
                    var prev: Offset? = null
                    points.forEach { p ->
                        val o = Offset(
                            pad + ((p.longitude - minLon) / spanLon * w).toFloat(),
                            pad + ((maxLat - p.latitude) / spanLat * h).toFloat(),
                        )
                        prev?.let { drawLine(Color(0xFF66D9E8), it, o, strokeWidth = 4f, cap = StrokeCap.Round) }
                        prev = o
                    }
                }
                drawRect(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xAA000000))))
            }
            Box(Modifier.align(Alignment.TopEnd)) { RouteMenuTinted(t, kind, actions) }
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.activityType != null) {
                        Icon(
                            ActivityTypeCatalog.iconFor(t.activityType),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                    }
                    if (inCompetition) {
                        Icon(
                            Icons.Filled.EmojiEvents,
                            contentDescription = null,
                            tint = Color(0xFFF4A261),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                    }
                    Text(t.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    buildString {
                        append(stringResource(R.string.home_distance_km, t.distanceMeters / 1000.0))
                        t.municipality?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    },
                    color = Color(0xFFB8C4CE),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RouteMenuTinted(t: FollowTrackEntity, kind: String, actions: RouteActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_cd_actions), tint = Color.White) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.home_open)) }, onClick = { open = false; actions.onOpen(t) })
            DropdownMenuItem(text = { Text(stringResource(R.string.home_export_gpx)) }, onClick = { open = false; actions.onExport(t) })
            if (t.archived) {
                DropdownMenuItem(text = { Text(stringResource(R.string.home_unarchive)) }, onClick = { open = false; actions.onArchive(t) })
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(if (kind == TrackKind.ROUTE) R.string.home_edit_track else R.string.home_rename)) },
                    onClick = { open = false; actions.onEdit(t) },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.home_move_to_folder_ellipsis)) }, onClick = { open = false; actions.onMove(t) })
                if (kind == TrackKind.TRAINING) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_send_to_competition)) },
                        onClick = { open = false; actions.onCompetition(t) },
                    )
                }
                if (kind == TrackKind.ROUTE) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_download_maps)) }, onClick = { open = false; actions.onDownloadMap(t) })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.home_archive)) }, onClick = { open = false; actions.onArchive(t) })
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; actions.onDelete(t) },
            )
        }
    }
}

// --- Shared dialogs ---

@Composable
internal fun TextDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) } },
    )
}

@Composable
internal fun MoveToFolderDialog(
    folders: List<String>,
    current: String,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_move_to_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FolderChoice(stringResource(R.string.home_none_root), current == ROOT) { onMove(ROOT) }
                folders.forEach { f -> FolderChoice(f, current == f) { onMove(f) } }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.home_new_folder_ellipsis)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (newName.isNotBlank()) onMove(newName.trim()) },
                enabled = newName.isNotBlank(),
            ) { Text(stringResource(R.string.home_create_and_move)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) } },
    )
}

@Composable
internal fun FolderChoice(label: String, selected: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onPick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
        Text("  $label", fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// --- The always-visible «Entrenament» doorway (map background) ---

@Composable
private fun ViewerMapButton(onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mapView = rememberMapViewWithLifecycle(textureMode = true)
    Box(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        AndroidView(
            factory = {
                mapView.getMapAsync { map ->
                    val prefs = ViewerPreferences.get(context)
                    val baseId = prefs.baseMapId
                        ?.takeUnless { it.startsWith(cat.rumb.app.data.map.OfflineMap.OFFLINE_PREFIX) }
                    val cfg = cat.rumb.app.data.map.MapDisplayStore.load(prefs, baseId ?: "")
                    map.setStyle(Style.Builder().fromJson(MapStyleFactory.rasterStyleJson(MapSource.byId(baseId), cfg)))
                    val here = lastKnownLatLng(context)
                    map.cameraPosition = CameraPosition.Builder()
                        .target(here ?: LatLng(41.65, 1.95))
                        .zoom(if (here != null) 14.0 else 7.0)
                        .build()
                    map.uiSettings.setAllGesturesEnabled(false)
                    map.uiSettings.isLogoEnabled = false
                    map.uiSettings.isAttributionEnabled = false
                }
                mapView
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0x22000000), Color(0x99000000))))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Map, contentDescription = null, tint = Color.White)
                Text(
                    "  " + stringResource(R.string.home_training),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

/** Most recent OS location fix (any provider), or null without permission/fix. */
private fun lastKnownLatLng(context: android.content.Context): LatLng? {
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_FINE_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
        as? android.location.LocationManager ?: return null
    for (provider in listOf("fused", android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
        val loc = runCatching {
            @android.annotation.SuppressLint("MissingPermission")
            lm.getLastKnownLocation(provider)
        }.getOrNull()
        if (loc != null) return LatLng(loc.latitude, loc.longitude)
    }
    return null
}
