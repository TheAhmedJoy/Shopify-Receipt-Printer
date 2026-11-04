package com.example.shopify_receipt_printer

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Status and a few actions. Everything else is built into the APK, so there is nothing here for
 * anyone at the till to misconfigure. One column on phones, two on the Sunmi's landscape screen.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private lateinit var status: StatusStore

    private lateinit var printerHost: EditText
    private lateinit var testPrintButton: MaterialButton
    private lateinit var findPrinterButton: MaterialButton
    private lateinit var candidateList: LinearLayout

    /** The candidate list last drawn, so the once-a-second refresh doesn't rebuild the buttons. */
    private var shownCandidates: List<String>? = null

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        status = StatusStore(this)

        printerHost = findViewById(R.id.printerHost)
        testPrintButton = findViewById(R.id.testPrintButton)
        findPrinterButton = findViewById(R.id.findPrinterButton)
        candidateList = findViewById(R.id.candidateList)
        printerHost.setText(settings.printerHost)
        printerHost.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) savePrinter()
            false
        }

        findViewById<MaterialButton>(R.id.pollNowButton).setOnClickListener {
            PrintService.pollNow(this)
            toast("Checking for new orders")
        }
        testPrintButton.setOnClickListener { testPrint() }
        findPrinterButton.setOnClickListener { findPrinter() }
        findViewById<MaterialButton>(R.id.savePrinterButton).setOnClickListener { savePrinter() }
        findViewById<MaterialButton>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            // Without this, Android 17+ silently drops connections to the printer. The Sunmi (Android 9) doesn't need it
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 0)
        // Covers the case where the process was killed and the service hasn't been restarted yet
        if (!PrintService.isRunning) PrintService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun savePrinter() {
        hideKeyboard()
        val typed = printerHost.text.toString().trim()
        if (typed.isEmpty()) {
            // An emptied field means "find it again"
            findPrinter()
            return
        }
        if (!IP_PATTERN.matches(typed)) {
            toast("That doesn't look like an IP address, e.g. 192.168.0.50")
            return
        }
        if (typed != settings.printerHost) usePrinter(typed) else toast("Printer saved")
    }

    /** Makes [host] the kitchen printer. A PRINTER CONNECTED ticket follows on the next check. */
    private fun usePrinter(host: String) {
        settings.choosePrinter(host)
        status.log("Printer set to $host")
        printerHost.setText(host)
        toast("Using the printer at $host")
        PrintService.pollNow(this)
        renderStatus()
    }

    private fun testPrint() {
        val host = settings.printerHost
        if (host.isEmpty()) {
            toast("No printer chosen yet. Press Find printer first")
            return
        }
        sendTestTicket(host, testPrintButton, R.string.test_print, recordError = true)
    }

    /**
     * Prints a test ticket on [host], showing progress on [button]. Problems with the chosen printer are
     * recorded; a test on a device someone is only trying out is not.
     */
    private fun sendTestTicket(host: String, button: MaterialButton, label: Int, recordError: Boolean) {
        button.isEnabled = false
        button.text = getString(R.string.test_print_busy)
        lifecycleScope.launch {
            try {
                PrinterClient.send(host, settings.printerPort, TicketFormatter.testTicket(settings.ticketHeader, host))
                status.log("Test ticket sent to $host")
                toast("Test ticket sent to $host")
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                if (recordError) status.recordError("Printer $host: $reason")
                toast(if (e is PrinterException) reason else "Couldn't print on $host: $reason")
            }
            button.isEnabled = true
            button.setText(label)
            renderStatus()
        }
    }

    private fun findPrinter() {
        hideKeyboard()
        findPrinterButton.isEnabled = false
        findPrinterButton.text = getString(R.string.find_printer_busy)
        lifecycleScope.launch {
            val found = PrinterDiscovery.scan(settings.printerPort)
            val current = settings.printerHost
            when {
                found.isEmpty() -> {
                    status.recordError("No printer found on port ${settings.printerPort}. Is it on and connected to the same WiFi?")
                    toast("No printer found. Check it is on and on the same WiFi")
                }
                current.isNotEmpty() && current in found -> toast("The printer at $current is answering")
                current.isEmpty() && found.size == 1 -> usePrinter(found.single())
                else -> {
                    // Never switch by itself: someone picks which device is the kitchen printer
                    settings.printerCandidates = found - current
                    status.log("Found devices answering as printers: ${found.joinToString()}")
                    toast("Choose the kitchen printer at the top of the screen")
                }
            }
            findPrinterButton.isEnabled = true
            findPrinterButton.setText(R.string.find_printer)
            renderStatus()
        }
    }

    private fun renderStatus() {
        val error = status.lastError
        val (state, color) = when {
            !PrintService.isRunning -> "Starting…" to R.color.status_idle
            error.isNotEmpty() -> "Needs attention" to R.color.status_warn
            else -> "Printing new orders automatically" to R.color.status_ok
        }
        findViewById<TextView>(R.id.serviceState).text = state
        findViewById<View>(R.id.serviceDot).backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, color))

        findViewById<TextView>(R.id.lastCheckValue).text = buildString {
            append(ago(status.lastPollMillis))
            status.lastPollResult.takeIf { it.isNotEmpty() && status.lastPollMillis != 0L }?.let { append(" · $it") }
        }
        val printed = status.lastPrinted
        findViewById<TextView>(R.id.lastPrintedValue).text =
            if (printed.isEmpty()) "None yet" else "$printed · ${ago(status.lastPrintedMillis)}"
        // Follow a printer found in the background, but never overwrite what someone is typing
        if (!printerHost.hasFocus() && printerHost.text.toString() != settings.printerHost) {
            printerHost.setText(settings.printerHost)
        }
        val candidates = settings.printerCandidates
        findViewById<TextView>(R.id.printerValue).text = settings.printerHost.ifEmpty {
            if (candidates.isEmpty()) "Searching…" else getString(R.string.printer_not_chosen)
        }
        renderCandidates(candidates)

        findViewById<TextView>(R.id.lastError).apply {
            visibility = if (error.isEmpty()) View.GONE else View.VISIBLE
            text = "$error\n${ago(status.lastErrorMillis)}"
        }
        findViewById<TextView>(R.id.logText).text = status.log.joinToString("\n").ifEmpty { "Nothing yet" }
    }

    /** The "Choose the kitchen printer" card: one row per device, each with Print test and Use this printer. */
    private fun renderCandidates(candidates: List<String>) {
        val card = findViewById<View>(R.id.choosePrinterCard)
        card.visibility = if (candidates.isEmpty()) View.GONE else View.VISIBLE
        val current = settings.printerHost
        findViewById<TextView>(R.id.choosePrinterText).text =
            if (current.isEmpty()) getString(R.string.choose_printer_several) else getString(R.string.choose_printer_moved, current)
        if (candidates == shownCandidates) return
        shownCandidates = candidates
        candidateList.removeAllViews()
        candidates.forEachIndexed { i, host ->
            if (i > 0) {
                candidateList.addView(View(this).apply {
                    setBackgroundResource(R.drawable.divider)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                        .apply { topMargin = (20 * resources.displayMetrics.density).toInt() }
                })
            }
            val row = LayoutInflater.from(this).inflate(R.layout.item_printer_candidate, candidateList, false)
            if (i == 0) row.setPadding(row.paddingLeft, 0, row.paddingRight, row.paddingBottom)
            row.findViewById<TextView>(R.id.candidateAddress).text = host
            val test = row.findViewById<MaterialButton>(R.id.candidateTestButton)
            test.setOnClickListener { sendTestTicket(host, test, R.string.candidate_test, recordError = false) }
            row.findViewById<MaterialButton>(R.id.candidateUseButton).setOnClickListener { usePrinter(host) }
            candidateList.addView(row)
        }
    }

    private fun ago(millis: Long): String =
        if (millis == 0L) "Never"
        else DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString()

    private fun hideKeyboard() {
        printerHost.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(printerHost.windowToken, 0)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private val IP_PATTERN = Regex("""^((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)$""")
    }
}
