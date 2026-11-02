package com.theerfan.mu.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theerfan.mu.song.SongEntry
import com.theerfan.mu.song.SongRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repo: SongRepository, version: Int, onChanged: () -> Unit, onPlay: (String) -> Unit, onTuner: () -> Unit, onCalibrate: () -> Unit) {
    val context = LocalContext.current
    val songs = remember(version) { repo.list() }
    var help by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<SongEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { repo.importUri(uri) }
            .onSuccess { Toast.makeText(context, "Imported “${it.song.title}”", Toast.LENGTH_SHORT).show(); onChanged() }
            .onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mu") }, actions = {
                TextButton(onClick = onTuner) { Text("Tuner") }
                TextButton(onClick = onCalibrate) { Text("Calibrate") }
                TextButton(onClick = { help = true }) { Text("?") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Import song") })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(songs, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth().clickable { onPlay(e.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            val bpm = e.song.tempoMap.firstOrNull()?.bpm?.toInt() ?: 0
                            Text(
                                listOfNotNull(e.song.artist.ifEmpty { null }, "%d:%02d".format(e.song.endTime.toInt() / 60, e.song.endTime.toInt() % 60),
                                    "$bpm bpm", "${e.song.events.size} events", "${e.song.chordCount} chords", if (e.bundled) "bundled" else "imported")
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!e.bundled) IconButton(onClick = { toDelete = e }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                }
            }
        }
    }
    toDelete?.let { e ->
        AlertDialog(onDismissRequest = { toDelete = null }, title = { Text("Delete “${e.song.title}”?") },
            confirmButton = { TextButton(onClick = { repo.delete(e.id); toDelete = null; onChanged() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } })
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, confirmButton = { TextButton(onClick = { help = false }) { Text("OK") } },
        title = { Text("Getting songs in") },
        text = {
            Text(
                "Mu plays song JSON files (schema 1).\n\n" +
                    "On a computer: node importer/mu-import.mjs song.gp5 -o song.json (Guitar Pro 3-7, MusicXML, alphaTex), " +
                    "then copy song.json to the phone and use “Import song”, or share it to Mu from any app.\n\n" +
                    "First time: run Calibrate once per output (speaker / headphones), then tune up with the Tuner."
            )
        })
}
