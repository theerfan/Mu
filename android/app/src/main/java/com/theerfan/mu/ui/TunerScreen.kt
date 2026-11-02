package com.theerfan.mu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theerfan.mu.audio.NativeAudio
import com.theerfan.mu.play.Notes
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

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
    Scaffold(topBar = { BackBar("Tuner", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (!hasMic) { MicNeeded(requestMic); return@Column }
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
            if (error != null) Text(error, color = MuColors.wrong, modifier = Modifier.padding(16.dp))
            TunerDisplay(freq.toDouble(), voiced, level)
        }
    }
}

@Composable
private fun TunerDisplay(freq: Double, voiced: Boolean, level: Float) {
    val (note, cents) = if (voiced && freq > 0) TunerMath.nearest(freq) else (-1 to 0.0)
    val inTune = voiced && abs(cents) <= 5
    val color = when { !voiced -> MuColors.miss; inTune -> MuColors.perfect; abs(cents) <= 15 -> MuColors.ok; else -> MuColors.wrong }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (voiced) Notes.midiName(note) else "–", fontSize = 96.sp, fontWeight = FontWeight.Bold, color = color)
        Text(if (voiced) "%+.1f cents   %.2f Hz".format(cents, freq) else "Play a single string", style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
            val cx = size.width / 2; val y = size.height / 2
            drawLine(Color.Gray, Offset(0f, y), Offset(size.width, y), strokeWidth = 4f)
            for (c in -50..50 step 10) {
                val x = cx + c / 50f * (size.width / 2 - 8)
                drawLine(if (c == 0) MuColors.perfect else Color.Gray, Offset(x, y - (if (c == 0) 30f else 14f)), Offset(x, y + (if (c == 0) 30f else 14f)), strokeWidth = 3f)
            }
            if (voiced) {
                val x = cx + (cents.coerceIn(-50.0, 50.0) / 50.0).toFloat() * (size.width / 2 - 8)
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 10f, cap = StrokeCap.Round)
            }
        }
        val names = listOf("E2", "A2", "D3", "G3", "B3", "E4"); val tuning = listOf(40, 45, 50, 55, 59, 64)
        val near = if (voiced) TunerMath.nearestString(freq, tuning) else -1
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            names.forEachIndexed { i, n -> Text(n, fontSize = 20.sp, color = if (i == near) MuColors.accent else Color.Gray, fontWeight = if (i == near) FontWeight.Bold else FontWeight.Normal) }
        }
        Text("Input level %.0f dBFS".format(level), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}
