package com.bonjee.notifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the capture pipeline alive and periodically retries queued payloads.
 * The user always sees a persistent, honest status notification.
 */
class ForegroundService : Service() {

    private var drainJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting up..."))
        startDrainLoop()
        return START_STICKY
    }

    private fun startDrainLoop() {
        if (drainJob?.isActive == true) return
        drainJob = scope.launch {
            while (isActive) {
                try {
                    QueueDrainer.drain(applicationContext)
                    updateForegroundStatus(applicationContext)
                } catch (e: Exception) {
                    android.util.Log.w("BonjeeService", "Drain cycle failed: ${e.message}")
                }
                delay(30_000)
            }
        }
    }

    override fun onDestroy() {
        drainJob?.cancel()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bonjee Notifier active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bonjee capture service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "bonjee_foreground_service"
        private const val NOTIFICATION_ID = 1001

        /** True while the capture service is alive in this process. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun updateForegroundStatus(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val prefs = Prefs.get(context)
            val queued = DeliveryQueue(context).count()
            val state = if (queued > 0) "$queued notification(s) waiting to send" else "All notifications delivered"
            val last = if (prefs.lastDeliveryAt > 0) {
                "Last: ${if (prefs.lastDeliveryOk) "ok" else "failed"} at ${prefs.lastDeliveryAt}"
            } else "No deliveries yet"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Bonjee Notifier active")
                .setContentText(state)
                .setSubText(last)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
