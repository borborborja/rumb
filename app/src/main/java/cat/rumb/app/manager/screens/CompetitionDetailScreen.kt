package cat.rumb.app.manager.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cat.rumb.app.R
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.competition.CompetitionAnalysis
import cat.rumb.app.data.competition.GapSample
import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.data.tracks.ActivityTypes
import cat.rumb.app.data.tracks.FollowTrackEntity
import cat.rumb.app.data.tracks.TrackStats
import cat.rumb.app.data.tracks.TrackStatsCalculator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ZoneColors = listOf(
    Color(0xFF3A86FF), // Z1
    Color(0xFF2A9D8F), // Z2
    Color(0xFFF4D35E), // Z3
    Color(0xFFF4A261), // Z4
    Color(0xFFE63946), // Z5
)
private val ZoneRanges = listOf("<60%", "60–70%", "70–80%", "80–90%", "≥90%")

/** Analysis screen for a competition reference track: attempts table, gap-vs-best chart, HR zones. */
@Composable
fun CompetitionDetailScreen(refId: Long, onBack: () -> Unit, onStartCompetition: (Long) -> Unit) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    val prefs = remember { ViewerPreferences.get(context) }
    val custom = remember(prefs.customActivityTypesJson) { ActivityTypes.decodeCustom(prefs.customActivityTypesJson) }

    var ref by remember { mutableStateOf<FollowTrackEntity?>(null) }
    var candidates by remember { mutableStateOf<List<FollowTrackEntity>>(emptyList()) }
    var pointsById by remember { mutableStateOf<Map<Long, List<GpxPoint>>>(emptyMap()) }
    var statsById by remember { mutableStateOf<Map<Long, TrackStats>>(emptyMap()) }
    var zonesById by remember { mutableStateOf<Map<Long, LongArray>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    var showRename by remember { mutableStateOf(false) }
    var showType by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(refId, reloadTick) {
        val all = app.trackRepository.observeSummaries().first()
        val refEntity = all.firstOrNull { it.id == refId } ?: app.trackRepository.get(refId)
        val attempts = all.filter { it.competitionRefId == refId && it.id != refId }
        val list = listOfNotNull(refEntity) + attempts
        ref = refEntity
        candidates = list
        val pts = HashMap<Long, List<GpxPoint>>(list.size)
        val stats = HashMap<Long, TrackStats>(list.size)
        val zones = HashMap<Long, LongArray>(list.size)
        val maxHr = prefs.userMaxHr
        for (c in list) {
            val p = app.trackRepository.loadGpxRoute(c.id)
            withContext(Dispatchers.Default) {
                pts[c.id] = p
                stats[c.id] = TrackStatsCalculator.compute(p)
                zones[c.id] = CompetitionAnalysis.hrZones(p, maxHr)
            }
        }
        pointsById = pts
        statsById = stats
        zonesById = zones
        loaded = true
    }

    // Best = shortest strictly positive duration (0 = untimed sentinel, null = pending).
    val best = remember(candidates) {
        candidates.filter { (it.durationMs ?: 0L) > 0L }.minByOrNull { it.durationMs!! }
    }

    DetailScaffold(
        title = ref?.name ?: stringResource(R.string.competition_title),
        onBack = onBack,
        actions = {
            if (ref?.competitionArchived != true) {
                IconButton(onClick = { onStartCompetition(refId) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.competition_play_cd))
                }
            }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = stringResource(R.string.competition_cd_menu))
                }
                androidx.compose.material3.DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_rename)) },
                        onClick = { showMenu = false; showRename = true },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.routes_assign_type_title)) },
                        onClick = { showMenu = false; showType = true },
                    )
                }
            }
        },
    ) { modifier ->
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ref?.let { e ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        activityTypeIcon(e.activityType, custom),
                        contentDescription = activityTypeLabel(e.activityType, custom),
                        modifier = Modifier.size(32.dp),
                    )
                    Column {
                        Text(e.name, style = MaterialTheme.typography.titleMedium)
                        val parts = buildList {
                            e.municipality?.takeIf { it.isNotBlank() }?.let(::add)
                            add(formatDayMonthYear(e.createdAt))
                        }
                        Text(
                            parts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (loaded && candidates.isNotEmpty()) {
                AttemptsTable(candidates, best, statsById)

                EvolutionCard(candidates, statsById)

                val withTimes = candidates.filter { c ->
                    pointsById[c.id].orEmpty().count { it.time != null } >= 2
                }
                if (best != null && withTimes.size >= 2) {
                    GapChartCard(best = best, others = withTimes.filter { it.id != best.id }, pointsById = pointsById)
                }

                HrZonesCard(candidates, zonesById)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showRename) {
        TextDialog(
            title = stringResource(R.string.home_rename),
            initial = ref?.name ?: "",
            confirm = stringResource(R.string.home_save),
            onDismiss = { showRename = false },
        ) { name ->
            scope.launch { app.trackRepository.rename(refId, name); reloadTick++ }
            showRename = false
        }
    }
    if (showType) {
        val typeOptions = rememberActivityTypeOptions(prefs)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showType = false },
            title = { Text(stringResource(R.string.routes_assign_type_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    typeOptions.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.trackRepository.setActivityType(refId, option.id); reloadTick++ }
                                    showType = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                option.icon,
                                contentDescription = null,
                                tint = if (ref?.activityType == option.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                            Text("  " + option.label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showType = false }) { Text(stringResource(R.string.home_cancel)) }
            },
        )
    }
}

// --- Attempts table ---

@Composable
private fun AttemptsTable(candidates: List<FollowTrackEntity>, best: FollowTrackEntity?, statsById: Map<Long, TrackStats>) {
    // durationMs asc, untimed (null or 0) last.
    val sorted = remember(candidates) {
        candidates.sortedWith(compareBy(nullsLast()) { it.durationMs?.takeIf { d -> d > 0L } })
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                HeaderCell(stringResource(R.string.competition_col_date), 1.3f)
                HeaderCell(stringResource(R.string.competition_col_time), 1f)
                HeaderCell(stringResource(R.string.competition_col_speed), 0.9f)
                HeaderCell(stringResource(R.string.competition_col_hr), 0.7f)
                HeaderCell(stringResource(R.string.competition_col_diff), 0.9f)
            }
            HorizontalDivider()
            sorted.forEach { c ->
                val s = statsById[c.id]
                val isBest = best != null && c.id == best.id
                val dur = c.durationMs?.takeIf { it > 0L }
                val diff = if (isBest || dur == null || best?.durationMs == null) null else dur - best.durationMs!!
                val color = if (isBest) MaterialTheme.colorScheme.primary else Color.Unspecified
                val weight = if (isBest) FontWeight.Bold else null
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1.3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            formatDayMonthYearShort(c.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                            fontWeight = weight,
                        )
                        if (isBest) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = stringResource(R.string.competition_best_badge),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    BodyCell(dur?.let(::formatHms) ?: "—", 1f, color, weight)
                    BodyCell(s?.avgSpeedKmh?.let { String.format("%.1f", it) } ?: "—", 0.9f, color, weight)
                    BodyCell(s?.avgHr?.let { "${it.toInt()}" } ?: "—", 0.7f, color, weight)
                    BodyCell(if (isBest) "—" else diff?.let(::formatDiff) ?: "—", 0.9f, color, weight)
                }
            }
            HorizontalDivider()
            val timedDurations = sorted.mapNotNull { it.durationMs?.takeIf { d -> d > 0L } }
            val speeds = sorted.mapNotNull { statsById[it.id]?.avgSpeedKmh }
            val hrs = sorted.mapNotNull { statsById[it.id]?.avgHr }
            Row(Modifier.fillMaxWidth()) {
                BodyCell(stringResource(R.string.competition_row_average), 1.3f, fontWeight = FontWeight.Medium)
                BodyCell(
                    timedDurations.takeIf { it.isNotEmpty() }?.let { formatHms(it.average().toLong()) } ?: "—",
                    1f,
                )
                BodyCell(speeds.takeIf { it.isNotEmpty() }?.let { String.format("%.1f", it.average()) } ?: "—", 0.9f)
                BodyCell(hrs.takeIf { it.isNotEmpty() }?.let { "${it.average().toInt()}" } ?: "—", 0.7f)
                BodyCell("", 0.9f)
            }
        }
    }
}

// --- Gap chart ---

@Composable
private fun GapChartCard(
    best: FollowTrackEntity,
    others: List<FollowTrackEntity>,
    pointsById: Map<Long, List<GpxPoint>>,
) {
    if (others.isEmpty()) return
    var selectedId by remember(others) {
        mutableStateOf(others.maxByOrNull { it.createdAt }?.id ?: others.first().id)
    }
    var series by remember { mutableStateOf<List<GapSample>>(emptyList()) }
    LaunchedEffect(selectedId, pointsById) {
        val bestPts = pointsById[best.id].orEmpty()
        val selPts = pointsById[selectedId].orEmpty()
        series = withContext(Dispatchers.Default) { CompetitionAnalysis.gapOverDistance(bestPts, selPts) }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.competition_gap_chart_title), style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                others.forEach { c ->
                    FilterChip(
                        selected = selectedId == c.id,
                        onClick = { selectedId = c.id },
                        label = { Text(formatDayMonthYearShort(c.createdAt)) },
                    )
                }
            }
            GapChart(series, Modifier.fillMaxWidth().height(180.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(GapGreenSolid, stringResource(R.string.competition_gap_ahead))
                LegendDot(GapRedSolid, stringResource(R.string.competition_gap_behind))
            }
        }
    }
}

// --- Evolution (trend across attempts by date) ---

private enum class EvoMetric { TIME, SPEED, HR }

@Composable
private fun EvolutionCard(candidates: List<FollowTrackEntity>, statsById: Map<Long, TrackStats>) {
    var metric by remember { mutableStateOf(EvoMetric.TIME) }
    val series = remember(candidates, statsById, metric) {
        candidates.sortedBy { it.createdAt }.mapNotNull { c ->
            val v = when (metric) {
                EvoMetric.TIME -> c.durationMs?.takeIf { it > 0L }?.toDouble()
                EvoMetric.SPEED -> statsById[c.id]?.avgSpeedKmh
                EvoMetric.HR -> statsById[c.id]?.avgHr
            }
            v?.let { c to it }
        }
    }
    if (series.size < 2) return
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.comp_evolution_title), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EvoMetric.entries.forEach { m ->
                    FilterChip(selected = metric == m, onClick = { metric = m }, label = { Text(stringResource(evoMetricLabel(m))) })
                }
            }
            // Lower time is better; higher speed is better; HR is informational (treat as neutral/higher).
            EvolutionBars(series.map { it.second.toFloat() }, lowerBetter = metric == EvoMetric.TIME, Modifier.fillMaxWidth().height(140.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDayMonthYearShort(series.first().first.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDayMonthYearShort(series.last().first.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun evoMetricLabel(m: EvoMetric): Int = when (m) {
    EvoMetric.TIME -> R.string.comp_metric_time
    EvoMetric.SPEED -> R.string.comp_metric_speed
    EvoMetric.HR -> R.string.comp_metric_hr
}

// --- HR zones ---

@Composable
private fun HrZonesCard(candidates: List<FollowTrackEntity>, zonesById: Map<Long, LongArray>) {
    val withHr = candidates.filter { (zonesById[it.id]?.sum() ?: 0L) > 0L }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.competition_hr_zones_title), style = MaterialTheme.typography.titleSmall)
            if (withHr.isEmpty()) {
                Text(
                    stringResource(R.string.competition_hr_no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                candidates.forEach { c ->
                    val zones = zonesById[c.id]
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            formatDayMonthYearShort(c.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(0.28f),
                        )
                        if (zones != null && zones.sum() > 0L) {
                            Row(Modifier.weight(0.72f).height(14.dp).clip(RoundedCornerShape(4.dp))) {
                                zones.forEachIndexed { i, ms ->
                                    if (ms > 0L) {
                                        Box(
                                            Modifier
                                                .weight(ms.toFloat().coerceAtLeast(1f))
                                                .fillMaxSize()
                                                .background(ZoneColors[i]),
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(0.72f),
                            )
                        }
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZoneColors.forEachIndexed { i, color ->
                        LegendDot(color, stringResource(R.string.competition_zone_fmt, i + 1) + " " + ZoneRanges[i])
                    }
                }
            }
        }
    }
}

// --- Formatting helpers ---

private fun formatDayMonthYear(epochMs: Long): String =
    DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
