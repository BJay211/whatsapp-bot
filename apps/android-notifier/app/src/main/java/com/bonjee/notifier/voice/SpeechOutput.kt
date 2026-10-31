package com.bonjee.notifier.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.bonjee.notifier.Prefs
import java.util.Locale

/**
 * Android text-to-speech with the details that make a conversation usable:
 * a selected voice, rate and pitch, audio focus, sentence queueing for streamed
 * answers, a hard stop for barge-in, and an honest error callback.
 *
 * Nothing here claims the phone made a sound. [Listener.onStart] fires only when
 * the platform engine reports that an utterance actually began.
 */
class SpeechOutput(context: Context, private val listener: Listener) {

    interface Listener {
        fun onReady(available: Boolean, detail: String)
        fun onStart(text: String)
        fun onDone()
        fun onStopped()
        fun onError(message: String)
        fun onVoicesChanged(voices: List<VoiceInfo>)
    }

    data class VoiceInfo(
        val name: String,
        val locale: Locale,
        val quality: Int,
        val needsNetwork: Boolean,
    ) {
        fun label(): String {
            val region = locale.displayName.replaceFirstChar { it.uppercase() }
            val net = if (needsNetwork) " · online" else ""
            return "$region · $name$net"
        }
    }

    private val app = context.applicationContext
    private val prefs = Prefs.get(app)
    private val handler = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var counter = 0
    private var lastId = ""
    private var completedId = ""
    private var streaming = false
    private var currentSpoken = ""
    private var pending: String? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            handler.post { stop() }
        }
    }

    fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            handler.post {
                ready = status == TextToSpeech.SUCCESS
                failed = !ready
                if (!ready) {
                    listener.onReady(false, "Android text-to-speech did not start.")
                    return@post
                }
                applySettings()
                val voices = voices()
                listener.onReady(true, describeEngine())
                listener.onVoicesChanged(voices)
                pending?.let { text ->
                    pending = null
                    speak(text)
                }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                handler.post {
                    if (utteranceId == firstOfCurrentBatch) {
                        listener.onStart(currentSpoken)
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                handler.post {
                    completedId = utteranceId.orEmpty()
                    if (completedId == lastId && !streaming) {
                        abandonFocus()
                        listener.onDone()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { reportError("The speech engine could not read that.") }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handler.post { reportError("Speech error $errorCode.") }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                handler.post { /* A deliberate stop is reported by stop(). */ }
            }
        })
    }

    /** The first utterance id of the current batch, used to report the real start. */
    private var firstOfCurrentBatch = ""

    fun isReady(): Boolean = ready

    fun isSpeaking(): Boolean = ready && lastId.isNotEmpty() && completedId != lastId

    fun voices(): List<VoiceInfo> {
        val engine = tts ?: return emptyList()
        return try {
            engine.voices
                .asSequence()
                .filter { it.locale != null }
                .map {
                    VoiceInfo(
                        name = it.name,
                        locale = it.locale,
                        quality = it.quality,
                        needsNetwork = it.isNetworkConnectionRequired,
                    )
                }
                .sortedWith(compareBy({ it.locale.language }, { it.name }))
                .toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun describeEngine(): String {
        val engine = tts ?: return "Text-to-speech is starting."
        return try {
            val locale = engine.voice?.locale ?: Locale.getDefault()
            "Android TTS · ${engine.defaultEngine} · ${locale.displayName}"
        } catch (t: Throwable) {
            "Android text-to-speech ready."
        }
    }

    /** Applies voice, language, rate and pitch. Safe to call at any time. */
    fun applySettings() {
        val engine = tts ?: return
        if (!ready) return
        try {
            val tag = prefs.voiceLanguage.ifBlank { Locale.getDefault().toLanguageTag() }
            val locale = Locale.forLanguageTag(tag)
            val availability = engine.isLanguageAvailable(locale)
            if (availability == TextToSpeech.LANG_MISSING_DATA ||
                availability == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.language = Locale.getDefault()
            } else {
                engine.language = locale
            }
            if (prefs.voiceName.isNotBlank()) {
                val match = engine.voices.firstOrNull { it.name == prefs.voiceName }
                if (match != null) engine.voice = match
            }
            engine.setSpeechRate(prefs.voiceRate)
            engine.setPitch(prefs.voicePitch)
        } catch (t: Throwable) {
            // A bad saved voice must never break the conversation.
        }
    }

    /** Speaks a complete answer, replacing anything already in the queue. */
    fun speak(raw: String) {
        val text = SpeechText.clean(raw)
        if (text.isEmpty()) return
        if (!ready) {
            pending = text
            initialize()
            return
        }
        stopInternal(notify = false)
        val segments = SpeechText.sentences(text)
        if (segments.isEmpty()) return
        if (!requestFocus()) {
            listener.onError("Another app has the audio focus.")
            return
        }
        streaming = false
        currentSpoken = text
        firstOfCurrentBatch = ""
        completedId = ""
        segments.forEachIndexed { index, segment ->
            val id = nextId()
            if (firstOfCurrentBatch.isEmpty()) firstOfCurrentBatch = id
            lastId = id
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            speakSegment(segment, mode, id)
        }
    }

    // ------------------------------------------------------------- streaming

    fun startStream() {
        if (!ready) return
        stopInternal(notify = false)
        if (!requestFocus()) return
        streaming = true
        currentSpoken = ""
        firstOfCurrentBatch = ""
        lastId = ""
        completedId = ""
    }

    /** Queues one complete sentence from a streamed answer. */
    fun enqueueSentence(sentence: String) {
        if (!ready) {
            pending = ((pending ?: "") + " " + sentence).trim()
            return
        }
        val text = SpeechText.clean(sentence)
        if (text.isEmpty()) return
        currentSpoken = (currentSpoken + " " + text).trim()
        val id = nextId()
        if (firstOfCurrentBatch.isEmpty()) firstOfCurrentBatch = id
        val mode = if (lastId.isEmpty()) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        lastId = id
        speakSegment(text, mode, id)
    }

    /** Called after the last delta: completes the onDone signal at the right time. */
    fun finishStream() {
        streaming = false
        if (lastId.isEmpty()) {
            abandonFocus()
            listener.onDone()
        } else if (completedId == lastId) {
            abandonFocus()
            listener.onDone()
        }
    }

    fun stop() {
        stopInternal(notify = true)
    }

    private fun stopInternal(notify: Boolean) {
        try { tts?.stop() } catch (t: Throwable) { }
        streaming = false
        lastId = ""
        completedId = ""
        firstOfCurrentBatch = ""
        currentSpoken = ""
        abandonFocus()
        if (notify) listener.onStopped()
    }

    private fun speakSegment(text: String, mode: Int, id: String) {
        val engine = tts ?: return
        val result = engine.speak(text, mode, null, id)
        if (result == TextToSpeech.ERROR) {
            reportError("The speech engine rejected the utterance.")
        }
    }

    private fun nextId(): String {
        counter += 1
        return "jarvis-$counter"
    }

    private fun reportError(message: String) {
        streaming = false
        lastId = ""
        completedId = ""
        abandonFocus()
        listener.onError(message)
    }

    private fun requestFocus(): Boolean {
        val manager = audio ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener(focusListener)
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = request
                manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (t: Throwable) {
            true
        }
    }

    private fun abandonFocus() {
        val manager = audio ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { manager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(focusListener)
            }
        } catch (t: Throwable) {
        }
    }

    fun shutdown() {
        stopInternal(notify = false)
        try { tts?.shutdown() } catch (t: Throwable) { }
        tts = null
        ready = false
        pending = null
    }
}
