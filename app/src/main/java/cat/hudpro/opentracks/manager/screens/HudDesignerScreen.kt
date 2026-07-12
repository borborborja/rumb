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
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cat.hudpro.opentracks.data.prefs.ViewerPreferences
import cat.hudpro.opentracks.viewer.hud.HudCatalog
import cat.hudpro.opentracks.viewer.hud.HudData
import cat.hudpro.opentracks.viewer.hud.HudDesignerCanvas
import cat.hudpro.opentracks.viewer.hud.HudLayout
import cat.hudpro.opentracks.viewer.hud.HudLayoutStore
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes

private val SAMPLE = HudData(
    metrics = cat.hudpro.opentracks.viewer.hud.LiveMetrics(
        speedKmh = 24.6, avgMovingSpeedKmh = 19.3, maxSpeedKmh = 41.2, distanceKm = 32.48,
        totalTime = 98.minutes, movingTime = 91.minutes, paceMinPerKm = 3.9, bearingDeg = 271.0,
        elevationGainM = 842.0, altitudeM = 1240.0, slopePercent = 6.4, remainingDistanceKm = 8.2,
        isRecording = true,
    ),
    speedSeries = listOf(12f, 15f, 18f, 22f, 20f, 24f, 27f, 25f, 23f, 26f, 28f, 24f),
    elevationProfile = listOf(800f, 840f, 910f, 1010f, 1180f, 1240f, 1200f, 1300f, 1360f),
    routeProgress = 0.55f,
)

@Composable
fun HudDesignerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ViewerPreferences.get(context) }
    var layout by remember { mutableStateOf(HudLayoutStore.load(prefs)) }
    var selected by remember { mutableStateOf<Int?>(null) }

    DetailScaffold(
        title = "Diseñar HUD",
        onBack = onBack,
        actions = {
            IconButton(onClick = { HudLayoutStore.save(prefs, layout) }) {
                Icon(Icons.Filled.Save, contentDescription = "Guardar")
            }
        },
    ) { modifier ->
        Column(modifier.fillMaxSize()) {
            // Live preview: real map + draggable HUD, fills most of the screen.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                MapPreview(Modifier.fillMaxSize())
                HudDesignerCanvas(
                    layout = layout,
                    data = SAMPLE,
                    selectedIndex = selected,
                    onSelect = { selected = it },
                    onMove = { i, x, y -> layout = layout.moveTo(i, x, y) }, // free drag
                    onDragEnd = { i -> layout = snapWidgetToZone(layout, i) }, // magnetize on release
                    onRemove = { i ->
                        layout = layout.copy(widgets = layout.widgets.toMutableList().also { it.removeAt(i) })
                        selected = null
                    },
                )
            }

            // Controls: presets, scale, palette.
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Presets:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    HudLayout.PRESETS.forEach { (name, preset) ->
                        AssistChip(onClick = { layout = preset; selected = null }, label = { Text(name) })
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Mida", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = layout.scale,
                        onValueChange = { layout = layout.copy(scale = it) },
                        valueRange = 0.7f..1.4f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                }
                Text("Toca per afegir · arrossega per moure · toca la ✕ per treure",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudCatalog.elements.forEach { element ->
                        val placed = layout.contains(element.id)
                        FilterChip(
                            selected = placed,
                            onClick = {
                                layout = if (placed) layout.remove(element.id) else layout.add(element.id)
                                selected = null
                            },
                            label = { Text(element.label) },
                        )
                    }
                }

                TrackAppearanceSection(prefs)
                FollowRouteSection(prefs)
                AudioAnnouncementsSection(prefs)
            }
        }
    }
}

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

    Text("Àudio i avisos", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
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

    Text("Ruta a seguir", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        palette.forEach { hex ->
            Box(
                Modifier
                    .size(30.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(android.graphics.Color.parseColor(hex)))
                    .border(
                        if (color == hex) 3.dp else 1.dp,
                        if (color == hex) Color.Black else Color.Gray,
                        androidx.compose.foundation.shape.CircleShape,
                    )
                    .clickable { color = hex; prefs.followColor = hex },
            )
        }
    }
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

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
        Text("  $label", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TrackAppearanceSection(prefs: ViewerPreferences) {
    val palette = listOf("#E63946", "#3A86FF", "#2A9D8F", "#F4A261", "#FFD166", "#9B5DE5")
    var mode by remember { mutableStateOf(cat.hudpro.opentracks.data.map.TrackColorMode.byName(prefs.trackColorMode)) }
    var color by remember { mutableStateOf(prefs.trackColor) }

    Text("Aparença del track", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            palette.forEach { hex ->
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(android.graphics.Color.parseColor(hex)))
                        .border(
                            if (color == hex) 3.dp else 1.dp,
                            if (color == hex) Color.Black else Color.Gray,
                            androidx.compose.foundation.shape.CircleShape,
                        )
                        .clickable { color = hex; prefs.trackColor = hex },
                )
            }
        }
    }
}

// Magnetized zones: 3 columns × 6 rows. On release a widget snaps to the nearest zone so the HUD
// stays tidy while dragging remains completely free.
private val ZONE_COLS = listOf(0.02f, 0.36f, 0.70f)
private val ZONE_ROWS = listOf(0.03f, 0.20f, 0.37f, 0.54f, 0.70f, 0.85f)

private fun snapWidgetToZone(layout: HudLayout, index: Int): HudLayout {
    val w = layout.widgets.getOrNull(index) ?: return layout
    val x = ZONE_COLS.minByOrNull { abs(it - w.x) } ?: w.x
    val y = ZONE_ROWS.minByOrNull { abs(it - w.y) } ?: w.y
    return layout.moveTo(index, x, y)
}
