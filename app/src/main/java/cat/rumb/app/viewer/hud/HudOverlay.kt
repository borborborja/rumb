package cat.rumb.app.viewer.hud

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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

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
    Box(modifier.fillMaxSize().padding(12.dp)) {
        if (data.competing && data.ghostHalo) {
            data.ghostState?.let { GhostHalo(it) }
        }
        HudZone.entries.forEach { zone ->
            ZoneGroup(zone, layout, data, controls)
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = SWITCHER_BAND),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Recording but the engine hasn't accepted a single fix yet (GPS warm-up): make it visible.
            if (data.metrics.isRecording && data.metrics.pointCount == 0) {
                GpsWaitingPill()
            }
            if (data.isOffRoute) {
                OffRouteBanner(data.metrics)
            }
            if (data.competing && data.metrics.ghostDeltaMeters != null) {
                GhostDeltaBadge(data)
            }
        }
    }
}

/**
 * Pulsating colored glow along the four screen edges: green ahead of the ghost, red behind, blue
 * even. Peripheral-vision race feedback without reading a number. Not clickable (touches pass
 * through to the map).
 */
@Composable
private fun GhostHalo(state: GhostState) {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "halo").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1000),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "haloAlpha",
    )
    val color = Color(android.graphics.Color.parseColor(state.colorHex))
    // `pulse` is read inside drawBehind so only the draw phase invalidates each animation frame.
    Box(
        Modifier.fillMaxSize().drawBehind {
            val w = 14.dp.toPx()
            val c = color.copy(alpha = pulse)
            // Top edge.
            drawRect(
                brush = Brush.verticalGradient(listOf(c, Color.Transparent), startY = 0f, endY = w),
                size = Size(size.width, w),
            )
            // Bottom edge (mirrored).
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, c), startY = size.height - w, endY = size.height),
                topLeft = Offset(0f, size.height - w),
                size = Size(size.width, w),
            )
            // Left edge.
            drawRect(
                brush = Brush.horizontalGradient(listOf(c, Color.Transparent), startX = 0f, endX = w),
                size = Size(w, size.height),
            )
            // Right edge (mirrored).
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, c), startX = size.width - w, endX = size.width),
                topLeft = Offset(size.width - w, 0f),
                size = Size(w, size.height),
            )
        },
    )
}

/** Top-center pill with the signed distance to the ghost (and optionally the estimated seconds). */
@Composable
private fun GhostDeltaBadge(data: HudData) {
    val delta = data.metrics.ghostDeltaMeters ?: return
    val state = data.ghostState ?: return
    val bg = Color(android.graphics.Color.parseColor(state.colorHex)).copy(alpha = 0.95f)
    val metersText = if (delta >= 0) "+${delta.toInt()} m" else "${delta.toInt()} m"
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            metersText,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        val secs = data.metrics.ghostSecondsEst
        if (data.ghostShowSeconds && secs != null) {
            val s = secs.roundToInt()
            Text(
                "≈ ${if (s >= 0) "+$s" else "$s"} s",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Auto-stacked group of the widgets in one zone, aligned to that corner/edge (never overlaps). */
@Composable
fun androidx.compose.foundation.layout.BoxScope.ZoneGroup(
    zone: HudZone,
    layout: HudLayout,
    data: HudData,
    controls: HudControls,
) {
    val widgets = layout.byZone(zone)
    if (widgets.isEmpty()) return
    val content: @Composable () -> Unit = {
        widgets.forEach { w ->
            w.element?.let { HudWidgetContent(it, data, controls, layout.scale * w.scale, w.options) }
        }
    }
    // Top zones start below the floating "Mapa / Dades" switcher so its pill never covers a widget.
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

/** Height reserved at the top for the floating switcher (see ViewerSwitcher). */
val SWITCHER_BAND = 44.dp

/**
 * Maps a point inside a canvas of [width]×[height] to the nearest [HudZone] (thirds grid). The
 * non-existent middle-center resolves to MIDDLE_LEFT/MIDDLE_RIGHT by which half the point is in. Pure.
 */
fun zoneForPoint(x: Float, y: Float, width: Float, height: Float): HudZone {
    val col = ((x / width) * 3).toInt().coerceIn(0, 2)
    val row = ((y / height) * 3).toInt().coerceIn(0, 2)
    return when (row) {
        0 -> listOf(HudZone.TOP_LEFT, HudZone.TOP_CENTER, HudZone.TOP_RIGHT)[col]
        1 -> if (col == 0 || (col == 1 && x < width / 2)) HudZone.MIDDLE_LEFT else HudZone.MIDDLE_RIGHT
        else -> listOf(HudZone.BOTTOM_LEFT, HudZone.BOTTOM_CENTER, HudZone.BOTTOM_RIGHT)[col]
    }
}

/** Maps a [HudZone] to a Compose [Alignment]. Pure. */
fun zoneAlignment(zone: HudZone): Alignment = when (zone) {
    HudZone.TOP_LEFT -> Alignment.TopStart
    HudZone.TOP_CENTER -> Alignment.TopCenter
    HudZone.TOP_RIGHT -> Alignment.TopEnd
    HudZone.MIDDLE_LEFT -> Alignment.CenterStart
    HudZone.MIDDLE_RIGHT -> Alignment.CenterEnd
    HudZone.BOTTOM_LEFT -> Alignment.BottomStart
    HudZone.BOTTOM_CENTER -> Alignment.BottomCenter
    HudZone.BOTTOM_RIGHT -> Alignment.BottomEnd
}

/** Prominent warning shown while off the followed route, with an arrow pointing back to it. */
@Composable
private fun OffRouteBanner(metrics: LiveMetrics, modifier: Modifier = Modifier) {
    val meters = metrics.offRouteMeters?.toInt() ?: 0
    // Arrow rotates to the route relative to the current heading.
    val relBearing = ((metrics.bearingToRouteDeg ?: 0.0) - (metrics.bearingDeg ?: 0.0)).toFloat()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xF2D7191C))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Navigation,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp).rotate(relBearing),
        )
        Text(
            "  " + androidx.compose.ui.res.stringResource(cat.rumb.app.R.string.viewer_off_route, meters),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Renders one element by its category. Shared between the viewer and the designer canvas. */
@Composable
fun HudWidgetContent(
    element: HudElement,
    data: HudData,
    controls: HudControls,
    scale: Float,
    options: Map<String, String> = emptyMap(),
) {
    when (element.category) {
        HudCategory.METRIC -> element.metric?.let { HudTile(it, data, scale, options) }
        HudCategory.CHART -> when (element.id) {
            HudCatalog.CHART_SPEED -> SpeedSparkline(data.speedSeries, scale)
            HudCatalog.CHART_ELEVATION -> ElevationProfile(data.elevationProfile, data.routeProgress, scale)
        }
        HudCategory.CONTROL -> when (element.id) {
            HudCatalog.CONTROL_RECENTER -> RecenterControl(controls, scale)
            HudCatalog.CONTROL_COMPASS -> CompassControl(controls, data.metrics.bearingDeg, scale)
            HudCatalog.CONTROL_ZOOM -> ZoomControl(controls, scale)
            HudCatalog.CONTROL_RECORD -> RecordControl(controls, data.metrics.isRecording, data.isPaused, scale)
        }
        HudCategory.EXTRA -> when (element.id) {
            HudCatalog.WIDGET_CLOCK -> ClockTile(scale, options)
        }
    }
}

/** Value-text color from the widget's options (white by default; malformed hex falls back). */
private fun optionColor(options: Map<String, String>): Color =
    options[HudOption.COLOR]?.let { hex ->
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
    } ?: Color.White

/** Amber pill shown while recording until the first precise GPS fix is accepted. */
@Composable
private fun GpsWaitingPill() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xF2F4A261))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            androidx.compose.ui.res.stringResource(cat.rumb.app.R.string.countdown_waiting_gps),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** History series to chart for a metric, when the "chart" option is on. */
private fun seriesFor(metric: HudMetric, data: HudData): List<Float> = when (metric) {
    HudMetric.SPEED -> data.speedSeries
    HudMetric.HEART_RATE -> data.heartRateSeries
    HudMetric.CADENCE -> data.cadenceSeries
    HudMetric.POWER -> data.powerSeries
    else -> emptyList()
}

@Composable
private fun HudTile(metric: HudMetric, data: HudData, scale: Float, options: Map<String, String> = emptyMap()) {
    val units = data.units
    val unitLabel = metric.unit(units)
    val valueColor = optionColor(options)
    val chartSeries = if (options[HudOption.CHART] == "1") seriesFor(metric, data) else emptyList()
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB0000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(metric.labelRes).uppercase(),
            color = Color(0xFFB8C4CE),
            fontSize = (11 * scale).sp,
            fontWeight = FontWeight.Medium,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = metric.value(data.metrics, units),
                color = valueColor,
                fontSize = (30 * scale).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
            )
            if (unitLabel.isNotEmpty()) {
                Text(
                    text = " $unitLabel",
                    color = Color(0xFFB8C4CE),
                    fontSize = (13 * scale).sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        if (chartSeries.size >= 2) {
            MiniSparkline(chartSeries, valueColor, scale)
        }
    }
}

/** Tiny history polyline drawn under the value when the widget's chart option is on. */
@Composable
private fun MiniSparkline(series: List<Float>, color: Color, scale: Float) {
    androidx.compose.foundation.Canvas(
        Modifier
            .padding(top = (4 * scale).dp)
            .size(width = (96 * scale).dp, height = (20 * scale).dp),
    ) {
        val min = series.min()
        val max = series.max()
        val span = (max - min).takeIf { it > 1e-3f } ?: 1f
        val stepX = size.width / (series.size - 1)
        var prev = androidx.compose.ui.geometry.Offset(0f, size.height * (1f - (series[0] - min) / span))
        for (i in 1 until series.size) {
            val p = androidx.compose.ui.geometry.Offset(i * stepX, size.height * (1f - (series[i] - min) / span))
            drawLine(color = color, start = prev, end = p, strokeWidth = 3f)
            prev = p
        }
    }
}

/** Wall-clock widget (ticking every second); 24 h by default, 12 h via the h24 option. */
@Composable
private fun ClockTile(scale: Float, options: Map<String, String>) {
    val h24 = options[HudOption.H24] != "0"
    val pattern = if (h24) "HH:mm" else "h:mm a"
    val time by androidx.compose.runtime.produceState(initialValue = "", key1 = pattern) {
        val fmt = java.time.format.DateTimeFormatter.ofPattern(pattern)
        while (true) {
            value = java.time.LocalTime.now().format(fmt)
            kotlinx.coroutines.delay(1000)
        }
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB0000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(HudCatalog.byId(HudCatalog.WIDGET_CLOCK)!!.labelRes).uppercase(),
            color = Color(0xFFB8C4CE),
            fontSize = (11 * scale).sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = time,
            color = optionColor(options),
            fontSize = (30 * scale).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
        )
    }
}

@Composable
private fun RecenterControl(controls: HudControls, scale: Float) {
    val tint = if (controls.followEnabled) Color(0xFFFFD166) else Color.White
    RoundButton(scale = scale, onClick = controls.onRecenter) {
        Icon(Icons.Filled.MyLocation, contentDescription = androidx.compose.ui.res.stringResource(cat.rumb.app.R.string.viewer_cd_center), tint = tint, modifier = Modifier.size((22 * scale).dp))
    }
}

@Composable
private fun CompassControl(controls: HudControls, bearingDeg: Double?, scale: Float) {
    RoundButton(scale = scale, onClick = controls.onNorth) {
        Icon(
            Icons.Filled.Navigation,
            contentDescription = androidx.compose.ui.res.stringResource(cat.rumb.app.R.string.viewer_cd_north),
            tint = Color.White,
            modifier = Modifier.size((22 * scale).dp).rotate((bearingDeg ?: 0.0).toFloat()),
        )
    }
}

@Composable
private fun RecordControl(controls: HudControls, isRecording: Boolean, isPaused: Boolean, scale: Float) {
    if (!isRecording) {
        // Filled red circle = start recording.
        RoundButton(scale = scale, onClick = controls.onStartRecording) {
            Box(
                Modifier
                    .size((20 * scale).dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE63946)),
            )
        }
        return
    }
    // Recording: pause/resume + stop, stacked like the zoom control.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundButton(
            scale = scale,
            onClick = { if (isPaused) controls.onResumeRecording() else controls.onPauseRecording() },
        ) {
            Icon(
                if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = androidx.compose.ui.res.stringResource(
                    if (isPaused) cat.rumb.app.R.string.viewer_cd_resume else cat.rumb.app.R.string.viewer_cd_pause,
                ),
                tint = if (isPaused) Color(0xFFFFD166) else Color.White,
                modifier = Modifier.size((22 * scale).dp),
            )
        }
        RoundButton(scale = scale, onClick = controls.onStopRecording) {
            // Red square = stop.
            Box(
                Modifier
                    .size((20 * scale).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE63946)),
            )
        }
    }
}

@Composable
private fun ZoomControl(controls: HudControls, scale: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundButton(scale = scale, onClick = controls.onZoomIn) {
            Icon(Icons.Filled.Add, contentDescription = androidx.compose.ui.res.stringResource(cat.rumb.app.R.string.viewer_cd_zoom_in), tint = Color.White, modifier = Modifier.size((22 * scale).dp))
        }
        RoundButton(scale = scale, onClick = controls.onZoomOut) {
            Icon(Icons.Filled.Remove, contentDescription = androidx.compose.ui.res.stringResource(cat.rumb.app.R.string.viewer_cd_zoom_out), tint = Color.White, modifier = Modifier.size((22 * scale).dp))
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
