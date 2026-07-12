package cat.hudpro.opentracks.viewer.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cat.hudpro.opentracks.viewer.hud.HudData
import cat.hudpro.opentracks.viewer.hud.HudMetric
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private data class DataCell(val label: String, val value: String, val unit: String)

/**
 * Full-screen "Dades" view: a scrollable grid of all live metrics (like OpenTracks' recording
 * screen). Swiped in from the map. Reuses [HudMetric] formatters so values match the HUD widgets.
 */
@Composable
fun DataView(data: HudData, modifier: Modifier = Modifier, reloadKey: Any? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val layout = remember(reloadKey) {
        DataLayoutStore.load(cat.hudpro.opentracks.data.prefs.ViewerPreferences.get(context))
    }
    val followOnly = setOf(HudMetric.REMAINING, HudMetric.OFF_ROUTE)
    val metrics = layout.metrics().filter { data.following || it !in followOnly }
    val cells = remember(data, layout) {
        metrics.map { DataCell(it.label, it.value(data.metrics, data.units), it.unit(data.units)) }
    }

    // Live wall clock tile (updates every second).
    val clock by produceState(initialValue = "", data.metrics.isRecording) {
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
        while (true) {
            value = LocalTime.now().format(fmt)
            delay(1000)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(layout.columns.coerceIn(1, 3)),
            // Extra top padding clears the floating "Mapa / Dades" switcher.
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 60.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(cells) { cell -> DataTile(cell) }
            if (layout.showClock) item { DataTile(DataCell("Rellotge", clock, "")) }
        }
    }
}

@Composable
private fun DataTile(cell: DataCell) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                cell.label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.Bottom,
            ) {
                Text(
                    cell.value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (cell.unit.isNotEmpty()) {
                    Text(
                        " ${cell.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}
