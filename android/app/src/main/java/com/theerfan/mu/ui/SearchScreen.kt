package com.theerfan.mu.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theerfan.mu.online.ClasstabApi
import com.theerfan.mu.online.OnlineSong
import com.theerfan.mu.online.SongsterrApi
import com.theerfan.mu.song.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Source(val label: String, val color: Color) { SONGSTERR("Songsterr", MuColors.orange), CLASSTAB("Classtab", MuColors.cyan) }

/**
 * Online search across Songsterr (band songs, several parts per song) and Classtab (classical guitar). Picking a
 * result downloads it, converts it on the phone, saves it with the other songs and opens it.
 */
@Composable
fun SearchScreen(repo: SongRepository, onBack: () -> Unit, onOpen: (String) -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var sources by remember { mutableStateOf(setOf(Source.SONGSTERR, Source.CLASSTAB)) }
    var results by remember { mutableStateOf<List<OnlineSong>?>(null) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var pickTrack by remember { mutableStateOf<OnlineSong.Songsterr?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun search() {
        val q = query.trim(); if (q.isEmpty()) return
        keyboard?.hide(); searching = true; errors = emptyList()
        scope.launch {
            val (found, errs) = withContext(Dispatchers.IO) {
                coroutineScope {
                    val ss = async { if (Source.SONGSTERR in sources) runCatching { SongsterrApi.search(q) } else Result.success(emptyList()) }
                    val ct = async { if (Source.CLASSTAB in sources) runCatching { ClasstabApi.search(context, q) } else Result.success(emptyList()) }
                    val a = ss.await(); val b = ct.await()
                    (a.getOrDefault(emptyList()) + b.getOrDefault(emptyList())) to listOfNotNull(
                        a.exceptionOrNull()?.let { "Songsterr: ${friendly(it)}" }, b.exceptionOrNull()?.let { "Classtab: ${friendly(it)}" })
                }
            }
            results = found; errors = errs; searching = false
        }
    }

    fun download(label: String, block: () -> com.theerfan.mu.song.Song) {
        downloading = label; downloadError = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { repo.save(block()) } }
            downloading = null
            r.onSuccess { onChanged(); onOpen(it.id) }.onFailure { downloadError = friendly(it) }
        }
    }

    MuScreen {
        MuTopBar("Find songs", onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            TextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Song, artist or composer", color = MuColors.textMuted) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MuColors.textDim) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MuColors.surfaceHi, unfocusedContainerColor = MuColors.surface, focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent, cursorColor = MuColors.primary, focusedTextColor = MuColors.text, unfocusedTextColor = MuColors.text,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (s in Source.entries) {
                    val on = s in sources
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) s.color.copy(alpha = 0.16f) else Color(0x10FFFFFF))
                        .border(1.dp, if (on) s.color.copy(alpha = 0.6f) else MuColors.stroke, RoundedCornerShape(50))
                        .clickable { sources = if (on && sources.size > 1) sources - s else sources + s }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text(s.label, color = if (on) s.color else MuColors.textMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (searching) CircularProgressIndicator(Modifier.size(22.dp), color = MuColors.primary, strokeWidth = 2.5.dp)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(errors) { Banner(it, MuColors.wrong) }
            val list = results
            when {
                list == null -> item { SearchHint() }
                list.isEmpty() && !searching -> item { Text("Nothing found. Try the artist, the song title, or a composer.", color = MuColors.textDim) }
                else -> items(list) { r ->
                    ResultRow(r) {
                        when (r) {
                            is OnlineSong.Songsterr -> pickTrack = r
                            is OnlineSong.Classtab -> download(r.title) { ClasstabApi.download(r) }
                        }
                    }
                }
            }
        }
    }

    pickTrack?.let { s ->
        val guitars = s.tracks.filter { it.isGuitar }.sortedByDescending { it.views }
        AlertDialog(
            onDismissRequest = { pickTrack = null }, containerColor = MuColors.surfaceHi, shape = RoundedCornerShape(28.dp),
            title = { Text("Which part do you play?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The other instruments become your backing band.", color = MuColors.textDim, fontSize = 13.sp)
                    for ((i, t) in guitars.withIndex()) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF))
                            .clickable { pickTrack = null; download("${s.title} · ${t.role}") { SongsterrApi.download(s, t.index) } }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.role, color = MuColors.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.instrument, color = MuColors.textMuted, fontSize = 12.sp, maxLines = 1)
                        }
                        if (i == 0) Pill("Most played", MuColors.star, MuColors.star.copy(alpha = 0.14f))
                        t.difficulty?.let { Spacer(Modifier.width(6.dp)); LevelBars(it.coerceIn(1, 5)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickTrack = null }) { Text("CANCEL", color = MuColors.textDim, fontWeight = FontWeight.Bold) } },
        )
    }
    downloading?.let { label ->
        AlertDialog(onDismissRequest = {}, containerColor = MuColors.surfaceHi, shape = RoundedCornerShape(28.dp), confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = MuColors.primary, strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Downloading and converting", color = MuColors.text, fontWeight = FontWeight.Bold)
                        Text(label, color = MuColors.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            })
    }
    downloadError?.let { MuDialog("Couldn't import that one", it, "OK", onConfirm = { downloadError = null }, onDismiss = { downloadError = null }) }
}

@Composable
private fun SearchHint() {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MuCard(Modifier.fillMaxWidth()) {
            Text("SONGSTERR", style = OverlineStyle.copy(color = MuColors.orange))
            Text("Band songs with every part tabbed. Pick your guitar part; bass, drums and the other parts become your backing.",
                color = MuColors.textDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text("Uses Songsterr's unofficial web API, so it can stop working without notice. For personal practice only.",
                color = MuColors.textMuted, fontSize = 12.sp)
        }
        MuCard(Modifier.fillMaxWidth()) {
            Text("CLASSTAB", style = OverlineStyle.copy(color = MuColors.cyan))
            Text("About 4,000 free classical and fingerstyle tabs in plain text: Tárrega, Sor, Bach, Carcassi…",
                color = MuColors.textDim, style = MaterialTheme.typography.bodyMedium)
        }
        Text("Everything you pick is converted on this phone and saved with your songs, so it works offline afterwards. " +
            "You can also share a Songsterr or Classtab link, or any text tab, from your browser to Mu.",
            color = MuColors.textMuted, fontSize = 13.sp)
    }
}

@Composable
private fun ResultRow(r: OnlineSong, onClick: () -> Unit) {
    MuCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 12.dp, shape = RoundedCornerShape(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SongCover(r.title, size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium, color = MuColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.artist.ifEmpty { "Unknown" }, color = MuColors.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (r) {
                        is OnlineSong.Songsterr -> {
                            Pill("Songsterr", Source.SONGSTERR.color, Source.SONGSTERR.color.copy(alpha = 0.14f))
                            val g = r.tracks.count { it.isGuitar }
                            Pill("$g guitar part${if (g == 1) "" else "s"}")
                            if (r.tracks.any { it.isDrums || it.isBass }) Pill("+ band")
                        }
                        is OnlineSong.Classtab -> {
                            Pill("Classtab", Source.CLASSTAB.color, Source.CLASSTAB.color.copy(alpha = 0.14f))
                            if (r.easy) Pill("Easy", MuColors.primary, MuColors.primary.copy(alpha = 0.14f))
                        }
                    }
                }
            }
        }
    }
}

private fun friendly(t: Throwable): String = when (t) {
    is java.net.UnknownHostException, is java.net.ConnectException -> "You seem to be offline."
    is java.net.SocketTimeoutException -> "The site took too long to answer."
    else -> t.message ?: t.javaClass.simpleName
}
