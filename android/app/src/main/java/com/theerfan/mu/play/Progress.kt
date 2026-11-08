package com.theerfan.mu.play

import android.content.Context
import java.time.LocalDate

/** Best result per song plus a daily practice streak (SharedPreferences "progress"). */
class ProgressStore(context: Context) {
    data class Best(val stars: Int, val pct: Int, val score: Int)

    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun best(songId: String): Best? = prefs.getString("best_$songId", null)?.split(',')?.takeIf { it.size == 3 }?.let {
        Best(it[0].toInt(), it[1].toInt(), it[2].toInt())
    }

    fun totalStars(): Int = prefs.all.entries.filter { it.key.startsWith("best_") }.sumOf { (it.value as? String)?.substringBefore(',')?.toIntOrNull() ?: 0 }

    val lastSongId: String? get() = prefs.getString("last_song", null)

    /** Days in a row with at least one finished play; 0 once a day is skipped. */
    fun streakDays(today: Long = LocalDate.now().toEpochDay()): Int =
        if (prefs.getLong("last_day", Long.MIN_VALUE) >= today - 1) prefs.getInt("streak", 0) else 0

    /** Records a finished play; returns true if it beat the previous best score. */
    fun record(songId: String, s: Summary, today: Long = LocalDate.now().toEpochDay()): Boolean {
        val prev = best(songId)
        val e = prefs.edit().putString("last_song", songId)
        val last = prefs.getLong("last_day", Long.MIN_VALUE)
        if (last != today) e.putInt("streak", if (last == today - 1) prefs.getInt("streak", 0) + 1 else 1).putLong("last_day", today)
        val better = prev == null || s.score > prev.score
        if (better) {
            val pct = if (s.maxScore > 0) 100 * s.score / s.maxScore else 0
            e.putString("best_$songId", "${stars(s.score, s.maxScore)},$pct,${s.score}")
        }
        e.apply()
        return better && prev != null
    }
}
