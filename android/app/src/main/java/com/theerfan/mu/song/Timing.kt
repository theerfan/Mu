package com.theerfan.mu.song

import kotlin.math.ceil
import kotlin.math.floor

/** Song-time helpers: tempo scaling, beat grid from the tempo map, metronome/count-in clicks. */
object Timing {
    /** Seconds of quarter-note beat `beat` at 100 % tempo, from a tempo map of (beat, bpm) points. */
    fun beatToSec(tempoMap: List<TempoPoint>, beat: Double): Double {
        val map = tempoMap.sortedBy { it.beat }.ifEmpty { listOf(TempoPoint(0.0, 120.0)) }
        var t = 0.0
        var prevBeat = 0.0
        var bpm = map.first().bpm
        for (p in map) {
            if (p.beat > beat) break
            if (p.beat > prevBeat) { t += (p.beat - prevBeat) * 60.0 / bpm; prevBeat = p.beat }
            bpm = p.bpm
        }
        return t + (beat - prevBeat) * 60.0 / bpm
    }

    /** Beat times (seconds, 100 % tempo) from 0 up to endSec. */
    fun beatTimes(tempoMap: List<TempoPoint>, endSec: Double): List<Double> {
        val out = ArrayList<Double>()
        var b = 0.0
        while (true) {
            val t = beatToSec(tempoMap, b)
            if (t > endSec + 1e-6 || out.size > 20000) break
            out += t; b += 1.0
        }
        return out
    }

    data class Clicks(val times: DoubleArray, val accents: BooleanArray, val leadInSec: Double)

    /**
     * Clicks for a play-through at `speed` (1.0 = 100 %): `countInBeats` count-in clicks before 0 at the first
     * tempo, plus (if `throughSong`) one click per beat until the end. Bar starts are accented.
     */
    fun clicks(song: Song, speed: Double, countInBeats: Int, throughSong: Boolean): Clicks {
        val num = song.timeSig.firstOrNull()?.num?.coerceAtLeast(1) ?: 4
        val beat0 = 60.0 / (song.tempoMap.minByOrNull { it.beat }?.bpm ?: 120.0) / speed
        val times = ArrayList<Double>(); val acc = ArrayList<Boolean>()
        for (k in countInBeats downTo 1) { times += -k * beat0; acc += (countInBeats - k) % num == 0 }
        if (throughSong) beatTimes(song.tempoMap, song.endTime).forEachIndexed { i, t -> times += t / speed; acc += i % num == 0 }
        return Clicks(times.toDoubleArray(), acc.toBooleanArray(), countInBeats * beat0 + 0.4)
    }

    /** Expected events for the native scorer: times scaled by speed, notes flattened. */
    data class Expected(val times: DoubleArray, val counts: IntArray, val midis: IntArray)

    fun expected(song: Song, speed: Double): Expected {
        val times = DoubleArray(song.events.size) { song.events[it].t / speed }
        val counts = IntArray(song.events.size) { song.events[it].notes.size.coerceAtMost(8) }
        val midis = song.events.flatMap { e -> e.notes.take(8).map { it.midi } }.toIntArray()
        return Expected(times, counts, midis)
    }

    /** First index with event time >= t (events sorted). */
    fun firstAtOrAfter(times: DoubleArray, t: Double): Int {
        var lo = 0; var hi = times.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (times[mid] < t) lo = mid + 1 else hi = mid }
        return lo
    }

    fun barIndex(beat: Double, num: Int): Int = floor(beat / num).toInt()
    fun barsFor(sec: Double, bpm: Double, num: Int): Int = ceil(sec * bpm / 60.0 / num).toInt()
}
