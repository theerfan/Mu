package com.theerfan.mu.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    MuScreen {
        MuTopBar("Calibration", onBack)
        if (!hasMic) { MicNeeded(requestMic); return@MuScreen }
        KeepScreenOn()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val (ready, error) = rememberAudio(true)
            val scope = rememberCoroutineScope()
            var route by remember { mutableStateOf(AudioRoute.current(context)) }
            var running by remember { mutableStateOf<CalMode?>(null) }
            var message by remember { mutableStateOf("") }
            var success by remember { mutableStateOf<Boolean?>(null) }
            var version by remember { mutableIntStateOf(0) }
            var beatPulse by remember { mutableFloatStateOf(0f) }
            var status by remember { mutableStateOf(DoubleArray(14)) }
            var details by remember { mutableStateOf(false) }
            LaunchedEffect(ready) { while (ready && isActive) { status = AudioSession.status(); route = AudioRoute.current(context); delay(500) } }

            fun run(mode: CalMode) = scope.launch {
                running = mode; success = null
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
                success = r[0] > 0.5f
                message = if (r[0] > 0.5f) {
                    if (mode == CalMode.LOOPBACK) store.setLoopback(route, r[1]) else store.setTap(route, r[1])
                    NativeAudio.setUserOffsetMs(store.effective(route))
                    "Offset %+.1f ms (spread ±%.1f ms, %d/%d clicks matched)".format(r[1], r[2], r[3].toInt(), r[4].toInt())
                } else "Not enough clicks detected (%d/%d). %s".format(r[3].toInt(), r[4].toInt(),
                    if (mode == CalMode.LOOPBACK) "Turn the volume up and use the speaker." else "Strum a bit harder, right on the click.")
                running = null; version++
            }

            if (error != null) Banner(error, MuColors.wrong)
            // Current route summary
            val lb = remember(version, route) { store.loopback(route) }; val tap = remember(version, route) { store.tap(route) }
            MuCard(Modifier.fillMaxWidth(), brush = Brush.linearGradient(listOf(Color(0xFF3B2A8F), Color(0xFF1E1842)))) {
                Text("OUTPUT", style = OverlineStyle.copy(color = MuColors.textDim))
                Text(route.label, style = MaterialTheme.typography.headlineSmall, color = MuColors.text)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%+.1f".format(store.effective(route)), color = MuColors.primary, fontSize = 44.sp, fontWeight = FontWeight.Black)
                    Text(" ms used for scoring", color = MuColors.textDim, modifier = Modifier.padding(bottom = 10.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Loopback", lb?.let { "%+.1f ms".format(it) } ?: "—", Modifier.weight(1f))
                    StatTile("Tap-along", tap?.let { "%+.1f ms".format(it) } ?: "—", Modifier.weight(1f))
                }
                if (lb != null || tap != null) Row(Modifier.padding(top = 10.dp).clip(CircleShape)
                    .clickable(enabled = running == null) { store.clear(route); NativeAudio.setUserOffsetMs(0f); version++ }
                    .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, null, tint = MuColors.textDim, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("RESET THIS OUTPUT", color = MuColors.textDim, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
                }
            }
            if (route == AudioRoute.BLUETOOTH) Banner("Bluetooth adds 150–300 ms that varies: use tap-along, or better, wired headphones.", MuColors.ok)

            // Live run indicator
            AnimatedVisibility(running != null || message.isNotEmpty()) {
                val c = when (success) { true -> MuColors.perfect; false -> MuColors.wrong; null -> MuColors.accent }
                MuCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(64.dp).scale(0.6f + 0.4f * beatPulse).clip(CircleShape).background(c.copy(alpha = 0.25f + 0.5f * beatPulse)))
                            Box(Modifier.size(26.dp).clip(CircleShape).background(c))
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(message, color = MuColors.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                }
            }

            StepCard(1, "Automatic", "Loopback", MuColors.accent,
                "Phone speaker on, volume ~70 %, quiet room. Plays 12 clicks and listens to them with the mic. Measures what the audio timestamps miss.") {
                ChunkyButton("Run loopback", { run(CalMode.LOOPBACK) }, Modifier.fillMaxWidth(), color = MuColors.accent, lip = MuColors.accentLip,
                    textColor = Color.White, enabled = ready && running == null, height = 54.dp)
            }
            StepCard(2, "Manual", "Tap-along", MuColors.primary,
                "Mute the strings with your fretting hand and strum on each click: 4 count-in clicks, then 16. Includes your own feel and the detector's bias; this is the one used for scoring when present. Headphones recommended (the speaker click itself can be heard by the mic).") {
                ChunkyButton("Run tap-along", { run(CalMode.TAP) }, Modifier.fillMaxWidth(), enabled = ready && running == null, height = 54.dp)
            }

            // Engine diagnostics, folded away
            Row(Modifier.fillMaxWidth().clip(CircleShape).clickable { details = !details }.padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("ENGINE DETAILS", style = OverlineStyle, modifier = Modifier.weight(1f))
                Icon(if (details) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = MuColors.textMuted)
            }
            AnimatedVisibility(details) {
                Text(
                    "Engine: %d Hz, buffer %d frames (burst %d), out latency %s, in latency %s, timestamps %s, xruns %d, preset %s. %s".format(
                        status[0].toInt(), status[1].toInt(), status[2].toInt(),
                        if (status[12] >= 0) "%.1f ms".format(status[12]) else "n/a", if (status[13] >= 0) "%.1f ms".format(status[13]) else "n/a",
                        if (status[8] > 0.5) "ok" else "unavailable", status[5].toInt(), presetName(status[3].toInt()), AudioSession.effectsReport),
                    style = MaterialTheme.typography.bodySmall, color = MuColors.textMuted)
            }
        }
    }
}

@Composable
private fun StepCard(n: Int, kicker: String, title: String, color: Color, body: String, action: @Composable () -> Unit) {
    MuCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)).border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", color = color, fontWeight = FontWeight.Black, fontSize = 18.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(kicker.uppercase(), style = OverlineStyle)
                Text(title, style = MaterialTheme.typography.titleLarge, color = MuColors.text)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(body, color = MuColors.textDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))
        action()
    }
}

fun presetName(p: Int) = when (p) { 9 -> "UNPROCESSED"; 6 -> "VOICE_RECOGNITION"; 1 -> "GENERIC"; 10 -> "VOICE_PERFORMANCE"; else -> "#$p" }
