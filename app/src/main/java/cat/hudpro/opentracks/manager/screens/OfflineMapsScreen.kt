package cat.hudpro.opentracks.manager.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cat.hudpro.opentracks.data.map.OfflineMap
import cat.hudpro.opentracks.data.map.OfflineMapStore
import cat.hudpro.opentracks.data.prefs.ViewerPreferences
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(onBack: () -> Unit, onDownloadArea: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { OfflineMapStore.get(context) }
    val prefs = remember { ViewerPreferences.get(context) }
    var maps by remember { mutableStateOf(store.list()) }
    var activeId by remember { mutableStateOf(prefs.baseMapId) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "mapa"
            runCatching { store.import(context.contentResolver, uri, name) }
            maps = store.list()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mapes offline") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Enrere") }
                },
            )
        },
        floatingActionButton = {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.material3.SmallFloatingActionButton(onClick = onDownloadArea) {
                    Icon(Icons.Filled.Map, contentDescription = "Descarregar àrea")
                }
                ExtendedFloatingActionButton(
                    onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    text = { Text("Importar MBTiles") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (maps.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Importa un fitxer .mbtiles (p. ex. el topogràfic o l'ortofoto híbrida oficials " +
                            "de l'ICGC des de visors.icgc.cat/appdownloads) per fer servir el mapa sense cobertura.",
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(maps, key = { it.path }) { map ->
                        OfflineRow(
                            map = map,
                            isActive = activeId == map.selectionId,
                            onUse = { activeId = map.selectionId; prefs.baseMapId = map.selectionId },
                            onDelete = { store.delete(map); maps = store.list() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineRow(map: OfflineMap, isActive: Boolean, onUse: () -> Unit, onDelete: () -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isActive, onClick = onUse)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(map.name, style = MaterialTheme.typography.bodyLarge)
                val mb = File(map.path).length() / (1024.0 * 1024.0)
                Text(String.format(Locale.US, "%.1f MB · offline", mb), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Esborrar") }
        }
    }
}
