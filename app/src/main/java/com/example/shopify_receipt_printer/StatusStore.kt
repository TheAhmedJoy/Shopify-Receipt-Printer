package com.example.shopify_receipt_printer

import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the status screen shows: last poll, last print, last error and a short activity log. */
class StatusStore(context: Context) {
    private val prefs = context.getSharedPreferences("status", Context.MODE_PRIVATE)

    val lastPollMillis: Long get() = prefs.getLong(KEY_LAST_POLL, 0L)
    val lastPollResult: String get() = prefs.getString(KEY_LAST_POLL_RESULT, "")!!
    val lastPrinted: String get() = prefs.getString(KEY_LAST_PRINTED, "")!!
    val lastPrintedMillis: Long get() = prefs.getLong(KEY_LAST_PRINTED_AT, 0L)
    val lastError: String get() = prefs.getString(KEY_LAST_ERROR, "")!!
    val lastErrorMillis: Long get() = prefs.getLong(KEY_LAST_ERROR_AT, 0L)
    val log: List<String> get() = prefs.getString(KEY_LOG, "")!!.lines().filter { it.isNotEmpty() }

    fun recordPoll(result: String) = prefs.edit {
        putLong(KEY_LAST_POLL, System.currentTimeMillis())
        putString(KEY_LAST_POLL_RESULT, result)
    }

    fun recordPrinted(orderName: String) {
        prefs.edit {
            putString(KEY_LAST_PRINTED, orderName)
            putLong(KEY_LAST_PRINTED_AT, System.currentTimeMillis())
        }
        log("Printed $orderName")
    }

    fun recordError(message: String) {
        // Repeated identical errors (e.g. printer off for an hour) only get logged once
        val repeated = message == lastError
        prefs.edit {
            putString(KEY_LAST_ERROR, message)
            putLong(KEY_LAST_ERROR_AT, System.currentTimeMillis())
        }
        if (!repeated) log("Error: $message")
    }

    fun clearError() = prefs.edit { remove(KEY_LAST_ERROR); remove(KEY_LAST_ERROR_AT) }

    fun log(message: String) {
        val stamp = TIME_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()))
        val lines = (listOf("$stamp  $message") + log).take(MAX_LOG_LINES)
        prefs.edit { putString(KEY_LOG, lines.joinToString("\n")) }
    }

    companion object {
        private const val MAX_LOG_LINES = 50
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("d MMM HH:mm:ss")

        private const val KEY_LAST_POLL = "last_poll"
        private const val KEY_LAST_POLL_RESULT = "last_poll_result"
        private const val KEY_LAST_PRINTED = "last_printed"
        private const val KEY_LAST_PRINTED_AT = "last_printed_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_ERROR_AT = "last_error_at"
        private const val KEY_LOG = "log"
    }
}
