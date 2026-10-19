package com.example.shopify_receipt_printer

import java.math.BigDecimal
import java.time.Instant

data class Order(
    /** Shopify GID, e.g. "gid://shopify/Order/123". */
    val id: String,
    /** Display number, e.g. "#1042". */
    val name: String,
    val createdAt: Instant,
    val cancelled: Boolean,
    /** Who ordered it (address company, else the person's name). The kitchen sorts by this. */
    val shopName: String,
    /** The person who placed it, when [shopName] is a company. */
    val shopDetail: String?,
    val shippingMethod: String?,
    val note: String?,
    val tags: List<String>,
    val attributes: List<Pair<String, String>>,
    val lineItems: List<LineItem>,
    /** Street, suburb/state/postcode lines. Empty when the order has no address. */
    val address: List<String> = emptyList(),
    /** True when [address] is where to deliver, false when it is only the billing address. */
    val addressIsDelivery: Boolean = true,
    val phone: String? = null,
    /** Payment gateway names, e.g. "Cash on Delivery (COD)". */
    val paymentMethod: String? = null,
    /** Shopify financial status, e.g. "PAID" or "PENDING". */
    val paymentStatus: String? = null,
    /** Shop currency, e.g. "AUD". */
    val currency: String = "",
    /** Delivery fee after edits and discounts. */
    val shippingPrice: BigDecimal? = null,
    /** Amount the customer pays, after edits, discounts and refunds. */
    val totalPrice: BigDecimal? = null,
) {
    val isTaggedPrinted: Boolean
        get() = tags.any { it.equals(ShopifyClient.PRINTED_TAG, ignoreCase = true) }
}

data class LineItem(
    val quantity: Int,
    val title: String,
    val variant: String?,
    /** Price charged per unit, after discounts. */
    val unitPrice: BigDecimal? = null,
) {
    val linePrice: BigDecimal? get() = unitPrice?.multiply(BigDecimal(quantity))
}
