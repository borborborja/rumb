package cat.rumb.app.manager.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cat.rumb.app.R
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.competition.CompetitionAnalysis
import cat.rumb.app.data.competition.GapSample
import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.data.tracks.FollowTrackEntity
import cat.rumb.app.data.tracks.LapKind
import cat.rumb.app.data.tracks.LapRange
import cat.rumb.app.data.tracks.Laps
import cat.rumb.app.data.tracks.TrackKind
import cat.rumb.app.data.tracks.TrackStats
import cat.rumb.app.data.tracks.TrackStatsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private enum class CompareMode { LAPS, ATTEMPTS, OTHER }

/** One comparable entity: a lap slice, a sibling attempt, or another track. */
private data class CompareUnit(
    val key: String,
    val label: String,
    val points: List<GpxPoint>,
    val stats: TrackStats,
    val durationMs: Long?,
)

/**
 * Compares a track against homogeneous units: its own laps, its sibling competition attempts, or
 * another library track. Reuses the competition gap-over-distance chart + attempts-table cells.
 */
@Composable
fun CompareScreen(trackId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }

    var entity by remember { mutableStateOf<FollowTrackEntity?>(null) }
    var basePoints by remember { mutableStateOf<List<GpxPoint>>(emptyList()) }
    var laps by remember { mutableStateOf<List<LapRange>>(emptyList()) }
    var siblings by remember { mutableStateOf<List<FollowTrackEntity>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<FollowTrackEntity>>(emptyList()) }
    var pickedId by remember { mutableStateOf<Long?>(null) }
    var mode by remember { mutableStateOf<CompareMode?>(null) }
    var units by remember { mutableStateOf<List<CompareUnit>>(emptyList()) }
    var baselineKey by remember { mutableStateOf<String?>(null) }
    var otherKey by remember { mutableStateOf<String?>(null) }
    var series by remember { mutableStateOf<List<GapSample>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(trackId) {
        val e = app.trackRepository.get(trackId)
        entity = e
        basePoints = app.trackRepository.loadGpxRoute(trackId)
        laps = Laps.decode(e?.laps)
        val all = app.trackRepository.observeSummaries().first()
        val refId = e?.competitionRefId
        if (refId != null) siblings = all.filter { it.competitionRefId == refId || it.id == refId }
        candidates = all.filter { it.kind == TrackKind.TRAINING && it.id != trackId }
        mode = when {
            laps.count { it.kind == LapKind.LAP } >= 2 -> CompareMode.LAPS
            refId != null -> CompareMode.ATTEMPTS
            else -> CompareMode.OTHER
        }
    }

    // Build the comparable units for the current mode.
    LaunchedEffect(mode, basePoints, siblings, pickedId) {
        val m = mode ?: return@LaunchedEffect
        val list: List<CompareUnit> = withContext(Dispatchers.Default) {
            when (m) {
                CompareMode.LAPS -> laps.filter { it.kind == LapKind.LAP }.map { lap ->
                    val slice = basePoints.subList(lap.startIdx.coerceIn(0, basePoints.size), lap.endIdx.coerceIn(0, basePoints.size))
                    unit("lap${lap.index}", context.getString(R.string.training_lap_n, lap.index), slice, sliceDurationMs(slice))
                }
                CompareMode.ATTEMPTS -> siblings.map { s ->
                    val p = app.trackRepository.loadGpxRoute(s.id)
                    unit("att${s.id}", formatDayMonthYearShort(s.createdAt), p, s.durationMs?.takeIf { it > 0L } ?: sliceDurationMs(p))
                }
                CompareMode.OTHER -> {
                    val base = unit("base", entity?.name ?: "", basePoints, entity?.durationMs?.takeIf { it > 0L } ?: sliceDurationMs(basePoints))
                    val picked = pickedId?.let { pid ->
                        val pe = candidates.firstOrNull { it.id == pid }
                        val pp = app.trackRepository.loadGpxRoute(pid)
                        unit("pick$pid", pe?.name ?: "", pp, pe?.durationMs?.takeIf { it > 0L } ?: sliceDurationMs(pp))
                    }
                    listOfNotNull(base, picked)
                }
            }
        }
        units = list
        val timed = list.filter { (it.durationMs ?: 0L) > 0L }
        baselineKey = (timed.minByOrNull { it.durationMs!! } ?: list.firstOrNull())?.key
        otherKey = list.map { it.key }.firstOrNull { it != baselineKey }
    }

    // Recompute the gap curve for the selected baseline/other pair.
    LaunchedEffect(baselineKey, otherKey, units) {
        val base = units.firstOrNull { it.key == baselineKey }
        val other = units.firstOrNull { it.key == otherKey }
        series = if (base != null && other != null) {
            withContext(Dispatchers.Default) { CompetitionAnalysis.gapOverDistance(base.points, other.points) }
        } else {
            emptyList()
        }
    }

    val availableModes = buildList {
        if (laps.count { it.kind == LapKind.LAP } >= 2) add(CompareMode.LAPS)
        if (entity?.competitionRefId != null) add(CompareMode.ATTEMPTS)
        add(CompareMode.OTHER)
    }

    DetailScaffold(title = stringResource(R.string.compare_title), onBack = onBack) { modifier ->
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            // Mode selector (only applicable modes).
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                availableModes.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(stringResource(modeLabel(m))) },
                    )
                }
            }

            if (mode == CompareMode.OTHER) {
                TextButton(onClick = { showPicker = true }) {
                    Text(
                        pickedId?.let { pid -> candidates.firstOrNull { it.id == pid }?.name } ?: stringResource(R.string.compare_pick_track),
                    )
                }
            }

            val base = units.firstOrNull { it.key == baselineKey }
            if (units.size < 2 || base == null) {
                Text(
                    stringResource(R.string.compare_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                UnitsTable(units, baselineKey)
                if (mode == CompareMode.LAPS) {
                    val lapTimes = units.mapNotNull { it.durationMs?.toFloat() }
                    if (lapTimes.size >= 2) {
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.comp_evolution_title), style = MaterialTheme.typography.titleSmall)
                                EvolutionBars(lapTimes, lowerBetter = true, Modifier.fillMaxWidth().height(120.dp))
                            }
                        }
                    }
                }
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.competition_gap_chart_title), style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            units.filter { it.key != baselineKey }.forEach { u ->
                                FilterChip(
                                    selected = otherKey == u.key,
                                    onClick = { otherKey = u.key },
                                    label = { Text(u.label) },
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
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showPicker) {
        val shown = if (showAll) candidates else candidates.filter { it.activityType == entity?.activityType }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.compare_pick_track)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(stringResource(if (showAll) R.string.compare_same_type else R.string.compare_show_all))
                    }
                    shown.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable { pickedId = t.id; showPicker = false }.padding(vertical = 8.dp),
                        ) {
                            Text(t.name)
                        }
                    }
                    if (shown.isEmpty()) {
                        Text(stringResource(R.string.compare_no_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
}

@Composable
private fun UnitsTable(units: List<CompareUnit>, baselineKey: String?) {
    val baseDur = units.firstOrNull { it.key == baselineKey }?.durationMs
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                HeaderCell(stringResource(R.string.compare_col_unit), 1.4f)
                HeaderCell(stringResource(R.string.competition_col_time), 1f)
                HeaderCell(stringResource(R.string.compare_col_dist), 0.9f)
                HeaderCell(stringResource(R.string.competition_col_speed), 0.9f)
                HeaderCell(stringResource(R.string.competition_col_diff), 0.9f)
            }
            HorizontalDivider()
            units.forEach { u ->
                val isBase = u.key == baselineKey
                val color = if (isBase) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified
                val fw = if (isBase) FontWeight.Bold else null
                val diff = if (isBase || u.durationMs == null || baseDur == null) null else u.durationMs - baseDur
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BodyCell(u.label, 1.4f, color, fw)
                    BodyCell(u.durationMs?.let(::formatHms) ?: "—", 1f, color, fw)
                    BodyCell(String.format("%.2f", u.stats.distanceM / 1000.0), 0.9f, color, fw)
                    BodyCell(u.stats.avgSpeedKmh?.let { String.format("%.1f", it) } ?: "—", 0.9f, color, fw)
                    BodyCell(if (isBase) "—" else diff?.let(::formatDiff) ?: "—", 0.9f, color, fw)
                }
            }
        }
    }
}

private fun modeLabel(m: CompareMode): Int = when (m) {
    CompareMode.LAPS -> R.string.compare_mode_laps
    CompareMode.ATTEMPTS -> R.string.compare_mode_attempts
    CompareMode.OTHER -> R.string.compare_mode_other
}

private suspend fun unit(key: String, label: String, points: List<GpxPoint>, durationMs: Long?): CompareUnit =
    CompareUnit(key, label, points, TrackStatsCalculator.compute(points), durationMs)

private fun sliceDurationMs(p: List<GpxPoint>): Long? {
    val a = p.firstOrNull()?.time
    val b = p.lastOrNull()?.time
    return if (a != null && b != null) java.time.Duration.between(a, b).toMillis().takeIf { it > 0 } else null
}
