package com.example.shopify_receipt_printer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
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

/** Foreground service that polls Shopify on a fixed interval. It always runs; there is no off switch. */
class PrintService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastAlertMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
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
        // Cut the current wait short rather than cancelling, so a print in progress is never interrupted
        if (intent?.action == ACTION_POLL_NOW) wake.trySend(Unit)
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
            updateAlert()
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

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Order printing", NotificationManager.IMPORTANCE_LOW),
        )
        // Alarm sound and alarm volume, so it is heard in a busy kitchen even with notifications turned down
        val alarm = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Printing problems", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Sounds an alarm when orders stop printing"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarm)
                enableVibration(true)
            },
        )
    }

    /**
     * Sounds an alarm when a problem has lasted [ALERT_AFTER_MS], so a single dropped poll stays quiet.
     * The sound repeats until someone opens or dismisses it, and comes back every [REALERT_MS] while
     * the problem lasts. Cleared as soon as printing works again.
     */
    private fun updateAlert() {
        val manager = getSystemService(NotificationManager::class.java)
        val status = StatusStore(this)
        val now = System.currentTimeMillis()
        val problem = status.lastError
        if (problem.isEmpty()) {
            manager.cancel(ALERT_NOTIFICATION_ID)
            lastAlertMillis = 0L
            return
        }
        if (now - status.problemSinceMillis < ALERT_AFTER_MS || now - lastAlertMillis < REALERT_MS) return
        lastAlertMillis = now
        val alert = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Orders are not printing")
            .setContentText(problem)
            .setStyle(NotificationCompat.BigTextStyle().bigText(problem))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        alert.flags = alert.flags or Notification.FLAG_INSISTENT
        manager.notify(ALERT_NOTIFICATION_ID, alert)
        status.log("Alarm: $problem")
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Watching for Shopify orders")
            .setContentText(text)
            .setContentIntent(openAppIntent())
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
        private const val ALERT_CHANNEL_ID = "printing_problems"
        private const val ALERT_NOTIFICATION_ID = 2
        private const val ALERT_AFTER_MS = 60_000L
        private const val REALERT_MS = 10 * 60_000L
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
    }
}
