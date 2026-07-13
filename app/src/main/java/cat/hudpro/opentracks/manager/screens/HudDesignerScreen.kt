package cat.hudpro.opentracks.manager.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cat.hudpro.opentracks.data.prefs.ViewerPreferences
import cat.hudpro.opentracks.viewer.hud.HudCatalog
import cat.hudpro.opentracks.viewer.hud.HudCategory
import cat.hudpro.opentracks.viewer.hud.HudData
import cat.hudpro.opentracks.viewer.hud.HudEditorCanvas
import cat.hudpro.opentracks.viewer.hud.HudLayout
import cat.hudpro.opentracks.viewer.hud.HudLayoutStore
import cat.hudpro.opentracks.viewer.hud.HudOption
import cat.hudpro.opentracks.viewer.hud.HudZone
import kotlin.time.Duration.Companion.minutes

private val SAMPLE = HudData(
    metrics = cat.hudpro.opentracks.viewer.hud.LiveMetrics(
        speedKmh = 24.6, avgMovingSpeedKmh = 19.3, maxSpeedKmh = 41.2, distanceKm = 32.48,
        totalTime = 98.minutes, movingTime = 91.minutes, paceMinPerKm = 3.9, bearingDeg = 271.0,
        elevationGainM = 842.0, altitudeM = 1240.0, slopePercent = 6.4, remainingDistanceKm = 8.2,
        heartRateBpm = 148.0, cadenceRpm = 86.0, powerW = 213.0,
        isRecording = true,
    ),
    speedSeries = listOf(12f, 15f, 18f, 22f, 20f, 24f, 27f, 25f, 23f, 26f, 28f, 24f),
    heartRateSeries = listOf(120f, 132f, 141f, 150f, 147f, 155f, 149f, 152f, 148f, 151f),
    cadenceSeries = listOf(78f, 82f, 85f, 88f, 84f, 86f, 87f, 85f, 86f, 86f),
    powerSeries = listOf(180f, 205f, 226f, 240f, 218f, 210f, 231f, 224f, 213f, 219f),
    elevationProfile = listOf(800f, 840f, 910f, 1010f, 1180f, 1240f, 1200f, 1300f, 1360f),
    routeProgress = 0.55f,
)

/**
 * Full-screen WYSIWYG HUD edit mode: the exact viewer look (map + HUD), where widgets are dragged
 * (magnetized zones), resized (corner handle) and configured (center gear). The top pill — where the
 * viewer shows Mapa/Dades — is the widget multiselector dropdown; every change auto-saves.
 */
@Composable
fun HudDesignerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ViewerPreferences.get(context) }
    var layout by remember { mutableStateOf(HudLayoutStore.load(prefs)) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var configFor by remember { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    fun update(next: HudLayout) {
        layout = next
        HudLayoutStore.save(prefs, next) // live editing: always persisted
    }

    Box(Modifier.fillMaxSize()) {
        MapPreview(Modifier.fillMaxSize())
        HudEditorCanvas(
            layout = layout,
            data = SAMPLE,
            selectedIndex = selected,
            onSelect = { selected = it },
            onChange = ::update,
            onConfigure = { configFor = it },
            modifier = Modifier.safeDrawingPadding(),
        )

        // Top overlay: back arrow + the "Widgets ▾" pill (the editor's counterpart of Mapa/Dades).
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 8.dp)) {
            RoundIconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Sortir", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x99000000))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { menuOpen = true }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Widgets", color = Color.White, fontWeight = FontWeight.Bold)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color.White)
                }
                WidgetsDropdown(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    layout = layout,
                    onUpdate = { update(it); selected = null },
                )
            }
        }

        // Per-widget settings dialog (center gear).
        val cfg = configFor
        if (cfg != null && cfg in layout.widgets.indices) {
            WidgetConfigDialog(
                layout = layout,
                index = cfg,
                onUpdate = ::update,
                onRemove = { update(layout.copy(widgets = layout.widgets.toMutableList().also { it.removeAt(cfg) })); configFor = null; selected = null },
                onDismiss = { configFor = null },
            )
        } else if (cfg != null) {
            configFor = null
        }
    }
}

/** Multiselect dropdown: add/remove widgets live without leaving the editor; presets + global size. */
@Composable
private fun WidgetsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    layout: HudLayout,
    onUpdate: (HudLayout) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val groups = listOf(
            "Mètriques" to HudCategory.METRIC,
            "Gràfics" to HudCategory.CHART,
            "Controls" to HudCategory.CONTROL,
        )
        groups.forEach { (title, category) ->
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HudCatalog.elements.filter { it.category == category }.forEach { element ->
                val placed = layout.contains(element.id)
                Row(
                    Modifier
                        .clickable {
                            onUpdate(if (placed) layout.remove(element.id) else layout.add(element.id))
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = placed, onCheckedChange = null)
                    Text(element.label, Modifier.padding(end = 16.dp))
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(
            "Presets",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HudLayout.PRESETS.forEach { (name, preset) ->
                AssistChip(onClick = { onUpdate(preset) }, label = { Text(name) })
            }
        }
        Text(
            "Mida global",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Slider(
            value = layout.scale,
            onValueChange = { onUpdate(layout.copy(scale = it)) },
            valueRange = 0.7f..1.4f,
            modifier = Modifier.padding(horizontal = 16.dp).width(220.dp),
        )
    }
}

/** Per-widget settings: fine size, exact zone, remove. */
@Composable
private fun WidgetConfigDialog(
    layout: HudLayout,
    index: Int,
    onUpdate: (HudLayout) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val widget = layout.widgets[index]
    val element = widget.element
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(element?.label ?: "Widget") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Mida de la lletra: ${"%.0f".format(widget.scale * 100)}%", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = widget.scale,
                    onValueChange = { onUpdate(layout.setWidgetScale(index, it)) },
                    valueRange = HudLayout.MIN_WIDGET_SCALE..HudLayout.MAX_WIDGET_SCALE,
                )

                Text("Color del valor", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val palette = listOf(null, "#FFD166", "#E63946", "#2A9D8F", "#3A86FF", "#F4A261", "#9B5DE5")
                    palette.forEach { hex ->
                        val current = widget.options[HudOption.COLOR]
                        val selected = current == hex || (hex == null && current == null)
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    if (hex == null) Color.White
                                    else Color(android.graphics.Color.parseColor(hex)),
                                )
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                    androidx.compose.foundation.shape.CircleShape,
                                )
                                .clickable { onUpdate(layout.setWidgetOption(index, HudOption.COLOR, hex)) },
                        )
                    }
                }

                // History chart toggle (metrics with a live series).
                val chartable = element?.metric in setOf(
                    cat.hudpro.opentracks.viewer.hud.HudMetric.SPEED,
                    cat.hudpro.opentracks.viewer.hud.HudMetric.HEART_RATE,
                    cat.hudpro.opentracks.viewer.hud.HudMetric.CADENCE,
                    cat.hudpro.opentracks.viewer.hud.HudMetric.POWER,
                )
                if (chartable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mostrar gràfica", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        androidx.compose.material3.Switch(
                            checked = widget.options[HudOption.CHART] == "1",
                            onCheckedChange = { on ->
                                onUpdate(layout.setWidgetOption(index, HudOption.CHART, if (on) "1" else null))
                            },
                        )
                    }
                }

                // Clock-specific: 24 h vs 12 h.
                if (widget.elementId == HudCatalog.WIDGET_CLOCK) {
                    Text("Format de l'hora", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val h24 = widget.options[HudOption.H24] != "0"
                        androidx.compose.material3.FilterChip(
                            selected = h24,
                            onClick = { onUpdate(layout.setWidgetOption(index, HudOption.H24, null)) },
                            label = { Text("24 h") },
                        )
                        androidx.compose.material3.FilterChip(
                            selected = !h24,
                            onClick = { onUpdate(layout.setWidgetOption(index, HudOption.H24, "0")) },
                            label = { Text("12 h") },
                        )
                    }
                }

                Text("Zona", style = MaterialTheme.typography.labelLarge)
                ZonePicker(widget.zone) { zone -> onUpdate(layout.moveToZone(index, zone)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fet") } },
        dismissButton = {
            TextButton(onClick = onRemove) { Text("Treure", color = MaterialTheme.colorScheme.error) }
        },
    )
}

/** 3×3 grid of zone buttons (center-center is inactive) for precise placement. */
@Composable
private fun ZonePicker(current: HudZone, onPick: (HudZone) -> Unit) {
    val grid = listOf(
        listOf(HudZone.TOP_LEFT, HudZone.TOP_CENTER, HudZone.TOP_RIGHT),
        listOf(HudZone.MIDDLE_LEFT, null, HudZone.MIDDLE_RIGHT),
        listOf(HudZone.BOTTOM_LEFT, HudZone.BOTTOM_CENTER, HudZone.BOTTOM_RIGHT),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        grid.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { zone ->
                    if (zone == null) {
                        Box(Modifier.size(48.dp))
                    } else {
                        val isSel = zone == current
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onPick(zone) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                zone.label,
                                color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x99000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}
