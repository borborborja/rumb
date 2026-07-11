package cat.hudpro.opentracks.viewer.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val AccentSpeed = Color(0xFFFFD166)
private val AccentElev = Color(0xFF8ECAE6)
private val LabelColor = Color(0xFFB8C4CE)

/** Live sparkline of recent speed (km/h). */
@Composable
fun SpeedSparkline(series: List<Float>, scale: Float, modifier: Modifier = Modifier) {
    ChartCard(
        title = "VELOCITAT",
        subtitle = series.lastOrNull()?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "—",
        scale = scale,
        modifier = modifier,
    ) { w, h -> drawSeries(series, w, h, AccentSpeed, baselineZero = true) }
}

/**
 * Elevation profile of the followed route (m) with a progress marker. Empty when not following a
 * route with elevation data.
 */
@Composable
fun ElevationProfile(profile: List<Float>, progress: Float, scale: Float, modifier: Modifier = Modifier) {
    val subtitle = if (profile.isEmpty()) {
        "sense ruta"
    } else {
        "${profile.minOrNull()?.toInt()}–${profile.maxOrNull()?.toInt()} m"
    }
    ChartCard(title = "PERFIL ALTITUD", subtitle = subtitle, scale = scale, modifier = modifier) { w, h ->
        if (profile.isNotEmpty()) {
            drawSeries(profile, w, h, AccentElev, baselineZero = false)
            val x = progress.coerceIn(0f, 1f) * w
            drawLine(Color.White, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    scale: Float,
    modifier: Modifier = Modifier,
    draw: DrawScope.(width: Float, height: Float) -> Unit,
) {
    Column(
        modifier
            .width((150 * scale).dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB0000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(title, color = LabelColor, fontSize = (11 * scale).sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Color.White, fontSize = (15 * scale).sp, fontWeight = FontWeight.SemiBold)
        Canvas(Modifier.fillMaxWidth().height((44 * scale).dp).padding(top = 4.dp)) {
            draw(size.width, size.height)
        }
    }
}

private fun DrawScope.drawSeries(series: List<Float>, w: Float, h: Float, color: Color, baselineZero: Boolean) {
    if (series.size < 2) return
    val min = if (baselineZero) 0f else (series.minOrNull() ?: 0f)
    val max = series.maxOrNull() ?: 1f
    val range = (max - min).takeIf { it > 0.0001f } ?: 1f
    val stepX = w / (series.size - 1)
    fun px(i: Int) = i * stepX
    fun py(v: Float) = h - ((v - min) / range) * h

    val line = Path().apply {
        moveTo(px(0), py(series[0]))
        for (i in 1 until series.size) lineTo(px(i), py(series[i]))
    }
    val area = Path().apply {
        addPath(line)
        lineTo(px(series.size - 1), h)
        lineTo(px(0), h)
        close()
    }
    drawPath(area, color.copy(alpha = 0.25f))
    drawPath(line, color, style = Stroke(width = 3f))
}
