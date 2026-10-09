package cat.rumb.app.data.premium

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PremiumEntitlementsTest {
    private val valid = PremiumPurchaseSnapshot(
        "cat.rumb.app", listOf(PREMIUM_PRODUCT_ID), PremiumPurchaseStatus.PURCHASED, true,
    )

    @Test fun verifiedCurrentOwnershipGrantsAccess() {
        assertThat(PremiumEntitlements.hasPremium(listOf(valid), "cat.rumb.app")).isTrue()
    }

    @Test fun pendingAndSuspendedSubscriptionsNeverGrantAccess() {
        assertThat(PremiumEntitlements.isEligible(valid.copy(status = PremiumPurchaseStatus.PENDING), "cat.rumb.app")).isFalse()
        assertThat(PremiumEntitlements.isEligible(valid.copy(suspended = true), "cat.rumb.app")).isFalse()
        assertThat(PremiumEntitlements.isEligible(valid.copy(status = PremiumPurchaseStatus.UNSPECIFIED), "cat.rumb.app")).isFalse()
    }

    @Test fun wrongPackageProductOrInvalidSignatureNeverGrantsAccess() {
        assertThat(PremiumEntitlements.isEligible(valid.copy(packageName = "other.app"), "cat.rumb.app")).isFalse()
        assertThat(PremiumEntitlements.isEligible(valid.copy(products = listOf("other_product")), "cat.rumb.app")).isFalse()
        assertThat(PremiumEntitlements.isEligible(valid.copy(signatureVerified = false), "cat.rumb.app")).isFalse()
    }

    @Test fun authoritativeEmptyOwnershipRevokesPremium() {
        assertThat(PremiumEntitlements.hasPremium(emptyList(), "cat.rumb.app")).isFalse()
    }

    @Test fun oneEligiblePurchaseIsEnoughAndPendingCannotMaskIt() {
        assertThat(PremiumEntitlements.hasPremium(listOf(valid.copy(status = PremiumPurchaseStatus.PENDING), valid), "cat.rumb.app")).isTrue()
    }
}
