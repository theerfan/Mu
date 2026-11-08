package com.theerfan.mu

import com.theerfan.mu.audio.BackingBuilder
import com.theerfan.mu.audio.Voice
import com.theerfan.mu.song.BackingInfo
import com.theerfan.mu.song.BackingPart
import com.theerfan.mu.song.SongParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackingTest {
    private val etude = SongParser.parse(File("../../importer/samples/chord_etude.json").readText())

    @Test fun grooveHasDrumsOnEveryBeatAndBassUnderEveryBar() {
        val g = BackingBuilder.groove(etude)
        val kicks = g.filter { it.voice == Voice.DRUMS && it.midi == 36 }
        val snares = g.filter { it.voice == Voice.DRUMS && it.midi == 38 }
        val bass = g.filter { it.voice == Voice.BASS }
        val bars = (etude.endTime * 80 / 60 / 4).toInt()   // 80 bpm, 4/4
        assertTrue("kicks ${kicks.size}", kicks.size >= 2 * bars - 2)
        assertTrue("snares ${snares.size}", snares.size >= 2 * bars - 2)
        assertTrue("bass per bar ${bass.size}", bass.size >= bars - 1)
        assertTrue("bass stays low", bass.all { it.midi in 24..43 })
        // the first C chord (lowest note C3) puts a C in the bass
        val firstC = etude.events.first { it.chord == "C" }
        assertEquals(0, bass.first { it.t >= firstC.t - 0.01 }.midi % 12)
    }

    @Test fun ownPartsReplaceTheGrooveAndScaleWithSpeed() {
        val s = etude.copy(backing = BackingInfo(parts = listOf(BackingPart("Bass", "bass", listOf(listOf(1.0, 0.5, 33.0, 0.9))))))
        val b = BackingBuilder.band(s, 0.5)
        assertEquals(1, b.size); assertEquals(2.0, b.t[0], 1e-9); assertEquals(1.0, b.dur[0], 1e-9); assertEquals(Voice.BASS, b.voice[0])
        val guide = BackingBuilder.guide(etude, 1.0)
        assertEquals(etude.events.sumOf { it.notes.size }, guide.size)
    }
}
