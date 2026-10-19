package com.example.shopify_receipt_printer

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
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
        if (typed != settings.printerHost) {
            settings.printerHost = typed
            // A typed address is respected and never replaced by a network scan
            settings.printerAutoDiscovered = false
            status.log("Printer set to $typed")
        }
        toast("Printer saved")
        renderStatus()
    }

    private fun testPrint() {
        val host = settings.printerHost
        if (host.isEmpty()) {
            toast("No printer yet. Press Find printer first")
            return
        }
        testPrintButton.isEnabled = false
        testPrintButton.text = getString(R.string.test_print_busy)
        lifecycleScope.launch {
            try {
                PrinterClient.send(host, settings.printerPort, TicketFormatter.testTicket(settings.ticketHeader, host))
                status.log("Test ticket sent to $host")
                toast("Test ticket sent")
            } catch (e: Exception) {
                status.recordError("Printer $host: ${e.message ?: e.javaClass.simpleName}")
                toast("Couldn't reach the printer at $host. Check it is on and on the same WiFi")
            }
            testPrintButton.isEnabled = true
            testPrintButton.setText(R.string.test_print)
            renderStatus()
        }
    }

    private fun findPrinter() {
        hideKeyboard()
        findPrinterButton.isEnabled = false
        findPrinterButton.text = getString(R.string.find_printer_busy)
        lifecycleScope.launch {
            val found = PrinterDiscovery.scan(settings.printerPort)
            if (found.isEmpty()) {
                status.recordError("No printer found on port ${settings.printerPort}. Is it on and connected to the same WiFi?")
                toast("No printer found. Check it is on and on the same WiFi")
            } else {
                settings.printerHost = found.first()
                settings.printerAutoDiscovered = true
                status.log("Found printer(s): ${found.joinToString()}")
                toast("Using printer ${found.first()}" + if (found.size > 1) " (${found.size} found)" else "")
            }
            printerHost.setText(settings.printerHost)
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
        findViewById<TextView>(R.id.printerValue).text = settings.printerHost.ifEmpty { "Searching…" }

        findViewById<TextView>(R.id.lastError).apply {
            visibility = if (error.isEmpty()) View.GONE else View.VISIBLE
            text = "$error\n${ago(status.lastErrorMillis)}"
        }
        findViewById<TextView>(R.id.logText).text = status.log.joinToString("\n").ifEmpty { "Nothing yet" }
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
