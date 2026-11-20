package com.interviewassistant.app.platform.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BillingOfferSelection]: the base plan wins over promotions, and the price that
 * represents the product is the renewing (infinite-recurring) phase's.
 */
class BillingOfferSelectionTest {

    private fun offer(
        token: String,
        offerId: String?,
        phases: List<OfferPhase>,
    ): OfferInfo = OfferInfo(offerToken = token, offerId = offerId, phases = phases)

    private val recurring = OfferPhase("$19.99", BillingOfferSelection.RECURRENCE_INFINITE)
    private val intro = OfferPhase("$4.99", 3) // a non-recurring introductory phase

    @Test
    fun select_PrefersTheBasePlanOverPromotions() {
        val promo = offer("promo-token", "intro-offer", listOf(intro, recurring))
        val base = offer("base-token", null, listOf(recurring))
        assertEquals("base-token", BillingOfferSelection.select(listOf(promo, base))?.offerToken)
    }

    @Test
    fun select_WithoutABasePlan_TakesTheFirstOffer() {
        val first = offer("first-token", "a", listOf(recurring))
        val second = offer("second-token", "b", listOf(recurring))
        assertEquals("first-token", BillingOfferSelection.select(listOf(first, second))?.offerToken)
        assertNull(BillingOfferSelection.select(emptyList()))
    }

    @Test
    fun displayPrice_IsTheRenewingPhase_NotTheIntroductoryOne() {
        val o = offer("t", null, listOf(intro, recurring))
        assertEquals("$19.99", BillingOfferSelection.displayPrice(o))
        // With no infinite-recurring phase, the last phase is the price.
        val finiteOnly = offer("t", null, listOf(OfferPhase("$9.99", 2), OfferPhase("$14.99", 2)))
        assertEquals("$14.99", BillingOfferSelection.displayPrice(finiteOnly))
    }

    @Test
    fun toProduct_BuildsTheProductFromTheSelectedOffer() {
        val base = offer("base-token", null, listOf(recurring))
        val product = BillingOfferSelection.toProduct("pro_monthly", "Pro", listOf(base))
        assertEquals("pro_monthly", product?.productId)
        assertEquals("$19.99", product?.formattedPrice)
        assertEquals("base-token", product?.offerToken)
        assertNull(BillingOfferSelection.toProduct("pro_monthly", "Pro", emptyList()))
    }
}
