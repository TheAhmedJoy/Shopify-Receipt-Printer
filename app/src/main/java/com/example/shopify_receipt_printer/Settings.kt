package com.example.shopify_receipt_printer

import android.content.Context
import androidx.core.content.edit

/**
 * Device-local configuration. Store domain, token and API version default to the values built into
 * the APK from local.properties, so a fresh install needs no setup. Nothing secret is in the repo.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The store's myshopify.com domain, e.g. "my-store.myshopify.com". */
    var storeDomain: String
        get() = prefs.getString(KEY_STORE_DOMAIN, null) ?: BuildConfig.SHOPIFY_STORE_DOMAIN
        set(value) = prefs.edit { putString(KEY_STORE_DOMAIN, normalizeDomain(value)) }

    var accessToken: String
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)?.ifEmpty { null } ?: BuildConfig.SHOPIFY_ACCESS_TOKEN
        set(value) = prefs.edit { putString(KEY_ACCESS_TOKEN, value.trim()) }

    var apiVersion: String
        get() = prefs.getString(KEY_API_VERSION, null) ?: BuildConfig.SHOPIFY_API_VERSION.ifEmpty { DEFAULT_API_VERSION }
        set(value) = prefs.edit { putString(KEY_API_VERSION, value.trim()) }

    var printerHost: String
        get() = prefs.getString(KEY_PRINTER_HOST, "")!!
        set(value) = prefs.edit { putString(KEY_PRINTER_HOST, value.trim()) }

    var printerPort: Int
        get() = prefs.getInt(KEY_PRINTER_PORT, DEFAULT_PRINTER_PORT)
        set(value) = prefs.edit { putInt(KEY_PRINTER_PORT, value) }

    var pollSeconds: Int
        get() = prefs.getInt(KEY_POLL_SECONDS, DEFAULT_POLL_SECONDS)
        set(value) = prefs.edit { putInt(KEY_POLL_SECONDS, value.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS)) }

    /** Never look further back than this, however long the device was offline. */
    var lookbackHours: Int
        get() = prefs.getInt(KEY_LOOKBACK_HOURS, DEFAULT_LOOKBACK_HOURS)
        set(value) = prefs.edit { putInt(KEY_LOOKBACK_HOURS, value.coerceIn(1, 24 * 30)) }

    var ticketHeader: String
        get() = prefs.getString(KEY_TICKET_HEADER, DEFAULT_TICKET_HEADER)!!
        set(value) = prefs.edit { putString(KEY_TICKET_HEADER, value.trim().ifEmpty { DEFAULT_TICKET_HEADER }) }

    /** Orders created before this instant (epoch millis) are ignored. 0 means not set yet. */
    var printSinceMillis: Long
        get() = prefs.getLong(KEY_PRINT_SINCE, 0L)
        set(value) = prefs.edit { putLong(KEY_PRINT_SINCE, value) }

    /** True when [printerHost] was found by a network scan rather than typed in, so it may be re-scanned. */
    var printerAutoDiscovered: Boolean
        get() = prefs.getBoolean(KEY_PRINTER_AUTO, false)
        set(value) = prefs.edit { putBoolean(KEY_PRINTER_AUTO, value) }

    /**
     * Whether the service should be running. On by default so a fresh install starts printing
     * without anyone pressing Start. The boot receiver only starts it when this is true.
     */
    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SERVICE_ENABLED, value) }

    /** The token was built into the APK, so the settings screen should neither show nor edit it. */
    val hasBuiltInToken: Boolean get() = BuildConfig.SHOPIFY_ACCESS_TOKEN.isNotEmpty()

    /** Names of required Shopify settings that are still empty. The printer is found automatically. */
    fun missingFields(): List<String> = buildList {
        if (storeDomain.isEmpty()) add("store domain")
        if (accessToken.isEmpty()) add("access token")
        if (apiVersion.isEmpty()) add("API version")
    }

    companion object {
        const val DEFAULT_API_VERSION = "2026-10"
        const val DEFAULT_PRINTER_PORT = 9100
        const val DEFAULT_POLL_SECONDS = 25
        const val MIN_POLL_SECONDS = 10
        const val MAX_POLL_SECONDS = 600
        const val DEFAULT_LOOKBACK_HOURS = 48
        // The business name is built in from local.properties so it stays out of the repo
        val DEFAULT_TICKET_HEADER: String = BuildConfig.TICKET_HEADER.ifEmpty { "KITCHEN" }

        private const val KEY_STORE_DOMAIN = "store_domain"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_API_VERSION = "api_version"
        private const val KEY_PRINTER_HOST = "printer_host"
        private const val KEY_PRINTER_PORT = "printer_port"
        private const val KEY_PRINTER_AUTO = "printer_auto"
        private const val KEY_POLL_SECONDS = "poll_seconds"
        private const val KEY_LOOKBACK_HOURS = "lookback_hours"
        private const val KEY_TICKET_HEADER = "ticket_header"
        private const val KEY_PRINT_SINCE = "print_since"
        private const val KEY_SERVICE_ENABLED = "service_enabled"

        /** Accepts pasted URLs like "https://foo.myshopify.com/admin" and keeps just the host. */
        fun normalizeDomain(input: String): String =
            input.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .lowercase()
    }
}
