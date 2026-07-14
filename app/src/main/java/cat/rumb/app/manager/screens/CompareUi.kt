package cat.rumb.app.manager.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cat.rumb.app.data.competition.GapSample
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Shared compare/analysis UI, reused by CompetitionDetailScreen and CompareScreen. Green = the
// selected entry is ahead of the baseline; red = behind (gap seconds > 0 means slower).
internal val GapGreen = Color(0x552ECC71)
internal val GapRed = Color(0x55E63946)
internal val GapGreenSolid = Color(0xFF2ECC71)
internal val GapRedSolid = Color(0xFFE63946)

/** Gap-over-distance chart: sign-colored bars from a zero line + the gap polyline. */
@Composable
internal fun GapChart(series: List<GapSample>, modifier: Modifier) {
    val lineColor = MaterialTheme.colorScheme.onSurface
    val zeroColor = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        if (series.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val maxDist = series.last().distM.coerceAtLeast(1.0)
        val maxAbs = series.maxOf { kotlin.math.abs(it.gapSeconds) }.coerceAtLeast(1.0)
        val zeroY = h / 2f
        fun x(d: Double): Float = (d / maxDist * w).toFloat()
        fun y(gap: Double): Float = zeroY - (gap / maxAbs * (h / 2f)).toFloat()

        val barW = (w / series.size).coerceAtLeast(1f)
        series.forEach { s ->
            if (s.gapSeconds == 0.0) return@forEach
            val top = minOf(zeroY, y(s.gapSeconds))
            val bottom = maxOf(zeroY, y(s.gapSeconds))
            drawRect(
                color = if (s.gapSeconds > 0) GapRed else GapGreen,
                topLeft = Offset(x(s.distM) - barW / 2f, top),
                size = Size(barW, bottom - top),
            )
        }
        drawLine(zeroColor, Offset(0f, zeroY), Offset(w, zeroY), strokeWidth = 1f)
        val path = Path()
        series.forEachIndexed { i, s ->
            if (i == 0) path.moveTo(x(s.distM), y(s.gapSeconds)) else path.lineTo(x(s.distM), y(s.gapSeconds))
        }
        drawPath(path, lineColor, style = Stroke(width = 2f))
    }
}

/** Trend bars across a sequence (e.g. per-attempt or per-lap): best entry highlighted. */
@Composable
internal fun EvolutionBars(values: List<Float>, lowerBetter: Boolean, modifier: Modifier) {
    val best = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.surfaceVariant
    val bestIdx = if (lowerBetter) values.indices.minByOrNull { values[it] } else values.indices.maxByOrNull { values[it] }
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0.0001f } ?: 1f
        val n = values.size
        val gap = 6f
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        values.forEachIndexed { i, v ->
            val norm = (v - min) / range
            val hFrac = if (lowerBetter) 1f - norm else norm
            val barH = (0.15f + 0.85f * hFrac) * size.height
            val x = i * (barW + gap)
            drawRect(
                color = if (i == bestIdx) best else muted,
                topLeft = Offset(x, size.height - barH),
                size = Size(barW, barH),
            )
        }
    }
}

@Composable
internal fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(weight),
    )
}

@Composable
internal fun RowScope.BodyCell(
    text: String,
    weight: Float,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = fontWeight,
        modifier = Modifier.weight(weight),
    )
}

internal fun formatDayMonthYearShort(epochMs: Long): String =
    DateTimeFormatter.ofPattern("dd/MM/yy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))

internal fun formatHms(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return String.format("%d:%02d:%02d", h, m, s)
}

internal fun formatDiff(ms: Long): String {
    val sign = if (ms < 0) "−" else "+"
    val totalSeconds = kotlin.math.abs(ms) / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return String.format("%s%d:%02d", sign, m, s)
}
