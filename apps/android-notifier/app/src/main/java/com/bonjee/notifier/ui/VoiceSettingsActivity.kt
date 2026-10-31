package com.bonjee.notifier.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.databinding.ActivityVoiceSettingsBinding
import com.bonjee.notifier.memory.ConversationStore
import com.bonjee.notifier.voice.JarvisWakeService
import com.bonjee.notifier.voice.SpeechOutput
import java.util.Locale

/**
 * Voice behaviour and tone.
 *
 * Every switch is applied immediately. The voice list and the engine line come
 * from the phone's real text-to-speech engine, so a missing language or voice is
 * visible here rather than discovered mid-answer. Nothing claims to be
 * listening: the wake switch starts the visible foreground service, and its
 * status line repeats that some phones throttle background audio.
 */
class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceSettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var store: ConversationStore
    private lateinit var speech: SpeechOutput

    private var voices: List<SpeechOutput.VoiceInfo> = emptyList()
    private var suppress = false

    private val wakePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.isNotEmpty() && grants.values.all { it }
        if (!granted) {
            suppress = true
            binding.switchWake.isChecked = false
            suppress = false
            prefs.wakeWordEnabled = false
            JarvisWakeService.stop(this)
            status(
                "Wake listening needs the microphone permission" +
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        " and notification permission"
                    } else "",
            )
            return@registerForActivityResult
        }
        prefs.wakeWordEnabled = true
        val started = JarvisWakeService.start(this)
        status(
            if (started) "Wake listener started. A notification is showing while it listens."
            else "The phone refused to start the wake listener. The microphone button still works.",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        store = ConversationStore(this)
        speech = SpeechOutput(this, speechListener)
        speech.initialize()

        binding.btnVoiceBack.setOnClickListener { finish() }
        setupSwitches()
        setupSliders()
        setupSpinner()
        setupButtons()
        refreshContext()
    }

    override fun onDestroy() {
        speech.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup

    private fun setupSwitches() {
        binding.switchAutoSpeak.isChecked = prefs.voiceAutoSpeak
        binding.switchAutoSpeak.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            prefs.voiceAutoSpeak = checked
            if (!checked) speech.stop()
            status(if (checked) "Answers are read aloud." else "Answers are shown only.")
        }

        binding.switchContinuous.isChecked = prefs.voiceContinuous
        binding.switchContinuous.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            prefs.voiceContinuous = checked
            status(
                if (checked) "The microphone reopens after each answer."
                else "The microphone closes after each answer.",
            )
        }

        binding.switchBargeIn.isChecked = prefs.voiceBargeIn
        binding.switchBargeIn.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            prefs.voiceBargeIn = checked
            status(
                if (checked) "Say “stop” or “JARVIS, stop” to interrupt speech."
                else "Speech can only be stopped with the STOP button.",
            )
        }

        binding.switchWake.isChecked = prefs.wakeWordEnabled && JarvisWakeService.isRunning
        binding.switchWake.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            if (!checked) {
                prefs.wakeWordEnabled = false
                JarvisWakeService.stop(this)
                status("Wake listener stopped. Nothing is using the microphone now.")
                return@setOnCheckedChangeListener
            }
            val missing = ArrayList<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (missing.isEmpty()) {
                prefs.wakeWordEnabled = true
                val started = JarvisWakeService.start(this)
                status(
                    if (started) "Wake listener started. A notification is showing while it listens."
                    else "The phone refused to start the wake listener. The microphone button still works.",
                )
            } else {
                wakePermissions.launch(missing.toTypedArray())
            }
        }
    }

    private fun setupSliders() {
        binding.seekFollowUp.max = 60
        binding.seekFollowUp.progress = prefs.voiceFollowUpSeconds.coerceIn(0, 60)
        showFollowUp(prefs.voiceFollowUpSeconds)
        binding.seekFollowUp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                showFollowUp(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                val seconds = bar?.progress ?: return
                prefs.voiceFollowUpSeconds = seconds
                status(
                    if (seconds == 0) "Follow-up listening is off. Tap the microphone for each question."
                    else "After an answer the microphone stays open for $seconds seconds of silence.",
                )
            }
        })

        binding.seekRate.max = 150
        binding.seekRate.progress = ((prefs.voiceRate - 0.5f) * 100f).toInt().coerceIn(0, 150)
        showRate(prefs.voiceRate)
        binding.seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + progress / 100f
                showRate(rate)
                if (!fromUser) return
                prefs.voiceRate = rate
                speech.applySettings()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        binding.seekPitch.max = 150
        binding.seekPitch.progress = ((prefs.voicePitch - 0.5f) * 100f).toInt().coerceIn(0, 150)
        showPitch(prefs.voicePitch)
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = 0.5f + progress / 100f
                showPitch(pitch)
                if (!fromUser) return
                prefs.voicePitch = pitch
                speech.applySettings()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    private fun setupSpinner() {
        binding.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppress) return
                val selected = if (position <= 0) "" else voices.getOrNull(position - 1)?.name.orEmpty()
                prefs.voiceName = selected
                speech.applySettings()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupButtons() {
        binding.btnVoiceTest.setOnClickListener {
            if (prefs.voiceMuted) {
                status("Spoken replies are muted in the conversation. Unmute there first.")
                return@setOnClickListener
            }
            speech.speak(
                "All systems online, boss. This is the voice I will use for your answers.",
            )
        }
        binding.btnVoiceStop.setOnClickListener {
            speech.stop()
            status("Stopped speaking.")
        }
        binding.btnVoiceClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear this phone's conversation?")
                .setMessage(
                    "The recent context used for follow-up questions is deleted from this phone. " +
                        "Friday's transcript on the laptop is not touched.",
                )
                .setPositiveButton("Clear") { _, _ ->
                    store.clear()
                    refreshContext()
                    status("Conversation context cleared.")
                }
                .setNegativeButton("Keep", null)
                .show()
        }
    }

    private fun showFollowUp(seconds: Int) {
        binding.txtFollowUp.text =
            if (seconds <= 0) "Off — the microphone closes after each answer"
            else "$seconds seconds without speech"
    }

    private fun showRate(rate: Float) {
        binding.txtRate.text = String.format(Locale.US, "Speed %.2f×", rate)
    }

    private fun showPitch(pitch: Float) {
        binding.txtPitch.text = String.format(Locale.US, "Pitch %.2f×", pitch)
    }

    private fun refreshContext() {
        val turns = store.turns()
        binding.txtVoiceContext.text = if (turns.isEmpty()) {
            "No saved conversation on this phone yet."
        } else {
            "${turns.size} saved turns · last answer " + relativeTime(turns.last().at)
        }
    }

    private fun relativeTime(at: Long): String {
        if (at <= 0L) return "unknown"
        val delta = (System.currentTimeMillis() - at) / 1000L
        return when {
            delta < 60 -> "${delta}s ago"
            delta < 3600 -> "${delta / 60}m ago"
            delta < 86_400 -> "${delta / 3600}h ago"
            else -> "${delta / 86_400}d ago"
        }
    }

    private fun status(text: String) {
        binding.txtVoiceStatus.text = text
    }

    // ----------------------------------------------------------------- speech

    private val speechListener = object : SpeechOutput.Listener {
        override fun onReady(available: Boolean, detail: String) {
            binding.txtVoiceEngine.text = detail
            if (!available) {
                status(detail)
                return
            }
            voices = speech.voices()
            val labels = ArrayList<String>(voices.size + 1)
            labels.add("Engine default")
            voices.forEach { labels.add(it.label()) }
            val adapter = ArrayAdapter(
                this@VoiceSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels,
            )
            suppress = true
            binding.spinnerVoice.adapter = adapter
            val saved = prefs.voiceName
            val index = voices.indexOfFirst { it.name == saved }
            binding.spinnerVoice.setSelection(if (index >= 0) index + 1 else 0)
            suppress = false
            if (voices.isEmpty()) {
                status("This phone's speech engine reported no installed voices.")
            }
        }

        override fun onStart(text: String) {
            status("Speaking now.")
        }

        override fun onDone() {
            status("Finished speaking.")
        }

        override fun onStopped() {
            status("Stopped speaking.")
        }

        override fun onError(message: String) {
            status(message)
        }

        override fun onVoicesChanged(voices: List<SpeechOutput.VoiceInfo>) {
            if (this@VoiceSettingsActivity.voices.isEmpty()) {
                this@VoiceSettingsActivity.voices = voices
            }
        }
    }
}
