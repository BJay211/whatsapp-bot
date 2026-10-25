package com.bonjee.notifier.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.bonjee.notifier.AskActivity

/**
 * Bridges an Android assistant invocation to the existing JARVIS conversation UI.
 *
 * The session normally owns no UI: it starts the already-existing [AskActivity]
 * in "assistant mode", which listens, sends the recognised text to the configured
 * n8n endpoint and speaks the answer back. Everything the app already does
 * (settings, history, backend, auth header) is untouched.
 *
 * If the OEM refuses the activity start - HyperOS and MIUI sometimes block a
 * session-spawned task on the first invocation - the session falls back to a
 * small card of its own with a SPEAK button, so the assistant gesture never
 * appears to do nothing.
 */
class BonjeeVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())

    /** Container handed to us by the framework; only used for the fallback card. */
    private var contentRoot: FrameLayout? = null

    override fun onCreateContentView(): View {
        // Deliberately empty: the real UI is AskActivity. Content is only added
        // when the activity cannot be started, which avoids a flash over it.
        val root = FrameLayout(context)
        contentRoot = root
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        if (openAssistantUi()) {
            // Give the system a moment to commit the activity before the empty
            // session window goes away. Calling hide() immediately makes some
            // OEM builds drop the freshly started task.
            handler.postDelayed({ runCatching { hide() } }, HIDE_DELAY_MS)
        } else {
            showFallbackCard()
        }
    }

    /**
     * @return true when some activity accepted the intent.
     */
    private fun openAssistantUi(): Boolean {
        val intent = Intent(context, AskActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(AskActivity.EXTRA_FROM_ASSISTANT, true)
        }

        // startVoiceActivity() is the documented way for a session to put UI on
        // screen; it also keeps working where background activity starts are
        // blocked. Fall back to a plain start if a device refuses it.
        try {
            startVoiceActivity(intent)
            return true
        } catch (t: Throwable) {
            // fall through
        }
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Minimal visible assistant, used only when the activity cannot be shown. */
    private fun showFallbackCard() {
        val root = contentRoot ?: return
        root.removeAllViews()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 64, 48, 64)
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#0B0F14"))
                setStroke(3, Color.parseColor("#00E5FF"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }

        card.addView(
            TextView(context).apply {
                text = "JARVIS"
                textSize = 26f
                setTextColor(Color.parseColor("#00E5FF"))
                gravity = Gravity.CENTER
            },
        )
        card.addView(
            TextView(context).apply {
                text = "The system could not open the assistant screen."
                textSize = 13f
                setTextColor(Color.parseColor("#CCD6E0"))
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 24)
            },
        )
        card.addView(
            Button(context).apply {
                text = "SPEAK"
                setOnClickListener {
                    if (openAssistantUi()) {
                        runCatching { hide() }
                    }
                }
            },
        )

        root.addView(card)
    }

    override fun onHide() {
        handler.removeCallbacksAndMessages(null)
        contentRoot = null
        super.onHide()
    }

    override fun onBackPressed() {
        runCatching { hide() }
        super.onBackPressed()
    }

    companion object {
        private const val HIDE_DELAY_MS = 300L
    }
}
