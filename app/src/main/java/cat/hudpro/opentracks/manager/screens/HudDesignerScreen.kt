package cat.hudpro.opentracks.manager.screens

import androidx.compose.foundation.background
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

/** Snap threshold (fraction of the canvas) for aligning widgets to edges. */
private const val SNAP = 0.03f

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
                    onMove = { i, x, y -> layout = layout.moveTo(i, snap(x), snap(y)) },
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
            }
        }
    }
}

/** Soft snapping to the canvas edges/center so widgets align cleanly. */
private fun snap(v: Float): Float {
    val targets = listOf(0.03f, 0.5f, 0.88f, 0.97f)
    val hit = targets.firstOrNull { abs(it - v) < SNAP }
    return (hit ?: v).coerceIn(0f, 1f)
}
