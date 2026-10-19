package com.example.shopify_receipt_printer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class ShopifyException(message: String) : IOException(message)

/** Minimal Shopify Admin GraphQL client: list unprinted orders and tag them as printed. */
class ShopifyClient(
    private val storeDomain: String,
    private val accessToken: String,
    private val apiVersion: String,
    private val http: OkHttpClient = defaultHttp,
) {
    /** Non-fatal GraphQL errors from the last call (e.g. a field the token cannot read). */
    var lastWarnings: List<String> = emptyList()
        private set

    /** Unprinted orders created at or after [since], oldest first. */
    suspend fun fetchUnprintedOrders(since: Instant): List<Order> {
        val search = "-tag:$PRINTED_TAG created_at:>='${since.truncatedTo(ChronoUnit.SECONDS)}'"
        val orders = mutableListOf<Order>()
        val warnings = mutableListOf<String>()
        var cursor: String? = null
        for (page in 0 until MAX_PAGES) {
            val variables = JSONObject().put("query", search).put("cursor", cursor ?: JSONObject.NULL)
            val data = execute(ORDERS_QUERY, variables, warnings)
            orders += parseOrders(data)
            val pageInfo = data.getJSONObject("orders").getJSONObject("pageInfo")
            if (!pageInfo.getBoolean("hasNextPage")) break
            cursor = pageInfo.getString("endCursor")
        }
        lastWarnings = warnings
        return orders
    }

    suspend fun addPrintedTag(orderId: String) {
        val variables = JSONObject().put("id", orderId).put("tags", JSONArray().put(PRINTED_TAG))
        val data = execute(TAGS_ADD_MUTATION, variables, mutableListOf())
        val userErrors = data.getJSONObject("tagsAdd").getJSONArray("userErrors")
        if (userErrors.length() > 0) {
            throw ShopifyException("tagsAdd failed: " + userErrors.getJSONObject(0).optString("message"))
        }
    }

    /** Returns the shop name; used by the "check connection" button. */
    suspend fun fetchShopName(): String =
        execute("{ shop { name } }", JSONObject(), mutableListOf()).getJSONObject("shop").getString("name")

    private suspend fun execute(query: String, variables: JSONObject, warnings: MutableList<String>): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("query", query).put("variables", variables).toString()
            val request = Request.Builder()
                .url("https://$storeDomain/admin/api/$apiVersion/graphql.json")
                .header("X-Shopify-Access-Token", accessToken)
                .post(body.toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.code == 401 -> throw ShopifyException("Shopify rejected the access token (401)")
                    response.code == 403 -> throw ShopifyException("Token is missing a required scope (403)")
                    response.code == 404 -> throw ShopifyException("Store or API version not found (404). Check the myshopify.com domain and API version")
                    !response.isSuccessful -> throw ShopifyException("Shopify HTTP ${response.code}: ${text.take(200)}")
                }
                val json = JSONObject(text)
                val errors = json.optJSONArray("errors")
                    ?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).optString("message") } }
                    .orEmpty()
                val data = json.optJSONObject("data")
                    ?: throw ShopifyException(errors.firstOrNull() ?: "Shopify returned no data")
                // Partial data with errors usually means a protected field (e.g. customer name) came back
                // null. The order is still printable, so keep going and surface the message.
                warnings += errors
                data
            }
        }

    companion object {
        const val PRINTED_TAG = "printed"
        private const val PAGE_SIZE = 25
        private const val MAX_PAGES = 8
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Internal so the order preview test sends exactly the query the app does. */
        internal val ORDERS_QUERY = """
            query UnprintedOrders(${'$'}query: String!, ${'$'}cursor: String) {
              orders(first: $PAGE_SIZE, after: ${'$'}cursor, query: ${'$'}query, sortKey: CREATED_AT) {
                nodes {
                  id
                  name
                  createdAt
                  cancelledAt
                  note
                  tags
                  phone
                  displayFinancialStatus
                  paymentGatewayNames
                  currencyCode
                  currentShippingPriceSet { shopMoney { amount } }
                  currentTotalPriceSet { shopMoney { amount } }
                  customAttributes { key value }
                  shippingLine { title }
                  shippingAddress { company name address1 address2 city provinceCode zip phone }
                  billingAddress { company name address1 address2 city provinceCode zip phone }
                  lineItems(first: 250) {
                    nodes {
                      title variantTitle quantity currentQuantity
                      discountedUnitPriceAfterAllDiscountsSet { shopMoney { amount } }
                    }
                  }
                }
                pageInfo { hasNextPage endCursor }
              }
            }
        """.trimIndent()

        private val TAGS_ADD_MUTATION = """
            mutation AddTag(${'$'}id: ID!, ${'$'}tags: [String!]!) {
              tagsAdd(id: ${'$'}id, tags: ${'$'}tags) { userErrors { field message } }
            }
        """.trimIndent()

        /** Parses the `data` object of [ORDERS_QUERY]. */
        fun parseOrders(data: JSONObject): List<Order> {
            val nodes = data.getJSONObject("orders").getJSONArray("nodes")
            return (0 until nodes.length()).map { parseOrder(nodes.getJSONObject(it)) }
        }

        private fun parseOrder(o: JSONObject): Order {
            // Customer and B2B company fields need read_customers, which the token deliberately lacks.
            // The address company is where the shops enter their name, so prefer it.
            val shipping = o.obj("shippingAddress")
            val billing = o.obj("billingAddress")
            val shopName = listOfNotNull(
                shipping?.str("company"),
                billing?.str("company"),
                shipping?.str("name"),
                billing?.str("name"),
            ).firstOrNull() ?: "Unknown customer"
            val personName = (shipping?.str("name") ?: billing?.str("name"))
                ?.takeUnless { it.equals(shopName, ignoreCase = true) }

            // Pickup orders have no shipping address, so fall back to billing for the kitchen's reference
            val shippingLines = shipping?.addressLines().orEmpty()
            val address = shippingLines.ifEmpty { billing?.addressLines().orEmpty() }

            // Checkout stores the phone on the address; the order's own phone field is usually empty
            val phone = shipping?.str("phone") ?: billing?.str("phone") ?: o.str("phone")
            val gateways = o.optJSONArray("paymentGatewayNames") ?: JSONArray()
            val paymentMethod = (0 until gateways.length()).map { gateways.getString(it) }
                .filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null }

            val attrs = o.optJSONArray("customAttributes") ?: JSONArray()
            val attributes = (0 until attrs.length()).mapNotNull {
                val a = attrs.getJSONObject(it)
                val key = a.str("key") ?: return@mapNotNull null
                val value = a.str("value") ?: return@mapNotNull null
                key to value
            }

            val itemNodes = o.getJSONObject("lineItems").getJSONArray("nodes")
            val items = (0 until itemNodes.length()).mapNotNull {
                val li = itemNodes.getJSONObject(it)
                // currentQuantity reflects edits and removals after checkout
                val qty = if (li.isNull("currentQuantity")) li.getInt("quantity") else li.getInt("currentQuantity")
                if (qty <= 0) return@mapNotNull null
                LineItem(
                    quantity = qty,
                    title = li.str("title") ?: "Item",
                    variant = li.str("variantTitle")?.takeUnless { it == "Default Title" },
                    unitPrice = li.money("discountedUnitPriceAfterAllDiscountsSet"),
                )
            }

            val tags = o.optJSONArray("tags") ?: JSONArray()
            return Order(
                id = o.getString("id"),
                name = o.getString("name"),
                createdAt = Instant.parse(o.getString("createdAt")),
                cancelled = !o.isNull("cancelledAt"),
                shopName = shopName,
                shopDetail = personName,
                shippingMethod = o.obj("shippingLine")?.str("title"),
                note = o.str("note"),
                tags = (0 until tags.length()).map { tags.getString(it) },
                attributes = attributes,
                lineItems = items,
                address = address,
                addressIsDelivery = shippingLines.isNotEmpty(),
                phone = phone,
                paymentMethod = paymentMethod,
                paymentStatus = o.str("displayFinancialStatus"),
                currency = o.str("currencyCode").orEmpty(),
                shippingPrice = o.money("currentShippingPriceSet"),
                totalPrice = o.money("currentTotalPriceSet"),
            )
        }

        /** "12 Example St", optional unit line, then "Suburb NSW 2000". */
        private fun JSONObject.addressLines(): List<String> = listOfNotNull(
            str("address1"),
            str("address2"),
            listOfNotNull(str("city"), str("provinceCode"), str("zip")).joinToString(" ").ifEmpty { null },
        )

        private fun JSONObject.obj(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)

        /** Amount of a MoneyBag field in the shop's currency. */
        private fun JSONObject.money(key: String): BigDecimal? =
            obj(key)?.obj("shopMoney")?.str("amount")?.toBigDecimalOrNull()

        /** Non-blank string or null. org.json's optString turns JSON null into "null", so avoid it. */
        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).trim().ifEmpty { null }
    }
}
