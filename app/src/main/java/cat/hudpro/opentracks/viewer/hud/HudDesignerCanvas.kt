package cat.hudpro.opentracks.viewer.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Editable HUD overlay for the designer: reuses [HudWidgetContent] for pixel-perfect fidelity, but
 * wraps each widget so it can be dragged to any position, selected, and deleted. Positions are
 * committed back as fractional (x,y) via [onMove]; snapping is applied by the caller.
 */
@Composable
fun HudDesignerCanvas(
    layout: HudLayout,
    data: HudData,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    onMove: (index: Int, x: Float, y: Float) -> Unit,
    onRemove: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onSelect(null) }) },
    ) {
        val maxWpx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val maxHpx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        layout.widgets.forEachIndexed { index, widget ->
            val element = widget.element ?: return@forEachIndexed
            val current by rememberUpdatedState(widget)
            val selected = selectedIndex == index

            Box(
                Modifier
                    .offset { IntOffset((current.x * maxWpx).roundToInt(), (current.y * maxHpx).roundToInt()) }
                    .pointerInput(index) {
                        detectDragGestures(
                            onDragStart = { onSelect(index) },
                            onDrag = { change, drag ->
                                change.consume()
                                val cw = current
                                onMove(index, cw.x + drag.x / maxWpx, cw.y + drag.y / maxHpx)
                            },
                        )
                    }
                    .pointerInput(index) { detectTapGestures(onTap = { onSelect(index) }) },
            ) {
                val decorated = if (selected) {
                    Modifier.clip(RoundedCornerShape(16.dp)).border(2.dp, Color(0xFFFFD166), RoundedCornerShape(16.dp))
                } else {
                    Modifier
                }
                Box(decorated) {
                    HudWidgetContent(element, data, HudControls.disabled, layout.scale * widget.scale)
                }
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE63946))
                            .pointerInput(index) { detectTapGestures(onTap = { onRemove(index) }) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Close, contentDescription = "Treure", tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}
