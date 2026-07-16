package cat.rumb.app.viewer.hud

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [HudLayout.moveWidget] — the reorder that the HUD editor needs. Order within a zone is the widgets'
 * relative order in the flat list, so moving is a remove-and-reinsert (unlike [HudLayout.moveToZone],
 * which only rewrites the zone and keeps the index → cannot reorder). Pure logic; gestures are
 * device-verified.
 */
class HudLayoutMoveWidgetTest {

    private fun w(id: String, zone: HudZone) = HudWidget(elementId = id, zone = zone)
    private fun order(layout: HudLayout, zone: HudZone) = layout.byZone(zone).map { it.elementId }

    @Test
    fun reordersWithinAZone() {
        val layout = HudLayout(listOf(w("A", HudZone.BOTTOM_LEFT), w("B", HudZone.BOTTOM_LEFT), w("C", HudZone.BOTTOM_LEFT)))
        // Drag A onto C's slot → A leaves the front of the zone. Old moveToZone kept it first: [A,B,C].
        val moved = layout.moveWidget(from = 0, zone = HudZone.BOTTOM_LEFT, targetElementId = "C")
        assertThat(order(moved, HudZone.BOTTOM_LEFT)).isEqualTo(listOf("B", "C", "A"))
    }

    @Test
    fun movesToAnotherZoneNextToASibling() {
        val layout = HudLayout(listOf(w("A", HudZone.BOTTOM_LEFT), w("B", HudZone.TOP_RIGHT), w("C", HudZone.TOP_RIGHT)))
        val moved = layout.moveWidget(from = 0, zone = HudZone.TOP_RIGHT, targetElementId = "C")
        assertThat(order(moved, HudZone.TOP_RIGHT)).isEqualTo(listOf("B", "C", "A"))
        assertThat(order(moved, HudZone.BOTTOM_LEFT)).isEmpty()
        assertThat(moved.widgets.first { it.elementId == "A" }.zone).isEqualTo(HudZone.TOP_RIGHT)
    }

    @Test
    fun nullTargetAppendsToTheZone() {
        val layout = HudLayout(listOf(w("A", HudZone.BOTTOM_LEFT), w("B", HudZone.TOP_RIGHT)))
        val moved = layout.moveWidget(from = 0, zone = HudZone.TOP_RIGHT, targetElementId = null)
        assertThat(order(moved, HudZone.TOP_RIGHT)).isEqualTo(listOf("B", "A"))
    }

    @Test
    fun outOfRangeIndexIsANoOp() {
        val layout = HudLayout(listOf(w("A", HudZone.BOTTOM_LEFT)))
        assertThat(layout.moveWidget(from = 5, zone = HudZone.TOP_RIGHT, targetElementId = null)).isEqualTo(layout)
    }
}
