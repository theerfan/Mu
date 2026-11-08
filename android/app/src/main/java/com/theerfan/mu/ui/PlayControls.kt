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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * The song minimap as a scrubber: the white dot on the playhead line can be dragged (or the bar tapped) to move
 * through the song; with a loop set, its two handles can be dragged to resize it. All times here are practice
 * seconds (what the highway shows); [onSeek] / [onLoop] report practice seconds too.
 */
@Composable
fun ScrubMinimap(
    song: Song, times: DoubleArray, songTime: Double, endTime: Double, judged: Map<Int, Judgement>, loop: ClosedFloatingPointRange<Double>?,
    onSeek: (Double) -> Unit, onLoop: (ClosedFloatingPointRange<Double>) -> Unit, modifier: Modifier = Modifier,
) {
    val seek by rememberUpdatedState(onSeek); val setLoop by rememberUpdatedState(onLoop)
    val curLoop by rememberUpdatedState(loop); val end by rememberUpdatedState(maxOf(endTime, 1.0))
    var dragging by remember { mutableIntStateOf(0) }   // 0 playhead, 1 loop start, 2 loop end
    Box(modifier) {
        SongMinimap(song, times, songTime, endTime, judged, Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()
            .pointerInput(Unit) { detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f) * end) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val l = curLoop
                        val grab = 16.dp.toPx()
                        dragging = when {
                            l != null && abs(o.x - (l.start / end * size.width).toFloat()) < grab -> 1
                            l != null && abs(o.x - (l.endInclusive / end * size.width).toFloat()) < grab -> 2
                            else -> 0
                        }
                        if (dragging == 0) seek((o.x / size.width).coerceIn(0f, 1f) * end)
                    },
                    onDrag = { change, _ ->
                        val t = (change.position.x / size.width).coerceIn(0f, 1f) * end
                        val l = curLoop
                        when {
                            dragging == 1 && l != null -> setLoop(minOf(t, l.endInclusive - 1.0)..l.endInclusive)
                            dragging == 2 && l != null -> setLoop(l.start..maxOf(t, l.start + 1.0))
                            else -> seek(t)
                        }
                    },
                )
            }) {
            val w = size.width; val h = size.height
            loop?.let { l ->
                val x0 = (l.start / end * w).toFloat(); val x1 = (l.endInclusive / end * w).toFloat()
                drawRect(MuColors.accent.copy(alpha = 0.22f), Offset(x0, 0f), Size(x1 - x0, h))
                for (x in listOf(x0, x1)) {
                    drawRoundRect(MuColors.accent, Offset(x - 3.dp.toPx(), 0f), Size(6.dp.toPx(), h), CornerRadius(3.dp.toPx()))
                }
            }
            val px = (songTime.coerceIn(0.0, end) / end * w).toFloat()
            drawCircle(MuColors.primary, 9.dp.toPx(), Offset(px, h / 2))
            drawCircle(Color.White, 6.dp.toPx(), Offset(px, h / 2))
            drawCircle(Color.Black.copy(alpha = 0.3f), 9.dp.toPx(), Offset(px, h / 2), style = Stroke(1.dp.toPx()))
        }
    }
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
        Text(if (loop == null) "Tap the minimap or drag its dot to move through the song." else
            "Looping ${clock(loop.start)}–${clock(loop.endInclusive)}. Drag the blue handles on the minimap to change it.",
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


val AirCalm = Color(0xFF1E2C3D)      // dusk blue: nothing going on yet
private val AirGood = Color(0xFF1E8C5A)
private val AirBad = Color(0xFF7C2535)

/**
 * The sky behind the fretboard, telling you how it's going: calm blue, greener as your streak grows, flushed red
 * after misses. Soft motes drift up through it, livelier when you're playing well. [t] drives the motion (song time),
 * so it stands still while paused.
 */
@Composable
fun AirBackground(streak: Int, struggle: Float, t: Double, modifier: Modifier = Modifier) {
    val good = (streak / 16f).coerceIn(0f, 1f)
    val bad = (struggle * 0.8f).coerceIn(0f, 0.75f)
    val target = androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.lerp(AirCalm, AirGood, good), AirBad, bad)
    val air by androidx.compose.animation.animateColorAsState(target, androidx.compose.animation.core.tween(600), label = "air")
    val lively by androidx.compose.animation.core.animateFloatAsState(0.35f + 0.65f * good, label = "lively")
    Canvas(modifier) {
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(air, Color.White, 0.12f), air,
            androidx.compose.ui.graphics.lerp(air, Color.Black, 0.35f))))
        val w = size.width; val h = size.height
        for (i in 0 until 46) {
            // deterministic pseudo-random per mote: position, size, drift speed
            val r1 = ((i * 0.6180339f) % 1f); val r2 = ((i * 0.3819660f + 0.17f) % 1f); val r3 = ((i * 0.7548776f + 0.31f) % 1f)
            val x = ((r1 + (t * 0.004 * (0.5 + r3)).toFloat()) % 1f) * w
            val y = (1f - ((r2 + (t * 0.03 * (0.4 + r3)).toFloat()) % 1f)) * h * 0.85f
            drawCircle(Color.White.copy(alpha = (0.08f + 0.3f * r3) * lively), (1.2f + 2.2f * r3).dp.toPx(), Offset(x, y))
        }
    }
}
