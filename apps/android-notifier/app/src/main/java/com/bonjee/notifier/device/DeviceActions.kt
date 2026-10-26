package com.bonjee.notifier.device

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.bonjee.notifier.NotificationListener
import com.bonjee.notifier.ui.ContactsActivity
import com.bonjee.notifier.ui.SetupAccessActivity
import java.util.Calendar
import java.util.Locale

/**
 * Turns one spoken or typed sentence into an action on this phone.
 *
 * Everything here runs locally: no network, no account, nothing sent anywhere.
 * [handle] is called before the n8n request, so a command like "open WhatsApp"
 * works offline and answers instantly; anything the engine does not recognise is
 * declared [Result.NotHandled] and continues to the existing n8n workflow exactly
 * as before.
 *
 * The engine never pretends: where Android forbids an app from flipping a switch
 * (Wi-Fi, Bluetooth, mobile data) it opens the system panel instead and says so.
 */
object DeviceActions {

    /** What [handle] decided to do with the sentence. */
    sealed class Result {
        /** The action ran (or the question was answered) - show [message]. */
        data class Done(val message: String) : Result()

        /** Android needs this runtime permission before the action can run. */
        data class NeedsPermission(val permission: String, val why: String) : Result()

        /** Android needs the user to confirm something on a settings screen. */
        data class NeedsSettings(val intent: Intent, val why: String) : Result()

        /** Opens one of our own screens; the message explains what it is for. */
        data class Open(val intent: Intent, val message: String) : Result()

        /** Not a device command - send it to n8n as usual. */
        object NotHandled : Result()
    }

    // ------------------------------------------------------------------ entry

    fun handle(activity: Activity, raw: String): Result {
        val text = raw.trim()
        if (text.length < 2) return Result.NotHandled
        val q = normalise(text)

        return help(q)
            ?: accessSetup(activity, q)
            ?: permissionRequest(activity, q)
            ?: alarmAndTimer(activity, text, q)
            ?: reminder(activity, text, q)
            ?: brightness(activity, text, q)
            ?: volume(activity, text, q)
            ?: mediaControl(activity, q)
            ?: torch(activity, q)
            ?: callOrMessage(activity, text, q)
            ?: systemPanel(activity, q)
            ?: deviceInfo(activity, text, q)
            ?: notifications(activity, q)
            ?: contactSearch(activity, text, q)
            ?: searchAndNavigate(activity, text, q)
            ?: openApp(activity, text, q)
            ?: Result.NotHandled
    }

    // ------------------------------------------------------------------ help

    private fun help(q: String): Result? {
        val asks = listOf(
            "help", "jarvis help", "what can you do", "what can i say",
            "what can you do for me", "commands", "list commands", "show commands",
            "your commands", "capabilities", "what do you do",
        )
        return if (asks.contains(q)) Result.Done(CommandCatalog.summary()) else null
    }

    // --------------------------------------------------------- access setup

    /**
     * "set up access" opens the guided screen; "check my access" reads out the
     * current state. Both exist because the toggles live in different Settings
     * pages that are hard to find by hand on MIUI/HyperOS.
     */
    private fun accessSetup(activity: Activity, q: String): Result? {
        val opens = listOf(
            "set up access", "setup access", "set up my access", "allow access",
            "grant access", "fix my access", "fix permissions", "set up permissions",
            "connect my access", "access setup", "give yourself access",
            "allow everything", "set up jarvis",
        )
        if (opens.any { q == it || q.startsWith("$it ") }) {
            return Result.Open(
                Intent(activity, SetupAccessActivity::class.java),
                "Opening Set Up Access. One screen per permission, and it shows YES as soon as each one is on.",
            )
        }

        val checks = listOf(
            "check my access", "check access", "what access do you have",
            "what permissions do you have", "my permissions", "check permissions",
        )
        if (!checks.any { q == it || q.startsWith("$it ") }) return null

        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val dnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (activity.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)
                ?.isNotificationPolicyAccessGranted == true
        } else {
            true
        }
        return Result.Done(
            buildString {
                appendLine("ACCESS")
                appendLine("  notifications: ${yesNo(NotificationListener.isEnabled(activity))}")
                appendLine("  microphone: ${yesNo(has(activity, Manifest.permission.RECORD_AUDIO))}")
                appendLine("  contacts: ${yesNo(has(activity, Manifest.permission.READ_CONTACTS))}")
                appendLine("  phone calls: ${yesNo(has(activity, Manifest.permission.CALL_PHONE))}")
                appendLine("  camera / torch: ${yesNo(has(activity, Manifest.permission.CAMERA))}")
                appendLine("  brightness (system settings): ${yesNo(canWriteSystem(activity))}")
                appendLine("  do not disturb: ${yesNo(dnd)}")
                appendLine("  send sms directly: ${yesNo(has(activity, Manifest.permission.SEND_SMS))}")
                appendLine("  whatsapp tap assist: ${yesNo(TapAssistService.isEnabled(activity))}")
                append("  ringer mode: ${ringerLabel(audio)}")
                appendLine()
                appendLine()
                append("Say \"set up access\" and I will open the screens one by one.")
            },
        )
    }

    private fun ringerLabel(audio: AudioManager?): String = try {
        when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
    } catch (t: Throwable) {
        "unknown"
    }

    private fun yesNo(value: Boolean) = if (value) "YES" else "no"

    // ---------------------------------------------------------- permissions

    private fun permissionRequest(activity: Activity, q: String): Result? {
        if (matchesAny(q, "allow system settings", "grant system settings", "allow modify system settings")) {
            return if (canWriteSystem(activity)) {
                Result.Done("Modify system settings is already allowed.")
            } else {
                Result.NeedsSettings(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + activity.packageName),
                    ),
                    "Allow \"Modify system settings\" so I can change the whole phone's brightness.",
                )
            }
        }
        if (matchesAny(q, "allow contacts", "grant contacts", "allow contact access")) {
            return if (has(activity, Manifest.permission.READ_CONTACTS)) {
                Result.Done("Contacts access is already allowed.")
            } else {
                Result.NeedsPermission(
                    Manifest.permission.READ_CONTACTS,
                    "I need contacts access to look people up by name.",
                )
            }
        }
        if (matchesAny(q, "allow phone", "grant phone", "allow calls", "allow call permission")) {
            return if (has(activity, Manifest.permission.CALL_PHONE)) {
                Result.Done("Phone permission is already allowed.")
            } else {
                Result.NeedsPermission(
                    Manifest.permission.CALL_PHONE,
                    "Allow phone permission and I can place calls myself instead of only opening the dialer.",
                )
            }
        }
        if (matchesAny(q, "allow camera", "grant camera")) {
            return if (has(activity, Manifest.permission.CAMERA)) {
                Result.Done("Camera permission is already allowed.")
            } else {
                Result.NeedsPermission(Manifest.permission.CAMERA, "I need camera access for that.")
            }
        }
        if (matchesAny(q, "allow microphone", "allow mic", "grant microphone")) {
            return if (has(activity, Manifest.permission.RECORD_AUDIO)) {
                Result.Done("Microphone access is already allowed.")
            } else {
                Result.NeedsPermission(Manifest.permission.RECORD_AUDIO, "I need the microphone for that.")
            }
        }
        if (matchesAny(q, "allow notification access", "grant notification access")) {
            return if (NotificationListener.isEnabled(activity)) {
                Result.Done("Notification access is already allowed.")
            } else {
                Result.NeedsSettings(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    "Switch JARVIS on in Notification access so I can read your notifications.",
                )
            }
        }
        if (matchesAny(q, "allow sms", "allow messages", "grant sms", "allow send sms")) {
            return if (has(activity, Manifest.permission.SEND_SMS)) {
                Result.Done("SMS permission is already allowed.")
            } else {
                Result.NeedsPermission(
                    Manifest.permission.SEND_SMS,
                    "Allow SMS and I will send texts without opening the messaging app.",
                )
            }
        }
        return null
    }

    // --------------------------------------------------------- alarms/timers

    private val TIMER_RE = Regex(
        """(?:set\s+)?(?:a\s+)?timer(?:\s+for)?\s+(\d{1,4})\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)?""",
    )

    private fun alarmAndTimer(activity: Activity, text: String, q: String): Result? {
        val lower = text.lowercase(Locale.getDefault())

        TIMER_RE.find(lower)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: 0
            val unit = match.groupValues.getOrNull(2).orEmpty()
            val seconds = when {
                unit.startsWith("sec") -> amount
                unit.startsWith("h") -> amount * 3600
                else -> amount * 60
            }
            if (seconds > 0) {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS timer")
                return if (tryStart(activity, intent)) {
                    Result.Done("Timer set for ${describeSeconds(seconds)}.")
                } else {
                    Result.Done("I couldn't set the timer - the clock app refused it.")
                }
            }
        }

        // "alarm volume" belongs to the volume handler, not here.
        if (hasWord(q, "volume")) return null
        if (!hasWord(q, "alarm") && !q.contains("wake me")) return null

        val inMatch = Regex("""\bin\s+(\d{1,3})\s*(minutes?|mins?|hours?|hrs?)\b""").find(lower)
        val clock: Pair<Int, Int>? = if (inMatch != null) {
            val amount = inMatch.groupValues[1].toIntOrNull() ?: 0
            val hours = inMatch.groupValues[2].startsWith("h")
            val millis = if (hours) amount * 3_600_000L else amount * 60_000L
            val cal = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + millis }
            cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
        } else {
            parseClock(lower.substringAfter(" at ", lower))
        }

        if (clock == null) {
            return if (tryStart(activity, Intent(AlarmClock.ACTION_SHOW_ALARMS))) {
                Result.Done("Opening your alarms. Tell me a time next time, for example \"set an alarm for 6:30 am\".")
            } else {
                Result.Done("Tell me a time, for example \"set an alarm for 6:30 am\".")
            }
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, clock.first)
            .putExtra(AlarmClock.EXTRA_MINUTES, clock.second)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS")
        return if (tryStart(activity, intent)) {
            Result.Done("Alarm set for ${clockText(clock.first, clock.second)}.")
        } else {
            Result.Done("I couldn't set the alarm - the clock app refused it.")
        }
    }

    private fun reminder(activity: Activity, text: String, q: String): Result? {
        if (!q.startsWith("remind me")) return null
        val lower = text.lowercase(Locale.getDefault())
        val time = parseClock(lower.substringAfter(" at ", ""))
        if (time == null) {
            return Result.Done("Tell me the time too, for example \"remind me to call mum at 5 pm\".")
        }
        var title = text.substringAfter("remind me", "").trim()
        title = title.removePrefix("to ").removePrefix("about ").trim()
        title = title.substringBeforeLast(" at ", title).trim()
        if (title.isEmpty()) title = "Reminder"

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, time.first)
            set(Calendar.MINUTE, time.second)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 30 * 60_000L)
        return if (tryStart(activity, intent)) {
            Result.Done("Reminder drafted for ${clockText(time.first, time.second)}. Save it in your calendar app.")
        } else {
            Result.Done("No calendar app accepted the reminder.")
        }
    }

    // ------------------------------------------------------------ brightness

    private fun brightness(activity: Activity, text: String, q: String): Result? {
        if (!hasWord(q, "brightness") && !hasWord(q, "brighter") && !hasWord(q, "dim") &&
            !hasWord(q, "dimmer") && !q.contains("screen light") && !hasWord(q, "darken")
        ) {
            return null
        }

        if (q.contains("auto")) {
            requireWriteSettings(
                activity,
                "Auto brightness needs \"Modify system settings\". Find JARVIS on the " +
                    "next screen and switch it on - I will retry automatically.",
            )?.let { return it }
            if (!canWriteSystem(activity)) {
                return Result.Done(
                    "Auto brightness still needs \"Modify system settings\". " +
                        "Say \"set up access\" and I will take you straight there.",
                )
            }
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            )
            return Result.Done("Auto brightness on.")
        }

        val current = currentBrightnessPercent(activity)
        val percent = when {
            hasWord(q, "max") || q.contains("full") -> 100
            hasWord(q, "min") || q.contains("lowest") -> 5
            hasWord(q, "up") || hasWord(q, "increase") || hasWord(q, "raise") ||
                hasWord(q, "brighter") || hasWord(q, "brighten") -> current + 15
            hasWord(q, "down") || hasWord(q, "decrease") || hasWord(q, "lower") ||
                hasWord(q, "reduce") || hasWord(q, "dim") || hasWord(q, "dimmer") ||
                hasWord(q, "darken") -> current - 15
            else -> percentIn(text) ?: return Result.Done(
                "Say \"brightness up\", \"brightness down\" or \"brightness 60 percent\".",
            )
        }.coerceIn(5, 100)

        return applyBrightness(activity, percent)
    }

    private fun applyBrightness(activity: Activity, percent: Int): Result {
        if (canWriteSystem(activity)) {
            val value = (percent * 255 / 100).coerceIn(1, 255)
            try {
                Settings.System.putInt(
                    activity.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
                Settings.System.putInt(
                    activity.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    value,
                )
                return Result.Done("Brightness set to $percent% for the whole phone.")
            } catch (t: Throwable) {
                // Fall through to the window-only change.
            }
        } else {
            // First attempt: walk the user to the switch instead of silently
            // pretending. After that, do what is possible and say what it did.
            requireWriteSettings(
                activity,
                "Brightness needs \"Modify system settings\". Find JARVIS on the next " +
                    "screen and switch it on - I will retry automatically.",
            )?.let { return it }
        }
        val attrs = activity.window.attributes
        attrs.screenBrightness = (percent / 100f).coerceIn(0.05f, 1f)
        activity.window.attributes = attrs
        return Result.Done(
            "Brightness $percent% for this screen only. " +
                "For the whole phone say \"set up access\" and allow \"Modify system settings\".",
        )
    }

    /** Asks for WRITE_SETTINGS once, then stops nagging. */
    private fun requireWriteSettings(activity: Activity, why: String): Result.NeedsSettings? {
        if (canWriteSystem(activity)) return null
        val sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_ASKED_WRITE, false)) return null
        sp.edit().putBoolean(KEY_ASKED_WRITE, true).apply()
        return Result.NeedsSettings(writeSettingsIntent(activity), why)
    }

    private fun currentBrightnessPercent(activity: Activity): Int = try {
        val value = Settings.System.getInt(
            activity.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128,
        )
        (value * 100 / 255).coerceIn(1, 100)
    } catch (t: Throwable) {
        50
    }

    private fun canWriteSystem(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(context)

    /**
     * Some OEMs (MIUI/HyperOS included) do not resolve the package-scoped
     * variant. Fall back to the plain screen so the user is never dumped on a
     * blank "app not found" dialog.
     */
    private fun writeSettingsIntent(activity: Activity): Intent {
        val scoped = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:" + activity.packageName),
        )
        val resolvable = activity.packageManager.resolveActivity(scoped, 0) != null
        return if (resolvable) scoped else Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
    }

    // ---------------------------------------------------------------- volume

    private fun volume(activity: Activity, text: String, q: String): Result? {
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null

        if (hasWord(q, "silent") || q.contains("silent mode") || q == "silence") {
            return setRinger(activity, audio, AudioManager.RINGER_MODE_SILENT, "Phone silent.")
        }
        if (hasWord(q, "vibrate")) {
            return setRinger(activity, audio, AudioManager.RINGER_MODE_VIBRATE, "Phone on vibrate.")
        }
        if (q.contains("sound on") || q.contains("normal mode") || q.contains("ringer on")) {
            return setRinger(activity, audio, AudioManager.RINGER_MODE_NORMAL, "Ringer back on.")
        }

        val mentionsVolume = hasWord(q, "volume") || hasWord(q, "louder") ||
            hasWord(q, "quieter") || q == "mute" || q == "unmute"
        if (!mentionsVolume) return null

        val stream = when {
            hasWord(q, "alarm") -> AudioManager.STREAM_ALARM
            hasWord(q, "ring") || hasWord(q, "ringtone") || q.contains("incoming call") ->
                AudioManager.STREAM_RING
            hasWord(q, "notification") || hasWord(q, "alerts") -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val label = when (stream) {
            AudioManager.STREAM_ALARM -> "Alarm volume"
            AudioManager.STREAM_RING -> "Ring volume"
            AudioManager.STREAM_NOTIFICATION -> "Notification volume"
            else -> "Media volume"
        }

        if (hasWord(q, "mute")) {
            return try {
                rememberVolume(activity, stream, audio.getStreamVolume(stream))
                audio.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
                Result.Done("$label muted.")
            } catch (t: Throwable) {
                volumeFailure(activity, t)
            }
        }
        if (hasWord(q, "unmute")) {
            return try {
                val max = audio.getStreamMaxVolume(stream)
                val restore = rememberedVolume(activity, stream)
                    .coerceIn(1, max)
                    .let { if (it <= 0) (max / 2).coerceAtLeast(1) else it }
                audio.setStreamVolume(stream, restore, AudioManager.FLAG_SHOW_UI)
                Result.Done("$label back to ${percentOf(audio, stream, restore)}%.")
            } catch (t: Throwable) {
                volumeFailure(activity, t)
            }
        }

        val max = try {
            audio.getStreamMaxVolume(stream)
        } catch (t: Throwable) {
            return volumeFailure(activity, t)
        }
        val before = try {
            audio.getStreamVolume(stream)
        } catch (t: Throwable) {
            -1
        }

        return try {
            if (hasWord(q, "up") || hasWord(q, "increase") || hasWord(q, "raise") || hasWord(q, "louder")) {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            } else if (hasWord(q, "down") || hasWord(q, "decrease") || hasWord(q, "lower") ||
                hasWord(q, "reduce") || hasWord(q, "quieter")
            ) {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            } else if (hasWord(q, "max") || q.contains("full")) {
                audio.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
            } else {
                val percent = percentIn(text) ?: return Result.Done(
                    "Say \"volume up\", \"volume down\" or \"volume 60 percent\".",
                )
                audio.setStreamVolume(
                    stream,
                    (percent * max / 100).coerceIn(0, max),
                    AudioManager.FLAG_SHOW_UI,
                )
            }
            val after = audio.getStreamVolume(stream)
            val pctFrom = if (before < 0 || max <= 0) null else before * 100 / max
            val pctTo = percentOf(audio, stream, after)
            val hint = if (stream == AudioManager.STREAM_MUSIC && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                " The phone is on ${ringerLabel(audio)} - say \"sound on\" to hear it."
            } else {
                ""
            }
            Result.Done(
                if (pctFrom == null) "$label $pctTo%.$hint"
                else "$label $pctFrom% \u2192 $pctTo%.$hint",
            )
        } catch (t: Throwable) {
            volumeFailure(activity, t)
        }
    }

    /**
     * Volume changes can throw on ROMs with strict audio policy (Xiaomi, Samsung
     * work profiles). Never let that kill the assistant's answer.
     */
    private fun volumeFailure(activity: Activity, t: Throwable): Result {
        return if (t is SecurityException) {
            Result.NeedsSettings(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                "Android blocked the volume change. Allow Do Not Disturb access and I can control it.",
            )
        } else {
            Result.Done(
                "The phone refused the volume change (${t.javaClass.simpleName}). " +
                    "Try the volume buttons, or say \"set up access\".",
            )
        }
    }

    private fun setRinger(activity: Activity, audio: AudioManager, mode: Int, message: String): Result {
        return try {
            audio.ringerMode = mode
            Result.Done(message)
        } catch (t: Throwable) {
            if (mode == AudioManager.RINGER_MODE_NORMAL) {
                Result.Done("Android refused to change the ringer mode.")
            } else {
                Result.NeedsSettings(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                    "Allow Do Not Disturb access and I can silence the phone for you. " +
                        "Say \"set up access\" to do all the permissions at once.",
                )
            }
        }
    }

    private fun percentOf(audio: AudioManager, stream: Int, value: Int): Int {
        val max = try {
            audio.getStreamMaxVolume(stream)
        } catch (t: Throwable) {
            return 0
        }
        if (max <= 0) return 0
        return value * 100 / max
    }

    private fun rememberVolume(context: Context, stream: Int, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("volume_$stream", value).apply()
    }

    private fun rememberedVolume(context: Context, stream: Int): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("volume_$stream", 0)

    // ----------------------------------------------------------------- media

    private fun mediaControl(activity: Activity, q: String): Result? {
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val key = when {
            q == "pause" || q == "pause music" || q == "pause the music" ||
                q == "stop music" || q == "stop the music" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            q == "play" || q == "resume" || q == "resume music" || q == "play music" ||
                q == "continue music" || q == "unpause" -> KeyEvent.KEYCODE_MEDIA_PLAY
            q == "next" || q == "next song" || q == "next track" || q == "skip" ||
                q == "skip song" || q == "skip track" -> KeyEvent.KEYCODE_MEDIA_NEXT
            q == "previous" || q == "previous song" || q == "previous track" ||
                q == "last song" || q == "go back a song" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            q == "play pause" || q == "play or pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> return null
        }
        return try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            val message = when (key) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> "Next track."
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Previous track."
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "Paused."
                else -> "Playing."
            }
            Result.Done(message)
        } catch (t: Throwable) {
            Result.Done("No media player responded.")
        }
    }

    // ----------------------------------------------------------------- torch

    private fun torch(activity: Activity, q: String): Result? {
        val mentions = hasWord(q, "torch") || hasWord(q, "flashlight") ||
            q.contains("flash light") || q.contains("torch light")
        if (!mentions) return null

        val on = when {
            hasWord(q, "off") || q.contains("turn off") || q.contains("switch off") -> false
            hasWord(q, "on") || q.contains("turn on") || q.contains("switch on") -> true
            hasWord(q, "toggle") -> !torchWasOn(activity)
            else -> !torchWasOn(activity)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Result.Done("This Android version can't switch the torch from an app.")
        }
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return Result.Done("No camera on this device.")
        return try {
            val id = manager.cameraIdList.firstOrNull { candidate ->
                manager.getCameraCharacteristics(candidate)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: manager.cameraIdList.firstOrNull()
            if (id == null) {
                Result.Done("No torch on this device.")
            } else {
                manager.setTorchMode(id, on)
                saveTorch(activity, on)
                Result.Done(if (on) "Torch on." else "Torch off.")
            }
        } catch (t: Throwable) {
            if (t is SecurityException) {
                Result.NeedsPermission(Manifest.permission.CAMERA, "I need camera access for the torch.")
            } else {
                Result.Done("Couldn't switch the torch - another app may be using the camera.")
            }
        }
    }

    private fun torchWasOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("torch_on", false)

    private fun saveTorch(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("torch_on", on).apply()
    }

    // ------------------------------------------------- calls / texts / email

    private fun callOrMessage(activity: Activity, text: String, q: String): Result? {
        val lower = text.lowercase(Locale.getDefault())

        // WhatsApp call - must come before the plain "call" pattern, otherwise
        // "call John on WhatsApp" would dial his number instead.
        Regex("""^(?:whatsapp|wa)\s+(video\s+)?call\s+(.+)$""").find(lower)?.let { match ->
            val video = match.groupValues[1].isNotBlank()
            return whatsappCall(activity, match.groupValues[2].trim(), video)
        }
        Regex("""^(?:call|ring)\s+(.+?)\s+(?:on|via|in|through)\s+whatsapp(\s+video)?$""")
            .find(lower)?.let { match ->
                return whatsappCall(activity, match.groupValues[1].trim(), match.groupValues[2].isNotBlank())
            }
        Regex("""^(?:video\s+call|video)\s+(.+?)\s+(?:on|via|in)\s+whatsapp$""").find(lower)?.let { match ->
            return whatsappCall(activity, match.groupValues[1].trim(), video = true)
        }

        // call / dial
        val callMatch = Regex("""^(?:please\s+)?(?:call|dial|phone)\s+(.+)$""").find(lower)
        if (callMatch != null) {
            val target = callMatch.groupValues[1]
                .removeSuffix(" please")
                .trim()
            return placeCall(activity, target)
        }

        // whatsapp message
        val waMatch = Regex("""^(?:send\s+(?:a\s+)?whatsapp(?:\s+message)?\s+to|whatsapp|message\s+on\s+whatsapp)\s+(.+)$""")
            .find(lower)
        if (waMatch != null) {
            return whatsapp(activity, waMatch.groupValues[1], sendNow = lower.startsWith("send"))
        }

        // text / sms
        val smsMatch = Regex(
            """^(?:send\s+(?:an?\s+)?(?:text|sms|message)\s+to|text|sms|message)\s+(.+)$""",
        ).find(lower)
        if (smsMatch != null) return sendText(activity, smsMatch.groupValues[1])

        // email
        val mailMatch = Regex("""^(?:send\s+(?:an\s+)?email\s+to|email|mail)\s+(.+)$""").find(lower)
        if (mailMatch != null) return sendEmail(activity, mailMatch.groupValues[1])

        return null
    }

    /**
     * WhatsApp voice/video call.
     *
     * WhatsApp exposes no intent for calling, so the assistant opens the chat and
     * arms [TapAssistService]; when tap assist is enabled the service presses the
     * call button, otherwise the user is told exactly which icon to tap.
     */
    private fun whatsappCall(activity: Activity, target: String, video: Boolean): Result {
        if (target.isBlank()) return Result.Done("Who should I call on WhatsApp?")
        val digits = digitsOf(target)
        val number = if (digits.length >= 3) {
            digits
        } else {
            if (!has(activity, Manifest.permission.READ_CONTACTS)) {
                return Result.NeedsPermission(
                    Manifest.permission.READ_CONTACTS,
                    "I need contacts access to find \"$target\" on WhatsApp.",
                )
            }
            contactNumber(activity, target)
        }
        val label = if (number == null) target else target.trim()

        if (number == null) {
            val app = resolveApp(activity, "whatsapp")
            return if (app != null && launch(activity, app)) {
                Result.Done(
                    "I don't have \"$target\" in your contacts. Opening WhatsApp so you can pick them.",
                )
            } else {
                Result.Done("I don't have \"$target\" in your contacts and WhatsApp isn't installed.")
            }
        }

        val url = "https://wa.me/" + number.filter { it.isDigit() }
        if (!tryStart(activity, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            return Result.Done("No app handled that WhatsApp link.")
        }
        val kind = if (video) "video call" else "call"
        return if (TapAssistService.isEnabled(activity)) {
            TapAssistService.request(
                if (video) TapAssistService.Action.VIDEO_CALL else TapAssistService.Action.VOICE_CALL,
            )
            Result.Done("Opening $label's chat and starting the WhatsApp $kind.")
        } else {
            Result.Done(
                "Opening $label's chat. Tap the ${if (video) "video" else "call"} icon to start the " +
                    "WhatsApp $kind - WhatsApp does not let apps start calls by themselves. " +
                    "Say \"set up access\" and enable tap assist, then I can press it for you.",
            )
        }
    }

    private fun placeCall(activity: Activity, target: String): Result {
        // "call mum on phone" -> "mum"
        val cleaned = target
            .replace(Regex("""\s+(?:on|via)\s+(?:the\s+)?(?:phone|mobile|cell|line)$"""), "")
            .trim()
            .ifEmpty { target.trim() }
        val digits = digitsOf(cleaned)
        if (digits.length >= 3) {
            return startCall(activity, digits, cleaned)
        }
        if (!has(activity, Manifest.permission.READ_CONTACTS)) {
            return Result.NeedsPermission(
                Manifest.permission.READ_CONTACTS,
                "I need contacts access to find \"$cleaned\".",
            )
        }
        val number = contactNumber(activity, cleaned)
            ?: return Result.Done("I couldn't find \"$cleaned\" in your contacts.")
        return startCall(activity, number, cleaned)
    }

    private fun startCall(activity: Activity, number: String, label: String): Result {
        val direct = has(activity, Manifest.permission.CALL_PHONE)
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL)
            .setData(Uri.parse("tel:$number"))
        return if (tryStart(activity, intent)) {
            if (direct) Result.Done("Calling $label.")
            else Result.Done(
                "Dialer open for $label. Say \"allow phone\" and I can place the call myself.",
            )
        } else {
            Result.Done("No phone app handled that call.")
        }
    }

    private fun sendText(activity: Activity, rest: String): Result {
        val (who, body) = splitMessage(rest)
        if (who.isBlank()) return Result.Done("Who should I text?")
        val digits = digitsOf(who)
        val number = if (digits.length >= 3) {
            digits
        } else {
            if (!has(activity, Manifest.permission.READ_CONTACTS)) {
                return Result.NeedsPermission(
                    Manifest.permission.READ_CONTACTS,
                    "I need contacts access to find \"$who\".",
                )
            }
            contactNumber(activity, who)
                ?: return Result.Done("I couldn't find \"$who\" in your contacts.")
        }
        // With SEND_SMS granted the message goes out immediately; otherwise it is
        // composed and waits for the user to press send, as before.
        if (body.isNotBlank() && has(activity, Manifest.permission.SEND_SMS)) {
            try {
                smsManager(activity).sendTextMessage(number, null, body, null, null)
                return Result.Done("Text sent to $who.")
            } catch (t: Throwable) {
                // Fall through to the composer if the carrier or ROM refuses.
            }
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
        if (body.isNotBlank()) intent.putExtra("sms_body", body)
        return if (tryStart(activity, intent)) {
            Result.Done(
                if (body.isBlank()) "Text to $who ready."
                else "Text to $who ready. Say \"allow sms\" and I will send it without opening the app.",
            )
        } else {
            Result.Done("No messaging app handled that.")
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(activity: Activity): android.telephony.SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.getSystemService(android.telephony.SmsManager::class.java)
                ?: android.telephony.SmsManager.getDefault()
        } else {
            android.telephony.SmsManager.getDefault()
        }

    private fun whatsapp(activity: Activity, rest: String, sendNow: Boolean): Result {
        val (who, body) = splitMessage(rest)
        if (who.isBlank()) return Result.Done("Who should I message on WhatsApp?")
        val digits = digitsOf(who)
        val number = if (digits.length >= 3) {
            digits
        } else {
            if (!has(activity, Manifest.permission.READ_CONTACTS)) {
                return Result.NeedsPermission(
                    Manifest.permission.READ_CONTACTS,
                    "I need contacts access to find \"$who\".",
                )
            }
            contactNumber(activity, who)
        }
        if (number == null) {
            // WhatsApp cannot be opened for a name we cannot resolve, so at least
            // open the app rather than failing silently.
            val app = resolveApp(activity, "whatsapp")
            return if (app != null && launch(activity, app)) {
                Result.Done("I don't have \"$who\" in your contacts. Opening WhatsApp so you can search.")
            } else {
                Result.Done("I don't have \"$who\" in your contacts and WhatsApp isn't installed.")
            }
        }
        val url = "https://wa.me/" + number.filter { it.isDigit() } + if (body.isNotBlank()) {
            "?text=" + Uri.encode(body)
        } else {
            ""
        }
        if (!tryStart(activity, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            return Result.Done("No app handled that WhatsApp link.")
        }
        if (body.isBlank()) return Result.Done("Opening WhatsApp chat with $who.")

        return if (sendNow && TapAssistService.isEnabled(activity)) {
            TapAssistService.request(TapAssistService.Action.SEND)
            Result.Done("Sending the WhatsApp message to $who.")
        } else {
            Result.Done(
                "WhatsApp chat with $who is open with the message typed. Tap send - or enable " +
                    "tap assist in \"set up access\" and I will send it myself next time.",
            )
        }
    }

    private fun sendEmail(activity: Activity, rest: String): Result {
        val (who, body) = splitMessage(rest)
        if (who.isBlank()) return Result.Done("Who should I email?")
        val address = if (who.contains("@")) {
            who
        } else {
            if (!has(activity, Manifest.permission.READ_CONTACTS)) {
                return Result.NeedsPermission(
                    Manifest.permission.READ_CONTACTS,
                    "I need contacts access to find \"$who\"'s address.",
                )
            }
            contactEmail(activity, who)
                ?: return Result.Done(
                    "I don't have an email address for \"$who\". Say \"email someone@example.com\".",
                )
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address)))
        if (body.isNotBlank()) intent.putExtra(Intent.EXTRA_SUBJECT, body)
        return if (tryStart(activity, intent)) {
            Result.Done("Email to $who ready.")
        } else {
            Result.Done("No email app handled that.")
        }
    }

    /**
     * Splits "john saying i am coming" into the person and the message body.
     * A leading phone number is also split off: "0803 123 4567 i will call you".
     */
    private fun splitMessage(rest: String): Pair<String, String> {
        Regex("""^([+\d][\d\s()+-]{5,}?)\s+([A-Za-z].*)$""").find(rest)?.let { number ->
            return number.groupValues[1].trim() to number.groupValues[2].trim()
        }
        val markers = listOf(" saying ", " that says ", " with message ", " with ", " about ", " message ")
        val lower = rest.lowercase(Locale.getDefault())
        for (marker in markers) {
            val at = lower.indexOf(marker)
            if (at > 0) {
                return rest.substring(0, at).trim().removeSuffix(",") to
                    rest.substring(at + marker.length).trim()
            }
        }
        return rest.trim() to ""
    }

    // ------------------------------------------------------- system panels

    private fun systemPanel(activity: Activity, q: String): Result? {
        val entry = PANELS.firstOrNull { it.pattern.containsMatchIn(q) } ?: return null
        val message = if (entry.honest) {
            "Android won't let apps switch ${entry.label} directly, so I opened the ${entry.label} panel."
        } else {
            "Opening ${entry.label} settings."
        }
        val opened = openSettings(activity, entry.action, entry.fallback)
        return Result.Done(if (opened) message else "I couldn't open ${entry.label} settings on this phone.")
    }

    private fun openSettings(activity: Activity, action: String, fallback: String?): Boolean {
        if (tryStart(activity, Intent(action))) return true
        if (fallback != null && tryStart(activity, Intent(fallback))) return true
        return tryStart(activity, Intent(Settings.ACTION_SETTINGS))
    }

    // ----------------------------------------------------------- device info

    private fun deviceInfo(activity: Activity, text: String, q: String): Result? {
        if (hasWord(q, "battery") && !q.contains("saver")) {
            val manager = activity.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val level = manager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val charging = isCharging(activity)
            val body = if (level in 0..100) "Battery is $level%" else "Battery level is unavailable"
            return Result.Done(body + if (charging) " and charging." else ".")
        }
        if (q == "time" || q.contains("what time") || q.contains("the time") ||
            q.contains("current time") || q.endsWith(" time")
        ) {
            val now = Calendar.getInstance()
            return Result.Done("It is ${clockText(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))}.")
        }
        if (q == "date" || q.contains("what date") || q.contains("the date") ||
            q.contains("what day") || q.contains("today") || q.endsWith(" date")
        ) {
            val fmt = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
            return Result.Done("Today is ${fmt.format(java.util.Date())}.")
        }
        if (matchesAny(q, "storage", "free space", "disk space", "how much space")) {
            return try {
                val stat = StatFs(Environment.getDataDirectory().path)
                val freeGb = stat.availableBytes / 1_073_741_824.0
                val totalGb = stat.totalBytes / 1_073_741_824.0
                Result.Done(
                    "Storage: ${String.format(Locale.US, "%.1f", freeGb)} GB free of " +
                        "${String.format(Locale.US, "%.0f", totalGb)} GB.",
                )
            } catch (t: Throwable) {
                Result.Done("I couldn't read the storage figures.")
            }
        }
        if (matchesAny(q, "device info", "about phone", "phone model", "android version", "what phone is this")) {
            return Result.Done(
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}).",
            )
        }
        if (matchesAny(q, "who are you", "what is your name", "your name")) {
            return Result.Done("I am JARVIS - Bonjee's assistant on this phone. Say \"what can you do\".")
        }
        return null
    }

    private fun isCharging(activity: Activity): Boolean = try {
        val intent = activity.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    } catch (t: Throwable) {
        false
    }

    // --------------------------------------------------------- notifications

    private fun notifications(activity: Activity, q: String): Result? {
        val asks = matchesAny(
            q, "read my notifications", "any notifications", "check notifications",
            "what notifications", "show notifications", "my notifications", "read notifications",
        )
        if (!asks) return null
        if (!NotificationListener.isEnabled(activity)) {
            return Result.NeedsSettings(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                "Switch JARVIS on in Notification access, then I can read them to you.",
            )
        }
        val recent = NotificationListener.recent(5)
        return if (recent.isEmpty()) {
            Result.Done("No notifications are showing right now.")
        } else {
            Result.Done("Latest notifications:\n" + recent.joinToString("\n") { "- $it" })
        }
    }

    // ------------------------------------------------------- contact search

    /**
     * "search contacts for John", "find Ada in contacts", "what is John's number".
     *
     * Opens the Contacts screen with the name already typed, where the user can
     * pick call / WhatsApp call / WhatsApp message / SMS / email without
     * remembering any command syntax.
     */
    private fun contactSearch(activity: Activity, text: String, q: String): Result? {
        val lower = text.lowercase(Locale.getDefault())
        var name: String? = null

        Regex("""^(?:search|find|look\s*up)\s+(?:for\s+)?(.+?)\s+(?:in|on|from)\s+(?:my\s+)?contacts?$""")
            .find(lower)?.let { name = it.groupValues[1] }

        if (name == null) {
            Regex("""^(?:search\s+)?(?:my\s+)?contacts?\s+(?:for|named?)\s+(.+)$""")
                .find(lower)?.let { name = it.groupValues[1] }
        }

        if (name == null) {
            Regex("""^(?:find|look\s*up|search)\s+(.+?)(?:'s|s)?\s+(?:phone\s+|mobile\s+)?number$""")
                .find(lower)?.let { name = it.groupValues[1] }
        }

        if (name == null) {
            Regex("""^what(?:'s|\s+is)\s+(.+?)(?:'s|s)?\s+(?:phone\s+|mobile\s+)?number\??$""")
                .find(lower)?.let { name = it.groupValues[1] }
        }

        if (name == null) {
            val opens = listOf(
                "search contacts", "contact search", "open contacts search",
                "find a contact", "search my contacts", "contacts search",
            )
            if (opens.any { q == it || q.startsWith("$it ") }) name = ""
        }

        if (name == null) return null

        val clean = name!!.trim().removeSuffix("?").trim()
        val intent = Intent(activity, ContactsActivity::class.java)
        if (clean.isNotEmpty()) intent.putExtra(ContactsActivity.EXTRA_QUERY, clean)
        return Result.Open(
            intent,
            if (clean.isEmpty()) "Opening contacts. Type a name, then choose call, WhatsApp or SMS."
            else "Searching your contacts for \"$clean\".",
        )
    }

    // ---------------------------------------------------- search / navigate

    private fun searchAndNavigate(activity: Activity, text: String, q: String): Result? {
        // YouTube
        val youtube = Regex("""^(?:search\s+)?youtube\s+(?:for\s+)?(.+)$""").find(q)
            ?: Regex("""^search\s+youtube\s+for\s+(.+)$""").find(q)
            ?: Regex("""^(?:play|watch)\s+(.+?)\s+on\s+youtube$""").find(text.lowercase(Locale.getDefault()))
        if (youtube != null) {
            val query = youtube.groupValues[1].trim()
            if (query.isNotEmpty()) {
                val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
                return if (tryStart(activity, Intent(Intent.ACTION_VIEW, uri))) {
                    Result.Done("Searching YouTube for \"$query\".")
                } else {
                    Result.Done("No browser or YouTube app handled that search.")
                }
            }
        }

        // Spotify
        val spotify = Regex("""^(?:play\s+(.+?)\s+on\s+spotify|spotify\s+(.+))$""")
            .find(text.lowercase(Locale.getDefault()))
        if (spotify != null) {
            val query = (spotify.groupValues[1].ifBlank { spotify.groupValues[2] }).trim()
            if (query.isNotEmpty()) {
                val app = tryStart(activity, Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))))
                val web = app || tryStart(
                    activity,
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + Uri.encode(query))),
                )
                if (web) return Result.Done("Searching Spotify for \"$query\".")
            }
        }

        // Maps
        val maps = Regex(
            """^(?:navigate\s+to|directions\s+to|take\s+me\s+to|map\s+|where\s+is)\s*(.+)$""",
        ).find(text.lowercase(Locale.getDefault()))
        if (maps != null) {
            val place = maps.groupValues[1].trim().removeSuffix("?")
            if (place.isNotEmpty() && !hasWord(place, "settings")) {
                val uri = Uri.parse("geo:0,0?q=" + Uri.encode(place))
                return if (tryStart(activity, Intent(Intent.ACTION_VIEW, uri))) {
                    Result.Done("Opening directions to \"$place\".")
                } else {
                    Result.Done("No maps app handled that.")
                }
            }
        }

        // Google / web
        val search = Regex(
            """^(?:search\s+for|search\s+google\s+for|google|look\s+up|search\s+the\s+web\s+for|web\s+search)\s+(.+)$""",
        ).find(text.lowercase(Locale.getDefault()))
        if (search != null) {
            val query = search.groupValues[1].trim()
            val archiveWords = listOf("my messages", "notification", "whatsapp", "chat", "archive", "i sent", "i said")
            if (query.isNotEmpty() && archiveWords.none { query.contains(it) }) {
                val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
                return if (tryStart(activity, Intent(Intent.ACTION_VIEW, uri))) {
                    Result.Done("Searching the web for \"$query\".")
                } else {
                    Result.Done("No browser handled that search.")
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------ open apps

    private fun openApp(activity: Activity, text: String, q: String): Result? {
        if (matchesAny(q, "take a photo", "take a picture", "take photo", "open camera", "take a selfie")) {
            val camera = resolveApp(activity, "camera")
            if (camera != null && launch(activity, camera)) return Result.Done("Opening the camera.")
        }

        val verbs = listOf(
            "open up", "switch to", "bring up", "go to", "launch", "start", "open", "run",
        )
        val verb = verbs.firstOrNull { q == it || q.startsWith("$it ") } ?: return null
        if (q == verb) return null

        var target = text.substring(verb.length).trim()
        target = target.removeSuffix(" please").trim()
        target = target.removePrefix("the ").trim()
        target = target.removeSuffix(" app").trim()
        if (target.isEmpty()) return null

        // A URL, not an app.
        if (looksLikeUrl(target)) {
            val url = if (target.startsWith("http")) target else "https://$target"
            return if (tryStart(activity, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
                Result.Done("Opening $target.")
            } else {
                Result.Done("No browser handled that link.")
            }
        }

        val app = resolveApp(activity, target)
        if (app != null) {
            return if (launch(activity, app)) {
                Result.Done("Opening ${app.label}.")
            } else {
                Result.Done("${app.label} refused to open.")
            }
        }

        val suggestion = installedApps(activity)
            .map { it.label }
            .filter { normalise(it).contains(normalise(target).take(3)) }
            .take(3)
        val hint = if (suggestion.isEmpty()) {
            ""
        } else {
            " Did you mean ${suggestion.joinToString(" or ")}?"
        }
        return Result.Done(
            "I couldn't find an app called \"$target\" on this phone.$hint " +
                "Say \"what can you do\" to hear what I can run.",
        )
    }

    private fun launch(activity: Activity, app: AppEntry): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return tryStart(activity, intent)
    }

    private fun looksLikeUrl(target: String): Boolean {
        if (target.contains(" ")) return false
        if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("www.")) return true
        return Regex("""^[\w-]+(\.[\w-]+)+(/\S*)?$""").matches(target) &&
            listOf(".com", ".ng", ".org", ".net", ".io", ".gov", ".edu", ".co", ".app", ".dev")
                .any { target.contains(it) }
    }

    // -------------------------------------------------------- app resolving

    private data class AppEntry(val label: String, val pkg: String, val component: ComponentName)

    private var cachedApps: List<AppEntry>? = null
    private var cachedAt = 0L

    private fun installedApps(context: Context): List<AppEntry> {
        val now = System.currentTimeMillis()
        cachedApps?.let { if (now - cachedAt < 60_000) return it }

        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(launcher, 0)
        val seen = HashSet<String>()
        val apps = ArrayList<AppEntry>()
        for (info in resolved) {
            val activityInfo = info.activityInfo ?: continue
            val pkg = activityInfo.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (t: Throwable) {
                pkg
            }
            apps.add(AppEntry(label, pkg, ComponentName(pkg, activityInfo.name)))
        }
        cachedApps = apps
        cachedAt = now
        return apps
    }

    private fun resolveApp(context: Context, target: String): AppEntry? {
        val apps = installedApps(context)
        if (apps.isEmpty()) return null
        val wanted = normalise(target)
        if (wanted.isEmpty()) return null

        for ((alias, hints) in ALIASES) {
            val aliasMatches = wanted == alias ||
                (alias.length >= 3 && wanted.contains(alias)) ||
                (alias.length >= 4 && alias.contains(wanted) && wanted.length >= 3)
            if (!aliasMatches) continue
            for (hint in hints) {
                apps.firstOrNull {
                    normalise(it.label).contains(hint) || it.pkg.lowercase(Locale.ROOT).contains(hint)
                }?.let { return it }
            }
        }

        var best: AppEntry? = null
        var bestScore = 0
        for (app in apps) {
            val label = normalise(app.label)
            val pkg = app.pkg.lowercase(Locale.ROOT)
            val score = when {
                label == wanted -> 100
                pkg == wanted || pkg.endsWith(".$wanted") -> 95
                label.startsWith(wanted) -> 85
                label.contains(wanted) -> 70
                wanted.contains(label) && label.length >= 4 -> 60
                else -> tokenScore(wanted, label)
            }
            if (score > bestScore) {
                bestScore = score
                best = app
            }
        }
        return if (bestScore >= 55) best else null
    }

    private fun tokenScore(wanted: String, label: String): Int {
        val words = wanted.split(' ').filter { it.length >= 3 }
        if (words.isEmpty()) return 0
        return if (words.all { label.contains(it) }) 58 else 0
    }

    // ------------------------------------------------------------- contacts

    private fun contactNumber(context: Context, name: String): String? = try {
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(name),
        )
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            var found: String? = null
            if (cursor.moveToFirst()) found = cursor.getString(0)
            found
        }
    } catch (t: Throwable) {
        null
    }

    private fun contactEmail(context: Context, name: String): String? = try {
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,
            Uri.encode(name),
        )
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            null,
            null,
            null,
        )?.use { cursor ->
            var found: String? = null
            if (cursor.moveToFirst()) found = cursor.getString(0)
            found
        }
    } catch (t: Throwable) {
        null
    }

    // -------------------------------------------------------------- helpers

    private val PANELS = listOf(
        Panel(
            Regex("""\b(wi-?fi|internet)\b"""),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "android.settings.panel.action.INTERNET_CONNECTIVITY"
            else Settings.ACTION_WIFI_SETTINGS,
            Settings.ACTION_WIFI_SETTINGS,
            "Wi-Fi",
            honest = true,
        ),
        Panel(
            Regex("""\bbluetooth\b"""),
            Settings.ACTION_BLUETOOTH_SETTINGS, null, "Bluetooth", honest = true,
        ),
        Panel(
            Regex("""\b(airplane|aeroplane|flight)\s*mode\b"""),
            Settings.ACTION_AIRPLANE_MODE_SETTINGS, null, "Airplane mode", honest = true,
        ),
        Panel(
            Regex("""\b(mobile data|cellular data|data roaming)\b"""),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Settings.ACTION_DATA_USAGE_SETTINGS
            else Settings.ACTION_WIRELESS_SETTINGS,
            Settings.ACTION_WIRELESS_SETTINGS, "Mobile data", honest = true,
        ),
        Panel(
            Regex("""\b(hotspot|tethering|share internet)\b"""),
            Settings.ACTION_WIRELESS_SETTINGS, null, "Hotspot", honest = true,
        ),
        Panel(
            Regex("""\b(location|gps)\b"""),
            Settings.ACTION_LOCATION_SOURCE_SETTINGS, null, "Location", honest = true,
        ),
        Panel(
            Regex("""\bbattery saver\b"""),
            Settings.ACTION_BATTERY_SAVER_SETTINGS, null, "Battery saver", honest = false,
        ),
        Panel(
            Regex("""\b(do not disturb|dnd|zen mode)\b"""),
            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, null, "Do Not Disturb", honest = false,
        ),
        Panel(
            Regex("""\bnfc\b"""),
            Settings.ACTION_NFC_SETTINGS, null, "NFC", honest = true,
        ),
        Panel(
            Regex("""\b(sound|ringtone|vibration) settings\b"""),
            Settings.ACTION_SOUND_SETTINGS, null, "Sound", honest = false,
        ),
        Panel(
            Regex("""\bdisplay settings\b"""),
            Settings.ACTION_DISPLAY_SETTINGS, null, "Display", honest = false,
        ),
        Panel(
            Regex("""\bstorage settings\b"""),
            Settings.ACTION_INTERNAL_STORAGE_SETTINGS, null, "Storage", honest = false,
        ),
        Panel(
            Regex("""\b(date|time) settings\b"""),
            Settings.ACTION_DATE_SETTINGS, null, "Date and time", honest = false,
        ),
        Panel(
            Regex("""\blanguage\b"""),
            Settings.ACTION_LOCALE_SETTINGS, null, "Language", honest = false,
        ),
        Panel(
            Regex("""\bdeveloper (options|settings)\b"""),
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, null, "Developer options", honest = false,
        ),
        Panel(
            Regex("""\bnotification access\b"""),
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, null, "Notification access", honest = false,
        ),
        Panel(
            Regex("""\baccessibility\b"""),
            Settings.ACTION_ACCESSIBILITY_SETTINGS, null, "Accessibility", honest = false,
        ),
        Panel(
            Regex("""\b(assistant|digital assistant|default assistant) settings\b"""),
            Settings.ACTION_VOICE_INPUT_SETTINGS, null, "Assistant", honest = false,
        ),
        Panel(
            Regex("""\bmanage apps\b|\bapps? settings\b"""),
            Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, null, "Apps", honest = false,
        ),
        Panel(
            Regex("""\bsecurity settings\b"""),
            Settings.ACTION_SECURITY_SETTINGS, null, "Security", honest = false,
        ),
        Panel(
            Regex("""\bprivacy\b"""),
            Settings.ACTION_PRIVACY_SETTINGS, null, "Privacy", honest = false,
        ),
        Panel(
            Regex("""\b(settings|system settings)\b"""),
            Settings.ACTION_SETTINGS, null, "system", honest = false,
        ),
    )

    private data class Panel(
        val pattern: Regex,
        val action: String,
        val fallback: String?,
        val label: String,
        val honest: Boolean,
    )

    private val ALIASES: Map<String, List<String>> = mapOf(
        "whatsapp business" to listOf("whatsapp business", "w4b"),
        "whatsapp" to listOf("whatsapp"),
        "wa" to listOf("whatsapp"),
        "gmail" to listOf("gmail"),
        "mail" to listOf("gmail", "mail"),
        "email app" to listOf("gmail", "mail"),
        "contacts" to listOf("contacts", "phonebook"),
        "contact" to listOf("contacts"),
        "phonebook" to listOf("contacts"),
        "camera" to listOf("camera"),
        "settings" to listOf("settings"),
        "chrome" to listOf("chrome"),
        "browser" to listOf("chrome", "browser"),
        "youtube" to listOf("youtube"),
        "yt" to listOf("youtube"),
        "maps" to listOf("maps"),
        "google maps" to listOf("maps"),
        "play store" to listOf("play store", "vending"),
        "playstore" to listOf("play store", "vending"),
        "google play" to listOf("play store", "vending"),
        "messages" to listOf("messages", "messaging"),
        "sms" to listOf("messages", "messaging"),
        "text messages" to listOf("messages", "messaging"),
        "phone" to listOf("phone", "dialer"),
        "dialer" to listOf("dialer", "phone"),
        "calculator" to listOf("calculator"),
        "calc" to listOf("calculator"),
        "clock" to listOf("clock"),
        "alarm clock" to listOf("clock"),
        "files" to listOf("files", "file manager", "documents"),
        "file manager" to listOf("files", "file manager", "documents"),
        "my files" to listOf("files", "file manager"),
        "gallery" to listOf("gallery"),
        "photos" to listOf("photos", "gallery"),
        "music" to listOf("music", "player"),
        "music player" to listOf("music", "player"),
        "spotify" to listOf("spotify"),
        "telegram" to listOf("telegram"),
        "instagram" to listOf("instagram"),
        "insta" to listOf("instagram"),
        "ig" to listOf("instagram"),
        "facebook" to listOf("facebook"),
        "fb" to listOf("facebook"),
        "tiktok" to listOf("tiktok", "musically"),
        "twitter" to listOf("twitter"),
        "netflix" to listOf("netflix"),
        "linkedin" to listOf("linkedin"),
        "zoom" to listOf("zoom"),
        "teams" to listOf("teams"),
        "calendar" to listOf("calendar"),
        "notes" to listOf("notes", "keep"),
        "keep" to listOf("keep"),
        "drive" to listOf("drive"),
        "google drive" to listOf("drive"),
        "meet" to listOf("meet"),
        "google meet" to listOf("meet"),
        "opera" to listOf("opera"),
        "opera mini" to listOf("opera"),
        "uber" to listOf("uber"),
        "bolt" to listOf("bolt"),
        "bank" to listOf("bank"),
        "opay" to listOf("opay"),
        "palmpay" to listOf("palmpay"),
        "moniepoint" to listOf("moniepoint"),
        "kuda" to listOf("kuda"),
        "gtworld" to listOf("gtworld", "gtbank"),
    )

    private fun tryStart(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (t: Throwable) {
        false
    }

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun normalise(text: String): String = text
        .lowercase(Locale.getDefault())
        .replace(Regex("""[^a-z0-9% .:@/+&-]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun matchesAny(q: String, vararg phrases: String): Boolean =
        phrases.any { q == it || q.startsWith("$it ") }

    private fun hasWord(q: String, word: String): Boolean =
        Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(q)

    private fun digitsOf(text: String): String = text.filter { it.isDigit() || it == '+' }

    private fun percentIn(text: String): Int? {
        val match = Regex("""(\d{1,3})\s*(?:%|percent|per cent)?""").find(text) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        return value.takeIf { it in 0..100 }
    }

    /** Parses "6:30 am", "18:45" or "7" out of a sentence. */
    private fun parseClock(text: String): Pair<Int, Int>? {
        val match = Regex("""\b(\d{1,2})(?::(\d{1,2}))?\s*(am|pm)?\b""").find(text) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val meridiem = match.groupValues[3]
        if (hour > 23 || minute > 59) return null
        if (meridiem == "pm" && hour in 1..11) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        return hour to minute
    }

    private fun clockText(hour: Int, minute: Int): String {
        val suffix = if (hour < 12) "am" else "pm"
        val twelve = when {
            hour % 12 == 0 -> 12
            else -> hour % 12
        }
        return String.format(Locale.US, "%d:%02d %s", twelve, minute, suffix)
    }

    private fun describeSeconds(total: Int): String {
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours hour(s) $minutes minute(s)"
            hours > 0 -> "$hours hour(s)"
            minutes > 0 -> "$minutes minute(s)"
            else -> "$seconds second(s)"
        }
    }

    private const val PREFS = "bonjee_device_actions"
    private const val KEY_ASKED_WRITE = "asked_write_settings"
}
