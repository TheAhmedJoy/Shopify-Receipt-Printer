package com.example.shopify_receipt_printer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ShopifyClientTest {
    private fun data(vararg nodes: String) = JSONObject(
        """{"orders":{"nodes":[${nodes.joinToString(",")}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}"""
    )

    private val companyOrder = """
        {
          "id": "gid://shopify/Order/1",
          "name": "#1042",
          "createdAt": "2026-10-18T21:05:00Z",
          "cancelledAt": null,
          "note": "Leave at back door",
          "tags": ["wholesale"],
          "customAttributes": [{"key": "Delivery Date", "value": "Tue 20 Oct"}, {"key": "_hidden", "value": "x"}],
          "shippingLine": {"title": "Local delivery"},
          "shippingAddress": {"company": "Corner Espresso", "name": "Sam Baker"},
          "billingAddress": {"company": "Billing Co", "name": "Someone Else"},
          "lineItems": {"nodes": [
            {"title": "Sourdough", "variantTitle": "Large", "quantity": 12, "currentQuantity": 10},
            {"title": "Baguette", "variantTitle": "Default Title", "quantity": 4, "currentQuantity": 4},
            {"title": "Removed item", "variantTitle": null, "quantity": 2, "currentQuantity": 0}
          ]}
        }
    """

    @Test
    fun prefersAddressCompanyForShopName() {
        val order = ShopifyClient.parseOrders(data(companyOrder)).single()
        assertEquals("gid://shopify/Order/1", order.id)
        assertEquals("#1042", order.name)
        assertEquals(Instant.parse("2026-10-18T21:05:00Z"), order.createdAt)
        assertFalse(order.cancelled)
        assertEquals("Corner Espresso", order.shopName)
        assertEquals("Sam Baker", order.shopDetail)
        assertEquals("Local delivery", order.shippingMethod)
        assertEquals("Leave at back door", order.note)
        assertEquals(listOf(LineItem(10, "Sourdough", "Large"), LineItem(4, "Baguette", null)), order.lineItems)
        assertEquals(2, order.attributes.size)
        assertFalse(order.isTaggedPrinted)
    }

    @Test
    fun fallsBackToPersonNameWithoutCompany() {
        val order = ShopifyClient.parseOrders(
            data(
                """
                {"id": "gid://shopify/Order/2", "name": "#1043", "createdAt": "2026-10-18T22:00:00Z",
                 "cancelledAt": "2026-10-18T23:00:00Z", "note": "", "tags": ["Printed"], "customAttributes": [],
                 "shippingLine": null, "shippingAddress": null,
                 "billingAddress": {"company": null, "name": "Hillside Shop"},
                 "lineItems": {"nodes": [{"title": "Pie", "variantTitle": null, "quantity": 3, "currentQuantity": null}]}}
                """
            )
        ).single()
        assertEquals("Hillside Shop", order.shopName)
        assertNull(order.shopDetail)
        assertNull(order.note)
        assertNull(order.shippingMethod)
        assertTrue(order.cancelled)
        assertTrue(order.isTaggedPrinted)
        assertEquals(3, order.lineItems.single().quantity)
    }
}
