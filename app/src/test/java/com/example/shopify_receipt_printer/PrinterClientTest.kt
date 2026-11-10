package com.example.shopify_receipt_printer

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class PrinterClientTest {
    /**
     * A stand-in network printer. It records the job bytes it receives and answers DLE EOT status
     * queries with [replies] (keyed by n), or not at all when [replies] is null.
     */
    private inner class FakePrinter(
        private val replies: Map<Int, Int>? = HEALTHY,
        private val hangUpAtOnce: Boolean = false,
    ) {
        val server = ServerSocket(0)
        val received = ByteArrayOutputStream()
        val port get() = server.localPort
        private val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    if (hangUpAtOnce) return@use
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val pending = ArrayDeque<Int>()
                    while (true) {
                        val b = input.read()
                        if (b < 0) break
                        pending.addLast(b)
                        if (pending.size == 3 && pending[0] == 0x10 && pending[1] == 0x04) {
                            replies?.get(pending[2])?.let { output.write(it); output.flush() }
                            pending.clear()
                        } else if (pending.size == 3 || (pending.size >= 1 && pending[0] != 0x10) ||
                            (pending.size >= 2 && pending[1] != 0x04)
                        ) {
                            pending.forEach { received.write(it) }
                            pending.clear()
                        }
                    }
                    pending.forEach { received.write(it) }
                }
            }
        }

        fun close() {
            server.close()
            worker.join(2_000)
        }
    }

    private val printers = mutableListOf<FakePrinter>()
    private fun printer(replies: Map<Int, Int>? = HEALTHY, hangUpAtOnce: Boolean = false) =
        FakePrinter(replies, hangUpAtOnce).also { printers += it }

    @After
    fun closePrinters() = printers.forEach { it.close() }

    private val job = "HELLO KITCHEN\n".toByteArray()

    @Test
    fun readyPrinterConfirmsAndReceivesTheJob() {
        val p = printer()
        assertTrue(runBlocking { PrinterClient.send("127.0.0.1", p.port, job) })
        p.close()
        assertArrayEquals(job, p.received.toByteArray())
    }

    @Test
    fun printerThatHangsUpIsNotCountedAsPrinted() {
        val p = printer(hangUpAtOnce = true)
        try {
            runBlocking { PrinterClient.send("127.0.0.1", p.port, job) }
            fail("send() should fail when the printer drops the connection")
        } catch (e: PrinterException) {
            assertTrue(e.message!!.contains("connection"))
        }
    }

    @Test
    fun printerOutOfPaperIsNotSentTheJob() {
        val p = printer(HEALTHY + mapOf(2 to (0x12 or 0x20), 4 to (0x12 or 0x60)))
        try {
            runBlocking { PrinterClient.send("127.0.0.1", p.port, job) }
            fail("send() should fail when the printer is out of paper")
        } catch (e: PrinterException) {
            assertEquals("The printer is out of paper", e.message)
        }
        p.close()
        assertEquals(0, p.received.size())
    }

    @Test
    fun printerWithoutStatusSupportStillPrints() {
        val p = printer(replies = null)
        assertFalse(runBlocking { PrinterClient.send("127.0.0.1", p.port, job) })
        p.close()
        assertArrayEquals(job, p.received.toByteArray())
    }

    @Test
    fun printerThatAnsweredBeforeMustKeepAnswering() {
        val p = printer(replies = null)
        try {
            runBlocking { PrinterClient.send("127.0.0.1", p.port, job, requireStatus = true) }
            fail("send() should fail when a printer that used to answer goes quiet")
        } catch (e: PrinterException) {
            assertEquals("The printer stopped answering status checks", e.message)
        }
    }

    @Test
    fun checkPrintsNothing() {
        val p = printer()
        assertTrue(runBlocking { PrinterClient.check("127.0.0.1", p.port) })
        p.close()
        assertEquals(0, p.received.size())
    }

    @Test
    fun nothingListeningIsUnreachable() {
        val port = ServerSocket(0).use { it.localPort } // free port with nothing behind it
        try {
            runBlocking { PrinterClient.check("127.0.0.1", port) }
            fail("check() should fail with nothing listening")
        } catch (e: PrinterUnreachableException) {
            assertTrue(e.message!!.startsWith("Can't reach the printer"))
        }
    }

    @Test
    fun decodesStatusBytes() {
        fun status(vararg b: Int) = b.map { it.toByte() }.toByteArray()
        assertNull(PrinterStatus.problem(status(0x12, 0x12, 0x12, 0x12)))
        assertEquals("The printer cover is open", PrinterStatus.problem(status(0x12, 0x16, 0x12, 0x12)))
        assertEquals("The printer is out of paper", PrinterStatus.problem(status(0x12, 0x12, 0x12, 0x72)))
        assertEquals("The printer's paper cutter is jammed", PrinterStatus.problem(status(0x12, 0x12, 0x1A, 0x12)))
        assertEquals("The printer is offline", PrinterStatus.problem(status(0x1A, 0x12, 0x12, 0x12)))
        // Paper near end alone is not a problem
        assertNull(PrinterStatus.problem(status(0x12, 0x12, 0x12, 0x1E)))
        assertTrue(PrinterStatus.isStatusReply(status(0x12, 0x16)))
        assertFalse(PrinterStatus.isStatusReply(status()))
        assertFalse(PrinterStatus.isStatusReply(status('O'.code, 'K'.code)))
    }

    companion object {
        private val HEALTHY = mapOf(1 to 0x12, 2 to 0x12, 3 to 0x12, 4 to 0x12)
    }
}
