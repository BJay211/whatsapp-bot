package com.bonjee.notifier

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.databinding.ActivityAskBinding
import com.bonjee.notifier.device.DeviceActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Asks the n8n assistant a question and shows the grounded answer/evidence.
 * Uses the same endpoint exposed by the Bonjee n8n workflow.
 *
 * When launched from the Android assistant role (see
 * [com.bonjee.notifier.assistant.BonjeeVoiceInteractionSession]) the activity
 * opens the microphone immediately, sends the recognised text to the same
 * endpoint the app has always used, and reads the answer out loud. Nothing about
 * the backend, the endpoint configuration or the stored conversation changes.
 */
class AskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAskBinding
    private lateinit var prefs: Prefs

    /** True when Android summoned us as the default assistant. */
    private var fromAssistant = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    /** Sentence waiting for a permission/settings round-trip, so it can be retried. */
    private var pendingQuestion: String? = null

    /** Stops a settings screen from reopening in a loop when nothing was granted. */
    private var settingsShownOnce = false

    /** Permissions the user has already refused in this session. */
    private val deniedPermissions = mutableSetOf<String>()

    /**
     * Runtime permission result for a device command ("call mum" needs contacts).
     * On success the original sentence is replayed, so the action completes without
     * the user having to repeat themselves.
     */
    private val devicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val question = pendingQuestion
        pendingQuestion = null
        if (question == null) return@registerForActivityResult
        val granted = grants.isNotEmpty() && grants.values.all { it }
        if (!granted) {
            deniedPermissions.addAll(grants.keys)
            binding.txtAnswer.text = buildString {
                append("Permission denied.\n\n")
                append("Open Settings > Apps > JARVIS > Permissions, allow it, then ask again.")
            }
            return@registerForActivityResult
        }
        submit(question)
    }

    /** Returns from a settings screen the command asked for, then retries once. */
    private val deviceSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val question = pendingQuestion
        pendingQuestion = null
        if (question != null) submit(question)
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            binding.txtAnswer.text =
                "No speech was recognised. Tap the microphone again or type your question."
            return@registerForActivityResult
        }
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
            .trim()
        if (spoken.isEmpty()) {
            binding.txtAnswer.text =
                "No speech was recognised. Tap the microphone again or type your question."
            return@registerForActivityResult
        }
        binding.editQuestion.setText(spoken)
        ask()
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            binding.txtAnswer.text =
                "Microphone permission denied. Allow it in Settings > Apps > JARVIS > " +
                    "Permissions, or type your question below."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        fromAssistant = intent.getBooleanExtra(EXTRA_FROM_ASSISTANT, false)
        allowOverKeyguard()

        // Auto-fill query if passed from home or from the assistant session.
        val passedQuery = intent.getStringExtra(EXTRA_QUERY)
        if (!passedQuery.isNullOrEmpty()) {
            binding.editQuestion.setText(passedQuery)
        }

        binding.btnAsk.setOnClickListener { ask() }
        binding.btnMic.setOnClickListener { startVoiceInput() }
        binding.btnBack.setOnClickListener { finish() }

        if (fromAssistant) {
            binding.txtAnswer.text = "Listening..."
            if (!passedQuery.isNullOrEmpty()) {
                ask()
            } else {
                startVoiceInput()
            }
        } else if (!passedQuery.isNullOrEmpty()) {
            // Sent from the home screen: run it straight away instead of making the
            // user press Ask a second time.
            ask()
        }
    }

    /**
     * The assistant can be summoned while the phone is locked, and the sentence
     * it heard lands here. Without these flags this screen would be created behind
     * the keyguard, so the command would run but look like nothing happened.
     */
    private fun allowOverKeyguard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        if (keyguard.isKeyguardLocked) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    override fun onDestroy() {
        pendingSpeech = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDestroy()
    }

    // ------------------------------------------------------------ voice input

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (t: Throwable) {
            // No speech recogniser installed, or it was disabled by the OEM.
            binding.txtAnswer.text =
                "No speech recognition service is available on this device. " +
                    "Type your question below."
            Toast.makeText(this, "Speech recognition unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    // --------------------------------------------------------------- question

    private fun ask() {
        val question = binding.editQuestion.text.toString().trim()
        if (question.isEmpty()) {
            Toast.makeText(this, "Type a question first", Toast.LENGTH_SHORT).show()
            return
        }
        submit(question)
    }

    /**
     * Device-first routing.
     *
     * Every sentence is offered to [DeviceActions] before the network. A match runs
     * on the phone (open an app, change brightness/volume, torch, alarm, call...)
     * and answers immediately, even offline. Anything unrecognised is declared
     * NotHandled and continues to the n8n workflow exactly as it always did, so the
     * archived-question behaviour is unchanged.
     */
    private fun submit(question: String) {
        val outcome = try {
            DeviceActions.handle(this, question)
        } catch (t: Throwable) {
            // A device command must never take the whole assistant down.
            present(
                "I couldn't run that (${t.javaClass.simpleName}). " +
                    "Say \"set up access\" to check my permissions, or ask me a question.",
            )
            return
        }
        when (outcome) {
            is DeviceActions.Result.Done -> {
                settingsShownOnce = false
                present(outcome.message)
            }
            is DeviceActions.Result.NeedsPermission ->
                requestDevicePermission(outcome.permission, outcome.why, question)
            is DeviceActions.Result.NeedsSettings ->
                openRequiredSetting(outcome.intent, outcome.why, question)
            is DeviceActions.Result.Open -> {
                present(outcome.message)
                try {
                    startActivity(outcome.intent)
                } catch (t: Throwable) {
                    // Screen missing (should not happen) - the message still explains it.
                }
            }
            DeviceActions.Result.NotHandled -> askBackend(question)
        }
    }

    private fun present(message: String) {
        binding.btnAsk.isEnabled = true
        binding.txtAnswer.text = message
        speak(message)
    }

    private fun requestDevicePermission(permission: String, why: String, question: String) {
        binding.txtAnswer.text = why
        speak(why)
        if (deniedPermissions.contains(permission)) {
            binding.txtAnswer.text = buildString {
                append(why)
                append("\n\nIt was denied earlier. Allow it in ")
                append("Settings > Apps > JARVIS > Permissions, then ask again.")
            }
            return
        }
        pendingQuestion = question
        try {
            devicePermissionLauncher.launch(arrayOf(permission))
        } catch (t: Throwable) {
            pendingQuestion = null
            binding.txtAnswer.text = why
        }
    }

    private fun openRequiredSetting(intent: Intent, why: String, question: String) {
        binding.txtAnswer.text = why
        speak(why)
        if (settingsShownOnce) {
            // The user came back from that screen and it is still not granted.
            // Say so instead of leaving a stale "opening..." message.
            settingsShownOnce = false
            binding.txtAnswer.text = buildString {
                append(why)
                append("\n\nStill not allowed. Say \"set up access\" for a guided list, ")
                append("or open it yourself: Settings > Apps > JARVIS.")
            }
            return
        }
        settingsShownOnce = true
        pendingQuestion = question
        try {
            deviceSettingsLauncher.launch(intent)
        } catch (t: Throwable) {
            settingsShownOnce = false
            pendingQuestion = null
            binding.txtAnswer.text = why
        }
    }

    private fun askBackend(question: String) {
        settingsShownOnce = false
        binding.btnAsk.isEnabled = false
        binding.txtAnswer.text = "Thinking..."

        lifecycleScope.launch {
            val payload = JSONObject().apply { put("question", question) }.toString()
            val result = withContext(Dispatchers.IO) {
                ApiClient.shared.postJson(prefs.askUrl(), payload, prefs.headers())
            }
            binding.btnAsk.isEnabled = true
            if (result.ok) {
                val answer = extractAnswer(result.body)
                binding.txtAnswer.text = answer
                speak(answer)
            } else {
                binding.txtAnswer.text = buildString {
                    append("Could not reach the assistant.\n\n")
                    append("Endpoint: ${prefs.askUrl()}\n")
                    append("${result.summary}\n\n")
                    append(
                        "Check your internet connection and that the n8n workflow is " +
                            "active, then try again.\n\n",
                    )
                    append(
                        "Device commands still work without internet - try \"open WhatsApp\", " +
                            "\"brightness up\", \"torch on\" or \"what can you do\".",
                    )
                }
            }
        }
    }

    /**
     * The workflow returns JSON, but the field name varies between builds, so
     * prefer a human-readable field and fall back to the pretty-printed payload.
     */
    private fun extractAnswer(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return raw
        return try {
            val json = JSONObject(trimmed)
            ANSWER_KEYS
                .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
                .firstOrNull()
                ?: json.toString(2)
        } catch (t: Throwable) {
            raw
        }
    }

    // ----------------------------------------------------------- spoken reply

    private fun speak(text: String) {
        if (!fromAssistant || text.isBlank()) return
        pendingSpeech = text
        if (ttsReady) {
            speakPending()
            return
        }
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    runCatching { tts?.language = Locale.getDefault() }
                    speakPending()
                }
            }
        }
    }

    private fun speakPending() {
        val text = pendingSpeech ?: return
        if (!ttsReady) return
        pendingSpeech = null
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    companion object {
        /** Extra used by the assistant session to request an immediate listen. */
        const val EXTRA_FROM_ASSISTANT = "bonjee.extra.FROM_ASSISTANT"

        /** Kept for compatibility with the existing home screen hand-off. */
        const val EXTRA_QUERY = "query"

        private const val UTTERANCE_ID = "bonjee-answer"

        private val ANSWER_KEYS = listOf("answer", "response", "text", "output", "message")
    }
}
