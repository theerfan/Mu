package com.theerfan.mu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theerfan.mu.play.Grade
import com.theerfan.mu.play.Judgement
import com.theerfan.mu.play.Kind
import com.theerfan.mu.song.Difficulty
import com.theerfan.mu.song.Song
import com.theerfan.mu.song.SongEvent
import com.theerfan.mu.song.SongNote
import com.theerfan.mu.song.Timing
import kotlin.math.hypot

fun judgementColor(j: Judgement): Color = when (j.kind) {
    Kind.HIT -> when (j.grade) { Grade.PERFECT -> MuColors.perfect; Grade.GOOD -> MuColors.good; else -> MuColors.ok }
    Kind.PARTIAL -> MuColors.partial
    Kind.WRONG -> MuColors.wrong
    Kind.MISS -> MuColors.miss
    Kind.EXTRA -> MuColors.miss
}

// Flat charcoal board, bright strings, fret-coloured note capsules. The lime field behind it is drawn by the caller.
private val Board = Color(0xFF232323)
private val BoardLine = Color(0xFF0E0E0E)
private val MissNote = Color(0xFF5C616B)
private val FutureDot = Color(0xFFB5B5B5)
private val Tick = Color(0xFFE7FF8A)

/** Note colours by fret (open strings grey, then a fixed colour per fret, repeating from 6). */
private val FretColors = listOf(
    Color(0xFF8E939C), Color(0xFFF39B1F), Color(0xFFC43AD8), Color(0xFF2B9FE0), Color(0xFFE23B55), Color(0xFF2EBE6A),
)
fun fretColor(fret: Int): Color = if (fret <= 0) FretColors[0] else FretColors[1 + (fret - 1) % (FretColors.size - 1)]

/** Chord blocks, by root pitch class. A is green and D is blue, matching the play-screen design. */
private val ChordPalette = listOf(
    Color(0xFFE24B4B), Color(0xFFE0569A), Color(0xFF2F86D6), Color(0xFF7A45D4),
    Color(0xFFF08A1C), Color(0xFFD4A017), Color(0xFF1AA39A), Color(0xFFE07A28),
    Color(0xFFC43AD0), Color(0xFF5FBF2E), Color(0xFFE05A78), Color(0xFF3AA0E0),
)

private fun chordRoot(name: String?): Int? {
    if (name.isNullOrBlank()) return null
    val n = name.trim()
    val base = when (n[0].uppercaseChar()) {
        'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B' -> 11
        else -> return null
    }
    val acc = when {
        n.length > 1 && (n[1] == '#' || n[1] == '♯') -> 1
        n.length > 1 && (n[1] == 'b' || n[1] == '♭') -> -1
        else -> 0
    }
    return (base + acc).mod(12)
}

private fun chordFill(name: String?, midi: Int): Color = ChordPalette[chordRoot(name) ?: (midi.mod(12))]

private fun chordLabel(e: SongEvent): String = e.chord ?: Difficulty.chordName(e)

/** True when the tab marks an up-strum. Anything else (including a plain chord) is a down-strum. */
private fun strumUp(e: SongEvent): Boolean = e.techNames.any { it == "strum_up" }

private fun Color.lighten(by: Float) = Color(
    red + (1f - red) * by, green + (1f - green) * by, blue + (1f - blue) * by, alpha,
)
private fun Color.darken(by: Float) = Color(red * (1f - by), green * (1f - by), blue * (1f - by), alpha)

/** One chord on the highway: a slab that covers only the strings it uses. */
private class ChordMark(
    val index: Int,
    val x0: Float, val x1: Float,
    val yTop: Float, val yBot: Float,
    val up: Boolean,
    val color: Color,
    val name: String,
    val fade: Float,
    val notes: List<SongNote>,
)

/** Seconds of look-ahead the highway aims for. */
const val HIGHWAY_LOOKAHEAD_SEC = 2.2f

/**
 * Face-on fretboard. Notes scroll right to left toward a playhead about a third of the way in. A single note is a
 * fret-coloured bar along its string, from the attack to the end of the note, number on the head. A chord is a slab
 * that sits on the strings it uses (the rest of the board stays visible), filled with chevrons that point the strum:
 * down toward the low strings, up toward the high ones. The shape itself floats above the board as a fret diagram.
 * A white ball rides a dotted pitch arc from note to note (the arc hops over chord blocks). Hits flash, misses fade.
 * Text layouts are measured once and cached.
 *
 * String 1 (high e) is the top string, as in tab. Chord diagrams use the same order.
 */
@Composable
fun Highway(
    song: Song, speed: Double, times: DoubleArray, beatTimes: DoubleArray, barEvery: Int, songTime: Double,
    judged: Map<Int, Judgement>, modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val fretText = remember(measurer) {
        HashMap<Int, androidx.compose.ui.text.TextLayoutResult>().also { m ->
            for (f in 0..30) m[f] = measurer.measure(
                f.toString(),
                TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black),
            )
        }
    }
    val chordText = remember(song, measurer) {
        song.events.filter { it.notes.size >= 2 }.map { chordLabel(it) }.distinct().associateWith { name ->
            val sp = when { name.length <= 2 -> 30.sp; name.length <= 4 -> 22.sp; else -> 16.sp }
            measurer.measure(name, TextStyle(color = Color.White, fontSize = sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic))
        }
    }
    val cardTitle = remember(song, measurer) {
        song.events.map { e ->
            if (e.notes.size < 2) null
            else measurer.measure(chordLabel(e), TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
    }
    val fretNums = remember(measurer) {
        val style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        (1..15).associateWith { measurer.measure(it.toString(), style) }
    }
    val strings = song.stringCount
    val melodyOnly = remember(song) { song.events.count { it.notes.size < 2 } >= 2 }
    val denseGap = remember(times) {
        val gaps = times.toList().zipWithNext { a, b -> b - a }.filter { it > 0.03 }.sorted()
        if (gaps.isEmpty()) 1.0 else gaps[(gaps.size * 0.15).toInt().coerceAtMost(gaps.size - 1)]
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bottomGap = 8.dp.toPx()
        val minTop = 52.dp.toPx()
        // The board is the main band of the screen: tall enough that the six strings are easy to read.
        val cap = h * 0.70f
        val floor = minOf(strings * 34.dp.toPx(), cap)
        var boardH = (h * 0.62f).coerceIn(floor, cap)
        var boardTop = h - bottomGap - boardH
        if (boardTop < minTop) {
            boardTop = minTop.coerceAtMost(h * 0.36f)
            boardH = (h - bottomGap - boardTop).coerceAtLeast(minOf(strings * 22.dp.toPx(), h * 0.5f))
        }
        val boardBottom = boardTop + boardH
        val inset = boardH * 0.09f
        val spacing = (boardH - 2 * inset) / strings
        fun sy(s: Int): Float {
            val c = s.coerceIn(1, strings)
            return boardTop + inset + (c - 1) * spacing + spacing / 2f
        }
        val nowU = w * 0.32f
        val pps = maxOf(
            ((w - nowU) / HIGHWAY_LOOKAHEAD_SEC).coerceIn(140.dp.toPx(), 300.dp.toPx()),
            (34.dp.toPx() / denseGap).toFloat(),
        ).coerceAtMost(520.dp.toPx())
        fun u(t: Double) = nowU + ((t - songTime) * pps).toFloat()
        val tMin = songTime - nowU / pps - 0.6
        val tMax = songTime + (w - nowU) / pps + 0.6

        drawRect(Board, Offset(0f, boardTop), Size(w, boardH))
        drawRect(BoardLine, Offset(0f, boardTop), Size(w, 2.5f))
        drawRect(BoardLine, Offset(0f, boardBottom - 2.5f), Size(w, 2.5f))

        val chords = ArrayList<ChordMark>(8)
        var i = maxOf(0, Timing.firstAtOrAfter(times, tMin - 4.0))
        while (i < times.size && times[i] <= tMax) {
            val e = song.events[i]
            if (e.notes.size >= 2) {
                val dur = e.dur / speed
                val gap = times.getOrElse(i + 1) { times[i] + dur } - times[i]
                val span = maxOf(dur, 0.12).let { if (gap > 0.05) minOf(it, gap * 0.96) else it }
                val x0 = u(times[i])
                val x1 = u(times[i] + span)
                val pastEnd = (songTime - (times[i] + span)).toFloat()
                val fade = if (pastEnd <= 0f) 1f else (1f - pastEnd / 0.35f).coerceIn(0f, 1f)
                if (fade > 0.03f && x1 > 0f && x0 < w) {
                    val topString = e.notes.minOf { it.string }
                    val botString = e.notes.maxOf { it.string }
                    val pad = spacing * 0.48f
                    val yTop = (sy(topString) - pad).coerceAtLeast(boardTop + 3.dp.toPx())
                    val yBot = (sy(botString) + pad).coerceAtMost(boardBottom - 3.dp.toPx())
                    val j = judged[i]
                    val fill = when (j?.kind) {
                        Kind.WRONG -> MuColors.wrong
                        Kind.MISS -> MissNote
                        else -> chordFill(e.chord, e.notes.first().midi)
                    }
                    chords += ChordMark(i, x0, x1, yTop, yBot, strumUp(e), fill, chordLabel(e), fade, e.notes)
                }
            }
            i++
        }

        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.5.dp.toPx()))
        var b = Timing.firstAtOrAfter(beatTimes, tMin)
        while (b < beatTimes.size && beatTimes[b] <= tMax) {
            val x = u(beatTimes[b])
            val bar = b % barEvery == 0
            if (x > -2f && x < w + 2f) {
                drawLine(
                    Color.White.copy(alpha = if (bar) 0.30f else 0.10f),
                    Offset(x, boardTop), Offset(x, boardBottom),
                    if (bar) 1.6.dp.toPx() else 1.dp.toPx(),
                )
                if (bar) {
                    drawLine(Tick, Offset(x, boardTop - 18.dp.toPx()), Offset(x, boardTop), 1.6.dp.toPx(), pathEffect = dash)
                    drawLine(Tick, Offset(x, boardBottom), Offset(x, boardBottom + 8.dp.toPx()), 1.6.dp.toPx(), pathEffect = dash)
                }
            }
            b++
        }
        for (s in 1..strings) {
            val y = sy(s)
            val thick = (1.05f + (strings - s) * 0.28f).dp.toPx()
            drawLine(Color.White.copy(alpha = 0.94f), Offset(0f, y), Offset(w, y), thick)
        }

        // The slab covers only the strings in the chord, so the rest of the board stays visible. Strings are
        // redrawn across it so the block reads as sitting on them.
        for (c in chords) drawChordSlab(c, nowU)
        for (s in 1..strings) {
            val y = sy(s)
            val thick = (1.05f + (strings - s) * 0.28f).dp.toPx()
            drawLine(Color.White.copy(alpha = 0.92f), Offset(0f, y), Offset(w, y), thick)
        }

        // A note is a capsule along its string from the attack to the end of its duration. The fret number sits
        // on the head (the start). Whatever has already crossed the playhead is dimmed.
        val nh = spacing * 0.62f
        val headW = maxOf(nh * 1.55f, 30.dp.toPx())
        i = maxOf(0, Timing.firstAtOrAfter(times, tMin - 4.0))
        while (i < times.size && times[i] <= tMax) {
            val e = song.events[i]
            if (e.notes.size < 2) {
                val ue = u(times[i])
                val dur = e.dur / speed
                val gap = times.getOrElse(i + 1) { times[i] + dur } - times[i]
                val span = maxOf(dur, 0.08).let { if (gap > 0.05) minOf(it, gap * 0.94) else it }
                val x0 = ue
                val x1 = ue + maxOf(headW, (span * pps).toFloat())
                val age = (songTime - times[i]).toFloat()
                val pastEnd = (songTime - (times[i] + span)).toFloat()
                val fade = if (pastEnd <= 0f) 1f else (1f - pastEnd / 0.35f).coerceIn(0f, 1f)
                if (fade > 0.03f && x1 > -8f && x0 < w + 8f) {
                    val n = e.notes[0]
                    val j = judged[i]
                    val cy = sy(n.string)
                    val col = when (j?.kind) {
                        Kind.WRONG -> MuColors.wrong
                        Kind.MISS -> MissNote
                        else -> fretColor(n.fret)
                    }
                    val radius = CornerRadius(nh / 2f)
                    val top = cy - nh / 2f
                    drawRoundRect(Color.Black.copy(alpha = 0.22f * fade), Offset(x0, top + 1.8.dp.toPx()), Size(x1 - x0, nh), radius)
                    drawRoundRect(col.copy(alpha = fade), Offset(x0, top), Size(x1 - x0, nh), radius)
                    val playedTo = nowU.coerceIn(x0, x1)
                    if (playedTo - x0 > 1f) {
                        clipRect(x0, top, playedTo, top + nh) {
                            drawRoundRect(Color.Black.copy(alpha = 0.5f), Offset(x0, top), Size(x1 - x0, nh), radius)
                        }
                    }
                    val hit = j != null && (j.kind == Kind.HIT || j.kind == Kind.PARTIAL)
                    if (hit && age in 0f..0.35f) {
                        val k = age / 0.35f
                        drawRoundRect(
                            Color.White.copy(alpha = (1f - k) * 0.8f),
                            Offset(x0 - k * nh, top - k * nh * 0.35f),
                            Size((x1 - x0) + 2 * k * nh, nh * (1f + 0.7f * k)),
                            CornerRadius(nh), style = Stroke(2.5.dp.toPx() * (1f - k) + 1f),
                        )
                    }
                    fretText[n.fret]?.let { layout ->
                        val numCx = x0 + minOf(headW, x1 - x0) / 2f
                        val numAlpha = fade * if (numCx < nowU) (1f - age / 0.45f).coerceIn(0f, 0.4f) else 1f
                        if (numAlpha > 0.05f) {
                            val ts = (nh / layout.size.height * 0.78f).coerceIn(0.5f, 1.35f)
                            withTransform({ translate(numCx, cy); scale(ts, ts, Offset.Zero) }) {
                                drawText(layout, topLeft = Offset(-layout.size.width / 2f, -layout.size.height / 2f), alpha = numAlpha)
                            }
                        }
                    }
                }
            }
            i++
        }

        for (c in chords) {
            val layout = chordText[c.name] ?: continue
            drawChordName(c, layout)
        }

        drawMelody(song, times, melodyOnly, songTime, tMin, tMax, pps, ::u, ::sy, boardTop)
        drawChordCards(chords, songTime, times, strings, cardTitle, fretNums, boardTop, nowU)
    }
}

/** Parallelogram sitting on the strings: the top edge leans right, like the slab is lying on the board. */
private fun ChordMark.face(): Path {
    val h = (yBot - yTop).coerceAtLeast(1f)
    val shear = h * 0.18f
    val taper = minOf(h * 0.05f, (x1 - x0) * 0.06f)
    return Path().apply {
        moveTo(x0, yBot)
        lineTo(x1, yBot)
        lineTo(x1 + shear - taper, yTop)
        lineTo(x0 + shear + taper, yTop)
        close()
    }
}

private fun DrawScope.drawChordSlab(c: ChordMark, nowU: Float) {
    val face = c.face()
    val h = (c.yBot - c.yTop).coerceAtLeast(1f)
    val shear = h * 0.18f
    val depth = 3.5.dp.toPx()
    val side = Path().apply {
        moveTo(c.x0, c.yBot)
        lineTo(c.x0 + shear, c.yTop)
        lineTo(c.x0 + shear - depth, c.yTop)
        lineTo(c.x0 - depth, c.yBot)
        close()
    }
    drawPath(side, c.color.darken(0.22f).copy(alpha = c.fade))
    drawPath(face, c.color.copy(alpha = c.fade))
    clipPath(face) {
        drawStrumChevrons(c.x0 - 4f, c.x1 + shear + 4f, c.yTop, c.yBot, c.up, Color.White.copy(alpha = 0.55f * c.fade))
    }
    val playedTo = nowU.coerceIn(c.x0, c.x1)
    if (playedTo - c.x0 > 1f) {
        clipRect(c.x0 - depth, c.yTop, playedTo, c.yBot) {
            drawPath(face, Color.Black.copy(alpha = 0.38f * c.fade))
        }
    }
    drawLine(
        Color.White.copy(alpha = 0.40f * c.fade),
        Offset(c.x0 + shear, c.yTop), Offset(c.x1 + shear, c.yTop), 1.6.dp.toPx(),
    )
}

/** Separate chevrons, every one pointing the same way. Down goes toward the low strings. */
private fun DrawScope.drawStrumChevrons(x0: Float, x1: Float, y0: Float, y1: Float, up: Boolean, color: Color) {
    val col = 28.dp.toPx()
    val row = 18.dp.toPx()
    val amp = 6.2.dp.toPx()
    val half = col * 0.36f
    val sw = 3.4.dp.toPx()
    var y = y0 + row * 0.55f
    var rowIndex = 0
    while (y < y1 - amp * 0.2f) {
        val tipY = y + if (up) -amp else amp
        val wingY = y + if (up) amp * 0.15f else -amp * 0.15f
        var x = x0 + if (rowIndex % 2 == 0) col * 0.35f else col * 0.85f
        while (x < x1) {
            drawLine(color, Offset(x - half, wingY), Offset(x, tipY), sw, cap = StrokeCap.Round)
            drawLine(color, Offset(x, tipY), Offset(x + half, wingY), sw, cap = StrokeCap.Round)
            x += col
        }
        y += row
        rowIndex++
    }
}

private fun DrawScope.drawChordName(c: ChordMark, layout: TextLayoutResult) {
    val face = c.face()
    val h = (c.yBot - c.yTop).coerceAtLeast(1f)
    val shear = h * 0.18f
    val availH = h * 0.72f
    val availW = (c.x1 - c.x0) * 0.78f
    val sc = minOf(availH / layout.size.height, availW / layout.size.width).coerceIn(0.45f, 1.15f)
    // Clear the slanted leading edge so the italic name isn't clipped by it.
    val tx = c.x0 + shear * 0.72f + 16.dp.toPx()
    val ty = (c.yTop + c.yBot) / 2f
    val shadow = Offset(1.2.dp.toPx(), 1.4.dp.toPx())
    val labelW = layout.size.width * sc
    val labelH = layout.size.height * sc
    val padX = 8.dp.toPx()
    val padY = 3.dp.toPx()
    clipPath(face) {
        drawRoundRect(
            Color.Black.copy(alpha = 0.28f * c.fade),
            Offset(tx - padX, ty - labelH / 2f - padY),
            Size(labelW + padX * 2f, labelH + padY * 2f),
            CornerRadius(8.dp.toPx()),
        )
        withTransform({ translate(tx + shadow.x, ty + shadow.y); scale(sc, sc, Offset.Zero) }) {
            drawText(layout, color = Color.Black.copy(alpha = 0.35f * c.fade), topLeft = Offset(0f, -layout.size.height / 2f))
        }
        withTransform({ translate(tx, ty); scale(sc, sc, Offset.Zero) }) {
            drawText(layout, alpha = c.fade, topLeft = Offset(0f, -layout.size.height / 2f))
        }
    }
}

/**
 * Up to two shape cards in the lime above the board: the chord you're on (or about to play) and the next one.
 * Each card is a small tab of the real notes — open rings, muted crosses, fret-coloured dots.
 */
private fun DrawScope.drawChordCards(
    chords: List<ChordMark>, songTime: Double, times: DoubleArray, stringCount: Int,
    titles: List<TextLayoutResult?>, frets: Map<Int, TextLayoutResult>, boardTop: Float, nowU: Float,
) {
    if (boardTop < 78.dp.toPx() || chords.isEmpty()) return
    val upcoming = chords.filter { c ->
        val start = times[c.index]
        start < songTime + HIGHWAY_LOOKAHEAD_SEC + 0.15 && c.x1 > nowU - 40f
    }.take(2)
    if (upcoming.isEmpty()) return
    // Leave the top of the lime for the count-in and the score. Cards sit just above the board.
    val reservedTop = 52.dp.toPx()
    val aboveBoard = 6.dp.toPx()
    val cardH = (boardTop - reservedTop - aboveBoard).coerceIn(70.dp.toPx(), 108.dp.toPx())
    val cardTop = (boardTop - aboveBoard - cardH).coerceAtLeast(reservedTop * 0.55f)
    val cardW = cardH * 1.42f
    val safeL = 96.dp.toPx()
    val safeR = size.width - 74.dp.toPx()
    val gap = 8.dp.toPx()
    var prevRight = safeL - gap
    for (c in upcoming) {
        var x = c.x0.coerceIn(safeL, (safeR - cardW).coerceAtLeast(safeL))
        if (x < prevRight + gap) x = prevRight + gap
        if (x + cardW > safeR) x = (safeR - cardW).coerceAtLeast(safeL)
        val title = titles.getOrNull(c.index)
        drawChordCard(c, title, stringCount, frets, x, cardTop, cardW, cardH)
        prevRight = x + cardW
    }
}

private fun DrawScope.drawChordCard(
    c: ChordMark, title: TextLayoutResult?, stringCount: Int, frets: Map<Int, TextLayoutResult>,
    left: Float, top: Float, cw: Float, ch: Float,
) {
    val r = CornerRadius(12.dp.toPx())
    drawRoundRect(Color.Black.copy(alpha = 0.22f * c.fade), Offset(left, top + 3.dp.toPx()), Size(cw, ch), r)
    drawRoundRect(Color(0xFF2C4E78).copy(alpha = c.fade), Offset(left, top), Size(cw, ch), r)
    val border = when {
        c.color == MuColors.wrong -> MuColors.wrong
        c.color == MissNote -> Color.White.copy(alpha = 0.35f)
        else -> Color.White.copy(alpha = 0.16f)
    }
    drawRoundRect(border.copy(alpha = border.alpha * c.fade), Offset(left, top), Size(cw, ch), r, style = Stroke(1.4.dp.toPx()))

    val namePad = 7.dp.toPx()
    val nameH = title?.size?.height?.toFloat() ?: 0f
    if (title != null) {
        val maxW = cw - namePad * 2f
        val sc = (maxW / title.size.width).coerceAtMost(1f)
        val nameX = left + namePad
        val nameY = top + 5.dp.toPx()
        withTransform({ translate(nameX, nameY); scale(sc, sc, Offset.Zero) }) {
            drawText(title, alpha = c.fade)
        }
    }

    // The name has its own row. Open rings and mute marks live in the gutter under it, left of the nut.
    val gutter = 13.dp.toPx()
    val numH = 12.dp.toPx()
    val gridLeft = left + namePad + gutter
    val gridRight = left + cw - namePad
    val gridTop = top + 5.dp.toPx() + nameH + 5.dp.toPx()
    val gridBot = top + ch - 5.dp.toPx() - numH
    if (gridBot - gridTop < 8f || gridRight - gridLeft < 8f) return

    val fretCount = 5
    val maxFret = c.notes.maxOf { it.fret }
    val hasOpen = c.notes.any { it.fret == 0 }
    val minPositive = c.notes.filter { it.fret > 0 }.minOfOrNull { it.fret } ?: 1
    val startFret = if (hasOpen || maxFret <= fretCount) 1 else minPositive
    val colW = (gridRight - gridLeft) / fretCount
    val rowSpan = (stringCount - 1).coerceAtLeast(1)
    fun stringY(s: Int) = gridTop + (s - 1) * (gridBot - gridTop) / rowSpan

    for (s in 1..stringCount) {
        val y = stringY(s)
        drawLine(Color.White.copy(alpha = 0.88f * c.fade), Offset(gridLeft, y), Offset(gridRight, y), 1.15.dp.toPx())
    }
    for (f in 0..fretCount) {
        val x = gridLeft + f * colW
        val nut = f == 0 && startFret == 1
        drawLine(
            Color.White.copy(alpha = (if (nut) 0.95f else 0.55f) * c.fade),
            Offset(x, gridTop), Offset(x, gridBot),
            if (nut) 2.6.dp.toPx() else 1.05.dp.toPx(),
        )
    }
    for (f in 0 until fretCount) {
        val label = frets[startFret + f] ?: continue
        val cx = gridLeft + (f + 0.5f) * colW
        drawText(label, alpha = 0.9f * c.fade, topLeft = Offset(cx - label.size.width / 2f, gridBot + 1.5.dp.toPx()))
    }

    val played = c.notes.associateBy { it.string }
    val dotR = minOf(colW, (gridBot - gridTop) / rowSpan) * 0.30f
    val markR = minOf(5.dp.toPx(), dotR)
    for (s in 1..stringCount) {
        val y = stringY(s)
        val n = played[s]
        val mx = gridLeft - gutter * 0.55f
        when {
            n == null -> drawMutedMark(mx, y, markR, c.fade)
            n.fret == 0 -> drawOpenMark(mx, y, markR, c.fade)
            else -> {
                val col = n.fret - startFret
                if (col in 0 until fretCount) {
                    val cx = gridLeft + (col + 0.5f) * colW
                    drawCircle(Color.Black.copy(alpha = 0.28f * c.fade), dotR, Offset(cx, y + 1.dp.toPx()))
                    drawCircle(fretColor(n.fret).copy(alpha = c.fade), dotR, Offset(cx, y))
                }
            }
        }
    }
}

private fun DrawScope.drawOpenMark(cx: Float, cy: Float, r: Float, fade: Float) {
    drawCircle(Color.White.copy(alpha = 0.95f * fade), r, Offset(cx, cy), style = Stroke(1.4.dp.toPx()))
}

private fun DrawScope.drawMutedMark(cx: Float, cy: Float, r: Float, fade: Float) {
    val w = 1.6.dp.toPx()
    val col = Color.White.copy(alpha = 0.9f * fade)
    drawLine(col, Offset(cx - r, cy - r), Offset(cx + r, cy + r), w, cap = StrokeCap.Round)
    drawLine(col, Offset(cx - r, cy + r), Offset(cx + r, cy - r), w, cap = StrokeCap.Round)
}

/** Dotted arc from note to note. The part already travelled is white; the part still ahead is grey. */
private fun DrawScope.drawMelody(
    song: Song, times: DoubleArray, melodyOnly: Boolean, songTime: Double, tMin: Double, tMax: Double, pps: Float,
    u: (Double) -> Float, sy: (Int) -> Float, boardTop: Float,
) {
    fun isNode(idx: Int) = if (melodyOnly) song.events[idx].notes.size < 2 else true
    fun nodeY(e: SongEvent) = if (e.notes.size == 1) sy(e.notes[0].string) else e.notes.map { sy(it.string) }.average().toFloat()

    if (times.isEmpty()) return
    var start = Timing.firstAtOrAfter(times, tMin - 1.5).coerceAtMost(times.lastIndex)
    var p = start - 1
    while (p >= 0 && !isNode(p)) p--
    if (p >= 0) start = p
    val end = (Timing.firstAtOrAfter(times, tMax + 1.5) + 1).coerceAtMost(times.size)
    val nodes = ArrayList<Int>(16)
    for (idx in start until end) if (isNode(idx)) nodes.add(idx)
    if (nodes.isEmpty()) return

    val spacing = 7.5.dp.toPx()
    val dotR = 2.05.dp.toPx()
    var ball: Offset? = null
    val ballR = 7.2.dp.toPx()

    // approach arc so the ball is already moving during the count-in, before the first note
    val first = nodes.first()
    if (songTime < times[first]) {
        val t1 = times[first]
        val t0 = t1 - 1.15
        val y = nodeY(song.events[first])
        val lift = arcLift(1.15f, pps, boardTop, y)
        drawArc(u(t0), y, u(t1), y, lift, t0, t1, songTime, spacing, dotR)
        val s = ((songTime - t0) / (t1 - t0)).toFloat().coerceIn(0f, 1f)
        ball = bezier(u(t0), y, u(t1), y, lift, s)
    }

    for (k in 0 until nodes.size - 1) {
        val a = nodes[k]
        val c = nodes[k + 1]
        val t0 = times[a]
        val t1 = times[c]
        if (t1 < tMin - 0.2 || t0 > tMax + 0.2) continue
        val y0 = nodeY(song.events[a])
        val y1 = nodeY(song.events[c])
        val gap = (t1 - t0).toFloat().coerceAtLeast(0.05f)
        val lift = arcLift(gap, pps, boardTop, minOf(y0, y1))
        drawArc(u(t0), y0, u(t1), y1, lift, t0, t1, songTime, spacing, dotR)
        if (songTime in t0..t1) {
            val s = ((songTime - t0) / (t1 - t0)).toFloat().coerceIn(0f, 1f)
            ball = bezier(u(t0), y0, u(t1), y1, lift, s)
        }
    }
    if (ball == null && songTime >= times[nodes.last()]) {
        val last = nodes.last()
        ball = Offset(u(times[last]), nodeY(song.events[last]))
    }
    ball?.let { o ->
        drawCircle(Color.White.copy(alpha = 0.28f), ballR * 1.7f, o)
        drawCircle(Color.Black.copy(alpha = 0.22f), ballR, o + Offset(0f, 2.dp.toPx()))
        drawCircle(Color.White, ballR, o)
    }
}

private fun DrawScope.arcLift(gapSec: Float, pps: Float, boardTop: Float, y: Float): Float {
    val wanted = gapSec * pps * 0.42f
    val room = (y - 8.dp.toPx()).coerceAtLeast(0f)
    val cap = boardTop * 0.92f
    val hi = minOf(room, cap).coerceAtLeast(14.dp.toPx())
    return wanted.coerceIn(14.dp.toPx(), hi)
}

private fun bezier(x0: Float, y0: Float, x1: Float, y1: Float, lift: Float, t: Float): Offset {
    val cx = (x0 + x1) / 2f
    val cy = minOf(y0, y1) - lift
    val u = 1f - t
    return Offset(u * u * x0 + 2 * u * t * cx + t * t * x1, u * u * y0 + 2 * u * t * cy + t * t * y1)
}

private fun DrawScope.drawArc(
    x0: Float, y0: Float, x1: Float, y1: Float, lift: Float,
    t0: Double, t1: Double, now: Double, spacing: Float, dotR: Float,
) {
    val n = 28
    var px = x0
    var py = y0
    var carry = spacing * 0.5f
    for (step in 1..n) {
        val t = step / n.toFloat()
        val o = bezier(x0, y0, x1, y1, lift, t)
        val dx = o.x - px
        val dy = o.y - py
        val dist = hypot(dx, dy).coerceAtLeast(0.001f)
        var walked = 0f
        while (carry + (dist - walked) >= spacing) {
            val need = spacing - carry
            walked += need
            val f = walked / dist
            val dotT = (step - 1) / n.toFloat() + f / n
            val time = t0 + (t1 - t0) * dotT
            val past = time <= now + 0.02
            drawCircle(if (past) Color.White else FutureDot, if (past) dotR else dotR * 0.85f, Offset(px + dx * f, py + dy * f))
            carry = 0f
        }
        carry += dist - walked
        px = o.x
        py = o.y
    }
}

/** Time ↔ x inside the minimap capsule. Insets by half the height so ticks stay out of the rounded caps. */
internal fun minimapX(t: Double, width: Float, height: Float, end: Double): Float {
    val pad = height / 2f
    val inner = (width - 2 * pad).coerceAtLeast(1f)
    return pad + (t.coerceIn(0.0, end) / end * inner).toFloat()
}

internal fun minimapTime(x: Float, width: Float, height: Float, end: Double): Double {
    val pad = height / 2f
    val inner = (width - 2 * pad).coerceAtLeast(1f)
    return ((x - pad) / inner).coerceIn(0f, 1f) * end
}

/**
 * The whole song as one black capsule of coloured ticks, one per note, taller when the pitch is higher.
 * The stretch already played is dimmed. [times] are at practice speed.
 */
@Composable
fun SongMinimap(song: Song, times: DoubleArray, songTime: Double, endTime: Double, judged: Map<Int, Judgement>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val end = maxOf(endTime, 1.0)
        val r = CornerRadius(h / 2f)
        drawRoundRect(Color(0xFF121212), cornerRadius = r)
        val padY = 3.5.dp.toPx()
        val innerH = (h - 2 * padY).coerceAtLeast(1f)
        fun x(t: Double) = minimapX(t, w, h, end)
        val nNotes = song.events.sumOf { it.notes.size }.coerceAtLeast(1)
        val innerW = (w - h).coerceAtLeast(1f)   // minimapX insets by half the height on each side
        val bw = (innerW / nNotes * 0.8f).coerceIn(1.3f, 3.5.dp.toPx())
        for (i in times.indices) {
            val e = song.events[i]
            val played = songTime >= 0 && times[i] < songTime
            val base = if (played) 0.34f else 1f
            val j = judged[i]
            e.notes.forEachIndexed { k, n ->
                val pitch = ((n.midi - 36) / 46f).coerceIn(0f, 1f)
                val bh = innerH * (0.30f + 0.70f * pitch)
                val c = when (j?.kind) { Kind.MISS -> MissNote; Kind.WRONG -> MuColors.wrong; else -> fretColor(n.fret) }
                drawRoundRect(
                    c.copy(alpha = base),
                    Offset(x(times[i]) + k * bw * 0.45f, padY + (innerH - bh) / 2f),
                    Size(bw, bh),
                    CornerRadius(bw / 2f),
                )
            }
        }
        drawRoundRect(Color.White.copy(alpha = 0.16f), cornerRadius = r, style = Stroke(1.dp.toPx()))
    }
}
