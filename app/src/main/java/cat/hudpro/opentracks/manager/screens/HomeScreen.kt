package cat.hudpro.opentracks.manager.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cat.hudpro.opentracks.manager.Routes

private data class Tile(val title: String, val subtitle: String, val icon: ImageVector, val route: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenViewer: () -> Unit, onNavigate: (String) -> Unit, onOpenSettings: () -> Unit = {}) {
    val tiles = listOf(
        Tile("Capas de mapa", "Online i offline", Icons.Filled.Layers, Routes.LAYERS),
        Tile("Tracks a seguir", "GPX i col·leccions", Icons.AutoMirrored.Filled.DirectionsRun, Routes.TRACKS),
        Tile("Endurain", "Pujar i sincronitzar", Icons.Filled.CloudUpload, Routes.ENDURAIN),
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HUD Pro") },
                actions = {
                    androidx.compose.material3.IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ajustos")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            val context = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ElevatedButton(
                    onClick = onOpenViewer,
                    modifier = Modifier.weight(1f).height(72.dp),
                ) {
                    Icon(Icons.Filled.Map, contentDescription = null)
                    Text("  Visor", style = MaterialTheme.typography.titleMedium)
                }
                ElevatedButton(
                    onClick = { cat.hudpro.opentracks.data.opentracks.OpenTracksRecording.start(context) },
                    modifier = Modifier.weight(1f).height(72.dp),
                ) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color(0xFFE63946))
                    Text("  Gravar", style = MaterialTheme.typography.titleMedium)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tiles) { tile ->
                    Card(onClick = { onNavigate(tile.route) }, modifier = Modifier.height(140.dp)) {
                        Box(Modifier.fillMaxSize().padding(16.dp)) {
                            Icon(tile.icon, contentDescription = null, modifier = Modifier.align(Alignment.TopStart))
                            Column(Modifier.align(Alignment.BottomStart)) {
                                Text(tile.title, fontWeight = FontWeight.Bold)
                                Text(tile.subtitle, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
