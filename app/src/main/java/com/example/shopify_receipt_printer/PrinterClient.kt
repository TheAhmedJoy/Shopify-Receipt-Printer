package com.example.shopify_receipt_printer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

open class PrinterException(message: String) : IOException(message)

/** Nothing answered at the printer's address: it is off, off the network, or has a new address. */
class PrinterUnreachableException(message: String) : PrinterException(message)

/**
 * Sends raw ESC/POS bytes to a network printer (JetDirect / port 9100), checking the printer's
 * real-time status (DLE EOT) before and after each job.
 *
 * A successful network send alone proves nothing: a printer can accept the data and still be out
 * of paper, or drop the connection. When the printer answers status queries, a job only counts as
 * sent if the printer was ready, received the data, and was still connected and ready afterwards.
 * Printers that never answer are still printed to, but a printer that answered once and then goes
 * quiet is treated as a failure (see `requireStatus`).
 */
object PrinterClient {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val WRITE_TIMEOUT_MS = 10_000
    private const val STATUS_TIMEOUT_MS = 2_000
    private const val STATUS_AFTER_JOB_TIMEOUT_MS = 5_000

    /** DLE EOT 1-4: printer, offline cause, error cause and paper sensor status. One byte comes back for each. */
    private val STATUS_QUERY = byteArrayOf(0x10, 0x04, 0x01, 0x10, 0x04, 0x02, 0x10, 0x04, 0x03, 0x10, 0x04, 0x04)

    // Keeps a test print and an order ticket from interleaving on the wire
    private val lock = Mutex()

    /**
     * Connects and checks the printer is ready without printing anything. Returns true when the
     * printer reported its status, false when it accepted the connection but doesn't answer status
     * queries. Throws when it can't be reached, reports a problem, or drops the connection.
     */
    suspend fun check(host: String, port: Int, requireStatus: Boolean = false): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            connect(host, port).use { socket -> readStatus(socket, STATUS_TIMEOUT_MS, requireStatus) }
        }
    }

    /** Sends a job. Returns true when the printer confirmed its status before and after; see [check]. */
    suspend fun send(host: String, port: Int, data: ByteArray, requireStatus: Boolean = false): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            connect(host, port).use { socket ->
                val reports = readStatus(socket, STATUS_TIMEOUT_MS, requireStatus)
                socket.getOutputStream().apply {
                    write(data)
                    flush()
                }
                // Real-time queries are answered as soon as they arrive, so this confirms the printer is still
                // connected and ready after taking the job. It can't confirm the paper physically came out.
                if (reports) readStatus(socket, STATUS_AFTER_JOB_TIMEOUT_MS, requireStatus = true)
                socket.shutdownOutput()
                reports
            }
        }
    }

    private fun connect(host: String, port: Int): Socket = Socket().apply {
        soTimeout = WRITE_TIMEOUT_MS
        try {
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            close()
            throw PrinterUnreachableException("Can't reach the printer at $host. Is it switched on and on the same WiFi?")
        }
    }

    /** Sends the status queries and reads the answers. Returns false if the printer doesn't answer at all. */
    private fun readStatus(socket: Socket, timeoutMs: Int, requireStatus: Boolean): Boolean {
        socket.getOutputStream().apply {
            write(STATUS_QUERY)
            flush()
        }
        val reply = ByteArray(4)
        var count = 0
        socket.soTimeout = timeoutMs
        try {
            while (count < reply.size) {
                val b = socket.getInputStream().read()
                if (b < 0) throw PrinterException("The printer closed the connection")
                reply[count++] = b.toByte()
            }
        } catch (_: SocketTimeoutException) {
            // Some printers answer fewer queries; judge what did arrive
        } catch (e: PrinterException) {
            throw e
        } catch (e: IOException) {
            throw PrinterException("The printer dropped the connection")
        } finally {
            socket.soTimeout = WRITE_TIMEOUT_MS
        }

        val answers = reply.copyOf(count)
        if (!PrinterStatus.isStatusReply(answers)) {
            if (requireStatus) throw PrinterException("The printer stopped answering status checks")
            return false
        }
        PrinterStatus.problem(answers)?.let { throw PrinterException(it) }
        return true
    }
}

/** Decodes ESC/POS DLE EOT replies, in the order printer (1), offline cause (2), error cause (3), paper (4). */
object PrinterStatus {
    /** Every DLE EOT reply has bits 1 and 4 set and bits 0 and 7 clear. */
    fun isStatusReply(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() && bytes.all { (it.toInt() and 0x93) == 0x12 }

    /** A plain-English description of the first problem reported, or null when the printer is ready. */
    fun problem(bytes: ByteArray): String? {
        fun bit(index: Int, mask: Int) = bytes.size > index && (bytes[index].toInt() and mask) != 0
        return when {
            bit(1, 0x04) -> "The printer cover is open"
            bit(3, 0x60) || bit(1, 0x20) -> "The printer is out of paper"
            bit(2, 0x08) -> "The printer's paper cutter is jammed"
            bit(2, 0x60) || bit(1, 0x40) -> "The printer has reported an error"
            bit(0, 0x08) -> "The printer is offline"
            else -> null
        }
    }
}
