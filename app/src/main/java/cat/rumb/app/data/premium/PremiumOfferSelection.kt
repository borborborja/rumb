package cat.rumb.app.data.premium

data class PremiumPricePhase(
    val formattedPrice: String,
    val billingPeriod: String,
    val infiniteRecurring: Boolean,
)

/** Store-provided plan data, separated from Android billing objects for policy tests. */
data class PremiumOfferCandidate(
    val basePlanId: String,
    val offerId: String?,
    val offerToken: String,
    val phases: List<PremiumPricePhase>,
)

data class SelectedPremiumOffer(val offer: PremiumOffer, val token: String)

object PremiumOfferSelection {
    /** Only plain recurring plans can use the current monthly/yearly disclosure. */
    fun select(candidate: PremiumOfferCandidate): SelectedPremiumOffer? {
        val plan = PremiumPlan.entries.firstOrNull { it.basePlanId == candidate.basePlanId } ?: return null
        val phase = candidate.phases.singleOrNull() ?: return null
        if (candidate.offerId != null || candidate.offerToken.isBlank() || phase.formattedPrice.isBlank() ||
            phase.billingPeriod != plan.billingPeriod || !phase.infiniteRecurring
        ) return null
        return SelectedPremiumOffer(PremiumOffer(plan, phase.formattedPrice), candidate.offerToken)
    }
}
