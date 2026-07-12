package cat.hudpro.opentracks.manager.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cat.hudpro.opentracks.data.map.MapSource
import cat.hudpro.opentracks.data.map.OfflineMapStore
import cat.hudpro.opentracks.data.map.OfflineSector
import cat.hudpro.opentracks.data.map.RegionDownloadWorker
import java.util.Locale

@Composable
fun OfflineSectorsScreen(mapPath: String, onBack: () -> Unit, onDownloadArea: () -> Unit) {
    val context = LocalContext.current
    val store = remember { OfflineMapStore.get(context) }
    val mapView = rememberMapViewWithLifecycle()
    val requestNotif = rememberNotificationPermission()

    var map by remember { mutableStateOf(store.byPath(mapPath)) }
    var sectors by remember { mutableStateOf(map?.let { store.sectorsOf(it) } ?: emptyList()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var controller by remember { mutableStateOf<AreaSelectController?>(null) }
    var pendingDelete by remember { mutableStateOf<OfflineSector?>(null) }

    val sourceId = map?.sourceId
        ?: MapSource.entries
            .filter { map?.name?.startsWith(it.displayName) == true }
            .maxByOrNull { it.displayName.length }?.id

    fun refresh() {
        map = store.byPath(mapPath)
        sectors = map?.let { store.sectorsOf(it) } ?: emptyList()
        if (selected != null && selected !in sectors.indices) selected = null
    }

    // Register the tap hit-test once the controller is ready.
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        c.setGesturesEnabled(true)
        c.onMapClick { lat, lng ->
            selected = sectors.indexOfFirst {
                lng in it.bounds[0]..it.bounds[2] && lat in it.bounds[1]..it.bounds[3]
            }.takeIf { it >= 0 }
        }
        if (sectors.isNotEmpty()) c.fitBoxes(sectors.map { it.bbox })
    }
    // Redraw rectangles when sectors or the selection change.
    LaunchedEffect(controller, sectors, selected) {
        controller?.showBoxes(sectors.map { it.bbox }, selected)
    }

    DetailScaffold(
        title = map?.name ?: "Sectors offline",
        onBack = onBack,
        actions = {
            IconButton(onClick = onDownloadArea) { Icon(Icons.Filled.Add, contentDescription = "Afegir sector") }
        },
    ) { modifier ->
        Column(modifier.fillMaxSize()) {
            AndroidView(
                factory = {
                    mapView.getMapAsync { m ->
                        val c = AreaSelectController(m)
                        controller = c
                        c.init { }
                    }
                    mapView
                },
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (sectors.isEmpty()) {
                    Text("Cap sector. Afegeix-ne un descarregant una àrea.", style = MaterialTheme.typography.bodySmall)
                }
                sectors.forEachIndexed { i, sector ->
                    SectorRow(
                        sector = sector,
                        index = i,
                        selected = selected == i,
                        onSelect = { selected = i },
                        onRedownload = {
                            val sid = sourceId
                            if (sid == null) {
                                Toast.makeText(context, "Tipus de mapa desconegut", Toast.LENGTH_SHORT).show()
                            } else {
                                requestNotif()
                                RegionDownloadWorker.enqueue(
                                    context, sid, MapSource.byId(sid).displayName + " · àrea",
                                    sector.bbox, sector.minZoom, sector.maxZoom,
                                )
                                Toast.makeText(context, "Redescarregant sector…", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = { pendingDelete = sector },
                    )
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Esborrar sector") },
            text = { Text("S'esborraran les tessel·les d'aquest sector que no comparteixi cap altre.") },
            confirmButton = {
                TextButton(onClick = {
                    map?.let { store.deleteSector(it, target) }
                    pendingDelete = null
                    refresh()
                }) { Text("Esborrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel·lar") } },
        )
    }
}

@Composable
private fun SectorRow(
    sector: OfflineSector,
    index: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onRedownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onSelect) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Sector ${index + 1}" + if (selected) "  ●" else "",
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    String.format(
                        Locale.US, "zoom %d–%d · %,d tessel·les · %.0f×%.0f km",
                        sector.minZoom, sector.maxZoom, sector.tileCount,
                        widthKm(sector), heightKm(sector),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onRedownload) {
                Icon(Icons.Filled.Refresh, contentDescription = "Redescarregar")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Esborrar", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun widthKm(s: OfflineSector): Double {
    val midLat = (s.bounds[1] + s.bounds[3]) / 2.0
    return (s.bounds[2] - s.bounds[0]) * 111.320 * Math.cos(Math.toRadians(midLat))
}

private fun heightKm(s: OfflineSector): Double = (s.bounds[3] - s.bounds[1]) * 110.574
