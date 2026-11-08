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
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
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

// A real fingerboard: dark rosewood, nickel fret wires, plain steel and bronze-wound strings. All flat, solid colours.
private val Board = Color(0xFF2B231E)
private val BoardPlayed = Color(0xFF221C18)
private val BoardEdge = Color(0xFF16110E)
private val BeatLine = Color(0xFF3A302A)
private val FretWire = Color(0xFF9EA3AA)
private val PlainString = Color(0xFFD9DCE0)
private val WoundString = Color(0xFFB98D55)
private val MissNote = Color(0xFF4A4D55)

/** Note colours by fret (≈ which finger): open strings grey, then a fixed colour per fret, repeating from 6. */
private val FretColors = listOf(
    Color(0xFF7A8090), Color(0xFFF08A1C), Color(0xFFA334E0), Color(0xFF1F9BE0), Color(0xFFE23A55), Color(0xFF22A863),
)
fun fretColor(fret: Int): Color = if (fret <= 0) FretColors[0] else FretColors[1 + (fret - 1) % (FretColors.size - 1)]

/** Seconds of look-ahead the highway aims for (also used by the minimap's view window). */
const val HIGHWAY_LOOKAHEAD_SEC = 2.2f

/**
 * The fretboard highway, seen at an angle: the neck lies tilted away from the player, so the far strings (high e on
 * top, as in tab) sit closer together and every beat/fret line leans. Notes are raised slabs riding on their string
 * towards the slanted timing line on the left; chords get a region across their strings and a big slanted name.
 * Hits burst on the line, misses fade. Text layouts are measured once and cached (no per-frame text measuring).
 *
 * Geometry: u = time axis in px along the near edge, v ∈ [0, 1] = depth from the near (low E) to the far (high e)
 * edge. Screen y = yNear − depth(v) with depth() concave (perspective-like compression), screen x = u + K·depth(v).
 */
@Composable
fun Highway(
    song: Song, speed: Double, times: DoubleArray, beatTimes: DoubleArray, barEvery: Int, songTime: Double,
    judged: Map<Int, Judgement>, modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val fretText = remember(measurer) {
        HashMap<Int, TextLayoutResult>().also { m ->
            for (f in 0..30) m[f] = measurer.measure(f.toString(), TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black))
        }
    }
    val chordText = remember(song, measurer) {
        song.events.mapNotNull { it.chord }.distinct().associateWith { measurer.measure(it, TextStyle(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)) }
    }
    val strings = song.stringCount
    // a short gap typical of the busy parts (15th percentile): the scroll speed keeps notes that close apart
    val denseGap = remember(times) {
        val gaps = times.toList().zipWithNext { a, b -> b - a }.filter { it > 0.03 }.sorted()
        if (gaps.isEmpty()) 1.0 else gaps[(gaps.size * 0.15).toInt().coerceAtMost(gaps.size - 1)]
    }
    // tuning is low → high; string 1 is the highest
    val stringText = remember(song, measurer) {
        (1..strings).map { s ->
            val midi = song.tuning.getOrElse(strings - s) { 40 }
            measurer.measure(Notes.pcName(midi % 12), TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black))
        }
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        // the board sits at the bottom; the space above it is "air" (drawn by the caller) where chord names float
        val edgeH = 10.dp.toPx(); val chordRoom = 52.dp.toPx()
        val depthPx = minOf(h - edgeH - chordRoom, strings * 44.dp.toPx()).coerceAtLeast(strings * 18.dp.toPx())
        val yNear = h - edgeH
        val k = 0.13f                      // slight lean of the fret lines: the neck is seen nearly face-on
        val a = 0.18f                      // mild perspective: far strings only a little closer together
        fun depth(v: Float) = depthPx * v * (1 + a) / (1 + a * v)
        fun spacing(v: Float) = depthPx * (1 + a) / ((1 + a * v) * (1 + a * v)) / strings   // local lane spacing
        fun vOf(s: Int) = (strings - s + 0.5f) / strings
        fun sx(u: Float, v: Float) = u + k * depth(v)
        fun sy(v: Float) = yNear - depth(v)
        val gutter = 30.dp.toPx()
        val nowU = gutter + (w - gutter) * 0.16f
        // pixels per second: about HIGHWAY_LOOKAHEAD_SEC of look-ahead, but fast enough that busy passages don't overlap
        val pps = maxOf(((w - nowU) / HIGHWAY_LOOKAHEAD_SEC).coerceIn(150.dp.toPx(), 280.dp.toPx()), (38.dp.toPx() / denseGap).toFloat())
            .coerceAtMost(480.dp.toPx())
        fun u(t: Double) = nowU + ((t - songTime) * pps).toFloat()
        val tMin = songTime - nowU / pps - 0.5; val tMax = songTime + (w - nowU) / pps + 0.5
        fun quad(u0: Float, u1: Float, v0: Float, v1: Float) = Path().apply {
            moveTo(sx(u0, v0), sy(v0)); lineTo(sx(u1, v0), sy(v0)); lineTo(sx(u1, v1), sy(v1)); lineTo(sx(u0, v1), sy(v1)); close()
        }

        // board: front edge, top face, the played part a shade darker
        drawRect(BoardEdge, Offset(0f, yNear), Size(w, h - yNear))
        drawPath(quad(-w, w * 2, 0f, 1f), Board)
        drawPath(quad(-w, nowU, 0f, 1f), BoardPlayed)

        // beat lines (faint) and bar lines (fret wires)
        var b = Timing.firstAtOrAfter(beatTimes, tMin)
        while (b < beatTimes.size && beatTimes[b] <= tMax) {
            val ub = u(beatTimes[b]); val bar = b % barEvery == 0
            if (ub > gutter) drawLine(if (bar) FretWire else BeatLine, Offset(sx(ub, 0f), sy(0f)), Offset(sx(ub, 1f), sy(1f)), if (bar) 3.dp.toPx() else 1.5.dp.toPx())
            b++
        }
        // strings: solid plain steel on top, bronze-wound below; the string's name on the left
        for (s in 1..strings) {
            val v = vOf(s); val y = sy(v); val thick = (1.6f + (strings - s) * 0.5f).dp.toPx() * (spacing(v) / spacing(0.5f))
            drawLine(if (s > strings / 2) WoundString else PlainString, Offset(sx(-w, v), y), Offset(w + 40f, y), thick)
            val label = stringText[s - 1]
            drawRoundRect(BoardEdge, Offset(sx(gutter / 2f, v) - 11.dp.toPx(), y - 10.dp.toPx()), Size(22.dp.toPx(), 20.dp.toPx()), CornerRadius(6.dp.toPx()))
            drawText(label, topLeft = Offset(sx(gutter / 2f, v) - label.size.width / 2f, y - label.size.height / 2f))
        }
        // timing line and a target outline per string
        drawLine(Color.White, Offset(sx(nowU, -0.03f), sy(-0.03f)), Offset(sx(nowU, 1.03f), sy(1.03f)), 3.dp.toPx())
        val noteW = 34.dp.toPx(); val maxSpacing = spacing(0f)
        fun noteSize(v: Float) = Size(noteW * (0.75f + 0.25f * spacing(v) / maxSpacing), spacing(v) * 0.74f)
        for (s in 1..strings) {
            val v = vOf(s); val ns = noteSize(v)
            skewed(sx(nowU, v), sy(v), k) {
                drawRoundRect(Color.White.copy(alpha = 0.5f), Offset(-ns.width / 2, -ns.height / 2), ns, CornerRadius(ns.height / 2), style = Stroke(2.dp.toPx()))
            }
        }

        // notes, clipped to the board right of the string names (chord names float above it)
        clipPath(quad(gutter, w + 80f, -0.1f, 1f + chordRoom / depthPx)) {
            var i = maxOf(0, Timing.firstAtOrAfter(times, tMin - 4.0))
            while (i < times.size && times[i] <= tMax) {
                val e = song.events[i]; val ue = u(times[i]); val j = judged[i]
                // sustain: the note's duration at practice speed, cut 10 % before the next event
                val dur = e.dur / speed
                val gap = times.getOrElse(i + 1) { times[i] + dur } - times[i]
                val tailW = (minOf(dur, gap * 0.9) * pps).toFloat()
                if (ue + tailW < gutter - noteW) { i++; continue }
                val age = (songTime - times[i]).toFloat()          // > 0 once the note has crossed the line
                val hit = j != null && (j.kind == Kind.HIT || j.kind == Kind.PARTIAL)
                val bad = j != null && (j.kind == Kind.MISS || j.kind == Kind.WRONG)
                if (e.notes.size >= 2) {
                    val vs = e.notes.map { vOf(it.string) }
                    val v0 = (vs.min() - 0.55f / strings).coerceAtLeast(0f); val v1 = (vs.max() + 0.55f / strings).coerceAtMost(1f)
                    drawPath(quad(ue - noteW * 0.65f, ue + maxOf(noteW * 0.65f, tailW), v0, v1), Color.White.copy(alpha = if (ue < nowU) 0.04f else 0.08f))
                    e.chord?.let { name -> chordText[name]?.let { t ->
                        val cx = sx(ue, 1f) + t.size.width / 2f - noteW / 2; val cy = sy(1f) - 8.dp.toPx() - t.size.height / 2f
                        skewed(cx, cy, k) {
                            drawText(t, color = if (bad) MuColors.wrong else Color.White, topLeft = Offset(-t.size.width / 2f, -t.size.height / 2f), alpha = if (ue < nowU) 0.4f else 1f)
                        }
                    } }
                }
                for (n in e.notes) {
                    val v = vOf(n.string); val cx = sx(ue, v); val cy = sy(v)
                    val ns = noteSize(v); val nh = ns.height; val nw = ns.width
                    val col = when { j?.kind == Kind.WRONG -> MuColors.wrong; j?.kind == Kind.MISS -> MissNote; else -> fretColor(n.fret) }
                    if (hit && age in 0f..0.4f) {
                        val kk = age / 0.4f
                        skewed(sx(nowU, v), cy, k) {
                            drawRoundRect(Color.White.copy(alpha = (1f - kk) * 0.9f), Offset(-nw / 2 - kk * nh, -nh / 2 - kk * nh * 0.6f),
                                Size(nw + 2 * kk * nh, nh + 1.2f * kk * nh), CornerRadius(nh / 2 + kk * nh), style = Stroke(3.dp.toPx() * (1f - kk) + 1f))
                        }
                    }
                    // One solid bar for the note's whole length, number on the head only. Whatever has crossed the
                    // timing line is played and dims; what is still to play stays full strength.
                    skewed(cx, cy, k) {
                        val x0 = -nw / 2; val x1 = maxOf(nw / 2, tailW)
                        val split = (nowU - ue).coerceIn(x0, x1)          // local x of the timing line
                        val gone = ((ue + x1 - gutter) / (nowU - gutter)).coerceIn(0f, 1f)   // fades out towards the gutter
                        fun bar(a: Float, b: Float, alpha: Float) {
                            if (b - a < 1f || alpha <= 0f) return
                            drawRoundRect(col.copy(alpha = alpha), Offset(a, -nh / 2), Size(b - a, nh), CornerRadius(minOf(nh / 2, (b - a) / 2)))
                        }
                        bar(x0, split, 0.35f * gone)
                        bar(split, x1, 1f)
                        fretText[n.fret]?.let {
                            val ts = (nh / it.size.height * 0.9f).coerceAtMost(1.15f)
                            withTransform({ scale(ts, ts, Offset.Zero) }) {
                                drawText(it, topLeft = Offset(-it.size.width / 2f, -it.size.height / 2f), alpha = if (split > 0f) 0.45f * gone else 1f)
                            }
                        }
                    }
                }
                i++
            }
        }
    }
}

/** Draws [block] around (cx, cy) with the board's lean applied (things lean right as they go up). */
private inline fun DrawScope.skewed(cx: Float, cy: Float, k: Float, crossinline block: DrawScope.() -> Unit) {
    val m = Matrix().apply { values[Matrix.SkewX] = -k }
    withTransform({ translate(cx, cy); transform(m) }) { block() }
}

/**
 * The whole song at a glance: one mark per note on its string row, coloured by string until judged, sections as
 * ticks, the part already played dimmed and the stretch currently on the highway framed. [times] are at practice
 * speed, so the timeline is too.
 */
@Composable
fun SongMinimap(song: Song, times: DoubleArray, songTime: Double, endTime: Double, judged: Map<Int, Judgement>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val end = maxOf(endTime, 1.0)
        val speed = song.endTime / end    // section starts are at 100 %
        val r = CornerRadius(8.dp.toPx())
        drawRoundRect(Color(0xFF101114), cornerRadius = r)
        val strings = song.stringCount; val pad = 5.dp.toPx(); val rowH = (h - 2 * pad) / strings
        fun x(t: Double) = (t / end * w).toFloat()
        for (sec in song.sections) {
            val xs = x(sec.start / speed)
            drawLine(Color(0x33FFFFFF), Offset(xs, 0f), Offset(xs, h), 1.5f)
        }
        val mw = maxOf(2f, w / 400f)
        for (i in times.indices) {
            val e = song.events[i]; val xe = x(times[i]); val j = judged[i]
            for (n in e.notes) {
                val c = when (j?.kind) { Kind.MISS -> MissNote; Kind.WRONG -> MuColors.wrong; else -> fretColor(n.fret) }
                drawRect(c, Offset(xe, pad + (n.string - 1) * rowH + rowH * 0.15f), Size(mw, rowH * 0.7f))
            }
        }
        if (songTime > 0) drawRect(Color(0x99000000), Offset.Zero, Size(x(songTime).coerceIn(0f, w), h))
        val px = x(songTime.coerceAtLeast(0.0))
        val win = (HIGHWAY_LOOKAHEAD_SEC / end * w).toFloat()
        drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(px, 1f), Size(win, h - 2f), CornerRadius(6.dp.toPx()))
        drawRoundRect(Color.White.copy(alpha = 0.6f), Offset(px, 1f), Size(win, h - 2f), CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        drawLine(Color.White, Offset(px, 0f), Offset(px, h), 2.dp.toPx())
        drawRoundRect(Color.White.copy(alpha = 0.85f), cornerRadius = r, style = Stroke(2.dp.toPx()))   // a clear frame
    }
}
