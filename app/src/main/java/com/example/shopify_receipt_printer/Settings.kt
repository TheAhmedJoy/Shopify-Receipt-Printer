package com.example.shopify_receipt_printer

import android.content.Context
import androidx.core.content.edit

/**
 * Configuration. The Shopify connection and ticket header are built into the APK from
 * local.properties, so a fresh install needs no setup and the screen has nothing to edit there.
 * Only what the device learns for itself (the printer address, the first-run time) is stored.
 * Nothing secret is in the repo.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The store's myshopify.com domain, e.g. "my-store.myshopify.com". */
    val storeDomain: String = BuildConfig.SHOPIFY_STORE_DOMAIN
    val accessToken: String = BuildConfig.SHOPIFY_ACCESS_TOKEN
    val apiVersion: String = BuildConfig.SHOPIFY_API_VERSION.ifEmpty { DEFAULT_API_VERSION }
    val ticketHeader: String = BuildConfig.TICKET_HEADER.ifEmpty { "KITCHEN" }
    val printerPort: Int = DEFAULT_PRINTER_PORT
    val pollSeconds: Int = DEFAULT_POLL_SECONDS

    /** The chosen printer. Once set it is kept: the app never switches printers by itself. */
    val printerHost: String
        get() = prefs.getString(KEY_PRINTER_HOST, "")!!

    /** Devices that answered on the printer port, waiting for someone to pick the kitchen printer. */
    var printerCandidates: List<String>
        get() = prefs.getString(KEY_PRINTER_CANDIDATES, "")!!.split(',').filter { it.isNotEmpty() }
        set(value) = prefs.edit { putString(KEY_PRINTER_CANDIDATES, value.joinToString(",")) }

    /** True once [printerHost] has answered a status query, so going quiet afterwards counts as a failure. */
    var printerReportsStatus: Boolean
        get() = prefs.getBoolean(KEY_PRINTER_REPORTS_STATUS, false)
        set(value) = prefs.edit { putBoolean(KEY_PRINTER_REPORTS_STATUS, value) }

    /** A PRINTER CONNECTED ticket is due, printed once the store connection has been confirmed. */
    var connectedTicketPending: Boolean
        get() = prefs.getBoolean(KEY_CONNECTED_TICKET_PENDING, false)
        set(value) = prefs.edit { putBoolean(KEY_CONNECTED_TICKET_PENDING, value) }

    /** Orders created before this instant (epoch millis) are ignored. 0 means not set yet. */
    var printSinceMillis: Long
        get() = prefs.getLong(KEY_PRINT_SINCE, 0L)
        set(value) = prefs.edit { putLong(KEY_PRINT_SINCE, value) }

    /** Set on first run: recent orders placed before install still need listing on the connected ticket. */
    var skippedOrdersCheckPending: Boolean
        get() = prefs.getBoolean(KEY_SKIPPED_CHECK_PENDING, false)
        set(value) = prefs.edit { putBoolean(KEY_SKIPPED_CHECK_PENDING, value) }

    /** Unprinted orders placed shortly before install, e.g. "#1005,#1006", to list on the connected ticket. */
    var skippedOrders: List<String>
        get() = prefs.getString(KEY_SKIPPED_ORDERS, "")!!.split(',').filter { it.isNotEmpty() }
        set(value) = prefs.edit { putString(KEY_SKIPPED_ORDERS, value.joinToString(",")) }

    /** Makes [host] the printer, clears any pending choice and queues a PRINTER CONNECTED ticket. */
    fun choosePrinter(host: String) = prefs.edit {
        putString(KEY_PRINTER_HOST, host.trim())
        putString(KEY_PRINTER_CANDIDATES, "")
        putBoolean(KEY_PRINTER_REPORTS_STATUS, false)
        putBoolean(KEY_CONNECTED_TICKET_PENDING, true)
    }

    /** Names of required Shopify settings missing from the build. The printer is found automatically. */
    fun missingFields(): List<String> = buildList {
        if (storeDomain.isEmpty()) add("store domain")
        if (accessToken.isEmpty()) add("access token")
    }

    companion object {
        const val DEFAULT_API_VERSION = "2026-10"
        const val DEFAULT_PRINTER_PORT = 9100
        const val DEFAULT_POLL_SECONDS = 25

        private const val KEY_PRINTER_HOST = "printer_host"
        private const val KEY_PRINTER_CANDIDATES = "printer_candidates"
        private const val KEY_PRINTER_REPORTS_STATUS = "printer_reports_status"
        private const val KEY_CONNECTED_TICKET_PENDING = "connected_ticket_pending"
        private const val KEY_PRINT_SINCE = "print_since"
        private const val KEY_SKIPPED_CHECK_PENDING = "skipped_check_pending"
        private const val KEY_SKIPPED_ORDERS = "skipped_orders"
    }
}
