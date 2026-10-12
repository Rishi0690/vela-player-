package com.hark.velaplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Focus Mode history, stored only on this phone (SQLite). Nothing is uploaded.
 * Time is written as small measured pieces of real playing time, so a crash or a killed app
 * can lose at most the last few seconds — it never adds time that wasn't spent watching.
 */
class FocusStore private constructor(c: Context) : SQLiteOpenHelper(c.applicationContext, "focus.db", null, 1) {

    companion object {
        @Volatile private var inst: FocusStore? = null
        fun get(c: Context): FocusStore = inst ?: synchronized(this) { inst ?: FocusStore(c).also { inst = it } }
        /** A video counts as "watched in focus" after this much real focus time on it. */
        const val WATCHED_MIN_MS = 30_000L
        private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun dayKey(t: Long): String = dayFmt.format(Date(t))
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL DEFAULT 0, last_ms INTEGER NOT NULL DEFAULT 0, focus_ms INTEGER NOT NULL DEFAULT 0, open INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE video_time(session_id INTEGER NOT NULL, uri TEXT NOT NULL, title TEXT, focus_ms INTEGER NOT NULL DEFAULT 0, media_ms INTEGER NOT NULL DEFAULT 0, completed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(session_id, uri))")
        db.execSQL("CREATE TABLE daily(day TEXT PRIMARY KEY, focus_ms INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun startSession(now: Long): Long = writableDatabase.insert("sessions", null, ContentValues().apply {
        put("start_ms", now); put("last_ms", now); put("open", 1)
    })

    /** Adds [deltaMs] of real focus time that ended at wall-clock [now]. */
    fun addTime(session: Long, uri: String?, title: String?, mediaMs: Long, deltaMs: Long, now: Long) {
        if (deltaMs <= 0 || session < 0) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE sessions SET focus_ms = focus_ms + ?, last_ms = ? WHERE id = ?", arrayOf<Any>(deltaMs, now, session))
            if (uri != null) {
                db.execSQL("INSERT OR IGNORE INTO video_time(session_id, uri, title, media_ms) VALUES(?,?,?,?)", arrayOf<Any>(session, uri, title ?: "", mediaMs))
                db.execSQL("UPDATE video_time SET focus_ms = focus_ms + ?, title = ?, media_ms = MAX(media_ms, ?) WHERE session_id = ? AND uri = ?", arrayOf<Any>(deltaMs, title ?: "", mediaMs, session, uri))
            }
            // Split across midnight so each day gets only its own minutes.
            val start = now - deltaMs
            val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val midnight = cal.timeInMillis
            if (start < midnight) {
                addDay(db, dayKey(start), midnight - start)
                addDay(db, dayKey(now), now - midnight)
            } else addDay(db, dayKey(now), deltaMs)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun addDay(db: SQLiteDatabase, day: String, ms: Long) {
        if (ms <= 0) return
        db.execSQL("INSERT OR IGNORE INTO daily(day, focus_ms) VALUES(?, 0)", arrayOf<Any>(day))
        db.execSQL("UPDATE daily SET focus_ms = focus_ms + ? WHERE day = ?", arrayOf<Any>(ms, day))
    }

    fun markCompleted(session: Long, uri: String, title: String?, mediaMs: Long) {
        if (session < 0) return
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO video_time(session_id, uri, title, media_ms) VALUES(?,?,?,?)", arrayOf<Any>(session, uri, title ?: "", mediaMs))
        db.execSQL("UPDATE video_time SET completed = 1 WHERE session_id = ? AND uri = ?", arrayOf<Any>(session, uri))
    }

    fun endSession(session: Long, now: Long) {
        if (session < 0) return
        val db = writableDatabase
        db.execSQL("UPDATE sessions SET end_ms = ?, open = 0 WHERE id = ?", arrayOf<Any>(now, session))
        // Sessions that never really played are noise in the history.
        db.execSQL("DELETE FROM sessions WHERE id = ? AND focus_ms < 1000", arrayOf<Any>(session))
        db.execSQL("DELETE FROM video_time WHERE session_id NOT IN (SELECT id FROM sessions)")
    }

    /** After a crash or a killed app: close sessions at their last saved moment (adds nothing). */
    fun closeAbandoned(startedBefore: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.execSQL("UPDATE sessions SET end_ms = last_ms, open = 0 WHERE open = 1 AND start_ms < ?", arrayOf<Any>(startedBefore))
        db.execSQL("DELETE FROM sessions WHERE focus_ms < 1000 AND open = 0")
        db.execSQL("DELETE FROM video_time WHERE session_id NOT IN (SELECT id FROM sessions)")
    }

    fun clearAll() {
        val db = writableDatabase
        db.execSQL("DELETE FROM sessions"); db.execSQL("DELETE FROM video_time"); db.execSQL("DELETE FROM daily")
    }

    // ---------------------------------------------------------------- reading

    private fun long(sql: String, vararg args: String): Long = readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }

    fun dayTotal(day: String) = long("SELECT focus_ms FROM daily WHERE day = ?", day)
    fun today() = dayTotal(dayKey(System.currentTimeMillis()))
    fun allTime() = long("SELECT SUM(focus_ms) FROM daily")

    /** Since the start of this calendar week (the phone's first day of week). */
    fun thisWeek(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) cal.add(Calendar.DAY_OF_MONTH, -1)
        return long("SELECT SUM(focus_ms) FROM daily WHERE day >= ?", dayKey(cal.timeInMillis))
    }

    /** Focus minutes for each of the last [days] days, oldest first. */
    fun lastDays(days: Int): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -(days - 1))
        repeat(days) {
            out += cal.timeInMillis to dayTotal(dayKey(cal.timeInMillis))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }

    fun videosWatched() = long("SELECT COUNT(*) FROM (SELECT uri FROM video_time GROUP BY uri HAVING SUM(focus_ms) >= $WATCHED_MIN_MS)")
    fun videosCompleted() = long("SELECT COUNT(DISTINCT uri) FROM video_time WHERE completed = 1")
    fun sessionCount() = long("SELECT COUNT(*) FROM sessions WHERE open = 0")

    data class Session(val id: Long, val start: Long, val end: Long, val focusMs: Long, val videos: Int, val completed: Int)
    fun recentSessions(limit: Int = 20): List<Session> = readableDatabase.rawQuery(
        "SELECT s.id, s.start_ms, s.end_ms, s.focus_ms, " +
            "(SELECT COUNT(*) FROM video_time v WHERE v.session_id = s.id AND v.focus_ms >= $WATCHED_MIN_MS), " +
            "(SELECT COUNT(*) FROM video_time v WHERE v.session_id = s.id AND v.completed = 1) " +
            "FROM sessions s WHERE s.open = 0 ORDER BY s.start_ms DESC LIMIT $limit", null
    ).use { c -> val l = ArrayList<Session>(); while (c.moveToNext()) l += Session(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getInt(4), c.getInt(5)); l }

    data class VideoStat(val uri: String, val title: String, val focusMs: Long, val mediaMs: Long, val completed: Boolean)
    fun topVideos(limit: Int = 15): List<VideoStat> = readableDatabase.rawQuery(
        "SELECT uri, MAX(title), SUM(focus_ms), MAX(media_ms), MAX(completed) FROM video_time GROUP BY uri HAVING SUM(focus_ms) > 0 ORDER BY SUM(focus_ms) DESC LIMIT $limit", null
    ).use { c -> val l = ArrayList<VideoStat>(); while (c.moveToNext()) l += VideoStat(c.getString(0), c.getString(1) ?: "", c.getLong(2), c.getLong(3), c.getInt(4) == 1); l }

    data class Summary(val focusMs: Long, val videos: Int, val completed: Int)
    fun sessionSummary(id: Long): Summary = readableDatabase.rawQuery(
        "SELECT (SELECT focus_ms FROM sessions WHERE id = ?), (SELECT COUNT(*) FROM video_time WHERE session_id = ? AND focus_ms >= $WATCHED_MIN_MS), (SELECT COUNT(*) FROM video_time WHERE session_id = ? AND completed = 1)",
        arrayOf(id.toString(), id.toString(), id.toString())
    ).use { c -> if (c.moveToFirst()) Summary(c.getLong(0), c.getInt(1), c.getInt(2)) else Summary(0, 0, 0) }
}

/** "1h 05m", "12m", "45s" — for focus totals. */
object FocusFmt {
    fun dur(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }
    /** Live clock for the session timer: 12:34 or 1:02:03. */
    fun clock(ms: Long) = Fmt.time(ms)
}
