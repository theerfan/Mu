package com.theerfan.mu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.theerfan.mu.play.Grade
import com.theerfan.mu.play.Judgement
import com.theerfan.mu.play.Kind
import com.theerfan.mu.song.Song
import com.theerfan.mu.song.Timing

fun judgementColor(j: Judgement): Color = when (j.kind) {
    Kind.HIT -> when (j.grade) { Grade.PERFECT -> MuColors.perfect; Grade.GOOD -> MuColors.good; else -> MuColors.ok }
    Kind.PARTIAL -> MuColors.partial
    Kind.WRONG -> MuColors.wrong
    Kind.MISS -> MuColors.miss
    Kind.EXTRA -> MuColors.miss
}

/**
 * Scrolling tab-style note highway: one lane per string (string 1 = high e on top), notes move right-to-left
 * towards the timing line; fret numbers on the notes, chord names above. Judged notes take their result colour.
 * Text layouts for frets and chord names are measured once and cached (no per-frame text measuring).
 */
@Composable
fun Highway(
    song: Song, speed: Double, times: DoubleArray, beatTimes: DoubleArray, barEvery: Int, songTime: Double,
    judged: Map<Int, Judgement>, modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val fretText = remember(measurer) {
        HashMap<Int, TextLayoutResult>().also { m ->
            for (f in 0..30) m[f] = measurer.measure(f.toString(), TextStyle(color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        }
    }
    val chordText = remember(song, measurer) {
        song.events.mapNotNull { it.chord }.distinct().associateWith { measurer.measure(it, TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)) }
    }
    val strings = song.stringCount
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val top = 34.sp.toPx(); val laneH = (h - top - 8f) / strings
        val nowX = w * 0.22f
        val pps = 230.sp.toPx()  // pixels per second
        fun x(t: Double) = nowX + ((t - songTime) * pps).toFloat()
        fun laneY(s: Int) = top + (s - 0.5f) * laneH
        val tMin = songTime - nowX / pps - 0.5; val tMax = songTime + (w - nowX) / pps + 0.5
        // beat / bar lines
        var b = Timing.firstAtOrAfter(beatTimes, tMin)
        while (b < beatTimes.size && beatTimes[b] <= tMax) {
            val xb = x(beatTimes[b]); val bar = b % barEvery == 0
            drawLine(if (bar) Color(0x66FFFFFF) else Color(0x22FFFFFF), Offset(xb, top), Offset(xb, h - 4f), strokeWidth = if (bar) 3f else 1.5f)
            b++
        }
        // strings
        for (s in 1..strings) drawLine(MuColors.strings[(s - 1) % 6].copy(alpha = 0.55f), Offset(0f, laneY(s)), Offset(w, laneY(s)), strokeWidth = 1f + s * 0.6f)
        // notes
        val noteW = 30.sp.toPx(); val noteH = laneH * 0.72f
        var i = maxOf(0, Timing.firstAtOrAfter(times, tMin - 4.0))
        while (i < times.size && times[i] <= tMax) {
            val e = song.events[i]; val xe = x(times[i]); val j = judged[i]
            // sustain tail: the note's duration at practice speed, cut 10 % before the next event
            val dur = e.dur / speed
            val gap = times.getOrElse(i + 1) { times[i] + dur } - times[i]
            val tailW = (minOf(dur, gap * 0.9) * pps).toFloat()
            if (xe + tailW < -noteW) { i++; continue }
            if (e.notes.size >= 2) {
                val ys = e.notes.map { laneY(it.string) }
                drawRoundRect(Color(0x33FFFFFF), Offset(xe - noteW * 0.6f, ys.min() - noteH * 0.6f), Size(noteW * 1.2f, ys.max() - ys.min() + noteH * 1.2f), CornerRadius(10f))
                e.chord?.let { c -> chordText[c]?.let { drawText(it, topLeft = Offset(xe - it.size.width / 2f, 4f)) } }
            }
            for (n in e.notes) {
                val y = laneY(n.string)
                val col = j?.let { judgementColor(it) } ?: MuColors.strings[(n.string - 1) % 6]
                if (tailW > noteW) drawRoundRect(col.copy(alpha = 0.35f), Offset(xe, y - noteH * 0.18f), Size(tailW, noteH * 0.36f), CornerRadius(6f))
                drawRoundRect(col, Offset(xe - noteW / 2, y - noteH / 2), Size(noteW, noteH), CornerRadius(8f))
                fretText[n.fret]?.let { drawText(it, topLeft = Offset(xe - it.size.width / 2f, y - it.size.height / 2f)) }
            }
            i++
        }
        // timing line
        drawLine(Color.White, Offset(nowX, top - 6f), Offset(nowX, h), strokeWidth = 4f)
    }
}
