package cat.hudpro.opentracks.manager.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cat.hudpro.opentracks.BuildConfig
import cat.hudpro.opentracks.data.prefs.ViewerPreferences
import cat.hudpro.opentracks.data.update.ApkInstaller
import cat.hudpro.opentracks.data.update.UpdateInfo
import cat.hudpro.opentracks.data.update.UpdateRepository
import kotlinx.coroutines.launch

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data object Downloading : UpdateState
    data class Error(val message: String) : UpdateState
}

private val TABS = listOf("Unitats", "Aparença", "Ruta", "Àudio", "App")

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenDebugLog: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { ViewerPreferences.get(context) }
    var tab by remember { mutableIntStateOf(0) }

    DetailScaffold(title = "Ajustos", onBack = onBack) { modifier ->
        Column(modifier.fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                TABS.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    0 -> UnitsSection(prefs)
                    1 -> TrackAppearanceSection(prefs)
                    2 -> FollowRouteSection(prefs)
                    3 -> AudioAnnouncementsSection(prefs)
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

    Text("Distància", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(dist == "KM", { dist = "KM"; prefs.distanceUnit = "KM" }, label = { Text("Quilòmetres (km)") })
        FilterChip(dist == "MILE", { dist = "MILE"; prefs.distanceUnit = "MILE" }, label = { Text("Milles (mi)") })
    }
    Text("Altitud i desnivell", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(elev == "METER", { elev = "METER"; prefs.elevationUnit = "METER" }, label = { Text("Metres (m)") })
        FilterChip(elev == "FOOT", { elev = "FOOT"; prefs.elevationUnit = "FOOT" }, label = { Text("Peus (ft)") })
    }
    Text("Velocitat", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(spd == "KMH", { spd = "KMH"; prefs.speedUnit = "KMH" }, label = { Text("km/h") })
        FilterChip(spd == "MPH", { spd = "MPH"; prefs.speedUnit = "MPH" }, label = { Text("mph") })
    }
    Text(
        "El ritme usa la unitat de distància (min/km o min/mi); VAM i desviació, la d'altitud.",
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

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Versió instal·lada", style = MaterialTheme.typography.labelMedium)
            Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleMedium)
        }
    }

    Button(
        onClick = {
            state = UpdateState.Checking
            scope.launch {
                state = try {
                    repo.checkForUpdate()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
                } catch (e: Exception) {
                    UpdateState.Error(e.message ?: "Error de xarxa")
                }
            }
        },
        enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Buscar actualització") }

    when (val s = state) {
        is UpdateState.Checking -> Text("Comprovant…")
        is UpdateState.UpToDate -> Text("Estàs a l'última versió ✓")
        is UpdateState.Error -> Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error)
        is UpdateState.Downloading ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Descarregant… ${(progress * 100).toInt()}%")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        is UpdateState.Available -> Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nova versió v${s.info.version}", style = MaterialTheme.typography.titleMedium)
                Text(s.info.changelog, style = MaterialTheme.typography.bodySmall)
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
                                state = UpdateState.Error(e.message ?: "Error descarregant")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Descarregar i instal·lar") }
            }
        }
        UpdateState.Idle -> {}
    }

    // Debug: full app/viewer diagnostics.
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var diag by remember { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Depuració", style = MaterialTheme.typography.labelMedium)
            OutlinedButton(
                onClick = onOpenDebugLog,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Registre de debug (log complet)") }
            OutlinedButton(
                onClick = { scope.launch { diag = buildDiagnostics(context) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Diagnòstic complet") }
            OutlinedButton(
                onClick = { diag = cat.hudpro.opentracks.data.opentracks.OpenTracksRecording.diagnostics(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Provar iniciar gravació") }
            diag?.let { report ->
                OutlinedButton(
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(report)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Copiar diagnòstic") }
                Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Quant a", style = MaterialTheme.typography.labelMedium)
            Text("OpenTracks HUD Pro — fork d'OSMDashboard amb mapes ICGC, HUD i Endurain.",
                style = MaterialTheme.typography.bodySmall)
            Text("github.com/borborborja/opentracks-HUDpro", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// --- Track appearance (moved from the HUD designer) ---

@Composable
private fun TrackAppearanceSection(prefs: ViewerPreferences) {
    val palette = listOf("#E63946", "#3A86FF", "#2A9D8F", "#F4A261", "#FFD166", "#9B5DE5")
    var mode by remember { mutableStateOf(cat.hudpro.opentracks.data.map.TrackColorMode.byName(prefs.trackColorMode)) }
    var color by remember { mutableStateOf(prefs.trackColor) }

    Text("Aparença del track", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cat.hudpro.opentracks.data.map.TrackColorMode.entries.forEach { m ->
            FilterChip(
                selected = mode == m,
                onClick = { mode = m; prefs.trackColorMode = m.name },
                label = { Text(m.label) },
            )
        }
    }
    if (mode == cat.hudpro.opentracks.data.map.TrackColorMode.SINGLE) {
        ColorPalette(palette, color) { color = it; prefs.trackColor = it }
    }
}

// --- Follow route + off-route alert (moved from the HUD designer) ---

@Composable
private fun FollowRouteSection(prefs: ViewerPreferences) {
    val palette = listOf("#3A86FF", "#E63946", "#2A9D8F", "#F4A261", "#FFD166", "#9B5DE5")
    var color by remember { mutableStateOf(prefs.followColor) }
    var width by remember { mutableFloatStateOf(prefs.followWidth) }
    var arrows by remember { mutableStateOf(prefs.followArrows) }
    var progress by remember { mutableStateOf(prefs.followProgress) }
    var threshold by remember { mutableFloatStateOf(prefs.offRouteThresholdM.toFloat()) }
    var sound by remember { mutableStateOf(prefs.offRouteSound) }
    var vibrate by remember { mutableStateOf(prefs.offRouteVibrate) }

    Text("Ruta a seguir", style = MaterialTheme.typography.labelLarge)
    ColorPalette(palette, color) { color = it; prefs.followColor = it }
    Text("Gruix ${width.toInt()}", style = MaterialTheme.typography.bodySmall)
    Slider(value = width, onValueChange = { width = it; prefs.followWidth = it }, valueRange = 3f..12f)
    ToggleRow("Fletxes de direcció", arrows) { arrows = it; prefs.followArrows = it }
    ToggleRow("Mostrar progrés (recorregut atenuat)", progress) { progress = it; prefs.followProgress = it }

    Text("Avís fora de ruta", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Text("Llindar de desviació: ${threshold.toInt()} m", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = threshold,
        onValueChange = { threshold = it; prefs.offRouteThresholdM = it.toInt() },
        valueRange = 10f..100f,
        steps = 8,
    )
    ToggleRow("So", sound) { sound = it; prefs.offRouteSound = it }
    ToggleRow("Vibració", vibrate) { vibrate = it; prefs.offRouteVibrate = it }
}

// --- Audio announcements (moved from the HUD designer) ---

@Composable
private fun AudioAnnouncementsSection(prefs: ViewerPreferences) {
    var enabled by remember { mutableStateOf(prefs.announceEnabled) }
    var voice by remember { mutableStateOf(prefs.announceMode == "VOICE") }
    var lang by remember { mutableStateOf(cat.hudpro.opentracks.viewer.audio.AnnounceLang.byCode(prefs.announceLang)) }
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

    Text("Àudio i avisos", style = MaterialTheme.typography.labelLarge)
    ToggleRow("Activar avisos d'àudio", enabled) { enabled = it; prefs.announceEnabled = it }
    if (!enabled) return

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(voice, { voice = true; prefs.announceMode = "VOICE" }, label = { Text("Veu") })
        FilterChip(!voice, { voice = false; prefs.announceMode = "BEEP" }, label = { Text("Xiulets") })
    }
    if (voice) {
        Text("Idioma", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cat.hudpro.opentracks.viewer.audio.AnnounceLang.entries.forEach { l ->
                FilterChip(lang == l, { lang = l; prefs.announceLang = l.code }, label = { Text(l.label) })
            }
        }
    }

    ToggleRow("Cada ${distKm.toInt()} km", byDist) { byDist = it; prefs.announceByDistance = it }
    if (byDist) {
        Slider(value = distKm, onValueChange = { distKm = it; prefs.announceDistanceKm = it }, valueRange = 1f..10f, steps = 8)
    }
    ToggleRow("Cada ${timeMin.toInt()} min", byTime) { byTime = it; prefs.announceByTime = it }
    if (byTime) {
        Slider(value = timeMin, onValueChange = { timeMin = it; prefs.announceTimeMin = it.toInt() }, valueRange = 1f..30f, steps = 28)
    }

    if (voice) {
        Text("Què s'anuncia", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        ToggleRow("Distància i temps", fDist) { fDist = it; prefs.annDistanceTime = it }
        ToggleRow("Ritme", fPace) { fPace = it; prefs.annPace = it }
        ToggleRow("Ritme de l'últim km", fSplit) { fSplit = it; prefs.annSplitPace = it }
        ToggleRow("Desnivell", fElev) { fElev = it; prefs.annElevation = it }
        ToggleRow("Pulsacions", fHr) { fHr = it; prefs.annHeartRate = it }
        ToggleRow("Dir 'Fora de ruta' per veu", offSpoken) { offSpoken = it; prefs.offRouteSpoken = it }
    }
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
