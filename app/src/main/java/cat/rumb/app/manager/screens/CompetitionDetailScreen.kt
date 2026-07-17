package cat.rumb.app.manager.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cat.rumb.app.R
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.competition.CompetitionAnalysis
import cat.rumb.app.data.competition.GapSample
import cat.rumb.app.data.competition.TrackCurve
import cat.rumb.app.data.tracks.TrackStatsCalculator
import cat.rumb.app.data.tracks.nearestSampleAt
import cat.rumb.app.data.tracks.nearestSampleAtFraction
import cat.rumb.app.data.gpx.Gpx
import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.opentracks.model.GeoPoint
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.data.tracks.CompetitionAttemptEntity
import cat.rumb.app.data.tracks.CompetitionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ZoneColors = listOf(
    Color(0xFF4CAF50), Color(0xFF8BC34A), Color(0xFFFFC107), Color(0xFFFF9800), Color(0xFFF44336),
)

/** Line/badge colours by finishing position, so an attempt keeps its colour however you tick. */
private val AttemptPalette = listOf(
    "#3A86FF", "#E63946", "#2A9D8F", "#F4A261", "#9B5DE5", "#FFD166",
)

internal fun attemptColor(rank: Int): String =
    AttemptPalette[((rank - 1).coerceAtLeast(0)) % AttemptPalette.size]

/** Where a ticked attempt was at the scrubbed instant, and how far that is from the leader. */
private data class RaceMark(
    val attemptId: Long,
    val number: Int,
    val point: GeoPoint,
    /** + = further along the track than the leader right now. */
    val aheadM: Double,
    /** + = slower than the leader over the ground covered so far. */
    val gapSeconds: Double,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompetitionDetailScreen(competitionId: Long, onBack: () -> Unit, onStartCompetition: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    val scope = rememberCoroutineScope()
    val prefs = remember { ViewerPreferences.get(context) }
    val maxHr = remember { prefs.userMaxHr }
    val competitions by remember { app.competitionRepository.observeCompetitions() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val attempts by remember(competitionId) { app.competitionRepository.attemptsFor(competitionId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val competition = competitions.firstOrNull { it.id == competitionId }
    val name = competition?.name ?: ""
    val isLap = competition?.type == CompetitionType.LAP

    // Competitions created before the sport was stored have none: recover it on open, or the badge
    // (and the sport gate's usefulness) would stay invisible until the next attempt was filed.
    LaunchedEffect(competitionId) { app.competitionRepository.ensureActivityType(competitionId) }

    var pointsById by remember { mutableStateOf<Map<Long, List<GpxPoint>>>(emptyMap()) }
    LaunchedEffect(attempts) {
        val m = withContext(Dispatchers.Default) {
            attempts.associate { it.id to runCatching { Gpx.read(it.gpx.byteInputStream()).points }.getOrDefault(emptyList()) }
        }
        pointsById = m
    }

    // Map of the competition's reference: the whole route (ROUTE) or just the lap, meta-to-meta (LAP).
    val mapView = rememberMapViewWithLifecycle()
    var mapController by remember { mutableStateOf<RouteEditorController?>(null) }
    var refPoints by remember { mutableStateOf<List<GpxPoint>>(emptyList()) }
    var mapFramed by remember(competitionId) { mutableStateOf(false) }
    LaunchedEffect(competition?.referenceGpx) {
        val gpx = competition?.referenceGpx
        refPoints = if (gpx.isNullOrBlank()) emptyList() else withContext(Dispatchers.Default) {
            runCatching { Gpx.read(gpx.byteInputStream()).points }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(mapController, refPoints, competition?.lineLat, competition?.lineLng) {
        val c = mapController ?: return@LaunchedEffect
        if (refPoints.size >= 2) {
            c.setRoute(refPoints)
            if (!mapFramed) { c.frame(refPoints); mapFramed = true }
        }
        // LAP: mark the meta (start/finish line) on the lap.
        val lat = competition?.lineLat
        val lng = competition?.lineLng
        c.setWaypoints(if (isLap && lat != null && lng != null) listOf(GeoPoint(lat, lng)) else emptyList())
    }

    // Attempts ranked fastest-first, so an attempt's number and colour never move as you tick.
    val ranked = remember(attempts) { attempts.sortedBy { it.timeMs } }
    val rankOf = remember(ranked) { ranked.withIndex().associate { (i, a) -> a.id to i + 1 } }
    val samplesById = remember(pointsById) {
        pointsById.mapValues { (_, pts) -> TrackStatsCalculator.samples(pts) }
    }
    val curvesById = remember(pointsById) {
        pointsById.mapValues { (_, pts) -> TrackCurve.of(pts) }
    }
    val refSamples = remember(refPoints) { TrackStatsCalculator.samples(refPoints) }
    val refCurve = remember(refPoints) { TrackCurve.of(refPoints) }

    // Ticked attempts get their line drawn on the map; none by default, so the map opens as before.
    var checkedIds by remember(competitionId) { mutableStateOf(emptySet<Long>()) }
    var expandedId by remember(competitionId) { mutableStateOf<Long?>(null) }
    // Scrub position as a fraction of the REFERENCE's distance — one value shared by the anchored
    // strip, the gap chart and every expanded mini-chart, so dragging any of them moves the map.
    var scrub by remember(competitionId) { mutableStateOf<Float?>(null) }

    // The race as it looked at the scrubbed moment: the leader sits under the finger, and every
    // ticked rival is drawn where IT was at that same instant — that gap is the whole point, and
    // placing rivals at the same DISTANCE instead would stack them all on one spot.
    val race = remember(scrub, checkedIds, curvesById, samplesById, refCurve, ranked) {
        val f = scrub ?: return@remember emptyList()
        val lead = refCurve ?: return@remember emptyList()
        val d = f.coerceIn(0f, 1f) * lead.totalDist
        val t = lead.timeAt(d)
        ranked.filter { it.id in checkedIds }.mapNotNull { a ->
            val curve = curvesById[a.id] ?: return@mapNotNull null
            val samples = samplesById[a.id].orEmpty()
            val theirDist = curve.distanceAt(t)
            val sample = nearestSampleAt(samples, theirDist) ?: return@mapNotNull null
            RaceMark(
                attemptId = a.id,
                number = rankOf[a.id] ?: 0,
                point = GeoPoint(sample.lat, sample.lon),
                aheadM = theirDist - d,
                gapSeconds = curve.timeAt(d) - t,
            )
        }
    }

    LaunchedEffect(mapController, checkedIds, pointsById) {
        val c = mapController ?: return@LaunchedEffect
        c.setTracks(
            ranked.filter { it.id in checkedIds }.mapNotNull { a ->
                val pts = pointsById[a.id].orEmpty()
                if (pts.size < 2) null else MapTrack(pts, attemptColor(rankOf[a.id] ?: 0))
            },
        )
    }
    // Keyed on `scrub` as well as `race`: with nothing ticked every race is the same emptyList()
    // singleton, so keying on race alone would leave the reference's marker frozen mid-drag.
    LaunchedEffect(mapController, race, scrub) {
        val c = mapController ?: return@LaunchedEffect
        c.setLabels(race.map { MapLabel(it.point, it.number, attemptColor(it.number)) })
        // The reference's own position under the finger, when nothing is ticked to carry a badge.
        val f = scrub
        c.setHighlight(if (f != null && race.isEmpty()) nearestSampleAtFraction(refSamples, f)?.let { GeoPoint(it.lat, it.lon) } else null)
    }

    var menuOpen by remember { mutableStateOf(false) }
    var renameTo by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CompetitionAttemptEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Weighted + ellipsised: a long name used to squeeze the badge into a sliver
                        // and wrap it one letter per line ("Re/cor/rid/o").
                        Text(
                            name.ifBlank { stringResource(R.string.home_tab_competition) },
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TypeBadge(isLap)
                        // The sport was stored but never shown; it decides which attempts count.
                        competition?.activityType?.let { SportBadge(it) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { onStartCompetition(competitionId) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.circuit_start))
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.home_rename)) },
                            onClick = { menuOpen = false; renameTo = name },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.home_delete)) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        // Map + elevation strip stay put; only the lists below scroll. Reading an attempt used to
        // push the map off the top, which is the one thing you want to keep looking at.
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (refPoints.size >= 2) {
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    AndroidView(
                        factory = {
                            mapView.getMapAsync { map ->
                                val c = RouteEditorController(map)
                                mapController = c
                                c.init(source = MapSource.byId(prefs.statsMapSourceId)) { }
                            }
                            mapView
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (refSamples.size >= 2) {
                    // One lane: this strip is the drag surface anchored to the map, not a dashboard.
                    StackedTrackChart(
                        samples = refSamples,
                        highlightFraction = scrub,
                        onScrub = { scrub = it },
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        maxLanes = 1,
                    )
                }
                RaceRow(race, scrub != null)
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (attempts.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.circuit_empty_efforts))
                    }
                } else {
                    val best = attempts.minByOrNull { it.timeMs }
                    val bestMs = best?.timeMs
                    Text(
                        stringResource(if (isLap) R.string.circuit_efforts_count else R.string.competition_attempts_count, attempts.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ranked.forEach { a ->
                        AttemptRow(
                            rank = rankOf[a.id] ?: 0,
                            a = a,
                            bestMs = bestMs,
                            checked = a.id in checkedIds,
                            onChecked = { on -> checkedIds = if (on) checkedIds + a.id else checkedIds - a.id },
                            expanded = expandedId == a.id,
                            onExpand = { expandedId = if (expandedId == a.id) null else a.id },
                            samples = samplesById[a.id].orEmpty(),
                            scrub = scrub,
                            onScrub = { scrub = it },
                            onDelete = { pendingDelete = a },
                        )
                    }

                    if (best != null && attempts.size >= 2) {
                        GapCard(best, attempts.filter { it.id != best.id }, pointsById, scrub)
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.comp_evolution_title), style = MaterialTheme.typography.titleSmall)
                                val byDate = attempts.sortedBy { it.createdAt }
                                EvolutionBars(byDate.map { it.timeMs.toFloat() }, lowerBetter = true, Modifier.fillMaxWidth().height(120.dp))
                            }
                        }
                    }
                    HrZonesCard(attempts, pointsById, maxHr)
                }
            }
        }
    }

    renameTo?.let { current ->
        var text by remember(current) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { renameTo = null },
            title = { Text(stringResource(R.string.home_rename)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val n = text.trim()
                    if (n.isNotEmpty()) scope.launch { app.competitionRepository.rename(competitionId, n) }
                    renameTo = null
                }) { Text(stringResource(R.string.home_save)) }
            },
            dismissButton = { TextButton(onClick = { renameTo = null }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.home_delete)) },
            text = { Text(stringResource(R.string.home_delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { app.competitionRepository.delete(competitionId); onBack() }
                }) { Text(stringResource(R.string.home_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
    pendingDelete?.let { lap ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(fmtTime(lap.timeMs)) },
            text = { Text(stringResource(R.string.competition_delete_lap_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { app.competitionRepository.deleteAttempt(competitionId, lap.id) }
                }) { Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
}

/**
 * The scrubbed moment in numbers: who was where when the leader reached the finger. Metres tell you
 * the gap you can see on the map; seconds tell you what it cost over the ground covered so far.
 */
@Composable
private fun RaceRow(marks: List<RaceMark>, scrubbing: Boolean) {
    Box(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (marks.isEmpty()) {
            Text(
                stringResource(if (scrubbing) R.string.competition_race_row_tick else R.string.competition_race_row_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                marks.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier.size(14.dp).clip(RoundedCornerShape(7.dp))
                                .background(Color(android.graphics.Color.parseColor(attemptColor(m.number)))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${m.number}", style = MaterialTheme.typography.labelSmall, color = Color.White, fontSize = 8.sp)
                        }
                        Text(
                            "%+.0f m · %+.1f s".format(m.aheadM, m.gapSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The competition's sport: only same-family efforts are filed as attempts. */
@Composable
private fun SportBadge(activityTypeId: String) {
    val context = LocalContext.current
    val prefs = remember { ViewerPreferences.get(context) }
    val custom = remember(prefs.customActivityTypesJson) {
        cat.rumb.app.data.tracks.ActivityTypes.decodeCustom(prefs.customActivityTypesJson)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            activityTypeIcon(activityTypeId, custom),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            activityTypeLabel(activityTypeId, custom),
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TypeBadge(isLap: Boolean) {
    Text(
        stringResource(if (isLap) R.string.competition_type_lap else R.string.competition_type_route),
        maxLines = 1,
        softWrap = false,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun GapCard(
    best: CompetitionAttemptEntity,
    others: List<CompetitionAttemptEntity>,
    pointsById: Map<Long, List<GpxPoint>>,
    scrub: Float?,
) {
    if (others.isEmpty()) return
    var selectedId by remember(others) { mutableStateOf(others.minByOrNull { it.timeMs }?.id ?: others.first().id) }
    var series by remember { mutableStateOf<List<GapSample>>(emptyList()) }
    LaunchedEffect(selectedId, pointsById) {
        val bestPts = pointsById[best.id].orEmpty()
        val selPts = pointsById[selectedId].orEmpty()
        series = withContext(Dispatchers.Default) { CompetitionAnalysis.gapOverDistance(bestPts, selPts) }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.competition_gap_chart_title), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                others.forEach { a ->
                    FilterChip(selected = selectedId == a.id, onClick = { selectedId = a.id }, label = { Text(fmtTime(a.timeMs)) })
                }
            }
            // Same scrub as the strip above, so "here on the map" and "here on the curve" agree.
            GapChart(series, Modifier.fillMaxWidth().height(160.dp), scrub)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(GapGreenSolid, stringResource(R.string.competition_gap_ahead))
                LegendDot(GapRedSolid, stringResource(R.string.competition_gap_behind))
            }
        }
    }
}

@Composable
private fun HrZonesCard(attempts: List<CompetitionAttemptEntity>, pointsById: Map<Long, List<GpxPoint>>, maxHr: Int) {
    val zonesById = remember(pointsById, maxHr) {
        attempts.associate { it.id to CompetitionAnalysis.hrZones(pointsById[it.id].orEmpty(), maxHr) }
    }
    if (zonesById.values.none { it.sum() > 0L }) return
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.competition_hr_zones_title), style = MaterialTheme.typography.titleSmall)
            attempts.forEach { a ->
                val zones = zonesById[a.id]
                if (zones != null && zones.sum() > 0L) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(fmtTime(a.timeMs), style = MaterialTheme.typography.labelSmall, modifier = Modifier.size(width = 56.dp, height = 16.dp))
                        Row(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(4.dp))) {
                            zones.forEachIndexed { i, ms ->
                                if (ms > 0L) Box(Modifier.weight(ms.toFloat()).fillMaxSize().background(ZoneColors[i]))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One attempt: tick it onto the map, tap it open for its own charts. The checkbox wears the colour
 * its line and badge take, which is the only thing tying a row to a line on the map.
 */
@Composable
private fun AttemptRow(
    rank: Int,
    a: CompetitionAttemptEntity,
    bestMs: Long?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    expanded: Boolean,
    onExpand: () -> Unit,
    samples: List<cat.rumb.app.data.tracks.TrackSample>,
    scrub: Float?,
    onScrub: (Float?) -> Unit,
    onDelete: () -> Unit,
) {
    val isBest = bestMs != null && a.timeMs == bestMs
    val color = Color(android.graphics.Color.parseColor(attemptColor(rank)))
    Card {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(start = 4.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = onChecked,
                    colors = CheckboxDefaults.colors(checkedColor = color),
                    modifier = Modifier.size(36.dp),
                )
                if (isBest) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                } else {
                    Text("$rank", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        fmtTime(a.timeMs),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                        color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        fmtDate(a.createdAt) + " · " + "%.2f km".format(a.distanceM / 1000.0) +
                            (a.avgHr?.let { " · %d bpm".format(it.toInt()) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val gap = if (bestMs != null) a.timeMs - bestMs else 0L
                Text(
                    if (isBest) stringResource(R.string.home_laps_best) else "+" + fmtTime(gap),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.competition_attempt_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.home_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (expanded && samples.size >= 2) {
                // Lanes with no data hide themselves, which is how "HR if there is any" is handled.
                StackedTrackChart(
                    samples = samples,
                    highlightFraction = scrub,
                    onScrub = onScrub,
                    modifier = Modifier.fillMaxWidth().height(180.dp).padding(bottom = 8.dp),
                )
            }
        }
    }
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
private fun fmtDate(ms: Long): String =
    if (ms <= 0) "" else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFmt)
