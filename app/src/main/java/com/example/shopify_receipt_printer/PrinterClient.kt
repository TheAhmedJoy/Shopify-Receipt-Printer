package com.example.shopify_receipt_printer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Sends raw ESC/POS bytes to a network printer (JetDirect / port 9100). */
object PrinterClient {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val WRITE_TIMEOUT_MS = 10_000

    // Keeps a test print and an order ticket from interleaving on the wire
    private val lock = Mutex()

    suspend fun send(host: String, port: Int, data: ByteArray) = lock.withLock {
        withContext(Dispatchers.IO) {
            Socket().use { socket ->
                socket.soTimeout = WRITE_TIMEOUT_MS
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.getOutputStream().apply {
                    write(data)
                    flush()
                }
                socket.shutdownOutput()
            }
        }
    }
}
