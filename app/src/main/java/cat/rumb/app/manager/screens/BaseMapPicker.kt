package cat.rumb.app.manager.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.rumb.app.R
import cat.rumb.app.data.map.MapSource

/**
 * Reusable dropdown rows listing the raster base maps, with a check on the current one.
 * Call inside an existing [DropdownMenu] (e.g. the stats screen's combined view menu).
 */
@Composable
fun BaseMapMenu(currentId: String?, onSelect: (MapSource) -> Unit) {
    MapSource.entries.filter { it.kind == MapSource.Kind.RASTER }.forEach { src ->
        DropdownMenuItem(
            text = { Text(src.displayName) },
            leadingIcon = {
                if (MapSource.byId(currentId) == src) {
                    Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                }
            },
            onClick = { onSelect(src) },
        )
    }
}

/**
 * Self-contained base-map selector overlay: the Home-screen layers icon over a translucent
 * circle, opening a [BaseMapMenu]. Meant to sit in a map [Box] corner (pass an aligned modifier).
 */
@Composable
fun BaseMapPicker(currentId: String?, onSelect: (MapSource) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0x99000000)),
        ) {
            Icon(
                Icons.Filled.Layers,
                contentDescription = stringResource(R.string.home_cd_map_layers),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BaseMapMenu(currentId) { src ->
                onSelect(src)
                expanded = false
            }
        }
    }
}
