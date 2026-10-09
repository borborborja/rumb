package cat.rumb.app.data.premium

import android.content.Context
import cat.rumb.app.RumbApplication

/** Recheck ownership before deferred paid work; a store outage preserves the queued file. */
suspend fun premiumWorkAccess(context: Context): PremiumWorkAccess {
    val manager = RumbApplication.from(context).premiumManager
    val state = manager.refreshAndAwait() ?: return PremiumWorkAccess.UNKNOWN
    return PremiumFeaturePolicy.workAccess(state.hasPremium, state.ownershipVerified, state.accessCodeActive)
}
