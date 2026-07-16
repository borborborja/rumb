package cat.rumb.app.data.map

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * The pure logic behind the per-map display config. The JSON assembly in [MapStyleFactory] itself
 * uses Android's org.json (a throwing stub under plain JVM), so the produced style string is
 * verified on-device; everything here — the zoom maths, the identity guarantee, the JSON round-trip
 * — is pure Kotlin and carries the actual risk.
 */
class MapStyleFactoryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun defaultIsTheIdentity() {
        // The whole design rests on this: an unedited map behaves exactly as before.
        val d = MapDisplayConfig.DEFAULT
        assertThat(d.isIdentity).isTrue()
        assertThat(d.effectiveMaxZoom(20)).isEqualTo(20) // no cap
        assertThat(d.grayscale).isFalse()
        assertThat(d.opacity).isEqualTo(1f)
    }

    @Test
    fun detailReductionCapsMaxzoomButNeverBelowTheFloor() {
        assertThat(MapDisplayConfig(detailReduction = 3).effectiveMaxZoom(20)).isEqualTo(17)
        assertThat(MapDisplayConfig(detailReduction = 99).effectiveMaxZoom(17))
            .isEqualTo(MapDisplayConfig.MIN_DISPLAY_ZOOM) // a huge reduction still leaves a city map
        assertThat(MapDisplayConfig(detailReduction = -5).effectiveMaxZoom(20)).isEqualTo(20) // clamped ≥ 0
    }

    @Test
    fun anyNonNeutralOptionMakesItNonIdentity() {
        assertThat(MapDisplayConfig(detailReduction = 1).isIdentity).isFalse()
        assertThat(MapDisplayConfig(grayscale = true).isIdentity).isFalse()
        assertThat(MapDisplayConfig(opacity = 0.6f).isIdentity).isFalse()
    }

    @Test
    fun detailNotchesReflectEachSourcesHeadroom() {
        // "Only maps that permit it": headroom = nativeMax − floor, capped at 4.
        assertThat(MapDisplayConfig.maxDetailReductionFor(20)).isEqualTo(4) // ICGC
        assertThat(MapDisplayConfig.maxDetailReductionFor(17)).isEqualTo(4) // OpenTopoMap
        assertThat(MapDisplayConfig.maxDetailReductionFor(12)).isEqualTo(1) // barely any room
        assertThat(MapDisplayConfig.maxDetailReductionFor(11)).isEqualTo(0) // none — no slider
    }

    @Test
    fun configRoundTripsThroughJson() {
        val c = MapDisplayConfig(detailReduction = 2, grayscale = true, opacity = 0.5f)
        val decoded = json.decodeFromString(MapDisplayConfig.serializer(), json.encodeToString(MapDisplayConfig.serializer(), c))
        assertThat(decoded).isEqualTo(c)
    }

    @Test
    fun opacityRoundTripKeepsPrecision() {
        val c = MapDisplayConfig(opacity = 0.35f)
        val decoded = json.decodeFromString(MapDisplayConfig.serializer(), json.encodeToString(MapDisplayConfig.serializer(), c))
        assertThat(decoded.opacity).isEqualTo(0.35f, Offset.offset(1e-6f))
    }
}
