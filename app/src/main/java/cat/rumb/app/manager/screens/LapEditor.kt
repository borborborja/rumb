package cat.rumb.app.manager.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.rumb.app.R
import cat.rumb.app.data.gpx.GpxPoint
import cat.rumb.app.data.tracks.LapEdits
import cat.rumb.app.data.tracks.LapRange
import cat.rumb.app.data.tracks.TrackSample
import cat.rumb.app.viewer.hud.MetricsCalculator

/**
 * Graphical lap-boundary editor: the track's elevation profile with a draggable vertical handle at
 * each lap boundary. Dragging a handle snaps it to the nearest track point (clamped between its
 * neighbours), so a mistimed lap button can be corrected visually. Tapping the profile places a
 * cursor to add a split there; tapping a handle selects it so it can be removed (merging two laps).
 * Boundaries are shared between adjacent ranges (approach/lap/return), so moving one resizes both
 * sides. Add/remove only touch LAP segments, so the approach and return are always preserved.
 */
@Composable
fun LapBoundaryEditor(
    samples: List<TrackSample>,
    points: List<GpxPoint>,
    ranges: List<LapRange>,
    onSave: (List<LapRange>) -> Unit,
    onCancel: () -> Unit,
) {
    // Cumulative distance along the full-resolution points (lap indices are into this list).
    val pointDist = remember(points) {
        val out = DoubleArray(points.size)
        var acc = 0.0
        for (i in points.indices) {
            if (i > 0) acc += MetricsCalculator.distanceMeters(points[i - 1].toGeoPoint(), points[i].toGeoPoint())
            out[i] = acc
        }
        out
    }
    val total = (pointDist.lastOrNull() ?: 0.0).coerceAtLeast(1.0)
    // cuts: boundary point-indices (size N+1). segKinds: the kind of each segment between two cuts
    // (size N). Both grow/shrink together on add/remove.
    var cuts by remember(ranges) {
        mutableStateOf(ranges.map { it.startIdx } + (ranges.lastOrNull()?.endIdx ?: points.size))
    }
    var segKinds by remember(ranges) { mutableStateOf(ranges.map { it.kind }) }
    var dragging by remember { mutableStateOf(-1) }
    var selectedCut by remember { mutableStateOf(-1) } // internal cut selected for removal
    var cursor by remember { mutableStateOf(-1) }       // point index under the tap, candidate for add
    var confirmClear by remember { mutableStateOf(false) }

    val lineColor = MaterialTheme.colorScheme.primary
    val elevColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cursorColor = MaterialTheme.colorScheme.tertiary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.laps_edit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(points, cuts.size) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val w = size.width
                                dragging = (1 until cuts.size - 1).minByOrNull {
                                    kotlin.math.abs((pointDist[cuts[it]] / total * w).toFloat() - pos.x)
                                } ?: -1
                            },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 },
                            onDrag = { change, _ ->
                                change.consume()
                                val d = dragging
                                if (d in 1 until cuts.size - 1) {
                                    val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val targetDist = frac * total
                                    var idx = pointDist.indices.minByOrNull { kotlin.math.abs(pointDist[it] - targetDist) } ?: cuts[d]
                                    val lo = cuts[d - 1] + 1
                                    val hi = cuts[d + 1] - 1
                                    // Guard against non-contiguous cuts from corrupt data (lo > hi would
                                    // make coerceIn throw); a healthy lap set always has lo <= hi.
                                    if (lo <= hi) {
                                        idx = idx.coerceIn(lo, hi)
                                        cuts = cuts.toMutableList().also { it[d] = idx }
                                    }
                                }
                            },
                        )
                    }
                    .pointerInput(points, cuts.size) {
                        detectTapGestures { pos ->
                            val w = size.width
                            // Tap on/near an existing internal handle → select it (for removal).
                            val nearest = (1 until cuts.size - 1).minByOrNull {
                                kotlin.math.abs((pointDist[cuts[it]] / total * w).toFloat() - pos.x)
                            }
                            val nearPx = nearest?.let {
                                kotlin.math.abs((pointDist[cuts[it]] / total * w).toFloat() - pos.x)
                            } ?: Float.MAX_VALUE
                            if (nearest != null && nearPx <= 28f) {
                                selectedCut = nearest; cursor = -1
                            } else {
                                // Empty area → drop an add-cursor at the nearest track point.
                                val targetDist = (pos.x / w).coerceIn(0f, 1f) * total
                                cursor = pointDist.indices.minByOrNull { kotlin.math.abs(pointDist[it] - targetDist) } ?: -1
                                selectedCut = -1
                            }
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                // Elevation profile (by distance) so it aligns with the distance-based handles.
                val elev = samples.mapNotNull { s -> s.elevation?.let { s.distM to it } }
                if (elev.size >= 2) {
                    val sampleTotal = (samples.lastOrNull()?.distM ?: 0.0).coerceAtLeast(1.0)
                    val min = elev.minOf { it.second }
                    val max = elev.maxOf { it.second }
                    val range = (max - min).takeIf { it > 0.01f } ?: 1f
                    fun px(d: Double) = (d / sampleTotal * w).toFloat()
                    fun py(v: Float) = h - (v - min) / range * h
                    val line = Path().apply {
                        moveTo(px(elev[0].first), py(elev[0].second))
                        for (i in 1 until elev.size) lineTo(px(elev[i].first), py(elev[i].second))
                    }
                    val area = Path().apply {
                        addPath(line)
                        lineTo(px(elev.last().first), h)
                        lineTo(px(elev.first().first), h)
                        close()
                    }
                    drawPath(area, elevColor.copy(alpha = 0.15f))
                    drawPath(line, elevColor, style = Stroke(width = 2f))
                } else {
                    drawLine(elevColor.copy(alpha = 0.4f), Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 2f)
                }
                // Add-cursor (dashed) where a new split would land.
                if (cursor in points.indices) {
                    val x = (pointDist[cursor] / total * w).toFloat()
                    drawLine(
                        cursorColor, Offset(x, 0f), Offset(x, h), strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                    )
                }
                // Boundary handles.
                for (i in 1 until cuts.size - 1) {
                    val x = (pointDist[cuts[i]] / total * w).toFloat()
                    val active = i == dragging || i == selectedCut
                    drawLine(lineColor, Offset(x, 0f), Offset(x, h), strokeWidth = if (active) 4f else 2f)
                    drawCircle(lineColor, radius = if (active) 12f else 9f, center = Offset(x, h / 2))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = {
                LapEdits.addSplit(cuts, segKinds, cursor)?.let { (c, k) ->
                    cuts = c; segKinds = k; cursor = -1
                }
            }, enabled = LapEdits.canAdd(cuts, segKinds, cursor)) { Text(stringResource(R.string.laps_edit_add)) }
            TextButton(onClick = {
                LapEdits.removeCut(cuts, segKinds, selectedCut)?.let { (c, k) ->
                    cuts = c; segKinds = k; selectedCut = -1
                }
            }, enabled = LapEdits.canRemove(cuts, segKinds, selectedCut)) { Text(stringResource(R.string.laps_edit_remove)) }
            // Merging bottoms out at one lap (a cut needs a LAP on both sides), so clearing is its own
            // action — the only way to turn a lapped track back into a plain one.
            TextButton(onClick = { confirmClear = true }) {
                Text(stringResource(R.string.laps_edit_clear), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.home_cancel)) }
            TextButton(onClick = { onSave(LapEdits.rebuild(cuts, segKinds)) }) { Text(stringResource(R.string.home_save)) }
        }
    }

    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.laps_edit_clear)) },
            // One-way: with no laps the section (and this editor) disappears, so there's no re-entry.
            text = { Text(stringResource(R.string.laps_edit_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onSave(emptyList()) }) {
                    Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
}
