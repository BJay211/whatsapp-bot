package com.bonjee.notifier

import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.bonjee.notifier.whatsapp.WhatsAppWatcher

/**
 * Captures posted notifications and forwards them to the n8n webhook.
 *
 * Every payload is written to the durable queue first, then delivered.
 * The listener never blocks on network I/O.
 */
class NotificationListener : NotificationListenerService() {

    private val tag = "BonjeeListener"

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Never capture our own notification (avoids a feedback loop).
        if (sbn.packageName == packageName) return

        val prefs = Prefs.get(this)
        if (!prefs.captureEnabled) return
        if (prefs.blockedPackages.contains(sbn.packageName)) return

        val payload = buildPayload(sbn, prefs) ?: return
        remember(
            payload.optString("app_name"),
            payload.optString("title"),
            payload.optString("content").ifBlank { payload.optString("big_text") },
        )

        // WhatsApp messages are also fed into the rules pipeline (important
        // notifications, draft replies, auto-reply policy). This must never stop
        // the generic capture below, so failures are contained here.
        if (WhatsAppWatcher.isWhatsApp(sbn.packageName)) {
            try {
                WhatsAppWatcher.handle(this, sbn, prefs)
            } catch (t: Throwable) {
                Log.w(tag, "WhatsApp rules pipeline failed: ${t.message}")
            }
        }

        val queue = DeliveryQueue(this)
        val url = prefs.notificationUrl()
        if (url.isBlank()) {
            Log.w(tag, "No webhook URL configured, dropping notification")
            return
        }
        queue.enqueue(url, payload.toString())

        // Try to deliver right away; anything left behind is retried later.
        CoroutineScope(Dispatchers.IO).launch {
            QueueDrainer.drain(applicationContext)
            ForegroundService.updateForegroundStatus(applicationContext)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(tag, "Notification listener connected")
        startForegroundServiceCompat()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(tag, "Notification listener disconnected")
    }

    private fun startForegroundServiceCompat() {
        try {
            val intent = android.content.Intent(this, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not start foreground service: ${e.message}")
        }
    }

    private fun buildPayload(sbn: StatusBarNotification, prefs: Prefs): JSONObject? {
        return try {
            val notification = sbn.notification
            val extras = notification.extras

            fun str(key: String): String {
                val v = extras.getCharSequence(key)
                return v?.toString().orEmpty()
            }

            val title = str("android.title")
            val text = str("android.text")
            val bigText = str("android.bigText")
            val subText = str("android.subText")
            val infoText = str("android.infoText")
            val summary = str("android.summaryText")

            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.packageName, 0)
                ).toString()
            } catch (e: Exception) {
                sbn.packageName
            }

            val body = JSONObject().apply {
                put("external_id", "${sbn.packageName}_${sbn.postTime}_${sbn.id}_${sbn.tag ?: ""}")
                put("app_package", sbn.packageName)
                put("app_name", appName)
                put("title", title)
                put("content", text)
                put("ticker", notification.tickerText?.toString().orEmpty())
                put("post_time", sbn.postTime)
                put("is_clearable", sbn.isClearable)
                put("category", notification.category ?: "")
                put("tag", sbn.tag ?: "")
                put("id", sbn.id)
                put("key", sbn.key ?: "")
                put("group", notification.group ?: "")
                put("is_group_summary", (notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0)
                put("big_text", bigText)
                put("sub_text", subText)
                put("info_text", infoText)
                put("summary_text", summary)
                put("source", "android_notification")
                put("device_time", System.currentTimeMillis())
            }
            body
        } catch (e: Exception) {
            Log.e(tag, "Failed to build payload", e)
            null
        }
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val packageName = context.packageName
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabled.split(":").any { it == packageName || it.startsWith("$packageName/") }
        }

        /**
         * Newest-last ring buffer of notifications the listener has seen. It exists
         * so the assistant can answer "read my notifications" without any extra
         * permission or query. Only notifications that pass the capture settings
         * (not blocked, capture enabled) are remembered, and only the last 20.
         */
        private val recentLines = java.util.concurrent.CopyOnWriteArrayList<String>()

        fun recent(limit: Int = 5): List<String> =
            recentLines.toList().takeLast(limit.coerceAtLeast(1))

        fun remember(app: String, title: String, text: String) {
            val line = buildString {
                append(app.ifBlank { "App" })
                if (title.isNotBlank()) append(": ").append(title)
                if (text.isNotBlank()) append(" - ").append(text)
            }.trim()
            if (line.length <= 4) return
            recentLines.add(line)
            while (recentLines.size > 20) {
                recentLines.removeAt(0)
            }
        }
    }
}
