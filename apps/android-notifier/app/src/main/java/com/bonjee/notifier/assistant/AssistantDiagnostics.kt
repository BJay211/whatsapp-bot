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
        appendLine()

        // ---- the session component named by the config -------------------
        appendLine("[CONFIG]")
        val sessionName = sessionServiceFromXml(context)
        when {
            sessionName == null ->
                appendLine("res/xml/voice_interaction_service.xml: UNREADABLE")
            else -> {
                appendLine("sessionService declared: $sessionName")
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
                !mineListed ->
                    "This build is NOT registered as a VoiceInteractionService in the " +
                        "running package. If the version above is not 2.2, the update did " +
                        "not install - reinstall the new APK."
                !AssistantRole.isDefault(context) ->
                    "The system can see JARVIS but it is not the selected assistant. " +
                        "Tap SET AS DEFAULT ASSISTANT and accept the Android dialog."
                else ->
                    "JARVIS is the active assistant on this device."
            },
        )
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

    /** Reads sessionService out of the compiled assistant config. */
    private fun sessionServiceFromXml(context: Context): String? = try {
        val parser = context.resources.getXml(R.xml.voice_interaction_service)
        var name: String? = null
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG &&
                parser.name == "voice-interaction-service"
            ) {
                name = parser.getAttributeValue(ANDROID_NS, "sessionService")
                    ?: parser.getAttributeValue(null, "sessionService")
                break
            }
            event = parser.next()
        }
        parser.close()
        name
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
