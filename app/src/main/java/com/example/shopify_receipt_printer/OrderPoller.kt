package com.example.shopify_receipt_printer

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/** One poll cycle: check the printer, fetch unprinted orders, print each, then tag it in Shopify. */
class OrderPoller(context: Context) {
    private val settings = Settings(context)
    private val status = StatusStore(context)
    private val printedLog = PrintedLog(context)
    private var lastDiscoveryMillis = 0L

    /** Returns a short summary for the notification. Errors are recorded in [StatusStore], not thrown. */
    suspend fun pollOnce(): String {
        val missing = settings.missingFields()
        if (missing.isNotEmpty()) {
            val msg = "Missing settings: ${missing.joinToString()}"
            status.recordError(msg)
            status.recordPoll("Not configured")
            return msg
        }

        if (settings.printSinceMillis == 0L) {
            // First run on this device: only print orders from here on, not the whole history. Recent unprinted
            // orders from before are listed on the PRINTER CONNECTED ticket instead, so none go unnoticed
            settings.printSinceMillis = System.currentTimeMillis()
            settings.skippedOrdersCheckPending = true
            status.log("First start, printing orders placed from now on")
        }
        // No upper age limit: an order missed while the device was off still prints, marked LATE
        val since = Instant.ofEpochMilli(settings.printSinceMillis)

        if (!ensurePrinter()) {
            status.recordPoll("Waiting for a printer")
            return "No printer chosen"
        }
        val host = settings.printerHost
        val printerProblem = checkPrinter(host)

        val shopify = ShopifyClient(settings.storeDomain, settings.accessToken, settings.apiVersion)
        val orders = try {
            shopify.fetchUnprintedOrders(since)
        } catch (e: Exception) {
            val msg = "Shopify: ${e.message ?: e.javaClass.simpleName}"
            status.recordError(msg)
            status.recordPoll("Failed")
            return msg
        }
        val warnings = shopify.lastWarnings
        if (settings.skippedOrdersCheckPending) findSkippedOrders(shopify, since)

        if (printerProblem != null) return printerFailed(printerProblem, orders)

        var printed = 0
        var hadError = warnings.isNotEmpty()
        warnings.firstOrNull()?.let { status.recordError("Shopify warning: $it") }
        // Only now is the store connection confirmed, so the ticket never claims success with a rejected token
        if (settings.connectedTicketPending && !settings.skippedOrdersCheckPending && !printConnectedTicket(host)) {
            hadError = true
        }
        for (order in orders) {
            if (order.cancelled || order.isTaggedPrinted) continue

            if (order.id !in printedLog) {
                // Printing and recording it must not be split by a service stop, or the order prints twice
                val error = withContext(NonCancellable) {
                    try {
                        send(host, TicketFormatter.format(order, settings.ticketHeader))
                        printedLog.add(order.id)
                        status.recordPrinted(order.name)
                        null
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                }
                // Stop here so orders keep printing in sequence once the printer is back
                if (error != null) return printerFailed(error, orders)
                printed++
            }

            // Also retries tagging for orders printed earlier whose tag call failed
            try {
                shopify.addPrintedTag(order.id)
            } catch (e: Exception) {
                hadError = true
                status.recordError("Could not tag ${order.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        if (!hadError) status.clearError()
        val summary = if (printed > 0) "Printed $printed order(s)" else "No new orders"
        status.recordPoll(summary)
        return summary
    }

    /** Sends to the chosen printer, remembering once it answers status queries so silence later counts as failure. */
    private suspend fun send(host: String, data: ByteArray) {
        if (PrinterClient.send(host, settings.printerPort, data, requireStatus = settings.printerReportsStatus)) {
            settings.printerReportsStatus = true
        }
    }

    /**
     * Checks the chosen printer on every poll, so a printer that is off or out of paper is noticed even
     * when no orders are waiting. Returns the problem, or null when it is ready.
     */
    private suspend fun checkPrinter(host: String): String? = try {
        if (PrinterClient.check(host, settings.printerPort, requireStatus = settings.printerReportsStatus)) {
            settings.printerReportsStatus = true
        }
        if (settings.printerCandidates.isNotEmpty()) {
            settings.printerCandidates = emptyList()
            status.log("Printer at $host is answering again")
        }
        null
    } catch (e: Exception) {
        val problem = e.message ?: e.javaClass.simpleName
        if (e is PrinterUnreachableException) offerOtherPrinters(host)
        val others = settings.printerCandidates
        if (others.isEmpty()) problem
        else sentence(problem, "Another device answered at ${others.joinToString()}. If that is the kitchen printer, choose it in the app")
    }

    private fun printerFailed(problem: String, orders: List<Order>): String {
        val waiting = orders.count { !it.cancelled && !it.isTaggedPrinted && it.id !in printedLog }
        val msg = if (waiting > 0) sentence(problem, "$waiting order(s) waiting to print") else problem
        status.recordError(msg)
        status.recordPoll(if (waiting > 0) "Printer problem, $waiting waiting" else "Printer problem")
        return msg
    }

    /**
     * Makes sure a printer is chosen. It is picked automatically only when exactly one device answers;
     * with more than one, someone has to choose in the app.
     */
    private suspend fun ensurePrinter(): Boolean {
        if (settings.printerHost.isNotEmpty()) return true
        val waiting = settings.printerCandidates
        if (waiting.isNotEmpty()) {
            status.recordError("${waiting.size} devices answered as printers. Open the app and choose the kitchen printer")
            return false
        }
        if (!discoveryAllowed()) return false
        status.log("Searching the network for the printer")
        val found = PrinterDiscovery.scan(settings.printerPort)
        when (found.size) {
            0 -> status.recordError("No printer found on port ${settings.printerPort}. Is it on and connected to the same WiFi?")
            1 -> {
                settings.choosePrinter(found.single())
                status.log("Using printer at ${found.single()}")
                return true
            }
            else -> {
                settings.printerCandidates = found
                status.log("Found ${found.size} devices answering as printers: ${found.joinToString()}")
                status.recordError("${found.size} devices answered as printers. Open the app and choose the kitchen printer")
            }
        }
        return false
    }

    /**
     * The chosen printer can't be reached; its address may have changed. Look for other devices and offer
     * them in the app, but never switch by itself: another device on port 9100 may not be the kitchen printer.
     */
    private suspend fun offerOtherPrinters(current: String) {
        if (!discoveryAllowed()) return
        val others = PrinterDiscovery.scan(settings.printerPort) - current
        if (others != settings.printerCandidates && others.isNotEmpty()) {
            status.log("Printer at $current isn't answering. Also answering: ${others.joinToString()}")
        }
        settings.printerCandidates = others
    }

    /** On first run, lists recent unprinted orders placed before install so the kitchen can check them. */
    private suspend fun findSkippedOrders(shopify: ShopifyClient, since: Instant) {
        try {
            val skipped = shopify.fetchUnprintedOrders(since.minus(SKIPPED_LOOKBACK_DAYS, ChronoUnit.DAYS))
                .filter { it.createdAt < since && !it.isTaggedPrinted }
                .map { it.name }
            settings.skippedOrders = skipped
            settings.skippedOrdersCheckPending = false
            if (skipped.isNotEmpty()) {
                status.log("Not printing ${skipped.size} order(s) placed before the app started: ${skipped.joinToString()}")
            }
        } catch (_: Exception) {
            // Tried again next poll; the connected ticket waits for it
        }
    }

    /** Returns false if it couldn't be printed; it is tried again next poll. */
    private suspend fun printConnectedTicket(host: String): Boolean =
        try {
            val ticket = TicketFormatter.connectedTicket(settings.ticketHeader, host, skippedOrders = settings.skippedOrders)
            send(host, ticket)
            settings.connectedTicketPending = false
            settings.skippedOrders = emptyList()
            status.log("Printed the PRINTER CONNECTED ticket")
            true
        } catch (e: Exception) {
            status.recordError("Printer $host: ${e.message ?: e.javaClass.simpleName}")
            false
        }

    /** Scans take several seconds, so don't repeat them every poll while the printer is off. */
    private fun discoveryAllowed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDiscoveryMillis < DISCOVERY_COOLDOWN_MS) return false
        lastDiscoveryMillis = now
        return true
    }

    companion object {
        private const val DISCOVERY_COOLDOWN_MS = 2 * 60 * 1000L
        private const val SKIPPED_LOOKBACK_DAYS = 7L

        /** Joins two sentences, adding a full stop unless the first already ends with punctuation. */
        fun sentence(first: String, second: String): String =
            if (first.trimEnd().lastOrNull() in listOf('.', '?', '!')) "${first.trimEnd()} $second" else "${first.trimEnd()}. $second"
    }
}
