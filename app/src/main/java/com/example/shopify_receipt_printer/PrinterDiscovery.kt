package com.example.shopify_receipt_printer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Finds network printers by scanning the device's /24 subnet for an open raw print port. */
object PrinterDiscovery {
    private const val PROBE_TIMEOUT_MS = 400
    private const val PARALLEL_PROBES = 64

    /** IPs answering on [port], in address order. Empty if nothing answered or there is no LAN. */
    suspend fun scan(port: Int): List<String> = withContext(Dispatchers.IO) {
        val own = localIpv4Addresses()
        val candidates = own
            .map { it.substringBeforeLast('.') }
            .distinct()
            .flatMap { prefix -> (1..254).map { "$prefix.$it" } }
            .filter { it !in own }
        val permits = Semaphore(PARALLEL_PROBES)
        coroutineScope {
            candidates.map { ip -> async { permits.withPermit { ip.takeIf { isOpen(ip, port) } } } }
                .awaitAll()
                .filterNotNull()
        }
    }

    fun isOpen(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }

    private fun localIpv4Addresses(): List<String> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
}
