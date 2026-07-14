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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
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
import cat.rumb.app.data.tracks.FollowTrackEntity

private val TABS = listOf(
    R.string.viewer_qs_tab_map,
    R.string.viewer_qs_tab_route,
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
    competing: Boolean = false,
    ghostCandidates: List<FollowTrackEntity> = emptyList(),
    opponentId: Long = -1L,
    onSelectOpponent: (Long) -> Unit = {},
    halo: Boolean = true,
    onHalo: (Boolean) -> Unit = {},
    showSeconds: Boolean = true,
    onShowSeconds: (Boolean) -> Unit = {},
    turnVoice: Boolean = true,
    onTurnVoice: (Boolean) -> Unit = {},
    lapManagement: Boolean = true,
    onLapManagement: (Boolean) -> Unit = {},
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
    var lapMgmt by remember { mutableStateOf(lapManagement) }

    val tabs = TABS + if (competing) listOf(R.string.viewer_qs_tab_competition) else emptyList()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, title ->
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
                1 -> FollowTab(selFollow, tracks, turnVoice, onTurnVoice) { id -> selFollow = id; onSelectFollow(id) }
                3 -> CompetitionQsTab(
                    ghostCandidates, opponentId, onSelectOpponent,
                    halo, onHalo, showSeconds, onShowSeconds,
                )
                else -> OptionsTab(
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
                    lapManagement = lapMgmt,
                    onLapManagement = { lapMgmt = it; onLapManagement(it) },
                )
            }
        }
    }
}

@Composable
private fun MapTab(current: String?, offlineMaps: List<OfflineMap>, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.viewer_qs_online_map), style = MaterialTheme.typography.labelLarge)
    MapSource.entries.forEach { source ->
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

@Composable
private fun FollowTab(
    current: Long,
    tracks: List<FollowTrackEntity>,
    turnVoice: Boolean = true,
    onTurnVoice: (Boolean) -> Unit = {},
    onSelect: (Long) -> Unit,
) {
    Text(stringResource(R.string.viewer_qs_route_to_follow), style = MaterialTheme.typography.labelLarge)
    RadioRow(
        stringResource(R.string.viewer_qs_route_none),
        stringResource(R.string.viewer_qs_route_none_subtitle),
        current <= 0L,
    ) { onSelect(-1L) }
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
    lapManagement: Boolean = true,
    onLapManagement: (Boolean) -> Unit = {},
) {
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
    ToggleRow(stringResource(R.string.viewer_qs_lap_management), lapManagement, onLapManagement)
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
private fun CompetitionQsTab(
    candidates: List<FollowTrackEntity>,
    opponentId: Long,
    onSelectOpponent: (Long) -> Unit,
    halo: Boolean,
    onHalo: (Boolean) -> Unit,
    showSeconds: Boolean,
    onShowSeconds: (Boolean) -> Unit,
) {
    var selOpponent by remember { mutableLongStateOf(opponentId) }
    var h by remember { mutableStateOf(halo) }
    var secs by remember { mutableStateOf(showSeconds) }

    fun fmtDuration(ms: Long?): String {
        if (ms == null || ms <= 0) return "—"
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    val bestId = candidates.filter { (it.durationMs ?: 0) > 0 }.minByOrNull { it.durationMs!! }?.id

    Text(stringResource(R.string.viewer_qs_opponent), style = MaterialTheme.typography.labelLarge)
    candidates.forEach { c ->
        val subtitle = fmtDuration(c.durationMs) +
            if (c.id == bestId) " · " + stringResource(R.string.viewer_qs_opponent_best) else ""
        RadioRow(c.name, subtitle, selOpponent == c.id) {
            selOpponent = c.id
            onSelectOpponent(c.id)
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    ToggleRow(stringResource(R.string.viewer_qs_halo), h) { h = it; onHalo(it) }
    Text(
        stringResource(R.string.viewer_qs_halo_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    ToggleRow(stringResource(R.string.viewer_qs_ghost_seconds), secs) { secs = it; onShowSeconds(it) }
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
