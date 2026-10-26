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

    /**
     * Result of the Android 10+ "assistant" role dialog. The system already
     * applied the user's choice by the time we get here, so only the status line
     * needs refreshing.
     */
    private val requestAssistantRole = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshAssistantStatus()
        if (com.bonjee.notifier.assistant.AssistantRole.isDefault(this)) {
            toast("JARVIS is now your default assistant")
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
        binding.btnSetAssistant.setOnClickListener { requestAssistantRole() }
        binding.btnAssistantDiagnostics.setOnClickListener { showAssistantDiagnostics() }
        binding.btnTestAssistant.setOnClickListener { testVoiceAssistant() }
        binding.btnSetupAccess.setOnClickListener {
            startActivity(Intent(this, com.bonjee.notifier.ui.SetupAccessActivity::class.java))
        }
        binding.btnDeviceCommands.setOnClickListener {
            startActivity(Intent(this, com.bonjee.notifier.ui.CommandsActivity::class.java))
        }
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
        refreshAssistantStatus()
    }

    /**
     * Shows whether Android currently routes assistant invocations to this app.
     * Purely informational - the system forbids changing it programmatically.
     */
    private fun refreshAssistantStatus() {
        val isDefault = com.bonjee.notifier.assistant.AssistantRole.isDefault(this)
        binding.txtAssistantStatus.text = if (isDefault) {
            "Default assistant: JARVIS is active"
        } else if (com.bonjee.notifier.assistant.AssistantRole.isRoleAvailable(this)) {
            "Default assistant: not set - tap below to choose JARVIS"
        } else {
            "Default assistant: not set (this Android version has no assistant role)"
        }
    }

    /**
     * Asks the system to consider this app as the assistant. Android 10+ shows
     * the official role dialog; everything else falls back to the settings screen
     * where the user makes the same choice by hand.
     */
    private fun requestAssistantRole() {
        val intent = com.bonjee.notifier.assistant.AssistantRole.roleRequestIntent(this)
        if (intent != null && com.bonjee.notifier.assistant.AssistantRole.canHandle(this, intent)) {
            try {
                requestAssistantRole.launch(intent)
                return
            } catch (t: Throwable) {
                // No activity handles the role request; fall through to Settings.
            }
        }
        toast("Opening Android settings - choose Digital assistant app, then JARVIS (Bonjee)")
        com.bonjee.notifier.assistant.AssistantRole.openSettings(this)
    }

    /**
     * Shows what the system reports about the assistant registration, using the
     * same PackageManager/RoleManager queries the Digital assistant app picker
     * uses. Read-only: nothing is changed by looking.
     */
    private fun showAssistantDiagnostics() {
        val report = com.bonjee.notifier.assistant.AssistantDiagnostics.report(this)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Assistant status")
            .setMessage(report)
            .setPositiveButton("Share") { _, _ ->
                try {
                    startActivity(
                        Intent.createChooser(
                            com.bonjee.notifier.assistant.AssistantDiagnostics.shareIntent(report),
                            "Share diagnostic",
                        ),
                    )
                } catch (t: Throwable) {
                    toast("No app available to share the report")
                }
            }
            .setNegativeButton("Close", null)
            .show()
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
            val dashboard = withContext(Dispatchers.IO) {
                ApiClient.shared.get(prefs.dashboardUrl("/api/health"), prefs.headers())
            }
            binding.txtTestResult.text = buildString {
                append("n8n URL: ${prefs.notificationUrl()}\n")
                append("n8n result: ${result.summary}\n")
                if (result.ok) append("OK - n8n accepted the payload.\n")
                else append("Check: workflow active? webhook path correct? auth header needed?\n")
                append("Dashboard: ${prefs.dashboardUrl("/api/health")}\n")
                append("Dashboard result: ${dashboard.summary}\n")
                if (dashboard.ok) append("OK - the dashboard answers, the side menu will load live data.")
                else append("Check: Dashboard URL set? service deployed on Render?")
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

    /**
     * Runs the same destinations the system trigger uses - the JARVIS home page in
     * assistant mode, plus the ASSIST intent that some ROMs dispatch - without
     * depending on the assistant role. If this works but the gesture does not, the
     * problem is the ROM's routing, not the app.
     */
    private fun testVoiceAssistant() {
        val intent = Intent(this, com.bonjee.notifier.ui.JarvisHomeActivity::class.java).apply {
            putExtra(
                com.bonjee.notifier.ui.JarvisHomeActivity.EXTRA_FROM_ASSISTANT,
                true,
            )
        }
        try {
            startActivity(intent)
            return
        } catch (t: Throwable) {
            // Fall through to the older Ask screen so the test still shows something.
        }
        try {
            startActivity(
                Intent(this, AskActivity::class.java).apply {
                    putExtra(AskActivity.EXTRA_FROM_ASSISTANT, true)
                },
            )
        } catch (t: Throwable) {
            toast("Could not open the assistant screen")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
