package com.example.shopify_receipt_printer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
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
          "phone": null,
          "displayFinancialStatus": "PENDING",
          "paymentGatewayNames": ["Cash on Delivery (COD)"],
          "currencyCode": "AUD",
          "currentShippingPriceSet": {"shopMoney": {"amount": "5.0"}},
          "currentTotalPriceSet": {"shopMoney": {"amount": "102.5"}},
          "customAttributes": [{"key": "Delivery Date", "value": "Tue 20 Oct"}, {"key": "_hidden", "value": "x"}],
          "shippingLine": {"title": "Local delivery"},
          "shippingAddress": {"company": "Corner Espresso", "name": "Sam Baker", "address1": "12 Example St",
                              "address2": "", "city": "Sampletown", "provinceCode": "NSW", "zip": "2000",
                              "phone": "0400 000 000"},
          "billingAddress": {"company": "Billing Co", "name": "Someone Else", "address1": "1 Billing Rd",
                             "address2": null, "city": "Elsewhere", "provinceCode": "NSW", "zip": "2001"},
          "lineItems": {"nodes": [
            {"title": "Sourdough", "variantTitle": "Large", "quantity": 12, "currentQuantity": 10,
             "discountedUnitPriceAfterAllDiscountsSet": {"shopMoney": {"amount": "7.75"}}},
            {"title": "Baguette", "variantTitle": "Default Title", "quantity": 4, "currentQuantity": 4,
             "discountedUnitPriceAfterAllDiscountsSet": {"shopMoney": {"amount": "4.5"}}},
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
        assertEquals(
            listOf(LineItem(10, "Sourdough", "Large", BigDecimal("7.75")), LineItem(4, "Baguette", null, BigDecimal("4.5"))),
            order.lineItems,
        )
        assertEquals(BigDecimal("77.50"), order.lineItems.first().linePrice)
        assertEquals("AUD", order.currency)
        assertEquals(BigDecimal("5.0"), order.shippingPrice)
        assertEquals(BigDecimal("102.5"), order.totalPrice)
        assertEquals(2, order.attributes.size)
        assertFalse(order.isTaggedPrinted)
        assertEquals(listOf("12 Example St", "Sampletown NSW 2000"), order.address)
        assertTrue(order.addressIsDelivery)
        assertEquals("0400 000 000", order.phone)
        assertEquals("Cash on Delivery (COD)", order.paymentMethod)
        assertEquals("PENDING", order.paymentStatus)
    }

    @Test
    fun fallsBackToPersonNameWithoutCompany() {
        val order = ShopifyClient.parseOrders(
            data(
                """
                {"id": "gid://shopify/Order/2", "name": "#1043", "createdAt": "2026-10-18T22:00:00Z",
                 "cancelledAt": "2026-10-18T23:00:00Z", "note": "", "tags": ["Printed"], "customAttributes": [],
                 "shippingLine": null, "shippingAddress": null,
                 "billingAddress": {"company": null, "name": "Hillside Shop", "address1": "5 Hill Rd",
                                    "address2": "Shop 2", "city": "Hillside", "provinceCode": null, "zip": "3000"},
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
        assertEquals(listOf("5 Hill Rd", "Shop 2", "Hillside 3000"), order.address)
        assertFalse(order.addressIsDelivery)
        assertNull(order.phone)
        assertNull(order.paymentMethod)
        assertNull(order.totalPrice)
        assertNull(order.lineItems.single().unitPrice)
    }
}
