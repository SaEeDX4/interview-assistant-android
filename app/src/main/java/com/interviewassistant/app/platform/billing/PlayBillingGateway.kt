package com.interviewassistant.app.platform.billing

/**
 * The seam between [BillingCoordinator] and the real Play client: every operation the
 * coordinator needs, expressed over the pure models of `BillingModels.kt`. The production
 * implementation is `PlayBillingClient.kt`; tests substitute a fake and pin the
 * coordinator's state mapping without the Billing Library (or a device) in the room.
 *
 * The one platform act — putting the purchase flow on screen — needs the foreground
 * activity; the real gateway holds an activity provider the activity installs, so this
 * interface stays free of Android types and fully fakeable.
 */
interface PlayBillingGateway {
    /** Purchases arrive here (the `PurchasesUpdatedListener` results), as (responseCode, purchases). */
    var purchasesListener: ((responseCode: Int, purchases: List<BillingPurchase>) -> Unit)?

    /** Connects to the Play billing service (a no-op success when already connected). */
    suspend fun connect(): GatewayResult

    /** The purchasable offer for [productId], or null when Play cannot fetch it / it does not exist. */
    suspend fun queryProduct(productId: String): BillingProduct?

    /** Purchases the user already owns (restore): queried on start, and again on ITEM_ALREADY_OWNED. */
    suspend fun queryOwnedPurchases(): List<BillingPurchase>

    /**
     * Launches the purchase flow for [product] on the installed activity. Returns the
     * launch's Billing response code; the purchase result itself arrives via [purchasesListener].
     */
    fun launchFlow(product: BillingProduct): Int

    /** Acknowledges a completed purchase (Play requires it within 3 days, or it refunds). */
    suspend fun acknowledge(purchaseToken: String): Boolean

    /** Ends the billing connection (the coordinator's close). */
    fun endConnection()
}
