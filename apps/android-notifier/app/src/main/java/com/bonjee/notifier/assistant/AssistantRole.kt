package com.bonjee.notifier.assistant

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/**
 * Helpers for Android's assistant role.
 *
 * The app never changes the default assistant silently - Android only allows the
 * user to do that. This class only reports the current state and opens the right
 * settings screen so the user can pick JARVIS.
 */
object AssistantRole {

    /** Settings.Secure key that stores the active VoiceInteractionService. */
    private const val VOICE_INTERACTION_SERVICE = "voice_interaction_service"

    fun component(context: Context): ComponentName =
        ComponentName(context, BonjeeVoiceInteractionService::class.java)

    /** True when Android currently routes assistant invocations to this app. */
    fun isDefault(context: Context): Boolean {
        val stored = try {
            Settings.Secure.getString(context.contentResolver, VOICE_INTERACTION_SERVICE)
        } catch (t: Throwable) {
            null
        } ?: return false

        val current = ComponentName.unflattenFromString(stored) ?: return false
        val ours = component(context)
        return current.packageName == ours.packageName && current.className == ours.className
    }

    /**
     * Opens the assistant picker. The exact screen differs per OEM (HyperOS/MIUI
     * use "Digital assistant app"), so try the dedicated action first and fall
     * back to the broader default-apps and root settings screens.
     */
    fun openSettings(activity: Activity) {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                activity.startActivity(intent)
                return
            } catch (t: Throwable) {
                // Try the next screen.
            }
        }
        Toast.makeText(
            activity,
            "Open Settings > Apps > Default apps > Digital assistant app",
            Toast.LENGTH_LONG,
        ).show()
    }
}
