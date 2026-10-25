package com.bonjee.notifier.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import com.bonjee.notifier.AskActivity

/**
 * Bridges an Android assistant invocation to the existing JARVIS conversation UI.
 *
 * The session owns no UI of its own: it launches the already-existing
 * [AskActivity] in "assistant mode", which listens, sends the recognised text to
 * the configured n8n endpoint and speaks the answer back. Everything the app
 * already does (settings, history, backend, auth header) is untouched.
 */
class BonjeeVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val intent = Intent(context, AskActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(AskActivity.EXTRA_FROM_ASSISTANT, true)
        }

        // startVoiceActivity() is the documented way for a session to put UI on
        // screen; it also keeps working on versions that block background
        // activity starts. If a device refuses it, fall back to a plain start.
        var launched = false
        try {
            startVoiceActivity(intent)
            launched = true
        } catch (t: Throwable) {
            launched = false
        }
        if (!launched) {
            try {
                context.startActivity(intent)
            } catch (t: Throwable) {
                // Nothing else we can do; the session will simply close.
            }
        }

        // Give the system a moment to commit the activity before the empty
        // session window goes away. Calling hide() immediately makes some OEM
        // builds drop the freshly started task.
        handler.postDelayed(
            { runCatching { hide() } },
            HIDE_DELAY_MS,
        )
    }

    override fun onHide() {
        handler.removeCallbacksAndMessages(null)
        super.onHide()
    }

    companion object {
        private const val HIDE_DELAY_MS = 300L
    }
}
