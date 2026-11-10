package com.example.shopify_receipt_printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class TicketFormatterTest {
    @Test
    fun wrapBreaksOnWordsAndLongWords() {
        assertEquals(listOf("one two", "three"), EscPos.wrap("one two three", 8))
        assertEquals(listOf("abcd", "efgh", "ij"), EscPos.wrap("abcdefghij", 4))
        assertEquals(listOf("a", "", "b"), EscPos.wrap("a\n\nb\n", 10))
        assertEquals(listOf(""), EscPos.wrap("", 10))
    }

    @Test
    fun toAsciiStripsAccentsAndSmartPunctuation() {
        assertEquals("Creme brulee - \"Jo's\"...", EscPos.toAscii("Crème brûlée — “Jo’s”…"))
        assertEquals("?", EscPos.toAscii("🍞"))
    }

    @Test
    fun toAsciiKeepsLineBreaks() {
        assertEquals("Line one\nLine two\nLine three", EscPos.toAscii("Line one\r\nLine two\rLine three"))
    }

    private fun simpleOrder(createdAt: Instant, note: String? = null) = Order(
        "id", "#7", createdAt, false, "Hillside Shop", null, null, note, emptyList(), emptyList(),
        listOf(LineItem(1, "Pie", null)),
    )

    private fun render(bytes: ByteArray) = EscPosPreview.render(bytes)

    @Test
    fun multiLineNoteKeepsItsLineBreaks() {
        val now = Instant.parse("2026-11-03T00:00:00Z")
        val text = render(TicketFormatter.format(simpleOrder(now, "Ring the bell\nLeave by the gate"), "H", ZoneId.of("UTC"), now))
        assertTrue(text, text.contains("| Ring the bell "))
        assertTrue(text, text.contains("| Leave by the gate "))
        assertFalse(text, text.contains("?"))
    }

    @Test
    fun lateOrdersGetABanner() {
        val placed = Instant.parse("2026-11-01T09:00:00Z")
        val onTime = render(TicketFormatter.format(simpleOrder(placed), "H", ZoneId.of("UTC"), placed.plusSeconds(10 * 60)))
        assertFalse(onTime.contains("L A T E"))
        val late = render(TicketFormatter.format(simpleOrder(placed), "H", ZoneId.of("UTC"), placed.plusSeconds((2 * 24 + 3) * 3600L)))
        assertTrue(late, late.contains("* *   L A T E   * *"))
        assertTrue(late, late.contains("Placed 2 days 3 hours ago"))
        val anHour = render(TicketFormatter.format(simpleOrder(placed), "H", ZoneId.of("UTC"), placed.plusSeconds(3600 + 120)))
        assertTrue(anHour, anHour.contains("Placed 1 hour ago"))
    }

    @Test
    fun connectedTicketListsOrdersSkippedAtInstall() {
        val plain = render(TicketFormatter.connectedTicket("H", "10.0.0.5"))
        assertFalse(plain.contains("NOT PRINTED"))
        val withSkipped = render(TicketFormatter.connectedTicket("H", "10.0.0.5", skippedOrders = listOf("#1006", "#1007")))
        assertTrue(withSkipped, withSkipped.contains("NOT PRINTED"))
        assertTrue(withSkipped, withSkipped.contains("#1006, #1007"))
    }

    @Test
    fun sentenceJoinsWithoutDoubledPunctuation() {
        assertEquals("Printer off. 2 waiting", OrderPoller.sentence("Printer off", "2 waiting"))
        assertEquals("Is it on? 2 waiting", OrderPoller.sentence("Is it on?", "2 waiting"))
    }

    @Test
    fun ticketContainsKeyFieldsAndEndsWithCut() {
        val order = Order(
            id = "gid://shopify/Order/1",
            name = "#1042",
            createdAt = Instant.parse("2026-10-18T21:05:00Z"),
            cancelled = false,
            shopName = "Corner Cafe North",
            shopDetail = null,
            shippingMethod = "Local delivery",
            note = "Extra crusty please",
            tags = emptyList(),
            attributes = listOf("Delivery Date" to "Tue 20 Oct", "Gate code" to "1234"),
            lineItems = listOf(
                LineItem(12, "Sourdough Loaf", "Large / Sliced", BigDecimal("7.5")),
                LineItem(3, "Croissant", null, BigDecimal("4.25")),
            ),
            address = listOf("12 Example St", "Sampletown NSW 2000"),
            phone = "0400 000 000",
            paymentMethod = "Cash on Delivery (COD)",
            paymentStatus = "PENDING",
            currency = "AUD",
            shippingPrice = BigDecimal("8"),
            totalPrice = BigDecimal("110.75"),
        )
        val bytes = TicketFormatter.format(order, "Sample Bakery", ZoneId.of("Australia/Sydney"))
        val text = String(bytes, Charsets.US_ASCII)

        assertTrue(text.contains("SAMPLE BAKERY"))
        assertTrue(text.contains("KITCHEN ORDER"))
        assertTrue(text.contains("ORDER #1042"))
        assertTrue("placed time is shown in local time", text.contains("Placed: Mon 19 Oct 2026  8:05 AM"))
        assertTrue(text.contains("CORNER CAFE NORTH"))
        assertTrue(text.contains("DELIVERY DATE: Tue 20 Oct"))
        assertTrue(text.contains("Address:  12 Example St"))
        assertTrue(text.contains("          Sampletown NSW 2000"))
        assertTrue(text.contains("Phone:    0400 000 000"))
        assertTrue(text.contains("Delivery: Local delivery"))
        assertTrue(text.contains("Payment:  Cash on Delivery (COD)"))
        assertTrue(text.contains("          NOT PAID YET"))
        assertTrue(text.contains("12x  "))
        assertTrue(text.contains("Sourdough Loaf"))
        assertTrue(text.contains("- Large / Sliced"))
        assertTrue(text.contains("Total items: 15"))
        assertTrue("unit price left, line total right", text.contains("\$7.50 each" + " ".repeat(22) + "\$90.00\n"))
        assertTrue(text.contains("\$4.25 each"))
        assertTrue(text.contains("\$12.75"))
        assertTrue(text.contains("Delivery fee" + " ".repeat(31) + "\$8.00"))
        assertTrue(text.contains("ORDER TOTAL" + " ".repeat(30) + "\$110.75"))
        assertTrue(text.contains("Extra crusty please"))
        assertTrue(text.contains("Gate code: 1234"))

        // ESC @ first, GS V 66 3 (feed and partial cut) last
        assertEquals(listOf(0x1B, '@'.code), bytes.take(2).map { it.toInt() })
        assertEquals(listOf(0x1D, 'V'.code, 66, 3), bytes.takeLast(4).map { it.toInt() })
    }

    @Test
    fun noPrintedLineIsWiderThanThePaper() {
        val order = Order(
            "id", "#1", Instant.EPOCH, false,
            "A Very Long Wholesale Customer Company Name Pty Ltd", "Some location",
            null, "word ".repeat(40), emptyList(), emptyList(),
            listOf(
                LineItem(
                    9999, "Extraordinarily Long Product Title That Must Wrap Across Several Lines", "Variant ".repeat(10),
                    BigDecimal("99999.99"),
                ),
            ),
            currency = "EUR",
            shippingPrice = BigDecimal("123456.78"),
            totalPrice = BigDecimal("999999999.99"),
        )
        val bytes = TicketFormatter.format(order, "Header", ZoneId.of("UTC")).map { it.toInt() and 0xFF }

        // Walk the byte stream like the printer would, counting columns with the current char width
        var i = 0
        var widthMul = 1
        var column = 0
        var maxColumn = 0
        while (i < bytes.size) {
            when (bytes[i]) {
                0x1B -> i += if (bytes[i + 1] == '@'.code) 2 else 3
                0x1D -> {
                    if (bytes[i + 1] == '!'.code) widthMul = (bytes[i + 2] shr 4) + 1
                    i += if (bytes[i + 1] == 'V'.code) 4 else 3
                }
                0x0A -> { column = 0; i++ }
                else -> { column += widthMul; maxColumn = maxOf(maxColumn, column); i++ }
            }
        }
        assertTrue("widest line is $maxColumn columns", maxColumn <= TicketFormatter.WIDTH)
    }
}
