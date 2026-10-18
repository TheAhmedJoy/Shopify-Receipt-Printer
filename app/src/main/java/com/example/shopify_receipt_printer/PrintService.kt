package com.example.shopify_receipt_printer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Foreground service that polls Shopify on a fixed interval for as long as it is enabled. */
class PrintService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Starting"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        // The Sunmi never sleeps, but hold a partial lock so the CPU can't doze between polls
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShopifyReceiptPrinter:poll")
            .apply { setReferenceCounted(false); acquire() }
        isRunning = true
        StatusStore(this).log("Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            // Cut the current wait short rather than cancelling, so a print in progress is never interrupted
            ACTION_POLL_NOW -> wake.trySend(Unit)
        }
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val poller = OrderPoller(this)
        val settings = Settings(this)
        while (scope.isActive) {
            val summary = try {
                poller.pollOnce()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                StatusStore(this).recordError("Unexpected: ${e.message ?: e.javaClass.simpleName}")
                "Error"
            }
            updateNotification("${LocalTime.now().format(TIME_FORMAT)}  $summary")
            withTimeoutOrNull(settings.pollSeconds * 1000L) { wake.receive() }
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        wakeLock?.release()
        StatusStore(this).log("Service stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Order printing", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Watching for Shopify orders")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "printing"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val ACTION_POLL_NOW = "poll_now"
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PrintService::class.java))
        }

        fun pollNow(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, PrintService::class.java).setAction(ACTION_POLL_NOW),
            )
        }

        fun stop(context: Context) {
            if (isRunning) context.startService(Intent(context, PrintService::class.java).setAction(ACTION_STOP))
        }
    }
}
