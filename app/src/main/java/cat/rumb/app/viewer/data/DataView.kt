package cat.rumb.app.viewer.data

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FlagCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cat.rumb.app.R
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.viewer.hud.HudData
import cat.rumb.app.viewer.hud.HudMetric
import cat.rumb.app.viewer.hud.drawSeries
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val AccentSpeed = Color(0xFFFFD166)
private val AccentElev = Color(0xFF8ECAE6)
private val RecordRed = Color(0xFFE63946)

/** One resolved tile in the Dades grid. */
private sealed interface Tile {
    val span: Int

    data class Metric(
        val label: String,
        val value: String,
        val unit: String,
        val color: String?,
        val series: List<Float>?,
        override val span: Int,
    ) : Tile

    data class Chart(val label: String, val series: List<Float>, val progress: Float?, val accent: Color, override val span: Int) : Tile

    data class Toggle(val toggle: DataToggle, val label: String, val hint: String?, override val span: Int) : Tile
}

/**
 * Full-screen "Dades" view: a scrollable grid of live metrics plus graphical + interactive tiles,
 * with a fixed record button anchored at the bottom. Reuses [HudMetric] formatters so values match
 * the HUD widgets. Now interactive: chart tiles graph live series and toggle tiles flip settings.
 */
@Composable
fun DataView(
    data: HudData,
    modifier: Modifier = Modifier,
    reloadKey: Any? = null,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onPauseRecording: () -> Unit = {},
    onResumeRecording: () -> Unit = {},
    onLap: () -> Unit = {},
    onEndLaps: () -> Unit = {},
    onToggleSetting: (DataToggle, Boolean) -> Unit = { _, _ -> },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = ViewerPreferences.get(context)
    val layout = remember(reloadKey) { DataLayoutStore.load(prefs) }
    val followOnly = setOf(HudMetric.REMAINING.name, HudMetric.OFF_ROUTE.name)

    // Toggle tiles read straight from prefs (in-memory, updates synchronously). Bumped on tap to
    // recompose instantly, and the ~1 s HudData recomposition reflects changes made elsewhere too —
    // so the switch never latches a stale value (unlike a one-shot optimistic cache).
    var togglesVersion by remember { mutableStateOf(0) }

    // Live wall clock (updates every second).
    val clock by produceState(initialValue = "", layout.clockH24) {
        val fmt = DateTimeFormatter.ofPattern(if (layout.clockH24) "HH:mm:ss" else "h:mm:ss a")
        while (true) {
            value = LocalTime.now().format(fmt)
            delay(1000)
        }
    }

    // Cells strictly in fields order; clock/chart/toggle are just fields (orderable/resizable).
    val tiles = layout.fields.mapNotNull { field ->
        val span = layout.spanOf(field)
        when {
            field == DataLayout.CLOCK ->
                Tile.Metric(context.getString(R.string.hudel_clock), clock, "", layout.colorOf(field), null, span)
            !data.following && field in followOnly -> null
            !data.competing && field in COMPETITION_FIELDS -> null
            DataChart.byId(field) != null -> {
                val chart = DataChart.byId(field)!!
                val series = if (chart == DataChart.SPEED) data.speedSeries else data.elevationProfile
                Tile.Chart(
                    label = context.getString(chart.labelRes),
                    series = series,
                    progress = if (chart == DataChart.ELEVATION) data.routeProgress else null,
                    accent = if (chart == DataChart.SPEED) AccentSpeed else AccentElev,
                    span = span,
                )
            }
            DataToggle.byId(field) != null -> {
                val t = DataToggle.byId(field)!!
                Tile.Toggle(t, context.getString(t.labelRes), t.hintRes?.let { context.getString(it) }, span)
            }
            else -> runCatching { HudMetric.valueOf(field) }.getOrNull()?.let { metric ->
                val color = if (field == HudMetric.GHOST_DELTA.name) {
                    data.ghostState?.colorHex ?: layout.colorOf(field)
                } else {
                    layout.colorOf(field)
                }
                Tile.Metric(
                    label = context.getString(metric.labelRes),
                    value = metric.value(data.metrics, data.units),
                    unit = metric.unit(data.units),
                    color = color,
                    series = if (layout.hasGraph(field)) seriesFor(field, data) else null,
                    span = span,
                )
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(layout.columns.coerceIn(1, 3)),
                // Top clears the "Mapa / Dades" switcher; bottom clears the fixed record bar.
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 60.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tiles, span = { GridItemSpan(it.span) }) { tile ->
                    when (tile) {
                        is Tile.Metric -> DataTileContent(tile.label, tile.value, tile.unit, tile.color, series = tile.series)
                        is Tile.Chart -> ChartTile(tile.label, tile.series, tile.progress, tile.accent)
                        is Tile.Toggle -> {
                            togglesVersion // read so a tap (which bumps it) recomposes this switch
                            SettingsToggleTile(
                                label = tile.label,
                                hint = tile.hint,
                                checked = prefs.toggleValue(tile.toggle),
                                onChange = { v ->
                                    onToggleSetting(tile.toggle, v)
                                    togglesVersion++
                                },
                            )
                        }
                    }
                }
            }
        }

        RecordBar(
            isRecording = data.metrics.isRecording,
            isPaused = data.isPaused,
            lapsActive = data.metrics.lapsActive,
            lapManagement = data.lapManagementEnabled,
            onStart = onStartRecording,
            onStop = onStopRecording,
            onPause = onPauseRecording,
            onResume = onResumeRecording,
            onLap = onLap,
            onEndLaps = onEndLaps,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

/** Series backing the per-tile mini-sparkline, or null for metrics without a recent history. */
private fun seriesFor(field: String, data: HudData): List<Float>? = when (field) {
    HudMetric.SPEED.name, HudMetric.AVG_SPEED.name, HudMetric.MAX_SPEED.name -> data.speedSeries
    HudMetric.HEART_RATE.name -> data.heartRateSeries
    HudMetric.CADENCE.name -> data.cadenceSeries
    HudMetric.POWER.name -> data.powerSeries
    HudMetric.ALTITUDE.name, HudMetric.ELEV_GAIN.name -> data.elevationProfile
    else -> null
}.takeIf { !it.isNullOrEmpty() }

private fun ViewerPreferences.toggleValue(t: DataToggle): Boolean = when (t) {
    DataToggle.AUTO_PAUSE -> recAutoPause
    DataToggle.TURN_VOICE -> turnVoice
    DataToggle.ADAPTIVE_ZOOM -> adaptiveZoom
    DataToggle.KEEP_SCREEN -> keepScreenOn
    DataToggle.BAROMETER -> recBarometer
}

/**
 * The one true Dades metric tile look, shared by the live view AND the editor so editing is WYSIWYG.
 * Optionally draws a mini-sparkline of [series] under the value.
 */
@Composable
fun DataTileContent(
    label: String,
    value: String,
    unit: String,
    colorHex: String?,
    modifier: Modifier = Modifier,
    series: List<Float>? = null,
) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colorHex?.let { hex ->
                        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
                    } ?: Color.Unspecified,
                )
                if (unit.isNotEmpty()) {
                    Text(" $unit", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            if (series != null && series.size >= 2) {
                val accent = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(24.dp).padding(top = 4.dp)) {
                    drawSeries(series, size.width, size.height, accent, baselineZero = false)
                }
            }
        }
    }
}

/** Sample series so the editor can preview graph tiles/sparklines (WYSIWYG). */
private val SAMPLE_SERIES = listOf(3f, 5f, 4f, 7f, 6f, 9f, 7f, 11f, 10f, 12f, 9f, 13f)

/** Editor preview of a graph tile with sample data (same look as the live [ChartTile]). */
@Composable
fun DataChartTilePreview(chart: DataChart, modifier: Modifier = Modifier) {
    val accent = if (chart == DataChart.SPEED) AccentSpeed else AccentElev
    ChartTile(
        label = androidx.compose.ui.res.stringResource(chart.labelRes),
        series = SAMPLE_SERIES,
        progress = if (chart == DataChart.ELEVATION) 0.6f else null,
        accent = accent,
        modifier = modifier,
    )
}

/** Sample sparkline for a metric tile preview in the editor. */
val DATA_SAMPLE_SPARKLINE: List<Float> = SAMPLE_SERIES

/** A graphical tile: title + a filled sparkline of a live series (with an optional progress marker). */
@Composable
private fun ChartTile(label: String, series: List<Float>, progress: Float?, accent: Color, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                if (series.size >= 2) {
                    drawSeries(series, size.width, size.height, accent, baselineZero = progress == null)
                    progress?.let { p ->
                        val x = p.coerceIn(0f, 1f) * size.width
                        drawLine(Color.White, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 2f)
                    }
                } else {
                    drawLine(accent.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), strokeWidth = 2f)
                }
            }
        }
    }
}

/** An interactive tile: a labelled switch that flips a setting live. */
@Composable
fun SettingsToggleTile(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

/** Fixed bottom record control: start when idle; pause/resume + stop + lap while recording. */
@Composable
private fun RecordBar(
    isRecording: Boolean,
    isPaused: Boolean,
    lapsActive: Boolean,
    lapManagement: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onLap: () -> Unit,
    onEndLaps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!isRecording) {
            RecordFab(RecordRed, onStart) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = androidx.compose.ui.res.stringResource(R.string.data_record_start), tint = Color.White, modifier = Modifier.size(30.dp))
            }
        } else {
            RecordFab(MaterialTheme.colorScheme.secondaryContainer, if (isPaused) onResume else onPause) {
                Icon(
                    if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = androidx.compose.ui.res.stringResource(if (isPaused) R.string.viewer_cd_resume else R.string.viewer_cd_pause),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            // Lap controls only while actively recording (not paused) and lap management is enabled.
            if (lapManagement && !isPaused) {
                RecordFab(MaterialTheme.colorScheme.primary, onLap) {
                    Icon(Icons.Filled.Flag, contentDescription = androidx.compose.ui.res.stringResource(R.string.data_record_lap), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
                }
                if (lapsActive) {
                    RecordFab(MaterialTheme.colorScheme.tertiaryContainer, onEndLaps) {
                        Icon(Icons.Filled.FlagCircle, contentDescription = androidx.compose.ui.res.stringResource(R.string.data_record_lap_end), tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(26.dp))
                    }
                }
            }
            RecordFab(RecordRed, onStop) {
                Icon(Icons.Filled.Stop, contentDescription = androidx.compose.ui.res.stringResource(R.string.rec_action_stop), tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun RecordFab(bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        shadowElevation = 6.dp,
        modifier = Modifier.size(60.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(CircleShape), contentAlignment = Alignment.Center) { content() }
    }
}
