package cat.hudpro.opentracks.viewer.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/**
 * Full-screen WYSIWYG HUD editor canvas: renders exactly like the viewer's [HudOverlay] (auto-stacked
 * zones, switcher band), but every widget can be dragged (it lands magnetized in the nearest of the 8
 * zones, highlighted while dragging), resized with the corner handle, configured (center gear) or
 * removed (✕).
 */
@Composable
fun HudEditorCanvas(
    layout: HudLayout,
    data: HudData,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    onChange: (HudLayout) -> Unit,
    onConfigure: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentLayout by rememberUpdatedState(layout)
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var dragPointer by remember { mutableStateOf<Offset?>(null) } // canvas-local, while dragging
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var draggingId by remember { mutableStateOf<String?>(null) }

    val targetZone = dragPointer?.let {
        if (canvasSize.width > 0) zoneForPoint(it.x, it.y, canvasSize.width, canvasSize.height) else null
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                canvasOrigin = it.boundsInRoot().topLeft
                canvasSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
            }
            .noRippleClickable { onSelect(null) }
            .padding(12.dp),
    ) {
        // Magnetized landing zones, visible while dragging (target highlighted).
        if (draggingId != null && canvasSize.width > 0) {
            ZoneHighlights(canvasSize, targetZone)
        }

        HudZone.entries.forEach { zone ->
            val items = layout.widgets.withIndex().filter { it.value.zone == zone }
            if (items.isEmpty()) return@forEach
            val content: @Composable () -> Unit = {
                items.forEach { (index, widget) ->
                    val element = widget.element ?: return@forEach
                    key(widget.elementId) {
                        EditorWidget(
                            elementId = widget.elementId,
                            selected = selectedIndex == index,
                            dragging = draggingId == widget.elementId,
                            dragOffset = if (draggingId == widget.elementId) dragOffset else Offset.Zero,
                            onSelect = { onSelect(index) },
                            onRemove = { onChange(currentLayout.remove(widget.elementId)); onSelect(null) },
                            onConfigure = { onConfigure(index) },
                            onResizeBy = { factor ->
                                val i = currentLayout.widgets.indexOfFirst { it.elementId == widget.elementId }
                                if (i >= 0) {
                                    onChange(currentLayout.setWidgetScale(i, currentLayout.widgets[i].scale * factor))
                                }
                            },
                            onDragStart = { widgetOriginInRoot, startInWidget ->
                                draggingId = widget.elementId
                                dragOffset = Offset.Zero
                                dragPointer = widgetOriginInRoot - canvasOrigin + startInWidget
                                onSelect(index)
                            },
                            onDragBy = { delta ->
                                dragOffset += delta
                                dragPointer = dragPointer?.plus(delta)
                            },
                            onDragEnd = { cancelled ->
                                // Compute the drop zone from the CURRENT pointer state (a composition
                                // value here would be a stale closure inside pointerInput).
                                val p = dragPointer
                                val zoneAtDrop = if (p != null && canvasSize.width > 0) {
                                    zoneForPoint(p.x, p.y, canvasSize.width, canvasSize.height)
                                } else {
                                    null
                                }
                                if (!cancelled && zoneAtDrop != null) {
                                    val i = currentLayout.widgets.indexOfFirst { it.elementId == widget.elementId }
                                    if (i >= 0) onChange(currentLayout.moveToZone(i, zoneAtDrop))
                                }
                                draggingId = null
                                dragOffset = Offset.Zero
                                dragPointer = null
                            },
                        ) {
                            HudWidgetContent(element, data, HudControls.disabled, layout.scale * widget.scale, widget.options)
                        }
                    }
                }
            }
            val zoneModifier = Modifier.align(zoneAlignment(zone))
                .then(if (zone.isTop) Modifier.padding(top = SWITCHER_BAND) else Modifier)
            Box(zoneModifier) {
                if (zone.isCenter) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
                }
            }
        }
    }
}

/** Translucent 8-zone grid shown while dragging; the zone under the finger is highlighted. */
@Composable
private fun ZoneHighlights(canvas: Size, target: HudZone?) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun rectFor(zone: HudZone): Pair<Offset, Size> = when (zone) {
            HudZone.TOP_LEFT -> Offset(0f, 0f) to Size(w / 3, h / 3)
            HudZone.TOP_CENTER -> Offset(w / 3, 0f) to Size(w / 3, h / 3)
            HudZone.TOP_RIGHT -> Offset(2 * w / 3, 0f) to Size(w / 3, h / 3)
            HudZone.MIDDLE_LEFT -> Offset(0f, h / 3) to Size(w / 2, h / 3)
            HudZone.MIDDLE_RIGHT -> Offset(w / 2, h / 3) to Size(w / 2, h / 3)
            HudZone.BOTTOM_LEFT -> Offset(0f, 2 * h / 3) to Size(w / 3, h / 3)
            HudZone.BOTTOM_CENTER -> Offset(w / 3, 2 * h / 3) to Size(w / 3, h / 3)
            HudZone.BOTTOM_RIGHT -> Offset(2 * w / 3, 2 * h / 3) to Size(w / 3, h / 3)
        }
        HudZone.entries.forEach { zone ->
            val (topLeft, sz) = rectFor(zone)
            val inset = 6f
            drawRoundRect(
                color = if (zone == target) Color(0x66FFD166) else Color(0x22FFFFFF),
                topLeft = topLeft + Offset(inset, inset),
                size = Size(sz.width - 2 * inset, sz.height - 2 * inset),
                cornerRadius = CornerRadius(24f, 24f),
            )
        }
    }
}

@Composable
private fun EditorWidget(
    elementId: String,
    selected: Boolean,
    dragging: Boolean,
    dragOffset: Offset,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onConfigure: () -> Unit,
    onResizeBy: (Float) -> Unit,
    onDragStart: (widgetOriginInRoot: Offset, startInWidget: Offset) -> Unit,
    onDragBy: (Offset) -> Unit,
    onDragEnd: (cancelled: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    // pointerInput(elementId) never restarts, so its lambdas would capture the FIRST composition's
    // callbacks (stale layout/pointer state). Route every call through rememberUpdatedState.
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnResizeBy by rememberUpdatedState(onResizeBy)

    Box(
        Modifier
            .onGloballyPositioned { originInRoot = it.boundsInRoot().topLeft }
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                alpha = if (dragging) 0.85f else 1f
            },
    ) {
        val deco = if (selected || dragging) {
            Modifier.clip(RoundedCornerShape(16.dp)).border(2.dp, Color(0xFFFFD166), RoundedCornerShape(16.dp))
        } else {
            Modifier
        }
        Box(deco) { content() }
        // Transparent layer: tap = select, drag = move (magnetized landing on release).
        Box(
            Modifier
                .matchParentSize()
                .noRippleClickable(onSelect)
                .pointerInput(elementId) {
                    detectDragGestures(
                        onDragStart = { start -> currentOnDragStart(originInRoot, start) },
                        onDrag = { change, delta -> change.consume(); currentOnDragBy(delta) },
                        onDragEnd = { currentOnDragEnd(false) },
                        onDragCancel = { currentOnDragEnd(true) },
                    )
                },
        )
        if (selected && !dragging) {
            // Center gear: widget settings.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC1D3557))
                    .noRippleClickable(onConfigure),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Settings, contentDescription = "Configurar", tint = Color.White, modifier = Modifier.size(18.dp)) }
            // Top-end ✕: remove.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE63946))
                    .noRippleClickable(onRemove),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, contentDescription = "Treure", tint = Color.White, modifier = Modifier.size(15.dp)) }
            // Bottom-end handle: drag to resize (individual scale).
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC000000))
                    .pointerInput(elementId) {
                        detectDragGestures(
                            onDrag = { change, delta ->
                                change.consume()
                                // Down-right grows, up-left shrinks.
                                currentOnResizeBy(1f + (delta.x + delta.y) / 250f)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.OpenInFull, contentDescription = "Redimensionar", tint = Color.White, modifier = Modifier.size(14.dp)) }
        }
    }
}

@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}
