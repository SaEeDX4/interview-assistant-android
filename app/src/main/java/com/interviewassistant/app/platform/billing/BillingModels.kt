package com.interviewassistant.app.platform.billing

/**
 * Google Play Billing, client side (DECISIONS OD-1: Android billing is Play Billing;
 * TRANSFER-MAP §8 D7). Everything in this file is platform-neutral: the real Play client
 * (`PlayBillingClient.kt`) maps the Billing Library's types onto these models, and
 * [BillingCoordinator] decides over them, so the state mapping is JVM-tested with a fake
 * gateway.
 *
 * HONEST BOUNDARY (D7): a completed Play purchase is NOT an entitlement. Verification and
 * the Play→backend entitlement bridge live server-side; the shared backend is Windows-repo
 * code and read-only for this project. The client therefore surfaces a purchased-but-
 * unverified state and holds the purchase token for the account layer — it never grants
 * Pro itself.
 */
object PlayBilling {
    /**
     * The Pro subscription's Play product id. PROVISIONAL: it names the product the owner
     * creates in Play Console; OD-3 (the owner's Play account check) is deferred to P6/P8,
     * and no console state is claimed anywhere in this phase (P6-PORT-NOTES DC-187).
     */
    const val PRO_PRODUCT_ID: String = "pro_monthly"
}

/** `BillingClient.BillingResponseCode` values, mirrored so the pure layer needs no library types. */
object BillingResponseCodes {
    const val OK: Int = 0
    const val USER_CANCELED: Int = 1
    const val SERVICE_UNAVAILABLE: Int = 2
    const val BILLING_UNAVAILABLE: Int = 3
    const val ITEM_UNAVAILABLE: Int = 4
    const val DEVELOPER_ERROR: Int = 5
    const val ERROR: Int = 6
    const val ITEM_ALREADY_OWNED: Int = 7
    const val FEATURE_NOT_SUPPORTED: Int = -2
    const val SERVICE_DISCONNECTED: Int = -1
    const val NETWORK_ERROR: Int = 12
}

/** A purchasable Pro offer as the account layer sees it (the gateway fills it from ProductDetails). */
data class BillingProduct(
    val productId: String,
    val title: String,
    val formattedPrice: String,
    val offerToken: String,
)

enum class BillingPurchaseState { Purchased, Pending, Unspecified }

/** A Play purchase as the coordinator sees it (the gateway fills it from `Purchase`). */
data class BillingPurchase(
    val productIds: List<String>,
    val purchaseToken: String,
    val state: BillingPurchaseState,
    val acknowledged: Boolean,
)

/** The outcome of a gateway operation that reports a Billing response code. */
data class GatewayResult(val ok: Boolean, val responseCode: Int, val debugMessage: String = "")

/** The client-visible billing state, surfaced truthfully (see the D7 boundary above). */
sealed interface BillingState {
    /** Nothing attempted yet. */
    data object Idle : BillingState

    /** Connecting to the Play billing service. */
    data object Connecting : BillingState

    /** Connected; [product] is the purchasable Pro offer, or null when Play has none for us. */
    data class Ready(val product: BillingProduct?) : BillingState

    /** The purchase flow is on screen; its result arrives through the purchases listener. */
    data object PurchaseInFlight : BillingState

    /** Play reports the purchase as pending (e.g. a deferred payment method). Not an entitlement. */
    data object PurchasePending : BillingState

    /**
     * Play holds a completed purchase. The token is kept for the account layer; the plan
     * changes only when the service confirms it — never from this state alone.
     */
    data class PurchasedUnverified(val purchaseToken: String) : BillingState

    /** Play billing cannot run on this device/account (unavailable, unsupported, service down). */
    data object Unavailable : BillingState

    /** An operation failed with a Billing response code. */
    data class Failed(val responseCode: Int) : BillingState
}

/** One subscription offer's pricing phase, as the selection rule reads it. */
data class OfferPhase(val formattedPrice: String, val recurrenceMode: Int)

/** One subscription offer (a base plan or a promotion) with its phases. */
data class OfferInfo(val offerToken: String, val offerId: String?, val phases: List<OfferPhase>)

/**
 * Which offer of a subscription product is THE Pro offer, and what price represents it:
 * the base plan (the offer with no offer id) wins over promotions; the displayed price is
 * the infinite-recurring phase's — the price that actually renews — else the last phase's.
 */
object BillingOfferSelection {
    /** `PricingPhase.RECURRING_MODE_INFINITE_RECURRING`. */
    const val RECURRENCE_INFINITE: Int = 1

    fun select(offers: List<OfferInfo>): OfferInfo? =
        offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull()

    fun displayPrice(offer: OfferInfo): String =
        (offer.phases.firstOrNull { it.recurrenceMode == RECURRENCE_INFINITE } ?: offer.phases.lastOrNull())
            ?.formattedPrice ?: ""

    /** The [BillingProduct] for a product's details, or null when it carries no offer at all. */
    fun toProduct(productId: String, title: String, offers: List<OfferInfo>): BillingProduct? {
        val offer = select(offers) ?: return null
        return BillingProduct(productId, title, displayPrice(offer), offer.offerToken)
    }
}
