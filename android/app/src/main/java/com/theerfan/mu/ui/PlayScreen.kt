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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import com.theerfan.mu.audio.AudioDecoder
import com.theerfan.mu.audio.AudioRoute
import com.theerfan.mu.audio.BackingBuilder
import com.theerfan.mu.audio.BackingPrefs
import com.theerfan.mu.audio.BandMode
import com.theerfan.mu.audio.GuideMode
import com.theerfan.mu.audio.SynthNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.theerfan.mu.audio.CalibrationStore
import com.theerfan.mu.audio.NativeAudio
import com.theerfan.mu.play.Judgement
import com.theerfan.mu.play.Kind
import com.theerfan.mu.play.ProgressStore
import com.theerfan.mu.play.Summary
import com.theerfan.mu.play.points
import com.theerfan.mu.play.stars
import com.theerfan.mu.play.summarize
import com.theerfan.mu.song.Arrangements
import com.theerfan.mu.song.DifficultyProfile
import com.theerfan.mu.song.Song
import com.theerfan.mu.song.Version
import com.theerfan.mu.song.SongRepository
import com.theerfan.mu.song.Timing
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private enum class Phase { SETUP, PLAYING, PAUSED, RESULTS }

/** Backing tracks start this long before song time 0, so a recording's intro can play during the count-in. */
private const val BACKING_PREROLL_SEC = 4.0

@Composable
fun PlayScreen(repo: SongRepository, songId: String, hasMic: Boolean, requestMic: () -> Unit, onBack: () -> Unit, onCalibrate: () -> Unit) {
    val song = remember(songId) { repo.get(songId) }
    when {
        song == null -> MuScreen { MuTopBar("Song", onBack); Text("Could not load this song.", Modifier.padding(20.dp), color = MuColors.textDim) }
        !hasMic -> MuScreen { MuTopBar(song.title, onBack); MicNeeded(requestMic) }
        else -> PlayContent(song, songId, onBack, onCalibrate)
    }
}

@Composable
private fun PlayContent(base: Song, songId: String, onBack: () -> Unit, onCalibrate: () -> Unit) {
    val context = LocalContext.current
    val versions = remember(base) { Arrangements.versions(base) }
    var versionIdx by remember { mutableIntStateOf(versions.lastIndex) }
    val version = versions[versionIdx]
    val song = version.song
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

    // backing: band (synth or a recording), guide (your part), chosen per song
    val scope = rememberCoroutineScope()
    val bprefs = remember { BackingPrefs(context) }
    var bandMode by remember { mutableStateOf(bprefs.band(songId)) }
    var guideMode by remember { mutableStateOf(bprefs.guide(songId)) }
    var bandVol by remember { mutableFloatStateOf(bprefs.volume(songId)) }
    var recStart by remember { mutableFloatStateOf(bprefs.recStart(songId)) }
    var recName by remember { mutableStateOf(bprefs.recordingName(songId)) }
    var recPcm by remember { mutableStateOf<AudioDecoder.Pcm?>(null) }
    var preparing by remember { mutableStateOf<String?>(null) }
    var backingError by remember { mutableStateOf<String?>(null) }
    var struggle by remember { mutableFloatStateOf(0f) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null } ?: "Recording"
            bprefs.setRecording(songId, uri, name); recName = name; recPcm = null; backingError = null
            bandMode = BandMode.RECORDING; bprefs.set(songId, band = BandMode.RECORDING)
        }
    }

    val times = remember(speed, song) { Timing.expected(song, speed.toDouble()).times }
    val beats = remember(speed, song) { Timing.beatTimes(song.tempoMap, song.endTime).map { it / speed }.toDoubleArray() }
    val barEvery = song.timeSig.firstOrNull()?.num ?: 4

    // ---- the run: positions are score seconds (100 % tempo) so they survive tempo changes
    var pos by remember { mutableDoubleStateOf(0.0) }                 // where the next run starts / where we paused
    var runStart by remember { mutableDoubleStateOf(0.0) }            // practice seconds where the current run begins
    var segBase by remember { mutableIntStateOf(0) }                  // first event index loaded into the scorer
    var loop by remember { mutableStateOf<ClosedFloatingPointRange<Double>?>(null) }
    var practice by remember { mutableStateOf(false) }                // seeked or looped: results are a practice summary
    var pauseRequested by remember { mutableStateOf(false) }
    var seekTo by remember { mutableStateOf<Double?>(null) }           // a seek made while playing, applied when the run stops
    var continueAfterSeek by remember { mutableStateOf(false) }        // that seek should keep playing, not open the pause panel
    var restartAt by remember { mutableStateOf<Double?>(null) }        // tempo changed while playing: resume here
    var renderedKey by remember { mutableStateOf<List<Any?>?>(null) }

    fun guideGain() = when (guideMode) { GuideMode.OFF -> 0f; GuideMode.ALWAYS -> 0.55f; GuideMode.AUTO -> 0.6f * struggle.coerceIn(0f, 1f) }
    fun bandGain() = if (bandMode == BandMode.OFF) 0f else bandVol

    /** Renders the backing for the current tempo/version/settings unless that's already done. False = can't play. */
    suspend fun ensureBacking(): Boolean {
        val key = listOf(speed, versionIdx, bandMode, recStart, recName)
        if (key == renderedKey) return true
        val sp = speed.toDouble()
        preparing = if (bandMode == BandMode.RECORDING && recPcm == null) "Loading recording…" else "Preparing backing…"
        backingError = null
        val result = runCatching {
            withContext(Dispatchers.Default) {
                val pcm = if (bandMode == BandMode.RECORDING) recPcm ?: bprefs.recording(songId)?.let { AudioDecoder.decode(context, it) } else null
                val band = if (bandMode == BandMode.SYNTH || (bandMode == BandMode.RECORDING && pcm == null)) BackingBuilder.band(base, sp) else SynthNotes.of(emptyList())
                val guide = BackingBuilder.guide(song, sp)
                NativeAudio.renderBacking(band.t, band.dur, band.midi, band.vel, band.voice, guide.t, guide.dur, guide.midi, guide.vel,
                    song.endTime / sp, BACKING_PREROLL_SEC, pcm?.samples, pcm?.rate ?: 0, sp, recStart.toDouble())
                pcm
            }
        }
        preparing = null
        result.onSuccess { if (it != null) recPcm = it; renderedKey = key }
            .onFailure { backingError = "Backing unavailable: ${it.message}"; NativeAudio.setBackingGains(0f, 0f) }
        return result.isSuccess || bandMode != BandMode.RECORDING
    }

    /** Starts a run at score position [p100] (loop end or song end stops it) after [countIn] clicks. */
    fun playFrom(p100: Double, countIn: Int = barEvery) = scope.launch {
        if (!ensureBacking()) { phase = if (phase == Phase.PLAYING) Phase.PAUSED else phase; return@launch }
        val sp = speed.toDouble()
        val t0 = (p100 / sp).coerceIn(0.0, song.endTime / sp)
        val exp = Timing.expected(song, sp)
        val from = Timing.firstAtOrAfter(exp.times, t0 - 0.02)
        val to = loop?.let { Timing.firstAtOrAfter(exp.times, it.endInclusive / sp) }?.coerceAtLeast(from) ?: exp.times.size
        val n0 = exp.counts.take(from).sum(); val n1 = n0 + exp.counts.slice(from until to).sum()
        NativeAudio.loadSong(exp.times.copyOfRange(from, to), exp.counts.copyOfRange(from, to), exp.midis.copyOfRange(n0, n1))
        segBase = from
        val clicks = Timing.clicksFrom(song, sp, t0, countIn, clickThrough)
        NativeAudio.setUserOffsetMs(store.effective(AudioRoute.current(context)))
        NativeAudio.setBackingGains(bandGain(), guideGain())
        pauseRequested = false; seekTo = null; restartAt = null
        runStart = t0; songTime = t0 - clicks.leadInSec
        if (NativeAudio.startPlay(clicks.times, clicks.accents, clicks.leadInSec - t0, 0.6f)) { phase = Phase.PLAYING; runId++ }
    }

    fun resetStats() { judged.clear(); all = emptyList(); last = null; score = 0; streak = 0; summary = null; newBest = false; struggle = 0f }

    fun finishRun(completed: Boolean) {
        val s = if (!practice && completed) summarize(all, times.size) { song.events[it].isChord }
        else {
            // a practice run (seeks, loops, or ended early): score only the notes that were actually played
            val idx = judged.keys.sorted()
            val remap = idx.withIndex().associate { (k, v) -> v to k }
            val js = idx.map { i -> judged.getValue(i).copy(index = remap.getValue(i)) } + all.filter { it.kind == Kind.EXTRA }
            summarize(js, idx.size) { song.events[idx[it]].isChord }
        }
        finished = completed && !practice
        if (finished) newBest = progress.record(version.progressKey(songId), s)
        summary = s
        phase = Phase.RESULTS
    }

    fun changeSpeed(v: Float) {
        val nv = v.coerceIn(0.4f, 1.5f)
        if (nv == speed) return
        if (phase == Phase.PLAYING && preparing == null) restartAt = maxOf(songTime, runStart) * speed   // resume right here at the new tempo
        speed = nv
    }

    fun seek(practiceSec: Double) {
        var p = (practiceSec * speed).coerceIn(0.0, song.endTime)
        loop?.let { p = p.coerceIn(it.start, it.endInclusive) }
        practice = true
        if (phase == Phase.PLAYING) {
            seekTo = p
            continueAfterSeek = true
            pauseRequested = true
        } else {
            pos = p
            songTime = p / speed
        }
    }

    LaunchedEffect(runId) {
        if (phase != Phase.PLAYING) return@LaunchedEffect
        val buf = FloatArray(64 * Judgement.FLOATS)
        val sp = speed.toDouble(); val tt = times; val base0 = segBase
        val end = song.endTime / sp + 1.6
        val loopEnd = loop?.let { it.endInclusive / sp }
        fun apply(js: List<Judgement>, dueBefore: Double = Double.MAX_VALUE) {
            for (j0 in js) {
                val j = if (j0.index >= 0) j0.copy(index = j0.index + base0) else j0
                if (j.kind != Kind.EXTRA && j.index >= 0 && tt.getOrElse(j.index) { 0.0 } >= dueBefore) continue   // not reached yet
                all = all + j
                if (j.kind != Kind.EXTRA && j.index >= 0) judged[j.index] = j
                if (j.kind != Kind.MISS || last == null || j.songTime > (last?.songTime ?: 0f)) last = j
                score += points(j)
                streak = if (j.kind == Kind.HIT) streak + 1 else if (j.kind == Kind.EXTRA) streak else 0
                // falling behind raises the guide; clean hits fade it back out
                struggle = when (j.kind) { Kind.MISS, Kind.WRONG -> struggle + 0.34f; Kind.HIT -> struggle - 0.25f; else -> struggle }.coerceIn(0f, 1.2f)
            }
        }
        var sentGuide = -1f
        var outcome: String
        while (true) {
            withFrameNanos { }
            songTime = NativeAudio.songTime()
            val n = NativeAudio.poll(buf)
            if (n > 0) apply(Judgement.unpack(buf, n))
            struggle *= 0.997f
            val g = guideGain()
            if (abs(g - sentGuide) > 0.01f) { NativeAudio.setBackingGains(bandGain(), g); sentGuide = g }
            outcome = when {
                pauseRequested -> "pause"
                restartAt != null -> "tempo"
                loopEnd != null && songTime > loopEnd + 0.25 -> "loop"
                songTime > end -> "end"
                else -> continue
            }
            break
        }
        val fin = FloatArray(4096 * Judgement.FLOATS)
        val stoppedAt = songTime
        apply(Judgement.unpack(fin, NativeAudio.finishPlay(fin)), if (outcome == "end" || outcome == "loop") Double.MAX_VALUE else stoppedAt)
        NativeAudio.stopAll()
        when (outcome) {
            "end" -> finishRun(completed = true)
            "loop" -> { practice = true; playFrom(loop!!.start, countIn = maxOf(2, barEvery / 2)) }
            "tempo" -> playFrom(restartAt!!)
            else -> {
                val cont = continueAfterSeek
                continueAfterSeek = false
                pos = seekTo ?: (maxOf(stoppedAt, runStart) * sp).coerceIn(0.0, song.endTime)
                songTime = pos / speed
                seekTo = null
                if (cont) playFrom(pos, countIn = 1) else phase = Phase.PAUSED
            }
        }
    }

    BackHandler(enabled = phase == Phase.PLAYING) { pauseRequested = true }
    BackHandler(enabled = phase == Phase.PAUSED) { finishRun(completed = false) }

    when (phase) {
        Phase.SETUP -> MuScreen {
            Setup(song, versions, versionIdx, { versionIdx = it }, versions.map { progress.best(it.progressKey(songId)) }, speed, { speed = it }, clickThrough, { clickThrough = it }, ready, error,
                speakerTip = clickThrough && route == AudioRoute.SPEAKER && bandMode == BandMode.OFF && guideMode == GuideMode.OFF, calibrated = calibrated, routeLabel = route.label,
                onBack = onBack, onCalibrate = onCalibrate, onStart = { resetStats(); practice = false; loop = null; pos = 0.0; playFrom(0.0) }, preparing = preparing,
                backing = {
                    BackingCard(bandMode, { bandMode = it; bprefs.set(songId, band = it) }, guideMode, { guideMode = it; bprefs.set(songId, guide = it) },
                        bandVol, { bandVol = it; bprefs.set(songId, volume = it) }, recName, { picker.launch(arrayOf("audio/*")) },
                        recStart, { recStart = it; bprefs.set(songId, recStart = it) }, hasParts = base.backing?.parts?.isNotEmpty() == true,
                        speaker = route == AudioRoute.SPEAKER, error = backingError)
                },
                overview = { SongMinimap(song, times, -1.0, song.endTime / speed, emptyMap(), it) }) { mod ->
                Box(mod) {
                    AirBackground(0, 0f, 0.0, Modifier.matchParentSize())
                    Highway(song, speed.toDouble(), times, beats, barEvery, (times.firstOrNull() ?: 0.0) - 0.15, emptyMap(), Modifier.matchParentSize())
                }
            }
        }
        Phase.PLAYING, Phase.PAUSED -> {
            KeepScreenOn()
            LockLandscape()
            val paused = phase == Phase.PAUSED
            val shown = if (paused) pos / speed else songTime
            val mistakes = all.count { it.kind == Kind.MISS || it.kind == Kind.WRONG }
            Playing(song, speed, shown, runStart, paused, preparing, score, streak, mistakes, struggle,
                onPause = { if (phase == Phase.PLAYING) pauseRequested = true },
                minimap = {
                    ScrubMinimap(song, times, shown, song.endTime / speed, judged, loop?.let { l -> l.start / speed..l.endInclusive / speed },
                        onSeek = { seek(it) },
                        onLoop = { l ->
                            loop = l?.let { it.start * speed..it.endInclusive * speed }
                            if (l != null) practice = true
                            if (phase != Phase.PLAYING) {
                                loop?.let { pos = pos.coerceIn(it.start, it.endInclusive) }
                                songTime = pos / speed
                            }
                        },
                        onCommit = {
                            if (phase != Phase.PLAYING) return@ScrubMinimap
                            val here = loop?.let { songTime.coerceIn(it.start / speed, it.endInclusive / speed) } ?: songTime
                            seek(here)
                        },
                        modifier = it)
                },
                pausePanel = {
                    PausePanel(song, pos, speed, { changeSpeed(it) }, loop, { l -> loop = l; if (l != null) { practice = true; pos = l.start; songTime = pos / speed } },
                        onResume = { playFrom(pos) },
                        onRestart = { resetStats(); practice = loop != null; pos = loop?.start ?: 0.0; playFrom(pos) },
                        onFinish = { finishRun(completed = false) }, onQuit = onBack, preparing = preparing)
                }) {
                Highway(song, speed.toDouble(), times, beats, barEvery, shown, judged, it)
            }
        }
        Phase.RESULTS -> MuScreen { Results(summary, finished, practice, newBest, onRetry = { phase = Phase.SETUP }, onBack = onBack) }
    }
}

// ---------------------------------------------------------------- setup

@Composable
private fun Setup(
    song: Song, versions: List<Version>, selected: Int, onSelect: (Int) -> Unit, bests: List<ProgressStore.Best?>, speed: Float, onSpeed: (Float) -> Unit, clickThrough: Boolean, onClick: (Boolean) -> Unit,
    ready: Boolean, error: String?, speakerTip: Boolean, calibrated: Boolean, routeLabel: String,
    onBack: () -> Unit, onCalibrate: () -> Unit, onStart: () -> Unit, preparing: String?, backing: @Composable () -> Unit,
    overview: @Composable (Modifier) -> Unit,
    preview: @Composable (Modifier) -> Unit,
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
                    Pill(durationText(song.endTime))
                    Pill("${song.events.size} notes")
                    bests[selected]?.let { Pill("Best ${it.pct}%", MuColors.star, MuColors.star.copy(alpha = 0.14f)) }
                }
            }
        }
        VersionPicker(versions, selected, bests, onSelect)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            preview(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(24.dp)))
            overview(Modifier.fillMaxWidth().height(36.dp))
        }
        HardnessCard(versions[selected].profile)

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
        backing()
        if (speakerTip) Banner("The mic also hears speaker clicks. Headphones give cleaner scoring.", MuColors.cyan)
        if (!calibrated) Banner("Not calibrated for $routeLabel: timing may be off by tens of ms.", MuColors.ok, action = "Calibrate", onAction = onCalibrate)
        if (error != null) Banner(error, MuColors.wrong)
        ChunkyButton(preparing ?: if (ready) "Start" else "Starting audio…", onStart, Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow,
            enabled = ready && preparing == null, height = 66.dp)
    }
}

@Composable
private fun VersionPicker(versions: List<Version>, selected: Int, bests: List<ProgressStore.Best?>, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Version", trailing = "${versions.size} of 3")
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            versions.forEachIndexed { i, v ->
                val sel = i == selected
                val c = levelColor(v.profile.level)
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                    .background(if (sel) c.copy(alpha = 0.13f) else MuColors.surface)
                    .border(if (sel) 2.dp else 1.dp, if (sel) c else MuColors.stroke, RoundedCornerShape(18.dp))
                    .clickable { onSelect(i) }.padding(12.dp)) {
                    Text(v.level.title, style = MaterialTheme.typography.titleSmall, color = MuColors.text, maxLines = 1)
                    Text("Level ${v.profile.level}", color = c, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Stars(bests[i]?.stars ?: 0, size = 14.dp)
                }
            }
        }
        Text(versions[selected].level.blurb, color = MuColors.textDim, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Level badge plus each difficulty factor with a 5-step meter and a plain explanation. */
@Composable
private fun HardnessCard(p: DifficultyProfile) {
    val c = levelColor(p.level)
    MuCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(CircleShape).background(c.copy(alpha = 0.15f)).border(2.dp, c, CircleShape), contentAlignment = Alignment.Center) {
                Text("${p.level}", color = c, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("WHAT MAKES IT HARD", style = OverlineStyle)
                Text("${p.label} · level ${p.level} of 10", style = MaterialTheme.typography.titleMedium, color = MuColors.text)
            }
        }
        Spacer(Modifier.height(10.dp))
        for (f in p.factors()) Column(Modifier.padding(vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.title, style = MaterialTheme.typography.titleSmall, color = MuColors.text, modifier = Modifier.weight(1f))
                Meter(f.amount)
            }
            Spacer(Modifier.height(2.dp))
            Text(f.detail, color = MuColors.textDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Meter(amount: Float) {
    val lit = if (amount <= 0f) 0 else (amount * 5).roundToInt().coerceIn(1, 5)
    val c = levelColor(lit * 2)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until 5) Box(Modifier.size(width = 16.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(if (i < lit) c else Color(0x1FFFFFFF)))
    }
}

/** Band (off / synth / your recording) with volume and alignment, and the guide mode. */
@Composable
private fun BackingCard(
    band: BandMode, onBand: (BandMode) -> Unit, guide: GuideMode, onGuide: (GuideMode) -> Unit, volume: Float, onVolume: (Float) -> Unit,
    recName: String?, onPick: () -> Unit, recStart: Float, onRecStart: (Float) -> Unit, hasParts: Boolean, speaker: Boolean, error: String?,
) {
    MuCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(MuColors.primary) { WaveGlyph(Modifier.size(22.dp), Color.White) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Backing", style = MaterialTheme.typography.titleMedium, color = MuColors.text)
                Text(when (band) {
                    BandMode.OFF -> "Just you and the metronome"
                    BandMode.SYNTH -> if (hasParts) "The song's other instruments, synthesised" else "Bass and drums generated from the tab"
                    BandMode.RECORDING -> recName ?: "Pick an audio file of the song"
                }, color = MuColors.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        Segmented(BandMode.entries, band, { it.label }, onBand)
        if (band == BandMode.RECORDING) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Song starts at", color = MuColors.textDim, modifier = Modifier.weight(1f))
                Nudge("−") { onRecStart(((recStart - 0.1f) * 10).roundToInt() / 10f) }
                Text("%.1f s".format(recStart), color = MuColors.text, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp))
                Nudge("+") { onRecStart(((recStart + 0.1f) * 10).roundToInt() / 10f) }
            }
            Slider(recStart.coerceIn(-5f, 60f), { onRecStart((it * 10).roundToInt() / 10f) }, valueRange = -5f..60f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MuColors.primary, inactiveTrackColor = Color(0x22FFFFFF)))
            Text("Where the first note of the tab falls in the recording. The recording slows down with the tempo, keeping its pitch.",
                color = MuColors.textMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).clickable(onClick = onPick).padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(if (recName == null) "CHOOSE AUDIO FILE" else "CHANGE FILE", color = MuColors.primary, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
        }
        if (band != BandMode.OFF) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", color = MuColors.textDim, modifier = Modifier.width(72.dp))
                Slider(volume, onVolume, Modifier.weight(1f), valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MuColors.primary, inactiveTrackColor = Color(0x22FFFFFF)))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("GUIDE", style = OverlineStyle)
        Spacer(Modifier.height(6.dp))
        Segmented(GuideMode.entries, guide, { it.label }, onGuide)
        Spacer(Modifier.height(6.dp))
        Text("The guide plays your part softly. \"When I fall behind\" brings it in after misses and fades it out as you catch up.",
            color = MuColors.textMuted, fontSize = 12.sp)
        if (speaker && (band != BandMode.OFF || guide != GuideMode.OFF)) {
            Spacer(Modifier.height(10.dp))
            Banner("On the phone speaker the mic hears the backing too. Use headphones for fair scoring.", MuColors.ok)
        }
        if (error != null) { Spacer(Modifier.height(10.dp)); Banner(error, MuColors.wrong) }
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x10FFFFFF)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (o in options) {
            val sel = o == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) MuColors.surfaceHi else Color.Transparent)
                .clickable { onSelect(o) }.padding(vertical = 9.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(label(o), color = if (sel) MuColors.text else MuColors.textMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Nudge(label: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(MuColors.surfaceHi).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = MuColors.text, fontWeight = FontWeight.Black, fontSize = 18.sp)
    }
}

@Composable
private fun IconBadge(color: Color, content: @Composable () -> Unit) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.6f)))),
        contentAlignment = Alignment.Center) { content() }
}

// ---------------------------------------------------------------- playing

private val ComboSteps = intArrayOf(0, 4, 10, 20, 35)

/** 1x..5x from the current hit streak, plus how full the bar toward the next tier is. */
private fun comboTier(streak: Int): Pair<Int, Float> {
    var tier = 0
    for (i in ComboSteps.indices) if (streak >= ComboSteps[i]) tier = i
    val fill = if (tier >= ComboSteps.lastIndex) 1f
    else (streak - ComboSteps[tier]).toFloat() / (ComboSteps[tier + 1] - ComboSteps[tier])
    return (tier + 1) to fill.coerceIn(0f, 1f)
}

@Composable
private fun Playing(
    song: Song, speed: Float, songTime: Double, runStart: Double, paused: Boolean, preparing: String?,
    score: Int, streak: Int, mistakes: Int, struggle: Float,
    onPause: () -> Unit, minimap: @Composable (Modifier) -> Unit, pausePanel: @Composable () -> Unit,
    highway: @Composable (Modifier) -> Unit,
) {
    val safe = WindowInsets.safeDrawing
    Box(Modifier.fillMaxSize()) {
        AirBackground(streak, struggle, songTime, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.fillMaxSize().pointerInput(paused) { if (!paused) detectTapGestures { onPause() } }) {
                    highway(Modifier.fillMaxSize())
                }
                if (!paused && preparing == null && songTime < runStart) CountIn(song, speed, runStart - songTime)
                if (preparing != null && !paused) Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(preparing, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                ComboBadge(
                    streak,
                    Modifier.align(Alignment.TopStart)
                        .windowInsetsPadding(safe.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                        .padding(start = 12.dp, top = 8.dp),
                )
                ScoreReadout(
                    score, mistakes,
                    Modifier.align(Alignment.TopEnd)
                        .windowInsetsPadding(safe.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                        .padding(end = 16.dp, top = 6.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(start = 4.dp, end = 14.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clickable(onClick = onPause), contentAlignment = Alignment.Center) {
                    PauseGlyph(Modifier.size(18.dp), Color.White)
                }
                minimap(Modifier.weight(1f).height(40.dp))
            }
        }
        if (paused) Box(Modifier.fillMaxSize().background(Color(0xB3000000)).padding(vertical = 8.dp), contentAlignment = Alignment.Center) { pausePanel() }
    }
}

@Composable
private fun ComboBadge(streak: Int, modifier: Modifier = Modifier) {
    val (mult, fill) = comboTier(streak)
    Column(
        modifier.width(64.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF3C4C26)).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${mult}x", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(Color(0xFF243018))) {
            if (fill > 0f) Box(Modifier.fillMaxWidth(fill).height(5.dp).clip(RoundedCornerShape(50)).background(Color(0xFFC6EE78)))
        }
    }
}

@Composable
private fun ScoreReadout(score: Int, mistakes: Int, modifier: Modifier = Modifier) {
    val shadow = Shadow(Color.Black.copy(alpha = 0.28f), Offset(0f, 1f), 3f)
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(score.toString(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, style = TextStyle(shadow = shadow))
        Text(mistakes.toString(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, style = TextStyle(shadow = shadow))
    }
}

@Composable
private fun CountIn(song: Song, speed: Float, remaining: Double) {
    val beatSec = 60.0 / song.tempoMap.first().bpm / speed
    val n = ceil(remaining / beatSec).toInt().coerceAtLeast(1)
    val frac = ((remaining / beatSec) - (n - 1)).toFloat().coerceIn(0f, 1f)   // 1 right on the click, → 0
    Box(Modifier.fillMaxSize().padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            if (n > 8) "…" else "$n",
            color = Color.White,
            fontSize = 72.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(top = 2.dp).scale(0.86f + 0.2f * frac),
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.30f), Offset(0f, 3f), 12f)),
        )
    }
}

// ---------------------------------------------------------------- results

@Composable
private fun Results(s: Summary?, finished: Boolean, practice: Boolean, newBest: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    MuTopBar("", onBack)
    if (s == null) { Text("No result", Modifier.padding(20.dp), color = MuColors.textDim); return }
    val earned = stars(s.score, s.maxScore)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (practice) "Practice run" else if (!finished) "Stopped early" else when (earned) { 3 -> "Amazing!"; 2 -> "Great job!"; 1 -> "Nice work!"; else -> "Keep practicing!" },
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
            Icon(Icons.Filled.Star, null, tint = if (lit) MuColors.star else Color(0xFF2A2C30),
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
