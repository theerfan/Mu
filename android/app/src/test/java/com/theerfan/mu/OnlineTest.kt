package com.theerfan.mu

import com.theerfan.mu.online.ClasstabApi
import com.theerfan.mu.online.SharedLinks
import com.theerfan.mu.online.SongsterrApi
import com.theerfan.mu.online.SongsterrConverter
import com.theerfan.mu.song.AsciiTab
import com.theerfan.mu.song.SongParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineTest {
    // A made-up two-bar riff in drop D with a hammer-on, a bend and a chord line.
    private val tab = """
        Some Riff - by nobody
        Tuning: drop D   Time: 3/4   100 bpm   capo 2

        [Intro]
           D5              G
        e|-----------|-----0-----|
        B|-----------|-----0-----|
        G|-----------|-----0-----|
        D|--0--2h4---|-----0-----|
        A|--0--------|-----2-----|
        D|--0--------|--7b9------|
    """.trimIndent()

    @Test fun asciiTabTimingTuningAndTechniques() {
        val s = AsciiTab.parse(tab, "Some Riff")
        assertEquals(listOf(38, 45, 50, 55, 59, 64), s.tuning)
        assertEquals(2, s.capo); assertEquals(100.0, s.tempoMap.first().bpm, 0.0); assertEquals(3, s.timeSig.first().num)
        assertEquals(5, s.events.size)
        val first = s.events[0]
        assertEquals(3, first.notes.size); assertEquals("D5", first.chord)
        assertEquals(38 + 2, first.notes.maxBy { it.string }.midi)        // low D string, capo 2
        assertTrue("hammer on the 4", s.events[2].tech.toString().contains("hammer"))
        assertTrue("bend", s.events[3].tech.toString().contains("bend"))
        assertEquals("G", s.events[4].chord); assertEquals(5, s.events[4].notes.size)
        // bar 2 starts at 3 quarters = 1.8 s at 100 bpm; its notes sit inside it
        assertTrue(s.events[3].t >= 1.8 && s.events[4].t < 3.6)
        assertEquals("Intro", s.sections.single().name)
        // the result survives the app's own validation
        SongParser.parse(Json.encodeToString(com.theerfan.mu.song.Song.serializer(), s))
    }

    @Test fun songsterrConverterMapsStringsTiesTechniquesAndBacking() {
        val guitar = Json.parseToJsonElement("""
            {"tuning":[64,59,55,50,45,40],"capo":0,"automations":{"tempo":[{"measure":0,"position":0,"bpm":120}]},
             "measures":[
               {"signature":[4,4],"marker":{"text":"Verse"},"voices":[{"beats":[
                  {"duration":[1,4],"chord":{"text":"Em"},"notes":[{"string":5,"fret":0},{"string":4,"fret":2},{"string":3,"fret":2}]},
                  {"duration":[1,4],"notes":[{"string":3,"fret":2,"hp":true}]},
                  {"duration":[1,4],"notes":[{"string":3,"fret":4}]},
                  {"duration":[1,4],"notes":[{"string":3,"fret":4,"tie":true}]}]}]},
               {"voices":[{"beats":[{"duration":[1,2],"notes":[{"string":1,"fret":5,"bend":{"tone":100}}]},{"duration":[1,2],"rest":true,"notes":[{"rest":true}]}]}]}
             ]}""").jsonObject
        val bass = Json.parseToJsonElement("""{"tuning":[43,38,33,28],"measures":[{"signature":[4,4],"voices":[{"beats":[{"duration":[1,1],"notes":[{"string":3,"fret":0}]}]}]}]}""").jsonObject
        val drums = Json.parseToJsonElement("""{"measures":[{"signature":[4,4],"voices":[{"beats":[{"duration":[1,4],"notes":[{"string":-1,"fret":36}]}]}]}]}""").jsonObject
        val bt = SongsterrApi.Track(5, "X | Bass", "Electric Bass", 33, listOf(43, 38, 33, 28), 1, 10, false)
        val dt = SongsterrApi.Track(6, "Drums", "Drums", 1024, emptyList(), null, 10, false)
        val s = SongsterrConverter.convert(guitar, listOf(bt to bass, dt to drums), "T", "A", "Lead")
        assertEquals(listOf(40, 45, 50, 55, 59, 64), s.tuning)
        assertEquals(4, s.events.size)                                   // the tied note adds no onset
        assertEquals("Em", s.events[0].chord); assertEquals(listOf(4, 5, 6), s.events[0].notes.map { it.string })
        assertEquals(0.5, s.events[1].t, 1e-9)                           // quarter at 120 bpm
        assertTrue(s.events[2].tech.toString().contains("hammer"))
        assertEquals(59 + 5, s.events[3].notes.single().midi)          // string index 1 = B, fret 5
        assertTrue(s.events[3].tech.toString().contains("bend"))
        assertEquals(4.0, s.duration!!, 1e-9)
        assertEquals("Verse", s.sections.single().name)
        val parts = s.backing!!.parts.associateBy { it.kind }
        assertEquals(28.0, parts.getValue("bass").notes.single()[2], 0.0)
        assertEquals(36.0, parts.getValue("drums").notes.single()[2], 0.0)
        SongParser.parse(Json.encodeToString(com.theerfan.mu.song.Song.serializer(), s))
    }

    @Test fun classtabIndexAndEntities() {
        val html = """
            <b>Isaac Alb&eacute;niz &#160;(Isaak Albeniz)</b>&#160; (1860-1909)<br>
            <a href="albeniz_tango.txt">Tango in D</a> - <a href="albeniz_tango.mid">MIDI</a> - [<b>drop D</b>]<br>
            Op 1 - see <a href="albeniz_other.txt">Op 2</a><br>
            <b>Fernando Sor</b>&#160; (1778-1839)<br>
            <a href="sor_op60_01.txt">Op 60 No 1 in C</a> - [<b>easy</b>]<br>
        """.trimIndent()
        val e = ClasstabApi.parseIndex(html)
        assertEquals(listOf("albeniz_tango.txt", "sor_op60_01.txt"), e.map { it.file })
        assertEquals("Isaac Albéniz", e[0].artist); assertEquals("Fernando Sor", e[1].artist); assertTrue(e[1].easy)
        assertEquals("Córdoba & Cataluña", ClasstabApi.decode("C&oacute;rdoba &amp; Catalu&ntilde;a"))
    }

    @Test fun sharedLinks() {
        assertEquals(SharedLinks.Link.Songsterr(2, 3), SharedLinks.find("look https://www.songsterr.com/a/wsa/oasis-wonderwall-tab-s2t3 nice"))
        assertEquals(SharedLinks.Link.Songsterr(27, null), SharedLinks.find("https://www.songsterr.com/a/wsa/led-zeppelin-stairway-to-heaven-tab-s27"))
        assertEquals(SharedLinks.Link.Classtab("tarrega_lagrima.txt"), SharedLinks.find("https://www.classtab.org/tarrega_lagrima.txt"))
        assertNull(SharedLinks.find("e|--0--2--|"))
        assertNotNull(SharedLinks.find("songsterr.com/a/wsa/x-tab-s9t1"))
    }
}
