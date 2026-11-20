package com.interviewassistant.app.platform.billing

import com.interviewassistant.app.services.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Play Billing client logic (OD-1 / §8 D7) over the [PlayBillingGateway] seam:
 * connect → restore owned purchases → query the Pro product; launch the purchase flow;
 * map every `PurchasesUpdatedListener` result onto the truthful [BillingState]; acknowledge
 * completed purchases; keep a completed purchase's token available to the account layer.
 *
 * The coordinator never grants an entitlement: a completed purchase lands in
 * [BillingState.PurchasedUnverified] and the account's plan changes only when the shared
 * backend confirms it (the server-side Play bridge is the D7 follow-up, P6-PORT-NOTES DC-187).
 */
class BillingCoordinator(
    val gateway: PlayBillingGateway,
    private val scope: CoroutineScope,
    val productId: String = PlayBilling.PRO_PRODUCT_ID,
) {
    var state: BillingState = BillingState.Idle
        private set

    /** The C#-style change notification: fired after every state transition. */
    val stateListeners: MutableList<(BillingState) -> Unit> = mutableListOf()

    /** The last queried Pro offer, retained so a cancelled flow returns to a complete Ready state. */
    var product: BillingProduct? = null
        private set

    /** The completed purchase's token, held for the account layer (null until a purchase completes). */
    val purchaseTokenForAccount: String?
        get() = (state as? BillingState.PurchasedUnverified)?.purchaseToken

    private var started = false

    init {
        gateway.purchasesListener = { code, purchases -> onPurchasesUpdated(code, purchases) }
    }

    private fun setState(next: BillingState) {
        state = next
        val snapshot = synchronized(stateListeners) { stateListeners.toList() }
        for (listener in snapshot) listener(next)
    }

    /**
     * Starts the client: connect, restore what the user already owns (an owned completed
     * purchase is acknowledged if Play still waits for it, and surfaces as
     * [BillingState.PurchasedUnverified]), otherwise query the Pro product. Idempotent.
     */
    suspend fun start() {
        if (started) return
        started = true
        setState(BillingState.Connecting)
        val conn = gateway.connect()
        if (!conn.ok) {
            setState(stateForConnectFailure(conn.responseCode))
            return
        }
        restoreAndQuery()
    }

    /** Restore + product query (start's second half; also the ITEM_ALREADY_OWNED recovery). */
    private suspend fun restoreAndQuery() {
        val mine = gateway.queryOwnedPurchases().firstOrNull { productId in it.productIds }
        if (mine != null) {
            when (mine.state) {
                BillingPurchaseState.Purchased -> {
                    if (!mine.acknowledged) gateway.acknowledge(mine.purchaseToken)
                    setState(BillingState.PurchasedUnverified(mine.purchaseToken))
                    return
                }
                BillingPurchaseState.Pending -> {
                    setState(BillingState.PurchasePending)
                    return
                }
                BillingPurchaseState.Unspecified -> Unit // not a state we can act on; fall through to the product
            }
        }
        product = gateway.queryProduct(productId)
        setState(BillingState.Ready(product))
    }

    /**
     * Launches the Pro purchase flow (the Account Upgrade action's Play half). False when
     * there is nothing to launch (not Ready, or Play returned no product) or the launch
     * itself failed; the purchase result arrives through the purchases listener.
     */
    fun launchPurchase(): Boolean {
        val p = product ?: return false
        if (state !is BillingState.Ready) return false
        setState(BillingState.PurchaseInFlight)
        val code = gateway.launchFlow(p)
        if (code != BillingResponseCodes.OK) {
            setState(stateForLaunchFailure(code, p))
            return false
        }
        return true
    }

    /** The `PurchasesUpdatedListener` mapping: every outcome lands in a truthful state. */
    private fun onPurchasesUpdated(code: Int, purchases: List<BillingPurchase>) {
        when (code) {
            BillingResponseCodes.OK -> Unit
            BillingResponseCodes.USER_CANCELED -> {
                // Backing out of the flow is not an error: the Ready state (and its product) returns.
                if (state is BillingState.PurchaseInFlight) setState(BillingState.Ready(product))
                return
            }
            BillingResponseCodes.ITEM_ALREADY_OWNED -> {
                // Play says the user owns it already: re-run the restore, which surfaces it.
                scope.launch { restoreAndQuery() }
                return
            }
            else -> {
                setState(BillingState.Failed(code))
                return
            }
        }
        val mine = purchases.firstOrNull { productId in it.productIds } ?: return
        when (mine.state) {
            BillingPurchaseState.Purchased -> {
                scope.launch {
                    if (!mine.acknowledged && !gateway.acknowledge(mine.purchaseToken)) {
                        AppLog.warn("Play purchase acknowledgement failed; Play will retry the purchase update")
                    }
                    setState(BillingState.PurchasedUnverified(mine.purchaseToken))
                }
            }
            BillingPurchaseState.Pending -> setState(BillingState.PurchasePending)
            BillingPurchaseState.Unspecified -> Unit
        }
    }

    /** Ends the billing connection (app teardown). */
    fun close() = gateway.endConnection()

    companion object {
        /** Connect failures that mean "billing cannot run here" (vs. a transient failure code). */
        fun stateForConnectFailure(code: Int): BillingState = when (code) {
            BillingResponseCodes.BILLING_UNAVAILABLE,
            BillingResponseCodes.SERVICE_UNAVAILABLE,
            BillingResponseCodes.FEATURE_NOT_SUPPORTED,
            -> BillingState.Unavailable
            else -> BillingState.Failed(code)
        }

        /** A failed launch: a cancel returns to Ready; the "cannot run here" codes map as connects do. */
        fun stateForLaunchFailure(code: Int, product: BillingProduct? = null): BillingState = when (code) {
            BillingResponseCodes.USER_CANCELED -> BillingState.Ready(product)
            BillingResponseCodes.BILLING_UNAVAILABLE,
            BillingResponseCodes.SERVICE_UNAVAILABLE,
            BillingResponseCodes.FEATURE_NOT_SUPPORTED,
            -> BillingState.Unavailable
            else -> BillingState.Failed(code)
        }
    }
}
