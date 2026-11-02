package com.theerfan.mu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.theerfan.mu.audio.AudioRoute
import com.theerfan.mu.audio.AudioSession
import com.theerfan.mu.audio.CalibrationStore
import com.theerfan.mu.audio.NativeAudio
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class CalMode { LOOPBACK, TAP }

@Composable
fun CalibrationScreen(hasMic: Boolean, requestMic: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CalibrationStore(context) }
    Scaffold(topBar = { BackBar("Latency calibration", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!hasMic) { MicNeeded(requestMic); return@Column }
            val (ready, error) = rememberAudio(true)
            val scope = rememberCoroutineScope()
            var route by remember { mutableStateOf(AudioRoute.current(context)) }
            var running by remember { mutableStateOf<CalMode?>(null) }
            var message by remember { mutableStateOf("") }
            var version by remember { mutableIntStateOf(0) }
            var beatPulse by remember { mutableFloatStateOf(0f) }
            var status by remember { mutableStateOf(DoubleArray(14)) }
            LaunchedEffect(ready) { while (ready && isActive) { status = AudioSession.status(); route = AudioRoute.current(context); delay(500) } }

            fun run(mode: CalMode) = scope.launch {
                running = mode
                val beat = if (mode == CalMode.LOOPBACK) 0.5 else 60.0 / 90.0
                val count = if (mode == CalMode.LOOPBACK) 12 else 16
                val countIn = if (mode == CalMode.LOOPBACK) 0 else 4
                val times = DoubleArray(countIn + count) { (it - countIn) * beat }
                val accents = BooleanArray(times.size) { mode == CalMode.LOOPBACK || it < countIn || (it - countIn) % 4 == 0 }
                val refs = DoubleArray(count) { it * beat }
                message = if (mode == CalMode.LOOPBACK) "Listening to the clicks… keep quiet" else "Count-in… then strum muted strings on every click"
                NativeAudio.startClicks(times, accents, 0.6 + countIn * beat, if (mode == CalMode.LOOPBACK) 0.9f else 0.5f, true)
                val end = refs.last() + 0.8
                while (isActive) {
                    val t = NativeAudio.songTime()
                    if (t > end) break
                    val ph = ((t + 10 * beat) % beat) / beat
                    beatPulse = if (t > -countIn * beat - 0.05) (1f - (ph * 4).toFloat()).coerceIn(0f, 1f) else 0f
                    if (mode == CalMode.TAP && t >= 0) message = "Strum on every click (${(t / beat).toInt().coerceAtMost(count - 1) + 1}/$count)"
                    delay(16)
                }
                beatPulse = 0f
                val det = DoubleArray(512); val n = NativeAudio.onsets(det)
                NativeAudio.stopAll()
                val r = NativeAudio.robustOffset(det.copyOf(n), refs, if (mode == CalMode.LOOPBACK) 0.15 else 0.25)
                message = if (r[0] > 0.5f) {
                    if (mode == CalMode.LOOPBACK) store.setLoopback(route, r[1]) else store.setTap(route, r[1])
                    NativeAudio.setUserOffsetMs(store.effective(route))
                    "Offset %+.1f ms (spread ±%.1f ms, %d/%d clicks matched)".format(r[1], r[2], r[3].toInt(), r[4].toInt())
                } else "Not enough clicks detected (%d/%d). %s".format(r[3].toInt(), r[4].toInt(),
                    if (mode == CalMode.LOOPBACK) "Turn the volume up and use the speaker." else "Strum a bit harder, right on the click.")
                running = null; version++
            }

            if (error != null) Text(error, color = MuColors.wrong)
            Card { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Output: ${route.label}", style = MaterialTheme.typography.titleMedium)
                val lb = remember(version, route) { store.loopback(route) }; val tap = remember(version, route) { store.tap(route) }
                Text("Loopback offset: " + (lb?.let { "%+.1f ms".format(it) } ?: "not measured"))
                Text("Tap-along offset: " + (tap?.let { "%+.1f ms".format(it) } ?: "not measured"))
                Text("Used for scoring: %+.1f ms".format(store.effective(route)), color = MuColors.accent)
                if (route == AudioRoute.BLUETOOTH) Text("Bluetooth adds 150–300 ms that varies: use tap-along, or better, wired headphones.", color = MuColors.ok)
                OutlinedButton(onClick = { store.clear(route); NativeAudio.setUserOffsetMs(0f); version++ }, enabled = running == null) { Text("Reset this route") }
            } }
            Card { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1 · Automatic (loopback)", style = MaterialTheme.typography.titleMedium)
                Text("Phone speaker on, volume ~70 %, quiet room. Plays 12 clicks and listens to them with the mic. Measures what the audio timestamps miss.")
                Button(onClick = { run(CalMode.LOOPBACK) }, enabled = ready && running == null) { Text("Run loopback") }
            } }
            Card { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2 · Tap-along (manual)", style = MaterialTheme.typography.titleMedium)
                Text("Mute the strings with your fretting hand and strum on each click: 4 count-in clicks, then 16. Includes your own feel and the detector's bias; this is the one used for scoring when present. Headphones recommended (the speaker click itself can be heard by the mic).")
                Button(onClick = { run(CalMode.TAP) }, enabled = ready && running == null) { Text("Run tap-along") }
            } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Canvas(Modifier.size(28.dp)) { drawCircle(if (beatPulse > 0f) MuColors.accent.copy(alpha = 0.3f + 0.7f * beatPulse) else Color.DarkGray) }
                Text(message)
            }
            Text(
                "Engine: %d Hz, buffer %d frames (burst %d), out latency %s, in latency %s, timestamps %s, xruns %d, preset %s. %s".format(
                    status[0].toInt(), status[1].toInt(), status[2].toInt(),
                    if (status[12] >= 0) "%.1f ms".format(status[12]) else "n/a", if (status[13] >= 0) "%.1f ms".format(status[13]) else "n/a",
                    if (status[8] > 0.5) "ok" else "unavailable", status[5].toInt(), presetName(status[3].toInt()), AudioSession.effectsReport),
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

fun presetName(p: Int) = when (p) { 9 -> "UNPROCESSED"; 6 -> "VOICE_RECOGNITION"; 1 -> "GENERIC"; 10 -> "VOICE_PERFORMANCE"; else -> "#$p" }
