package com.bonjee.notifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast("Notifications are blocked - the status banner will not show.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)

        binding.editBaseUrl.setText(prefs.n8nBaseUrl)
        binding.editDashboardUrl.setText(prefs.dashboardBaseUrl)
        binding.editNotificationPath.setText(prefs.notificationPath)
        binding.editAskPath.setText(prefs.askPath)
        binding.editAuthToken.setText(prefs.authToken)
        binding.editAuthHeader.setText(prefs.authHeaderName)
        binding.editBlocklist.setText(prefs.blockedPackages.joinToString(","))
        binding.switchCapture.isChecked = prefs.captureEnabled

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnGrantPermission.setOnClickListener { openNotificationSettings() }
        binding.btnStartService.setOnClickListener { startCapture() }
        binding.btnStopService.setOnClickListener {
            stopService(Intent(this, ForegroundService::class.java))
            toast("Capture service stopped")
            refreshStatus()
        }
        binding.btnAsk.setOnClickListener { openAssistant() }
        binding.btnSync.setOnClickListener { syncNow() }
        binding.btnClearQueue.setOnClickListener {
            val n = DeliveryQueue(this).count()
            DeliveryQueue(this).clear()
            toast("Cleared $n queued item(s)")
            refreshStatus()
        }

        askForNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------------------------------------------------------------- settings

    private fun saveSettings() {
        prefs.n8nBaseUrl = binding.editBaseUrl.text.toString()
        prefs.dashboardBaseUrl = binding.editDashboardUrl.text.toString()
        prefs.notificationPath = binding.editNotificationPath.text.toString()
        prefs.askPath = binding.editAskPath.text.toString()
        prefs.authToken = binding.editAuthToken.text.toString()
        prefs.authHeaderName = binding.editAuthHeader.text.toString()
        prefs.blockedPackages = binding.editBlocklist.text.toString()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs.captureEnabled = binding.switchCapture.isChecked

        // Reflect the normalised values back into the fields.
        binding.editBaseUrl.setText(prefs.n8nBaseUrl)
        binding.editDashboardUrl.setText(prefs.dashboardBaseUrl)
        binding.editNotificationPath.setText(prefs.notificationPath)
        binding.editAskPath.setText(prefs.askPath)

        toast("Settings saved")
        refreshStatus()
    }

    // ------------------------------------------------------------------- tests

    private fun testConnection() {
        saveSettings()
        binding.btnTest.isEnabled = false
        binding.txtTestResult.text = "Testing..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val payload = JSONObject().apply {
                    put("external_id", "bonjee_selftest_${System.currentTimeMillis()}")
                    put("app_package", packageName)
                    put("app_name", getString(R.string.app_name))
                    put("title", "Bonjee connection test")
                    put("content", "If you can see this row in Postgres, the app can reach n8n.")
                    put("post_time", System.currentTimeMillis())
                    put("is_clearable", true)
                    put("category", "test")
                    put("source", "android_self_test")
                }.toString()
                ApiClient.shared.postJson(prefs.notificationUrl(), payload, prefs.headers())
            }
            binding.btnTest.isEnabled = true
            binding.txtTestResult.text = buildString {
                append("URL: ${prefs.notificationUrl()}\n")
                append("Result: ${result.summary}\n")
                if (result.ok) append("OK - n8n accepted the payload.")
                else append("Check: workflow active? webhook path correct? auth header needed?")
            }
            prefs.lastDeliveryOk = result.ok
            prefs.lastDeliveryAt = System.currentTimeMillis()
            prefs.lastDeliveryMessage = result.summary
            refreshStatus()
        }
    }

    private fun syncNow() {
        lifecycleScope.launch {
            val delivered = withContext(Dispatchers.IO) {
                QueueDrainer.drain(applicationContext)
            }
            toast("Synced $delivered queued item(s)")
            refreshStatus()
        }
    }

    // ------------------------------------------------------------------ service

    private fun startCapture() {
        if (!NotificationListener.isEnabled(this)) {
            toast("Grant notification access first")
            openNotificationSettings()
            return
        }
        val intent = Intent(this, ForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            toast("Capture service started")
        } catch (e: Exception) {
            toast("Could not start service: ${e.message}")
        }
        refreshStatus()
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            toast("Open Settings > Notifications > Device & app notifications manually")
        }
    }

    private fun openAssistant() {
        startActivity(Intent(this, AskActivity::class.java))
    }

    // ------------------------------------------------------------------- status

    private fun refreshStatus() {
        val listenerOk = NotificationListener.isEnabled(this)
        val queued = DeliveryQueue(this).count()
        val running = isServiceRunning()

        binding.txtPermissionStatus.text = if (listenerOk) {
            "Notification access: GRANTED"
        } else {
            "Notification access: NOT GRANTED - tap Grant Notification Access"
        }
        binding.txtServiceStatus.text = if (running) {
            "Capture service: RUNNING"
        } else {
            "Capture service: STOPPED"
        }
        binding.txtQueueStatus.text = "Queued notifications: $queued"

        val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
        if (prefs.lastDeliveryAt > 0) {
            binding.txtLastDelivery.text = "Last delivery: ${if (prefs.lastDeliveryOk) "SUCCESS" else "FAILED"} " +
                "at ${fmt.format(Date(prefs.lastDeliveryAt))} - ${prefs.lastDeliveryMessage}"
        } else {
            binding.txtLastDelivery.text = "Last delivery: nothing sent yet"
        }
    }

    private fun isServiceRunning(): Boolean = ForegroundService.isRunning

    private fun askForNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
