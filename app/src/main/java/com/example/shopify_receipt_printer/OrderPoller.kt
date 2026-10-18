package com.example.shopify_receipt_printer

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/** One poll cycle: fetch unprinted orders, print each, then tag it in Shopify. */
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

        val now = Instant.now()
        if (settings.printSinceMillis == 0L) {
            // First run on this device: only print orders from here on, not the whole history
            settings.printSinceMillis = now.toEpochMilli()
            status.log("First start, printing orders placed from now on")
        }
        val since = maxOf(
            Instant.ofEpochMilli(settings.printSinceMillis),
            now.minus(settings.lookbackHours.toLong(), ChronoUnit.HOURS),
        )

        if (!ensurePrinter()) {
            status.recordPoll("Looking for printer")
            return "No printer found"
        }

        val shopify = ShopifyClient(settings.storeDomain, settings.accessToken, settings.apiVersion)
        val orders = try {
            shopify.fetchUnprintedOrders(since)
        } catch (e: Exception) {
            val msg = "Shopify: ${e.message ?: e.javaClass.simpleName}"
            status.recordError(msg)
            status.recordPoll("Failed")
            return msg
        }
        shopify.lastWarnings.firstOrNull()?.let { status.recordError("Shopify warning: $it") }

        var printed = 0
        var hadError = shopify.lastWarnings.isNotEmpty()
        for (order in orders) {
            if (order.cancelled || order.isTaggedPrinted) continue

            if (order.id !in printedLog) {
                // Printing and recording it must not be split by a service stop, or the order prints twice
                val sent = withContext(NonCancellable) {
                    try {
                        PrinterClient.send(
                            settings.printerHost,
                            settings.printerPort,
                            TicketFormatter.format(order, settings.ticketHeader),
                        )
                        printedLog.add(order.id)
                        status.recordPrinted(order.name)
                        true
                    } catch (e: Exception) {
                        status.recordError("Printer ${settings.printerHost}: ${e.message ?: e.javaClass.simpleName}")
                        false
                    }
                }
                if (!sent) {
                    // DHCP may have given the printer a new address. Look for it before the next poll
                    if (settings.printerAutoDiscovered) rediscoverPrinter()
                    // Stop here so orders keep printing in sequence once the printer is back
                    status.recordPoll("Printer unreachable, ${orders.size} waiting")
                    return "Printer unreachable"
                }
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

    /** Makes sure a printer address is set, scanning the network on first run. */
    private suspend fun ensurePrinter(): Boolean {
        if (settings.printerHost.isNotEmpty()) return true
        if (!discoveryAllowed()) return false
        status.log("Searching the network for the printer")
        val found = PrinterDiscovery.scan(settings.printerPort)
        if (found.isEmpty()) {
            status.recordError("No printer found on port ${settings.printerPort}. Is it on and connected to the same WiFi?")
            return false
        }
        usePrinter(found.first(), found)
        return true
    }

    private suspend fun rediscoverPrinter() {
        if (!discoveryAllowed()) return
        val current = settings.printerHost
        if (PrinterDiscovery.isOpen(current, settings.printerPort)) return
        val found = PrinterDiscovery.scan(settings.printerPort)
        found.firstOrNull { it != current }?.let { usePrinter(it, found) }
    }

    /** Scans take several seconds, so don't repeat them every poll while the printer is off. */
    private fun discoveryAllowed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDiscoveryMillis < DISCOVERY_COOLDOWN_MS) return false
        lastDiscoveryMillis = now
        return true
    }

    private suspend fun usePrinter(host: String, allFound: List<String>) {
        settings.printerHost = host
        settings.printerAutoDiscovered = true
        val others = (allFound - host).takeIf { it.isNotEmpty() }?.let { " (also answering: ${it.joinToString()})" }.orEmpty()
        status.log("Using printer at $host$others")
        // A ticket the kitchen can see confirms the right printer was picked
        try {
            PrinterClient.send(host, settings.printerPort, TicketFormatter.connectedTicket(settings.ticketHeader, host))
        } catch (e: Exception) {
            status.recordError("Printer $host: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val DISCOVERY_COOLDOWN_MS = 2 * 60 * 1000L
    }
}
