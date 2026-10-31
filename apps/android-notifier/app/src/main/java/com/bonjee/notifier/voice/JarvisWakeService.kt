package com.bonjee.notifier.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bonjee.notifier.AskActivity
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R
import java.util.Locale

/**
 * Foreground wake-word listener.
 *
 * Android only allows continuous microphone use from a foreground service with
 * a visible notification, which is exactly what this is. It is started from the
 * app (never secretly at boot) and stops itself as soon as the assistant opens,
 * so only one component owns the microphone at a time.
 *
 * Limits are stated in the UI: some vendors still throttle background
 * recognition, and Android 14 refuses a microphone foreground service started
 * from the background. The reliable fallback is the microphone button.
 */
class JarvisWakeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var restarting = false
    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        running = true
        isRunning = true
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.wakeWordEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (!prefs.wakeWordEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification("Listening for \"Hey JARVIS\"")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }

        startRecognition()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        isRunning = false
        restarting = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (t: Throwable) { }
        try { recognizer?.destroy() } catch (t: Throwable) { }
        recognizer = null
        super.onDestroy()
    }

    private fun startRecognition() {
        if (!running) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition is unavailable on this phone")
            stopSelf()
            return
        }

        if (recognizer == null) {
            recognizer = try {
                SpeechRecognizer.createSpeechRecognizer(this).also {
                    it.setRecognitionListener(recognitionListener)
                }
            } catch (t: Throwable) {
                stopSelf()
                return
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                prefs.voiceLanguage.ifBlank { Locale.getDefault().toLanguageTag() },
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        }

        try {
            recognizer?.startListening(intent)
        } catch (t: Throwable) {
            scheduleRestart(1200)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running || restarting) return
        restarting = true
        handler.postDelayed({
            restarting = false
            if (running) startRecognition()
        }, delayMs)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank() && WakePhrases.matches(text)) handleWake(text)
        }

        override fun onResults(results: Bundle?) {
            if (!running) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank() && WakePhrases.matches(text)) {
                handleWake(text)
            } else {
                scheduleRestart(300)
            }
        }

        override fun onError(error: Int) {
            if (!running) return
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    updateNotification("Microphone permission is missing")
                    stopSelf()
                    return
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> 2500L
                else -> 700L
            }
            scheduleRestart(delay)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleWake(heard: String) {
        if (!running) return
        running = false
        try { recognizer?.cancel() } catch (t: Throwable) { }

        val command = WakePhrases.strip(heard)
        val intent = Intent(this, AskActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AskActivity.EXTRA_FROM_ASSISTANT, true)
            putExtra(AskActivity.EXTRA_START_LISTENING, true)
            putExtra(AskActivity.EXTRA_WAKE_TEXT, command)
        }

        val pending = PendingIntent.getActivity(
            this,
            43,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Android 10+ may refuse an activity start from the background. The
        // full-screen notification is the supported fallback; it is only sent
        // after the wake word was actually heard.
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            // Notification below is the fallback.
        }
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setContentTitle("JARVIS is listening")
                .setContentText("The wake word was heard. Tap if the assistant did not open.")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ALERT_ID, notification)
        } catch (t: Throwable) {
        }

        handler.postDelayed({ stopSelf() }, 1200)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            41,
            Intent(this, AskActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            42,
            Intent(this, JarvisWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_WAKE)
            .setContentTitle("JARVIS is listening")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(0, "Stop listening", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (t: Throwable) {
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WAKE, "JARVIS wake word", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while JARVIS listens for the wake word."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "JARVIS wake alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens JARVIS when the wake word is heard."
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_WAKE = "jarvis_wake_listener"
        private const val CHANNEL_ALERT = "jarvis_wake_alert"
        private const val NOTIFICATION_ID = 1102
        private const val ALERT_ID = 1103
        private const val ACTION_STOP = "com.bonjee.notifier.STOP_WAKE"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Starts the foreground listener. Returns false when it cannot start. */
        fun start(context: Context): Boolean {
            val prefs = Prefs.get(context)
            if (!prefs.wakeWordEnabled) return false
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) return false
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, JarvisWakeService::class.java),
                )
                true
            } catch (t: Throwable) {
                false
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, JarvisWakeService::class.java))
            } catch (t: Throwable) {
            }
        }
    }
}
