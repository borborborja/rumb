package cat.hudpro.opentracks.viewer.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The HUD drawn on top of the map in the viewer. Widgets are freely positioned by their fractional
 * (x,y). Non-interactive areas let touches fall through to the map; only control buttons consume them.
 * Insets are applied by the host (viewer), not here, so the same renderer works in the designer preview.
 */
@Composable
fun HudOverlay(
    data: HudData,
    layout: HudLayout,
    controls: HudControls = HudControls.disabled,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxW = maxWidth
        val maxH = maxHeight
        layout.widgets.forEach { widget ->
            val element = widget.element ?: return@forEach
            Box(Modifier.offset(x = maxW * widget.x, y = maxH * widget.y)) {
                HudWidgetContent(element, data, controls, layout.scale * widget.scale)
            }
        }
    }
}

/** Renders one element by its category. Shared between the viewer and the designer canvas. */
@Composable
fun HudWidgetContent(element: HudElement, data: HudData, controls: HudControls, scale: Float) {
    when (element.category) {
        HudCategory.METRIC -> element.metric?.let { HudTile(it, data.metrics, scale) }
        HudCategory.CHART -> when (element.id) {
            HudCatalog.CHART_SPEED -> SpeedSparkline(data.speedSeries, scale)
            HudCatalog.CHART_ELEVATION -> ElevationProfile(data.elevationProfile, data.routeProgress, scale)
        }
        HudCategory.CONTROL -> when (element.id) {
            HudCatalog.CONTROL_RECENTER -> RecenterControl(controls, scale)
            HudCatalog.CONTROL_COMPASS -> CompassControl(controls, data.metrics.bearingDeg, scale)
            HudCatalog.CONTROL_ZOOM -> ZoomControl(controls, scale)
        }
    }
}

@Composable
private fun HudTile(metric: HudMetric, metrics: LiveMetrics, scale: Float) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB0000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = metric.label.uppercase(),
            color = Color(0xFFB8C4CE),
            fontSize = (11 * scale).sp,
            fontWeight = FontWeight.Medium,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = metric.value(metrics),
                color = Color.White,
                fontSize = (30 * scale).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
            )
            if (metric.unit.isNotEmpty()) {
                Text(
                    text = " ${metric.unit}",
                    color = Color(0xFFB8C4CE),
                    fontSize = (13 * scale).sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RecenterControl(controls: HudControls, scale: Float) {
    val tint = if (controls.followEnabled) Color(0xFFFFD166) else Color.White
    RoundButton(scale = scale, onClick = controls.onRecenter) {
        Icon(Icons.Filled.MyLocation, contentDescription = "Centrar", tint = tint, modifier = Modifier.size((22 * scale).dp))
    }
}

@Composable
private fun CompassControl(controls: HudControls, bearingDeg: Double?, scale: Float) {
    RoundButton(scale = scale, onClick = controls.onNorth) {
        Icon(
            Icons.Filled.Navigation,
            contentDescription = "Nord",
            tint = Color.White,
            modifier = Modifier.size((22 * scale).dp).rotate((bearingDeg ?: 0.0).toFloat()),
        )
    }
}

@Composable
private fun ZoomControl(controls: HudControls, scale: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundButton(scale = scale, onClick = controls.onZoomIn) {
            Icon(Icons.Filled.Add, contentDescription = "Apropar", tint = Color.White, modifier = Modifier.size((22 * scale).dp))
        }
        RoundButton(scale = scale, onClick = controls.onZoomOut) {
            Icon(Icons.Filled.Remove, contentDescription = "Allunyar", tint = Color.White, modifier = Modifier.size((22 * scale).dp))
        }
    }
}

@Composable
private fun RoundButton(scale: Float, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size((46 * scale).dp)
            .clip(CircleShape)
            .background(Color(0xB0000000))
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Clickable that consumes the touch (so the map doesn't pan) but without a heavy ripple over the map. */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = androidx.compose.runtime.remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}
