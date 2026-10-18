package com.example.shopify_receipt_printer

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
) {
    val isTaggedPrinted: Boolean
        get() = tags.any { it.equals(ShopifyClient.PRINTED_TAG, ignoreCase = true) }
}

data class LineItem(
    val quantity: Int,
    val title: String,
    val variant: String?,
)
