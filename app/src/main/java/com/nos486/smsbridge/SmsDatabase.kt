package com.nos486.smsbridge

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SmsRecord(
    val id: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val simSlot: Int?,
    val status: Int,
    val attempts: Int,
    val lastError: String?,
)

/**
 * Durable outbox. Every SMS is written here *before* any network activity, so nothing is lost
 * if the process dies, the phone reboots, or the internet is down for days.
 */
class SmsDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "smsbridge.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                sender TEXT NOT NULL,
                body TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                sim_slot INTEGER,
                status INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                last_attempt INTEGER,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_status ON messages(status, created_at)")
        db.execSQL("CREATE INDEX idx_dedup ON messages(sender, received_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(id: String, sender: String, body: String, receivedAt: Long, simSlot: Int?) {
        val cv = ContentValues().apply {
            put("id", id)
            put("sender", sender)
            put("body", body)
            put("received_at", receivedAt)
            if (simSlot != null) put("sim_slot", simSlot) else putNull("sim_slot")
            put("status", STATUS_PENDING)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun exists(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM messages WHERE id = ? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    fun inTransaction(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** True if an identical message (same sender + body) was already stored within [windowMs] of [receivedAt]. */
    fun existsSimilar(sender: String, body: String, receivedAt: Long, windowMs: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE sender = ? AND body = ? AND received_at BETWEEN ? AND ? LIMIT 1",
            arrayOf(sender, body, (receivedAt - windowMs).toString(), (receivedAt + windowMs).toString())
        ).use { return it.moveToFirst() }
    }

    fun nextPending(limit: Int): List<SmsRecord> = query(
        "SELECT * FROM messages WHERE status = $STATUS_PENDING ORDER BY created_at ASC LIMIT $limit"
    )

    fun recent(limit: Int): List<SmsRecord> = query(
        "SELECT * FROM messages ORDER BY created_at DESC LIMIT $limit"
    )

    fun markSent(id: String) = update(id, STATUS_SENT, null)

    fun markFailed(id: String, error: String) = update(id, STATUS_FAILED, error)

    fun markAttempt(id: String, error: String) = update(id, STATUS_PENDING, error)

    fun retryFailed(): Int {
        val cv = ContentValues().apply { put("status", STATUS_PENDING) }
        return writableDatabase.update("messages", cv, "status = ?", arrayOf(STATUS_FAILED.toString()))
    }

    fun count(status: Int): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE status = ?", arrayOf(status.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Keep the DB small: drop delivered messages older than [maxAgeMs]. Pending ones are never deleted. */
    fun purgeSent(maxAgeMs: Long) {
        writableDatabase.delete(
            "messages", "status = ? AND created_at < ?",
            arrayOf(STATUS_SENT.toString(), (System.currentTimeMillis() - maxAgeMs).toString())
        )
    }

    private fun update(id: String, status: Int, error: String?) {
        writableDatabase.execSQL(
            "UPDATE messages SET status = ?, last_error = ?, attempts = attempts + 1, last_attempt = ? WHERE id = ?",
            arrayOf<Any?>(status, error, System.currentTimeMillis(), id)
        )
    }

    private fun query(sql: String): List<SmsRecord> =
        readableDatabase.rawQuery(sql, null).use { c ->
            val out = ArrayList<SmsRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            out
        }

    private fun Cursor.toRecord(): SmsRecord {
        val slotIdx = getColumnIndexOrThrow("sim_slot")
        val errIdx = getColumnIndexOrThrow("last_error")
        return SmsRecord(
            id = getString(getColumnIndexOrThrow("id")),
            sender = getString(getColumnIndexOrThrow("sender")),
            body = getString(getColumnIndexOrThrow("body")),
            receivedAt = getLong(getColumnIndexOrThrow("received_at")),
            simSlot = if (isNull(slotIdx)) null else getInt(slotIdx),
            status = getInt(getColumnIndexOrThrow("status")),
            attempts = getInt(getColumnIndexOrThrow("attempts")),
            lastError = if (isNull(errIdx)) null else getString(errIdx),
        )
    }

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_FAILED = 2

        @Volatile private var instance: SmsDatabase? = null

        fun get(context: Context): SmsDatabase =
            instance ?: synchronized(this) { instance ?: SmsDatabase(context).also { instance = it } }
    }
}
