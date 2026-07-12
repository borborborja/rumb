package cat.hudpro.opentracks.viewer

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
import androidx.compose.ui.unit.dp
import cat.hudpro.opentracks.data.map.MapSource
import cat.hudpro.opentracks.data.map.OfflineMap
import cat.hudpro.opentracks.data.tracks.FollowTrackEntity
import java.util.Locale

private val TABS = listOf("Mapa", "Ruta", "Opcions")

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
    onSelectBaseMap: (String) -> Unit,
    onSelectFollow: (Long) -> Unit,
    onOrientation: (String) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) }
    var selBase by remember { mutableStateOf(currentBaseMapId) }
    var selFollow by remember { mutableLongStateOf(currentFollowId) }
    var orient by remember { mutableStateOf(orientation) }
    var keep by remember { mutableStateOf(keepScreenOn) }
    var full by remember { mutableStateOf(fullscreen) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (tab) {
                0 -> MapTab(selBase, offlineMaps) { id -> selBase = id; onSelectBaseMap(id) }
                1 -> FollowTab(selFollow, tracks) { id -> selFollow = id; onSelectFollow(id) }
                else -> OptionsTab(
                    orient, keep, full,
                    onOrientation = { orient = it; onOrientation(it) },
                    onKeepScreenOn = { keep = it; onKeepScreenOn(it) },
                    onFullscreen = { full = it; onFullscreen(it) },
                )
            }
        }
    }
}

@Composable
private fun MapTab(current: String?, offlineMaps: List<OfflineMap>, onSelect: (String) -> Unit) {
    Text("Mapa online", style = MaterialTheme.typography.labelLarge)
    MapSource.entries.forEach { source ->
        RadioRow(source.displayName, source.attribution, current == source.id) { onSelect(source.id) }
    }
    if (offlineMaps.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Mapa offline", style = MaterialTheme.typography.labelLarge)
        offlineMaps.forEach { map ->
            RadioRow(map.name, "offline", current == map.selectionId) { onSelect(map.selectionId) }
        }
    }
}

@Composable
private fun FollowTab(current: Long, tracks: List<FollowTrackEntity>, onSelect: (Long) -> Unit) {
    Text("Ruta a seguir", style = MaterialTheme.typography.labelLarge)
    RadioRow("Cap", "sense ruta", current <= 0L) { onSelect(-1L) }
    tracks.forEach { t ->
        RadioRow(
            t.name,
            String.format(Locale.US, "%.1f km · %d punts", t.distanceMeters / 1000.0, t.pointCount),
            current == t.id,
        ) { onSelect(t.id) }
    }
    if (tracks.isEmpty()) {
        Text("Encara no hi ha rutes. Crea'n o importa'n des de «Tracks a seguir».",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun OptionsTab(
    orientation: String,
    keepScreenOn: Boolean,
    fullscreen: Boolean,
    onOrientation: (String) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
) {
    Text("Orientació del mapa", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(orientation == "NORTH_UP", { onOrientation("NORTH_UP") }, label = { Text("Nord amunt") })
        FilterChip(orientation == "HEADING_UP", { onOrientation("HEADING_UP") }, label = { Text("Segons direcció") })
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    ToggleRow("Mantenir la pantalla encesa", keepScreenOn, onKeepScreenOn)
    ToggleRow("Pantalla completa", fullscreen, onFullscreen)
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
