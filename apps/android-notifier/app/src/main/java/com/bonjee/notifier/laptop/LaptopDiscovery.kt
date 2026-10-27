package com.bonjee.notifier.laptop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Finds the laptop agent on the phone's own Wi-Fi.
 *
 * Typing the laptop's IP by hand is the step people get wrong, so the app can
 * probe the /24 it is sitting on for `GET /ping`. Only a machine answering with
 * our agent's name is reported - an open port alone is not enough.
 *
 * This is a LAN scan of the user's own network, started only when they tap
 * DISCOVER, and it stops after one pass. Nothing is sent anywhere but the
 * addresses probed, and no token is used.
 */
class LaptopDiscovery {

    data class Found(val address: String, val port: Int, val name: String) {
        val url: String get() = "http://$address:$port"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(350, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .callTimeout(900, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** The /24 prefixes this phone currently has an IPv4 address on. */
    suspend fun subnets(): List<String> = withContext(Dispatchers.IO) {
        val out = LinkedHashSet<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                for (address in network.inetAddresses) {
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val parts = address.hostAddress?.split(".") ?: continue
                    if (parts.size != 4) continue
                    val prefix = parts.take(3).joinToString(".")
                    if (prefix.startsWith("169.254")) continue
                    out.add(prefix)
                }
            }
        } catch (t: Throwable) {
            // No address list (airplane mode, permission quirk): caller reports it.
        }
        out.toList()
    }

    suspend fun scan(port: Int, extraHosts: List<String> = emptyList()): List<Found> {
        val prefixes = subnets()
        if (prefixes.isEmpty() && extraHosts.isEmpty()) return emptyList()

        val targets = LinkedHashSet<String>()
        prefixes.forEach { prefix -> (1..254).forEach { targets.add("$prefix.$it") } }
        extraHosts.forEach { host ->
            val clean = host.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
            if (clean.isNotBlank()) targets.add(clean)
        }
        targets.remove("")
        if (targets.isEmpty()) return emptyList()

        val gate = Semaphore(48)
        val found = coroutineScope {
            targets.map { host ->
                async(Dispatchers.IO) {
                    gate.withPermit { probe(host, port) }
                }
            }.awaitAll()
        }
        return found.filterNotNull().sortedBy { it.address }
    }

    private fun probe(host: String, port: Int): Found? {
        val url = "http://$host:$port/ping"
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(body)
                } catch (t: Throwable) {
                    return null
                }
                // Only our agent answers with this name, so a random web server
                // on port 8787 is not offered as "your laptop".
                val name = json.optString("name")
                if (!name.lowercase().contains("jarvis")) return null
                Found(host, port, name)
            }
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        /** The agent's default port; the user can change it in config.json. */
        const val DEFAULT_PORT = 8787
    }
}
