package com.theerfan.mu

import com.theerfan.mu.song.SongFormatException
import com.theerfan.mu.song.SongParser
import com.theerfan.mu.song.TempoPoint
import com.theerfan.mu.song.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SongTest {
    private val samples = File("../../importer/samples")

    @Test fun parsesAllBundledSamples() {
        val files = samples.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue("expected bundled sample songs", files.size >= 3)
        for (f in files) {
            val s = SongParser.parse(f.readText())
            assertTrue("${f.name}: events", s.events.isNotEmpty())
            assertTrue("${f.name}: sorted", s.events.zipWithNext().all { (a, b) -> a.t <= b.t })
            s.events.forEach { e -> e.notes.forEach { n -> assertEquals("${f.name}: midi = tuning + fret", s.tuning[s.stringCount - n.string] + s.capo + n.fret, n.midi) } }
        }
    }

    @Test fun exercise1HasChordsAndTechniques() {
        val s = SongParser.parse(File(samples, "exercise1.json").readText())
        assertEquals("Mu Exercise 1", s.title)
        assertEquals(listOf("C", "G", "Em"), s.events.mapNotNull { it.chord })
        assertTrue(s.events.any { "bend" in it.techNames })
        assertTrue(s.events.any { "hammer" in it.techNames })
    }

    @Test fun rejectsBadInput() {
        for (bad in listOf("not json", """{"schema":2,"events":[]}""", """{"schema":1,"events":[]}""",
            """{"schema":1,"events":[{"t":0,"notes":[{"string":9,"fret":0,"midi":40}]}]}""")) {
            try { SongParser.parse(bad); fail("accepted: $bad") } catch (_: SongFormatException) {}
        }
    }

    @Test fun minimalSongUsesDefaults() {
        val s = SongParser.parse("""{"events":[{"t":1.0,"notes":[{"string":6,"fret":0,"midi":40}]}], "unknownField": 3}""")
        assertEquals(listOf(40, 45, 50, 55, 59, 64), s.tuning)
        assertEquals(120.0, s.tempoMap.first().bpm, 0.0)
        assertEquals(1.0, s.endTime, 1e-9)
    }

    @Test fun beatTimesFollowTempoChanges() {
        val map = listOf(TempoPoint(0.0, 90.0), TempoPoint(8.0, 120.0))
        assertEquals(8 * 60.0 / 90, Timing.beatToSec(map, 8.0), 1e-9)
        assertEquals(8 * 60.0 / 90 + 2 * 0.5, Timing.beatToSec(map, 10.0), 1e-9)
        val beats = Timing.beatTimes(map, 9.333)
        assertEquals(16, beats.size)  // beats 0-7 at 90 bpm (to 5.333 s), 8-15 at 120 bpm; beat 16 = 9.3333 s > 9.333
    }

    @Test fun clicksAndExpectedScaleWithSpeed() {
        val s = SongParser.parse(File(samples, "exercise1.json").readText())
        val c = Timing.clicks(s, 0.5, 4, throughSong = false)
        assertEquals(4, c.times.size)
        assertEquals(-4 * 60.0 / 90 / 0.5, c.times[0], 1e-9)
        assertTrue(c.accents[0] && !c.accents[1])
        assertTrue(c.leadInSec > 4 * 60.0 / 90 / 0.5)
        val e = Timing.expected(s, 0.5)
        assertEquals(s.events.size, e.times.size)
        assertEquals(s.events.last().t / 0.5, e.times.last(), 1e-9)
        assertEquals(s.events.sumOf { it.notes.size }, e.midis.size)
        assertEquals(2, Timing.firstAtOrAfter(doubleArrayOf(0.0, 1.0, 2.0, 3.0), 1.5))
    }
}
