package cat.rumb.app.data.premium

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PremiumOfferSelectionTest {
    private val monthly = PremiumOfferCandidate("monthly", null, "store-token", listOf(
        PremiumPricePhase("€2.00", "P1M", true),
    ))

    @Test fun theStorePriceAndTokenArePreservedExactly() {
        val offer = PremiumOfferSelection.select(monthly.copy(phases = listOf(
            PremiumPricePhase("2,19 €", "P1M", true),
        )))
        assertThat(offer).isEqualTo(SelectedPremiumOffer(PremiumOffer(PremiumPlan.MONTHLY, "2,19 €"), "store-token"))
    }

    @Test fun annualPlanRequiresAnAnnualRecurringPhase() {
        val annual = monthly.copy(basePlanId = "annual", phases = listOf(PremiumPricePhase("€20.00", "P1Y", true)))
        assertThat(PremiumOfferSelection.select(annual)?.offer?.plan).isEqualTo(PremiumPlan.ANNUAL)
        assertThat(PremiumOfferSelection.select(monthly.copy(basePlanId = "annual"))).isNull()
    }

    @Test fun trialDiscountAndPrepaidPlansCannotUseTheRecurringPriceDisclosure() {
        assertThat(PremiumOfferSelection.select(monthly.copy(offerId = "trial"))).isNull()
        assertThat(PremiumOfferSelection.select(monthly.copy(phases = listOf(
            PremiumPricePhase("€0.00", "P1M", false), monthly.phases.single(),
        )))).isNull()
        assertThat(PremiumOfferSelection.select(monthly.copy(phases = listOf(
            monthly.phases.single().copy(infiniteRecurring = false),
        )))).isNull()
    }

    @Test fun unknownAndIncompletePlansAreNotOfferedForPurchase() {
        assertThat(PremiumOfferSelection.select(monthly.copy(basePlanId = "unknown"))).isNull()
        assertThat(PremiumOfferSelection.select(monthly.copy(offerToken = ""))).isNull()
        assertThat(PremiumOfferSelection.select(monthly.copy(phases = emptyList()))).isNull()
        assertThat(PremiumOfferSelection.select(monthly.copy(phases = listOf(
            monthly.phases.single().copy(formattedPrice = ""),
        )))).isNull()
    }
}
