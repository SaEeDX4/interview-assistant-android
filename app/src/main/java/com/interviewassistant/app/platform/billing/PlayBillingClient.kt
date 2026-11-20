package com.interviewassistant.app.platform.billing

import android.app.Activity
import android.content.Context
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
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The real [PlayBillingGateway] over the Play Billing Library (9.1.0, P6-PORT-NOTES DC-182):
 * one connection for the app's lifetime, automatic service reconnection, pending purchases
 * enabled. All library types stay inside this file; everything crossing the seam is the
 * pure model of `BillingModels.kt`. Product details are cached by product id because the
 * purchase flow needs the library's own `ProductDetails` + the chosen offer's token.
 *
 * The flow's on-screen act needs the foreground activity: [activityProvider] is installed
 * by MainActivity (onCreate/onDestroy). Without one, a launch reports DEVELOPER_ERROR
 * rather than pretending.
 */
class PlayBillingClient(context: Context) : PlayBillingGateway {

    /** The foreground activity for the purchase flow, installed by the hosting activity. */
    var activityProvider: (() -> Activity?)? = null

    override var purchasesListener: ((responseCode: Int, purchases: List<BillingPurchase>) -> Unit)? = null

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            purchasesListener?.invoke(
                result.responseCode,
                purchases?.map { mapPurchase(it) } ?: emptyList(),
            )
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .enableAutoServiceReconnection()
        .build()

    private var detailsByProduct: Map<String, ProductDetails> = emptyMap()

    override suspend fun connect(): GatewayResult {
        if (client.isReady) return GatewayResult(true, BillingResponseCodes.OK)
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) {
                        cont.resume(
                            GatewayResult(
                                ok = result.responseCode == BillingClient.BillingResponseCode.OK,
                                responseCode = result.responseCode,
                                debugMessage = result.debugMessage,
                            ),
                        )
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Automatic reconnection is enabled; the next operation re-checks isReady.
                }
            })
        }
    }

    override suspend fun queryProduct(productId: String): BillingProduct? {
        if (!client.isReady) return null
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, queryResult ->
                if (!cont.isActive) return@queryProductDetailsAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.resume(null)
                    return@queryProductDetailsAsync
                }
                val details = queryResult.productDetailsList.firstOrNull { it.productId == productId }
                if (details == null) {
                    cont.resume(null)
                    return@queryProductDetailsAsync
                }
                detailsByProduct = detailsByProduct + (productId to details)
                cont.resume(BillingOfferSelection.toProduct(productId, details.title, offersOf(details)))
            }
        }
    }

    override suspend fun queryOwnedPurchases(): List<BillingPurchase> {
        if (!client.isReady) return emptyList()
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, purchases ->
                if (!cont.isActive) return@queryPurchasesAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.resume(emptyList())
                    return@queryPurchasesAsync
                }
                cont.resume(purchases.map { mapPurchase(it) })
            }
        }
    }

    override fun launchFlow(product: BillingProduct): Int {
        val activity = activityProvider?.invoke() ?: return BillingResponseCodes.DEVELOPER_ERROR
        val details = detailsByProduct[product.productId] ?: return BillingResponseCodes.ITEM_UNAVAILABLE
        if (!client.isReady) return BillingResponseCodes.SERVICE_DISCONNECTED
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(product.offerToken)
                        .build(),
                ),
            )
            .build()
        return client.launchBillingFlow(activity, flowParams).responseCode
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        if (!client.isReady) return false
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
        return suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(params) { result ->
                if (cont.isActive) {
                    cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
            }
        }
    }

    override fun endConnection() = client.endConnection()

    companion object {
        /** A library `Purchase` as the pure model: state constants mapped one-to-one. */
        internal fun mapPurchase(p: Purchase): BillingPurchase = BillingPurchase(
            productIds = p.products,
            purchaseToken = p.purchaseToken,
            state = when (p.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> BillingPurchaseState.Purchased
                Purchase.PurchaseState.PENDING -> BillingPurchaseState.Pending
                else -> BillingPurchaseState.Unspecified
            },
            acknowledged = p.isAcknowledged,
        )

        /** A library `ProductDetails`' subscription offers as pure offer data for the selection rule. */
        internal fun offersOf(details: ProductDetails): List<OfferInfo> =
            details.subscriptionOfferDetails?.map { offer ->
                OfferInfo(
                    offerToken = offer.offerToken,
                    offerId = offer.offerId,
                    phases = offer.pricingPhases.pricingPhaseList.map { phase ->
                        OfferPhase(phase.formattedPrice, phase.recurrenceMode)
                    },
                )
            } ?: emptyList()
    }
}
