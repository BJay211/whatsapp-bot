package com.bonjee.notifier

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable on-device queue for captured notifications.
 *
 * A notification listener callback must return fast and the network may be
 * unavailable, so every payload is written to SQLite first and delivered
 * later. This makes the pipeline survive process death, reboots and outages.
 */
class DeliveryQueue(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Item(
        val id: Long,
        val url: String,
        val payload: String,
        val attempts: Int,
        val createdAt: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_created ON $TABLE(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    @Synchronized
    fun enqueue(url: String, payload: String): Long {
        val values = ContentValues().apply {
            put("url", url)
            put("payload", payload)
            put("created_at", System.currentTimeMillis())
            put("attempts", 0)
        }
        return writableDatabase.insert(TABLE, null, values)
    }

    @Synchronized
    fun nextBatch(limit: Int): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.query(
            TABLE, null, null, null, null, null, "created_at ASC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Item(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        url = c.getString(c.getColumnIndexOrThrow("url")),
                        payload = c.getString(c.getColumnIndexOrThrow("payload")),
                        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun markFailed(id: Long, error: String) {
        val values = ContentValues().apply {
            put("attempts", attemptsOf(id) + 1)
            put("last_error", error.take(500))
        }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    private fun attemptsOf(id: Long): Int {
        readableDatabase.query(TABLE, arrayOf("attempts"), "id = ?", arrayOf(id.toString()), null, null, null)
            .use { c ->
                return if (c.moveToFirst()) c.getInt(0) else 0
            }
    }

    @Synchronized
    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }

    companion object {
        private const val DB_NAME = "bonjee_queue.db"
        private const val DB_VERSION = 1
        private const val TABLE = "delivery_queue"
    }
}
