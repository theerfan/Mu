package com.theerfan.mu.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theerfan.mu.play.Judgement
import com.theerfan.mu.song.Song
import kotlin.math.abs
import kotlin.math.roundToInt

/** Holds the screen in landscape while shown (the highway wants the width); restores the previous orientation. */
@Composable
fun LockLandscape() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(activity) {
        val before = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity.requestedOrientation = before }
    }
}

@Composable
fun PauseGlyph(modifier: Modifier, color: Color) = Canvas(modifier) {
    val w = size.width; val h = size.height; val bw = w * 0.26f
    drawRoundRect(color, Offset(w * 0.18f, h * 0.15f), Size(bw, h * 0.7f), CornerRadius(bw / 3))
    drawRoundRect(color, Offset(w * 0.82f - bw, h * 0.15f), Size(bw, h * 0.7f), CornerRadius(bw / 3))
}

fun clock(sec: Double): String { val s = sec.coerceAtLeast(0.0).toInt(); return "%d:%02d".format(s / 60, s % 60) }

/**
 * The song minimap as a scrubber. A tap or drag on the bar jumps playback to that moment. The two handles sit on
 * the start and end of the song; drag them inward to limit the portion that plays (it loops). Drag them back out
 * to the ends to play the whole song. Times are practice seconds, same as the highway. A null [onLoop] means the
 * whole song.
 */
@Composable
fun ScrubMinimap(
    song: Song, times: DoubleArray, songTime: Double, endTime: Double, judged: Map<Int, Judgement>, loop: ClosedFloatingPointRange<Double>?,
    onSeek: (Double) -> Unit, onLoop: (ClosedFloatingPointRange<Double>?) -> Unit, onCommit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val seek by rememberUpdatedState(onSeek); val setLoop by rememberUpdatedState(onLoop)
    val commit by rememberUpdatedState(onCommit)
    val curLoop by rememberUpdatedState(loop); val end by rememberUpdatedState(maxOf(endTime, 1.0))
    var dragging by remember { mutableIntStateOf(0) }   // 0 playhead, 1 range start, 2 range end
    var dragTime by remember { mutableStateOf<Double?>(null) }   // playhead follows the finger; the jump happens on release
    val playhead = dragTime ?: songTime
    Box(modifier) {
        SongMinimap(song, times, playhead, endTime, judged, Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    if (handleAt(o.x, curLoop, w, h, end) == 0) seek(minimapTime(o.x, w, h, end))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        dragging = handleAt(o.x, curLoop, w, h, end)
                        if (dragging == 0) dragTime = minimapTime(o.x, w, h, end)
                    },
                    onDrag = { change, _ ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val t = minimapTime(change.position.x, w, h, end)
                        val span = curLoop ?: 0.0..end
                        val minGap = 1.5
                        when (dragging) {
                            1 -> setLoop(rangeOrWhole(minOf(t, span.endInclusive - minGap), span.endInclusive, end))
                            2 -> setLoop(rangeOrWhole(span.start, maxOf(t, span.start + minGap), end))
                            else -> dragTime = t
                        }
                    },
                    onDragEnd = {
                        if (dragging == 1 || dragging == 2) commit()
                        else dragTime?.let { seek(it) }
                        dragTime = null
                        dragging = 0
                    },
                    onDragCancel = { dragTime = null; dragging = 0 },
                )
            }) {
            val w = size.width; val h = size.height
            val span = loop ?: 0.0..end
            val x0 = minimapX(span.start, w, h, end)
            val x1 = minimapX(span.endInclusive, w, h, end)
            val limited = span.start > 0.35 || span.endInclusive < end - 0.35
            if (limited) {
                val capsule = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(h / 2f, h / 2f)))
                }
                clipPath(capsule) {
                    if (x0 > 0f) drawRect(Color.Black.copy(alpha = 0.55f), Offset(0f, 0f), Size(x0, h))
                    if (x1 < w) drawRect(Color.Black.copy(alpha = 0.55f), Offset(x1, 0f), Size(w - x1, h))
                }
            }
            val knob = h * 0.46f
            for (x in listOf(x0, x1)) {
                drawCircle(Color.Black.copy(alpha = 0.35f), knob, Offset(x, h / 2f + 1.2.dp.toPx()))
                drawCircle(Color.White, knob, Offset(x, h / 2f))
                drawCircle(PlayLime, knob * 0.42f, Offset(x, h / 2f))
            }
            if (playhead >= 0) {
                val px = minimapX(playhead, w, h, end)
                drawLine(Color.White, Offset(px, 3.dp.toPx()), Offset(px, h - 3.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/** 1 = start handle, 2 = end handle, 0 = the bar itself. The end handle wins when the two are close. */
private fun handleAt(x: Float, loop: ClosedFloatingPointRange<Double>?, w: Float, h: Float, end: Double): Int {
    val span = loop ?: 0.0..end
    val grab = maxOf(h * 0.9f, 28f)
    val start = minimapX(span.start, w, h, end)
    val stop = minimapX(span.endInclusive, w, h, end)
    val nearStart = abs(x - start) < grab
    val nearEnd = abs(x - stop) < grab
    return when {
        nearStart && nearEnd -> if (abs(x - stop) <= abs(x - start)) 2 else 1
        nearEnd -> 2
        nearStart -> 1
        else -> 0
    }
}

/** Null when the handles are back at the two ends, so the whole song plays. */
private fun rangeOrWhole(start: Double, end: Double, songEnd: Double): ClosedFloatingPointRange<Double>? {
    val a = start.coerceIn(0.0, songEnd)
    val b = end.coerceIn(0.0, songEnd)
    if (a <= 0.4 && b >= songEnd - 0.4) return null
    return a..b
}

/**
 * Shown over the highway while paused: resume, tempo, looping (whole song, a section, or the dragged A–B range)
 * and ways out. Positions are score seconds (100 % tempo).
 */
@Composable
fun PausePanel(
    song: Song, pos: Double, speed: Float, onSpeed: (Float) -> Unit, loop: ClosedFloatingPointRange<Double>?, onLoop: (ClosedFloatingPointRange<Double>?) -> Unit,
    onResume: () -> Unit, onRestart: () -> Unit, onFinish: () -> Unit, onQuit: () -> Unit, preparing: String?, modifier: Modifier = Modifier,
) {
    Column(
        modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xF0101113))
            .border(1.dp, MuColors.stroke, RoundedCornerShape(26.dp)).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PAUSED", style = OverlineStyle)
                Text("${clock(pos)} / ${clock(song.endTime)}" + (song.sections.lastOrNull { it.start <= pos + 0.01 }?.let { "  ·  ${it.name}" } ?: ""),
                    color = MuColors.text, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ChunkyButton(preparing ?: "Resume", onResume, Modifier.width(170.dp), icon = Icons.Filled.PlayArrow, enabled = preparing == null, height = 54.dp)
        }
        // tempo
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tempo", color = MuColors.textDim, modifier = Modifier.width(64.dp))
            StepButton("−") { onSpeed(((speed - 0.05f) * 20).roundToInt() / 20f) }
            Text("${(speed * 100).roundToInt()}%", color = MuColors.text, fontWeight = FontWeight.Black, fontSize = 18.sp,
                modifier = Modifier.width(64.dp).padding(horizontal = 6.dp), maxLines = 1)
            StepButton("+") { onSpeed(((speed + 0.05f) * 20).roundToInt() / 20f) }
            Spacer(Modifier.width(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in listOf(0.5f, 0.75f, 1f)) Chip("${(p * 100).roundToInt()}%", abs(speed - p) < 0.01f) { onSpeed(p) }
            }
        }
        // loop
        Text("LOOP", style = OverlineStyle)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Off", loop == null) { onLoop(null) }
            val bar = 60.0 / song.tempoMap.first().bpm * (song.timeSig.firstOrNull()?.num ?: 4)
            Chip("4 bars from here", loop != null && abs(loop.start - pos) < 0.05 && song.sections.none { abs(it.start - loop.start) < 0.01 && abs(it.end - loop.endInclusive) < 0.01 }) {
                onLoop(pos..minOf(song.endTime, pos + 4 * bar))
            }
            for (sec in song.sections) Chip(sec.name, loop != null && abs(loop.start - sec.start) < 0.01 && abs(loop.endInclusive - sec.end) < 0.01) {
                onLoop(sec.start..sec.end)
            }
        }
        Text(if (loop == null) "Tap the bar to skip through the song. Drag the handles at each end to play only that part." else
            "Looping ${clock(loop.start)}–${clock(loop.endInclusive)}. Drag the handles on the bar to change it.",
            color = MuColors.textMuted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("Restart", Icons.Filled.Refresh, onRestart)
            TextAction("See results", null, onFinish)
            Spacer(Modifier.weight(1f))
            TextAction("Quit", null, onQuit, MuColors.wrong)
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(MuColors.surfaceHi).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = MuColors.text, fontWeight = FontWeight.Black, fontSize = 20.sp)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) MuColors.accent else Color(0x14FFFFFF)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, color = if (selected) Color.White else MuColors.textDim, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun TextAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit, color: Color = MuColors.textDim) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { androidx.compose.material3.Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label.uppercase(), color = color, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 0.8.sp)
    }
}

/** Compact tempo control for the HUD: − 85% + (applies right away; a moving run restarts from where it is). */
@Composable
fun TempoNudge(speed: Float, onSpeed: (Float) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(MuColors.surface).border(1.dp, MuColors.stroke, RoundedCornerShape(50)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).clickable { onSpeed(((speed - 0.05f) * 20).roundToInt() / 20f) }, contentAlignment = Alignment.Center) {
            Text("−", color = MuColors.text, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(speed * 100).roundToInt()}%", color = MuColors.text, fontWeight = FontWeight.Black, fontSize = 14.sp)
            Text("TEMPO", style = OverlineStyle.copy(fontSize = 8.sp))
        }
        Box(Modifier.size(32.dp).clip(CircleShape).clickable { onSpeed(((speed + 0.05f) * 20).roundToInt() / 20f) }, contentAlignment = Alignment.Center) {
            Text("+", color = MuColors.text, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
    }
}


/** Lime field behind the fretboard, the same green as the play-screen design. */
val PlayLime = Color(0xFF98D63C)
private val PlayLimeTop = Color(0xFFA8E252)
private val PlayLimeBottom = Color(0xFF8FCF34)

/**
 * Bright lime field with soft vertical bands and drifting sparkles. [t] drives the motion (song time), so the
 * sparkles stand still while paused. [streak] and [struggle] are kept so callers can pass play state; the field
 * stays this green.
 */
@Composable
fun AirBackground(streak: Int, struggle: Float, t: Double, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(PlayLimeTop, PlayLime, PlayLimeBottom)))
        val w = size.width
        val h = size.height
        val bands = 5
        val bw = w / bands
        for (i in 0 until bands) {
            val band = if (i % 2 == 0) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f)
            drawRect(band, Offset(i * bw, 0f), Size(bw, h))
        }
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), Offset(w * 0.5f, h * 0.18f), w * 0.4f),
            w * 0.4f, Offset(w * 0.5f, h * 0.18f),
        )
        val twinkle = 0.85f + 0.15f * ((streak * 0.01f) - struggle * 0.02f).coerceIn(-0.1f, 0.15f)
        for (i in 0 until 54) {
            val r1 = (i * 0.6180339f) % 1f
            val r2 = (i * 0.3819660f + 0.17f) % 1f
            val r3 = (i * 0.7548776f + 0.31f) % 1f
            val x = ((r1 + (t * 0.003 * (0.4 + r3)).toFloat()) % 1f) * w
            val y = (1f - ((r2 + (t * 0.018 * (0.35 + r3)).toFloat()) % 1f)) * h
            val rad = (1.0f + 2.3f * r3).dp.toPx()
            val alpha = (0.18f + 0.55f * r3) * twinkle
            drawCircle(Color.White.copy(alpha = alpha), rad, Offset(x, y))
            if (i % 6 == 0) {
                val arm = rad * 2.6f
                val sw = rad * 0.32f
                drawLine(Color.White.copy(alpha = alpha), Offset(x - arm, y), Offset(x + arm, y), sw, StrokeCap.Round)
                drawLine(Color.White.copy(alpha = alpha), Offset(x, y - arm), Offset(x, y + arm), sw, StrokeCap.Round)
            }
        }
    }
}
