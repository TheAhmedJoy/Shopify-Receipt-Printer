package com.example.shopify_receipt_printer

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Shows what recent orders look like to the app, without printing or tagging anything.
 *
 * Run with: ./gradlew :app:testDebugUnitTest --tests "*OrderPreviewTest" -PorderPreview=24
 * (24 = hours to look back). Writes to app/build/order-preview:
 *  - response.json  the raw Shopify response, from the same query the app sends
 *  - tickets.txt    each order laid out by the app's ticket formatter, as it would print
 *
 * Unlike the app this includes orders already tagged printed, so a test order can be checked
 * again after the kitchen printed it. Skipped in normal test runs.
 */
class OrderPreviewTest {
    @Test
    fun previewRecentOrders() {
        val hours = System.getProperty("orderPreview").orEmpty()
        assumeTrue("Pass -PorderPreview=<hours> to run the order preview", hours.isNotEmpty())
        check(BuildConfig.SHOPIFY_STORE_DOMAIN.isNotEmpty() && BuildConfig.SHOPIFY_ACCESS_TOKEN.isNotEmpty()) {
            "SHOPIFY_STORE_DOMAIN and SHOPIFY_APP_ACCESS_TOKEN must be set in local.properties"
        }
        val outDir = File(System.getProperty("orderPreviewDir")!!).apply { deleteRecursively(); mkdirs() }

        val since = Instant.now().minus(hours.toLong(), ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val variables = JSONObject().put("query", "created_at:>='$since'").put("cursor", JSONObject.NULL)
        val raw = post(JSONObject().put("query", ShopifyClient.ORDERS_QUERY).put("variables", variables).toString())
        val json = JSONObject(raw)
        File(outDir, "response.json").writeText(json.toString(2))

        val data = json.optJSONObject("data") ?: error("Shopify returned no data: ${raw.take(500)}")
        val orders = ShopifyClient.parseOrders(data).reversed()
        val header = BuildConfig.TICKET_HEADER.ifEmpty { "KITCHEN" }
        // Times are shown in the store's timezone, which is what the kitchen device runs on
        val zone = ZoneId.of(
            JSONObject(post(JSONObject().put("query", "{ shop { ianaTimezone } }").toString()))
                .getJSONObject("data").getJSONObject("shop").getString("ianaTimezone"),
        )
        val report = buildString {
            appendLine("${orders.size} order(s) placed since ${since.atZone(zone).toLocalDate()}, newest first. Times in $zone")
            json.optJSONArray("errors")?.let { appendLine("Shopify warnings: $it") }
            for (order in orders) {
                val state = when {
                    order.cancelled -> "cancelled, the app skips it"
                    order.isTaggedPrinted -> "already tagged printed"
                    else -> "not printed yet, the app will print it"
                }
                appendLine()
                appendLine("${order.name}  ($state)")
                appendLine(EscPosPreview.render(TicketFormatter.format(order, header, zone)))
            }
        }
        File(outDir, "tickets.txt").writeText(report)
        println(report)
    }

    private fun post(body: String): String {
        val request = Request.Builder()
            .url("https://${BuildConfig.SHOPIFY_STORE_DOMAIN}/admin/api/${BuildConfig.SHOPIFY_API_VERSION.ifEmpty { Settings.DEFAULT_API_VERSION }}/graphql.json")
            .header("X-Shopify-Access-Token", BuildConfig.SHOPIFY_ACCESS_TOKEN)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return ShopifyClient.defaultHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Shopify HTTP ${response.code}: ${text.take(300)}" }
            text
        }
    }
}
