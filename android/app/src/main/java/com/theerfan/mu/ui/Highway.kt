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

private val BoardFar = Color(0xFF1C1D20)
private val BoardNear = Color(0xFF0E0F11)
private val BoardEdge = Color(0xFF060607)
private val FretWire = Color(0xFF8E9299)
private val PlainString = Color(0xFFD7D9DE)
private val WoundString = Color(0xFFC9A46A)
private val NoteText = Color(0xFF111214)

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
            for (f in 0..30) m[f] = measurer.measure(f.toString(), TextStyle(color = NoteText, fontSize = 17.sp, fontWeight = FontWeight.Black))
        }
    }
    val chordText = remember(song, measurer) {
        song.events.mapNotNull { it.chord }.distinct().associateWith { measurer.measure(it, TextStyle(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)) }
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
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0C0D), Color.Black)))
        val labelH = 40.dp.toPx(); val edgeH = 9.dp.toPx(); val room = h - labelH - edgeH - 6.dp.toPx()
        val depthPx = minOf(room, strings * 50.dp.toPx())
        val yNear = labelH + depthPx + (room - depthPx) / 2
        val k = minOf(0.3f, 0.15f * w / depthPx)   // lean of the fret lines (gentler on narrow screens)
        val a = 0.45f                      // perspective: lanes get closer together towards the far edge
        fun depth(v: Float) = depthPx * v * (1 + a) / (1 + a * v)
        fun spacing(v: Float) = depthPx * (1 + a) / ((1 + a * v) * (1 + a * v)) / strings   // local lane spacing
        fun vOf(s: Int) = (strings - s + 0.5f) / strings
        fun sx(u: Float, v: Float) = u + k * depth(v)
        fun sy(v: Float) = yNear - depth(v)
        val gutter = 30.dp.toPx()
        val nowU = gutter + (w - gutter) * 0.16f
        // pixels per second: about HIGHWAY_LOOKAHEAD_SEC of look-ahead, within limits that keep notes readable
        val pps = ((w - nowU) / HIGHWAY_LOOKAHEAD_SEC).coerceIn(150.dp.toPx(), 280.dp.toPx())
        fun u(t: Double) = nowU + ((t - songTime) * pps).toFloat()
        val tMin = songTime - nowU / pps - 0.5; val tMax = songTime + (w - nowU) / pps + 0.5
        fun quad(u0: Float, u1: Float, v0: Float, v1: Float) = Path().apply {
            moveTo(sx(u0, v0), sy(v0)); lineTo(sx(u1, v0), sy(v0)); lineTo(sx(u1, v1), sy(v1)); lineTo(sx(u0, v1), sy(v1)); close()
        }

        // board: front edge, top face, darker played zone
        drawRect(BoardEdge, Offset(0f, yNear), Size(w, edgeH))
        drawLine(Color(0x33FFFFFF), Offset(0f, yNear), Offset(w, yNear), 1.5f)
        drawPath(quad(0f, w + 10f, 0f, 1f), Brush.verticalGradient(listOf(BoardFar, BoardNear), startY = sy(1f), endY = yNear))
        drawPath(quad(0f, nowU, 0f, 1f), Color(0x55000000))

        // beat lines (thin) and bar lines (fret wires)
        var b = Timing.firstAtOrAfter(beatTimes, tMin)
        while (b < beatTimes.size && beatTimes[b] <= tMax) {
            val ub = u(beatTimes[b]); val bar = b % barEvery == 0
            if (ub > gutter) {
                if (bar) {
                    drawLine(FretWire.copy(alpha = 0.55f), Offset(sx(ub, 0f), sy(0f)), Offset(sx(ub, 1f), sy(1f)), 3.5f)
                    drawLine(Color.White.copy(alpha = 0.25f), Offset(sx(ub, 0f) - 1.5f, sy(0f)), Offset(sx(ub, 1f) - 1.5f, sy(1f)), 1f)
                } else drawLine(Color(0x16FFFFFF), Offset(sx(ub, 0f), sy(0f)), Offset(sx(ub, 1f), sy(1f)), 1.5f)
            }
            b++
        }
        // strings: wound low strings in bronze, plain ones silver; a coloured name on the left
        for (s in 1..strings) {
            val v = vOf(s); val y = sy(v); val thick = (0.9f + (strings - s + 1) * 0.55f) * (spacing(v) / spacing(0.5f))
            val col = if (s > strings / 2) WoundString else PlainString
            drawLine(Color.Black.copy(alpha = 0.6f), Offset(sx(gutter, v), y + thick + 1.5f), Offset(w + 40f, y + thick + 1.5f), thick)   // shadow
            drawLine(col.copy(alpha = 0.8f), Offset(sx(gutter, v), y), Offset(w + 40f, y), thick)
            val label = stringText[s - 1]
            drawText(label, topLeft = Offset(sx(gutter / 2f, v) - label.size.width / 2f, y - label.size.height / 2f))
        }
        // timing line: slanted glow + bright core, with a target ring on every string
        val lineA = Offset(sx(nowU, -0.02f), sy(-0.02f)); val lineB = Offset(sx(nowU, 1.02f), sy(1.02f))
        drawLine(MuColors.primary.copy(alpha = 0.10f), lineA, lineB, 30.dp.toPx())
        drawLine(MuColors.primary.copy(alpha = 0.25f), lineA, lineB, 10.dp.toPx())
        drawLine(Color.White, lineA, lineB, 3.5f)
        val noteW = 34.dp.toPx(); val maxSpacing = spacing(0f)
        fun noteSize(v: Float) = Size(noteW * (0.75f + 0.25f * spacing(v) / maxSpacing), spacing(v) * 0.66f)
        for (s in 1..strings) {
            val v = vOf(s); val ns = noteSize(v)
            skewed(sx(nowU, v), sy(v), k) {
                drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(-ns.width / 2, -ns.height / 2), ns, CornerRadius(ns.height / 2), style = Stroke(2.5f))
            }
        }

        // notes, clipped to the board right of the string names (chord names may rise above it)
        clipPath(quad(gutter, w + 80f, -0.1f, 1f + labelH / depthPx * 1.5f)) {
            var i = maxOf(0, Timing.firstAtOrAfter(times, tMin - 4.0))
            while (i < times.size && times[i] <= tMax) {
                val e = song.events[i]; val ue = u(times[i]); val j = judged[i]
                // sustain tail: the note's duration at practice speed, cut 10 % before the next event
                val dur = e.dur / speed
                val gap = times.getOrElse(i + 1) { times[i] + dur } - times[i]
                val tailW = (minOf(dur, gap * 0.9) * pps).toFloat()
                if (ue + tailW < gutter - noteW) { i++; continue }
                val age = (songTime - times[i]).toFloat()          // > 0 once the note has crossed the line
                val hit = j != null && (j.kind == Kind.HIT || j.kind == Kind.PARTIAL)
                val fade = if (ue < nowU) (0.25f + 0.75f * ((ue - gutter) / (nowU - gutter)).coerceIn(0f, 1f)) else 1f
                if (e.notes.size >= 2) {
                    val vs = e.notes.map { vOf(it.string) }
                    val v0 = (vs.min() - 0.6f / strings).coerceAtLeast(0f); val v1 = (vs.max() + 0.6f / strings).coerceAtMost(1f)
                    val region = quad(ue - noteW * 0.7f, ue + maxOf(noteW * 0.7f, tailW), v0, v1)
                    val c = j?.let { judgementColor(it) } ?: Color.White
                    drawPath(region, c.copy(alpha = 0.08f * fade))
                    drawPath(region, c.copy(alpha = 0.28f * fade), style = Stroke(2f))
                    e.chord?.let { name -> chordText[name]?.let { t ->
                        val cx = sx(ue, 1f); val cy = sy(1f) - 6.dp.toPx() - t.size.height / 2f
                        skewed(cx, cy, k) {
                            drawText(t, color = j?.let { jj -> judgementColor(jj) } ?: Color.White, topLeft = Offset(-t.size.width / 2f, -t.size.height / 2f), alpha = fade)
                        }
                    } }
                }
                for (n in e.notes) {
                    val v = vOf(n.string); val cx = sx(ue, v); val cy = sy(v)
                    val sc = spacing(v) / maxSpacing
                    val ns = noteSize(v); val nh = ns.height; val nw = ns.width
                    val lane = MuColors.strings[(n.string - 1) % 6]
                    val col = j?.let { judgementColor(it) } ?: lane
                    if (tailW > nw) drawPath(quad(ue, ue + tailW, v - 0.12f / strings, v + 0.12f / strings), col.copy(alpha = 0.38f * fade))
                    if (hit && age in 0f..0.45f) {
                        val kk = age / 0.45f
                        skewed(sx(nowU, v), cy, k) {
                            drawOval(col.copy(alpha = (1f - kk) * 0.9f), Offset(-nh * (0.7f + 1.6f * kk), -nh * (0.5f + 1.1f * kk)),
                                Size(nh * (1.4f + 3.2f * kk), nh * (1f + 2.2f * kk)), style = Stroke(4f * (1f - kk) + 1f))
                        }
                    }
                    skewed(cx, cy, k) {
                        val r = CornerRadius(nh / 2)
                        val glowA = if (j == null && ue in nowU - nw..nowU + nw * 3) 0.4f else 0.14f
                        drawRoundRect(col.copy(alpha = glowA * fade), Offset(-nw / 2 - 6f, -nh / 2 - 6f), Size(nw + 12f, nh + 12f), CornerRadius(nh / 2 + 6f))
                        drawRoundRect(lerp(col, Color.Black, 0.55f).copy(alpha = fade), Offset(-nw / 2, -nh / 2 + 5f * sc), Size(nw, nh), r)  // slab side
                        drawRoundRect(col.copy(alpha = fade), Offset(-nw / 2, -nh / 2), Size(nw, nh), r)
                        drawRoundRect(lerp(col, Color.White, 0.5f).copy(alpha = 0.5f * fade), Offset(-nw / 2 + nh * 0.2f, -nh * 0.4f),
                            Size(nw - nh * 0.4f, nh * 0.22f), CornerRadius(nh * 0.11f))
                        fretText[n.fret]?.let {
                            val ts = (nh / it.size.height * 0.95f).coerceAtMost(1.15f)
                            withTransform({ scale(ts, ts, Offset.Zero) }) {
                                drawText(it, topLeft = Offset(-it.size.width / 2f, -it.size.height / 2f), alpha = fade)
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
        drawRoundRect(Color(0xFF0E0F11), cornerRadius = CornerRadius(10.dp.toPx()))
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
                val c = j?.let { judgementColor(it) } ?: MuColors.strings[(n.string - 1) % 6]
                drawRect(c, Offset(xe, pad + (n.string - 1) * rowH + rowH * 0.15f), Size(mw, rowH * 0.7f))
            }
        }
        if (songTime > 0) drawRect(Color(0x99000000), Offset.Zero, Size(x(songTime).coerceIn(0f, w), h))
        val px = x(songTime.coerceAtLeast(0.0))
        val win = (HIGHWAY_LOOKAHEAD_SEC / end * w).toFloat()
        drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(px, 1f), Size(win, h - 2f), CornerRadius(6.dp.toPx()))
        drawRoundRect(Color.White.copy(alpha = 0.6f), Offset(px, 1f), Size(win, h - 2f), CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        drawLine(Color.White, Offset(px, 0f), Offset(px, h), 2.dp.toPx())
    }
}
