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
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
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
import com.theerfan.mu.play.STAR_THRESHOLDS
import com.theerfan.mu.play.Summary
import com.theerfan.mu.play.feedback
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

private enum class Phase { SETUP, PLAYING, RESULTS }

/** Backing tracks start this long before song time 0, so a recording's intro can play during the count-in. */
private const val BACKING_PREROLL_SEC = 4.0

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

    fun guideGain() = when (guideMode) { GuideMode.OFF -> 0f; GuideMode.ALWAYS -> 0.55f; GuideMode.AUTO -> 0.6f * struggle.coerceIn(0f, 1f) }

    fun start() = scope.launch {
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
        result.onSuccess { if (it != null) recPcm = it }
            .onFailure { backingError = "Backing unavailable: ${it.message}"; NativeAudio.setBackingGains(0f, 0f) }
        if (result.isFailure && bandMode == BandMode.RECORDING) return@launch
        struggle = 0f
        NativeAudio.setBackingGains(if (bandMode == BandMode.OFF) 0f else bandVol, guideGain())
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
                // falling behind raises the guide; clean hits fade it back out
                struggle = when (j.kind) { Kind.MISS, Kind.WRONG -> struggle + 0.34f; Kind.HIT -> struggle - 0.25f; else -> struggle }.coerceIn(0f, 1.2f)
            }
        }
        var sentGuide = -1f
        while (true) {
            withFrameNanos { }
            songTime = NativeAudio.songTime()
            val n = NativeAudio.poll(buf)
            if (n > 0) apply(Judgement.unpack(buf, n))
            struggle *= 0.997f
            val g = guideGain()
            if (abs(g - sentGuide) > 0.01f) { NativeAudio.setBackingGains(if (bandMode == BandMode.OFF) 0f else bandVol, g); sentGuide = g }
            if (songTime > end || stopRequested) break
        }
        val fin = FloatArray(4096 * Judgement.FLOATS)
        apply(Judgement.unpack(fin, NativeAudio.finishPlay(fin)))
        NativeAudio.stopAll()
        // Stopped early: only score the events that were due.
        val due = if (stopRequested) Timing.firstAtOrAfter(times, songTime) else times.size
        val s = summarize(all.filter { it.index < due }, due) { song.events[it].isChord }
        finished = !stopRequested
        if (finished) newBest = progress.record(version.progressKey(songId), s)
        summary = s
        phase = Phase.RESULTS
    }

    when (phase) {
        Phase.SETUP -> Setup(song, versions, versionIdx, { versionIdx = it }, versions.map { progress.best(it.progressKey(songId)) }, speed, { speed = it }, clickThrough, { clickThrough = it }, ready, error,
            speakerTip = clickThrough && route == AudioRoute.SPEAKER && bandMode == BandMode.OFF && guideMode == GuideMode.OFF, calibrated = calibrated, routeLabel = route.label,
            onBack = onBack, onCalibrate = onCalibrate, onStart = { start() }, preparing = preparing,
            backing = {
                BackingCard(bandMode, { bandMode = it; bprefs.set(songId, band = it) }, guideMode, { guideMode = it; bprefs.set(songId, guide = it) },
                    bandVol, { bandVol = it; bprefs.set(songId, volume = it) }, recName, { picker.launch(arrayOf("audio/*")) },
                    recStart, { recStart = it; bprefs.set(songId, recStart = it) }, hasParts = base.backing?.parts?.isNotEmpty() == true,
                    speaker = route == AudioRoute.SPEAKER, error = backingError)
            },
            overview = { SongMinimap(song, times, -1.0, song.endTime / speed, emptyMap(), it) }) {
            Highway(song, speed.toDouble(), times, beats, barEvery, (times.firstOrNull() ?: 0.0) - 0.15, emptyMap(), it)
        }
        Phase.PLAYING -> {
            KeepScreenOn()
            Playing(song, speed, songTime, score, streak, last, onStop = { stopRequested = true },
                minimap = { SongMinimap(song, times, songTime, song.endTime / speed, judged, it) }) {
                Highway(song, speed.toDouble(), times, beats, barEvery, songTime, judged, it)
            }
        }
        Phase.RESULTS -> Results(summary, finished, newBest, onRetry = { phase = Phase.SETUP }, onBack = onBack)
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
            preview(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, MuColors.stroke, RoundedCornerShape(24.dp)))
            overview(Modifier.fillMaxWidth().height(40.dp))
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

@Composable
private fun Playing(
    song: Song, speed: Float, songTime: Double, score: Int, streak: Int, last: Judgement?, onStop: () -> Unit,
    minimap: @Composable (Modifier) -> Unit, highway: @Composable (Modifier) -> Unit,
) {
    val maxScore = 100 * song.events.size
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Filled.Close, "Stop", onStop, size = 42.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { StarMeter(score.toFloat() / maxOf(maxScore, 1)) }
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
        // the highway keeps a sensible height and sits centred in whatever room is left (tall in portrait, short in
        // landscape), with the song minimap right under it
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            val mapH = if (maxHeight < 300.dp) 30.dp else 44.dp
            val boardH = minOf(maxHeight - mapH - 8.dp, 440.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(boardH).clip(RoundedCornerShape(24.dp))
                    .border(1.dp, MuColors.stroke, RoundedCornerShape(24.dp))) {
                    highway(Modifier.fillMaxSize())
                    if (songTime < 0) CountIn(song, speed, songTime)
                }
                minimap(Modifier.fillMaxWidth().height(mapH))
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
            Icon(Icons.Filled.Star, null, tint = if (lit) MuColors.star else Color(0xFF45484E),
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
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
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
