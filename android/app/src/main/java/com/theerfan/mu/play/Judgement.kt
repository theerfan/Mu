package com.theerfan.mu.play

import kotlin.math.abs
import kotlin.math.roundToInt

enum class Kind { HIT, PARTIAL, WRONG, MISS, EXTRA }
enum class Grade { PERFECT, GOOD, OK, NONE }

/** One judgement from the native scorer (mucore PlayAnalyzer). */
data class Judgement(
    val kind: Kind, val grade: Grade, val index: Int, val dtMs: Float, val songTime: Float,
    val detMidi: Int, val cents: Float, val chordScore: Float, val missingPc: Int, val extraPc: Int,
) {
    companion object {
        const val FLOATS = 10
        fun unpack(a: FloatArray, n: Int): List<Judgement> = List(n) { i ->
            val o = i * FLOATS
            Judgement(
                Kind.entries[a[o].toInt().coerceIn(0, 4)], Grade.entries[a[o + 1].toInt().coerceIn(0, 3)], a[o + 2].toInt(),
                a[o + 3], a[o + 4], a[o + 5].toInt(), a[o + 6], a[o + 7], a[o + 8].toInt(), a[o + 9].toInt(),
            )
        }
    }
}

object Notes {
    private val PC = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun pcName(pc: Int) = if (pc in 0..11) PC[pc] else "?"
    fun midiName(m: Int) = if (m < 0) "?" else PC[m % 12] + (m / 12 - 1)
    fun midiToHz(m: Double) = 440.0 * Math.pow(2.0, (m - 69) / 12.0)
    fun hzToMidi(hz: Double) = 69 + 12 * Math.log(hz / 440.0) / Math.log(2.0)
}

/** Points per judgement: timing grade x pitch factor (ARCHITECTURE.md §5). */
fun points(j: Judgement): Int {
    val timing = when (j.grade) { Grade.PERFECT -> 100; Grade.GOOD -> 70; Grade.OK -> 40; Grade.NONE -> 0 }
    return when (j.kind) { Kind.HIT -> timing; Kind.PARTIAL -> timing / 2; else -> 0 }
}

/** Short feedback line for the play screen. */
fun feedback(j: Judgement, expectedMidi: List<Int>?): String {
    val timing = when {
        j.grade == Grade.PERFECT -> "Perfect"
        j.dtMs < 0 -> "Early ${abs(j.dtMs).roundToInt()} ms"
        else -> "Late ${j.dtMs.roundToInt()} ms"
    }
    return when (j.kind) {
        Kind.HIT -> timing
        Kind.PARTIAL -> "$timing · octave off (${Notes.midiName(j.detMidi)})"
        Kind.WRONG -> if (expectedMidi != null && expectedMidi.size >= 2) {
            val parts = buildList {
                if (j.missingPc >= 0) add("missing ${Notes.pcName(j.missingPc)}")
                if (j.extraPc >= 0) add("extra ${Notes.pcName(j.extraPc)}")
            }
            "Wrong chord" + if (parts.isNotEmpty()) ": " + parts.joinToString(", ") else ""
        } else if (j.detMidi >= 0) "Wrong note: ${Notes.midiName(j.detMidi)}" + (expectedMidi?.firstOrNull()?.let { " (want ${Notes.midiName(it)})" } ?: "")
        else "No clear pitch"
        Kind.MISS -> "Miss"
        Kind.EXTRA -> "Extra note"
    }
}

data class Summary(
    val total: Int, val perfect: Int, val good: Int, val ok: Int, val partial: Int, val wrong: Int, val miss: Int, val extra: Int,
    val score: Int, val maxScore: Int, val bestStreak: Int, val meanOffsetMs: Float?, val chordHits: Int, val chordTotal: Int,
) {
    val hits get() = perfect + good + ok
    val accuracyPct get() = if (total == 0) 0f else 100f * hits / total
}

/** Aggregates judgements for an expected-event list of size `total` (one judgement per event index + extras). */
fun summarize(js: List<Judgement>, total: Int, isChord: (Int) -> Boolean): Summary {
    val byIndex = HashMap<Int, Judgement>()
    var extra = 0
    for (j in js) if (j.kind == Kind.EXTRA) extra++ else if (j.index >= 0 && j.index !in byIndex) byIndex[j.index] = j
    var streak = 0; var best = 0
    for (i in 0 until total) {
        val j = byIndex[i]
        if (j != null && j.kind == Kind.HIT) { streak++; best = maxOf(best, streak) } else streak = 0
    }
    val hits = byIndex.values.filter { it.kind == Kind.HIT }
    val timed = byIndex.values.filter { it.kind == Kind.HIT || it.kind == Kind.PARTIAL || it.kind == Kind.WRONG }
    return Summary(
        total = total,
        perfect = hits.count { it.grade == Grade.PERFECT }, good = hits.count { it.grade == Grade.GOOD }, ok = hits.count { it.grade == Grade.OK },
        partial = byIndex.values.count { it.kind == Kind.PARTIAL }, wrong = byIndex.values.count { it.kind == Kind.WRONG },
        miss = total - byIndex.values.count { it.kind != Kind.MISS },
        extra = extra, score = byIndex.values.sumOf { points(it) }, maxScore = 100 * total, bestStreak = best,
        meanOffsetMs = if (timed.isEmpty()) null else timed.map { it.dtMs }.average().toFloat(),
        chordHits = hits.count { isChord(it.index) }, chordTotal = (0 until total).count(isChord),
    )
}

/** 0–3 stars from the share of the maximum score: 40 %, 70 %, 90 %. */
val STAR_THRESHOLDS = floatArrayOf(0.4f, 0.7f, 0.9f)
fun stars(score: Int, maxScore: Int): Int = if (maxScore <= 0) 0 else STAR_THRESHOLDS.count { score >= it * maxScore }
