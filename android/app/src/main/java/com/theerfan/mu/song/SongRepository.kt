package com.theerfan.mu.song

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

data class SongEntry(val id: String, val song: Song, val bundled: Boolean)

/** Bundled songs (assets/songs) + imported songs (filesDir/songs). */
class SongRepository(private val context: Context) {
    private val dir = File(context.filesDir, "songs").apply { mkdirs() }

    fun list(): List<SongEntry> {
        val bundled = (context.assets.list("songs") ?: emptyArray()).sorted().mapNotNull { name ->
            runCatching {
                val text = context.assets.open("songs/$name").bufferedReader().use { it.readText() }
                SongEntry("asset:$name", SongParser.parse(text), true)
            }.getOrNull()
        }
        val imported = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            runCatching { SongEntry("file:${f.name}", SongParser.parse(f.readText()), false) }.getOrNull()
        }
        return bundled + imported
    }

    fun get(id: String): Song? = when {
        id.startsWith("asset:") -> runCatching {
            SongParser.parse(context.assets.open("songs/${id.removePrefix("asset:")}").bufferedReader().use { it.readText() })
        }.getOrNull()
        id.startsWith("file:") -> runCatching { SongParser.parse(File(dir, id.removePrefix("file:")).readText()) }.getOrNull()
        else -> null
    }

    /** Stores a song built in the app (online import, text tab). */
    fun save(song: Song): SongEntry = importText(Json.encodeToString(Song.serializer(), song))

    /**
     * Imports Mu song JSON, or failing that a plain-text guitar tab (titled [fallbackTitle] or its first line).
     * Throws SongFormatException when it is neither.
     */
    fun importAny(text: String, fallbackTitle: String? = null): SongEntry = try {
        importText(text)
    } catch (e: SongFormatException) {
        if (text.trimStart().startsWith("{")) throw e
        val title = fallbackTitle?.takeIf { it.isNotBlank() } ?: text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && '|' !in it }?.take(60) ?: "Text tab"
        save(AsciiTab.parse(text, title))
    }

    /** Validates and stores a song; returns the stored entry. Throws SongFormatException on bad input. */
    fun importText(text: String): SongEntry {
        val song = SongParser.parse(text)
        val hash = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
        val safe = song.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "song" }
        val f = File(dir, "$safe-$hash.json")
        f.writeText(text)
        return SongEntry("file:${f.name}", song, false)
    }

    fun importUri(uri: Uri): SongEntry {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw SongFormatException("Could not read $uri")
        if (text.length > 20_000_000) throw SongFormatException("File too large")
        return importAny(text)
    }

    fun delete(id: String) { if (id.startsWith("file:")) File(dir, id.removePrefix("file:")).delete() }
}
