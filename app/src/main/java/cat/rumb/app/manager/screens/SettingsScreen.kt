package cat.rumb.app.manager.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cat.rumb.app.BuildConfig
import cat.rumb.app.R
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.data.tracks.ActivityTypes
import cat.rumb.app.data.tracks.CustomActivityType
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.update.ApkDownloadWorker
import kotlinx.coroutines.flow.first
import cat.rumb.app.data.update.ApkInstaller
import cat.rumb.app.data.update.UpdateInfo
import cat.rumb.app.data.update.UpdateRepository
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

private val TABS = listOf(
    R.string.settings_tab_recording,   // 0
    R.string.settings_tab_profile,     // 1 — units + personal data
    R.string.settings_tab_map_routes,  // 2 — map + track/route appearance + off-route
    R.string.settings_tab_audio,       // 3
    R.string.settings_tab_sync,        // 4
    R.string.settings_tab_app,         // 5 — app + activity types
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDebugLog: () -> Unit = {},
    onOpenSensors: () -> Unit = {},
    onOpenEndurainDownload: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { ViewerPreferences.get(context) }
    // Saveable: opening a sub-screen (sensors, debug log, Endurain) disposes this composition, and a
    // plain remember would drop you back on the first tab instead of the one you left from.
    var tab by rememberSaveable { mutableIntStateOf(0) }

    DetailScaffold(title = stringResource(R.string.settings_title), onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                TABS.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(title)) })
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    0 -> RecordingSection(prefs, onOpenSensors)
                    1 -> ProfileSection(prefs)
                    2 -> MapRoutesSection(prefs)
                    3 -> AudioAnnouncementsSection(prefs)
                    4 -> SyncSection(onOpenEndurainDownload)
                    else -> AppAndTypesSection(prefs, onOpenDebugLog)
                }
            }
        }
    }
}

/** «Mapa y rutas»: caché de mapa + apariencia de traza + ruta a seguir + fuera de ruta. */
@Composable
private fun MapRoutesSection(prefs: ViewerPreferences) {
    Text(stringResource(R.string.settings_tab_map), style = MaterialTheme.typography.titleSmall)
    MapSection(prefs)
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(stringResource(R.string.settings_appearance_track), style = MaterialTheme.typography.titleSmall)
    TrackAppearanceSection(prefs)
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
    FollowRouteSection(prefs)
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
    GhostAppearanceSection(prefs)
}

/**
 * How the ghost (the opponent you race) is drawn on the map: a plain dot, or a figure whose face
 * taunts you. Sibling of the tracking-point block in [FollowRouteSection] — same three controls,
 * for the other marker on the map.
 */
@Composable
private fun GhostAppearanceSection(prefs: ViewerPreferences) {
    val palette = listOf("#9B5DE5", "#3A86FF", "#E63946", "#2A9D8F", "#F4A261", "#FFD166")
    var style by remember { mutableStateOf(prefs.ghostMarkerStyle) }
    var color by remember { mutableStateOf(prefs.ghostColor) }
    var size by remember { mutableFloatStateOf(prefs.ghostSize) }

    Text(stringResource(R.string.settings_ghost_appearance), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = style == "DOT",
            onClick = { style = "DOT"; prefs.ghostMarkerStyle = "DOT" },
            label = { Text(stringResource(R.string.settings_ghost_dot)) },
        )
        FilterChip(
            selected = style == "GHOST",
            onClick = { style = "GHOST"; prefs.ghostMarkerStyle = "GHOST" },
            label = { Text(stringResource(R.string.settings_ghost_figure)) },
        )
    }
    if (style == "GHOST") {
        Text(
            stringResource(R.string.settings_ghost_face_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ColorPalette(palette, color) { color = it; prefs.ghostColor = it }
    Text(stringResource(R.string.settings_ghost_size), style = MaterialTheme.typography.bodySmall)
    Slider(value = size, onValueChange = { size = it; prefs.ghostSize = it }, valueRange = 0.6f..1.8f)
}

/** «App»: información/actualización/depuración + gestión de tipos de actividad. */
@Composable
private fun AppAndTypesSection(prefs: ViewerPreferences, onOpenDebugLog: () -> Unit) {
    AppSection(onOpenDebugLog)
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
    // Weight-control module master switch (self-contained; remove this block to drop the toggle).
    var weight by remember { mutableStateOf(prefs.weightControlEnabled) }
    Text(stringResource(R.string.settings_weight_control), style = MaterialTheme.typography.titleSmall)
    ToggleRow(stringResource(R.string.settings_weight_control_enable), weight) { weight = it; prefs.weightControlEnabled = it }
    Text(
        stringResource(R.string.settings_weight_control_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(stringResource(R.string.settings_tab_activity_types), style = MaterialTheme.typography.titleSmall)
    ActivityTypesSection(prefs)
}

// --- Units ---

@Composable
private fun UnitsSection(prefs: ViewerPreferences) {
    var dist by remember { mutableStateOf(prefs.distanceUnit) }
    var elev by remember { mutableStateOf(prefs.elevationUnit) }
    var spd by remember { mutableStateOf(prefs.speedUnit) }

    Text(stringResource(R.string.settings_units_distance), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(dist == "KM", { dist = "KM"; prefs.distanceUnit = "KM" }, label = { Text(stringResource(R.string.settings_units_kilometers)) })
        FilterChip(dist == "MILE", { dist = "MILE"; prefs.distanceUnit = "MILE" }, label = { Text(stringResource(R.string.settings_units_miles)) })
    }
    Text(stringResource(R.string.settings_units_elevation), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(elev == "METER", { elev = "METER"; prefs.elevationUnit = "METER" }, label = { Text(stringResource(R.string.settings_units_meters)) })
        FilterChip(elev == "FOOT", { elev = "FOOT"; prefs.elevationUnit = "FOOT" }, label = { Text(stringResource(R.string.settings_units_feet)) })
    }
    Text(stringResource(R.string.settings_units_speed), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(spd == "KMH", { spd = "KMH"; prefs.speedUnit = "KMH" }, label = { Text("km/h") })
        FilterChip(spd == "MPH", { spd = "MPH"; prefs.speedUnit = "MPH" }, label = { Text("mph") })
    }
    Text(
        stringResource(R.string.settings_units_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 8.dp),
    )
}

// --- Sync (Endurain now; more services later) ---

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SyncSection(onOpenEndurainDownload: () -> Unit = {}) {
    val context = LocalContext.current
    val endurainPrefs = remember { cat.rumb.app.data.prefs.EndurainPreferences.get(context) }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(endurainPrefs.host ?: "") }
    var mode by remember { mutableStateOf(endurainPrefs.authMode) }
    var username by remember { mutableStateOf(endurainPrefs.username ?: "") }
    var password by remember { mutableStateOf(endurainPrefs.password ?: "") }
    var apiKey by remember { mutableStateOf(endurainPrefs.apiKey ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    val credMode = cat.rumb.app.data.prefs.EndurainPreferences.AuthMode.CREDENTIALS
    val keyMode = cat.rumb.app.data.prefs.EndurainPreferences.AuthMode.API_KEY

    Text(stringResource(R.string.settings_sync_endurain), style = MaterialTheme.typography.titleSmall)
    androidx.compose.material3.OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text(stringResource(R.string.settings_sync_server_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    // Full access needs a login (username/password → JWT); an API key can only upload.
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = mode == credMode,
            onClick = { mode = credMode },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
        ) { Text(stringResource(R.string.settings_sync_mode_credentials)) }
        SegmentedButton(
            selected = mode == keyMode,
            onClick = { mode = keyMode },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
        ) { Text(stringResource(R.string.settings_sync_mode_api_key)) }
    }
    if (mode == credMode) {
        androidx.compose.material3.OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.settings_sync_username_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.settings_sync_password_label)) },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        androidx.compose.material3.OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.settings_sync_api_key_label)) },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        onClick = {
            endurainPrefs.host = host
            endurainPrefs.authMode = mode
            if (mode == credMode) {
                endurainPrefs.username = username
                endurainPrefs.password = password
            } else {
                endurainPrefs.apiKey = apiKey
            }
            endurainPrefs.clearSession() // credentials changed → drop any stale tokens
            status = context.getString(R.string.settings_sync_saved_testing)
            scope.launch {
                val repo = cat.rumb.app.data.endurain.EndurainRepository(endurainPrefs)
                status = endurainStatusText(context, repo.testConnection())
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_sync_save_test)) }
    status?.let { Card { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) } }
    Text(
        stringResource(R.string.settings_sync_upload_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    // Auto-upload only fires when a recording stops, so this catches up pre-existing/imported tracks.
    if (endurainPrefs.isConfigured) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    val n = cat.rumb.app.data.sync.SyncTargets.uploadAllPendingToEndurain(context)
                    status = context.getString(R.string.settings_sync_upload_all_queued, n)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_sync_upload_all)) }
    }
    // Downloading needs a JWT session to read activities, so it's credentials-only.
    if (mode == credMode) {
        androidx.compose.material3.OutlinedButton(
            onClick = onOpenEndurainDownload,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Filled.Download,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_sync_download))
        }
    }

    SyncStatusSummary()
    FolderExportBlock()
    WebDavBlock()

    Text(stringResource(R.string.settings_sync_coming_soon), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    Text(
        stringResource(R.string.settings_sync_coming_soon_items),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

/** Turns a connection-test outcome into a user-facing line. */
private fun endurainStatusText(
    context: android.content.Context,
    result: cat.rumb.app.data.endurain.ConnResult,
): String = when (result) {
    is cat.rumb.app.data.endurain.ConnResult.Ok ->
        result.activityCount?.let { context.getString(R.string.settings_sync_connected, "$it") }
            ?: context.getString(R.string.settings_sync_connected_no_count)
    cat.rumb.app.data.endurain.ConnResult.BadKey -> context.getString(R.string.settings_sync_bad_key)
    cat.rumb.app.data.endurain.ConnResult.MissingScope -> context.getString(R.string.settings_sync_missing_scope)
    cat.rumb.app.data.endurain.ConnResult.BadUrl -> context.getString(R.string.settings_sync_bad_url)
    cat.rumb.app.data.endurain.ConnResult.NotConfigured -> context.getString(R.string.settings_sync_not_configured)
    is cat.rumb.app.data.endurain.ConnResult.Error -> context.getString(R.string.settings_error, result.message)
}

/** Sync outbox summary: last upload + pending/failed counts + retry-failed. */
@Composable
private fun SyncStatusSummary() {
    val context = LocalContext.current
    val app = remember { cat.rumb.app.RumbApplication.from(context) }
    val scope = rememberCoroutineScope()
    val counts by app.database.syncStatusDao().observeCounts().collectAsState(initial = emptyList())
    val lastUpload by app.database.syncStatusDao().observeLastUploaded().collectAsState(initial = null)
    val pending = counts.filter { it.status == cat.rumb.app.data.tracks.SyncState.PENDING }.sumOf { it.n }
    val failed = counts.filter { it.status == cat.rumb.app.data.tracks.SyncState.FAILED }.sumOf { it.n }

    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.settings_sync_status), style = MaterialTheme.typography.titleSmall)
            val last = lastUpload?.let {
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(it))
            } ?: "—"
            Text(stringResource(R.string.settings_sync_last_upload, last), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_sync_pending_failed, pending, failed),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (failed > 0) {
                OutlinedButton(onClick = { scope.launch { cat.rumb.app.data.sync.SyncTargets.retryFailed(context) } }) {
                    Text(stringResource(R.string.settings_sync_retry_failed))
                }
            }
        }
    }
}

/** Save each recorded GPX into a user-chosen folder (SAF). */
@Composable
private fun FolderExportBlock() {
    val context = LocalContext.current
    val prefs = remember { cat.rumb.app.data.prefs.FolderExportPreferences.get(context) }
    var uri by remember { mutableStateOf(prefs.treeUri) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    picked,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            prefs.treeUri = picked.toString(); uri = picked.toString()
            prefs.enabled = true; enabled = true
        }
    }

    Text(stringResource(R.string.settings_sync_folder), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    val folderLabel = uri?.let { android.net.Uri.parse(it).lastPathSegment ?: it } ?: stringResource(R.string.settings_sync_folder_none)
    Text(folderLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_sync_folder_choose))
    }
    if (uri != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_sync_folder_enable), Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { prefs.enabled = it; enabled = it })
        }
    }
}

/** Upload each recorded GPX to a WebDAV collection. */
@Composable
private fun WebDavBlock() {
    val context = LocalContext.current
    val prefs = remember { cat.rumb.app.data.prefs.WebDavPreferences.get(context) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.url ?: "") }
    var user by remember { mutableStateOf(prefs.user ?: "") }
    var pass by remember { mutableStateOf(prefs.pass ?: "") }
    var status by remember { mutableStateOf<String?>(null) }

    Text(stringResource(R.string.settings_sync_webdav), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.settings_sync_webdav_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.settings_sync_webdav_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        pass, { pass = it }, label = { Text(stringResource(R.string.settings_sync_webdav_pass)) }, singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            prefs.url = url; prefs.user = user; prefs.pass = pass
            status = context.getString(R.string.settings_sync_saved_testing)
            scope.launch {
                status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val body = "rumb webdav test".toByteArray()
                            .toRequestBody("text/plain".toMediaType())
                        val req = okhttp3.Request.Builder()
                            .url("${url.trimEnd('/')}/.rumb-test.txt")
                            .header("Authorization", okhttp3.Credentials.basic(user, pass))
                            .put(body).build()
                        okhttp3.OkHttpClient().newCall(req).execute().use { r ->
                            if (r.isSuccessful) context.getString(R.string.settings_sync_connected, "WebDAV")
                            else context.getString(R.string.settings_error, "HTTP ${r.code}")
                        }
                    }.getOrElse { context.getString(R.string.settings_error, "${it.message}") }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_sync_webdav_test)) }
    status?.let { Card { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) } }
}

// --- Native recording engine ---

@Composable
private fun RecordingSection(prefs: ViewerPreferences, onOpenSensors: () -> Unit) {
    var interval by remember { mutableIntStateOf(prefs.recGpsIntervalSec) }
    var minDist by remember { mutableFloatStateOf(prefs.recMinDistanceM) }
    var accuracy by remember { mutableFloatStateOf(prefs.recMaxAccuracyM) }
    var autoPause by remember { mutableStateOf(prefs.recAutoPause) }
    var barometer by remember { mutableStateOf(prefs.recBarometer) }

    Text(stringResource(R.string.settings_rec_gps_interval), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 2, 5).forEach { s ->
            FilterChip(interval == s, { interval = s; prefs.recGpsIntervalSec = s }, label = { Text("${s}s") })
        }
    }

    Text(stringResource(R.string.settings_rec_min_distance, minDist.toInt()), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Slider(
        value = minDist,
        onValueChange = { minDist = it; prefs.recMinDistanceM = it },
        valueRange = 1f..15f,
        steps = 13,
    )

    Text(stringResource(R.string.settings_rec_max_accuracy, accuracy.toInt()), style = MaterialTheme.typography.labelLarge)
    Text(stringResource(R.string.settings_rec_accuracy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Slider(
        value = accuracy,
        onValueChange = { accuracy = it; prefs.recMaxAccuracyM = it },
        valueRange = 10f..100f,
        steps = 8,
    )

    ToggleRow(stringResource(R.string.settings_rec_auto_pause), autoPause) { autoPause = it; prefs.recAutoPause = it }
    if (autoPause) {
        var autoPauseSec by remember { mutableIntStateOf(prefs.recAutoPauseSec) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 20, 30).forEach { secs ->
                FilterChip(autoPauseSec == secs, { autoPauseSec = secs; prefs.recAutoPauseSec = secs }, label = { Text("$secs s") })
            }
        }
    }
    ToggleRow(stringResource(R.string.settings_rec_barometer), barometer) { barometer = it; prefs.recBarometer = it }

    OutlinedButton(onClick = onOpenSensors, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(R.string.settings_rec_ble_sensors))
    }
    Text(
        stringResource(R.string.settings_rec_paired_sensors, prefs.bleSensorAddrs.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Text(
        stringResource(R.string.settings_rec_apply_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 8.dp),
    )
}

// --- Profile: display units + personal data (weight/age/sex/max HR for calories & zones) ---

@Composable
private fun ProfileSection(prefs: ViewerPreferences) {
    UnitsSection(prefs)

    var maxHr by remember { mutableIntStateOf(prefs.userMaxHr) }
    var weight by remember { mutableIntStateOf(prefs.userWeightKg) }
    var height by remember { mutableIntStateOf(prefs.userHeightCm) }
    var age by remember { mutableIntStateOf(prefs.userAge) }
    var sex by remember { mutableStateOf(prefs.userSex) }

    Text(stringResource(R.string.settings_profile_personal), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    Text(stringResource(R.string.settings_max_hr, maxHr), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Slider(
        value = maxHr.toFloat(),
        onValueChange = { maxHr = it.toInt(); prefs.userMaxHr = maxHr },
        valueRange = 120f..220f,
        steps = 19,
    )
    Text(
        stringResource(R.string.settings_max_hr_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Text(stringResource(R.string.settings_weight, weight), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Slider(
        value = weight.toFloat(),
        onValueChange = { weight = it.toInt(); prefs.userWeightKg = weight },
        valueRange = 40f..150f,
        steps = 109,
    )
    Text(
        stringResource(R.string.settings_weight_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Text(
        if (height > 0) stringResource(R.string.settings_height, height) else stringResource(R.string.settings_height_unset),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
    Slider(
        value = height.toFloat(),
        onValueChange = { height = it.toInt(); prefs.userHeightCm = height },
        valueRange = 0f..220f,
        steps = 219,
    )
    Text(
        if (age > 0) stringResource(R.string.settings_age, age) else stringResource(R.string.settings_age_unset),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
    Slider(
        value = age.toFloat(),
        onValueChange = { age = it.toInt(); prefs.userAge = age },
        valueRange = 0f..99f,
        steps = 98,
    )
    Text(stringResource(R.string.settings_sex), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to R.string.settings_sex_unset, "M" to R.string.settings_sex_male, "F" to R.string.settings_sex_female).forEach { (code, label) ->
            FilterChip(selected = sex == code, onClick = { sex = code; prefs.userSex = code }, label = { Text(stringResource(label)) })
        }
    }
    Text(
        stringResource(R.string.settings_calories_hr_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

// --- App (version / update / debug / about) ---

@Composable
private fun AppSection(onOpenDebugLog: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { UpdateRepository() }
    val requestNotif = rememberNotificationPermission()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    // The download runs in a foreground worker, so it survives leaving this screen and the screen
    // going off. Observe it instead of owning it: re-entering settings re-attaches to it.
    val downloadInfos by androidx.work.WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(ApkDownloadWorker.WORK_NAME)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val download = downloadInfos.lastOrNull()
    val downloading = download?.state == androidx.work.WorkInfo.State.RUNNING ||
        download?.state == androidx.work.WorkInfo.State.ENQUEUED
    val progress = (download?.progress?.getInt(ApkDownloadWorker.KEY_PROGRESS, 0) ?: 0) / 100f

    // Hand the finished APK to the system installer once per completed download. The marker MUST be
    // persisted: WorkManager keeps a SUCCEEDED work around, so a remember-scoped flag reset on every
    // navigation and re-launched the installer for an old download each time this screen was opened.
    val prefs = remember { ViewerPreferences.get(context) }
    LaunchedEffect(download?.id, download?.state) {
        val d = download ?: return@LaunchedEffect
        if (d.state != androidx.work.WorkInfo.State.SUCCEEDED) return@LaunchedEffect
        val workId = d.id.toString()
        if (prefs.lastInstalledDownloadId == workId) return@LaunchedEffect
        val path = d.outputData.getString(ApkDownloadWorker.KEY_PATH) ?: return@LaunchedEffect
        prefs.lastInstalledDownloadId = workId
        ApkInstaller.install(context, java.io.File(path))
    }

    LanguageCard()

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.settings_app_installed_version), style = MaterialTheme.typography.labelMedium)
            Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleMedium)
        }
    }

    val networkError = stringResource(R.string.settings_update_network_error)
    Button(
        onClick = {
            state = UpdateState.Checking
            scope.launch {
                state = try {
                    repo.checkForUpdate()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
                } catch (e: Exception) {
                    UpdateState.Error(e.message ?: networkError)
                }
            }
        },
        enabled = state !is UpdateState.Checking && !downloading,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_check_update)) }

    if (downloading) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.settings_update_downloading, (progress * 100).toInt()))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.settings_update_background_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    download?.takeIf { it.state == androidx.work.WorkInfo.State.FAILED }?.let {
        val msg = it.outputData.getString(ApkDownloadWorker.KEY_ERROR)
            ?: stringResource(R.string.settings_update_download_error)
        Text(stringResource(R.string.settings_error, msg), color = MaterialTheme.colorScheme.error)
    }

    when (val s = state) {
        is UpdateState.Checking -> Text(stringResource(R.string.settings_update_checking))
        is UpdateState.UpToDate -> Text(stringResource(R.string.settings_update_up_to_date))
        is UpdateState.Error -> Text(stringResource(R.string.settings_error, s.message), color = MaterialTheme.colorScheme.error)
        is UpdateState.Available -> Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_update_new_version, s.info.version), style = MaterialTheme.typography.titleMedium)
                Text(s.info.changelog, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        if (!ApkInstaller.canInstall(context)) {
                            ApkInstaller.requestInstallPermission(context)
                            return@Button
                        }
                        requestNotif()
                        ApkDownloadWorker.enqueue(context, s.info.apkUrl)
                    },
                    enabled = !downloading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_update_download_install)) }
            }
        }
        UpdateState.Idle -> {}
    }

    // Debug: full app/viewer diagnostics.
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var diag by remember { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_debug_section), style = MaterialTheme.typography.labelMedium)
            OutlinedButton(
                onClick = onOpenDebugLog,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_debug_log_button)) }
            OutlinedButton(
                onClick = { scope.launch { diag = buildDiagnostics(context) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_debug_full)) }
            SimulatorSection()
            diag?.let { report ->
                OutlinedButton(
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(report)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_debug_copy)) }
                Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.settings_about_desc),
                style = MaterialTheme.typography.bodySmall)
            Text("github.com/borborborja/rumb", style = MaterialTheme.typography.bodySmall)
        }
    }

    // Open-source licenses & attributions (Apache-2.0 requires keeping these visible).
    var showLicenses by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_licenses_title), style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = { showLicenses = !showLicenses }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (showLicenses) R.string.settings_licenses_hide else R.string.settings_licenses_show))
            }
            if (showLicenses) {
                Text(
                    stringResource(R.string.settings_licenses_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// --- App language (per-app locales, API 33+) ---

@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lang_title), style = MaterialTheme.typography.labelMedium)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
                var current by remember {
                    mutableStateOf(localeManager.applicationLocales.toLanguageTags().substringBefore('-'))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = current.isEmpty(),
                        onClick = {
                            current = ""
                            localeManager.applicationLocales = android.os.LocaleList.getEmptyLocaleList()
                        },
                        label = { Text(stringResource(R.string.lang_system)) },
                    )
                    listOf(
                        "ca" to "Català", "es" to "Español", "en" to "English",
                        "fr" to "Français", "de" to "Deutsch", "it" to "Italiano", "pt" to "Português",
                        "nl" to "Nederlands", "pl" to "Polski", "ru" to "Русский", "uk" to "Українська",
                        "tr" to "Türkçe", "sv" to "Svenska", "da" to "Dansk", "fi" to "Suomi",
                        "cs" to "Čeština", "el" to "Ελληνικά", "ro" to "Română",
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = current == code,
                            onClick = {
                                current = code
                                localeManager.applicationLocales = android.os.LocaleList.forLanguageTags(code)
                            },
                            label = { Text(label) },
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.lang_hint_old),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

// --- Track appearance (moved from the HUD designer) ---

@Composable
private fun TrackAppearanceSection(prefs: ViewerPreferences) {
    val palette = listOf("#E63946", "#3A86FF", "#2A9D8F", "#F4A261", "#FFD166", "#9B5DE5")
    var mode by remember { mutableStateOf(cat.rumb.app.data.map.TrackColorMode.byName(prefs.trackColorMode)) }
    var color by remember { mutableStateOf(prefs.trackColor) }

    Text(stringResource(R.string.settings_appearance_track), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cat.rumb.app.data.map.TrackColorMode.entries.forEach { m ->
            FilterChip(
                selected = mode == m,
                onClick = { mode = m; prefs.trackColorMode = m.name },
                label = { Text(stringResource(m.labelRes)) },
            )
        }
    }
    if (mode == cat.rumb.app.data.map.TrackColorMode.SINGLE) {
        ColorPalette(palette, color) { color = it; prefs.trackColor = it }
    }
}

// --- Follow route + off-route alert (moved from the HUD designer) ---

@Composable
private fun MapSection(prefs: ViewerPreferences) {
    val context = LocalContext.current
    var cacheMb by remember { mutableFloatStateOf(prefs.mapCacheSizeMb.toFloat()) }
    var prefetch by remember { mutableStateOf(prefs.prefetchOnFollow) }

    Text(stringResource(R.string.settings_map_cache), style = MaterialTheme.typography.labelLarge)
    Text(stringResource(R.string.settings_map_cache_size, cacheMb.toInt()), style = MaterialTheme.typography.bodySmall)
    Slider(
        value = cacheMb,
        onValueChange = { cacheMb = it },
        onValueChangeFinished = {
            prefs.mapCacheSizeMb = cacheMb.toInt()
            cat.rumb.app.data.map.MapCache.applyAmbientSize(context, cacheMb.toInt())
        },
        valueRange = 100f..2000f,
        steps = 18,
    )
    OutlinedButton(
        onClick = {
            cat.rumb.app.data.map.MapCache.clearAmbient(context)
            android.widget.Toast.makeText(context, context.getString(R.string.settings_map_cache_cleared), android.widget.Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_map_clear_cache)) }

    Text(stringResource(R.string.settings_map_prefetch), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    ToggleRow(stringResource(R.string.settings_map_prefetch_follow), prefetch) { prefetch = it; prefs.prefetchOnFollow = it }
    Text(
        stringResource(R.string.settings_map_prefetch_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FollowRouteSection(prefs: ViewerPreferences) {
    val palette = listOf("#3A86FF", "#E63946", "#2A9D8F", "#F4A261", "#FFD166", "#9B5DE5")
    var color by remember { mutableStateOf(prefs.followColor) }
    var width by remember { mutableFloatStateOf(prefs.followWidth) }
    var arrows by remember { mutableStateOf(prefs.followArrows) }
    var arrowColor by remember { mutableStateOf(prefs.followArrowColor) }
    var arrowSize by remember { mutableFloatStateOf(prefs.followArrowSize) }
    var progress by remember { mutableStateOf(prefs.followProgress) }
    var tpStyle by remember { mutableStateOf(prefs.trackingPointStyle) }
    var tpColor by remember { mutableStateOf(prefs.trackingPointColor) }
    var tpSize by remember { mutableFloatStateOf(prefs.trackingPointSize) }
    var threshold by remember { mutableFloatStateOf(prefs.offRouteThresholdM.toFloat()) }
    var sound by remember { mutableStateOf(prefs.offRouteSound) }
    var vibrate by remember { mutableStateOf(prefs.offRouteVibrate) }
    var spoken by remember { mutableStateOf(prefs.offRouteSpoken) }

    Text(stringResource(R.string.settings_route_follow), style = MaterialTheme.typography.labelLarge)
    ColorPalette(palette, color) { color = it; prefs.followColor = it }
    Text(stringResource(R.string.settings_route_width, width.toInt()), style = MaterialTheme.typography.bodySmall)
    Slider(value = width, onValueChange = { width = it; prefs.followWidth = it }, valueRange = 3f..12f)
    ToggleRow(stringResource(R.string.settings_route_arrows), arrows) { arrows = it; prefs.followArrows = it }
    Text(
        stringResource(R.string.settings_route_arrows_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (arrows) {
        Text(stringResource(R.string.settings_route_arrow_color), style = MaterialTheme.typography.bodySmall)
        ColorPalette(palette, arrowColor) { arrowColor = it; prefs.followArrowColor = it }
        Text(stringResource(R.string.settings_route_arrow_size), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = arrowSize,
            onValueChange = { arrowSize = it; prefs.followArrowSize = it },
            valueRange = 0.4f..2.0f,
        )
    }
    ToggleRow(stringResource(R.string.settings_route_progress), progress) { progress = it; prefs.followProgress = it }

    Text(stringResource(R.string.settings_route_tracking_point), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = tpStyle == "DOT",
            onClick = { tpStyle = "DOT"; prefs.trackingPointStyle = "DOT" },
            label = { Text(stringResource(R.string.settings_tracking_dot)) },
        )
        FilterChip(
            selected = tpStyle == "ARROW",
            onClick = { tpStyle = "ARROW"; prefs.trackingPointStyle = "ARROW" },
            label = { Text(stringResource(R.string.settings_tracking_arrow)) },
        )
    }
    ColorPalette(palette, tpColor) { tpColor = it; prefs.trackingPointColor = it }
    Text(stringResource(R.string.settings_route_tracking_size), style = MaterialTheme.typography.bodySmall)
    Slider(value = tpSize, onValueChange = { tpSize = it; prefs.trackingPointSize = it }, valueRange = 0.6f..1.8f)

    Text(stringResource(R.string.settings_route_off_route), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.settings_route_threshold, threshold.toInt()), style = MaterialTheme.typography.bodySmall)
    Slider(
        value = threshold,
        onValueChange = { threshold = it; prefs.offRouteThresholdM = it.toInt() },
        valueRange = 10f..100f,
        steps = 8,
    )
    ToggleRow(stringResource(R.string.settings_route_sound), sound) { sound = it; prefs.offRouteSound = it }
    ToggleRow(stringResource(R.string.settings_route_vibration), vibrate) { vibrate = it; prefs.offRouteVibrate = it }
    ToggleRow(stringResource(R.string.settings_audio_off_route_spoken), spoken) { spoken = it; prefs.offRouteSpoken = it }
}

// --- Audio announcements (moved from the HUD designer) ---

@Composable
private fun AudioAnnouncementsSection(prefs: ViewerPreferences) {
    var enabled by remember { mutableStateOf(prefs.announceEnabled) }
    var voice by remember { mutableStateOf(prefs.announceMode == "VOICE") }
    var lang by remember { mutableStateOf(cat.rumb.app.viewer.audio.AnnounceLang.byCode(prefs.announceLang)) }
    var byDist by remember { mutableStateOf(prefs.announceByDistance) }
    var distKm by remember { mutableFloatStateOf(prefs.announceDistanceKm) }
    var byTime by remember { mutableStateOf(prefs.announceByTime) }
    var timeMin by remember { mutableFloatStateOf(prefs.announceTimeMin.toFloat()) }
    var fDist by remember { mutableStateOf(prefs.annDistanceTime) }
    var fPace by remember { mutableStateOf(prefs.annPace) }
    var fSplit by remember { mutableStateOf(prefs.annSplitPace) }
    var fElev by remember { mutableStateOf(prefs.annElevation) }
    var fHr by remember { mutableStateOf(prefs.annHeartRate) }
    var beepIdx by remember { mutableIntStateOf(prefs.announceBeepSound) }
    var headsUp by remember { mutableStateOf(prefs.turnHeadsUp) }

    Text(stringResource(R.string.settings_audio_title), style = MaterialTheme.typography.labelLarge)
    ToggleRow(stringResource(R.string.settings_audio_enable), enabled) { enabled = it; prefs.announceEnabled = it }
    if (!enabled) return

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(voice, { voice = true; prefs.announceMode = "VOICE" }, label = { Text(stringResource(R.string.settings_audio_voice)) })
        FilterChip(!voice, { voice = false; prefs.announceMode = "BEEP" }, label = { Text(stringResource(R.string.settings_audio_beeps)) })
    }
    if (voice) {
        Text(stringResource(R.string.settings_audio_language), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cat.rumb.app.viewer.audio.AnnounceLang.entries.forEach { l ->
                FilterChip(lang == l, { lang = l; prefs.announceLang = l.code }, label = { Text(l.label) })
            }
        }
    } else {
        Text(stringResource(R.string.settings_audio_beep_sound), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val soundLabels = listOf(
                R.string.settings_audio_sound_1, R.string.settings_audio_sound_2,
                R.string.settings_audio_sound_3, R.string.settings_audio_sound_4,
                R.string.settings_audio_sound_5,
            )
            soundLabels.forEachIndexed { i, label ->
                FilterChip(
                    selected = beepIdx == i,
                    onClick = {
                        beepIdx = i; prefs.announceBeepSound = i
                        cat.rumb.app.viewer.AlertFeedback.beeps(1, cat.rumb.app.viewer.BeepSound.byIndex(i))
                    },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        OutlinedButton(
            onClick = { cat.rumb.app.viewer.AlertFeedback.beeps(1, cat.rumb.app.viewer.BeepSound.byIndex(beepIdx)) },
        ) { Text(stringResource(R.string.settings_audio_preview)) }
    }

    ToggleRow(stringResource(R.string.settings_audio_turn_heads_up), headsUp) { headsUp = it; prefs.turnHeadsUp = it }

    ToggleRow(stringResource(R.string.settings_every_km, distKm.toInt()), byDist) { byDist = it; prefs.announceByDistance = it }
    if (byDist) {
        Slider(value = distKm, onValueChange = { distKm = it; prefs.announceDistanceKm = it }, valueRange = 1f..10f, steps = 8)
    }
    ToggleRow(stringResource(R.string.settings_every_min, timeMin.toInt()), byTime) { byTime = it; prefs.announceByTime = it }
    if (byTime) {
        Slider(value = timeMin, onValueChange = { timeMin = it; prefs.announceTimeMin = it.toInt() }, valueRange = 1f..30f, steps = 28)
    }

    if (voice) {
        Text(stringResource(R.string.settings_audio_announce_what), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        ToggleRow(stringResource(R.string.settings_audio_distance_time), fDist) { fDist = it; prefs.annDistanceTime = it }
        ToggleRow(stringResource(R.string.settings_audio_pace), fPace) { fPace = it; prefs.annPace = it }
        ToggleRow(stringResource(R.string.settings_audio_split_pace), fSplit) { fSplit = it; prefs.annSplitPace = it }
        ToggleRow(stringResource(R.string.settings_audio_elevation), fElev) { fElev = it; prefs.annElevation = it }
        ToggleRow(stringResource(R.string.settings_audio_heart_rate), fHr) { fHr = it; prefs.annHeartRate = it }
    }
}

// --- Activity types (predefined list + custom type management) ---

@Composable
private fun ActivityTypesSection(prefs: ViewerPreferences) {
    var custom by remember { mutableStateOf(ActivityTypes.decodeCustom(prefs.customActivityTypesJson)) }
    var editing by remember { mutableStateOf<CustomActivityType?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomActivityType?>(null) }

    fun persist(newList: List<CustomActivityType>) {
        custom = newList
        prefs.customActivityTypesJson = ActivityTypes.encodeCustom(newList)
    }

    Text(stringResource(R.string.settings_types_predefined_header), style = MaterialTheme.typography.labelMedium)
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityTypes.PREDEFINED.forEach { id ->
                val labelRes = ActivityTypeCatalog.labelRes(id) ?: return@forEach
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(ActivityTypeCatalog.iconFor(id), contentDescription = null)
                    Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    Text(
        stringResource(R.string.settings_types_custom_header),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (custom.isEmpty()) {
        Text(
            stringResource(R.string.settings_types_empty_custom),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    } else {
        Card {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                custom.forEach { type ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(ActivityTypeCatalog.iconFor(type.iconId), contentDescription = null)
                        Text(type.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editing = type; showEditor = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.settings_types_edit_title))
                        }
                        IconButton(onClick = { deleting = type }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.settings_types_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }

    OutlinedButton(
        onClick = { editing = null; showEditor = true },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) { Text(stringResource(R.string.settings_types_add)) }

    if (showEditor) {
        CustomTypeDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { name, iconId, family ->
                val current = editing
                val newList = if (current == null) {
                    custom + CustomActivityType(ActivityTypes.newCustomId(), name, iconId, family.name)
                } else {
                    custom.map {
                        if (it.id == current.id) it.copy(name = name, iconId = iconId, family = family.name) else it
                    }
                }
                persist(newList)
                showEditor = false
            },
        )
    }

    deleting?.let { type ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.settings_types_custom_header)) },
            text = { Text(stringResource(R.string.settings_types_delete_confirm, type.name)) },
            confirmButton = {
                TextButton(onClick = {
                    persist(custom.filterNot { it.id == type.id })
                    deleting = null
                }) { Text(stringResource(R.string.settings_types_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_types_cancel)) }
            },
        )
    }
}

/**
 * Debug: replay a saved track as fake GPS. It swaps the recorder's location source, so pressing
 * record in the viewer exercises the real path (engine → auto-lap → competition → save) from a
 * chair. What it records is marked SIMULATED and is excluded from records and competitions.
 */
@Composable
private fun SimulatorSection() {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    val prefs = remember { ViewerPreferences.get(context) }
    var trackId by remember { mutableStateOf(prefs.simulateTrackId) }
    var speed by remember { mutableFloatStateOf(prefs.simulateSpeed) }
    var tracks by remember { mutableStateOf<List<cat.rumb.app.data.tracks.FollowTrackEntity>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        tracks = app.trackRepository.observeSummaries().first()
            .filter { !it.archived && (it.pointCount) > 1 }
    }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(stringResource(R.string.settings_sim_title), style = MaterialTheme.typography.labelMedium)
    Text(
        stringResource(R.string.settings_sim_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    val active = tracks.firstOrNull { it.id == trackId }
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
        Text(active?.name ?: stringResource(R.string.settings_sim_off))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.settings_sim_off)) },
            onClick = { trackId = -1L; prefs.simulateTrackId = -1L; expanded = false },
        )
        tracks.forEach { t ->
            DropdownMenuItem(
                text = { Text(t.name, maxLines = 1) },
                onClick = { trackId = t.id; prefs.simulateTrackId = t.id; expanded = false },
            )
        }
    }
    if (trackId > 0) {
        Text(stringResource(R.string.settings_sim_speed, speed.toInt()), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = speed,
            onValueChange = { speed = it; prefs.simulateSpeed = it },
            valueRange = 1f..20f,
            steps = 18,
        )
        Text(
            stringResource(R.string.settings_sim_active_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun CustomTypeDialog(
    initial: CustomActivityType?,
    onDismiss: () -> Unit,
    onSave: (name: String, iconId: String, family: cat.rumb.app.data.tracks.ActivityFamily) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var iconId by remember { mutableStateOf(initial?.iconId ?: ActivityTypeCatalog.CURATED_ICON_IDS.first()) }
    // Required: without a family this type can't be compared against anything (records, competitions).
    var family by remember { mutableStateOf(initial?.familyEnum ?: cat.rumb.app.data.tracks.ActivityFamily.UNKNOWN) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.settings_types_add_title else R.string.settings_types_edit_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_types_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.settings_types_icon_label), style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActivityTypeCatalog.CURATED_ICON_IDS.chunked(6).forEach { rowIds ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowIds.forEach { id ->
                                val selected = id == iconId
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else Color.Transparent,
                                        )
                                        .clickable { iconId = id },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        ActivityTypeCatalog.iconFor(id),
                                        contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.family_picker_title), style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    cat.rumb.app.data.tracks.ActivityFamily.entries
                        .filter { it != cat.rumb.app.data.tracks.ActivityFamily.UNKNOWN }
                        .chunked(3)
                        .forEach { rowFamilies ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowFamilies.forEach { f ->
                                    FilterChip(
                                        selected = family == f,
                                        onClick = { family = f },
                                        label = { Text(stringResource(activityFamilyLabel(f))) },
                                    )
                                }
                            }
                        }
                }
                Text(
                    stringResource(R.string.family_picker_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), iconId, family) },
                enabled = name.isNotBlank() && family != cat.rumb.app.data.tracks.ActivityFamily.UNKNOWN,
            ) {
                Text(stringResource(R.string.settings_types_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_types_cancel)) }
        },
    )
}

// --- Shared bits ---

@Composable
private fun ColorPalette(palette: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        palette.forEach { hex ->
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(android.graphics.Color.parseColor(hex)))
                    .border(
                        if (selected == hex) 3.dp else 1.dp,
                        if (selected == hex) Color.Black else Color.Gray,
                        CircleShape,
                    )
                    .clickable { onPick(hex) },
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text("  $label", style = MaterialTheme.typography.bodyMedium)
    }
}
