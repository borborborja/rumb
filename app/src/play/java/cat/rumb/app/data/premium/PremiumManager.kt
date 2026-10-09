package cat.rumb.app.data.premium

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File
import cat.rumb.app.BuildConfig
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One BillingClient per application. No persistent premium flag or inferred renewal date. */
class PremiumManager(context: Context) : Application.ActivityLifecycleCallbacks {
    private val application = context.applicationContext as Application
    private val expectedPackage = application.packageName
    private val verifier = PurchaseSignatureVerifier(BuildConfig.PLAY_BILLING_PUBLIC_KEY)
    private val accessCodeVerifier = PremiumAccessCodeVerifier(BuildConfig.PLAY_ACCESS_CODE_SHA256)
    // This sandboxed file is excluded from Android cloud backup and device-transfer backup.
    private val accessCodeFile = File(application.noBackupFilesDir, "rumb-demo-access")
    private val mutableState = MutableStateFlow(
        PremiumAccessPolicy.withAccessCode(PremiumState(), hasValidStoredCode()),
    )
    val state: StateFlow<PremiumState> = mutableState.asStateFlow()
    private var started = false
    private var connecting = false
    private var connectionGeneration = 0L
    private val ownershipQueries = PremiumQueryLifecycle()
    private val planQueries = PremiumQueryLifecycle()
    private var refreshQueued = false
    private var restoreRequested = false
    private var productDetails: ProductDetails? = null
    private var offerTokens: Map<PremiumPlan, String> = emptyMap()
    private val acknowledging = mutableSetOf<String>()
    private val ownershipWaiters = mutableSetOf<CompletableDeferred<PremiumState>>()

    private val billing = BillingClient.newBuilder(application)
        .setListener { result, purchases ->
            mutableState.update { it.copy(isPurchasing = false) }
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    // Updates may contain only the changed purchase. Refresh the complete inventory
                    // before revoking existing access, while granting a newly verified purchase now.
                    purchases.orEmpty().filter(::isEligible).forEach(::acknowledge)
                    if (purchases.orEmpty().any(::isEligible)) {
                        mutableState.update { PremiumAccessPolicy.withSubscription(it, true).copy(notice = null) }
                    } else if (purchases.orEmpty().any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
                        mutableState.update { it.copy(notice = PremiumNotice.PURCHASE_PENDING) }
                    }
                    refresh()
                }
                BillingClient.BillingResponseCode.USER_CANCELED ->
                    mutableState.update { it.copy(notice = PremiumNotice.PURCHASE_CANCELED) }
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh(restore = true)
                else -> mutableState.update { it.copy(notice = PremiumNotice.PURCHASE_FAILED) }
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (!started) {
            started = true
            application.registerActivityLifecycleCallbacks(this)
        }
        refresh()
    }

    fun refresh(restore: Boolean = false) = requestRefresh(restore)

    /** Workers await a query result directly instead of observing a potentially older UI value. */
    suspend fun refreshAndAwait(timeoutMs: Long = 15_000L): PremiumState? =
        withContext(Dispatchers.Main.immediate) {
            recheckAccessCode()
            if (state.value.accessCodeActive) return@withContext state.value
            val result = CompletableDeferred<PremiumState>()
            ownershipWaiters.add(result)
            try {
                requestRefresh(restore = false, joinQuery = true)
                withTimeoutOrNull(timeoutMs) { result.await() }
            } finally {
                ownershipWaiters.remove(result)
            }
        }

    private fun requestRefresh(restore: Boolean, joinQuery: Boolean = false) {
        recheckAccessCode()
        restoreRequested = restoreRequested || restore
        if (connecting || ownershipQueries.isInFlight) {
            if (!joinQuery) refreshQueued = true
            return
        }
        mutableState.update { it.copy(isLoading = true, ownershipVerified = false, notice = null) }
        if (billing.isReady) {
            queryOwnership()
            queryPlans()
            return
        }
        connecting = true
        val connection = ++connectionGeneration
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (connection != connectionGeneration) return
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryOwnership()
                    queryPlans()
                } else {
                    mutableState.update { it.copy(isLoading = false, notice = PremiumNotice.STORE_UNAVAILABLE) }
                    completeOwnershipWaiters()
                }
            }

            override fun onBillingServiceDisconnected() {
                if (connection != connectionGeneration) return
                connecting = false
                ownershipQueries.invalidate()
                planQueries.invalidate()
                refreshQueued = false
                productDetails = null
                offerTokens = emptyMap()
                // Auto reconnection is enabled. The next foreground query reconnects the service.
                // Ignore callbacks from queries submitted to the previous service connection.
                mutableState.update { it.copy(
                    isLoading = false, ownershipVerified = false, plans = emptyList(),
                    notice = PremiumNotice.STORE_UNAVAILABLE,
                ) }
                completeOwnershipWaiters()
            }
        })
    }

    private fun queryOwnership() {
        val request = ownershipQueries.begin() ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .includeSuspendedSubscriptions(true)
            .build()
        billing.queryPurchasesAsync(params) { result, purchases ->
            if (!ownershipQueries.complete(request)) return@queryPurchasesAsync
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                // Preserve only access verified during this process. A cold start remains locked.
                mutableState.update { it.copy(isLoading = false, notice = PremiumNotice.STORE_UNAVAILABLE) }
                refreshQueued = false
                completeOwnershipWaiters()
                return@queryPurchasesAsync
            }
            val relevant = purchases.filter { PREMIUM_PRODUCT_ID in it.products }
            val snapshots = relevant.map(::snapshot)
            val hasPremium = PremiumEntitlements.hasPremium(snapshots, expectedPackage)
            val notice = when {
                hasPremium && restoreRequested -> PremiumNotice.RESTORED
                hasPremium -> null
                relevant.any { it.purchaseState == Purchase.PurchaseState.PENDING } -> PremiumNotice.PURCHASE_PENDING
                snapshots.any { it.suspended && it.signatureVerified } -> PremiumNotice.SUBSCRIPTION_SUSPENDED
                snapshots.any { !it.signatureVerified || it.packageName != expectedPackage } -> PremiumNotice.VERIFICATION_FAILED
                restoreRequested -> PremiumNotice.NO_SUBSCRIPTION
                else -> null
            }
            restoreRequested = false
            mutableState.update {
                PremiumAccessPolicy.withSubscription(it, hasPremium).copy(
                    ownershipVerified = true, isLoading = false, notice = notice,
                )
            }
            completeOwnershipWaiters()
            relevant.filter(::isEligible).forEach(::acknowledge)
            if (refreshQueued) {
                refreshQueued = false
                refresh()
            }
        }
    }

    private fun completeOwnershipWaiters() {
        val result = state.value
        ownershipWaiters.toList().forEach { it.complete(result) }
        ownershipWaiters.clear()
    }

    private fun queryPlans() {
        val request = planQueries.begin(supersede = true) ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PREMIUM_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS).build()))
            .build()
        billing.queryProductDetailsAsync(params) { result, details ->
            if (!planQueries.complete(request)) return@queryProductDetailsAsync
            productDetails = null
            offerTokens = emptyMap()
            val offers = mutableListOf<PremiumOffer>()
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val product = details.productDetailsList.firstOrNull { it.productId == PREMIUM_PRODUCT_ID }
                val tokens = mutableMapOf<PremiumPlan, String>()
                product?.subscriptionOfferDetails.orEmpty().forEach { offer ->
                    // Sell the plain auto-renewing base plans. An undisclosed trial or discounted
                    // phase must never replace the price and renewal terms shown by this screen.
                    val selected = PremiumOfferSelection.select(PremiumOfferCandidate(
                        basePlanId = offer.basePlanId,
                        offerId = offer.offerId,
                        offerToken = offer.offerToken,
                        phases = offer.pricingPhases.pricingPhaseList.map { phase -> PremiumPricePhase(
                            phase.formattedPrice, phase.billingPeriod,
                            phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING,
                        ) },
                    ))
                    if (selected != null) {
                        offers += selected.offer
                        tokens[selected.offer.plan] = selected.token
                    }
                }
                productDetails = product
                offerTokens = tokens
            }
            mutableState.update {
                it.copy(plans = offers.sortedBy { offer -> offer.plan.ordinal },
                    notice = if (offers.isEmpty() && !it.subscriptionActive && it.notice == null)
                        PremiumNotice.PRICES_UNAVAILABLE else it.notice)
            }
        }
    }

    fun launchPurchase(activity: Activity, plan: PremiumPlan) {
        if (state.value.subscriptionActive || state.value.isPurchasing) return
        val product = productDetails
        val token = offerTokens[plan]
        if (!billing.isReady || product == null || token == null) {
            mutableState.update { it.copy(notice = PremiumNotice.PRICES_UNAVAILABLE) }
            refresh()
            return
        }
        if (billing.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS).responseCode !=
            BillingClient.BillingResponseCode.OK
        ) {
            mutableState.update { it.copy(notice = PremiumNotice.STORE_UNAVAILABLE) }
            return
        }
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(product).setOfferToken(token).build()),
        ).build()
        mutableState.update { it.copy(isPurchasing = true, notice = null) }
        val result = billing.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            mutableState.update { it.copy(isPurchasing = false, notice = PremiumNotice.PURCHASE_FAILED) }
            if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) refresh(restore = true)
        }
    }

    private fun snapshot(purchase: Purchase) = PremiumPurchaseSnapshot(
        packageName = purchase.packageName,
        products = purchase.products,
        status = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PremiumPurchaseStatus.PURCHASED
            Purchase.PurchaseState.PENDING -> PremiumPurchaseStatus.PENDING
            else -> PremiumPurchaseStatus.UNSPECIFIED
        },
        signatureVerified = verifier.verify(purchase.originalJson, purchase.signature),
        suspended = purchase.isSuspended,
    )

    private fun isEligible(purchase: Purchase) = PremiumEntitlements.isEligible(snapshot(purchase), expectedPackage)

    fun redeemAccessCode(code: String): Boolean {
        val normalized = PremiumAccessCodeVerifier.normalize(code)
        if (!accessCodeVerifier.isValid(normalized)) return false
        val saved = runCatching {
            accessCodeFile.writeText(normalized, Charsets.UTF_8)
            accessCodeFile.setReadable(false, false)
            accessCodeFile.setReadable(true, true)
            accessCodeFile.setWritable(false, false)
            accessCodeFile.setWritable(true, true)
        }.isSuccess
        if (!saved) return false
        recheckAccessCode()
        return state.value.accessCodeActive
    }

    fun revokeAccessCode() {
        runCatching { accessCodeFile.delete() }
        recheckAccessCode()
    }

    private fun hasValidStoredCode(): Boolean = runCatching {
        accessCodeVerifier.isValid(accessCodeFile.readText(Charsets.UTF_8))
    }.getOrDefault(false)

    private fun recheckAccessCode() {
        val valid = hasValidStoredCode()
        mutableState.update { PremiumAccessPolicy.withAccessCode(it, valid) }
    }

    private fun acknowledge(purchase: Purchase) {
        if (!isEligible(purchase) || purchase.isAcknowledged || !acknowledging.add(purchase.purchaseToken)) return
        billing.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
        ) { result ->
            acknowledging.remove(purchase.purchaseToken)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                // Retry on the next ownership query. Pending or unverified receipts never reach here.
                mutableState.update { it.copy(notice = PremiumNotice.ACKNOWLEDGEMENT_RETRY) }
            }
        }
    }

    fun openSubscriptions(context: Context) {
        val uri = Uri.parse("https://play.google.com/store/account/subscriptions")
            .buildUpon().appendQueryParameter("sku", PREMIUM_PRODUCT_ID)
            .appendQueryParameter("package", expectedPackage).build()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { mutableState.update { it.copy(notice = PremiumNotice.STORE_UNAVAILABLE) } }
    }

    fun close() {
        if (started) application.unregisterActivityLifecycleCallbacks(this)
        started = false
        connectionGeneration++
        connecting = false
        ownershipQueries.invalidate()
        planQueries.invalidate()
        refreshQueued = false
        productDetails = null
        offerTokens = emptyMap()
        billing.endConnection()
        mutableState.update { it.copy(isLoading = false, ownershipVerified = false, plans = emptyList()) }
        completeOwnershipWaiters()
    }

    override fun onActivityResumed(activity: Activity) = refresh()
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
