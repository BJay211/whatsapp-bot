package com.bonjee.notifier.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.NotificationListener
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivityAutomationsBinding
import com.bonjee.notifier.device.TapAssistService
import com.bonjee.notifier.whatsapp.Announcer
import com.bonjee.notifier.whatsapp.AudioPolicy
import com.bonjee.notifier.whatsapp.ContactRulesStore
import com.bonjee.notifier.whatsapp.JarvisApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The automation switches, wired to the same [Prefs] the watcher and announcer
 * read. Nothing here is decorative: toggling a switch changes behaviour
 * immediately, and where Android or the workflow is the real gate (notification
 * access, accessibility, server authorisation) the screen says so instead of
 * showing a fake "on".
 */
class AutomationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutomationsBinding
    private lateinit var prefs: Prefs
    private lateinit var api: JarvisApi
    private lateinit var rules: ContactRulesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutomationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        api = JarvisApi(this)
        rules = ContactRulesStore(this)

        bindSwitches()
        bindButtons()

        binding.txtAutomationFooter.text =
            "These switches are stored on this phone. Notification capture, tap assist and the " +
                "workflow are separate permissions: each card shows its real state above."
    }

    override fun onResume() {
        super.onResume()
        saveQuietHours()
        loadSwitches()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        saveQuietHours()
    }

    private fun bindSwitches() {
        binding.switchCapture.setOnCheckedChangeListener { _, value ->
            prefs.whatsappCaptureEnabled = value
            refreshStatus()
        }
        binding.switchAnnounceImportant.setOnCheckedChangeListener { _, value ->
            prefs.announceImportant = value
            refreshStatus()
        }
        binding.switchAnnounceBody.setOnCheckedChangeListener { _, value ->
            prefs.announceBody = value
            refreshStatus()
        }
        binding.switchSpeakerBody.setOnCheckedChangeListener { _, value ->
            prefs.announceBodyOnSpeaker = value
            refreshStatus()
        }
        binding.switchEarphone.setOnCheckedChangeListener { _, value ->
            prefs.earphoneException = value
            refreshStatus()
        }
        binding.switchVolumeBoost.setOnCheckedChangeListener { _, value ->
            prefs.announceVolumeBoost = value
        }
        binding.switchQuiet.setOnCheckedChangeListener { _, value ->
            prefs.quietHoursEnabled = value
            binding.editQuietStart.isEnabled = value
            binding.editQuietEnd.isEnabled = value
            if (value) saveQuietHours()
            refreshStatus()
        }
        binding.switchNotifyOthers.setOnCheckedChangeListener { _, value ->
            prefs.notifyOtherSenders = value
        }
        binding.switchShowContent.setOnCheckedChangeListener { _, value ->
            prefs.showMessageContent = value
        }
        binding.switchAutoReply.setOnCheckedChangeListener { _, value ->
            prefs.autoReplyGlobalEnabled = value
            refreshStatus()
        }
    }

    private fun bindButtons() {
        binding.btnNotificationAccess.setOnClickListener {
            if (NotificationListener.isEnabled(this)) {
                Toast.makeText(this, "Notification access is already granted", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        binding.btnAutomationAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (t: Throwable) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        binding.btnAutomationRules.setOnClickListener {
            startActivity(Intent(this, ContactRulesActivity::class.java))
        }
        binding.btnAutomationMessageSettings.setOnClickListener {
            startActivity(Intent(this, MessageSettingsActivity::class.java))
        }
        binding.btnTestAnnouncement.setOnClickListener { testAnnouncement() }
        binding.btnAutomationRegister.setOnClickListener { registerDevice() }
        binding.btnAutomationAi.setOnClickListener {
            startActivity(Intent(this, AiSettingsActivity::class.java))
        }
        binding.btnAutomationLaptop.setOnClickListener {
            startActivity(Intent(this, LaptopActivity::class.java))
        }
        binding.btnAutomationFriday.setOnClickListener {
            startActivity(Intent(this, FridayActivity::class.java))
        }
    }

    private fun loadSwitches() {
        binding.switchCapture.isChecked = prefs.whatsappCaptureEnabled
        binding.switchAnnounceImportant.isChecked = prefs.announceImportant
        binding.switchAnnounceBody.isChecked = prefs.announceBody
        binding.switchSpeakerBody.isChecked = prefs.announceBodyOnSpeaker
        binding.switchEarphone.isChecked = prefs.earphoneException
        binding.switchVolumeBoost.isChecked = prefs.announceVolumeBoost
        binding.switchQuiet.isChecked = prefs.quietHoursEnabled
        binding.editQuietStart.setText(prefs.quietStart)
        binding.editQuietEnd.setText(prefs.quietEnd)
        binding.editQuietStart.isEnabled = prefs.quietHoursEnabled
        binding.editQuietEnd.isEnabled = prefs.quietHoursEnabled
        binding.switchNotifyOthers.isChecked = prefs.notifyOtherSenders
        binding.switchShowContent.isChecked = prefs.showMessageContent
        binding.switchAutoReply.isChecked = prefs.autoReplyGlobalEnabled
    }

    private fun refreshStatus() {
        val listener = NotificationListener.isEnabled(this)
        val tapAssist = TapAssistService.isEnabled(this)
        val decision = Announcer.get(this).decide(important = true)
        val audio = if (decision.speak) {
            "speak (${decision.reason})"
        } else {
            "held (${decision.reason})"
        }
        val quiet = if (prefs.quietHoursEnabled) "${prefs.quietStart}-${prefs.quietEnd}" else "off"

        binding.txtAutomationStatus.text = buildString {
            appendLine("notification access: " + if (listener) "granted" else "NOT granted")
            appendLine("tap assist: " + if (tapAssist) "enabled" else "off")
            appendLine("quiet hours: $quiet")
            append("announcement now: $audio")
        }

        binding.txtCaptureHint.text = when {
            prefs.whatsappCaptureEnabled && listener ->
                "On. Incoming WhatsApp notifications are read by the listener on this phone."
            prefs.whatsappCaptureEnabled ->
                "Switch is on, but notification access is NOT granted, so nothing is captured yet."
            else ->
                "Off. WhatsApp notifications are ignored until you switch this on."
        }

        val authorized = prefs.serverAuthorizedNumbers.size
        val autoContacts = rules.load().items.count { it.autoReplyEnabled }
        binding.txtAutoReplyHint.text = when {
            !prefs.autoReplyGlobalEnabled ->
                "Off. No auto-reply is sent, even for contacts marked Auto-Reply."
            authorized == 0 ->
                "On locally, but the workflow has authorised no numbers. Nothing is sent until it does."
            autoContacts == 0 ->
                "$authorized number(s) authorised, but no contact has auto-reply enabled. Add one in Message Rules."
            else ->
                "$authorized number(s) authorised; $autoContacts contact(s) have auto-reply enabled. " +
                    "Per-contact switches and the workflow's authorisation both still apply."
        }

        binding.txtTapAssistStatus.text = if (tapAssist) {
            "Enabled. JARVIS can press WhatsApp's call/send button only when you ask it to, only inside WhatsApp."
        } else {
            "Off. WhatsApp calls and sends stop with the chat open so you press the button. " +
                "Enable it in Accessibility if you want JARVIS to press it for you."
        }

        val registered = if (prefs.deviceRegisteredAt > 0) {
            "registered " + SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                .format(Date(prefs.deviceRegisteredAt))
        } else {
            "not registered"
        }
        binding.txtWorkflowStatus.text = buildString {
            appendLine("endpoint: " + prefs.appApiUrl())
            appendLine("device: $registered")
            append("server-authorised numbers: $authorized")
        }

        binding.txtAiCardStatus.text = when {
            !prefs.aiEnabled ->
                "Off. Questions go to the n8n archive instead."
            prefs.aiApiKey.isBlank() ->
                "On, but no API key is saved, so it cannot answer yet."
            else -> buildString {
                append(prefs.aiModel)
                append(" via ")
                append(prefs.aiBaseUrl)
                if (prefs.aiLastOkAt > 0) {
                    append(" · last success ")
                    append(
                        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                            .format(Date(prefs.aiLastOkAt)),
                    )
                } else {
                    append(" · not tested yet")
                }
            }
        }

        binding.txtLaptopCardStatus.text = when {
            !prefs.laptopEnabled ->
                "Off. Nothing is ever sent to a computer."
            !prefs.laptopConfigured() ->
                "On, but the address or token is missing."
            else -> buildString {
                append(prefs.laptopBaseUrl())
                append(" · ")
                append(if (prefs.laptopAskBeforeRun) "asks before running" else "runs without asking")
                if (prefs.laptopLastSeenAt > 0) {
                    append(" · last seen ")
                    append(
                        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                            .format(Date(prefs.laptopLastSeenAt)),
                    )
                }
            }
        }

        binding.txtFridayCardStatus.text = when {
            !prefs.fridayEnabled ->
                "Off. \"Friday, ...\" stays a normal question."
            prefs.fridayHost.isBlank() ->
                "On, but no laptop address is set."
            !prefs.fridayConfigured() ->
                "Address set, but this phone is not paired yet."
            else -> buildString {
                append(prefs.fridayBaseUrl())
                append(" · paired")
                if (prefs.fridayLastOkAt > 0) {
                    append(" · last success ")
                    append(
                        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                            .format(Date(prefs.fridayLastOkAt)),
                    )
                }
            }
        }
    }

    private fun testAnnouncement() {
        saveQuietHours()
        val announcer = Announcer.get(this)
        val decision = announcer.decide(important = true)
        val hint = AudioPolicy.suppressionHint(decision) ?: "Test announcement playing."
        binding.txtAutomationStatus.text = hint
        if (decision.speak) {
            announcer.speak("This is JARVIS. Important WhatsApp messages will be announced like this.")
        }
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        refreshStatus()
    }

    private fun registerDevice() {
        saveQuietHours()
        binding.txtWorkflowStatus.text = "Registering with ${prefs.appApiUrl()} ..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { api.registerDevice() }
            if (result.ok) {
                prefs.deviceRegisteredAt = System.currentTimeMillis()
                binding.txtWorkflowStatus.text =
                    "Registered (HTTP ${result.code}). Push provider: " +
                        result.field("push_provider").ifBlank { "none" } + ". " +
                        "Delivery still uses the notification listener in this build."
            } else {
                binding.txtWorkflowStatus.text = result.userMessage()
            }
            refreshStatus()
        }
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
        val hour = parts[0].toIntOrNull() ?: return false
        val minute = parts[1].toIntOrNull() ?: return false
        return hour in 0..23 && minute in 0..59
    }

    @Suppress("unused")
    private fun dndNote(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            "Android controls Do Not Disturb; JARVIS never claims to override it."
        } else {
            "This Android version has no per-app DND policy."
        }
}
