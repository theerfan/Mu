package com.theerfan.mu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theerfan.mu.play.Grade
import com.theerfan.mu.play.Judgement
import com.theerfan.mu.play.Kind
import com.theerfan.mu.play.Notes
import com.theerfan.mu.song.Song
import com.theerfan.mu.song.Timing

fun judgementColor(j: Judgement): Color = when (j.kind) {
    Kind.HIT -> when (j.grade) { Grade.PERFECT -> MuColors.perfect; Grade.GOOD -> MuColors.good; else -> MuColors.ok }
    Kind.PARTIAL -> MuColors.partial
    Kind.WRONG -> MuColors.wrong
    Kind.MISS -> MuColors.miss
    Kind.EXTRA -> MuColors.miss
}

private val HighwayTop = Color(0xFF17123A)
private val HighwayBottom = Color(0xFF0A0819)
private val NoteText = Color(0xFF1A1433)

/**
 * Scrolling tab-style note highway: one lane per string (string 1 = high e on top), notes move right-to-left
 * towards the glowing timing line; fret numbers on the notes, chord names above. Judged notes take their result
 * colour, hits burst as they cross the line, misses dim out. String names sit in a left gutter.
 * Text layouts for frets, chord names and string labels are measured once and cached (no per-frame text measuring).
 */
@Composable
fun Highway(
    song: Song, speed: Double, times: DoubleArray, beatTimes: DoubleArray, barEvery: Int, songTime: Double,
    judged: Map<Int, Judgement>, modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val fretText = remember(measurer) {
        HashMap<Int, TextLayoutResult>().also { m ->
            for (f in 0..30) m[f] = measurer.measure(f.toString(), TextStyle(color = NoteText, fontSize = 16.sp, fontWeight = FontWeight.Black))
        }
    }
    val chordText = remember(song, measurer) {
        song.events.mapNotNull { it.chord }.distinct().associateWith { measurer.measure(it, TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)) }
    }
    val strings = song.stringCount
    // tuning is low → high; string 1 is the highest
    val stringText = remember(song, measurer) {
        (1..strings).map { s ->
            val midi = song.tuning.getOrElse(strings - s) { 40 }
            measurer.measure(Notes.pcName(midi % 12), TextStyle(color = MuColors.strings[(s - 1) % 6], fontSize = 13.sp, fontWeight = FontWeight.Black))
        }
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(HighwayTop, HighwayBottom)))
        // lanes are capped in height and centred, so a tall portrait view doesn't stretch them apart
        val labelH = 34.dp.toPx(); val avail = h - labelH - 10.dp.toPx()
        val laneH = minOf(avail / strings, 58.dp.toPx())
        val top = labelH + (avail - laneH * strings) / 2; val bottom = top + laneH * strings
        val gutter = 30.dp.toPx()
        val nowX = gutter + (w - gutter) * 0.2f
        // pixels per second: about 2.2 s of look-ahead, within limits that keep notes readable
        val pps = ((w - nowX) / 2.2f).coerceIn(150.dp.toPx(), 260.dp.toPx())
        fun x(t: Double) = nowX + ((t - songTime) * pps).toFloat()
        fun laneY(s: Int) = top + (s - 0.5f) * laneH
        val tMin = songTime - nowX / pps - 0.5; val tMax = songTime + (w - nowX) / pps + 0.5

        // played zone: slightly darker behind the line
        drawRect(Color(0x33000000), Offset(0f, 0f), Size(nowX, h))
        // beat / bar lines
        var b = Timing.firstAtOrAfter(beatTimes, tMin)
        while (b < beatTimes.size && beatTimes[b] <= tMax) {
            val xb = x(beatTimes[b]); val bar = b % barEvery == 0
            if (xb > gutter) drawLine(if (bar) Color(0x40FFFFFF) else Color(0x14FFFFFF), Offset(xb, top - 6f), Offset(xb, bottom), strokeWidth = if (bar) 2.5f else 1.5f)
            b++
        }
        // strings: soft glow + core line, thicker for the low strings
        for (s in 1..strings) {
            val c = MuColors.strings[(s - 1) % 6]; val y = laneY(s); val thick = 1.5f + s * 0.7f
            drawLine(c.copy(alpha = 0.10f), Offset(gutter, y), Offset(w, y), strokeWidth = thick * 5f)
            drawLine(c.copy(alpha = 0.55f), Offset(gutter, y), Offset(w, y), strokeWidth = thick)
            val label = stringText[s - 1]
            drawText(label, topLeft = Offset((gutter - label.size.width) / 2f + 2f, y - label.size.height / 2f))
        }
        // timing line: wide glow, bright core, a target ring per lane
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x40FFFFFF), Color.Transparent), nowX - 26f, nowX + 26f),
            Offset(nowX - 26f, top - 10f), Size(52f, bottom - top + 20f))
        drawLine(Color.White, Offset(nowX, top - 10f), Offset(nowX, bottom + 10f), strokeWidth = 3.5f)
        val noteH = minOf(laneH * 0.8f, 36.dp.toPx()); val noteW = noteH * 1.18f
        for (s in 1..strings) drawCircle(MuColors.strings[(s - 1) % 6].copy(alpha = 0.6f), noteH * 0.52f, Offset(nowX, laneY(s)), style = Stroke(2.5f))

        // notes (clipped so tails never run under the string labels)
        clipRect(left = gutter) {
            var i = maxOf(0, Timing.firstAtOrAfter(times, tMin - 4.0))
            while (i < times.size && times[i] <= tMax) {
                val e = song.events[i]; val xe = x(times[i]); val j = judged[i]
                // sustain tail: the note's duration at practice speed, cut 10 % before the next event
                val dur = e.dur / speed
                val gap = times.getOrElse(i + 1) { times[i] + dur } - times[i]
                val tailW = (minOf(dur, gap * 0.9) * pps).toFloat()
                if (xe + tailW < gutter - noteW) { i++; continue }
                val age = (songTime - times[i]).toFloat()          // > 0 once the note has crossed the line
                val hit = j != null && (j.kind == Kind.HIT || j.kind == Kind.PARTIAL)
                // fade notes as they leave towards the gutter
                val fade = ((xe - gutter) / (nowX - gutter)).coerceIn(0f, 1f).let { if (xe < nowX) 0.25f + 0.75f * it else 1f }
                if (e.notes.size >= 2) {
                    val ys = e.notes.map { laneY(it.string) }
                    val box = Offset(xe - noteW * 0.68f, ys.min() - noteH * 0.68f)
                    val boxSize = Size(noteW * 1.36f, ys.max() - ys.min() + noteH * 1.36f)
                    drawRoundRect(Color.White.copy(alpha = 0.07f * fade), box, boxSize, CornerRadius(noteH * 0.5f))
                    drawRoundRect(Color.White.copy(alpha = 0.22f * fade), box, boxSize, CornerRadius(noteH * 0.5f), style = Stroke(2f))
                    e.chord?.let { c -> chordText[c]?.let {
                        val pw = it.size.width + 16.dp.toPx(); val ph = it.size.height + 4.dp.toPx(); val py = top - labelH + 4.dp.toPx()
                        drawRoundRect((j?.let { jj -> judgementColor(jj) } ?: MuColors.accent).copy(alpha = 0.9f * fade), Offset(xe - pw / 2, py), Size(pw, ph), CornerRadius(ph / 2))
                        drawText(it, topLeft = Offset(xe - it.size.width / 2f, py + 2.dp.toPx()), alpha = fade)
                    } }
                }
                for (n in e.notes) {
                    val y = laneY(n.string)
                    val lane = MuColors.strings[(n.string - 1) % 6]
                    val col = j?.let { judgementColor(it) } ?: lane
                    if (tailW > noteW) drawRoundRect(col.copy(alpha = 0.35f * fade), Offset(xe, y - noteH * 0.17f), Size(tailW, noteH * 0.34f), CornerRadius(noteH * 0.17f))
                    if (hit && age in 0f..0.45f) {
                        // burst: expanding ring + flash
                        val k = age / 0.45f
                        drawCircle(col.copy(alpha = (1f - k) * 0.9f), noteH * (0.6f + 1.4f * k), Offset(xe, y), style = Stroke(4f * (1f - k) + 1f))
                        drawCircle(col.copy(alpha = (1f - k) * 0.35f), noteH * (0.5f + 0.9f * k), Offset(xe, y))
                    }
                    val glowA = if (j == null && xe in nowX - noteW..nowX + noteW * 3) 0.35f else 0.12f
                    drawRoundRect(col.copy(alpha = glowA * fade), Offset(xe - noteW / 2 - 5f, y - noteH / 2 - 5f), Size(noteW + 10f, noteH + 10f), CornerRadius(noteH / 2 + 5f))
                    drawRoundRect(col.copy(alpha = fade), Offset(xe - noteW / 2, y - noteH / 2), Size(noteW, noteH), CornerRadius(noteH / 2))
                    drawRoundRect(lerp(col, Color.White, 0.45f).copy(alpha = 0.55f * fade), Offset(xe - noteW / 2 + noteH * 0.18f, y - noteH * 0.4f),
                        Size(noteW - noteH * 0.36f, noteH * 0.26f), CornerRadius(noteH * 0.13f))
                    fretText[n.fret]?.let { drawText(it, topLeft = Offset(xe - it.size.width / 2f, y - it.size.height / 2f), alpha = fade) }
                }
                i++
            }
        }
    }
}
