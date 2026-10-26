package com.bonjee.notifier.assistant

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * Helpers for Android's assistant role.
 *
 * The app never changes the default assistant silently - Android only allows the
 * user to do that. This class reports the current state and produces the picker
 * the user needs: the official [android.app.role.RoleManager.ROLE_ASSISTANT]
 * request on Android 10+, or the assistant settings screen on older releases.
 */
object AssistantRole {

    /** Settings.Secure key that stores the active VoiceInteractionService. */
    private const val VOICE_INTERACTION_SERVICE = "voice_interaction_service"

    fun component(context: Context): ComponentName =
        ComponentName(context, BonjeeVoiceInteractionService::class.java)

    /** True when Android currently routes assistant invocations to this app. */
    fun isDefault(context: Context): Boolean = isDefaultBySetting(context) || holdsRole(context)

    private fun isDefaultBySetting(context: Context): Boolean {
        val stored = try {
            Settings.Secure.getString(context.contentResolver, VOICE_INTERACTION_SERVICE)
        } catch (t: Throwable) {
            null
        } ?: return false

        val current = ComponentName.unflattenFromString(stored) ?: return false
        val ours = component(context)
        return current.packageName == ours.packageName && current.className == ours.className
    }

    // ------------------------------------------------------------- role APIs

    /**
     * The assistant role only exists from Android 10 (API 29). On older releases
     * the same thing is controlled by the settings screen below.
     */
    fun isRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val manager = context.getSystemService(android.app.role.RoleManager::class.java)
            manager != null && manager.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)
        } catch (t: Throwable) {
            false
        }
    }

    fun holdsRole(context: Context): Boolean {
        if (!isRoleAvailable(context)) return false
        return try {
            val manager = context.getSystemService(android.app.role.RoleManager::class.java)
            manager?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * The system dialog that asks the user to make this app the default
     * assistant. Returns null when the device has no assistant role (API < 29,
     * or an OEM that removed it), in which case the settings screen is used.
     */
    fun roleRequestIntent(context: Context): Intent? {
        if (!isRoleAvailable(context)) return null
        return try {
            context.getSystemService(android.app.role.RoleManager::class.java)
                ?.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Opens the assistant picker. The exact screen differs per OEM (HyperOS/MIUI
     * use "Digital assistant app"), so try the dedicated action first and fall
     * back to the broader default-apps, root settings and this app's own page.
     */
    fun openSettings(activity: Activity) {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + activity.packageName),
            ),
        )
        for (intent in candidates) {
            if (!canHandle(activity, intent)) continue
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

    /** True when some activity on the device can open this intent. */
    fun canHandle(context: Context, intent: Intent): Boolean = try {
        context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    } catch (t: Throwable) {
        false
    }
}
