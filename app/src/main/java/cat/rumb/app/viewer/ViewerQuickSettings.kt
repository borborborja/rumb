package cat.rumb.app.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.rumb.app.R
import cat.rumb.app.data.map.MapSource
import cat.rumb.app.data.map.OfflineMap
import cat.rumb.app.data.tracks.CompetitionEntity
import cat.rumb.app.data.tracks.CompetitionType
import cat.rumb.app.data.tracks.FollowTrackEntity

private val TABS = listOf(
    R.string.viewer_qs_tab_map,
    R.string.viewer_qs_tab_route_competition,
    R.string.viewer_qs_tab_options,
)

/** Bottom sheet with quick, live viewer settings, opened from the switcher's gear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerQuickSettings(
    currentBaseMapId: String?,
    offlineMaps: List<OfflineMap>,
    currentFollowId: Long,
    tracks: List<FollowTrackEntity>,
    competitions: List<CompetitionEntity> = emptyList(),
    currentCompetitionId: Long = -1L,
    onStartCompetition: (Long) -> Unit = {},
    orientation: String,
    keepScreenOn: Boolean,
    fullscreen: Boolean,
    adaptiveZoom: Boolean,
    onSelectBaseMap: (String) -> Unit,
    onSelectFollow: (Long) -> Unit,
    onOrientation: (String) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    countdown: Boolean = false,
    onCountdown: (Boolean) -> Unit = {},
    autoPause: Boolean = false,
    onAutoPause: (Boolean) -> Unit = {},
    autoPauseSec: Int = 5,
    onAutoPauseSec: (Int) -> Unit = {},
    onAdaptiveZoom: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    halo: Boolean = true,
    onHalo: (Boolean) -> Unit = {},
    showSeconds: Boolean = true,
    onShowSeconds: (Boolean) -> Unit = {},
    turnVoice: Boolean = true,
    onTurnVoice: (Boolean) -> Unit = {},
    lapCountdown: Boolean = false,
    onLapCountdown: (Boolean) -> Unit = {},
    ghostEnabled: Boolean = true,
    onGhostEnabled: (Boolean) -> Unit = {},
    lapManagement: Boolean = true,
    onLapManagement: (Boolean) -> Unit = {},
    autoLapByPosition: Boolean = false,
    onAutoLapByPosition: (Boolean) -> Unit = {},
    autoDetectLoop: Boolean = false,
    onAutoDetectLoop: (Boolean) -> Unit = {},
    autoLapEveryM: Float = 0f,
    onAutoLapEveryM: (Float) -> Unit = {},
    sportLabel: String? = null,
    sportLocked: Boolean = false,
    onChangeSport: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) }
    var selBase by remember { mutableStateOf(currentBaseMapId) }
    var selFollow by remember { mutableLongStateOf(currentFollowId) }
    var orient by remember { mutableStateOf(orientation) }
    var keep by remember { mutableStateOf(keepScreenOn) }
    var full by remember { mutableStateOf(fullscreen) }
    var cd by remember { mutableStateOf(countdown) }
    var ap by remember { mutableStateOf(autoPause) }
    var apSec by remember { mutableStateOf(autoPauseSec) }
    var autoZoom by remember { mutableStateOf(adaptiveZoom) }
    // Hoisted like every other toggle on this sheet: state inside a tab dies when you switch away,
    // and the parameters were read once when the sheet opened — so a chip you had just changed would
    // come back showing its old value while the pref underneath was right.
    var lapMgmt by remember { mutableStateOf(lapManagement) }
    var autoLap by remember { mutableStateOf(autoLapByPosition) }
    var detectLoop by remember { mutableStateOf(autoDetectLoop) }
    var lapEveryM by remember { mutableStateOf(autoLapEveryM) }
    var lapCd by remember { mutableStateOf(lapCountdown) }
    var ghost by remember { mutableStateOf(ghostEnabled) }
    var haloOn by remember { mutableStateOf(halo) }
    var secsOn by remember { mutableStateOf(showSeconds) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(title)) })
            }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (tab) {
                0 -> MapTab(selBase, offlineMaps) { id -> selBase = id; onSelectBaseMap(id) }
                // Named, not positional: this is a wall of adjacent Boolean/lambda pairs, exactly the
                // shape where one reordered parameter swaps two settings without a compiler peep.
                1 -> FollowTab(
                    current = selFollow,
                    tracks = tracks,
                    competitions = competitions,
                    currentCompetitionId = currentCompetitionId,
                    turnVoice = turnVoice,
                    onTurnVoice = onTurnVoice,
                    lapCountdown = lapCd,
                    onLapCountdown = { lapCd = it; onLapCountdown(it) },
                    ghostEnabled = ghost,
                    onGhostEnabled = { ghost = it; onGhostEnabled(it) },
                    lapManagement = lapMgmt,
                    onLapManagement = { lapMgmt = it; onLapManagement(it) },
                    autoLapByPosition = autoLap,
                    onAutoLapByPosition = { autoLap = it; onAutoLapByPosition(it) },
                    autoDetectLoop = detectLoop,
                    onAutoDetectLoop = { detectLoop = it; onAutoDetectLoop(it) },
                    autoLapEveryM = lapEveryM,
                    onAutoLapEveryM = { lapEveryM = it; onAutoLapEveryM(it) },
                    halo = haloOn,
                    onHalo = { haloOn = it; onHalo(it) },
                    showSeconds = secsOn,
                    onShowSeconds = { secsOn = it; onShowSeconds(it) },
                    onStartCompetition = onStartCompetition,
                ) { id -> selFollow = id; onSelectFollow(id) }
                2 -> OptionsTab(
                    orient, keep, full, autoZoom,
                    onOrientation = { orient = it; onOrientation(it) },
                    onKeepScreenOn = { keep = it; onKeepScreenOn(it) },
                    onFullscreen = { full = it; onFullscreen(it) },
                    countdown = cd,
                    onCountdown = { cd = it; onCountdown(it) },
                    autoPause = ap,
                    onAutoPause = { ap = it; onAutoPause(it) },
                    autoPauseSec = apSec,
                    onAutoPauseSec = { apSec = it; onAutoPauseSec(it) },
                    onAdaptiveZoom = { autoZoom = it; onAdaptiveZoom(it) },
                    sportLabel = sportLabel,
                    sportLocked = sportLocked,
                    onChangeSport = onChangeSport,
                )
            }
        }
    }
}

@Composable
private fun MapTab(current: String?, offlineMaps: List<OfflineMap>, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.viewer_qs_online_map), style = MaterialTheme.typography.labelLarge)
    MapSource.entries.filter { it.isSelectable }.forEach { source ->
        RadioRow(source.displayName, source.attribution, current == source.id) { onSelect(source.id) }
    }
    if (offlineMaps.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(stringResource(R.string.viewer_qs_offline_map), style = MaterialTheme.typography.labelLarge)
        offlineMaps.forEach { map ->
            RadioRow(map.name, stringResource(R.string.viewer_qs_offline_subtitle), current == map.selectionId) { onSelect(map.selectionId) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowTab(
    current: Long,
    tracks: List<FollowTrackEntity>,
    competitions: List<CompetitionEntity>,
    currentCompetitionId: Long = -1L,
    turnVoice: Boolean = true,
    onTurnVoice: (Boolean) -> Unit = {},
    lapCountdown: Boolean = false,
    onLapCountdown: (Boolean) -> Unit = {},
    ghostEnabled: Boolean = true,
    onGhostEnabled: (Boolean) -> Unit = {},
    lapManagement: Boolean = true,
    onLapManagement: (Boolean) -> Unit = {},
    autoLapByPosition: Boolean = false,
    onAutoLapByPosition: (Boolean) -> Unit = {},
    autoDetectLoop: Boolean = false,
    onAutoDetectLoop: (Boolean) -> Unit = {},
    autoLapEveryM: Float = 0f,
    onAutoLapEveryM: (Float) -> Unit = {},
    halo: Boolean = true,
    onHalo: (Boolean) -> Unit = {},
    showSeconds: Boolean = true,
    onShowSeconds: (Boolean) -> Unit = {},
    onStartCompetition: (Long) -> Unit = {},
    onSelect: (Long) -> Unit,
) {
    // "Ninguna" stays on top as the follow baseline; a mode switch below lists either catalogue
    // routes to follow, or competitions to race — so a long track list is never a single flat wall.
    Text(stringResource(R.string.viewer_qs_route_to_follow), style = MaterialTheme.typography.labelLarge)
    RadioRow(
        stringResource(R.string.viewer_qs_route_none),
        stringResource(R.string.viewer_qs_route_none_subtitle),
        current <= 0L,
    ) { onSelect(-1L) }

    var mode by remember { mutableIntStateOf(0) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        SegmentedButton(
            selected = mode == 0,
            onClick = { mode = 0 },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text(stringResource(R.string.viewer_qs_mode_routes)) }
        SegmentedButton(
            selected = mode == 1,
            onClick = { mode = 1 },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text(stringResource(R.string.viewer_qs_mode_competitions)) }
    }

    if (mode == 0) {
        tracks.forEach { t ->
            RadioRow(
                t.name,
                stringResource(R.string.viewer_qs_route_meta, t.distanceMeters / 1000.0, t.pointCount),
                current == t.id,
            ) { onSelect(t.id) }
        }
        if (tracks.isEmpty()) {
            Text(stringResource(R.string.viewer_qs_no_routes_yet),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        var tv by remember { mutableStateOf(turnVoice) }
        ToggleRow(stringResource(R.string.viewer_qs_turn_voice), tv) { tv = it; onTurnVoice(it) }
    } else {
        // "None" is what gets you OUT of a competition (and leaves the map clean); the rows show
        // which one is being raced, so the list reflects state instead of being a blind launcher.
        RadioRow(
            stringResource(R.string.viewer_qs_competition_none),
            stringResource(R.string.viewer_qs_competition_none_subtitle),
            currentCompetitionId <= 0L,
        ) { onStartCompetition(-1L) }
        if (competitions.isEmpty()) {
            Text(stringResource(R.string.viewer_qs_no_competitions_yet),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        competitions.forEach { c ->
            val isLap = c.type == CompetitionType.LAP
            CompetitionRow(
                name = c.name,
                isLap = isLap,
                selected = c.id == currentCompetitionId,
            ) { onStartCompetition(c.id) }
        }
    }

    // Laps are their own axis, not a sub-mode of competition: you can lap a route, lap a
    // competition's circuit, or lap nothing at all. So the block sits BELOW the routes/competitions
    // switch and outside it — buried in the "Competiciones" branch, these were invisible unless you
    // happened to tap that button, and the switch resets to "Rutas" every time the sheet opens.
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    LapsSection(
        lapManagement = lapManagement,
        onLapManagement = onLapManagement,
        autoLapByPosition = autoLapByPosition,
        onAutoLapByPosition = onAutoLapByPosition,
        autoDetectLoop = autoDetectLoop,
        onAutoDetectLoop = onAutoDetectLoop,
        autoLapEveryM = autoLapEveryM,
        onAutoLapEveryM = onAutoLapEveryM,
        lapCountdown = lapCountdown,
        onLapCountdown = onLapCountdown,
        ghostEnabled = ghostEnabled,
        onGhostEnabled = onGhostEnabled,
        halo = halo,
        onHalo = onHalo,
        showSeconds = showSeconds,
        onShowSeconds = onShowSeconds,
    )
}

/**
 * Everything about going round again: whether laps exist at all, how they close, and who you chase.
 * The ghost sits under its own heading because it serves a route competition too, where there are
 * no laps — its help text has always said so even while the UI filed it under "Competiciones".
 */
@Composable
private fun LapsSection(
    lapManagement: Boolean,
    onLapManagement: (Boolean) -> Unit,
    autoLapByPosition: Boolean,
    onAutoLapByPosition: (Boolean) -> Unit,
    autoDetectLoop: Boolean,
    onAutoDetectLoop: (Boolean) -> Unit,
    autoLapEveryM: Float,
    onAutoLapEveryM: (Float) -> Unit,
    lapCountdown: Boolean,
    onLapCountdown: (Boolean) -> Unit,
    ghostEnabled: Boolean,
    onGhostEnabled: (Boolean) -> Unit,
    halo: Boolean,
    onHalo: (Boolean) -> Unit,
    showSeconds: Boolean,
    onShowSeconds: (Boolean) -> Unit,
) {
    Text(stringResource(R.string.viewer_qs_section_laps), style = MaterialTheme.typography.labelLarge)
    ToggleRow(stringResource(R.string.viewer_qs_lap_management), lapManagement, onLapManagement)
    if (lapManagement) {
        ToggleRow(stringResource(R.string.viewer_qs_auto_lap), autoLapByPosition, onAutoLapByPosition)
        Hint(R.string.viewer_qs_auto_lap_help)
        // Auto-detect needs a line to seed, which position auto-lap provides — so it nests here.
        if (autoLapByPosition) {
            ToggleRow(stringResource(R.string.viewer_qs_auto_detect_loop), autoDetectLoop, onAutoDetectLoop)
            Hint(R.string.viewer_qs_auto_detect_loop_help)
        }
        // Runner splits: a lap every N km, no buttons. Off (0) keeps laps fully manual.
        Hint(R.string.viewer_qs_auto_lap_distance)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0f, 500f, 1000f, 5000f).forEach { m ->
                FilterChip(
                    selected = autoLapEveryM == m,
                    onClick = { onAutoLapEveryM(m) },
                    label = {
                        Text(
                            when {
                                m == 0f -> stringResource(R.string.viewer_qs_auto_lap_off)
                                m < 1000f -> "%.1f km".format(m / 1000f)
                                else -> "%.0f km".format(m / 1000f)
                            },
                        )
                    },
                )
            }
        }
        ToggleRow(stringResource(R.string.viewer_qs_lap_countdown), lapCountdown, onLapCountdown)
        Hint(R.string.viewer_qs_lap_countdown_help)
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(stringResource(R.string.viewer_qs_section_ghost), style = MaterialTheme.typography.labelLarge)
    ToggleRow(stringResource(R.string.viewer_qs_ghost_enabled), ghostEnabled, onGhostEnabled)
    Hint(R.string.viewer_qs_ghost_enabled_help)
    if (ghostEnabled) {
        ToggleRow(stringResource(R.string.viewer_qs_halo), halo, onHalo)
        Hint(R.string.viewer_qs_halo_help)
        ToggleRow(stringResource(R.string.viewer_qs_ghost_seconds), showSeconds, onShowSeconds)
    }
}

/** Small explanatory line under a setting. */
@Composable
private fun Hint(@androidx.annotation.StringRes res: Int) = Text(
    stringResource(res),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.outline,
)

/** One competition in the race list: selection dot + type icon (lap vs route) + name + tap-to-race. */
@Composable
private fun CompetitionRow(name: String, isLap: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Icon(
            if (isLap) Icons.Filled.Loop else Icons.Filled.Route,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (isLap) R.string.competition_type_lap else R.string.competition_type_route),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun OptionsTab(
    orientation: String,
    keepScreenOn: Boolean,
    fullscreen: Boolean,
    adaptiveZoom: Boolean,
    onOrientation: (String) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    countdown: Boolean = false,
    onCountdown: (Boolean) -> Unit = {},
    autoPause: Boolean = false,
    onAutoPause: (Boolean) -> Unit = {},
    autoPauseSec: Int = 5,
    onAutoPauseSec: (Int) -> Unit = {},
    onAdaptiveZoom: (Boolean) -> Unit,
    sportLabel: String? = null,
    sportLocked: Boolean = false,
    onChangeSport: () -> Unit = {},
) {
    // Sport row FIRST: it drives the HUD, the splits and the cadence unit. Without a way back in,
    // the picker only ever showed once and the sport could never be changed again.
    sportLabel?.let { label ->
        Text(stringResource(R.string.viewer_qs_sport), style = MaterialTheme.typography.labelLarge)
        FilterChip(
            selected = false,
            enabled = !sportLocked,
            onClick = onChangeSport,
            label = { Text(label) },
        )
        if (sportLocked) {
            Text(
                stringResource(R.string.viewer_qs_sport_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
    Text(stringResource(R.string.viewer_qs_map_orientation), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(orientation == "NORTH_UP", { onOrientation("NORTH_UP") }, label = { Text(stringResource(R.string.viewer_qs_north_up)) })
        FilterChip(orientation == "HEADING_UP", { onOrientation("HEADING_UP") }, label = { Text(stringResource(R.string.viewer_qs_heading_up)) })
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    ToggleRow(stringResource(R.string.viewer_qs_adaptive_zoom), adaptiveZoom, onAdaptiveZoom)
    Text(
        stringResource(R.string.viewer_qs_adaptive_zoom_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    ToggleRow(stringResource(R.string.viewer_qs_keep_screen_on), keepScreenOn, onKeepScreenOn)
    ToggleRow(stringResource(R.string.viewer_qs_fullscreen), fullscreen, onFullscreen)
    ToggleRow(stringResource(R.string.viewer_qs_countdown), countdown, onCountdown)
    ToggleRow(stringResource(R.string.viewer_qs_auto_pause), autoPause, onAutoPause)
    if (autoPause) {
        Text(
            stringResource(R.string.viewer_qs_auto_pause_secs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 20, 30).forEach { secs ->
                FilterChip(autoPauseSec == secs, { onAutoPauseSec(secs) }, label = { Text("$secs s") })
            }
        }
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
