package com.bonjee.notifier.whatsapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.bonjee.notifier.Prefs

/**
 * Speaks important WhatsApp messages, within the phone's own rules.
 *
 * The core decision lives in the pure [AudioPolicy]; this class only reads the
 * real system state and drives TTS. It never toggles Do Not Disturb, never asks
 * for notification-policy access, and reports when Android has decided to keep
 * quiet. TTS is best-effort: a missing voice or a mid-sentence disconnection
 * ends the announcement without crashing the notification path.
 */
class Announcer(context: Context) {

    private val app = context.applicationContext
    private val prefs = Prefs.get(app)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var pending: String? = null

    @Volatile
    private var restoreVolume: Int = -1

    private val audioManager: AudioManager? =
        app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val notificationManager: android.app.NotificationManager? =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager

    private val initListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return@OnInitListener
            val locale = engine.defaultVoice?.locale ?: java.util.Locale.getDefault()
            val language = engine.setLanguage(locale)
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fall back to English rather than staying silent with no explanation.
                engine.setLanguage(java.util.Locale.ENGLISH)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
            ready = true
            pending?.let { text ->
                pending = null
                speakNow(text)
            }
        } else {
            Log.w(TAG, "Text-to-speech unavailable (status $status)")
        }
    }

    private fun ensureTts(): TextToSpeech? {
        tts?.let { return it }
        return try {
            val engine = TextToSpeech(app, initListener)
            tts = engine
            engine
        } catch (t: Throwable) {
            Log.w(TAG, "Could not start TTS: ${t.message}")
            null
        }
    }

    /** Reads the live audio state and asks [AudioPolicy] what to do. */
    fun decide(important: Boolean): AudioDecision {
        val am = audioManager
        val silent = am != null &&
            (am.ringerMode == AudioManager.RINGER_MODE_SILENT || am.ringerMode == AudioManager.RINGER_MODE_VIBRATE)
        val dnd = notificationManager?.currentInterruptionFilter?.let {
            it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL &&
                it != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } ?: false
        val inCall = am?.mode == AudioManager.MODE_IN_CALL || am?.mode == AudioManager.MODE_IN_COMMUNICATION

        return AudioPolicy.decide(
            AudioInput(
                important = important,
                announcementsEnabled = prefs.announceImportant,
                ringerSilent = silent,
                dndActive = dnd,
                earphoneConnected = hasPrivateOutput(),
                earphoneException = prefs.earphoneException,
                quietHours = inQuietHours(),
                callInProgress = inCall,
                readBody = prefs.announceBody,
                allowBodyOnSpeaker = prefs.announceBodyOnSpeaker,
            ),
        )
    }

    /**
     * Announces one message. Returns the decision so the caller can surface the
     * reason in the UI when speech was intentionally suppressed.
     */
    fun announce(record: MessageRecord): AudioDecision =
        announce(record, decide(record.isImportant))

    /** Same as [announce], reusing a decision already taken for the notification. */
    fun announce(record: MessageRecord, decision: AudioDecision): AudioDecision {
        if (!decision.speak) return decision

        val who = record.senderName.trim().ifEmpty { PhoneNumber.pretty(record.sender).ifEmpty { "a contact" } }
        val body = record.text.trim()
        val line = when {
            !decision.includeBody || body.isEmpty() -> "$who sent you a WhatsApp message."
            else -> "$who says: $body"
        }
        speak(line)
        return decision
    }

    /** Speaks arbitrary text (used by the reply screen to read a draft). */
    fun speak(text: String): Boolean {
        val clean = text.trim().take(600)
        if (clean.isEmpty()) return false
        if (ensureTts() == null) return false
        if (!ready) {
            pending = clean
            return true
        }
        return speakNow(clean)
    }

    private fun speakNow(text: String): Boolean {
        val engine = tts ?: return false
        return try {
            boostVolumeIfWanted()
            val id = "jarvis-${System.currentTimeMillis()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = restore()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = restore()
                override fun onError(utteranceId: String?, errorCode: Int) = restore()
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            // Backstop in case the engine never calls back.
            handler.postDelayed({ restore() }, 30_000)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "TTS speak failed: ${t.message}")
            restore()
            false
        }
    }

    /**
     * Optional volume behaviour: lift media volume only while speaking, then put
     * it back. Never touches the ringer or DND. If the phone is silent this is
     * skipped - raising media volume would not make a silent phone speak.
     */
    private fun boostVolumeIfWanted() {
        if (!prefs.announceVolumeBoost) return
        val am = audioManager ?: return
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current <= 0) {
                restoreVolume = current
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.6f).toInt(), 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Volume boost skipped: ${t.message}")
        }
    }

    private fun restore() {
        val am = audioManager ?: return
        val original = restoreVolume
        if (original < 0) return
        restoreVolume = -1
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not restore volume: ${t.message}")
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "TTS stop failed: ${t.message}")
        }
        pending = null
        restore()
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "TTS shutdown failed: ${t.message}")
        }
        tts = null
        ready = false
    }

    /** True when a wired/Bluetooth/USB private output is connected right now. */
    private fun hasPrivateOutput(): Boolean {
        val am = audioManager ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                when (device.type) {
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    -> true
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET ->
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    else -> false
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** Quiet hours, parsed from HH:mm. Handles a range that crosses midnight. */
    fun inQuietHours(now: Long = System.currentTimeMillis()): Boolean {
        if (!prefs.quietHoursEnabled) return false
        val start = minutesOf(prefs.quietStart) ?: return false
        val end = minutesOf(prefs.quietEnd) ?: return false
        if (start == end) return false
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val minutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (start < end) minutes in start until end else minutes >= start || minutes < end
    }

    private fun minutesOf(value: String): Int? {
        val parts = value.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    companion object {
        private const val TAG = "JarvisAnnouncer"

        @Volatile
        private var instance: Announcer? = null

        fun get(context: Context): Announcer =
            instance ?: synchronized(this) {
                instance ?: Announcer(context.applicationContext).also { instance = it }
            }
    }
}
