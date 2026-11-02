package com.theerfan.mu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theerfan.mu.audio.AudioRoute
import com.theerfan.mu.audio.CalibrationStore
import com.theerfan.mu.audio.NativeAudio
import com.theerfan.mu.play.Judgement
import com.theerfan.mu.play.Kind
import com.theerfan.mu.play.Summary
import com.theerfan.mu.play.feedback
import com.theerfan.mu.play.points
import com.theerfan.mu.play.summarize
import com.theerfan.mu.song.Song
import com.theerfan.mu.song.SongRepository
import com.theerfan.mu.song.Timing
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Phase { SETUP, PLAYING, RESULTS }

@Composable
fun PlayScreen(repo: SongRepository, songId: String, hasMic: Boolean, requestMic: () -> Unit, onBack: () -> Unit, onCalibrate: () -> Unit) {
    val song = remember(songId) { repo.get(songId) }
    Scaffold(topBar = { BackBar(song?.title ?: "Song", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                song == null -> Text("Could not load this song.", Modifier.padding(16.dp))
                !hasMic -> MicNeeded(requestMic)
                else -> PlayContent(song, onCalibrate)
            }
        }
    }
}

@Composable
private fun PlayContent(song: Song, onCalibrate: () -> Unit) {
    val context = LocalContext.current
    val (ready, error) = rememberAudio(true)
    var phase by remember { mutableStateOf(Phase.SETUP) }
    var speed by remember { mutableFloatStateOf(1f) }
    var clickThrough by remember { mutableStateOf(true) }
    var songTime by remember { mutableDoubleStateOf(-10.0) }
    val judged = remember { mutableStateMapOf<Int, Judgement>() }
    var all by remember { mutableStateOf(listOf<Judgement>()) }
    var last by remember { mutableStateOf<Judgement?>(null) }
    var score by remember { mutableIntStateOf(0) }
    var streak by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf<Summary?>(null) }
    var runId by remember { mutableIntStateOf(0) }
    var stopRequested by remember { mutableStateOf(false) }
    val store = remember { CalibrationStore(context) }
    val route = remember(runId) { AudioRoute.current(context) }
    val calibrated = store.tap(route) != null || store.loopback(route) != null

    val times = remember(speed) { Timing.expected(song, speed.toDouble()).times }
    val beats = remember(speed) { Timing.beatTimes(song.tempoMap, song.endTime).map { it / speed }.toDoubleArray() }
    val barEvery = song.timeSig.firstOrNull()?.num ?: 4

    fun start() {
        val exp = Timing.expected(song, speed.toDouble())
        NativeAudio.loadSong(exp.times, exp.counts, exp.midis)
        val clicks = Timing.clicks(song, speed.toDouble(), barEvery, clickThrough)
        NativeAudio.setUserOffsetMs(store.effective(AudioRoute.current(context)))
        judged.clear(); all = emptyList(); last = null; score = 0; streak = 0; summary = null; stopRequested = false
        if (NativeAudio.startPlay(clicks.times, clicks.accents, clicks.leadInSec, 0.6f)) { phase = Phase.PLAYING; runId++ }
    }

    LaunchedEffect(phase, runId) {
        if (phase != Phase.PLAYING) return@LaunchedEffect
        val buf = FloatArray(64 * Judgement.FLOATS)
        val end = song.endTime / speed + 1.6
        fun apply(js: List<Judgement>) {
            for (j in js) {
                all = all + j
                if (j.kind != Kind.EXTRA && j.index >= 0) judged[j.index] = j
                if (j.kind != Kind.MISS || last == null || j.songTime > (last?.songTime ?: 0f)) last = j
                score += points(j)
                streak = if (j.kind == Kind.HIT) streak + 1 else if (j.kind == Kind.EXTRA) streak else 0
            }
        }
        while (true) {
            withFrameNanos { }
            songTime = NativeAudio.songTime()
            val n = NativeAudio.poll(buf)
            if (n > 0) apply(Judgement.unpack(buf, n))
            if (songTime > end || stopRequested) break
        }
        val fin = FloatArray(4096 * Judgement.FLOATS)
        apply(Judgement.unpack(fin, NativeAudio.finishPlay(fin)))
        NativeAudio.stopAll()
        // Stopped early: only score the events that were due.
        val due = if (stopRequested) Timing.firstAtOrAfter(times, songTime) else times.size
        summary = summarize(all.filter { it.index < due }, due) { song.events[it].isChord }
        phase = Phase.RESULTS
    }

    if (error != null) Text(error, color = MuColors.wrong, modifier = Modifier.padding(16.dp))
    when (phase) {
        Phase.SETUP -> Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(song.title, style = MaterialTheme.typography.headlineSmall)
            Text("${song.artist}  ·  ${song.events.size} events, ${song.chordCount} chords  ·  %d bpm".format((song.tempoMap.first().bpm * speed).roundToInt()))
            Text("Tempo: ${(speed * 100).roundToInt()} %")
            Slider(value = speed, onValueChange = { speed = (it * 20).roundToInt() / 20f }, valueRange = 0.5f..1.25f, steps = 14)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = clickThrough, onCheckedChange = { clickThrough = it })
                Text("  Metronome during the song (count-in is always on)")
            }
            if (clickThrough && route == AudioRoute.SPEAKER) Text("Tip: with the phone speaker the mic also hears the clicks; headphones give cleaner scoring.", color = MuColors.ok)
            if (!calibrated) Card { Column(Modifier.padding(12.dp)) {
                Text("Not calibrated for ${route.label}: timing grades may be off by tens of ms.", color = MuColors.ok)
                OutlinedButton(onClick = onCalibrate, modifier = Modifier.padding(top = 8.dp)) { Text("Calibrate") }
            } }
            Button(onClick = { start() }, enabled = ready, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start", fontSize = 20.sp) }
            Highway(song, speed.toDouble(), times, beats, barEvery, -1.0, emptyMap(), Modifier.fillMaxWidth().height(260.dp))
        }
        Phase.PLAYING -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val section = song.sections.lastOrNull { it.start / speed <= songTime }?.name ?: ""
                    val beatSec = 60.0 / song.tempoMap.first().bpm / speed
                    Text(if (songTime < 0) "Count-in… ${kotlin.math.ceil(-songTime / beatSec).toInt()}" else section, style = MaterialTheme.typography.titleMedium)
                    Text("Score $score   Streak $streak", color = Color.Gray)
                }
                OutlinedButton(onClick = { stopRequested = true }) { Text("Stop") }
            }
            val lj = last
            val fresh = lj != null && songTime - lj.songTime < 1.5
            Text(
                if (fresh && lj != null) feedback(lj, song.events.getOrNull(lj.index)?.notes?.map { it.midi }) else " ",
                color = if (lj != null) judgementColor(lj) else Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Highway(song, speed.toDouble(), times, beats, barEvery, songTime, judged, Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp))
        }
        Phase.RESULTS -> Results(summary, onRetry = { phase = Phase.SETUP })
    }
}

@Composable
private fun Results(s: Summary?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s == null) { Text("No result"); return@Column }
        Text("${s.score} / ${s.maxScore}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("Accuracy %.0f %%  ·  best streak %d".format(s.accuracyPct, s.bestStreak), style = MaterialTheme.typography.titleMedium)
        Card { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Line("Perfect", s.perfect, MuColors.perfect); Line("Good", s.good, MuColors.good); Line("OK", s.ok, MuColors.ok)
            Line("Octave off", s.partial, MuColors.partial); Line("Wrong note/chord", s.wrong, MuColors.wrong); Line("Missed", s.miss, MuColors.miss)
            Line("Extra notes", s.extra, Color.Gray)
            if (s.chordTotal > 0) Text("Chords: ${s.chordHits}/${s.chordTotal} clean")
            s.meanOffsetMs?.let {
                Text("Average timing: %+.0f ms %s".format(it, if (abs(it) < 15) "(on the beat)" else if (it > 0) "(you play late)" else "(you rush)"))
            }
        } }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Play again") }
    }
}

@Composable
private fun Line(label: String, n: Int, c: Color) {
    Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f), color = c); Text(n.toString(), fontWeight = FontWeight.Bold) }
}
