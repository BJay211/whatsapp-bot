package com.bonjee.notifier.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.bonjee.notifier.R

/**
 * Read-only self-check for the assistant integration.
 *
 * The Digital assistant app list is built by the system, and the only way to
 * know why an app is missing from it is to ask the same APIs the system uses.
 * This collector performs exactly those queries and prints the answers, so the
 * state can be inspected on the phone without a PC or adb.
 *
 * Nothing here changes any setting - every call is a lookup.
 */
object AssistantDiagnostics {

    private const val VOICE_INTERACTION_ACTION = "android.service.voice.VoiceInteractionService"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val SETTINGS_KEY = "voice_interaction_service"

    fun report(context: Context): String = buildString {
        val pkg = context.packageName
        val ours = AssistantRole.component(context)

        appendLine("JARVIS assistant diagnostic")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine()

        // ---- identity ----------------------------------------------------
        appendLine("[APP]")
        appendLine("package: $pkg")
        appendLine("version: ${versionName(context)} (code ${versionCode(context)})")
        appendLine("service: ${ours.flattenToShortString()}")
        appendLine("gesture opens: com.bonjee.notifier.ui.JarvisHomeActivity")
        appendLine("lock screen: allowed (supportsLaunchVoiceAssistFromKeyguard)")
        appendLine()

        // ---- what the system can discover --------------------------------
        appendLine("[SYSTEM DISCOVERY]")
        val listed = servicesForAction(context)
        val mineListed = listed.any {
            it.packageName == ours.packageName && it.className == ours.className
        }
        appendLine("services answering $VOICE_INTERACTION_ACTION: ${listed.size}")
        listed.forEach { appendLine("  - ${it.packageName}/${it.className}") }
        appendLine("this app answers that action: ${yesNo(mineListed)}")
        val info = try {
            context.packageManager.getServiceInfo(ours, 0)
        } catch (t: Throwable) {
            null
        }
        appendLine("service declared exported: ${yesNo(info?.exported == true)}")
        appendLine(
            "service bound by permission: " +
                (info?.permission?.takeIf { it.isNotBlank() } ?: "NONE (the system will ignore it)"),
        )
        appendLine()

        // ---- the components named by the config --------------------------
        appendLine("[CONFIG]")
        val cfg = configFromXml(context)
        val sessionName = cfg?.first
        val recognitionName = cfg?.second
        if (cfg == null) {
            appendLine("res/xml/voice_interaction_service.xml: UNREADABLE")
        } else {
            appendLine("sessionService declared: ${sessionName ?: "MISSING"}")
            appendLine(
                "recognitionService declared: " + (recognitionName
                    ?: "MISSING - Android hides the app from the picker without it"),
            )
            // This is the exact rule android.service.voice.VoiceInteractionServiceInfo
            // applies before offering an app as an assistant.
            val pickerOk = sessionName != null && recognitionName != null
            appendLine("picker validation (session + recognition): ${if (pickerOk) "PASS" else "FAIL"}")
            if (sessionName != null) {
                val sessionResolvable = try {
                    context.packageManager.getServiceInfo(ComponentName(pkg, sessionName), 0)
                    true
                } catch (t: Throwable) {
                    false
                }
                appendLine("session service resolvable: ${yesNo(sessionResolvable)}")
            }
        }
        appendLine()

        // ---- role / default state ----------------------------------------
        appendLine("[ROLE]")
        appendLine("assistant role available on this device: ${yesNo(AssistantRole.isRoleAvailable(context))}")
        appendLine("role currently held by this app: ${yesNo(AssistantRole.holdsRole(context))}")
        appendLine("effective default (role or setting): ${yesNo(AssistantRole.isDefault(context))}")
        appendLine("role holders now: ${roleHolders(context).ifEmpty { listOf("none") }.joinToString()}")
        appendLine("system role dialog resolvable: ${yesNo(roleDialogResolvable(context))}")
        appendLine("assistant settings screen resolvable: ${yesNo(pickerResolvable(context))}")
        appendLine("Settings.Secure $SETTINGS_KEY = ${currentDefault(context)}")
        appendLine()

        // ---- permissions the assistant path needs ------------------------
        appendLine("[MICROPHONE]")
        appendLine(
            "RECORD_AUDIO granted: " + yesNo(
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED,
            ),
        )
        appendLine()

        appendLine("Verdict")
        appendLine(
            when {
                cfg == null || sessionName == null || recognitionName == null ->
                    "Android will NOT list this app: the voice-interaction config is " +
                        "incomplete. It needs both sessionService and recognitionService. " +
                        "Reinstall the APK - if the version above is not 2.4, the update " +
                        "did not install."
                !mineListed ->
                    "This build is NOT registered as a VoiceInteractionService in the " +
                        "running package. If the version above is not 2.4, the update did " +
                        "not install - reinstall the new APK."
                !roleDialogResolvable(context) && !AssistantRole.isDefault(context) ->
                    "JARVIS is registered but this ROM has no assistant role dialog. " +
                        "Open Settings > Apps > Default apps > Digital assistant app and " +
                        "pick JARVIS (Bonjee) by hand."
                !AssistantRole.isDefault(context) ->
                    "The system can see JARVIS but it is not the selected assistant. " +
                        "Tap SET AS DEFAULT ASSISTANT and accept the Android dialog."
                else ->
                    "JARVIS is the active assistant on this device."
            },
        )
    }

    /** Every package the system currently considers the assistant. */
    private fun roleHolders(context: Context): List<String> {
        // RoleManager only exposes isRoleHeld() publicly; the holders list is a
        // system API. Report what the app can legitimately read.
        if (!AssistantRole.isRoleAvailable(context)) return emptyList()
        return if (AssistantRole.holdsRole(context)) {
            listOf(context.packageName)
        } else {
            emptyList()
        }
    }

    /** True when some activity on the device can show the "set as assistant" dialog. */
    private fun roleDialogResolvable(context: Context): Boolean {
        val intent = AssistantRole.roleRequestIntent(context) ?: return false
        return try {
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        } catch (t: Throwable) {
            false
        }
    }

    /** True when the OEM kept the assistant settings screen the app tries to open. */
    private fun pickerResolvable(context: Context): Boolean = try {
        context.packageManager
            .queryIntentActivities(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), 0)
            .isNotEmpty()
    } catch (t: Throwable) {
        false
    }

    /** Every service the system would consider for the assistant role. */
    private fun servicesForAction(context: Context): List<ComponentName> {
        val intent = Intent(VOICE_INTERACTION_ACTION)
        val pm = context.packageManager
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
            }
        } catch (t: Throwable) {
            emptyList()
        }
        return resolved.mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            ComponentName(service.packageName, service.name)
        }.distinct()
    }

    /** Reads sessionService and recognitionService out of the compiled config. */
    private fun configFromXml(context: Context): Pair<String?, String?>? = try {
        val parser = context.resources.getXml(R.xml.voice_interaction_service)
        var session: String? = null
        var recognition: String? = null
        var found = false
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG &&
                parser.name == "voice-interaction-service"
            ) {
                session = parser.getAttributeValue(ANDROID_NS, "sessionService")
                    ?: parser.getAttributeValue(null, "sessionService")
                recognition = parser.getAttributeValue(ANDROID_NS, "recognitionService")
                    ?: parser.getAttributeValue(null, "recognitionService")
                found = true
                break
            }
            event = parser.next()
        }
        parser.close()
        if (found) session to recognition else null
    } catch (t: Throwable) {
        null
    }

    private fun currentDefault(context: Context): String = try {
        Settings.Secure.getString(context.contentResolver, SETTINGS_KEY) ?: "null"
    } catch (t: Throwable) {
        "unreadable"
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    private fun versionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (t: Throwable) {
        -1L
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "NO"

    /** Lets the user send the report to themselves (mail, chat, notes). */
    fun shareIntent(report: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "JARVIS assistant diagnostic")
            putExtra(Intent.EXTRA_TEXT, report)
        }
}
