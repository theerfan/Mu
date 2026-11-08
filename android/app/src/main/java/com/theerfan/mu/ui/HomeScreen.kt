package com.theerfan.mu.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theerfan.mu.audio.AudioRoute
import com.theerfan.mu.audio.CalibrationStore
import com.theerfan.mu.play.ProgressStore
import com.theerfan.mu.song.Arrangements
import com.theerfan.mu.song.SongEntry
import com.theerfan.mu.song.SongRepository
import java.time.LocalTime

/** Colour for a 1–10 difficulty level: green, yellow, orange, red. */
fun levelColor(level: Int) = when { level <= 3 -> MuColors.primary; level <= 6 -> MuColors.star; level <= 8 -> MuColors.orange; else -> MuColors.wrong }

fun durationText(sec: Double) = "%d:%02d".format(sec.toInt() / 60, sec.toInt() % 60)

@Composable
fun HomeScreen(repo: SongRepository, version: Int, onChanged: () -> Unit, onPlay: (String) -> Unit, onTuner: () -> Unit, onCalibrate: () -> Unit) {
    val context = LocalContext.current
    val songs = remember(version) { repo.list() }
    val levels = remember(songs) { songs.associate { e -> e.id to Arrangements.versions(e.song).map { it.profile.level } } }
    val progress = remember { ProgressStore(context) }
    val calibration = remember { CalibrationStore(context) }
    val route = remember { AudioRoute.current(context) }
    val calibrated = calibration.tap(route) != null || calibration.loopback(route) != null
    var help by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<SongEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { repo.importUri(uri) }
            .onSuccess { Toast.makeText(context, "Imported “${it.song.title}”", Toast.LENGTH_SHORT).show(); onChanged() }
            .onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val importSong = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
    val featured = songs.firstOrNull { it.id == progress.lastSongId } ?: songs.firstOrNull()

    MuScreen {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Header(progress.streakDays(), progress.totalStars(), onHelp = { help = true }) }
            item {
                val hour = remember { LocalTime.now().hour }
                Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    Text(when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" },
                        color = MuColors.textDim, style = MaterialTheme.typography.titleMedium)
                    Text("Ready to play?", style = MaterialTheme.typography.headlineLarge, color = MuColors.text)
                }
            }
            featured?.let { f -> item { FeaturedCard(f, progress.best(f.id), resume = f.id == progress.lastSongId, onPlay = { onPlay(f.id) }) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolTile("Tuner", "Get in tune", MuColors.cyan to MuColors.accentLip, Modifier.weight(1f), onTuner) { GaugeGlyph(Modifier.size(30.dp), Color.White) }
                    ToolTile("Calibrate", if (calibrated) "${route.label} ✓" else "Not set up yet", MuColors.star to MuColors.orange,
                        Modifier.weight(1f), onCalibrate, alert = !calibrated) { WaveGlyph(Modifier.size(30.dp), Color.White) }
                }
            }
            item { SectionHeader("Your songs", Modifier.padding(top = 14.dp), trailing = "${songs.size}") }
            items(songs, key = { it.id }) { e -> SongRow(e, progress.best(e.id), levels[e.id].orEmpty(), onClick = { onPlay(e.id) }, onDelete = { toDelete = e }) }
            item { ImportTile(importSong) }
        }
    }

    toDelete?.let { e ->
        MuDialog("Delete “${e.song.title}”?", "This removes the imported song and its best score from this phone.", "Delete",
            onConfirm = { repo.delete(e.id); toDelete = null; onChanged() }, onDismiss = { toDelete = null }, dismiss = "Cancel", danger = true)
    }
    if (help) MuDialog(
        "Getting songs in",
        "Mu plays song JSON files (schema 1).\n\n" +
            "On a computer: node importer/mu-import.mjs song.gp5 -o song.json (Guitar Pro 3-7, MusicXML, alphaTex), " +
            "then copy song.json to the phone and use “Import a song”, or share it to Mu from any app.\n\n" +
            "First time: run Calibrate once per output (speaker / headphones), then tune up with the Tuner.",
        "Got it", onConfirm = { help = false }, onDismiss = { help = false },
    )
}

@Composable
private fun Header(streak: Int, stars: Int, onHelp: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(MuColors.primary, MuColors.cyan))),
            contentAlignment = Alignment.Center) { Text("μ", color = MuColors.onPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(10.dp))
        Text("Mu", style = MaterialTheme.typography.titleLarge, color = MuColors.text, modifier = Modifier.weight(1f))
        Pill(if (streak > 0) "$streak day${if (streak == 1) "" else "s"}" else "No streak", color = if (streak > 0) MuColors.orange else MuColors.textMuted,
            background = MuColors.orange.copy(alpha = if (streak > 0) 0.16f else 0.06f), icon = FlameIcon)
        Spacer(Modifier.width(8.dp))
        Pill("$stars", color = MuColors.star, background = MuColors.star.copy(alpha = 0.14f), icon = Icons.Filled.Star)
        Spacer(Modifier.width(8.dp))
        RoundIconButton(Icons.Filled.Info, "Help", onHelp, size = 38.dp)
    }
}

@Composable
private fun FeaturedCard(e: SongEntry, best: ProgressStore.Best?, resume: Boolean, onPlay: () -> Unit) {
    val song = e.song
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF1C1E21), Color(0xFF0E2A1A), Color(0xFF125A33))))) {
        // Decorative lanes with a few notes flying in, like a mini highway.
        Canvas(Modifier.matchParentSize()) {
            val laneGap = size.height / 9f
            for (s in 0 until 6) {
                val y = laneGap * (1.4f + s)
                drawLine(Color.White.copy(alpha = 0.10f), Offset(size.width * 0.6f, y), Offset(size.width, y), strokeWidth = 1.5f + s * 0.5f)
            }
            val notes = listOf(0.76f to 1, 0.84f to 3, 0.93f to 0, 0.97f to 4, 0.88f to 5)
            for ((x, s) in notes) {
                val c = MuColors.strings[s]; val r = laneGap * 0.36f
                drawRoundRect(c.copy(alpha = 0.55f), Offset(size.width * x - r, laneGap * (1.4f + s) - r * 0.8f), Size(r * 2, r * 1.6f), CornerRadius(r))
            }
        }
        Column(Modifier.padding(20.dp)) {
            Pill(if (resume) "CONTINUE PLAYING" else "START HERE", color = Color.White, background = Color(0x33FFFFFF))
            Spacer(Modifier.height(14.dp))
            Text(song.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.7f))
            Text(song.artist.ifEmpty { "Mu originals" }, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stars(best?.stars ?: 0, size = 22.dp, empty = Color(0x40FFFFFF))
                Spacer(Modifier.width(10.dp))
                Text(best?.let { "Best ${it.pct}%" } ?: "Not played yet", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.height(18.dp))
            ChunkyButton(if (resume) "Play again" else "Play", onPlay, Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow)
        }
    }
}

@Composable
private fun ToolTile(title: String, subtitle: String, colors: Pair<Color, Color>, modifier: Modifier, onClick: () -> Unit, alert: Boolean = false, glyph: @Composable () -> Unit) {
    MuCard(modifier, onClick = onClick, padding = 16.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(colors.first, colors.second))),
                contentAlignment = Alignment.Center) { glyph() }
            Spacer(Modifier.weight(1f))
            if (alert) Box(Modifier.size(10.dp).clip(CircleShape).background(MuColors.orange))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MuColors.text)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (alert) MuColors.orange else MuColors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SongRow(e: SongEntry, best: ProgressStore.Best?, levels: List<Int>, onClick: () -> Unit, onDelete: () -> Unit) {
    val song = e.song
    MuCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 12.dp, shape = RoundedCornerShape(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SongCover(song.title, size = 64.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, color = MuColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist.ifEmpty { if (e.bundled) "Mu originals" else "Imported" }, color = MuColors.textMuted, fontSize = 13.sp, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val lo = levels.minOrNull() ?: 1; val hi = levels.maxOrNull() ?: lo
                    LevelBars((hi + 1) / 2)
                    Pill(if (lo == hi) "Lv $hi" else "Lv $lo–$hi", levelColor(hi), levelColor(hi).copy(alpha = 0.14f))
                    Pill(durationText(song.endTime))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Stars(best?.stars ?: 0, size = 16.dp)
                Spacer(Modifier.height(4.dp))
                Text(best?.let { "${it.pct}%" } ?: "New", color = if (best != null) MuColors.text else MuColors.primary, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
            if (!e.bundled) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Delete, "Delete", tint = MuColors.textMuted, modifier = Modifier.size(20.dp))
                }
            } else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MuColors.textMuted)
        }
    }
}

/** Five ascending bars, the first `level` lit. */
@Composable
fun LevelBars(level: Int, modifier: Modifier = Modifier) {
    val color = levelColor(level * 2)
    Row(modifier.height(14.dp).padding(end = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..5) Box(Modifier.width(4.dp).height((4 + i * 2).dp).clip(RoundedCornerShape(2.dp)).background(if (i <= level) color else Color(0x26FFFFFF)))
    }
}

@Composable
private fun ImportTile(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(MuColors.textMuted, cornerRadius = CornerRadius(22.dp.toPx()),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        }
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MuColors.accent.copy(alpha = 0.2f)).border(1.dp, MuColors.accent.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, null, tint = MuColors.accent) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Import a song", style = MaterialTheme.typography.titleMedium, color = MuColors.text)
                Text("Song JSON from the Guitar Pro / MusicXML importer", color = MuColors.textMuted, fontSize = 13.sp)
            }
        }
    }
}
