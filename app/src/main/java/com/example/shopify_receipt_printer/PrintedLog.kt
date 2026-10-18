package com.example.shopify_receipt_printer

import android.content.Context
import androidx.core.content.edit

/**
 * Local record of order IDs already sent to the printer.
 *
 * The Shopify `printed` tag is the durable marker, but order search can lag a few seconds behind a
 * tag change, and tagging can fail after a successful print. This list stops either case from
 * producing a second ticket.
 */
class PrintedLog(context: Context) {
    private val prefs = context.getSharedPreferences("printed_log", Context.MODE_PRIVATE)

    private fun ids(): List<String> = prefs.getString(KEY_IDS, "")!!.lines().filter { it.isNotEmpty() }

    operator fun contains(orderId: String): Boolean = orderId in ids()

    fun add(orderId: String) {
        val updated = (listOf(orderId) + ids().filter { it != orderId }).take(MAX_ENTRIES)
        prefs.edit(commit = true) { putString(KEY_IDS, updated.joinToString("\n")) }
    }

    companion object {
        private const val KEY_IDS = "ids"
        private const val MAX_ENTRIES = 1000
    }
}
