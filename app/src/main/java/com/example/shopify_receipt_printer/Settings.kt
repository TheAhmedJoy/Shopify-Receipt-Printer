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

    /** Never look further back than this, however long the device was offline. */
    val lookbackHours: Int = DEFAULT_LOOKBACK_HOURS

    var printerHost: String
        get() = prefs.getString(KEY_PRINTER_HOST, "")!!
        set(value) = prefs.edit { putString(KEY_PRINTER_HOST, value.trim()) }

    /** Orders created before this instant (epoch millis) are ignored. 0 means not set yet. */
    var printSinceMillis: Long
        get() = prefs.getLong(KEY_PRINT_SINCE, 0L)
        set(value) = prefs.edit { putLong(KEY_PRINT_SINCE, value) }

    /** True when [printerHost] was found by a network scan rather than typed in, so it may be re-scanned. */
    var printerAutoDiscovered: Boolean
        get() = prefs.getBoolean(KEY_PRINTER_AUTO, false)
        set(value) = prefs.edit { putBoolean(KEY_PRINTER_AUTO, value) }

    /** Names of required Shopify settings missing from the build. The printer is found automatically. */
    fun missingFields(): List<String> = buildList {
        if (storeDomain.isEmpty()) add("store domain")
        if (accessToken.isEmpty()) add("access token")
    }

    companion object {
        const val DEFAULT_API_VERSION = "2026-10"
        const val DEFAULT_PRINTER_PORT = 9100
        const val DEFAULT_POLL_SECONDS = 25
        const val DEFAULT_LOOKBACK_HOURS = 48

        private const val KEY_PRINTER_HOST = "printer_host"
        private const val KEY_PRINTER_AUTO = "printer_auto"
        private const val KEY_PRINT_SINCE = "print_since"
    }
}
