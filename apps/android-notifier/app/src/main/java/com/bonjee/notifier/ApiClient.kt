package com.bonjee.notifier

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Thin OkHttp wrapper. Deliberately tiny so it is easy to audit:
 * nothing is sent anywhere except the base URL the user configured.
 */
class ApiClient {

    data class ApiResult(
        val ok: Boolean,
        val code: Int,
        val body: String,
        val error: String?
    ) {
        val summary: String
            get() = when {
                error != null -> "Network error: $error"
                ok -> "HTTP $code"
                else -> "HTTP $code - ${body.take(300)}"
            }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun postJson(url: String, payload: String, headers: Map<String, String>): ApiResult {
        return try {
            val body = payload.toRequestBody(JSON)
            val builder = Request.Builder().url(url).post(body)
            headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                ApiResult(response.isSuccessful, response.code, response.body?.string().orEmpty(), null)
            }
        } catch (e: Exception) {
            ApiResult(false, 0, "", e.message ?: e.javaClass.simpleName)
        }
    }

    fun get(url: String, headers: Map<String, String>): ApiResult {
        return try {
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                ApiResult(response.isSuccessful, response.code, response.body?.string().orEmpty(), null)
            }
        } catch (e: Exception) {
            ApiResult(false, 0, "", e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val shared = ApiClient()
    }
}
