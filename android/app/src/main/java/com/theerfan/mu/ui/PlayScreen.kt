package com.theerfan.mu.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theerfan.mu.audio.AudioRoute
import com.theerfan.mu.audio.CalibrationStore
import com.theerfan.mu.audio.NativeAudio
import com.theerfan.mu.play.Judgement
import com.theerfan.mu.play.Kind
import com.theerfan.mu.play.ProgressStore
import com.theerfan.mu.play.STAR_THRESHOLDS
import com.theerfan.mu.play.Summary
import com.theerfan.mu.play.feedback
import com.theerfan.mu.play.points
import com.theerfan.mu.play.stars
import com.theerfan.mu.play.summarize
import com.theerfan.mu.song.Song
import com.theerfan.mu.song.SongRepository
import com.theerfan.mu.song.Timing
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private enum class Phase { SETUP, PLAYING, RESULTS }

@Composable
fun PlayScreen(repo: SongRepository, songId: String, hasMic: Boolean, requestMic: () -> Unit, onBack: () -> Unit, onCalibrate: () -> Unit) {
    val song = remember(songId) { repo.get(songId) }
    MuScreen {
        when {
            song == null -> { MuTopBar("Song", onBack); Text("Could not load this song.", Modifier.padding(20.dp), color = MuColors.textDim) }
            !hasMic -> { MuTopBar(song.title, onBack); MicNeeded(requestMic) }
            else -> PlayContent(song, songId, onBack, onCalibrate)
        }
    }
}

@Composable
private fun PlayContent(song: Song, songId: String, onBack: () -> Unit, onCalibrate: () -> Unit) {
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
    var finished by remember { mutableStateOf(false) }
    var newBest by remember { mutableStateOf(false) }
    var runId by remember { mutableIntStateOf(0) }
    var stopRequested by remember { mutableStateOf(false) }
    val store = remember { CalibrationStore(context) }
    val progress = remember { ProgressStore(context) }
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
        judged.clear(); all = emptyList(); last = null; score = 0; streak = 0; summary = null; stopRequested = false; newBest = false
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
        val s = summarize(all.filter { it.index < due }, due) { song.events[it].isChord }
        finished = !stopRequested
        if (finished) newBest = progress.record(songId, s)
        summary = s
        phase = Phase.RESULTS
    }

    when (phase) {
        Phase.SETUP -> Setup(song, progress.best(songId), speed, { speed = it }, clickThrough, { clickThrough = it }, ready, error,
            speakerTip = clickThrough && route == AudioRoute.SPEAKER, calibrated = calibrated, routeLabel = route.label,
            onBack = onBack, onCalibrate = onCalibrate, onStart = { start() }) {
            Highway(song, speed.toDouble(), times, beats, barEvery, (times.firstOrNull() ?: 0.0) - 0.15, emptyMap(), it)
        }
        Phase.PLAYING -> {
            KeepScreenOn()
            Playing(song, speed, songTime, score, streak, last, onStop = { stopRequested = true }) {
                Highway(song, speed.toDouble(), times, beats, barEvery, songTime, judged, it)
            }
        }
        Phase.RESULTS -> Results(summary, finished, newBest, onRetry = { phase = Phase.SETUP }, onBack = onBack)
    }
}

// ---------------------------------------------------------------- setup

@Composable
private fun Setup(
    song: Song, best: ProgressStore.Best?, speed: Float, onSpeed: (Float) -> Unit, clickThrough: Boolean, onClick: (Boolean) -> Unit,
    ready: Boolean, error: String?, speakerTip: Boolean, calibrated: Boolean, routeLabel: String,
    onBack: () -> Unit, onCalibrate: () -> Unit, onStart: () -> Unit, preview: @Composable (Modifier) -> Unit,
) {
    MuTopBar("", onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SongCover(song.title, size = 92.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall, color = MuColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist.ifEmpty { "Mu originals" }, color = MuColors.textDim)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LevelBars(difficulty(song))
                    Pill(durationText(song.endTime))
                    if (song.chordCount > 0) Pill("${song.chordCount} chords")
                }
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Stars(best?.stars ?: 0, size = 24.dp)
            Spacer(Modifier.width(12.dp))
            Text(if (best != null) "Your best: ${best.pct}%" else "Earn up to 3 stars", color = MuColors.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${song.events.size} notes", color = MuColors.textMuted, fontSize = 13.sp)
        }
        preview(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, MuColors.stroke, RoundedCornerShape(24.dp)))

        MuCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(MuColors.accent) { TempoGlyph(Modifier.size(22.dp), Color.White) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tempo", style = MaterialTheme.typography.titleMedium, color = MuColors.text)
                    Text("%d bpm".format((song.tempoMap.first().bpm * speed).roundToInt()), color = MuColors.textMuted, fontSize = 13.sp)
                }
                Text("${(speed * 100).roundToInt()}%", color = MuColors.text, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
            Slider(value = speed, onValueChange = { onSpeed((it * 20).roundToInt() / 20f) }, valueRange = 0.5f..1.25f, steps = 14,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MuColors.accent, inactiveTrackColor = Color(0x22FFFFFF),
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in listOf(0.5f, 0.75f, 0.9f, 1f)) {
                    val sel = abs(speed - p) < 0.01f
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) MuColors.accent else Color(0x14FFFFFF))
                        .clickable { onSpeed(p) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                        Text("${(p * 100).roundToInt()}%", color = if (sel) Color.White else MuColors.textDim, fontWeight = FontWeight.Black, fontSize = 14.sp)
                    }
                }
            }
        }
        MuCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(MuColors.cyan) { WaveGlyph(Modifier.size(22.dp), Color.White) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Metronome", style = MaterialTheme.typography.titleMedium, color = MuColors.text)
                    Text("Clicks through the song · count-in is always on", color = MuColors.textMuted, fontSize = 13.sp)
                }
                Switch(checked = clickThrough, onCheckedChange = onClick, colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = MuColors.primary, checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = MuColors.textMuted, uncheckedTrackColor = MuColors.surfaceHi, uncheckedBorderColor = MuColors.stroke))
            }
        }
        if (speakerTip) Banner("The mic also hears speaker clicks. Headphones give cleaner scoring.", MuColors.cyan)
        if (!calibrated) Banner("Not calibrated for $routeLabel: timing may be off by tens of ms.", MuColors.ok, action = "Calibrate", onAction = onCalibrate)
        if (error != null) Banner(error, MuColors.wrong)
        ChunkyButton(if (ready) "Start" else "Starting audio…", onStart, Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow, enabled = ready, height = 66.dp)
    }
}

@Composable
private fun IconBadge(color: Color, content: @Composable () -> Unit) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.6f)))),
        contentAlignment = Alignment.Center) { content() }
}

// ---------------------------------------------------------------- playing

@Composable
private fun Playing(
    song: Song, speed: Float, songTime: Double, score: Int, streak: Int, last: Judgement?, onStop: () -> Unit,
    highway: @Composable (Modifier) -> Unit,
) {
    val maxScore = 100 * song.events.size
    val endT = song.endTime / speed
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Filled.Close, "Stop", onStop, size = 42.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                StarMeter(score.toFloat() / maxOf(maxScore, 1))
                Spacer(Modifier.height(6.dp))
                // song position
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x1AFFFFFF))) {
                    Box(Modifier.fillMaxWidth((songTime / endT).toFloat().coerceIn(0f, 1f)).height(4.dp).background(MuColors.textDim))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("%,d".format(score), color = MuColors.text, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("SCORE", style = OverlineStyle)
            }
        }
        // section + streak, then the latest judgement popping in
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val section = song.sections.lastOrNull { it.start / speed <= songTime }?.name
            Box(Modifier.width(110.dp)) { if (section != null && songTime >= 0) Pill(section.uppercase(), MuColors.textDim) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val fresh = last != null && songTime - last.songTime < 1.2
                if (fresh && last != null) key(last) {
                    FeedbackPop(feedback(last, song.events.getOrNull(last.index)?.notes?.map { it.midi }), judgementColor(last))
                }
            }
            Box(Modifier.width(110.dp), contentAlignment = Alignment.CenterEnd) {
                if (streak >= 2) Pill("$streak streak", MuColors.orange, MuColors.orange.copy(alpha = 0.16f), icon = FlameIcon)
            }
        }
        // the highway keeps a sensible height and sits centred in whatever room is left (tall in portrait, short in landscape)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(minOf(maxHeight, 440.dp)).clip(RoundedCornerShape(24.dp))
                .border(1.dp, MuColors.stroke, RoundedCornerShape(24.dp))) {
                highway(Modifier.fillMaxSize())
                if (songTime < 0) CountIn(song, speed, songTime)
            }
        }
    }
}

/** Score bar towards the song maximum, with the three star thresholds marked. */
@Composable
private fun StarMeter(fraction: Float) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
        val w = maxWidth
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x1AFFFFFF))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(Brush.horizontalGradient(listOf(MuColors.primary, MuColors.cyan))))
        }
        for (t in STAR_THRESHOLDS) {
            val lit = fraction >= t
            Icon(Icons.Filled.Star, null, tint = if (lit) MuColors.star else Color(0xFF5D5590),
                modifier = Modifier.size(if (lit) 22.dp else 18.dp).align(Alignment.CenterStart).offset(x = w * t - 11.dp))
        }
    }
}

@Composable
private fun FeedbackPop(text: String, color: Color) {
    val a = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { a.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) }
    Text(text, color = color, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        modifier = Modifier.scale(a.value).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun CountIn(song: Song, speed: Float, songTime: Double) {
    val beatSec = 60.0 / song.tempoMap.first().bpm / speed
    val n = ceil(-songTime / beatSec).toInt()
    val frac = ((-songTime / beatSec) - (n - 1)).toFloat().coerceIn(0f, 1f)   // 1 right on the click, → 0
    Box(Modifier.fillMaxSize().background(Color(0x88070512)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(120.dp).scale(0.85f + 0.3f * frac).clip(CircleShape)
                .background(Brush.radialGradient(listOf(MuColors.accent, MuColors.accentLip))), contentAlignment = Alignment.Center) {
                Text(if (n > 8) "…" else "$n", color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(14.dp))
            Text("GET READY", style = OverlineStyle.copy(color = MuColors.textDim, fontSize = 14.sp))
        }
    }
}

// ---------------------------------------------------------------- results

@Composable
private fun Results(s: Summary?, finished: Boolean, newBest: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    MuTopBar("", onBack)
    if (s == null) { Text("No result", Modifier.padding(20.dp), color = MuColors.textDim); return }
    val earned = stars(s.score, s.maxScore)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (!finished) "Stopped early" else when (earned) { 3 -> "Amazing!"; 2 -> "Great job!"; 1 -> "Nice work!"; else -> "Keep practicing!" },
            style = MaterialTheme.typography.displaySmall, color = MuColors.text, textAlign = TextAlign.Center,
        )
        BigStars(earned)
        val shown = remember { Animatable(0f) }
        LaunchedEffect(s) { shown.animateTo(s.score.toFloat(), tween(1300)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%,d".format(shown.value.roundToInt()), color = MuColors.text, fontSize = 56.sp, fontWeight = FontWeight.Black)
            Text("of %,d points".format(s.maxScore), color = MuColors.textMuted)
        }
        if (newBest) Pill("NEW PERSONAL BEST", MuColors.star, MuColors.star.copy(alpha = 0.16f), icon = Icons.Filled.Star)

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuCard(Modifier.weight(1f).fillMaxHeight(), padding = 14.dp, verticalArrangement = Arrangement.Center) {
                Ring(s.accuracyPct / 100f, MuColors.primary, Modifier.size(72.dp).align(Alignment.CenterHorizontally), stroke = 8.dp) {
                    Text("%.0f%%".format(s.accuracyPct), color = MuColors.text, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("ACCURACY", style = OverlineStyle, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Best streak", "${s.bestStreak}", Modifier.fillMaxWidth(), MuColors.orange)
                if (s.chordTotal > 0) StatTile("Clean chords", "${s.chordHits}/${s.chordTotal}", Modifier.fillMaxWidth(), MuColors.cyan)
                else StatTile("Notes hit", "${s.hits}/${s.total}", Modifier.fillMaxWidth(), MuColors.cyan)
            }
        }

        MuCard(Modifier.fillMaxWidth()) {
            Text("BREAKDOWN", style = OverlineStyle)
            Spacer(Modifier.height(12.dp))
            val parts = listOf(
                Triple("Perfect", s.perfect, MuColors.perfect), Triple("Good", s.good, MuColors.good), Triple("OK", s.ok, MuColors.ok),
                Triple("Octave off", s.partial, MuColors.partial), Triple("Wrong note/chord", s.wrong, MuColors.wrong), Triple("Missed", s.miss, MuColors.miss),
            )
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0x14FFFFFF))) {
                for ((_, n, c) in parts) if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxSize().background(c))
            }
            Spacer(Modifier.height(12.dp))
            for ((label, n, c) in parts) Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(10.dp))
                Text(label, color = MuColors.textDim, modifier = Modifier.weight(1f))
                Text("$n", color = MuColors.text, fontWeight = FontWeight.Black)
            }
            if (s.extra > 0) Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Spacer(Modifier.width(20.dp))
                Text("Extra notes (no penalty)", color = MuColors.textMuted, modifier = Modifier.weight(1f))
                Text("${s.extra}", color = MuColors.textMuted, fontWeight = FontWeight.Bold)
            }
        }

        s.meanOffsetMs?.let { TimingCard(it) }

        ChunkyButton("Play again", onRetry, Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh, height = 62.dp)
        ChunkyButton("Back to songs", onBack, Modifier.fillMaxWidth(), color = MuColors.accent, lip = MuColors.accentLip, textColor = Color.White, height = 62.dp)
    }
}

/** Three stars popping in one after another; the middle one bigger and raised. */
@Composable
private fun BigStars(earned: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 3) {
            val a = remember { Animatable(0f) }
            LaunchedEffect(earned) {
                delay(250L + 320L * i)
                a.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
            }
            val lit = i < earned
            val sz = if (i == 1) 92.dp else 70.dp
            Icon(Icons.Filled.Star, null, tint = if (lit) MuColors.star else Color(0xFF3A3466),
                modifier = Modifier.padding(bottom = if (i == 1) 18.dp else 0.dp).size(sz).scale(if (lit) a.value else 1f))
        }
    }
}

/** Mean timing offset on a ±130 ms scale (the scorer's window). */
@Composable
private fun TimingCard(meanMs: Float) {
    val verdict = when { abs(meanMs) < 15 -> "Right on the beat"; meanMs > 0 -> "You tend to play late"; else -> "You tend to rush" }
    val color = when { abs(meanMs) < 15 -> MuColors.perfect; abs(meanMs) < 40 -> MuColors.ok; else -> MuColors.partial }
    MuCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TIMING", style = OverlineStyle)
                Text(verdict, style = MaterialTheme.typography.titleMedium, color = MuColors.text)
            }
            Text("%+.0f ms".format(meanMs), color = color, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(14.dp))
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val y = size.height / 2; val cx = size.width / 2
            drawRoundRect(Color(0x1AFFFFFF), Offset(0f, y - 4f), Size(size.width, 8f), CornerRadius(4f))
            drawRoundRect(MuColors.perfect.copy(alpha = 0.35f), Offset(cx - size.width * 40f / 260f, y - 4f), Size(size.width * 80f / 260f, 8f), CornerRadius(4f))
            drawLine(Color.White.copy(alpha = 0.6f), Offset(cx, 2f), Offset(cx, size.height - 2f), strokeWidth = 2f)
            val mx = cx + (meanMs.coerceIn(-130f, 130f) / 130f) * cx
            drawCircle(color, 10f, Offset(mx, y)); drawCircle(Color.White, 4f, Offset(mx, y))
        }
        Row(Modifier.fillMaxWidth()) {
            Text("EARLY", style = OverlineStyle, modifier = Modifier.weight(1f))
            Text("LATE", style = OverlineStyle)
        }
    }
}
