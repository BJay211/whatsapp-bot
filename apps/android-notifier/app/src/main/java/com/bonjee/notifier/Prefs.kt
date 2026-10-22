package com.bonjee.notifier

import android.content.Context
import android.content.SharedPreferences

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
        get() = sp.getString(KEY_BASE_URL, BuildConfig.DEFAULT_N8N_BASE_URL)!!
        set(v) = sp.edit().putString(KEY_BASE_URL, cleanBase(v)).apply()

    var notificationPath: String
        get() = sp.getString(KEY_NOTIF_PATH, BuildConfig.DEFAULT_NOTIFICATION_PATH)!!
        set(v) = sp.edit().putString(KEY_NOTIF_PATH, normalisePath(v)).apply()

    var askPath: String
        get() = sp.getString(KEY_ASK_PATH, BuildConfig.DEFAULT_ASK_PATH)!!
        set(v) = sp.edit().putString(KEY_ASK_PATH, normalisePath(v)).apply()

    var healthPath: String
        get() = sp.getString(KEY_HEALTH_PATH, DEFAULT_HEALTH_PATH)!!
        set(v) = sp.edit().putString(KEY_HEALTH_PATH, normalisePath(v)).apply()

    /** Optional bearer/header token sent on every outbound request. */
    var authToken: String
        get() = sp.getString(KEY_AUTH_TOKEN, "")!!
        set(v) = sp.edit().putString(KEY_AUTH_TOKEN, v.trim()).apply()

    var authHeaderName: String
        get() = sp.getString(KEY_AUTH_HEADER, DEFAULT_AUTH_HEADER)!!
        set(v) = sp.edit().putString(KEY_AUTH_HEADER, v.trim().ifEmpty { DEFAULT_AUTH_HEADER }).apply()

    var captureEnabled: Boolean
        get() = sp.getBoolean(KEY_CAPTURE_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_CAPTURE_ENABLED, v).apply()

    var lastDeliveryOk: Boolean
        get() = sp.getBoolean(KEY_LAST_OK, false)
        set(v) = sp.edit().putBoolean(KEY_LAST_OK, v).apply()

    var lastDeliveryAt: Long
        get() = sp.getLong(KEY_LAST_AT, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_AT, v).apply()

    var lastDeliveryMessage: String
        get() = sp.getString(KEY_LAST_MSG, "")!!
        set(v) = sp.edit().putString(KEY_LAST_MSG, v).apply()

    /** Comma separated list of app packages that must never be captured. */
    var blockedPackages: Set<String>
        get() = sp.getString(KEY_BLOCKLIST, "")!!
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        set(v) = sp.edit().putString(KEY_BLOCKLIST, v.joinToString(",")).apply()

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
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_AUTH_HEADER = "auth_header_name"
        const val KEY_CAPTURE_ENABLED = "capture_enabled"
        const val KEY_BLOCKLIST = "app_blocklist"
        const val KEY_LAST_OK = "last_delivery_ok"
        const val KEY_LAST_AT = "last_delivery_at"
        const val KEY_LAST_MSG = "last_delivery_message"

        const val DEFAULT_HEALTH_PATH = "/webhook/health"
        const val DEFAULT_AUTH_HEADER = "Authorization"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }

        fun cleanBase(url: String): String {
            var u = url.trim()
            if (u.isEmpty()) return BuildConfig.DEFAULT_N8N_BASE_URL
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
