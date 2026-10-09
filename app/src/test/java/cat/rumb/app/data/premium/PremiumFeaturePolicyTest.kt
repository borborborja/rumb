package cat.rumb.app.data.premium

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PremiumFeaturePolicyTest {
    @Test
    fun `recording and access to existing activities remain free`() {
        for (feature in listOf(PremiumFeature.RECORDING, PremiumFeature.ONLINE_MAPS, PremiumFeature.SAVED_ACTIVITIES, PremiumFeature.EXPORT)) {
            assertThat(PremiumFeaturePolicy.allows(feature, false)).isTrue()
        }
    }

    @Test
    fun `free users cannot start premium actions`() {
        for (feature in PremiumFeature.entries.filter { it.requiresPremium }) {
            assertThat(PremiumFeaturePolicy.allows(feature, false)).isFalse()
            assertThat(PremiumFeaturePolicy.allows(feature, true)).isTrue()
        }
    }

    @Test
    fun `losing entitlement during recording does not interrupt its tools`() {
        for (feature in listOf(PremiumFeature.OFFLINE_MAPS, PremiumFeature.ROUTE_FOLLOWING, PremiumFeature.BLE_SENSORS, PremiumFeature.COMPETITIONS)) {
            assertThat(PremiumFeaturePolicy.allowsContinuation(feature, false, true, true)).isTrue()
            assertThat(PremiumFeaturePolicy.allowsContinuation(feature, false, false, true)).isFalse()
            assertThat(PremiumFeaturePolicy.allowsContinuation(feature, false, true, false)).isFalse()
            assertThat(PremiumFeaturePolicy.allows(feature, false)).isFalse()
        }
    }

    @Test
    fun `recording cannot unlock editing or unrelated paid features`() {
        for (feature in listOf(PremiumFeature.LAYOUT_EDITING, PremiumFeature.CLOUD_SYNC, PremiumFeature.ADVANCED_ANALYSIS, PremiumFeature.WEIGHT)) {
            assertThat(PremiumFeaturePolicy.allowsContinuation(feature, false, true, true)).isFalse()
        }
    }

    @Test
    fun `workers retry unresolved ownership without granting access or declaring free ownership`() {
        assertThat(PremiumFeaturePolicy.workAccess(false, false)).isEqualTo(PremiumWorkAccess.UNKNOWN)
        assertThat(PremiumFeaturePolicy.workAccess(true, false)).isEqualTo(PremiumWorkAccess.UNKNOWN)
        assertThat(PremiumFeaturePolicy.workAccess(false, true)).isEqualTo(PremiumWorkAccess.DENIED)
        assertThat(PremiumFeaturePolicy.workAccess(true, true)).isEqualTo(PremiumWorkAccess.ALLOWED)
    }

    @Test
    fun `a validated free demo credential allows workers without a Play purchase`() {
        assertThat(PremiumFeaturePolicy.workAccess(true, false, true)).isEqualTo(PremiumWorkAccess.ALLOWED)
        assertThat(PremiumFeaturePolicy.workAccess(true, true, true)).isEqualTo(PremiumWorkAccess.ALLOWED)
        assertThat(PremiumFeaturePolicy.workAccess(false, false, false)).isEqualTo(PremiumWorkAccess.UNKNOWN)
    }
}
