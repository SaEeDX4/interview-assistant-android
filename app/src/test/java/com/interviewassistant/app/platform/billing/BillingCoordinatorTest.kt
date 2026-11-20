package com.interviewassistant.app.platform.billing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BillingCoordinator] over a fake [PlayBillingGateway]: connect/restore/query on start,
 * the launch, and every `PurchasesUpdatedListener` outcome mapped to the truthful state.
 * The coordinator's scope is [Dispatchers.Unconfined], so its launched halves (acknowledge,
 * the already-owned restore) complete inside the test's call, deterministically.
 */
class BillingCoordinatorTest {

    private class FakeGateway : PlayBillingGateway {
        override var purchasesListener: ((Int, List<BillingPurchase>) -> Unit)? = null
        var connectResult = GatewayResult(true, BillingResponseCodes.OK)
        var connectCount = 0
        var product: BillingProduct? = BillingProduct(PlayBilling.PRO_PRODUCT_ID, "Pro", "$19.99", "offer-token")
        var owned: List<BillingPurchase> = emptyList()
        var launchCode = BillingResponseCodes.OK
        var launchCount = 0
        val acknowledgedTokens = mutableListOf<String>()
        var endCount = 0

        override suspend fun connect(): GatewayResult {
            connectCount++
            return connectResult
        }

        override suspend fun queryProduct(productId: String): BillingProduct? = product

        override suspend fun queryOwnedPurchases(): List<BillingPurchase> = owned

        override fun launchFlow(product: BillingProduct): Int {
            launchCount++
            return launchCode
        }

        override suspend fun acknowledge(purchaseToken: String): Boolean {
            acknowledgedTokens += purchaseToken
            return true
        }

        override fun endConnection() {
            endCount++
        }

        fun emit(code: Int, purchases: List<BillingPurchase>) {
            purchasesListener?.invoke(code, purchases)
        }
    }

    private fun purchase(
        state: BillingPurchaseState,
        token: String = "token-1",
        acknowledged: Boolean = false,
        productId: String = PlayBilling.PRO_PRODUCT_ID,
    ): BillingPurchase = BillingPurchase(listOf(productId), token, state, acknowledged)

    private fun coordinator(gateway: FakeGateway): BillingCoordinator =
        BillingCoordinator(gateway, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun start_ConnectBillingUnavailable_BecomesUnavailable() = runBlocking {
        val gateway = FakeGateway()
        gateway.connectResult = GatewayResult(false, BillingResponseCodes.BILLING_UNAVAILABLE)
        val c = coordinator(gateway)
        c.start()
        assertEquals(BillingState.Unavailable, c.state)
    }

    @Test
    fun start_ConnectFailureOtherCode_BecomesFailed() = runBlocking {
        val gateway = FakeGateway()
        gateway.connectResult = GatewayResult(false, BillingResponseCodes.ERROR)
        val c = coordinator(gateway)
        c.start()
        assertEquals(BillingState.Failed(BillingResponseCodes.ERROR), c.state)
    }

    @Test
    fun start_Twice_ConnectsOnce() = runBlocking {
        val gateway = FakeGateway()
        val c = coordinator(gateway)
        c.start()
        c.start()
        assertEquals(1, gateway.connectCount)
    }

    @Test
    fun start_NoOwnedPurchase_QueriesTheProductAndBecomesReady() = runBlocking {
        val gateway = FakeGateway()
        val c = coordinator(gateway)
        c.start()
        val ready = c.state as BillingState.Ready
        assertEquals(PlayBilling.PRO_PRODUCT_ID, ready.product?.productId)
        assertEquals("$19.99", ready.product?.formattedPrice)
        assertNull(c.purchaseTokenForAccount)
    }

    @Test
    fun start_ProductMissing_BecomesReadyWithoutAProduct_AndLaunchIsRefused() = runBlocking {
        val gateway = FakeGateway()
        gateway.product = null
        val c = coordinator(gateway)
        c.start()
        assertEquals(BillingState.Ready(null), c.state)
        assertFalse(c.launchPurchase())
        assertEquals(0, gateway.launchCount)
    }

    @Test
    fun start_OwnedPurchasedUnacknowledged_AcknowledgesAndSurfacesTheToken() = runBlocking {
        val gateway = FakeGateway()
        gateway.owned = listOf(purchase(BillingPurchaseState.Purchased, token = "tok-owned", acknowledged = false))
        val c = coordinator(gateway)
        c.start()
        assertEquals(listOf("tok-owned"), gateway.acknowledgedTokens)
        assertEquals(BillingState.PurchasedUnverified("tok-owned"), c.state)
        assertEquals("tok-owned", c.purchaseTokenForAccount)
    }

    @Test
    fun start_OwnedPending_BecomesPurchasePending() = runBlocking {
        val gateway = FakeGateway()
        gateway.owned = listOf(purchase(BillingPurchaseState.Pending))
        val c = coordinator(gateway)
        c.start()
        assertEquals(BillingState.PurchasePending, c.state)
        assertNull(c.purchaseTokenForAccount)
    }

    @Test
    fun launchPurchase_BeforeStart_IsRefused() {
        val gateway = FakeGateway()
        val c = coordinator(gateway)
        assertFalse(c.launchPurchase())
        assertEquals(0, gateway.launchCount)
    }

    @Test
    fun launchPurchase_Ok_StaysInFlight_ThenThePurchaseCompletesUnverified() = runBlocking {
        val gateway = FakeGateway()
        val c = coordinator(gateway)
        c.start()
        assertTrue(c.launchPurchase())
        assertEquals(BillingState.PurchaseInFlight, c.state)
        gateway.emit(
            BillingResponseCodes.OK,
            listOf(purchase(BillingPurchaseState.Purchased, token = "tok-new", acknowledged = false)),
        )
        assertEquals(listOf("tok-new"), gateway.acknowledgedTokens)
        assertEquals(BillingState.PurchasedUnverified("tok-new"), c.state)
        assertEquals("tok-new", c.purchaseTokenForAccount)
    }

    @Test
    fun launchPurchase_CanceledAtLaunch_ReturnsToReady() = runBlocking {
        val gateway = FakeGateway()
        gateway.launchCode = BillingResponseCodes.USER_CANCELED
        val c = coordinator(gateway)
        c.start()
        assertFalse(c.launchPurchase())
        val ready = c.state as BillingState.Ready
        assertEquals(PlayBilling.PRO_PRODUCT_ID, ready.product?.productId)
    }

    @Test
    fun purchasesUpdated_UserCanceled_ReturnsToReady() = runBlocking {
        val gateway = FakeGateway()
        val c = coordinator(gateway)
        c.start()
        assertTrue(c.launchPurchase())
        gateway.emit(BillingResponseCodes.USER_CANCELED, emptyList())
        assertTrue(c.state is BillingState.Ready)
    }

    @Test
    fun purchasesUpdated_Pending_BecomesPurchasePending() = runBlocking {
        val gateway = FakeGateway()
        val c = coordinator(gateway)
        c.start()
        assertTrue(c.launchPurchase())
        gateway.emit(BillingResponseCodes.OK, listOf(purchase(BillingPurchaseState.Pending)))
        assertEquals(BillingState.PurchasePending, c.state)
    }

    @Test
    fun purchasesUpdated_AlreadyOwned_RestoresTheOwnedPurchase() = runBlocking {
        val gateway = FakeGateway()
        val c = coordinator(gateway)
        c.start()
        assertTrue(c.state is BillingState.Ready)
        gateway.owned = listOf(purchase(BillingPurchaseState.Purchased, token = "tok-owned", acknowledged = true))
        gateway.emit(BillingResponseCodes.ITEM_ALREADY_OWNED, emptyList())
        assertEquals(BillingState.PurchasedUnverified("tok-owned"), c.state)
    }

    @Test
    fun purchasesUpdated_ErrorCode_BecomesFailed() = runBlocking {
        val gateway = FakeGateway()
        val c = coordinator(gateway)
        c.start()
        assertTrue(c.launchPurchase())
        gateway.emit(BillingResponseCodes.ERROR, emptyList())
        assertEquals(BillingState.Failed(BillingResponseCodes.ERROR), c.state)
    }

    @Test
    fun purchasesUpdated_OtherProductsPurchase_IsIgnored() = runBlocking {
        val gateway = FakeGateway()
        val c = coordinator(gateway)
        c.start()
        assertTrue(c.launchPurchase())
        gateway.emit(
            BillingResponseCodes.OK,
            listOf(purchase(BillingPurchaseState.Purchased, productId = "some_other_product")),
        )
        assertEquals(BillingState.PurchaseInFlight, c.state)
        assertTrue(gateway.acknowledgedTokens.isEmpty())
    }
}
