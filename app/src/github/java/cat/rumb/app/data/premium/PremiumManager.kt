package cat.rumb.app.data.premium

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The open-source GitHub distribution retains its existing feature set. */
class PremiumManager(@Suppress("UNUSED_PARAMETER") context: Context) {
    private val mutableState = MutableStateFlow(
        PremiumState(hasPremium = true, ownershipVerified = true, available = false),
    )
    val state: StateFlow<PremiumState> = mutableState.asStateFlow()
    fun start() = Unit
    fun refresh(@Suppress("UNUSED_PARAMETER") restore: Boolean = false) = Unit
    suspend fun refreshAndAwait(@Suppress("UNUSED_PARAMETER") timeoutMs: Long = 15_000L): PremiumState = state.value
    fun redeemAccessCode(@Suppress("UNUSED_PARAMETER") code: String): Boolean = false
    fun revokeAccessCode() = Unit
    fun launchPurchase(
        @Suppress("UNUSED_PARAMETER") activity: Activity,
        @Suppress("UNUSED_PARAMETER") plan: PremiumPlan,
    ) = Unit
    fun openSubscriptions(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
    fun close() = Unit
}
