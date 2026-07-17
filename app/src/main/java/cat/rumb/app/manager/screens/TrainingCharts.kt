package cat.rumb.app.manager.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.rumb.app.R
import cat.rumb.app.data.tracks.TrackSample

private val ELEVATION_COLOR = Color(0xFF3A86FF)
private val SPEED_COLOR = Color(0xFF2A9D8F)
private val HR_COLOR = Color(0xFFE63946)

/** One chart lane: a series over the shared distance axis plus its presentation. */
private class Lane(
    val labelRes: Int,
    val color: Color,
    val values: List<Float?>,
    val filled: Boolean,
    val unit: String,
) {
    val min: Float = values.filterNotNull().minOrNull() ?: 0f
    val max: Float = values.filterNotNull().maxOrNull() ?: 0f
}

/**
 * Up to three stacked lanes (elevation / speed / HR) over a shared x-axis of cumulative distance.
 * A lane is shown only when its series has at least one value. Drag or tap anywhere to scrub:
 * [onScrub] receives the horizontal fraction (0..1), null when the gesture ends.
 *
 * [maxLanes] trims the stack for a short strip that is there to be dragged rather than read. It
 * keeps whichever lanes DO have data, so a track without elevation still offers a scrub surface
 * instead of collapsing to nothing.
 */
@Composable
fun StackedTrackChart(
    samples: List<TrackSample>,
    highlightFraction: Float?,
    onScrub: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    maxLanes: Int = 3,
) {
    if (samples.size < 2) return
    val totalDist = samples.last().distM.takeIf { it > 0.0 } ?: return
    val fractions = remember(samples) { samples.map { (it.distM / totalDist).toFloat() } }

    val lanes = remember(samples, maxLanes) {
        listOf(
            Lane(R.string.training_chart_elevation, ELEVATION_COLOR, samples.map { it.elevation }, filled = true, unit = "m"),
            Lane(R.string.training_chart_speed, SPEED_COLOR, samples.map { it.speedKmh }, filled = false, unit = "km/h"),
            Lane(R.string.training_chart_hr, HR_COLOR, samples.map { it.hr }, filled = false, unit = "bpm"),
        ).filter { lane -> lane.values.any { it != null } }.take(maxLanes.coerceAtLeast(1))
    }
    if (lanes.isEmpty()) return

    Column(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { pos ->
                    onScrub((pos.x / size.width).coerceIn(0f, 1f))
                })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { pos -> onScrub((pos.x / size.width).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onScrub((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                )
            },
    ) {
        lanes.forEach { lane ->
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawLane(lane, fractions, highlightFraction)
                }
                Text(
                    stringResource(lane.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = lane.color,
                    modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    "${lane.min.toInt()}–${lane.max.toInt()} ${lane.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun DrawScope.drawLane(lane: Lane, fractions: List<Float>, highlightFraction: Float?) {
    val w = size.width
    val h = size.height
    val padTop = h * 0.18f
    val padBottom = h * 0.08f
    val span = (lane.max - lane.min).takeIf { it > 0f } ?: 1f // flat-series guard

    fun yOf(v: Float): Float = padTop + (1f - (v - lane.min) / span) * (h - padTop - padBottom)

    val line = Path()
    var started = false
    var firstX = 0f
    var lastX = 0f
    for (i in fractions.indices) {
        val v = lane.values[i] ?: continue // connect over gaps
        val x = fractions[i] * w
        val y = yOf(v)
        if (!started) {
            line.moveTo(x, y)
            firstX = x
            started = true
        } else {
            line.lineTo(x, y)
        }
        lastX = x
    }
    if (!started) return

    if (lane.filled) {
        val area = Path().apply {
            addPath(line)
            lineTo(lastX, h)
            lineTo(firstX, h)
            close()
        }
        drawPath(area, lane.color.copy(alpha = 0.25f))
    }
    drawPath(line, lane.color, style = Stroke(width = 2.dp.toPx()))

    if (highlightFraction != null) {
        val x = highlightFraction.coerceIn(0f, 1f) * w
        drawLine(Color.White, Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f.dp.toPx())
    }
}
