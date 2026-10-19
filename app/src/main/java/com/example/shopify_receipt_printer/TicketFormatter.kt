package com.example.shopify_receipt_printer

import com.example.shopify_receipt_printer.EscPos.Align
import java.math.BigDecimal
import java.math.RoundingMode
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

    /** Width of the "Delivery: " label column in the customer details block. */
    private const val LABEL_COLS = 10

    private val DOLLAR_CURRENCIES = setOf("AUD", "USD", "NZD", "CAD", "SGD", "HKD")

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
        p.line()
        order.shopDetail?.let { field(p, "Name:", listOf(it)) }
        if (order.address.isNotEmpty()) {
            // Tall text keeps its width, so the address stays in the value column but reads easily
            p.size(1, 2)
            field(p, if (order.addressIsDelivery) "Address:" else "Billing:", order.address)
            p.size(1, 1)
        }
        order.phone?.let { field(p, "Phone:", listOf(it)) }
        order.shippingMethod?.let { field(p, "Delivery:", listOf(it)) }
        order.paymentMethod?.let { method ->
            field(p, "Payment:", listOfNotNull(method, order.paymentStatus?.let(::paymentStatusText)))
        }

        val (dateAttrs, otherAttrs) = order.attributes
            .filterNot { it.first.startsWith("_") }
            .partition { (key, _) -> DATE_KEY_HINTS.any { key.lowercase(Locale.ROOT).contains(it) } }
        if (dateAttrs.isNotEmpty()) {
            p.line()
            p.bold(true).size(1, 2)
            dateAttrs.forEach { (k, v) -> wrapped(p, "${k.uppercase(Locale.ROOT)}: $v", WIDTH) }
            p.size(1, 1).bold(false)
        }
        rule(p, '=')

        for (item in order.lineItems) lineItem(p, item, order.currency)
        rule(p, '=')
        p.line("Total items: ${order.lineItems.sumOf { it.quantity }}")
        order.shippingPrice?.takeIf { it.signum() > 0 }?.let { p.line(leftRight("Delivery fee", money(it, order.currency))) }
        order.totalPrice?.let {
            p.bold(true).size(1, 2).line(leftRight("ORDER TOTAL", money(it, order.currency))).size(1, 1).bold(false)
        }

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

    /** Quantity large in its own column, product in tall bold text beside it, variant and price underneath. */
    private fun lineItem(p: EscPos, item: LineItem, currency: String = "") {
        val qty = "${item.quantity}x".padEnd(QTY_COLS)
        EscPos.wrap(EscPos.toAscii(item.title), ITEM_WIDTH).forEachIndexed { i, text ->
            p.bold(true).size(2, 2).text(if (i == 0) qty else " ".repeat(QTY_COLS))
            p.size(1, 2).line(text)
        }
        p.size(1, 1).bold(false)
        item.variant?.let { v ->
            EscPos.wrap(EscPos.toAscii(v), ITEM_WIDTH - 2).forEach { p.line(" ".repeat(ITEM_INDENT) + "- " + it) }
        }
        val unit = item.unitPrice
        val total = item.linePrice
        if (unit != null && total != null) {
            p.line(" ".repeat(ITEM_INDENT) + leftRight("${money(unit, currency)} each", money(total, currency), ITEM_WIDTH))
        }
        p.line()
    }

    /** A "Label:   value" row; long or multi-line values wrap under the value column. */
    private fun field(p: EscPos, label: String, values: List<String>) {
        values.flatMap { EscPos.wrap(EscPos.toAscii(it), WIDTH - LABEL_COLS) }.forEachIndexed { i, text ->
            p.line((if (i == 0) label else "").padEnd(LABEL_COLS) + text)
        }
    }

    /** "$5.50" for dollar currencies, otherwise "EUR 5.50". */
    fun money(amount: BigDecimal, currency: String): String {
        val value = amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
        return if (currency.isEmpty() || currency in DOLLAR_CURRENCIES) "$$value" else "$currency $value"
    }

    /** [left] and [right] on one line, pushed to opposite edges of [width] columns. */
    private fun leftRight(left: String, right: String, width: Int = WIDTH): String {
        val l = EscPos.toAscii(left).take((width - right.length - 1).coerceAtLeast(0))
        return l + " ".repeat((width - l.length - right.length).coerceAtLeast(1)) + right
    }

    private fun paymentStatusText(status: String): String = when (status) {
        "PAID" -> "Paid"
        "PENDING" -> "NOT PAID YET"
        "AUTHORIZED" -> "Card authorised, not yet charged"
        "PARTIALLY_PAID" -> "Partly paid"
        "REFUNDED" -> "Refunded"
        "PARTIALLY_REFUNDED" -> "Partly refunded"
        "VOIDED" -> "Voided"
        else -> status.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun wrapped(p: EscPos, text: String, width: Int) {
        EscPos.wrap(EscPos.toAscii(text), width).forEach { p.line(it) }
    }

    private fun rule(p: EscPos, ch: Char) {
        p.line(ch.toString().repeat(WIDTH))
    }
}
