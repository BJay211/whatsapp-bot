package com.bonjee.notifier

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.ai.AiClient
import com.bonjee.notifier.databinding.ActivityAskBinding
import com.bonjee.notifier.device.DeviceActions
import com.bonjee.notifier.friday.FridayClient
import com.bonjee.notifier.laptop.LaptopClient
import com.bonjee.notifier.laptop.LaptopCommand
import com.bonjee.notifier.laptop.LaptopHistoryStore
import com.bonjee.notifier.memory.ConversationStore
import com.bonjee.notifier.ui.VoiceSettingsActivity
import com.bonjee.notifier.voice.JarvisWakeService
import com.bonjee.notifier.voice.SpeechOutput
import com.bonjee.notifier.voice.VoiceConversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * JARVIS: a continuous voice conversation, not a one-shot search box.
 *
 * Order for every sentence:
 *  1. DeviceActions - phone commands, answered offline;
 *  2. Friday, when the sentence is addressed to it ("friday ...") or clearly
 *     names the laptop, and Friday is paired;
 *  3. the older laptop agent ("on my laptop ..."), kept for compatibility;
 *  4. the user's own AI provider (streamed sentence by sentence to speech);
 *  5. the n8n archive, which is also the fallback when AI is off or fails.
 *
 * Every result is displayed and, unless muted, spoken. Conversation context is
 * kept for follow-ups and across restarts.
 */
class AskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAskBinding
    private lateinit var prefs: Prefs
    private lateinit var store: ConversationStore
    private lateinit var speech: SpeechOutput
    private lateinit var voice: VoiceConversation

    private val main = Handler(Looper.getMainLooper())

    private var fromAssistant = false
    private var voiceMode = true
    private var holdListening = false
    private var streamedAny = false
    private var speakingStarted = false
    private var lastAssistant = ""

    /** Sentence waiting for a permission/settings round-trip, so it can be retried. */
    private var pendingQuestion: String? = null
    private var pendingRetry = true
    private var settingsShownOnce = false
    private val deniedPermissions = mutableSetOf<String>()

    /** One laptop command at a time, so a double tap cannot send twice. */
    private var laptopBusy = false
    private var fridayBusy = false
    private var fridayTaskId: String? = null
    private val fridayAskedApprovals = mutableSetOf<String>()

    // ------------------------------------------------------------- launchers

    private val devicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val question = pendingQuestion
        val retry = pendingRetry
        pendingQuestion = null
        pendingRetry = true
        if (question == null) {
            if (grants.isNotEmpty() && grants.values.all { it }) {
                reply("Permission granted. Say the command again and I will continue from the start.")
            }
            return@registerForActivityResult
        }
        val granted = grants.isNotEmpty() && grants.values.all { it }
        if (!granted) {
            deniedPermissions.addAll(grants.keys)
            reply(
                "Permission denied.\n\nOpen Settings > Apps > JARVIS > Permissions, " +
                    "allow it, then ask again.",
                speakText = "Permission denied. Allow it in Settings, then ask again.",
            )
            return@registerForActivityResult
        }
        if (retry) submit(question) else reply(
            "Permission granted. Earlier steps already ran, so say the command again - " +
                "I won't repeat them by myself.",
        )
    }

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
            reply(
                "Back from settings. If you allowed it, say the command again; " +
                    "earlier steps will not be repeated.",
            )
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            reply(
                "Microphone permission denied. Allow it in Settings > Apps > JARVIS > " +
                    "Permissions, or type below.",
                speakText = "Microphone permission denied. You can type instead.",
            )
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        store = ConversationStore(this)
        fromAssistant = intent.getBooleanExtra(EXTRA_FROM_ASSISTANT, false)
        voiceMode = prefs.voiceAutoSpeak && !prefs.voiceMuted
        allowOverKeyguard()
        setupAnimations()
        setupSpeech()
        setupVoice()
        setupControls()
        restoreTranscript()
        updateMuteLabel()

        val passedQuery = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        val wakeText = intent.getStringExtra(EXTRA_WAKE_TEXT)?.trim().orEmpty()
        val startListening = intent.getBooleanExtra(EXTRA_START_LISTENING, false) || fromAssistant

        when {
            wakeText.isNotEmpty() -> handleUserText(wakeText)
            passedQuery.isNotEmpty() -> handleUserText(passedQuery)
            startListening -> greetAndListen()
            else -> setState(VoiceConversation.State.IDLE, "Ready")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val query = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        val wake = intent.getStringExtra(EXTRA_WAKE_TEXT)?.trim().orEmpty()
        when {
            wake.isNotEmpty() -> handleUserText(wake)
            query.isNotEmpty() -> handleUserText(query)
            intent.getBooleanExtra(EXTRA_START_LISTENING, false) ||
                intent.getBooleanExtra(EXTRA_FROM_ASSISTANT, false) -> greetAndListen()
        }
    }

    override fun onResume() {
        super.onResume()
        // The activity owns the microphone while it is visible. The foreground
        // wake listener resumes in onStop.
        JarvisWakeService.stop(this)
        updateMuteLabel()
    }

    override fun onStop() {
        super.onStop()
        voice.stop()
        speech.stop()
        if (!isFinishing && prefs.wakeWordEnabled && prefs.voiceAutoSpeak && !prefs.voiceMuted) {
            JarvisWakeService.start(this)
        }
    }

    override fun onDestroy() {
        voice.destroy()
        speech.shutdown()
        super.onDestroy()
    }

    private fun allowOverKeyguard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        if (keyguard.isKeyguardLocked) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else if (fromAssistant) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    // -------------------------------------------------------------------- UI

    private fun setupAnimations() {
        val pulse = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse)
        binding.coreHalo.startAnimation(pulse)
        binding.coreRing.startAnimation(
            android.view.animation.AnimationUtils.loadAnimation(this, R.anim.rotate),
        )
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnMic.setOnClickListener { toggleMicrophone() }
        binding.btnAsk.setOnClickListener { sendTyped() }
        binding.editQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else false
        }
        binding.btnStop.setOnClickListener { stopEverything() }
        binding.btnRepeat.setOnClickListener { repeatLast() }
        binding.btnMute.setOnClickListener { toggleMute() }
        binding.btnNew.setOnClickListener { confirmNewConversation() }
        binding.btnVoiceSettings.setOnClickListener {
            startActivity(Intent(this, VoiceSettingsActivity::class.java))
        }
        binding.btnCancel.setOnClickListener { cancelFridayTask() }
        binding.conversationScroll.setOnClickListener {
            if (speech.isSpeaking()) speech.stop()
        }
    }

    private fun restoreTranscript() {
        val turns = store.turns().takeLast(ConversationStore.MAX_TURNS * 2)
        if (turns.isEmpty()) {
            appendBubble(
                "system",
                "Say “Hey JARVIS” if wake listening is on. Otherwise tap the microphone " +
                    "or type. After an answer I keep listening, so you can just continue.",
            )
            return
        }
        turns.forEach { appendBubble(it.role, it.text, persist = false) }
        lastAssistant = turns.lastOrNull { it.role == "assistant" }?.text.orEmpty()
    }

    private fun sendTyped() {
        val question = binding.editQuestion.text?.toString()?.trim().orEmpty()
        if (question.isEmpty()) {
            Toast.makeText(this, "Type a question or command first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.editQuestion.setText("")
        hideKeyboard()
        handleUserText(question)
    }

    private fun handleUserText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        appendBubble("user", clean)
        store.add("user", clean)
        submit(clean)
    }

    private fun greetAndListen() {
        if (!prefs.voiceAutoSpeak || prefs.voiceMuted) {
            startListening()
            return
        }
        if (speech.isReady()) {
            setState(VoiceConversation.State.SPEAKING, "Greeting")
            speech.speak("Yes boss, I'm listening.")
        } else {
            startListening()
        }
    }

    private fun toggleMicrophone() {
        if (speech.isSpeaking()) {
            speech.stop()
            startListening()
            return
        }
        if (voice.isListening()) {
            voice.stop()
            setState(VoiceConversation.State.IDLE, "Microphone paused")
            return
        }
        startListening()
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!voice.isAvailable()) {
            reply(
                "No speech recognition service is available on this phone. Install or enable " +
                    "Google speech services, or type below.",
                speakText = "Speech recognition is not available. You can type instead.",
            )
            return
        }
        val seconds = if (prefs.voiceContinuous) prefs.voiceFollowUpSeconds else 0
        voice.startListening(seconds)
    }

    private fun stopEverything() {
        holdListening = false
        voice.stop()
        speech.stop()
        setState(VoiceConversation.State.IDLE, "Stopped")
        live("Stopped. Nothing is being listened to or spoken.")
        main.postDelayed({ live("") }, 1800)
    }

    private fun repeatLast() {
        val text = lastAssistant
        if (text.isBlank()) {
            live("There is nothing to repeat yet.")
            return
        }
        voice.stop()
        speech.speak(text)
    }

    private fun toggleMute() {
        prefs.voiceMuted = !prefs.voiceMuted
        voiceMode = prefs.voiceAutoSpeak && !prefs.voiceMuted
        if (prefs.voiceMuted) speech.stop()
        updateMuteLabel()
        live(if (prefs.voiceMuted) "Spoken replies off. The conversation continues in text." else "Spoken replies on.")
    }

    private fun updateMuteLabel() {
        binding.btnMute.text = if (prefs.voiceMuted) "UNMUTE" else "MUTE"
    }

    private fun confirmNewConversation() {
        AlertDialog.Builder(this)
            .setTitle("Start a new conversation?")
            .setMessage("The recent context on this phone is cleared; the transcript file on the laptop is untouched.")
            .setPositiveButton("Clear") { _, _ ->
                store.clear()
                binding.conversationList.removeAllViews()
                lastAssistant = ""
                appendBubble("system", "New conversation. Ask me anything.")
                live("")
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.editQuestion.windowToken, 0)
        } catch (t: Throwable) {
        }
    }

    // ------------------------------------------------------------------ speech

    private fun setupSpeech() {
        speech = SpeechOutput(this, object : SpeechOutput.Listener {
            override fun onReady(available: Boolean, detail: String) {
                if (!available) {
                    live(detail)
                    return
                }
                setState(VoiceConversation.State.IDLE, "Ready")
            }

            override fun onStart(text: String) {
                speakingStarted = true
                setState(VoiceConversation.State.SPEAKING, "Speaking")
                if (prefs.voiceContinuous && prefs.voiceBargeIn) voice.startBargeIn(text)
            }

            override fun onDone() {
                speakingStarted = false
                voice.stopBargeIn()
                if (holdListening) {
                    setState(VoiceConversation.State.IDLE, "Working on the laptop")
                    return
                }
                if (prefs.voiceContinuous && prefs.voiceAutoSpeak && !prefs.voiceMuted) {
                    voice.armFollowUp(prefs.voiceFollowUpSeconds)
                } else {
                    setState(VoiceConversation.State.IDLE, "Ready")
                }
            }

            override fun onStopped() {
                speakingStarted = false
                voice.stopBargeIn()
                setState(VoiceConversation.State.IDLE, "Stopped speaking")
            }

            override fun onError(message: String) {
                speakingStarted = false
                voice.stopBargeIn()
                live("Voice: $message")
                setState(VoiceConversation.State.IDLE, "Voice unavailable")
            }

            override fun onVoicesChanged(voices: List<SpeechOutput.VoiceInfo>) = Unit
        })
        speech.initialize()
    }

    private fun setupVoice() {
        voice = VoiceConversation(this, object : VoiceConversation.Listener {
            override fun onState(state: VoiceConversation.State, detail: String) {
                setState(state, detail)
            }

            override fun onHeard(text: String) {
                if (text.isBlank()) return
                holdListening = false
                appendBubble("user", text)
                store.add("user", text)
                submit(text)
            }

            override fun onBargeIn() {
                speech.stop()
                live("Interrupted. Listening…")
                setState(VoiceConversation.State.LISTENING, "Listening")
                voice.armFollowUp(prefs.voiceFollowUpSeconds)
            }

            override fun onRms(level: Float) {
                if (binding.txtStatus.text == "LISTENING") {
                    val scale = 1f + (level * 0.06f)
                    binding.coreContainer.scaleX = scale
                    binding.coreContainer.scaleY = scale
                }
            }

            override fun onError(message: String) {
                live(message)
            }

            override fun onPermissionMissing() {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        })
    }

    private fun setState(state: VoiceConversation.State, detail: String) {
        if (isFinishing || isDestroyed) return
        val (label, color) = when (state) {
            VoiceConversation.State.LISTENING -> "LISTENING" to R.color.cyan
            VoiceConversation.State.THINKING -> "THINKING" to R.color.amber
            VoiceConversation.State.SPEAKING -> "SPEAKING" to R.color.green
            VoiceConversation.State.IDLE -> "STANDBY" to R.color.text_dim
        }
        binding.txtStatus.text = label
        binding.txtStatus.setTextColor(ContextCompat.getColor(this, color))
        binding.txtCoreLabel.text = when (state) {
            VoiceConversation.State.LISTENING -> "LISTEN"
            VoiceConversation.State.THINKING -> "THINK"
            VoiceConversation.State.SPEAKING -> "SPEAK"
            VoiceConversation.State.IDLE -> "JARVIS"
        }
        val mic = when (state) {
            VoiceConversation.State.LISTENING ->
                if (prefs.voiceFollowUpSeconds > 0 && prefs.voiceContinuous) {
                    "MIC LIVE · ${detail} · say “JARVIS, stop” to interrupt"
                } else "MIC LIVE · speak now"
            VoiceConversation.State.THINKING -> "PROCESSING · ${detail}"
            VoiceConversation.State.SPEAKING -> "JARVIS IS SPEAKING · say “stop” or tap STOP"
            VoiceConversation.State.IDLE ->
                if (prefs.voiceMuted) "MUTED · TYPE OR TAP MIC"
                else "TAP TO SPEAK OR TYPE · “HEY JARVIS” ${if (prefs.wakeWordEnabled) "ON" else "OFF"}"
        }
        binding.txtMicState.text = mic
        if (state != VoiceConversation.State.LISTENING) {
            binding.coreContainer.scaleX = 1f
            binding.coreContainer.scaleY = 1f
        }
        binding.coreHalo.alpha = when (state) {
            VoiceConversation.State.LISTENING -> 0.75f
            VoiceConversation.State.THINKING -> 0.5f
            VoiceConversation.State.SPEAKING -> 0.65f
            VoiceConversation.State.IDLE -> 0.3f
        }
    }

    private fun live(text: String) {
        binding.txtLive.text = text
        binding.txtLive.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun appendBubble(role: String, text: String, persist: Boolean = true) {
        if (text.isBlank()) return
        val user = role == "user"
        val system = role == "system"
        val view = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            textSize = if (system) 10.5f else 13.5f
            setTextColor(
                ContextCompat.getColor(
                    this@AskActivity,
                    when {
                        system -> R.color.text_dim
                        user -> R.color.cyan_bright
                        else -> R.color.text_primary
                    },
                ),
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(if (system) 8 else 16).toFloat()
                setColor(
                    when {
                        system -> Color.TRANSPARENT
                        user -> 0xFF00293A.toInt()
                        else -> 0xFF000D1A.toInt()
                    },
                )
                if (!system) {
                    setStroke(
                        dp(1),
                        when {
                            user -> 0x6600E5FF
                            else -> 0x331A237E
                        },
                    )
                }
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(6)
            if (!system) {
                if (user) {
                    marginStart = dp(42)
                    gravity = Gravity.END
                } else {
                    marginEnd = dp(30)
                    gravity = Gravity.START
                }
            }
        }
        binding.conversationList.addView(view, params)
        binding.conversationScroll.post {
            binding.conversationScroll.fullScroll(View.FOCUS_DOWN)
        }
        if (persist && !system) store.add(if (user) "user" else "assistant", text)
        if (!user && !system) lastAssistant = text
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- routing

    private fun submit(question: String) {
        val outcome = try {
            DeviceActions.handle(this, question)
        } catch (t: Throwable) {
            reply(
                "I couldn't run that (${t.javaClass.simpleName}). " +
                    "Say \"set up access\" to check my permissions, or ask me a question.",
            )
            return
        }
        when (outcome) {
            is DeviceActions.Result.Done -> {
                settingsShownOnce = false
                reply(outcome.message)
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
                reply(outcome.message)
                try {
                    startActivity(outcome.intent)
                } catch (t: Throwable) {
                    // The message already explains the failure.
                }
            }
            is DeviceActions.Result.NeedInfo -> {
                settingsShownOnce = false
                reply(outcome.message)
            }
            DeviceActions.Result.NotHandled -> routeBeyondDevice(question)
        }
    }

    /**
     * Shows and speaks a final answer. [speakText] lets the screen include a
     * source line or task detail that should not be read aloud.
     */
    private fun reply(
        display: String,
        speakText: String = display,
        speakNow: Boolean = true,
    ) {
        appendBubble("assistant", display)
        lastAssistant = speakText.ifBlank { display }
        live("")
        val shouldSpeak = speakNow && prefs.voiceAutoSpeak && !prefs.voiceMuted &&
            speakText.isNotBlank()
        if (shouldSpeak) {
            holdListening = false
            speech.speak(speakText)
        } else {
            setState(VoiceConversation.State.IDLE, "Ready")
            if (prefs.voiceContinuous && prefs.voiceAutoSpeak && !prefs.voiceMuted && voiceMode) {
                voice.armFollowUp(prefs.voiceFollowUpSeconds)
            }
        }
    }

    private fun requestDevicePermission(
        permission: String,
        why: String,
        question: String,
        retryable: Boolean,
        progress: String,
    ) {
        val shown = if (progress.isBlank()) why else "$progress\n\n$why"
        reply(shown, speakText = why)
        if (deniedPermissions.contains(permission)) {
            reply(
                "$shown\n\nIt was denied earlier. Allow it in Settings > Apps > JARVIS > " +
                    "Permissions, then ask again.",
                speakText = "",
            )
            return
        }
        pendingQuestion = if (retryable) question else null
        pendingRetry = retryable
        try {
            devicePermissionLauncher.launch(arrayOf(permission))
        } catch (t: Throwable) {
            pendingQuestion = null
            pendingRetry = true
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
        reply(shown, speakText = why)
        if (settingsShownOnce) {
            settingsShownOnce = false
            reply(
                "$shown\n\nStill not allowed. Say \"set up access\" for a guided list, or open " +
                    "it yourself: Settings > Apps > JARVIS.",
                speakText = "It is still not allowed. Check the settings, then ask again.",
            )
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
        }
    }

    private fun askBackend(question: String, prefix: String = "") {
        live("Asking the archive…")
        setState(VoiceConversation.State.THINKING, "Archive")
        lifecycleScope.launch {
            val payload = JSONObject().apply { put("question", question) }.toString()
            val result = withContext(Dispatchers.IO) {
                ApiClient.shared.postJson(prefs.askUrl(), payload, prefs.headers())
            }
            if (result.ok) {
                val answer = extractAnswer(result.body)
                reply(prefix + answer, speakText = answer)
            } else {
                reply(
                    buildString {
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
                    },
                    speakText = "I could not reach the assistant. Device commands still work offline.",
                )
            }
        }
    }

    // ------------------------------------------------------- laptop and AI

    private fun routeBeyondDevice(question: String) {
        val fridayPart = fridayInstruction(question)
        if (fridayPart != null) {
            runFriday(fridayPart)
            return
        }
        val laptopPart = LaptopCommand.stripPrefix(question)
        if (laptopPart != null) {
            val oldAgentReady = prefs.laptopEnabled && prefs.laptopConfigured()
            if (!oldAgentReady && prefs.fridayEnabled && prefs.fridayConfigured()) {
                runFriday(laptopPart)
            } else {
                runLaptop(laptopPart)
            }
            return
        }
        answerQuestion(question)
    }

    // ---------------------------------------------------------------- Friday

    private fun fridayInstruction(question: String): String? {
        val text = question.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase(Locale.getDefault())

        val prefix = prefs.fridayInvokePrefix.trim().lowercase(Locale.getDefault())
        if (prefix.isNotEmpty()) {
            for (form in listOf(prefix, "hey $prefix", "ok $prefix", "okay $prefix")) {
                if (lower == form) return ""
                if (lower.startsWith("$form ") || lower.startsWith("$form,") ||
                    lower.startsWith("$form:") || lower.startsWith("$form-")
                ) {
                    return text.substring(form.length)
                        .trimStart(' ', ',', ':', '-', '.')
                        .trim()
                }
            }
        }

        if (!prefs.fridayConfigured()) return null
        val namesLaptop = lower.contains("laptop") || lower.contains("computer") ||
            lower.contains("pc ") || lower.endsWith(" pc") || lower.contains("desktop")
        if (!namesLaptop) return null

        val wantsSomething = LAPTOP_ACTION_WORDS.any { lower.contains(it) }
        return if (wantsSomething) text else null
    }

    private fun runFriday(instruction: String) {
        if (instruction.isBlank()) {
            reply("Yes? Tell me what to do on the laptop, for example \"Friday, open Spotify\".")
            return
        }
        if (!prefs.fridayEnabled) {
            reply(
                "Friday is switched off on this phone.\n\n" +
                    "Open Friday in the app's settings, pair with the laptop and turn it on. " +
                    "Nothing was sent.",
                speakText = "Friday is switched off on this phone. Nothing was sent.",
            )
            return
        }
        if (!prefs.fridayConfigured()) {
            reply(
                "This phone is not paired with Friday yet.\n\n" +
                    "Open Friday > remote pairing on the laptop, generate a code, " +
                    "then enter it here. Nothing was sent.",
                speakText = "This phone is not paired with Friday. Nothing was sent.",
            )
            return
        }
        if (fridayBusy) {
            reply("I am already working on something on the laptop. Wait for it, or press Cancel.")
            return
        }

        fridayBusy = true
        fridayTaskId = null
        holdListening = true
        binding.btnAsk.isEnabled = false
        binding.btnCancel.isEnabled = true
        binding.btnCancel.visibility = View.VISIBLE
        live("Sending to Friday…\n$instruction")
        setState(VoiceConversation.State.THINKING, "Friday")
        if (prefs.voiceAutoSpeak && !prefs.voiceMuted) speech.speak("Working on the laptop.")

        val requestId = "android-" + UUID.randomUUID()
        lifecycleScope.launch {
            try {
                val client = FridayClient(this@AskActivity)
                val sent = withContext(Dispatchers.IO) { client.command(instruction, requestId) }
                val task = sent.value
                if (!sent.ok || task == null) {
                    reply(
                        sent.message.ifBlank { "Friday did not accept that instruction." },
                        speakText = "I could not reach Friday.",
                    )
                    return@launch
                }
                prefs.fridayLastOkAt = System.currentTimeMillis()
                watchFriday(client, task)
            } finally {
                fridayBusy = false
                fridayTaskId = null
                holdListening = false
                if (!isFinishing && !isDestroyed) {
                    binding.btnAsk.isEnabled = true
                    binding.btnCancel.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun watchFriday(client: FridayClient, initial: FridayClient.Task) {
        var task = initial
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + 15 * 60 * 1000

        while (true) {
            if (isFinishing || isDestroyed) return
            fridayTaskId = task.taskId
            live(fridayProgressText(task))
            setState(VoiceConversation.State.THINKING, task.status)

            val approval = task.approval
            if (task.awaitingApproval && approval != null &&
                fridayAskedApprovals.add(approval.id)
            ) {
                val answer = askFridayApproval(approval)
                if (answer != null) {
                    val (id, approved) = answer
                    val result = withContext(Dispatchers.IO) {
                        client.approve(id, approved, remember = false)
                    }
                    if (!result.ok) {
                        live(result.message + "\nThe task is still waiting on the laptop.")
                    }
                }
            }

            if (task.isTerminal) {
                val spoken = when (task.status) {
                    FridayClient.STATUS_SUCCEEDED -> "Done on the laptop."
                    FridayClient.STATUS_CANCELLED -> "Cancelled on the laptop."
                    else -> "The laptop could not finish that."
                }
                reply(fridayProgressText(task) + "\n\n" + task.display(), speakText = spoken)
                return
            }

            if (System.currentTimeMillis() > deadline) {
                reply(
                    fridayProgressText(task) +
                        "\n\nI stopped watching after 15 minutes. The task may still be running " +
                        "on the laptop - check Friday's window there.",
                    speakText = "I stopped watching the laptop task after fifteen minutes.",
                )
                return
            }

            val elapsed = System.currentTimeMillis() - startedAt
            delay(if (elapsed < 10_000) 300 else 800)
            val next = withContext(Dispatchers.IO) { client.task(task.taskId) }
            if (!next.ok || next.value == null) {
                reply(
                    "Lost contact with Friday while it was working.\n\n${next.message}\n\n" +
                        "The task may still be running on the laptop. It was not cancelled, " +
                        "and it will not be sent twice.",
                    speakText = "I lost contact with the laptop.",
                )
                return
            }
            task = next.value
        }
    }

    private fun fridayProgressText(task: FridayClient.Task): String = buildString {
        append("On the laptop")
        if (task.instruction.isNotBlank()) append(": ${task.instruction}")
        append("\nStatus: ${fridayStatusLabel(task.status)}")
        val detail = when (task.status) {
            FridayClient.STATUS_SUCCEEDED, FridayClient.STATUS_FAILED, FridayClient.STATUS_CANCELLED ->
                task.display()
            else -> task.progress.ifBlank { task.summary }
        }
        if (detail.isNotBlank()) append(" · $detail")
        if (task.status == FridayClient.STATUS_AWAITING) {
            append(" · approve below or on the laptop")
        }
    }

    private fun fridayStatusLabel(status: String): String = when (status) {
        FridayClient.STATUS_RECEIVED -> "received"
        FridayClient.STATUS_VALIDATING -> "checking"
        FridayClient.STATUS_AWAITING -> "waiting for approval"
        FridayClient.STATUS_RUNNING -> "working"
        FridayClient.STATUS_SUCCEEDED -> "done"
        FridayClient.STATUS_FAILED -> "failed"
        FridayClient.STATUS_CANCELLED -> "cancelled"
        else -> status.ifBlank { "unknown" }
    }

    private suspend fun askFridayApproval(
        approval: FridayClient.Approval,
    ): Pair<String, Boolean>? {
        val deferred = CompletableDeferred<Pair<String, Boolean>?>()
        val message = buildString {
            appendLine(approval.detail.ifBlank { approval.title })
            appendLine()
            append("Capability: ${approval.capability.ifBlank { "unknown" }}")
            if (approval.destructive) {
                appendLine()
                appendLine()
                append("WARNING: Friday marked this as destructive. Read it before approving.")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (approval.destructive) "Approve a destructive action?"
                else "Approve this on the laptop?",
            )
            .setMessage(message.trim())
            .setPositiveButton("Approve") { _, _ -> deferred.complete(approval.id to true) }
            .setNegativeButton("Deny") { _, _ -> deferred.complete(approval.id to false) }
            .setOnCancelListener { deferred.complete(null) }
            .show()
        return deferred.await()
    }

    private fun cancelFridayTask() {
        val id = fridayTaskId
        if (id.isNullOrBlank()) {
            live("Nothing is running on the laptop.")
            return
        }
        binding.btnCancel.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FridayClient(this@AskActivity).cancel(id)
            }
            binding.btnCancel.isEnabled = true
            live(result.message.ifBlank { "Cancel requested." } + " · task " + id.take(8))
            if (result.ok) speech.speak("Cancelled.")
        }
    }

    private fun runLaptop(inner: String) {
        val parsed = LaptopCommand.parse(inner)
        if (parsed.name.isBlank()) {
            reply("I heard a laptop command but it was empty. Try \"on my laptop notepad\".")
            return
        }
        if (!prefs.laptopEnabled) {
            reply(
                "Laptop control is switched off.\n\nOpen Automations > Laptop Control, enter " +
                    "the laptop's address and the token from config.json, then turn it on. " +
                    "Nothing was sent.",
            )
            return
        }
        if (!prefs.laptopConfigured()) {
            reply(
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
                reply("Cancelled. Nothing was sent to the laptop.")
            }
            .setCancelable(true)
            .show()
    }

    private fun executeLaptop(parsed: LaptopCommand.Parsed) {
        if (laptopBusy) return
        laptopBusy = true
        binding.btnAsk.isEnabled = false
        live("Running on the laptop…")
        setState(VoiceConversation.State.THINKING, "Laptop")
        val client = LaptopClient(this)
        lifecycleScope.launch {
            val run = withContext(Dispatchers.IO) { client.execute(parsed) }
            laptopBusy = false
            binding.btnAsk.isEnabled = true
            LaptopHistoryStore(this@AskActivity).add(LaptopHistoryStore.from(run))
            reply(
                run.summary() + "\n\nrequest " + run.requestId.take(8),
                speakText = if (run.ok) "Done on the laptop." else "The laptop command did not succeed.",
            )
        }
    }

    /**
     * Streams the model answer into speech sentence by sentence. The complete
     * text is still shown and stored for follow-ups.
     */
    private fun answerQuestion(question: String) {
        if (!prefs.aiConfigured()) {
            askBackend(question)
            return
        }
        val client = AiClient(this)
        binding.btnAsk.isEnabled = false
        setState(VoiceConversation.State.THINKING, "Thinking")
        live("Thinking with ${prefs.aiModel}…")

        lifecycleScope.launch {
            val history = store.messages().ifEmpty {
                listOf(AiClient.Message("user", question))
            }
            streamedAny = false
            val answer = withContext(Dispatchers.IO) {
                client.chatStreaming(history) { sentence ->
                    main.post {
                        if (isFinishing || isDestroyed) return@post
                        if (!prefs.voiceAutoSpeak || prefs.voiceMuted || !speech.isReady()) {
                            return@post
                        }
                        if (!streamedAny) {
                            streamedAny = true
                            speech.startStream()
                        }
                        speech.enqueueSentence(sentence)
                    }
                }
            }

            binding.btnAsk.isEnabled = true
            if (answer.ok) {
                prefs.aiLastOkAt = System.currentTimeMillis()
                val shown = buildString {
                    appendLine(answer.text)
                    appendLine()
                    append("— " + answer.sourceLine())
                }
                appendBubble("assistant", shown, persist = false)
                lastAssistant = answer.text
                store.add("assistant", answer.text)
                live("")
                val alreadySpeaking = streamedAny && answer.streamed && speech.isReady()
                if (alreadySpeaking) {
                    main.post { speech.finishStream() }
                    setState(VoiceConversation.State.SPEAKING, "Speaking")
                } else {
                    reply(answer.text, speakText = answer.text)
                }
            } else if (answer.text.isNotBlank()) {
                // A stream that broke midway: show and keep what was said, then
                // report the failure instead of pretending it completed.
                val shown = buildString {
                    appendLine(answer.text)
                    appendLine()
                    append("(The answer was cut short: ${answer.error})")
                }
                appendBubble("assistant", shown, persist = false)
                lastAssistant = answer.text
                store.add("assistant", answer.text)
                live("Stream interrupted: ${answer.error}")
                if (streamedAny && speech.isReady()) main.post { speech.finishStream() }
                else reply(answer.text, speakText = answer.text)
            } else {
                askBackend(
                    question,
                    prefix = "AI could not answer.\n${answer.error}\n\nTrying the n8n archive instead.\n\n",
                )
            }
        }
    }

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

    companion object {
        const val EXTRA_FROM_ASSISTANT = "bonjee.extra.FROM_ASSISTANT"
        const val EXTRA_START_LISTENING = "bonjee.extra.START_LISTENING"
        const val EXTRA_WAKE_TEXT = "bonjee.extra.WAKE_TEXT"
        const val EXTRA_QUERY = "query"

        private val LAPTOP_ACTION_WORDS = listOf(
            "open", "launch", "start", "run ", "close", "switch to", "focus",
            "play", "pause", "resume", "stop", "next track", "previous track",
            "volume", "mute", "unmute", "brightness", "screen", "screenshot",
            "lock", "sleep", "shut down", "shutdown", "restart", "reboot",
            "battery", "charging", "cpu", "memory", "ram", "disk", "storage",
            "wifi", "wi-fi", "bluetooth", "install", "uninstall", "download",
            "file", "folder", "document", "terminal", "command", "type ",
            "click", "copy", "paste", "delete", "rename", "search", "find ",
            "window", "app ", "notification", "music", "video", "project",
            "build", "test", "status", "how much", "what's", "whats", "what is",
        )

        private val ANSWER_KEYS = listOf("answer", "response", "text", "output", "message")
    }
}
