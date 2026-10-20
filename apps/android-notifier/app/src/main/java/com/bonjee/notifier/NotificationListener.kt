package com.bonjee.notifier

import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class NotificationListener : NotificationListenerService() {
    private val TAG = "NotificationListener"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        fun isEnabled(context: Context): Boolean {
            val packageName = context.packageName
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return enabledListeners?.contains(packageName) == true
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        val prefs = getSharedPreferences("bonjee_prefs", Context.MODE_PRIVATE)
        val webhookUrl = prefs.getString("webhook_url", "") ?: ""
        
        if (webhookUrl.isEmpty()) {
            Log.d(TAG, "Webhook URL not configured")
            return
        }

        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val ticker = sbn.notification.tickerText?.toString() ?: ""
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val json = JSONObject().apply {
            put("external_id", "${sbn.packageName}_${sbn.postTime}_${sbn.id}")
            put("app_package", sbn.packageName)
            put("app_name", appName)
            put("title", title)
            put("content", text)
            put("ticker", ticker)
            put("post_time", sbn.postTime)
            put("is_clearable", sbn.isClearable)
            put("category", notification.category ?: "")
            put("tag", sbn.tag ?: "")
            put("id", sbn.id)
            put("key", sbn.key ?: "")
            put("user_id", sbn.user.hashCode())
            put("raw", JSONObject().apply {
                // Avoid sending huge extras - just basic info
                put("priority", notification.priority)
                put("defaults", notification.defaults)
                put("flags", notification.flags)
                put("when", notification.`when`)
            })
        }

        Log.d(TAG, "Forwarding notification from $appName: $title")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to forward notification: ${response.code}")
                    } else {
                        Log.d(TAG, "Notification forwarded successfully")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error forwarding notification", e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        // Optionally track removals if needed in future
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(this, ForegroundService::class.java)
            startForegroundService(intent)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(this, ForegroundService::class.java)
            stopService(intent)
        }
    }
}
