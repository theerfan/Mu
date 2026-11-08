package com.theerfan.mu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theerfan.mu.audio.NativeAudio
import com.theerfan.mu.play.Notes
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Tuner state helpers (pure, unit-tested). */
object TunerMath {
    /** Nearest note and deviation in cents. */
    fun nearest(hz: Double): Pair<Int, Double> { val m = Notes.hzToMidi(hz); val n = m.roundToInt(); return n to (m - n) * 100 }
    /** Closest open string of a tuning (MIDI list), by semitone distance. */
    fun nearestString(hz: Double, tuning: List<Int>): Int = tuning.indices.minBy { abs(Notes.hzToMidi(hz) - tuning[it]) }
    fun median(v: List<Double>): Double = v.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
}

@Composable
fun TunerScreen(hasMic: Boolean, requestMic: () -> Unit, onBack: () -> Unit) {
    MuScreen {
        MuTopBar("Tuner", onBack) { Pill("AUTO · STANDARD", MuColors.cyan, MuColors.cyan.copy(alpha = 0.14f)) }
        if (!hasMic) { MicNeeded(requestMic); return@MuScreen }
        KeepScreenOn()
        val (ready, error) = rememberAudio(true)
        var freq by remember { mutableFloatStateOf(0f) }
        var level by remember { mutableFloatStateOf(-100f) }
        var voiced by remember { mutableStateOf(false) }
        LaunchedEffect(ready) {
            if (!ready) return@LaunchedEffect
            NativeAudio.startTuner()
            val buf = FloatArray(4); val hist = ArrayDeque<Double>()
            var silentFrames = 0
            while (true) {
                NativeAudio.tuner(buf)
                level = buf[2]
                if (buf[3] > 0.5f && buf[0] > 0f) {
                    hist.addLast(buf[0].toDouble()); if (hist.size > 5) hist.removeFirst()
                    freq = TunerMath.median(hist.toList()).toFloat(); voiced = true; silentFrames = 0
                } else if (++silentFrames > 15) { voiced = false; hist.clear() }
                delay(33)
            }
        }
        if (error != null) Banner(error, MuColors.wrong, Modifier.padding(horizontal = 20.dp))
        TunerDisplay(freq.toDouble(), voiced, level)
    }
}

private val TUNING = listOf(40, 45, 50, 55, 59, 64)

@Composable
private fun TunerDisplay(freq: Double, voiced: Boolean, level: Float) {
    val (note, cents) = if (voiced && freq > 0) TunerMath.nearest(freq) else (-1 to 0.0)
    val inTune = voiced && abs(cents) <= 5
    val target = when { !voiced -> MuColors.textMuted; inTune -> MuColors.perfect; abs(cents) <= 15 -> MuColors.ok; else -> MuColors.wrong }
    val color by animateColorAsState(target, label = "tunerColor")
    val needle by animateFloatAsState(if (voiced) cents.coerceIn(-50.0, 50.0).toFloat() else 0f, spring(dampingRatio = 0.7f, stiffness = 120f), label = "needle")
    val near = if (voiced) TunerMath.nearestString(freq, TUNING) else -1

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // Gauge: -50..+50 cents, needle pivots at the bottom
        Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1.75f), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val r = minOf(size.width / 2f, size.height * 0.95f) - 10.dp.toPx()
                val c = Offset(size.width / 2, size.height * 0.97f)
                val tl = Offset(c.x - r, c.y - r); val sz = Size(2 * r, 2 * r); val sw = 16.dp.toPx()
                // zones: red / amber / green / amber / red across -50..+50 cents mapped to 180°..360°
                fun ang(cn: Float) = 270f + cn / 50f * 80f
                drawArc(Color(0x1AFFFFFF), ang(-50f), 160f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                drawArc(MuColors.ok.copy(alpha = 0.35f), ang(-15f), ang(15f) - ang(-15f), false, tl, sz, style = Stroke(sw))
                drawArc(MuColors.perfect, ang(-5f), ang(5f) - ang(-5f), false, tl, sz, style = Stroke(sw))
                for (k in -50..50 step 5) {
                    val a = Math.toRadians(ang(k.toFloat()).toDouble()); val major = k % 25 == 0
                    val r1 = r - sw - (if (major) 18.dp.toPx() else 10.dp.toPx()); val r2 = r - sw - 4.dp.toPx()
                    drawLine(Color.White.copy(alpha = if (major) 0.6f else 0.22f),
                        Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()), Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()),
                        strokeWidth = if (major) 4f else 2.5f, cap = StrokeCap.Round)
                }
                // needle
                val a = Math.toRadians(ang(needle).toDouble())
                val tip = Offset(c.x + (r - 6.dp.toPx()) * cos(a).toFloat(), c.y + (r - 6.dp.toPx()) * sin(a).toFloat())
                drawLine(color.copy(alpha = 0.25f), c, tip, strokeWidth = 14.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, c, tip, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(color, 11.dp.toPx(), c); drawCircle(MuColors.bg, 5.dp.toPx(), c)
            }
        }
        // Note name under the pivot, glowing when in tune
        Column(Modifier.offset(y = (-22).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                if (inTune) Box(Modifier.size(150.dp).clip(CircleShape).background(Brush.radialGradient(listOf(MuColors.perfect.copy(alpha = 0.35f), Color.Transparent))))
                Text(if (voiced) Notes.pcName(note % 12) else "–", fontSize = 84.sp, fontWeight = FontWeight.Black, color = if (voiced) Color.White else MuColors.textMuted)
            }
            Text(if (voiced) "%+.0f cents · %.1f Hz".format(cents, freq) else " ", color = MuColors.textDim, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        // Status pill
        val status = when { !voiced -> "Play one string"; inTune -> "In tune!"; cents < 0 -> "Too low · tighten"; else -> "Too high · loosen" }
        Row(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (inTune) { Icon(Icons.Filled.Check, null, tint = color, modifier = Modifier.size(20.dp)); Spacer(Modifier.size(6.dp)) }
            Text(status.uppercase(), color = if (voiced) color else MuColors.textDim, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
        Headstock(near, inTune)
        LevelMeter(level)
    }
}

/** 3 + 3 headstock; the string being tuned lights up in its lane colour (green when in tune). */
@Composable
private fun Headstock(near: Int, inTune: Boolean) {
    // pegs: left column top→bottom D A E, right column G B e (index into TUNING)
    val left = listOf(2, 1, 0); val right = listOf(3, 4, 5)
    Row(Modifier.widthIn(max = 380.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { for (i in left) StringPeg(i, near == i, inTune) }
        Canvas(Modifier.weight(1f).height(200.dp).padding(horizontal = 14.dp)) {
            val w = size.width; val h = size.height
            val body = Path().apply {
                moveTo(w * 0.32f, h); lineTo(w * 0.68f, h)
                cubicTo(w * 0.72f, h * 0.6f, w * 0.88f, h * 0.3f, w * 0.84f, h * 0.04f)
                quadraticTo(w * 0.5f, -h * 0.04f, w * 0.16f, h * 0.04f)
                cubicTo(w * 0.12f, h * 0.3f, w * 0.28f, h * 0.6f, w * 0.32f, h)
                close()
            }
            drawPath(body, Brush.verticalGradient(listOf(Color(0xFF2A2C30), Color(0xFF16171A))))
            drawPath(body, Color(0x33FFFFFF), style = Stroke(2f))
            // nut and strings running to the pegs
            drawRoundRect(Color(0xFFE8E8EA), Offset(w * 0.3f, h * 0.9f), Size(w * 0.4f, 6f), CornerRadius(3f))
            for (k in 0 until 6) {
                val peg = if (k < 3) left.indexOf(k) else right.indexOf(k)
                val px = if (k < 3) w * 0.2f else w * 0.8f; val py = h * (0.17f + peg * 0.3f)
                val sx = w * (0.36f + k * 0.056f)
                val lit = k == near
                val col = if (lit) (if (inTune) MuColors.perfect else MuColors.strings[5 - k]) else Color.White.copy(alpha = 0.35f)
                drawLine(col, Offset(sx, h), Offset(sx, h * 0.9f), strokeWidth = if (lit) 4f else 2f)
                drawLine(col, Offset(sx, h * 0.9f), Offset(px, py), strokeWidth = if (lit) 4f else 2f)
                drawCircle(Color(0xFFC9CBD0), 7.dp.toPx(), Offset(px, py))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { for (i in right) StringPeg(i, near == i, inTune) }
    }
}

@Composable
private fun StringPeg(i: Int, active: Boolean, inTune: Boolean) {
    val lane = MuColors.strings[5 - i]
    val bg = when { active && inTune -> MuColors.perfect; active -> lane; else -> MuColors.surfaceHi }
    Box(Modifier.size(56.dp).clip(CircleShape).background(bg).border(2.dp, if (active) Color.White.copy(alpha = 0.6f) else lane.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center) {
        if (active && inTune) Icon(Icons.Filled.Check, "In tune", tint = MuColors.onPrimary, modifier = Modifier.size(28.dp))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Notes.pcName(TUNING[i] % 12).let { if (i == 5) it.lowercase() else it }, color = if (active) MuColors.onPrimary else Color.White,
                fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun LevelMeter(levelDb: Float) {
    val f = ((levelDb + 70f) / 70f).coerceIn(0f, 1f)
    val shown by animateFloatAsState(f, label = "level")
    Row(Modifier.widthIn(max = 380.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MicGlyph(Modifier.size(18.dp), MuColors.textMuted)
        Spacer(Modifier.size(10.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x1AFFFFFF))) {
            Box(Modifier.fillMaxWidth(shown).height(8.dp).background(Brush.horizontalGradient(listOf(MuColors.primary, MuColors.star, MuColors.wrong))))
        }
        Spacer(Modifier.size(10.dp))
        Text("%.0f dB".format(levelDb), color = MuColors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
