package cat.rumb.app.data.premium

/** The store owns prices and eligibility; this model contains no assumed checkout price. */
enum class PremiumPlan(val basePlanId: String, val billingPeriod: String) {
    MONTHLY("monthly", "P1M"),
    ANNUAL("annual", "P1Y"),
}

data class PremiumOffer(val plan: PremiumPlan, val formattedPrice: String)

enum class PremiumNotice {
    STORE_UNAVAILABLE, PRICES_UNAVAILABLE, PURCHASE_CANCELED, PURCHASE_PENDING,
    VERIFICATION_FAILED, SUBSCRIPTION_SUSPENDED, ACKNOWLEDGEMENT_RETRY,
    RESTORED, NO_SUBSCRIPTION, PURCHASE_FAILED,
}

data class PremiumState(
    val hasPremium: Boolean = false,
    val subscriptionActive: Boolean = false,
    val accessCodeActive: Boolean = false,
    /** The latest ownership query succeeded; false while refreshing or after a query error. */
    val ownershipVerified: Boolean = false,
    val isLoading: Boolean = false,
    val isPurchasing: Boolean = false,
    val available: Boolean = true,
    val plans: List<PremiumOffer> = emptyList(),
    val notice: PremiumNotice? = null,
)

const val PREMIUM_PRODUCT_ID = "rumb_premium"

enum class PremiumPurchaseStatus { PURCHASED, PENDING, UNSPECIFIED }

/** Input from a current Google Play ownership query, never from an unsigned preference. */
data class PremiumPurchaseSnapshot(
    val packageName: String,
    val products: List<String>,
    val status: PremiumPurchaseStatus,
    val signatureVerified: Boolean,
    val suspended: Boolean = false,
)

object PremiumEntitlements {
    fun isEligible(purchase: PremiumPurchaseSnapshot, expectedPackage: String): Boolean =
        purchase.signatureVerified && purchase.packageName == expectedPackage &&
            PREMIUM_PRODUCT_ID in purchase.products &&
            purchase.status == PremiumPurchaseStatus.PURCHASED && !purchase.suspended

    /** An authoritative empty result revokes access; query errors must not call this reducer. */
    fun hasPremium(purchases: List<PremiumPurchaseSnapshot>, expectedPackage: String): Boolean =
        purchases.any { isEligible(it, expectedPackage) }
}
