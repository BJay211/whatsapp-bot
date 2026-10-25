package com.bonjee.notifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.databinding.ActivityAskBinding
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
                            "active, then try again.",
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
