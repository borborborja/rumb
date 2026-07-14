package cat.rumb.app.manager.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
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
    data object Downloading : UpdateState
    data class Error(val message: String) : UpdateState
}

private val TABS = listOf(
    R.string.settings_tab_units,
    R.string.settings_tab_recording,
    R.string.settings_tab_sync,
    R.string.settings_tab_appearance,
    R.string.settings_tab_route,
    R.string.settings_tab_map,
    R.string.settings_tab_audio,
    R.string.settings_tab_activity_types,
    R.string.settings_tab_app,
)

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenDebugLog: () -> Unit = {}, onOpenSensors: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { ViewerPreferences.get(context) }
    var tab by remember { mutableIntStateOf(0) }

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
                    0 -> UnitsSection(prefs)
                    1 -> RecordingSection(prefs, onOpenSensors)
                    2 -> SyncSection()
                    3 -> TrackAppearanceSection(prefs)
                    4 -> FollowRouteSection(prefs)
                    5 -> MapSection(prefs)
                    6 -> AudioAnnouncementsSection(prefs)
                    7 -> ActivityTypesSection(prefs)
                    else -> AppSection(onOpenDebugLog)
                }
            }
        }
    }
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

@Composable
private fun SyncSection() {
    val context = LocalContext.current
    val endurainPrefs = remember { cat.rumb.app.data.prefs.EndurainPreferences.get(context) }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(endurainPrefs.host ?: "") }
    var apiKey by remember { mutableStateOf(endurainPrefs.apiKey ?: "") }
    var status by remember { mutableStateOf<String?>(null) }

    Text(stringResource(R.string.settings_sync_endurain), style = MaterialTheme.typography.titleSmall)
    androidx.compose.material3.OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text(stringResource(R.string.settings_sync_server_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    androidx.compose.material3.OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        label = { Text(stringResource(R.string.settings_sync_api_key_label)) },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            endurainPrefs.host = host
            endurainPrefs.apiKey = apiKey
            status = context.getString(R.string.settings_sync_saved_testing)
            scope.launch {
                val repo = cat.rumb.app.data.endurain.EndurainRepository(endurainPrefs)
                status = repo.testConnection().fold(
                    onSuccess = { context.getString(R.string.settings_sync_connected, "$it") },
                    onFailure = { context.getString(R.string.settings_error, "${it.message}") },
                )
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
    var maxHr by remember { mutableIntStateOf(prefs.userMaxHr) }
    var weight by remember { mutableIntStateOf(prefs.userWeightKg) }
    var age by remember { mutableIntStateOf(prefs.userAge) }
    var sex by remember { mutableStateOf(prefs.userSex) }

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
    Text(
        stringResource(R.string.settings_rec_apply_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 8.dp),
    )
}

// --- App (version / update / debug / about) ---

@Composable
private fun AppSection(onOpenDebugLog: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { UpdateRepository() }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var progress by remember { mutableFloatStateOf(0f) }

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
        enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_check_update)) }

    when (val s = state) {
        is UpdateState.Checking -> Text(stringResource(R.string.settings_update_checking))
        is UpdateState.UpToDate -> Text(stringResource(R.string.settings_update_up_to_date))
        is UpdateState.Error -> Text(stringResource(R.string.settings_error, s.message), color = MaterialTheme.colorScheme.error)
        is UpdateState.Downloading ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_update_downloading, (progress * 100).toInt()))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        is UpdateState.Available -> Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_update_new_version, s.info.version), style = MaterialTheme.typography.titleMedium)
                Text(s.info.changelog, style = MaterialTheme.typography.bodySmall)
                val downloadError = stringResource(R.string.settings_update_download_error)
                Button(
                    onClick = {
                        if (!ApkInstaller.canInstall(context)) {
                            ApkInstaller.requestInstallPermission(context)
                            return@Button
                        }
                        state = UpdateState.Downloading
                        progress = 0f
                        scope.launch {
                            try {
                                val file = ApkInstaller.download(context, s.info.apkUrl) { progress = it }
                                ApkInstaller.install(context, file)
                                state = UpdateState.Idle
                            } catch (e: Exception) {
                                state = UpdateState.Error(e.message ?: downloadError)
                            }
                        }
                    },
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
                    listOf("ca" to "Català", "es" to "Español", "en" to "English").forEach { (code, label) ->
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
    var progress by remember { mutableStateOf(prefs.followProgress) }
    var tpStyle by remember { mutableStateOf(prefs.trackingPointStyle) }
    var tpColor by remember { mutableStateOf(prefs.trackingPointColor) }
    var tpSize by remember { mutableFloatStateOf(prefs.trackingPointSize) }
    var threshold by remember { mutableFloatStateOf(prefs.offRouteThresholdM.toFloat()) }
    var sound by remember { mutableStateOf(prefs.offRouteSound) }
    var vibrate by remember { mutableStateOf(prefs.offRouteVibrate) }

    Text(stringResource(R.string.settings_route_follow), style = MaterialTheme.typography.labelLarge)
    ColorPalette(palette, color) { color = it; prefs.followColor = it }
    Text(stringResource(R.string.settings_route_width, width.toInt()), style = MaterialTheme.typography.bodySmall)
    Slider(value = width, onValueChange = { width = it; prefs.followWidth = it }, valueRange = 3f..12f)
    ToggleRow(stringResource(R.string.settings_route_arrows), arrows) { arrows = it; prefs.followArrows = it }
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
    var offSpoken by remember { mutableStateOf(prefs.offRouteSpoken) }
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
        ToggleRow(stringResource(R.string.settings_audio_off_route_spoken), offSpoken) { offSpoken = it; prefs.offRouteSpoken = it }
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(ActivityTypeCatalog.iconFor(id), contentDescription = null)
                    Text(stringResource(ActivityTypeCatalog.labelRes(id)!!), style = MaterialTheme.typography.bodyMedium)
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
            onSave = { name, iconId ->
                val current = editing
                val newList = if (current == null) {
                    custom + CustomActivityType(ActivityTypes.newCustomId(), name, iconId)
                } else {
                    custom.map { if (it.id == current.id) it.copy(name = name, iconId = iconId) else it }
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

@Composable
private fun CustomTypeDialog(
    initial: CustomActivityType?,
    onDismiss: () -> Unit,
    onSave: (name: String, iconId: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var iconId by remember { mutableStateOf(initial?.iconId ?: ActivityTypeCatalog.CURATED_ICON_IDS.first()) }

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
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), iconId) }, enabled = name.isNotBlank()) {
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
