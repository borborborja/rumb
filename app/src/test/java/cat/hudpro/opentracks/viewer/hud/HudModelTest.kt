package cat.hudpro.opentracks.viewer.hud

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HudModelTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun catalogResolvesEveryPlacedElementId() {
        val ids = (HudLayout.CYCLING.widgets + HudLayout.TRAIL.widgets + HudLayout.SKI.widgets)
            .map { it.elementId }.toSet()
        ids.forEach { id -> assertThat(HudCatalog.byId(id)).describedAs(id).isNotNull() }
    }

    @Test
    fun catalogHasMetricsChartsAndControls() {
        assertThat(HudCatalog.byId(HudCatalog.idOf(HudMetric.SPEED))?.category).isEqualTo(HudCategory.METRIC)
        assertThat(HudCatalog.byId(HudCatalog.CHART_SPEED)?.category).isEqualTo(HudCategory.CHART)
        assertThat(HudCatalog.byId(HudCatalog.CONTROL_RECENTER)?.category).isEqualTo(HudCategory.CONTROL)
        assertThat(HudCatalog.byId(HudCatalog.CONTROL_RECORD)?.category).isEqualTo(HudCategory.CONTROL)
    }

    @Test
    fun layoutSerializationRoundTripsWithZones() {
        val layout = HudLayout.CYCLING.copy(scale = 1.2f)
        val encoded = json.encodeToString(HudLayout.serializer(), layout)
        val decoded = json.decodeFromString(HudLayout.serializer(), encoded)
        assertThat(decoded).isEqualTo(layout)
        assertThat(decoded.widgets.first().zone).isEqualTo(layout.widgets.first().zone)
    }

    @Test
    fun addIsIdempotentAndRemoveWorks() {
        val base = HudLayout()
        val once = base.add(HudCatalog.CHART_SPEED, HudZone.TOP_LEFT)
        val twice = once.add(HudCatalog.CHART_SPEED, HudZone.BOTTOM_RIGHT)
        assertThat(twice.widgets).hasSize(1)
        assertThat(twice.widgets[0].zone).isEqualTo(HudZone.TOP_LEFT) // not re-added/moved
        assertThat(twice.remove(HudCatalog.CHART_SPEED).widgets).isEmpty()
    }

    @Test
    fun moveToZoneReassigns() {
        val layout = HudLayout().add(HudCatalog.CONTROL_ZOOM, HudZone.TOP_LEFT)
        val moved = layout.moveToZone(0, HudZone.BOTTOM_CENTER)
        assertThat(moved.widgets[0].zone).isEqualTo(HudZone.BOTTOM_CENTER)
    }

    @Test
    fun byZoneGroupsWidgets() {
        assertThat(HudLayout.CYCLING.byZone(HudZone.BOTTOM_LEFT).map { it.elementId })
            .containsExactly(HudCatalog.idOf(HudMetric.SPEED), HudCatalog.idOf(HudMetric.AVG_SPEED))
    }

    @Test
    fun setWidgetScaleClamps() {
        val layout = HudLayout.CYCLING
        assertThat(layout.setWidgetScale(0, 5f).widgets[0].scale).isEqualTo(HudLayout.MAX_WIDGET_SCALE)
        assertThat(layout.setWidgetScale(0, 0.1f).widgets[0].scale).isEqualTo(HudLayout.MIN_WIDGET_SCALE)
        assertThat(layout.setWidgetScale(0, 1.5f).widgets[0].scale).isEqualTo(1.5f)
        assertThat(layout.setWidgetScale(99, 1.5f)).isEqualTo(layout) // out of range → no-op
    }

    @Test
    fun zoneForPointMapsThirdsGrid() {
        val w = 900f
        val h = 900f
        assertThat(zoneForPoint(10f, 10f, w, h)).isEqualTo(HudZone.TOP_LEFT)
        assertThat(zoneForPoint(450f, 10f, w, h)).isEqualTo(HudZone.TOP_CENTER)
        assertThat(zoneForPoint(890f, 10f, w, h)).isEqualTo(HudZone.TOP_RIGHT)
        assertThat(zoneForPoint(10f, 450f, w, h)).isEqualTo(HudZone.MIDDLE_LEFT)
        assertThat(zoneForPoint(890f, 450f, w, h)).isEqualTo(HudZone.MIDDLE_RIGHT)
        assertThat(zoneForPoint(10f, 890f, w, h)).isEqualTo(HudZone.BOTTOM_LEFT)
        assertThat(zoneForPoint(450f, 890f, w, h)).isEqualTo(HudZone.BOTTOM_CENTER)
        assertThat(zoneForPoint(890f, 890f, w, h)).isEqualTo(HudZone.BOTTOM_RIGHT)
        // Center-center resolves to a side by the half the point falls in.
        assertThat(zoneForPoint(430f, 450f, w, h)).isEqualTo(HudZone.MIDDLE_LEFT)
        assertThat(zoneForPoint(470f, 450f, w, h)).isEqualTo(HudZone.MIDDLE_RIGHT)
    }

    @Test
    fun setWidgetOptionAddsAndClears() {
        val layout = HudLayout.CYCLING
        val colored = layout.setWidgetOption(0, HudOption.COLOR, "#FFD166")
        assertThat(colored.widgets[0].options[HudOption.COLOR]).isEqualTo("#FFD166")
        val cleared = colored.setWidgetOption(0, HudOption.COLOR, null)
        assertThat(cleared.widgets[0].options).doesNotContainKey(HudOption.COLOR)
        assertThat(layout.setWidgetOption(99, HudOption.COLOR, "#FFFFFF")).isEqualTo(layout)
    }

    @Test
    fun legacyWidgetJsonWithoutOptionsDecodes() {
        val legacy = """{"widgets":[{"elementId":"metric:SPEED","zone":"TOP_LEFT","scale":1.0}],"scale":1.0}"""
        val decoded = json.decodeFromString(HudLayout.serializer(), legacy)
        assertThat(decoded.widgets[0].options).isEmpty()
    }

    @Test
    fun optionsRoundTripThroughJson() {
        val layout = HudLayout(
            widgets = listOf(
                HudWidget(HudCatalog.WIDGET_CLOCK, HudZone.TOP_RIGHT, options = mapOf(HudOption.H24 to "0")),
            ),
        )
        val restored = json.decodeFromString(HudLayout.serializer(), json.encodeToString(HudLayout.serializer(), layout))
        assertThat(restored).isEqualTo(layout)
    }
}
