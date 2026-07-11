package cat.hudpro.opentracks.viewer.hud

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
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
    }

    @Test
    fun layoutSerializationRoundTrips() {
        val layout = HudLayout.CYCLING.copy(scale = 1.2f)
        val encoded = json.encodeToString(HudLayout.serializer(), layout)
        val decoded = json.decodeFromString(HudLayout.serializer(), encoded)
        assertThat(decoded).isEqualTo(layout)
        assertThat(decoded.widgets.first().x).isEqualTo(layout.widgets.first().x)
    }

    @Test
    fun addIsIdempotentAndRemoveWorks() {
        val base = HudLayout()
        val once = base.add(HudCatalog.CHART_SPEED, 0.1f, 0.2f)
        val twice = once.add(HudCatalog.CHART_SPEED, 0.9f, 0.9f)
        assertThat(twice.widgets).hasSize(1)
        assertThat(twice.contains(HudCatalog.CHART_SPEED)).isTrue()
        assertThat(twice.remove(HudCatalog.CHART_SPEED).widgets).isEmpty()
    }

    @Test
    fun moveToClampsToUnitRange() {
        val layout = HudLayout().add(HudCatalog.CONTROL_ZOOM, 0.5f, 0.5f)
        val moved = layout.moveTo(0, 1.4f, -0.3f)
        assertThat(moved.widgets[0].x).isEqualTo(1.0f, within(0.0001f))
        assertThat(moved.widgets[0].y).isEqualTo(0.0f, within(0.0001f))
    }
}
