package com.bonjee.notifier

import android.content.Context
import android.content.SharedPreferences
import com.bonjee.notifier.whatsapp.PhoneNumber

/**
 * Central place for every user-configurable value.
 * Nothing secret is hardcoded: the n8n base URL, webhook paths and the optional
 * auth header are all supplied by the user at runtime and stored in the app's
 * private SharedPreferences.
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var n8nBaseUrl: String
        get() = sp.getString(KEY_BASE_URL, BuildConfig.DEFAULT_N8N_BASE_URL) ?: BuildConfig.DEFAULT_N8N_BASE_URL
        set(value) {
            sp.edit().putString(KEY_BASE_URL, cleanBase(value)).apply()
        }

    var notificationPath: String
        get() = sp.getString(KEY_NOTIF_PATH, BuildConfig.DEFAULT_NOTIFICATION_PATH)
            ?: BuildConfig.DEFAULT_NOTIFICATION_PATH
        set(value) {
            sp.edit().putString(KEY_NOTIF_PATH, normalisePath(value)).apply()
        }

    var askPath: String
        get() = sp.getString(KEY_ASK_PATH, BuildConfig.DEFAULT_ASK_PATH) ?: BuildConfig.DEFAULT_ASK_PATH
        set(value) {
            sp.edit().putString(KEY_ASK_PATH, normalisePath(value)).apply()
        }

    var healthPath: String
        get() = sp.getString(KEY_HEALTH_PATH, DEFAULT_HEALTH_PATH) ?: DEFAULT_HEALTH_PATH
        set(value) {
            sp.edit().putString(KEY_HEALTH_PATH, normalisePath(value)).apply()
        }

    /**
     * Base URL of the Bonjee OS web dashboard. Every drawer module (Inbox, Tasks,
     * Calendar, Automations, Memory, Settings, Devices) opens this same deployment
     * inside a WebView so the app and the web always read the same database.
     */
    var dashboardBaseUrl: String
        get() = sp.getString(KEY_DASHBOARD_URL, BuildConfig.DEFAULT_DASHBOARD_URL)
            ?: BuildConfig.DEFAULT_DASHBOARD_URL
        set(value) {
            sp.edit()
                .putString(KEY_DASHBOARD_URL, cleanBase(value, BuildConfig.DEFAULT_DASHBOARD_URL))
                .apply()
        }

    fun dashboardUrl(path: String = "/"): String =
        dashboardBaseUrl.trimEnd('/') + normalisePath(path)

    /** Optional bearer/header token sent on every outbound request. */
    var authToken: String
        get() = sp.getString(KEY_AUTH_TOKEN, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_AUTH_TOKEN, value.trim()).apply()
        }

    var authHeaderName: String
        get() = sp.getString(KEY_AUTH_HEADER, DEFAULT_AUTH_HEADER) ?: DEFAULT_AUTH_HEADER
        set(value) {
            val name = value.trim().ifEmpty { DEFAULT_AUTH_HEADER }
            sp.edit().putString(KEY_AUTH_HEADER, name).apply()
        }

    var captureEnabled: Boolean
        get() = sp.getBoolean(KEY_CAPTURE_ENABLED, true)
        set(value) {
            sp.edit().putBoolean(KEY_CAPTURE_ENABLED, value).apply()
        }

    var lastDeliveryOk: Boolean
        get() = sp.getBoolean(KEY_LAST_OK, false)
        set(value) {
            sp.edit().putBoolean(KEY_LAST_OK, value).apply()
        }

    var lastDeliveryAt: Long
        get() = sp.getLong(KEY_LAST_AT, 0L)
        set(value) {
            sp.edit().putLong(KEY_LAST_AT, value).apply()
        }

    var lastDeliveryMessage: String
        get() = sp.getString(KEY_LAST_MSG, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_LAST_MSG, value).apply()
        }

    /** Comma separated list of app packages that must never be captured. */
    var blockedPackages: Set<String>
        get() = (sp.getString(KEY_BLOCKLIST, "") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        set(value) {
            sp.edit().putString(KEY_BLOCKLIST, value.joinToString(",")).apply()
        }

    // ------------------------------------------------ WhatsApp / message rules

    /**
     * The ONE app-facing entry point. Every action the app asks of the workflow
     * goes to this path - no per-action webhooks. The Evolution API inbound
     * webhook is a separate, server-side concern and is never called from here.
     */
    var appApiPath: String
        get() = sp.getString(KEY_APP_API_PATH, DEFAULT_APP_API_PATH) ?: DEFAULT_APP_API_PATH
        set(value) {
            sp.edit().putString(KEY_APP_API_PATH, normalisePath(value)).apply()
        }

    fun appApiUrl(): String = n8nBaseUrl.trimEnd('/') + appApiPath

    /** Country code used to turn local numbers into E.164 (default Nigeria). */
    var defaultCountryCode: String
        get() = (sp.getString(KEY_COUNTRY_CODE, PhoneNumber.DEFAULT_COUNTRY_CODE) ?: PhoneNumber.DEFAULT_COUNTRY_CODE)
            .filter { it.isDigit() }
            .ifEmpty { PhoneNumber.DEFAULT_COUNTRY_CODE }
        set(value) {
            val clean = value.filter { it.isDigit() }.ifEmpty { PhoneNumber.DEFAULT_COUNTRY_CODE }
            sp.edit().putString(KEY_COUNTRY_CODE, clean).apply()
        }

    /**
     * Stable identifier for this install. Generated once, never derived from a
     * hardware id. The backend must still authenticate the request; this value
     * is only a correlation key, not proof of identity.
     */
    var deviceId: String
        get() {
            val existing = sp.getString(KEY_DEVICE_ID, "") ?: ""
            if (existing.isNotBlank()) return existing
            val generated = java.util.UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }
        set(value) {
            sp.edit().putString(KEY_DEVICE_ID, value.trim()).apply()
        }

    /** Human label sent at registration; helps the user pick the device later. */
    val deviceName: String
        get() = (sp.getString(KEY_DEVICE_NAME, "") ?: "").ifBlank {
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        }

    var deviceRegisteredAt: Long
        get() = sp.getLong(KEY_DEVICE_REGISTERED_AT, 0L)
        set(value) {
            sp.edit().putLong(KEY_DEVICE_REGISTERED_AT, value).apply()
        }

    var whatsappCaptureEnabled: Boolean
        get() = sp.getBoolean(KEY_WA_CAPTURE, true)
        set(value) {
            sp.edit().putBoolean(KEY_WA_CAPTURE, value).apply()
        }

    var announceImportant: Boolean
        get() = sp.getBoolean(KEY_ANNOUNCE, true)
        set(value) {
            sp.edit().putBoolean(KEY_ANNOUNCE, value).apply()
        }

    /** Read the message body aloud, not only the sender. */
    var announceBody: Boolean
        get() = sp.getBoolean(KEY_ANNOUNCE_BODY, true)
        set(value) {
            sp.edit().putBoolean(KEY_ANNOUNCE_BODY, value).apply()
        }

    /** Allow body text over the loudspeaker. Off = sender only in public. */
    var announceBodyOnSpeaker: Boolean
        get() = sp.getBoolean(KEY_ANNOUNCE_SPEAKER, false)
        set(value) {
            sp.edit().putBoolean(KEY_ANNOUNCE_SPEAKER, value).apply()
        }

    /** Speak under DND/silent only when a private output device is connected. */
    var earphoneException: Boolean
        get() = sp.getBoolean(KEY_EARPHONE_EXCEPTION, true)
        set(value) {
            sp.edit().putBoolean(KEY_EARPHONE_EXCEPTION, value).apply()
        }

    var quietHoursEnabled: Boolean
        get() = sp.getBoolean(KEY_QUIET_ENABLED, false)
        set(value) {
            sp.edit().putBoolean(KEY_QUIET_ENABLED, value).apply()
        }

    var quietStart: String
        get() = sp.getString(KEY_QUIET_START, "22:00") ?: "22:00"
        set(value) {
            sp.edit().putString(KEY_QUIET_START, value.trim()).apply()
        }

    var quietEnd: String
        get() = sp.getString(KEY_QUIET_END, "07:00") ?: "07:00"
        set(value) {
            sp.edit().putString(KEY_QUIET_END, value.trim()).apply()
        }

    /** Raise media volume for the announcement, then restore it. */
    var announceVolumeBoost: Boolean
        get() = sp.getBoolean(KEY_VOLUME_BOOST, false)
        set(value) {
            sp.edit().putBoolean(KEY_VOLUME_BOOST, value).apply()
        }

    /** Post a notification for Other/unknown senders as well as Important ones. */
    var notifyOtherSenders: Boolean
        get() = sp.getBoolean(KEY_NOTIFY_OTHERS, true)
        set(value) {
            sp.edit().putBoolean(KEY_NOTIFY_OTHERS, value).apply()
        }

    /** Show message text in our notification. Off = "New WhatsApp message". */
    var showMessageContent: Boolean
        get() = sp.getBoolean(KEY_SHOW_CONTENT, true)
        set(value) {
            sp.edit().putBoolean(KEY_SHOW_CONTENT, value).apply()
        }

    /** Master kill switch for every automatic reply. */
    var autoReplyGlobalEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_REPLY_GLOBAL, true)
        set(value) {
            sp.edit().putBoolean(KEY_AUTO_REPLY_GLOBAL, value).apply()
        }

    /**
     * Numbers the workflow has explicitly authorised for auto-reply. A local
     * toggle is not enough - this is written only from a backend response.
     */
    var serverAuthorizedNumbers: Set<String>
        get() = (sp.getString(KEY_SERVER_AUTHORIZED, "") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        set(value) {
            sp.edit().putString(KEY_SERVER_AUTHORIZED, value.joinToString(",")).apply()
        }

    fun isServerAuthorized(number: String): Boolean =
        serverAuthorizedNumbers.contains(PhoneNumber.normalize(number, defaultCountryCode))

    var lastMessageSyncAt: Long
        get() = sp.getLong(KEY_LAST_MESSAGE_SYNC, 0L)
        set(value) {
            sp.edit().putLong(KEY_LAST_MESSAGE_SYNC, value).apply()
        }

    fun headers(): Map<String, String> {
        val token = authToken
        return if (token.isEmpty()) emptyMap() else mapOf(authHeaderName to token)
    }

    fun notificationUrl(): String = n8nBaseUrl.trimEnd('/') + notificationPath

    fun askUrl(): String = n8nBaseUrl.trimEnd('/') + askPath

    fun healthUrl(): String = n8nBaseUrl.trimEnd('/') + healthPath

    companion object {
        private const val FILE = "bonjee_prefs"

        const val KEY_BASE_URL = "n8n_base_url"
        const val KEY_NOTIF_PATH = "notification_path"
        const val KEY_ASK_PATH = "ask_path"
        const val KEY_HEALTH_PATH = "health_path"
        const val KEY_DASHBOARD_URL = "dashboard_base_url"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_AUTH_HEADER = "auth_header_name"
        const val KEY_CAPTURE_ENABLED = "capture_enabled"
        const val KEY_BLOCKLIST = "app_blocklist"
        const val KEY_LAST_OK = "last_delivery_ok"
        const val KEY_LAST_AT = "last_delivery_at"
        const val KEY_LAST_MSG = "last_delivery_message"
        const val KEY_APP_API_PATH = "app_api_path"
        const val KEY_COUNTRY_CODE = "default_country_code"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_DEVICE_REGISTERED_AT = "device_registered_at"
        const val KEY_WA_CAPTURE = "whatsapp_capture_enabled"
        const val KEY_ANNOUNCE = "announce_important"
        const val KEY_ANNOUNCE_BODY = "announce_body"
        const val KEY_ANNOUNCE_SPEAKER = "announce_body_speaker"
        const val KEY_EARPHONE_EXCEPTION = "earphone_exception"
        const val KEY_QUIET_ENABLED = "quiet_hours_enabled"
        const val KEY_QUIET_START = "quiet_start"
        const val KEY_QUIET_END = "quiet_end"
        const val KEY_VOLUME_BOOST = "announce_volume_boost"
        const val KEY_NOTIFY_OTHERS = "notify_other_senders"
        const val KEY_SHOW_CONTENT = "show_message_content"
        const val KEY_AUTO_REPLY_GLOBAL = "auto_reply_global"
        const val KEY_SERVER_AUTHORIZED = "server_authorized_numbers"
        const val KEY_LAST_MESSAGE_SYNC = "last_message_sync"

        const val DEFAULT_HEALTH_PATH = "/webhook/health"
        const val DEFAULT_APP_API_PATH = "/webhook/app-api"
        const val DEFAULT_AUTH_HEADER = "Authorization"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }

        fun cleanBase(url: String, fallback: String = BuildConfig.DEFAULT_N8N_BASE_URL): String {
            var u = url.trim()
            if (u.isEmpty()) return fallback
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u.trimEnd('/')
        }

        fun normalisePath(path: String): String {
            var p = path.trim()
            if (p.isEmpty()) return "/"
            if (!p.startsWith("/")) p = "/$p"
            return p
        }
    }
}
