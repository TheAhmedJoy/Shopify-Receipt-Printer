package com.example.shopify_receipt_printer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Status on the left, settings on the right. Sized for the Sunmi's 15.6" landscape screen. */
class MainActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private lateinit var status: StatusStore

    private lateinit var storeDomain: EditText
    private lateinit var accessToken: EditText
    private lateinit var apiVersion: EditText
    private lateinit var printerHost: EditText
    private lateinit var printerPort: EditText
    private lateinit var ticketHeader: EditText
    private lateinit var pollSeconds: EditText
    private lateinit var lookbackHours: EditText

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

        storeDomain = findViewById(R.id.storeDomain)
        accessToken = findViewById(R.id.accessToken)
        apiVersion = findViewById(R.id.apiVersion)
        printerHost = findViewById(R.id.printerHost)
        printerPort = findViewById(R.id.printerPort)
        ticketHeader = findViewById(R.id.ticketHeader)
        pollSeconds = findViewById(R.id.pollSeconds)
        lookbackHours = findViewById(R.id.lookbackHours)
        if (settings.hasBuiltInToken) {
            // Never reveal the built-in token to anyone at the till
            accessToken.isEnabled = false
            findViewById<TextInputLayout>(R.id.accessTokenLayout).apply {
                endIconMode = TextInputLayout.END_ICON_NONE
                helperText = getString(R.string.helper_token_built_in)
            }
        }
        loadFields()

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            saveFields()
            toast("Settings saved")
        }
        findViewById<MaterialButton>(R.id.startStopButton).setOnClickListener { toggleService() }
        findViewById<MaterialButton>(R.id.pollNowButton).setOnClickListener {
            saveFields()
            if (PrintService.isRunning) PrintService.pollNow(this) else toast("Start the service first")
        }
        findViewById<MaterialButton>(R.id.testPrintButton).setOnClickListener { testPrint() }
        findViewById<MaterialButton>(R.id.testShopifyButton).setOnClickListener { testShopify() }
        findViewById<MaterialButton>(R.id.findPrinterButton).setOnClickListener { findPrinter() }
        findViewById<MaterialButton>(R.id.skipExistingButton).setOnClickListener {
            settings.printSinceMillis = System.currentTimeMillis()
            status.log("Ignoring orders placed before now")
            toast("Only orders placed from now on will print")
        }
        findViewById<MaterialButton>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        // Covers the case where the process was killed and the service hasn't been restarted yet
        if (settings.serviceEnabled && !PrintService.isRunning) PrintService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun loadFields() {
        storeDomain.setText(settings.storeDomain)
        accessToken.setText(if (settings.hasBuiltInToken) "" else settings.accessToken)
        apiVersion.setText(settings.apiVersion)
        printerHost.setText(settings.printerHost)
        printerPort.setText(settings.printerPort.toString())
        ticketHeader.setText(settings.ticketHeader)
        pollSeconds.setText(settings.pollSeconds.toString())
        lookbackHours.setText(settings.lookbackHours.toString())
    }

    private fun saveFields() {
        settings.storeDomain = storeDomain.text.toString()
        if (!settings.hasBuiltInToken) settings.accessToken = accessToken.text.toString()
        settings.apiVersion = apiVersion.text.toString().ifBlank { Settings.DEFAULT_API_VERSION }
        val typedHost = printerHost.text.toString().trim()
        if (typedHost != settings.printerHost) {
            settings.printerHost = typedHost
            // A typed address is respected; an emptied field means "find it again"
            settings.printerAutoDiscovered = false
        }
        settings.printerPort = printerPort.text.toString().toIntOrNull() ?: Settings.DEFAULT_PRINTER_PORT
        settings.ticketHeader = ticketHeader.text.toString()
        settings.pollSeconds = pollSeconds.text.toString().toIntOrNull() ?: Settings.DEFAULT_POLL_SECONDS
        settings.lookbackHours = lookbackHours.text.toString().toIntOrNull() ?: Settings.DEFAULT_LOOKBACK_HOURS
        // Show the normalized / clamped values
        loadFields()
    }

    private fun toggleService() {
        saveFields()
        if (PrintService.isRunning) {
            settings.serviceEnabled = false
            PrintService.stop(this)
        } else {
            val missing = settings.missingFields()
            if (missing.isNotEmpty()) {
                toast("Fill in: ${missing.joinToString()}")
                return
            }
            settings.serviceEnabled = true
            PrintService.start(this)
        }
        handler.postDelayed({ renderStatus() }, 300)
    }

    private fun testPrint() {
        saveFields()
        val host = settings.printerHost
        if (host.isEmpty()) {
            toast("Enter the printer IP first")
            return
        }
        lifecycleScope.launch {
            try {
                PrinterClient.send(host, settings.printerPort, TicketFormatter.testTicket(settings.ticketHeader, host))
                status.log("Test ticket sent to $host")
                toast("Test ticket sent")
            } catch (e: Exception) {
                status.recordError("Printer $host: ${e.message ?: e.javaClass.simpleName}")
                toast("Printer error: ${e.message}")
            }
            renderStatus()
        }
    }

    private fun findPrinter() {
        toast("Searching the network…")
        lifecycleScope.launch {
            val found = PrinterDiscovery.scan(settings.printerPort)
            if (found.isEmpty()) {
                status.recordError("No printer found on port ${settings.printerPort}")
                toast("No printer found. Check it is on and on the same WiFi")
            } else {
                settings.printerHost = found.first()
                settings.printerAutoDiscovered = true
                loadFields()
                status.log("Found printer(s): ${found.joinToString()}")
                toast("Using printer ${found.first()}" + if (found.size > 1) " (${found.size} found)" else "")
            }
            renderStatus()
        }
    }

    private fun testShopify() {
        saveFields()
        val missing = settings.missingFields()
        if (missing.isNotEmpty()) {
            toast("Fill in: ${missing.joinToString()}")
            return
        }
        lifecycleScope.launch {
            try {
                val name = ShopifyClient(settings.storeDomain, settings.accessToken, settings.apiVersion).fetchShopName()
                status.log("Connected to Shopify store \"$name\"")
                toast("Connected to \"$name\"")
            } catch (e: Exception) {
                status.recordError("Shopify: ${e.message ?: e.javaClass.simpleName}")
                toast("Shopify error: ${e.message}")
            }
            renderStatus()
        }
    }

    private fun renderStatus() {
        val running = PrintService.isRunning
        findViewById<TextView>(R.id.serviceState).text =
            if (running) "● Printing new orders automatically" else "○ Stopped. Orders will not print"
        findViewById<MaterialButton>(R.id.startStopButton).text = if (running) "Stop" else "Start"

        findViewById<TextView>(R.id.statusDetails).text = buildString {
            appendLine("Last poll: ${ago(status.lastPollMillis)}  ${status.lastPollResult}")
            val printed = status.lastPrinted
            appendLine("Last printed: " + if (printed.isEmpty()) "none yet" else "$printed (${ago(status.lastPrintedMillis)})")
            append("Store: ${settings.storeDomain.ifEmpty { "not set" }}   Printer: ${settings.printerHost.ifEmpty { "searching" }}")
        }
        findViewById<TextView>(R.id.lastError).text =
            status.lastError.takeIf { it.isNotEmpty() }?.let { "⚠ $it (${ago(status.lastErrorMillis)})" } ?: ""
        findViewById<TextView>(R.id.logText).text = status.log.joinToString("\n").ifEmpty { "Nothing yet" }

        val since = settings.printSinceMillis
        findViewById<TextView>(R.id.printSince).text =
            if (since == 0L) "Orders placed after the app first starts will print"
            else "Printing orders placed after " + SINCE_FORMAT.format(Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()))
    }

    private fun ago(millis: Long): String =
        if (millis == 0L) "never"
        else DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private val SINCE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy h:mm a")
    }
}
