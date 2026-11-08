package com.theerfan.mu

import com.theerfan.mu.song.Arrangements
import com.theerfan.mu.song.ChordKind
import com.theerfan.mu.song.Difficulty
import com.theerfan.mu.song.Level
import com.theerfan.mu.song.SongEvent
import com.theerfan.mu.song.SongNote
import com.theerfan.mu.song.SongParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArrangementTest {
    private val samples = File("../../importer/samples")
    private fun load(name: String) = SongParser.parse(File(samples, "$name.json").readText())
    private fun chord(name: String?, vararg sf: Pair<Int, Int>) =
        SongEvent(0.0, 1.0, name, sf.map { (s, f) -> SongNote(s, f, listOf(64, 59, 55, 50, 45, 40)[s - 1] + f) })

    @Test fun chordEtudeHasThreeVersionsEasiestFirst() {
        val vs = Arrangements.versions(load("chord_etude"))
        assertEquals(listOf(Level.BASICS, Level.SIMPLIFIED, Level.FULL), vs.map { it.level })
        assertTrue(vs[0].song.events.size < vs[2].song.events.size)
        assertTrue(vs[0].song.events.all { it.notes.size == 1 })
        assertTrue(vs[1].song.events.all { it.notes.size <= 3 })
        assertTrue("levels rise", vs.zipWithNext().all { (a, b) -> a.profile.level <= b.profile.level })
        // simplified chords are subsets of the real voicing, so they stay playable
        val full = vs[2].song.events.associateBy { it.t }
        for (e in vs[1].song.events) assertTrue(e.notes.all { n -> full.getValue(e.t).notes.any { it.string == n.string && it.fret == n.fret } })
    }

    @Test fun profileExplainsChordsAndTechniques() {
        val p = Difficulty.profile(load("chord_etude"))
        assertTrue(p.chords.getValue(ChordKind.OPEN).containsAll(listOf("C", "G", "Am", "Em", "D")))
        assertEquals(listOf("Cmaj7"), p.chords.getValue(ChordKind.EXTENDED))
        assertEquals(6, p.maxNotes); assertEquals(3, p.maxFret)
        assertTrue(p.factors().any { it.title == "Chord changes" })
        val ex1 = Difficulty.profile(load("exercise1"))
        assertTrue(ex1.techniques.keys.containsAll(listOf("hammer", "bend", "palm_mute")))
        assertTrue(ex1.factors().first { it.title == "Techniques" }.detail.contains("Bends"))
    }

    @Test fun chordKinds() {
        assertEquals(ChordKind.BARRE, Difficulty.chordKind(chord("F", 6 to 1, 5 to 3, 4 to 3, 3 to 2, 2 to 1, 1 to 1)))
        assertEquals(ChordKind.POWER, Difficulty.chordKind(chord(null, 6 to 3, 5 to 5)))
        assertEquals(ChordKind.EXTENDED, Difficulty.chordKind(chord("Dsus4", 4 to 0, 3 to 2, 2 to 3, 1 to 3)))
        assertEquals(ChordKind.OPEN, Difficulty.chordKind(chord("Am", 5 to 0, 4 to 2, 3 to 2, 2 to 1, 1 to 0)))
        assertEquals("G5", Difficulty.chordName(chord(null, 6 to 3, 5 to 5)))
        assertEquals("Am", Difficulty.chordName(chord(null, 5 to 0, 4 to 2, 3 to 2, 2 to 1, 1 to 0)))
        assertEquals("C", Difficulty.chordSymbol("Cmaj"))
        assertEquals("C", Difficulty.chordSymbol("CMaj"))
        assertEquals("C", Difficulty.chordSymbol("Cmajor"))
        assertEquals("Cm", Difficulty.chordSymbol("Cmin"))
        assertEquals("Cm", Difficulty.chordSymbol("Cminor"))
        assertEquals("Am", Difficulty.chordSymbol("Amin"))
        assertEquals("CΔ7", Difficulty.chordSymbol("Cmaj7"))
        assertEquals("CΔ7", Difficulty.chordSymbol("Cmajor7"))
        assertEquals("CΔ7", Difficulty.chordSymbol("CM7"))
        assertEquals("F♯m", Difficulty.chordSymbol("F#m"))
        assertEquals("F♯", Difficulty.chordSymbol("F#"))
        assertEquals("B♭", Difficulty.chordSymbol("Bb"))
        assertEquals("B♭m7", Difficulty.chordSymbol("Bbmin7"))
        assertEquals("B♭Δ7", Difficulty.chordSymbol("Bbmaj7"))
        assertEquals("E♭", Difficulty.chordSymbol("Eb"))
        assertEquals("E♭m", Difficulty.chordSymbol("D#m"))
        assertEquals("Dsus4", Difficulty.chordSymbol("Dsus4"))
        assertEquals("C7", Difficulty.chordSymbol("C7"))
        assertEquals("C7♭9", Difficulty.chordSymbol("C7b9"))
        assertEquals("C°", Difficulty.chordSymbol("Cdim"))
        assertEquals("C+", Difficulty.chordSymbol("Caug"))
        assertEquals("Am/C", Difficulty.chordSymbol("Amin/C"))
        assertEquals("F♯m/C♯", Difficulty.chordSymbol("F#m/C#"))
    }
}
