package com.bonjee.notifier.device

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional helper that presses one button inside WhatsApp for the user.
 *
 * WhatsApp has no public API to start a voice/video call or to send a message,
 * so without this the best the assistant can do is open the chat. When the user
 * turns "JARVIS tap assist" on in Accessibility settings, this service watches
 * for the WhatsApp window, finds the call/send button by its accessibility
 * label, and clicks it once.
 *
 * Safety rules:
 *  - only acts when [request] armed an action in the last [WINDOW_MS];
 *  - only inside com.whatsapp;
 *  - only clicks a clickable node whose label is one of the known button names;
 *  - clears the request after a single click, so it can never tap twice.
 */
class TapAssistService : AccessibilityService() {

    enum class Action { VOICE_CALL, VIDEO_CALL, SEND }

    private data class Pending(val action: Action, val expiresAt: Long)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pending = pendingAction ?: return
        if (System.currentTimeMillis() > pending.expiresAt) {
            pendingAction = null
            return
        }
        if (event == null) return
        if (event.packageName?.toString() != WHATSAPP) return

        val root = rootInActiveWindow ?: return
        val target = findButton(root, pending.action) ?: return
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) pendingAction = null
    }

    override fun onInterrupt() {
        pendingAction = null
    }

    private fun findButton(root: AccessibilityNodeInfo, action: Action): AccessibilityNodeInfo? {
        val labels = when (action) {
            Action.VOICE_CALL -> listOf("voice call", "call")
            Action.VIDEO_CALL -> listOf("video call")
            Action.SEND -> listOf("send")
        }
        return search(root, labels, 0)
    }

    private fun search(
        node: AccessibilityNodeInfo?,
        labels: List<String>,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        val description = node.contentDescription?.toString()?.trim()?.lowercase()
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (node.isClickable && description != null && labels.any { description == it }) {
            return node
        }
        // WhatsApp sometimes keeps the label on the container, not the button.
        if (node.isClickable && id.contains("call") && labels.contains("voice call")) {
            return node
        }
        if (node.isClickable && id.contains("send") && labels.contains("send")) {
            return node
        }
        for (i in 0 until node.childCount) {
            search(node.getChild(i), labels, depth + 1)?.let { return it }
        }
        return null
    }

    companion object {
        private const val WHATSAPP = "com.whatsapp"
        private const val MAX_DEPTH = 40
        private const val WINDOW_MS = 20_000L

        @Volatile
        private var pendingAction: Pending? = null

        /** Arms the next tap. Expires by itself so it can never fire much later. */
        fun request(action: Action) {
            pendingAction = Pending(action, System.currentTimeMillis() + WINDOW_MS)
        }

        fun cancel() {
            pendingAction = null
        }

        fun isEnabled(context: Context): Boolean {
            if (!isSupported(context)) return false
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val expected = ComponentName(context, TapAssistService::class.java)
                .flattenToString()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        private fun isSupported(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? android.view.accessibility.AccessibilityManager ?: return false
            return manager.isEnabled
        }
    }
}
