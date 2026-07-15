package cat.rumb.app.manager.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cat.rumb.app.R
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.tracks.CircuitEffortEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitDetailScreen(circuitId: Long, onBack: () -> Unit, onStartCircuit: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    val circuit by remember(circuitId) { app.circuitRepository.observeCircuits() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val efforts by remember(circuitId) { app.circuitRepository.effortsFor(circuitId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val name = circuit.firstOrNull { it.id == circuitId }?.name ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.ifBlank { stringResource(R.string.home_tab_circuits) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { onStartCircuit(circuitId) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.circuit_start))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (efforts.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.circuit_empty_efforts))
                }
            } else {
                Text(
                    stringResource(R.string.circuit_efforts_count, efforts.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val best = efforts.minByOrNull { it.timeMs }?.timeMs
                // Efforts already come sorted by time ASC from the DAO.
                efforts.forEachIndexed { rank, e ->
                    EffortRow(rank + 1, e, best)
                }
            }
        }
    }
}

@Composable
private fun EffortRow(rank: Int, e: CircuitEffortEntity, bestMs: Long?) {
    val isBest = bestMs != null && e.timeMs == bestMs
    Card {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isBest) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            } else {
                Text("$rank", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    fmtTime(e.timeMs),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                    color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    fmtDate(e.createdAt) + " · " + "%.2f km".format(e.distanceM / 1000.0) +
                        (e.avgHr?.let { " · %d bpm".format(it.toInt()) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val gap = if (bestMs != null) e.timeMs - bestMs else 0L
            Text(
                if (isBest) stringResource(R.string.home_laps_best) else "+" + fmtTime(gap),
                style = MaterialTheme.typography.labelMedium,
                color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
