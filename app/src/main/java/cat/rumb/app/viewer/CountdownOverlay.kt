package cat.rumb.app.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cat.rumb.app.R

/** A lap countdown tick: seconds left to the finish line, and which lap you're counting into. */
data class LapCountdown(val secondsLeft: Int, val lapAhead: Int)

/**
 * Fullscreen countdown: -1 = waiting for a precise GPS fix, 3/2/1 = digits, 0 = GO!.
 *
 * [onCancel] null means there is nothing to cancel (the lap countdown tracks the finish line and
 * stops on its own). Then the overlay must NOT swallow touches — it used to, leaving the map and the
 * pager dead for three seconds — and it hides the "tap to cancel" hint, which did nothing.
 * [subtitle] names what you're counting into, e.g. the lap ahead.
 */
@Composable
fun CountdownOverlay(value: Int, subtitle: String? = null, onCancel: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xC0000000))
            .let {
                if (onCancel == null) it
                else it.clickable(interactionSource = interaction, indication = null, onClick = onCancel)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            value < 0 -> {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.countdown_waiting_gps),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            value == 0 -> key(value) {
                PopText(stringResource(R.string.countdown_go), 120.sp, Color(0xFF2ECC71))
            }
            // Red like a start light, and only for the lap countdown: the pre-recording one is white
            // because nothing is about to be timed against a rival.
            else -> key(value) {
                PopText("$value", 160.sp, if (subtitle != null) Color(0xFFE63946) else Color.White)
            }
        }
        subtitle?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        if (onCancel != null) {
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.countdown_cancel_hint),
                color = Color(0xFF9AA5B1),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Big number/word with a quick pop-in (scale 1.3→1, fade 0→1) each time it appears. */
@Composable
private fun PopText(text: String, size: androidx.compose.ui.unit.TextUnit, color: Color) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 1.3f, tween(250), label = "cd-scale")
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(200), label = "cd-alpha")
    Text(
        text,
        color = color,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.scale(scale).alpha(alpha),
    )
}
