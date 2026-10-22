package com.bonjee.notifier

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drains the durable delivery queue to n8n.
 *
 * Guarantees:
 *  - at most one drain runs at a time (Mutex)
 *  - a payload is only deleted after a 2xx response
 *  - failures are retried with a capped attempt count
 */
object QueueDrainer {

    private const val TAG = "QueueDrainer"
    private const val MAX_ATTEMPTS = 8
    private const val BATCH = 25

    private val mutex = Mutex()

    /** Returns the number of payloads successfully delivered. */
    suspend fun drain(context: Context): Int = mutex.withLock {
        val app = context.applicationContext
        val prefs = Prefs.get(app)
        val queue = DeliveryQueue(app)
        val url = prefs.notificationUrl()
        val headers = prefs.headers()

        var delivered = 0
        val items = queue.nextBatch(BATCH)

        for (item in items) {
            if (item.attempts >= MAX_ATTEMPTS) {
                Log.w(TAG, "Dropping payload ${item.id} after ${item.attempts} attempts")
                queue.delete(item.id)
                continue
            }
            val result = ApiClient.shared.postJson(item.url, item.payload, headers)
            if (result.ok) {
                queue.delete(item.id)
                delivered++
                prefs.lastDeliveryOk = true
                prefs.lastDeliveryAt = System.currentTimeMillis()
                prefs.lastDeliveryMessage = "HTTP ${result.code}"
            } else {
                queue.markFailed(item.id, result.summary)
                prefs.lastDeliveryOk = false
                prefs.lastDeliveryAt = System.currentTimeMillis()
                prefs.lastDeliveryMessage = result.summary
                Log.w(TAG, "Delivery failed for ${item.id}: ${result.summary}")
                // Network-level failure: stop early, retry on the next cycle.
                if (result.code == 0) break
            }
        }
        Log.d(TAG, "Drain finished, delivered=$delivered, remaining=${queue.count()}")
        delivered
    }
}
