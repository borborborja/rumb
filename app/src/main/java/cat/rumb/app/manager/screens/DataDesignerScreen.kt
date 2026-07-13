package cat.rumb.app.manager.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cat.rumb.app.R
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.viewer.data.DataLayout
import cat.rumb.app.viewer.data.DataLayoutStore
import cat.rumb.app.viewer.data.DataTileContent
import cat.rumb.app.viewer.hud.HudMetric
import cat.rumb.app.viewer.hud.LiveMetrics
import cat.rumb.app.viewer.hud.Units
import cat.rumb.app.viewer.hud.UnitsStore
import kotlin.time.Duration.Companion.minutes

private val SAMPLE_METRICS = LiveMetrics(
    speedKmh = 24.6, avgMovingSpeedKmh = 19.3, maxSpeedKmh = 41.2, distanceKm = 32.48,
    totalTime = 98.minutes, movingTime = 91.minutes, paceMinPerKm = 3.9, bearingDeg = 271.0,
    elevationGainM = 842.0, altitudeM = 1240.0, slopePercent = 6.4, vamMeterPerHour = 780.0,
    heartRateBpm = 148.0, cadenceRpm = 86.0, powerW = 213.0, remainingDistanceKm = 8.2,
    isRecording = true,
)

/**
 * Full-screen WYSIWYG editor for the "Dades" grid: tiles render with the EXACT live-view look
 * ([DataTileContent]) in the same grid; the top pill «Mètriques ▾» adds/removes tiles live, the
 * global ⚙ sets the columns; every tile (clock included) reorders by long-press drag, resizes with
 * the corner handle (span snap) and configures via its center gear. Auto-saves on every change.
 * The system back gesture is disabled here (exit with ←) so it can't steal widget drags.
 */
@Composable
fun DataDesignerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ViewerPreferences.get(context) }
    val units = remember { UnitsStore.load(prefs) }
    var layout by remember { mutableStateOf(DataLayoutStore.load(prefs)) }
    var selected by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var configFor by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var globalOpen by remember { mutableStateOf(false) }

    // Edit mode owns all gestures: the system back gesture is annulled (leave via ←).
    BackHandler(enabled = true) {}

    fun update(next: DataLayout) {
        layout = next
        DataLayoutStore.save(prefs, next) // live editing: always persisted
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // Mirrors DataView's contentPadding (12 sides, 60 top for the pill band).
                    .padding(start = 12.dp, end = 12.dp, top = 60.dp, bottom = 12.dp),
            ) {
                TileGrid(
                    layout = layout,
                    units = units,
                    selected = selected,
                    dragging = dragging,
                    onSelect = { selected = if (selected == it) null else it },
                    onDragState = { dragging = it },
                    onReorder = { from, to -> update(layout.moveTo(from, to)) },
                    onConfigure = { configFor = it },
                    onSpanStep = { field, step ->
                        update(layout.setSpan(field, (layout.spanOf(field) + step).coerceIn(1, layout.columns)))
                    },
                )
                if (layout.fields.isEmpty()) {
                    Text(
                        stringResource(R.string.editor_no_tiles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }

            // Top overlay: ← back, «Mètriques ▾» pill, global ⚙ to its right.
            Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
                RoundDarkButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_exit), tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Row(
                    Modifier.align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
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
                            Text(stringResource(R.string.editor_metrics), color = Color.White, fontWeight = FontWeight.Bold)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color.White)
                        }
                        MetricsDropdown(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            layout = layout,
                            onUpdate = { update(it); selected = null },
                        )
                    }
                    Box {
                        RoundDarkButton(onClick = { globalOpen = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.editor_global_settings), tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = globalOpen, onDismissRequest = { globalOpen = false }) {
                            Text(
                                stringResource(R.string.editor_columns),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(2, 3).forEach { n ->
                                    FilterChip(
                                        selected = layout.columns == n,
                                        onClick = { update(layout.copy(columns = n)) },
                                        label = { Text("$n") },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Per-tile settings dialog (clock gains the 24/12h chips).
            configFor?.let { field ->
                if (layout.contains(field)) {
                    TileConfigDialog(
                        layout = layout,
                        field = field,
                        onUpdate = ::update,
                        onRemove = { update(layout.remove(field)); configFor = null; selected = null },
                        onDismiss = { configFor = null },
                    )
                } else {
                    configFor = null
                }
            }
        }
    }
}

/** Multiselect dropdown: add/remove tiles live (the clock is just another row). */
@Composable
private fun MetricsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    layout: DataLayout,
    onUpdate: (DataLayout) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        HudMetric.entries.forEach { metric ->
            val placed = layout.contains(metric.name)
            Row(
                Modifier
                    .clickable { onUpdate(if (placed) layout.remove(metric.name) else layout.add(metric.name)) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = placed, onCheckedChange = null)
                Text(androidx.compose.ui.res.stringResource(metric.labelRes), Modifier.padding(end = 16.dp))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        val clockOn = layout.contains(DataLayout.CLOCK)
        Row(
            Modifier
                .clickable { onUpdate(if (clockOn) layout.remove(DataLayout.CLOCK) else layout.add(DataLayout.CLOCK)) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = clockOn, onCheckedChange = null)
            Text(stringResource(R.string.hudel_clock), Modifier.padding(end = 16.dp))
        }
    }
}

@Composable
private fun TileConfigDialog(
    layout: DataLayout,
    field: String,
    onUpdate: (DataLayout) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isClock = field == DataLayout.CLOCK
    val metric = if (isClock) null else runCatching { HudMetric.valueOf(field) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isClock) stringResource(R.string.hudel_clock) else metric?.let { stringResource(it.labelRes) } ?: field) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isClock) {
                    Text(stringResource(R.string.editor_format), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(layout.clockH24, { onUpdate(layout.copy(clockH24 = true)) }, label = { Text("24 h") })
                        FilterChip(!layout.clockH24, { onUpdate(layout.copy(clockH24 = false)) }, label = { Text("12 h") })
                    }
                }
                Text(stringResource(R.string.editor_width), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..layout.columns).forEach { n ->
                        FilterChip(
                            selected = layout.spanOf(field) == n,
                            onClick = { onUpdate(layout.setSpan(field, n)) },
                            label = { Text("${n}x") },
                        )
                    }
                }
                Text(stringResource(R.string.editor_value_color), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val palette = listOf(null, "#FFD166", "#E63946", "#2A9D8F", "#3A86FF", "#F4A261", "#9B5DE5")
                    palette.forEach { hex ->
                        val current = layout.colorOf(field)
                        val isSel = current == hex || (hex == null && current == null)
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(if (hex == null) MaterialTheme.colorScheme.onSurface else Color(android.graphics.Color.parseColor(hex)))
                                .border(
                                    if (isSel) 3.dp else 1.dp,
                                    if (isSel) MaterialTheme.colorScheme.primary else Color.Gray,
                                    CircleShape,
                                )
                                .clickable { onUpdate(layout.setColor(field, hex)) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_done)) } },
        dismissButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.editor_remove), color = MaterialTheme.colorScheme.error) } },
    )
}

/**
 * Drop-target resolution: the tile under [pointer], else the tile whose center is nearest by
 * Euclidean distance. The dragged tile is intentionally NOT excluded — once it has slid under the
 * finger it resolves to itself, so no further move fires (this is what lets a tile settle instead
 * of oscillating within its row, and lets it climb to an upper row when the finger moves there).
 * Pure (unit-tested).
 */
internal fun nearestField(bounds: Map<String, Rect>, pointer: Offset): String? {
    bounds.entries.firstOrNull { it.value.contains(pointer) }?.let { return it.key }
    return bounds.entries
        .minByOrNull { (it.value.center - pointer).getDistanceSquared() }
        ?.key
}

/**
 * The editable grid: rows packed by span exactly like the live view (10 dp gaps), long-press drag
 * live-reorders (launcher-style) with gap-tolerant targeting.
 */
@Composable
private fun TileGrid(
    layout: DataLayout,
    units: Units,
    selected: String?,
    dragging: String?,
    onSelect: (String) -> Unit,
    onDragState: (String?) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onConfigure: (String) -> Unit,
    onSpanStep: (String, Int) -> Unit,
) {
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val currentLayout by rememberUpdatedState(layout)
    bounds.keys.retainAll(layout.fields.toSet())

    val rows = remember(layout) {
        val out = mutableListOf<MutableList<String>>()
        var used = 0
        for (f in layout.fields) {
            val span = layout.spanOf(f)
            if (out.isEmpty() || used + span > layout.columns) {
                out.add(mutableListOf(f)); used = span
            } else {
                out.last().add(f); used += span
            }
        }
        out
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { field ->
                    key(field) {
                        EditableTile(
                            field = field,
                            layout = layout,
                            units = units,
                            isSelected = selected == field,
                            isDragging = dragging == field,
                            onSelect = { onSelect(field) },
                            onConfigure = { onConfigure(field) },
                            onBounds = { bounds[field] = it },
                            onDragStart = { onDragState(field) },
                            onDragEnd = { onDragState(null) },
                            onDragTo = { pointer ->
                                val target = nearestField(bounds, pointer)
                                if (target != null) {
                                    val from = currentLayout.fields.indexOf(field)
                                    val to = currentLayout.fields.indexOf(target)
                                    if (from >= 0 && to >= 0 && from != to) onReorder(from, to)
                                }
                            },
                            onSpanStep = { step -> onSpanStep(field, step) },
                            modifier = Modifier.weight(layout.spanOf(field).toFloat()),
                        )
                    }
                }
                val usedSpan = row.sumOf { layout.spanOf(it) }
                if (usedSpan < layout.columns) Spacer(Modifier.weight((layout.columns - usedSpan).toFloat()))
            }
        }
    }
}

@Composable
private fun EditableTile(
    field: String,
    layout: DataLayout,
    units: Units,
    isSelected: Boolean,
    isDragging: Boolean,
    onSelect: () -> Unit,
    onConfigure: () -> Unit,
    onBounds: (Rect) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragTo: (Offset) -> Unit,
    onSpanStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isClock = field == DataLayout.CLOCK
    val metric = if (isClock) null else runCatching { HudMetric.valueOf(field) }.getOrNull()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val interaction = remember { MutableInteractionSource() }
    val currentOnDragTo by rememberUpdatedState(onDragTo)
    val currentOnSpanStep by rememberUpdatedState(onSpanStep)
    var resizeAcc by remember { mutableStateOf(0f) }

    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { origin = it.boundsInRoot().topLeft; onBounds(it.boundsInRoot()) }
                .alpha(if (isDragging) 0.45f else 1f)
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                )
                // Widget drags near the screen edge must not become the system back gesture.
                .systemGestureExclusion()
                .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
                .pointerInput(field) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, _ ->
                            change.consume()
                            currentOnDragTo(origin + change.position)
                        },
                    )
                },
        ) {
            // EXACT live-view tile (single source of truth → true WYSIWYG).
            DataTileContent(
                label = if (isClock) stringResource(R.string.hudel_clock) else metric?.let { stringResource(it.labelRes) } ?: field,
                value = when {
                    isClock && layout.clockH24 -> "18:42:07"
                    isClock -> "6:42:07 PM"
                    else -> metric?.value(SAMPLE_METRICS, units) ?: "—"
                },
                unit = if (isClock) "" else metric?.unit(units) ?: "",
                colorHex = layout.colorOf(field),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (isSelected && !isDragging) {
            // Center gear: tile settings.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC1D3557))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onConfigure,
                    ),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.editor_configure), tint = Color.White, modifier = Modifier.size(16.dp)) }
            // Bottom-end handle: horizontal drag snaps the span 1x↔2x↔3x.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC000000))
                    .systemGestureExclusion()
                    .pointerInput(field) {
                        detectDragGestures(
                            onDragStart = { resizeAcc = 0f },
                            onDrag = { change, delta ->
                                change.consume()
                                resizeAcc += delta.x
                                if (resizeAcc > 100f) { currentOnSpanStep(1); resizeAcc = 0f }
                                if (resizeAcc < -100f) { currentOnSpanStep(-1); resizeAcc = 0f }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.OpenInFull, contentDescription = stringResource(R.string.editor_resize), tint = Color.White, modifier = Modifier.size(13.dp)) }
        }
    }
}

@Composable
private fun RoundDarkButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
