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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.ai.AiClient
import com.bonjee.notifier.databinding.ActivityAskBinding
import com.bonjee.notifier.device.DeviceActions
import com.bonjee.notifier.laptop.LaptopClient
import com.bonjee.notifier.laptop.LaptopCommand
import com.bonjee.notifier.laptop.LaptopHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * The assistant.
 *
 * Order for every sentence:
 *  1. [DeviceActions] - phone commands, answered offline;
 *  2. a laptop command when it is addressed to the computer ("on my laptop ..."),
 *     shown in full and approved before it runs;
 *  3. the user's own AI provider, when configured;
 *  4. the n8n archive, which is also the fallback when AI is off or fails.
 *
 * Steps 3 and 4 report the real HTTP outcome. Nothing here pretends an answer
 * came from a service that did not respond.
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

    /**
     * Whether the whole sentence may be replayed after the round-trip. False
     * when an earlier step of a chain already ran: replaying could send twice.
     */
    private var pendingRetry = true

    /** Stops a settings screen from reopening in a loop when nothing was granted. */
    private var settingsShownOnce = false

    /** Permissions the user has already refused in this session. */
    private val deniedPermissions = mutableSetOf<String>()

    /** Rolling AI context, oldest first. Never persisted; cleared with the activity. */
    private val aiHistory = ArrayDeque<AiClient.Message>()

    /** One laptop command at a time, so a double tap cannot send twice. */
    private var laptopBusy = false

    /**
     * Runtime permission result for a device command ("call mum" needs contacts).
     * On success the original sentence is replayed, so the action completes without
     * the user having to repeat themselves.
     */
    private val devicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val question = pendingQuestion
        val retry = pendingRetry
        pendingQuestion = null
        pendingRetry = true
        if (question == null) {
            if (grants.isNotEmpty() && grants.values.all { it }) {
                binding.txtAnswer.text =
                    "Permission granted. Say the command again and I will continue from the start."
            }
            return@registerForActivityResult
        }
        val granted = grants.isNotEmpty() && grants.values.all { it }
        if (!granted) {
            deniedPermissions.addAll(grants.keys)
            binding.txtAnswer.text = buildString {
                append("Permission denied.\n\n")
                append("Open Settings > Apps > JARVIS > Permissions, allow it, then ask again.")
            }
            return@registerForActivityResult
        }
        if (retry) {
            submit(question)
        } else {
            binding.txtAnswer.text =
                "Permission granted. Earlier steps already ran, so say the command again - " +
                    "I won't repeat them by myself."
        }
    }

    /** Returns from a settings screen the command asked for, then retries once. */
    private val deviceSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val question = pendingQuestion
        val retry = pendingRetry
        pendingQuestion = null
        pendingRetry = true
        if (question != null && retry) {
            submit(question)
        } else {
            binding.txtAnswer.text =
                "Back from settings. If you allowed it, say the command again; " +
                    "earlier steps will not be repeated."
        }
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
     * and answers immediately, even offline. Anything unrecognised goes to
     * [routeBeyondDevice]: laptop command, then AI, then the n8n archive exactly as it
     * always did, so the archived-question behaviour is unchanged.
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
                requestDevicePermission(
                    outcome.permission, outcome.why, question,
                    retryable = outcome.retryable, progress = outcome.progress,
                )
            is DeviceActions.Result.NeedsSettings ->
                openRequiredSetting(
                    outcome.intent, outcome.why, question,
                    retryable = outcome.retryable, progress = outcome.progress,
                )
            is DeviceActions.Result.Open -> {
                present(outcome.message)
                try {
                    startActivity(outcome.intent)
                } catch (t: Throwable) {
                    // Screen missing (should not happen) - the message still explains it.
                }
            }
            is DeviceActions.Result.NeedInfo -> {
                settingsShownOnce = false
                present(outcome.message)
            }
            DeviceActions.Result.NotHandled -> routeBeyondDevice(question)
        }
    }

    private fun present(message: String) {
        binding.btnAsk.isEnabled = true
        binding.txtAnswer.text = message
        speak(message)
    }

    private fun requestDevicePermission(
        permission: String,
        why: String,
        question: String,
        retryable: Boolean,
        progress: String,
    ) {
        val shown = if (progress.isBlank()) why else "$progress\n\n$why"
        binding.txtAnswer.text = shown
        speak(why)
        if (deniedPermissions.contains(permission)) {
            binding.txtAnswer.text = buildString {
                append(shown)
                append("\n\nIt was denied earlier. Allow it in ")
                append("Settings > Apps > JARVIS > Permissions, then ask again.")
            }
            return
        }
        pendingQuestion = if (retryable) question else null
        pendingRetry = retryable
        try {
            devicePermissionLauncher.launch(arrayOf(permission))
        } catch (t: Throwable) {
            pendingQuestion = null
            pendingRetry = true
            binding.txtAnswer.text = shown
        }
    }

    private fun openRequiredSetting(
        intent: Intent,
        why: String,
        question: String,
        retryable: Boolean,
        progress: String,
    ) {
        val shown = if (progress.isBlank()) why else "$progress\n\n$why"
        binding.txtAnswer.text = shown
        speak(why)
        if (settingsShownOnce) {
            // The user came back from that screen and it is still not granted.
            // Say so instead of leaving a stale "opening..." message.
            settingsShownOnce = false
            binding.txtAnswer.text = buildString {
                append(shown)
                append("\n\nStill not allowed. Say \"set up access\" for a guided list, ")
                append("or open it yourself: Settings > Apps > JARVIS.")
            }
            return
        }
        settingsShownOnce = true
        pendingQuestion = if (retryable) question else null
        pendingRetry = retryable
        try {
            deviceSettingsLauncher.launch(intent)
        } catch (t: Throwable) {
            settingsShownOnce = false
            pendingQuestion = null
            pendingRetry = true
            binding.txtAnswer.text = shown
        }
    }

    private fun askBackend(question: String, prefix: String = "") {
        settingsShownOnce = false
        binding.btnAsk.isEnabled = false
        binding.txtAnswer.text = if (prefix.isBlank()) "Thinking..." else prefix + "Thinking..."

        lifecycleScope.launch {
            val payload = JSONObject().apply { put("question", question) }.toString()
            val result = withContext(Dispatchers.IO) {
                ApiClient.shared.postJson(prefs.askUrl(), payload, prefs.headers())
            }
            binding.btnAsk.isEnabled = true
            if (result.ok) {
                val answer = extractAnswer(result.body)
                binding.txtAnswer.text = if (prefix.isBlank()) answer else prefix + answer
                speak(answer)
            } else {
                binding.txtAnswer.text = buildString {
                    if (prefix.isNotBlank()) append(prefix)
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

    // ------------------------------------------------------- laptop and AI

    /**
     * Everything the phone itself cannot do.
     *
     * A sentence addressed to the computer is never quietly sent to a cloud
     * model: the explicit "on my laptop" phrasing is what routes it, so the two
     * destinations can never be confused.
     */
    private fun routeBeyondDevice(question: String) {
        val laptopPart = LaptopCommand.stripPrefix(question)
        if (laptopPart != null) {
            runLaptop(laptopPart)
            return
        }
        answerQuestion(question)
    }

    private fun runLaptop(inner: String) {
        val parsed = LaptopCommand.parse(inner)
        if (parsed.name.isBlank()) {
            present("I heard a laptop command but it was empty. Try \"on my laptop notepad\".")
            return
        }
        if (!prefs.laptopEnabled) {
            present(
                "Laptop control is switched off.\n\n" +
                    "Open Automations > Laptop Control, enter the laptop's address and the " +
                    "token from config.json, then turn it on. Nothing was sent.",
            )
            return
        }
        if (!prefs.laptopConfigured()) {
            present(
                "Laptop control needs the laptop's address and token.\n\n" +
                    "Open Automations > Laptop Control and tap TEST. Nothing was sent.",
            )
            return
        }
        if (prefs.laptopAskBeforeRun) {
            confirmLaptop(parsed) { executeLaptop(parsed) }
        } else {
            executeLaptop(parsed)
        }
    }

    private fun confirmLaptop(parsed: LaptopCommand.Parsed, onApprove: () -> Unit) {
        val message = buildString {
            appendLine("This will run on your laptop:")
            appendLine()
            appendLine("  " + LaptopCommand.format(parsed))
            if (LaptopCommand.isDangerous(parsed)) {
                appendLine()
                append("WARNING: this looks destructive. Read it again before approving.")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Approve this laptop command?")
            .setMessage(message.trim())
            .setPositiveButton("Approve & run") { _, _ -> onApprove() }
            .setNegativeButton("Cancel") { _, _ ->
                binding.txtAnswer.text = "Cancelled. Nothing was sent to the laptop."
            }
            .setCancelable(true)
            .show()
    }

    private fun executeLaptop(parsed: LaptopCommand.Parsed) {
        if (laptopBusy) return
        laptopBusy = true
        binding.btnAsk.isEnabled = false
        binding.txtAnswer.text = buildString {
            appendLine("Running on the laptop:")
            appendLine()
            append(LaptopCommand.format(parsed))
        }

        val client = LaptopClient(this)
        lifecycleScope.launch {
            val run = withContext(Dispatchers.IO) { client.execute(parsed) }
            laptopBusy = false
            binding.btnAsk.isEnabled = true
            LaptopHistoryStore(this@AskActivity).add(LaptopHistoryStore.from(run))
            binding.txtAnswer.text = buildString {
                appendLine(run.summary())
                appendLine()
                append("request " + run.requestId.take(8))
            }
            speak(if (run.ok) "Done on the laptop." else "The laptop command did not succeed.")
        }
    }

    /**
     * Asks the user's LLM. When it fails, the archive is still tried, and the
     * final text says both what failed and where the answer came from.
     */
    private fun answerQuestion(question: String) {
        if (!prefs.aiConfigured()) {
            askBackend(question)
            return
        }
        val client = AiClient(this)
        binding.btnAsk.isEnabled = false
        binding.txtAnswer.text = "Asking ${prefs.aiModel} ..."

        lifecycleScope.launch {
            val messages = aiHistory.toList() + AiClient.Message("user", question)
            val answer = withContext(Dispatchers.IO) { client.chat(messages) }
            if (answer.ok) {
                binding.btnAsk.isEnabled = true
                rememberTurn(question, answer.text)
                prefs.aiLastOkAt = System.currentTimeMillis()
                binding.txtAnswer.text = buildString {
                    appendLine(answer.text)
                    appendLine()
                    append("— " + answer.sourceLine())
                }
                speak(answer.text)
            } else {
                askBackend(
                    question,
                    prefix = "AI could not answer.\n${answer.error}\n\n" +
                        "Trying the n8n archive instead.\n\n",
                )
            }
        }
    }

    private fun rememberTurn(question: String, answer: String) {
        aiHistory.addLast(AiClient.Message("user", question))
        aiHistory.addLast(AiClient.Message("assistant", answer.take(2000)))
        while (aiHistory.size > AiClient.MAX_TURNS * 2) aiHistory.removeFirst()
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
