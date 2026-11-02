package com.theerfan.mu.song

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Song JSON (schema 1) as written by importer/mu-import.mjs; see ARCHITECTURE.md §6. */
@Serializable
data class Song(
    val schema: Int = 1,
    val title: String = "Untitled",
    val artist: String = "",
    val source: String = "",
    val track: String = "",
    val duration: Double? = null,
    val tuning: List<Int> = STANDARD_TUNING,
    val capo: Int = 0,
    val tempoMap: List<TempoPoint> = listOf(TempoPoint(0.0, 120.0)),
    val timeSig: List<TimeSig> = listOf(TimeSig(0, 4, 4)),
    val sections: List<Section> = emptyList(),
    val events: List<SongEvent> = emptyList(),
) {
    val stringCount: Int get() = tuning.size
    val endTime: Double get() = maxOf(duration ?: 0.0, events.maxOfOrNull { it.t + it.dur } ?: 0.0)
    val chordCount: Int get() = events.count { it.isChord }

    companion object {
        val STANDARD_TUNING = listOf(40, 45, 50, 55, 59, 64)
    }
}

@Serializable data class TempoPoint(val beat: Double, val bpm: Double)
@Serializable data class TimeSig(val bar: Int = 0, val num: Int = 4, val den: Int = 4)
@Serializable data class Section(val name: String, val start: Double, val end: Double)

@Serializable
data class SongNote(val string: Int, val fret: Int, val midi: Int, val tech: List<JsonElement> = emptyList())

@Serializable
data class SongEvent(
    val t: Double,
    val dur: Double = 0.0,
    val chord: String? = null,
    val notes: List<SongNote> = emptyList(),
    val tech: List<JsonElement> = emptyList(),
) {
    val isChord: Boolean get() = notes.size >= 2
    /** Technique names, e.g. "hammer", "bend", "strum_down" (objects contribute their "type"). */
    val techNames: List<String>
        get() = tech.mapNotNull {
            when (it) {
                is JsonPrimitive -> it.contentOrNull
                is JsonObject -> it["type"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }
}

class SongFormatException(message: String) : Exception(message)

object SongParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Parses and validates; events are sorted by time. Throws SongFormatException with a readable reason. */
    fun parse(text: String): Song {
        val song = try {
            json.decodeFromString(Song.serializer(), text)
        } catch (e: Exception) {
            throw SongFormatException("Not a Mu song JSON: ${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}")
        }
        if (song.schema != 1) throw SongFormatException("Unsupported schema ${song.schema} (expected 1)")
        if (song.events.isEmpty()) throw SongFormatException("Song has no events")
        if (song.tempoMap.isEmpty() || song.tempoMap.any { it.bpm <= 0 }) throw SongFormatException("Bad tempo map")
        song.events.forEachIndexed { i, e ->
            if (e.notes.isEmpty()) throw SongFormatException("Event $i has no notes")
            e.notes.forEach { n ->
                if (n.string !in 1..song.stringCount) throw SongFormatException("Event $i: string ${n.string} outside 1..${song.stringCount}")
                if (n.midi !in 20..110) throw SongFormatException("Event $i: MIDI ${n.midi} out of range")
            }
        }
        return song.copy(events = song.events.sortedBy { it.t })
    }
}
