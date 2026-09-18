package com.spamblocker.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Storage, rewritten.
 *
 * The bug that matters: the original called `db.close()` at the end of every
 * single operation —
 *
 *     val db = writableDatabase
 *     db.insert(...)
 *     db.close()          // <-- here
 *
 * SQLiteOpenHelper hands out ONE shared SQLiteDatabase. Three components use it
 * concurrently here: the UI, the call screening service, and the notification
 * listener. When the UI finishes a read and closes the database while the
 * screening service is mid-query, the screening service gets
 * "IllegalStateException: attempt to re-open an already-closed object" or a
 * dead cursor. That is a genuine, intermittent, timing-dependent crash, and it
 * takes the call screening path down with it — which is exactly the symptom
 * "it works, then it stops working."
 *
 * The fix is the standard one: a process-wide singleton, and never close.
 * Android closes the database when the process dies.
 */
class DatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "SpamDb"
        private const val DATABASE_NAME = "spam_blocker.db"
        /** v1 -> v2: adds action, applies_to, note; drops the UNIQUE(value) constraint. */
        private const val DATABASE_VERSION = 2

        private const val TABLE_RULES = "block_rules"
        private const val TABLE_LOGS = "blocked_logs"

        @Volatile private var instance: DatabaseHelper? = null

        fun get(context: Context): DatabaseHelper =
            instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context).also {
                    it.setWriteAheadLoggingEnabled(true)   // concurrent readers + one writer
                    instance = it
                }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_RULES(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                value TEXT NOT NULL,
                target_sim INTEGER NOT NULL DEFAULT -1,
                is_active INTEGER NOT NULL DEFAULT 1,
                action TEXT NOT NULL DEFAULT 'BLOCK',
                applies_to TEXT NOT NULL DEFAULT 'BOTH',
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        // The original declared `value TEXT UNIQUE`, so you could not have "210"
        // as both a call prefix and an SMS keyword, and a duplicate add failed
        // silently via CONFLICT_IGNORE with no feedback in the UI. Uniqueness
        // belongs on the combination, not on the value alone.
        db.execSQL(
            "CREATE UNIQUE INDEX idx_rule_unique ON $TABLE_RULES(type, value, target_sim, applies_to)"
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_LOGS(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                sender TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                sim_slot INTEGER NOT NULL DEFAULT -1,
                rule_matched TEXT NOT NULL DEFAULT '',
                action TEXT NOT NULL DEFAULT 'BLOCK'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_log_time ON $TABLE_LOGS(timestamp DESC)")
        insertDefaultRules(db)
    }

    /**
     * The original's onUpgrade was:
     *     DROP TABLE block_rules; DROP TABLE blocked_logs; onCreate(db)
     * Every app update wiped every rule the user had entered. Migrate instead.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.beginTransaction()
                db.execSQL("ALTER TABLE $TABLE_RULES ADD COLUMN action TEXT NOT NULL DEFAULT 'BLOCK'")
                db.execSQL("ALTER TABLE $TABLE_RULES ADD COLUMN applies_to TEXT NOT NULL DEFAULT 'BOTH'")
                db.execSQL("ALTER TABLE $TABLE_RULES ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE_LOGS ADD COLUMN action TEXT NOT NULL DEFAULT 'BLOCK'")
                // KEYWORD rules only ever made sense for SMS.
                db.execSQL("UPDATE $TABLE_RULES SET applies_to='SMS' WHERE type='KEYWORD'")
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e(TAG, "v1->v2 migration failed", e)
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun insertDefaultRules(db: SQLiteDatabase) {
        // The original shipped six KEYWORD rules and nothing else. Since the
        // call path only understood NUMBER and PREFIX, a fresh install blocked
        // exactly zero calls until the user added a rule by hand — while the
        // SMS path quietly matched "winner" and "congratulations" against every
        // message from anyone not in contacts.
        val defaults = listOf(
            // SMS-only, and narrowed. "winner" and "congratulations" alone are
            // too loose to ship on by default.
            BlockRule(type = BlockRule.TYPE_KEYWORD, value = "wire transfer",
                appliesTo = BlockRule.SCOPE_SMS, note = "common fraud lure"),
            BlockRule(type = BlockRule.TYPE_KEYWORD, value = "you have won",
                appliesTo = BlockRule.SCOPE_SMS),
            BlockRule(type = BlockRule.TYPE_KEYWORD, value = "claim your prize",
                appliesTo = BlockRule.SCOPE_SMS),
            BlockRule(type = BlockRule.TYPE_KEYWORD, value = "crypto",
                appliesTo = BlockRule.SCOPE_SMS, isActive = false),
            BlockRule(type = BlockRule.TYPE_KEYWORD, value = "refinance",
                appliesTo = BlockRule.SCOPE_SMS, isActive = false),
            // Off by default; one toggle away in the UI.
            BlockRule(type = BlockRule.TYPE_UNKNOWN, value = "withheld caller id",
                appliesTo = BlockRule.SCOPE_CALL, isActive = false,
                note = "block calls with no caller ID"),
            BlockRule(type = BlockRule.TYPE_NOT_IN_CONTACTS, value = "anyone not in contacts",
                appliesTo = BlockRule.SCOPE_CALL, isActive = false,
                note = "aggressive: silences every unknown caller")
        )
        defaults.forEach { r ->
            db.insert(TABLE_RULES, null, r.toValues())
        }
    }

    private fun BlockRule.toValues() = ContentValues().apply {
        put("type", type)
        // Only fold case for text rules. The original lowercased everything,
        // including regexes, which quietly broke any case-sensitive pattern.
        put("value", if (type == BlockRule.TYPE_KEYWORD) value.trim().lowercase() else value.trim())
        put("target_sim", targetSim)
        put("is_active", if (isActive) 1 else 0)
        put("action", action)
        put("applies_to", appliesTo)
        put("note", note)
    }

    // ---- reads -----------------------------------------------------------
    // Note: no close() anywhere below.

    fun getRules(): List<BlockRule> = try {
        readableDatabase.rawQuery(
            "SELECT id,type,value,target_sim,is_active,action,applies_to,note FROM $TABLE_RULES ORDER BY action DESC, id DESC",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        BlockRule(
                            id = c.getLong(0),
                            type = c.getString(1),
                            value = c.getString(2),
                            targetSim = c.getInt(3),
                            isActive = c.getInt(4) == 1,
                            action = c.getString(5),
                            appliesTo = c.getString(6),
                            note = c.getString(7)
                        )
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "getRules failed", e)
        emptyList()
    }

    fun getLogs(limit: Int = 500): List<BlockedLog> = try {
        readableDatabase.rawQuery(
            "SELECT id,type,sender,content,timestamp,sim_slot,rule_matched,action FROM $TABLE_LOGS ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        BlockedLog(
                            id = c.getLong(0),
                            type = c.getString(1),
                            sender = c.getString(2),
                            content = c.getString(3),
                            timestamp = c.getLong(4),
                            simSlot = c.getInt(5),
                            ruleMatched = c.getString(6),
                            action = c.getString(7)
                        )
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "getLogs failed", e)
        emptyList()
    }

    // ---- writes ----------------------------------------------------------

    /** Returns -1 when the rule already exists, so the UI can actually say so. */
    fun addRule(rule: BlockRule): Long = try {
        writableDatabase.insertWithOnConflict(
            TABLE_RULES, null, rule.toValues(), SQLiteDatabase.CONFLICT_IGNORE
        )
    } catch (e: Exception) {
        Log.e(TAG, "addRule failed", e); -1L
    }

    fun setRuleActive(id: Long, active: Boolean) = try {
        writableDatabase.update(
            TABLE_RULES, ContentValues().apply { put("is_active", if (active) 1 else 0) },
            "id = ?", arrayOf(id.toString())
        )
    } catch (e: Exception) {
        Log.e(TAG, "setRuleActive failed", e); 0
    }

    fun deleteRule(id: Long) = try {
        writableDatabase.delete(TABLE_RULES, "id = ?", arrayOf(id.toString()))
    } catch (e: Exception) {
        Log.e(TAG, "deleteRule failed", e); 0
    }

    fun addLog(log: BlockedLog): Long = try {
        val values = ContentValues().apply {
            put("type", log.type)
            put("sender", log.sender)
            put("content", log.content)
            put("timestamp", log.timestamp)
            put("sim_slot", log.simSlot)
            put("rule_matched", log.ruleMatched)
            put("action", log.action)
        }
        val id = writableDatabase.insert(TABLE_LOGS, null, values)
        // Unbounded growth: the original never pruned. Keep the last 2000.
        writableDatabase.execSQL(
            "DELETE FROM $TABLE_LOGS WHERE id NOT IN (SELECT id FROM $TABLE_LOGS ORDER BY timestamp DESC LIMIT 2000)"
        )
        id
    } catch (e: Exception) {
        Log.e(TAG, "addLog failed", e); -1L
    }

    fun clearLogs() = try {
        writableDatabase.delete(TABLE_LOGS, null, null)
    } catch (e: Exception) {
        Log.e(TAG, "clearLogs failed", e); 0
    }
}
