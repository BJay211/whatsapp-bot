package com.bonjee.notifier.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.NotificationListener
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.databinding.ActivityMessageSettingsBinding
import com.bonjee.notifier.whatsapp.Announcer
import com.bonjee.notifier.whatsapp.AudioPolicy
import com.bonjee.notifier.whatsapp.JarvisApi
import com.bonjee.notifier.whatsapp.MessageStore
import com.bonjee.notifier.whatsapp.SelfChecks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audio/privacy preferences, workflow endpoint and self-checks.
 *
 * The endpoint fields only store what the user typed. Nothing is called until
 * REGISTER THIS DEVICE is pressed, and the result is displayed verbatim - a 404
 * means the workflow has not been updated, not that the app is connected.
 */
class MessageSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessageSettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var api: JarvisApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        api = JarvisApi(this)

        bindAudioSwitches()
        bindNotificationSwitches()

        binding.editAppApiPath.setText(prefs.appApiPath)
        binding.editCountryCode.setText(prefs.defaultCountryCode)
        binding.txtDeviceId.text = "device id: ${prefs.deviceId}"

        binding.btnTestAnnounce.setOnClickListener { testAnnouncement() }
        binding.btnDndSettings.setOnClickListener { openDndSettings() }
        binding.btnNotificationAccess.setOnClickListener { openNotificationAccess() }
        binding.btnSaveEndpoint.setOnClickListener { saveEndpoint() }
        binding.btnRegisterDevice.setOnClickListener { registerDevice() }
        binding.btnSelfChecks.setOnClickListener { runSelfChecks() }
        binding.btnOpenRulesFromSettings.setOnClickListener {
            startActivity(Intent(this, ContactRulesActivity::class.java))
        }
        binding.btnClearMessages.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        saveQuietHours()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        saveQuietHours()
    }

    private fun bindAudioSwitches() {
        binding.switchAnnounce.isChecked = prefs.announceImportant
        binding.switchAnnounceBody.isChecked = prefs.announceBody
        binding.switchSpeakerBody.isChecked = prefs.announceBodyOnSpeaker
        binding.switchEarphone.isChecked = prefs.earphoneException
        binding.switchVolumeBoost.isChecked = prefs.announceVolumeBoost
        binding.switchQuiet.isChecked = prefs.quietHoursEnabled
        binding.editQuietStart.setText(prefs.quietStart)
        binding.editQuietEnd.setText(prefs.quietEnd)
        binding.editQuietStart.isEnabled = prefs.quietHoursEnabled
        binding.editQuietEnd.isEnabled = prefs.quietHoursEnabled

        binding.switchAnnounce.setOnCheckedChangeListener { _, v ->
            prefs.announceImportant = v
            refreshStatus()
        }
        binding.switchAnnounceBody.setOnCheckedChangeListener { _, v -> prefs.announceBody = v }
        binding.switchSpeakerBody.setOnCheckedChangeListener { _, v -> prefs.announceBodyOnSpeaker = v }
        binding.switchEarphone.setOnCheckedChangeListener { _, v -> prefs.earphoneException = v }
        binding.switchVolumeBoost.setOnCheckedChangeListener { _, v -> prefs.announceVolumeBoost = v }
        binding.switchQuiet.setOnCheckedChangeListener { _, v ->
            prefs.quietHoursEnabled = v
            binding.editQuietStart.isEnabled = v
            binding.editQuietEnd.isEnabled = v
            if (v) saveQuietHours()
        }
    }

    private fun bindNotificationSwitches() {
        binding.switchNotifyOthers.isChecked = prefs.notifyOtherSenders
        binding.switchShowContent.isChecked = prefs.showMessageContent
        binding.switchNotifyOthers.setOnCheckedChangeListener { _, v -> prefs.notifyOtherSenders = v }
        binding.switchShowContent.setOnCheckedChangeListener { _, v -> prefs.showMessageContent = v }
    }

    private fun saveQuietHours() {
        val start = binding.editQuietStart.text?.toString()?.trim().orEmpty()
        val end = binding.editQuietEnd.text?.toString()?.trim().orEmpty()
        if (isTime(start)) prefs.quietStart = start
        if (isTime(end)) prefs.quietEnd = end
    }

    private fun isTime(value: String): Boolean {
        val parts = value.split(":")
        if (parts.size != 2) return false
        val h = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        return h in 0..23 && m in 0..59
    }

    private fun refreshStatus() {
        val announcer = Announcer.get(this)
        val decision = announcer.decide(important = true)
        val n8n = if (api.isConfigured()) prefs.appApiUrl() else "not configured"
        val listener = if (NotificationListener.isEnabled(this)) "granted" else "NOT granted"
        val registered = if (prefs.deviceRegisteredAt > 0) {
            "registered " + SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                .format(Date(prefs.deviceRegisteredAt))
        } else {
            "not registered with the workflow"
        }
        val quiet = if (prefs.quietHoursEnabled) "${prefs.quietStart}-${prefs.quietEnd}" else "off"
        binding.txtBackendStatus.text = buildString {
            appendLine("app endpoint : $n8n")
            appendLine("device       : $registered")
            appendLine("notifications: $listener")
            appendLine("quiet hours  : $quiet")
            append("audio now    : ").append(if (decision.speak) "would speak (${decision.reason})" else "held (${decision.reason})")
        }
    }

    private fun testAnnouncement() {
        saveQuietHours()
        val announcer = Announcer.get(this)
        val decision = announcer.decide(important = true)
        val hint = AudioPolicy.suppressionHint(decision) ?: "Test announcement playing."
        binding.txtBackendStatus.text = hint
        if (decision.speak) {
            announcer.speak("This is JARVIS. Important WhatsApp messages will be announced like this.")
        }
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
    }

    private fun openDndSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        Toast.makeText(
            this,
            "DND is controlled by Android. JARVIS does not request notification-policy access and cannot override DND.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun openNotificationAccess() {
        if (NotificationListener.isEnabled(this)) {
            Toast.makeText(this, "Notification access is already granted", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun saveEndpoint() {
        val path = binding.editAppApiPath.text?.toString()?.trim().orEmpty()
        val cc = binding.editCountryCode.text?.toString()?.trim().orEmpty()
        if (path.isBlank()) {
            Toast.makeText(this, "Enter the app endpoint path", Toast.LENGTH_SHORT).show()
            return
        }
        if (cc.length !in 1..4 || cc.any { !it.isDigit() }) {
            Toast.makeText(this, "Country code should be digits, for example 234", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.appApiPath = path
        prefs.defaultCountryCode = cc
        refreshStatus()
        Toast.makeText(
            this,
            "Saved. Endpoint: ${prefs.appApiUrl()}. Nothing is verified until you register.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun registerDevice() {
        saveEndpoint()
        if (!api.isConfigured()) {
            binding.txtBackendStatus.text = "No endpoint configured. Nothing was sent."
            return
        }
        binding.txtBackendStatus.text = "Registering with the workflow at ${prefs.appApiUrl()} ..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { api.registerDevice() }
            if (result.ok) {
                prefs.deviceRegisteredAt = System.currentTimeMillis()
                val push = result.field("push_provider").ifBlank { "none" }
                binding.txtBackendStatus.text =
                    "Registered (HTTP ${result.code}). Push provider: $push.\n" +
                        "This build uses the notification listener for delivery; FCM is optional backend work."
            } else {
                binding.txtBackendStatus.text = result.userMessage()
            }
        }
    }

    private fun runSelfChecks() {
        binding.txtBackendStatus.text = "Running self-checks..."
        lifecycleScope.launch {
            val results = withContext(Dispatchers.Default) { SelfChecks.run() }
            val passed = results.count { it.ok }
            val failed = results.filterNot { it.ok }
            binding.txtBackendStatus.text = if (failed.isEmpty()) {
                "Self-checks: $passed/${results.size} passed. These are logic checks, not device tests."
            } else {
                buildString {
                    appendLine("Self-checks: $passed/${results.size} passed.")
                    failed.take(6).forEach { appendLine("FAIL ${it.name} ${it.detail}") }
                }.trim()
            }
            Toast.makeText(this@MessageSettingsActivity, "$passed/${results.size} checks passed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear local message history?")
            .setMessage("This removes the app's copy of captured messages and drafts. It does not delete anything on the phone or in WhatsApp.")
            .setPositiveButton("Clear") { _, _ ->
                MessageStore(this).clear()
                Toast.makeText(this, "Local message history cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
