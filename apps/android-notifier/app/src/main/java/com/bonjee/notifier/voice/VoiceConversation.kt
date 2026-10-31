package com.bonjee.notifier.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.bonjee.notifier.Prefs
import java.util.Locale

/**
 * The microphone half of the conversation.
 *
 * It owns one [SpeechRecognizer] and restarts it after every turn, so the user
 * never has to press the microphone between questions. After a reply the
 * follow-up window stays open for [Prefs.voiceFollowUpSeconds]; every phrase
 * restarts the countdown and silence closes it.
 *
 * While JARVIS is speaking the same recogniser is reused in barge-in mode. It
 * listens for a short stop phrase ("stop", "quiet", "JARVIS, stop"). Results
 * that are just an echo of JARVIS's own sentence are discarded.
 */
class VoiceConversation(context: Context, private val listener: Listener) {

    interface Listener {
        fun onState(state: State, detail: String)
        fun onHeard(text: String)
        fun onBargeIn()
        fun onRms(level: Float)
        fun onError(message: String)
        fun onPermissionMissing()
    }

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private enum class Mode { COMMAND, BARGE_IN }

    private val app = context.applicationContext
    private val prefs = Prefs.get(app)
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var mode = Mode.COMMAND
    private var active = false
    private var followSeconds = 0
    private var followUntil = 0L
    private var spokenText = ""

    fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(app)
    } catch (t: Throwable) {
        false
    }

    fun isListening(): Boolean = active

    /**
     * Opens the microphone. [followUpSeconds] greater than zero keeps it open
     * after a silent turn until that many seconds pass without speech.
     */
    fun startListening(followUpSeconds: Int = 0) {
        if (!isAvailable()) {
            listener.onError(
                "No speech recognition service is available on this phone. " +
                    "Install or enable Google speech services, or type instead.",
            )
            return
        }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onPermissionMissing()
            return
        }

        mode = Mode.COMMAND
        this.followSeconds = followUpSeconds.coerceIn(0, 300)
        followUntil = if (followSeconds > 0) {
            System.currentTimeMillis() + followSeconds * 1000L
        } else {
            0L
        }
        active = true
        beginCommandRecognition()
    }

    /** After a reply: keep the conversation open hands-free. */
    fun armFollowUp(seconds: Int = prefs.voiceFollowUpSeconds) {
        if (!prefs.voiceContinuous || seconds <= 0) {
            stop()
            return
        }
        startListening(seconds)
    }

    /** Stops the microphone while the answer is being generated. */
    fun pauseForThinking() {
        active = false
        cancelRecognizer()
        listener.onState(State.THINKING, "Thinking")
    }

    fun stop() {
        active = false
        mode = Mode.COMMAND
        followUntil = 0L
        cancelRecognizer()
        listener.onState(State.IDLE, "Ready")
    }

    // --------------------------------------------------------------- barge-in

    /** Starts a short listening pass while JARVIS speaks. */
    fun startBargeIn(text: String) {
        if (!prefs.voiceBargeIn || !isAvailable()) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        spokenText = text
        mode = Mode.BARGE_IN
        active = false
        cancelRecognizer()
        val engine = ensureRecognizer() ?: return
        try {
            engine.startListening(recognitionIntent(bargeIn = true))
        } catch (t: Throwable) {
            // Barge-in is a convenience; tapping Stop always works.
        }
    }

    fun stopBargeIn() {
        if (mode != Mode.BARGE_IN) return
        mode = Mode.COMMAND
        cancelRecognizer()
    }

    fun updateSpokenText(text: String) {
        spokenText = text
    }

    // ------------------------------------------------------------- internals

    private fun beginCommandRecognition() {
        val engine = ensureRecognizer() ?: return
        try {
            engine.startListening(recognitionIntent(bargeIn = false))
            listener.onState(State.LISTENING, listeningDetail())
        } catch (t: Throwable) {
            active = false
            listener.onError("Could not start the microphone: ${t.message ?: "unknown error"}")
        }
    }

    private fun listeningDetail(): String {
        if (followSeconds > 0) {
            val left = secondsLeft()
            return if (left > 0) "Listening · ${left}s" else "Listening"
        }
        return "Listening"
    }

    private fun secondsLeft(): Int {
        if (followUntil <= 0) return 0
        return (((followUntil - System.currentTimeMillis()) + 999) / 1000).toInt().coerceAtLeast(0)
    }

    private fun recognitionIntent(bargeIn: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                prefs.voiceLanguage.ifBlank { Locale.getDefault().toLanguageTag() },
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, !bargeIn)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
            if (bargeIn) putExtra(RecognizerIntent.EXTRA_PROMPT, "")
        }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (recognizer != null) return recognizer
        return try {
            SpeechRecognizer.createSpeechRecognizer(app).also {
                it.setRecognitionListener(listenerImpl)
                recognizer = it
            }
        } catch (t: Throwable) {
            listener.onError("Speech recognition could not start: ${t.message ?: "unknown error"}")
            null
        }
    }

    private fun cancelRecognizer() {
        try { recognizer?.cancel() } catch (t: Throwable) { }
    }

    private fun scheduleRestart(delayMs: Long = 350) {
        handler.postDelayed({
            if (mode == Mode.COMMAND && active) beginCommandRecognition()
        }, delayMs)
    }

    private val listenerImpl = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (mode == Mode.COMMAND) listener.onState(State.LISTENING, listeningDetail())
        }

        override fun onBeginningOfSpeech() {
            if (followSeconds > 0 && mode == Mode.COMMAND) {
                followUntil = System.currentTimeMillis() + followSeconds * 1000L
            }
        }

        override fun onRmsChanged(rmsdB: Float) {
            listener.onRms(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (mode == Mode.COMMAND) {
                listener.onState(State.THINKING, "Got it")
            }
        }

        override fun onError(error: Int) {
            if (mode == Mode.BARGE_IN) {
                // Silence while JARVIS speaks is the normal case. Keep it cheap:
                // do not restart until the next utterance starts.
                mode = Mode.COMMAND
                return
            }

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> {
                    if (!active) return
                    if (followUntil > System.currentTimeMillis()) {
                        scheduleRestart(400)
                    } else {
                        active = false
                        listener.onState(
                            State.IDLE,
                            if (followSeconds > 0) "Conversation paused. Tap the microphone." else "Ready",
                        )
                    }
                }

                SpeechRecognizer.ERROR_CLIENT -> Unit

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    cancelRecognizer()
                    if (active) scheduleRestart(900)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    active = false
                    listener.onPermissionMissing()
                }

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> {
                    active = false
                    listener.onError(
                        "Speech recognition needs a network connection on this phone " +
                            "and could not reach it. Try again, or type.",
                    )
                    listener.onState(State.IDLE, "Speech offline")
                }

                SpeechRecognizer.ERROR_AUDIO -> {
                    active = false
                    listener.onError("The microphone could not be read.")
                    listener.onState(State.IDLE, "Microphone error")
                }

                else -> {
                    if (active && followUntil > System.currentTimeMillis()) scheduleRestart(600)
                    else {
                        active = false
                        listener.onState(State.IDLE, "Ready")
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: return
            val text = list.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (text.isEmpty()) {
                if (active && followUntil > System.currentTimeMillis()) scheduleRestart(350)
                return
            }

            if (mode == Mode.BARGE_IN) {
                mode = Mode.COMMAND
                if (SpeechText.looksLikeEcho(text, spokenText)) {
                    // JARVIS heard itself. Resume listening only when it finishes.
                    return
                }
                if (isStopPhrase(text)) {
                    listener.onBargeIn()
                }
                return
            }

            active = false
            listener.onState(State.THINKING, "Thinking")
            listener.onHeard(text)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun isStopPhrase(text: String): Boolean {
        val clean = WakePhrases.normalize(text)
        if (clean.isEmpty()) return false
        if (WakePhrases.matches(clean)) {
            // "JARVIS" alone pauses speech and reopens the conversation.
            return clean.split(' ').size <= 4
        }
        val stopWords = listOf("stop", "quiet", "enough", "cancel", "shut up", "wait")
        return stopWords.any { word ->
            clean == word || clean.startsWith("$word ") || clean.endsWith(" $word") ||
                clean.contains(" $word ")
        }
    }

    fun destroy() {
        stop()
        try { recognizer?.destroy() } catch (t: Throwable) { }
        recognizer = null
        handler.removeCallbacksAndMessages(null)
    }
}
