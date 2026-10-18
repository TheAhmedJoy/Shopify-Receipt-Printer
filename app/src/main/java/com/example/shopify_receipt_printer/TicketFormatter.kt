package com.example.shopify_receipt_printer

import com.example.shopify_receipt_printer.EscPos.Align
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Lays out kitchen tickets for 80 mm paper (48 columns in Font A). */
object TicketFormatter {
    const val WIDTH = 48
    private const val WIDE = WIDTH / 2

    /** Quantity column, in double-width characters. */
    private const val QTY_COLS = 5
    private const val ITEM_INDENT = QTY_COLS * 2
    private const val ITEM_WIDTH = WIDTH - ITEM_INDENT

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy  h:mm a", Locale.ENGLISH)

    /** Attribute keys that most delivery/pickup date apps use. */
    private val DATE_KEY_HINTS = listOf("date", "deliver", "pickup", "pick up", "pick-up", "time", "day")

    fun format(order: Order, header: String, zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val p = EscPos().init()
        header(p, header)

        p.align(Align.LEFT).bold(true).size(2, 2)
        wrapped(p, "ORDER ${order.name}", WIDE)
        p.size(1, 1).bold(false)
        p.line("Placed: " + DATE_FORMAT.format(order.createdAt.atZone(zone)))
        rule(p, '-')

        p.bold(true).size(2, 2)
        wrapped(p, order.shopName.uppercase(Locale.ROOT), WIDE)
        p.size(1, 1).bold(false)
        order.shopDetail?.let { wrapped(p, it, WIDTH) }

        val (dateAttrs, otherAttrs) = order.attributes
            .filterNot { it.first.startsWith("_") }
            .partition { (key, _) -> DATE_KEY_HINTS.any { key.lowercase(Locale.ROOT).contains(it) } }
        if (dateAttrs.isNotEmpty()) {
            p.line()
            p.bold(true).size(1, 2)
            dateAttrs.forEach { (k, v) -> wrapped(p, "${k.uppercase(Locale.ROOT)}: $v", WIDTH) }
            p.size(1, 1).bold(false)
        }
        order.shippingMethod?.let { wrapped(p, "Method: $it", WIDTH) }
        rule(p, '=')

        for (item in order.lineItems) lineItem(p, item)
        rule(p, '=')
        p.line("Total items: ${order.lineItems.sumOf { it.quantity }}")

        order.note?.let { note ->
            rule(p, '-')
            p.bold(true).line("NOTE:")
            p.size(1, 2)
            wrapped(p, note, WIDTH)
            p.size(1, 1).bold(false)
        }
        if (otherAttrs.isNotEmpty()) {
            rule(p, '-')
            otherAttrs.forEach { (k, v) -> wrapped(p, "$k: $v", WIDTH) }
        }

        return p.feed(4).cut().bytes()
    }

    fun testTicket(header: String, printerHost: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val p = EscPos().init()
        header(p, header)
        p.align(Align.CENTER).bold(true).size(2, 2).line("TEST TICKET")
        p.size(1, 1).bold(false)
        p.line(DATE_FORMAT.format(now.atZone(zone)))
        p.line("Printer $printerHost")
        p.align(Align.LEFT)
        rule(p, '=')
        lineItem(p, LineItem(12, "Sourdough Loaf", "Large / Sliced"))
        lineItem(p, LineItem(3, "Croissant Tray With A Very Long Product Name To Check Wrapping", null))
        rule(p, '=')
        p.line("0123456789".repeat(5).take(WIDTH))
        p.line("If the line above fits on one row, paper is 80mm")
        return p.feed(4).cut().bytes()
    }

    /** Printed once when the app finds the printer, so the kitchen knows it is set up. */
    fun connectedTicket(header: String, printerHost: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val p = EscPos().init()
        header(p, header)
        p.align(Align.CENTER).bold(true).size(2, 2)
        wrapped(p, "PRINTER CONNECTED", WIDE)
        p.size(1, 1).bold(false)
        p.line(DATE_FORMAT.format(now.atZone(zone)))
        p.line("Printer $printerHost")
        p.line()
        p.line("New Shopify orders will print here")
        p.line("automatically.")
        return p.feed(4).cut().bytes()
    }

    private fun header(p: EscPos, header: String) {
        p.align(Align.CENTER).bold(true).size(2, 2)
        wrapped(p, header.uppercase(Locale.ROOT), WIDE)
        p.size(1, 2).line("KITCHEN ORDER")
        p.size(1, 1).bold(false).align(Align.LEFT)
        rule(p, '=')
    }

    /** Quantity large in its own column, product in tall bold text beside it, variant underneath. */
    private fun lineItem(p: EscPos, item: LineItem) {
        val qty = "${item.quantity}x".padEnd(QTY_COLS)
        EscPos.wrap(EscPos.toAscii(item.title), ITEM_WIDTH).forEachIndexed { i, text ->
            p.bold(true).size(2, 2).text(if (i == 0) qty else " ".repeat(QTY_COLS))
            p.size(1, 2).line(text)
        }
        p.size(1, 1).bold(false)
        item.variant?.let { v ->
            EscPos.wrap(EscPos.toAscii(v), ITEM_WIDTH - 2).forEach { p.line(" ".repeat(ITEM_INDENT) + "- " + it) }
        }
        p.line()
    }

    private fun wrapped(p: EscPos, text: String, width: Int) {
        EscPos.wrap(EscPos.toAscii(text), width).forEach { p.line(it) }
    }

    private fun rule(p: EscPos, ch: Char) {
        p.line(ch.toString().repeat(WIDTH))
    }
}
