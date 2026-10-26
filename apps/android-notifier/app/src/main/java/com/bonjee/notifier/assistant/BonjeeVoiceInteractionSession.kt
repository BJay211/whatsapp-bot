package com.bonjee.notifier.assistant

import android.app.KeyguardManager
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
import com.bonjee.notifier.ui.JarvisHomeActivity

/**
 * Bridges an Android assistant invocation to the existing JARVIS home screen.
 *
 * Unlocked: opens [JarvisHomeActivity] - the same page the launcher opens - and
 * asks it to listen immediately, rather than dropping the user on a secondary
 * "Ask your assistant" screen.
 *
 * Locked: the session shows its own small card instead of launching an activity.
 * The platform guarantees the session window is drawn above the keyguard, while
 * an activity launched from here may end up behind the lock - which is exactly
 * how the assistant used to "do nothing" after the screen was locked. The card
 * proves the gesture reached the app; SPEAK then opens the assistant.
 *
 * If the OEM refuses the activity start entirely, the same card is used, so the
 * gesture never appears to do nothing.
 */
class BonjeeVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())

    /** Container handed to us by the framework; only used for the fallback card. */
    private var contentRoot: FrameLayout? = null

    private var cardShown = false

    override fun onCreateContentView(): View {
        // Deliberately empty: the real UI is the home screen. Content is only
        // added when we cannot (or must not) start an activity, which avoids a
        // flash over it.
        val root = FrameLayout(context)
        contentRoot = root
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        if (isKeyguardLocked()) {
            // Deterministic: keep the session window up, above the keyguard.
            runCatching { setKeepAwake(true) }
            showAssistantCard(locked = true)
            return
        }

        if (openAssistantUi()) {
            // Give the system a moment to commit the activity before the empty
            // session window goes away. Calling hide() immediately makes some
            // OEM builds drop the freshly started task.
            handler.postDelayed({ runCatching { hide() } }, HIDE_DELAY_MS)
        } else {
            showAssistantCard(locked = false)
        }
    }

    override fun onLockscreenShown() {
        super.onLockscreenShown()
        // Some builds take this path instead of onShow(); make sure something is
        // on screen rather than an empty window.
        runCatching { setKeepAwake(true) }
        if (!cardShown) showAssistantCard(locked = true)
    }

    /**
     * @return true when some activity accepted the intent.
     */
    private fun openAssistantUi(): Boolean {
        val intent = Intent(context, JarvisHomeActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(JarvisHomeActivity.EXTRA_FROM_ASSISTANT, true)
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

    /** Minimal visible assistant card, used while locked or if no UI can start. */
    private fun showAssistantCard(locked: Boolean) {
        val root = contentRoot ?: return
        if (cardShown) return
        cardShown = true
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
                text = if (locked) {
                    "Phone locked. Tap SPEAK to talk."
                } else {
                    "Tap SPEAK to talk."
                }
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

    private fun isKeyguardLocked(): Boolean = try {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isKeyguardLocked == true
    } catch (t: Throwable) {
        false
    }

    override fun onHide() {
        handler.removeCallbacksAndMessages(null)
        contentRoot = null
        cardShown = false
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
