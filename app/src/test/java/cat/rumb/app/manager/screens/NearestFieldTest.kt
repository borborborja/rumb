package cat.rumb.app.manager.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NearestFieldTest {

    private val bounds = mapOf(
        "A" to Rect(0f, 0f, 100f, 100f),
        "B" to Rect(110f, 0f, 210f, 100f),
        "C" to Rect(0f, 110f, 100f, 210f),
    )

    @Test
    fun directHitWins() {
        assertThat(nearestField(bounds, Offset(50f, 50f))).isEqualTo("A")
        assertThat(nearestField(bounds, Offset(160f, 50f))).isEqualTo("B")
    }

    @Test
    fun draggedTileIsNotExcludedSoItResolvesToItself() {
        // The finger over A resolves to A (the dragged tile) → "no move"; this is what stops the
        // row oscillation that previously trapped upward drags.
        assertThat(nearestField(bounds, Offset(50f, 50f))).isEqualTo("A")
    }

    @Test
    fun climbsToUpperRowByEuclideanDistance() {
        assertThat(nearestField(bounds, Offset(40f, 130f))).isEqualTo("C") // still inside C
        assertThat(nearestField(bounds, Offset(40f, 95f))).isEqualTo("A") // moved up into A's band
    }

    @Test
    fun gapFallsBackToNearestCenter() {
        assertThat(nearestField(bounds, Offset(105f, 50f))).isEqualTo("A")
        assertThat(nearestField(bounds, Offset(109f, 50f))).isEqualTo("B")
    }

    @Test
    fun farPointerReturnsGloballyNearest() {
        // No vertical-band restriction any more: a far pointer resolves to the nearest tile.
        assertThat(nearestField(bounds, Offset(50f, 500f))).isEqualTo("C")
    }
}
