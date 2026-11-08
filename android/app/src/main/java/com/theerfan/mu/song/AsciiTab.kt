package com.theerfan.mu.song

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Plain-text guitar tab ("e|--0--2--|", as on Classtab or pasted from any site) → Mu song.
 *
 * Text tab has no rhythm, so timing comes from layout: every bar gets the time signature's length and a note's place
 * in its bar is its column's place between the bar lines. Good for evenly spaced tabs (most are); the tempo slider
 * fixes the overall speed. String names on the left set the tuning (nearest to standard for each letter); chord-name
 * lines above a system label its chords; "Intro"/"[Verse]"-style lines start sections; "capo N", "N bpm" and
 * "Time: 3/4" in the text are honoured.
 */
object AsciiTab {
    private val STANDARD = mapOf(4 to listOf(28, 33, 38, 43), 6 to listOf(40, 45, 50, 55, 59, 64), 7 to listOf(35, 40, 45, 50, 55, 59, 64))
    private val LABEL = Regex("^\\s*([A-Ga-g][#b]?)?\\s*(?=[|:\\-0-9])")
    private val CHORD = Regex("^[A-G][#b]?(m|maj|min|dim|aug|sus|add|M)?[0-9]*(sus[24]?|add[0-9]+|b5|#5|b9|#9)?(/[A-G][#b]?)?$")
    private val SECTION = Regex("^\\s*\\[?\\s*((intro|verse|chorus|pre-?chorus|bridge|solo|outro|interlude|riff|coda|theme|part)[^\\]:]{0,24})\\s*\\]?\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    private val PC = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    private fun isTabLine(line: String): Boolean {
        val m = LABEL.find(line) ?: return false
        val body = line.substring(m.range.last + 1).trimEnd()
        if (body.length < 6) return false
        val dashes = body.count { it == '-' }
        val ok = body.all { it in "-|:0123456789hpbrtsSv/\\~xX*().<>^=+ " }
        return ok && dashes >= 4 && dashes >= body.length * 0.3
    }

    private class RawNote(val quarter: Double, val line: Int, val fret: Int, val tech: List<JsonElement>, val col: Int, val system: Int)

    fun parse(text: String, title: String, artist: String = "", source: String = "text"): Song {
        val lines = text.replace("\t", "    ").lines()
        val bpm = Regex("(\\d{2,3})\\s*bpm", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toDouble()
            ?: Regex("tempo\\s*[:=]?\\s*(\\d{2,3})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toDouble() ?: 80.0
        val sig = Regex("time(?:\\s*signature)?\\s*[:=]?\\s*(\\d{1,2})\\s*/\\s*(\\d{1,2})", RegexOption.IGNORE_CASE).find(text)
        val num = sig?.groupValues?.get(1)?.toInt()?.coerceIn(1, 16) ?: 4
        val den = sig?.groupValues?.get(2)?.toInt()?.takeIf { it in listOf(2, 4, 8, 16) } ?: 4
        val capo = Regex("capo\\s*(?:on|at|:)?\\s*(\\d{1,2})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toInt()?.coerceIn(0, 12) ?: 0
        val barQuarters = num * 4.0 / den

        // systems: runs of 4–8 consecutive tab lines (longer runs split into 6s)
        val systems = ArrayList<IntRange>()
        var i = 0
        while (i < lines.size) {
            if (!isTabLine(lines[i])) { i++; continue }
            var j = i; while (j < lines.size && isTabLine(lines[j])) j++
            val n = j - i
            when {
                n in 4..8 -> systems += i until j
                n > 8 && n % 6 == 0 -> for (k in i until j step 6) systems += k until k + 6
            }
            i = j
        }
        if (systems.isEmpty()) throw SongFormatException("No guitar tab found in this text")
        val strings = systems.groupingBy { it.count() }.eachCount().maxBy { it.value }.key

        val notes = ArrayList<RawNote>()
        val chordAt = ArrayList<Pair<Double, String>>()
        val sections = ArrayList<Pair<String, Double>>()
        var labels: List<String>? = null
        var quarter = 0.0
        var sysIndex = 0
        for (sys in systems) {
            if (sys.count() != strings) continue
            val raw = sys.map { lines[it] }
            val starts = raw.map { l -> LABEL.find(l)!!.range.last + 1 }
            val lab = raw.map { l -> LABEL.find(l)!!.groupValues[1] }
            if (lab.all { it.isNotEmpty() } && labels == null) labels = lab
            val body = raw.mapIndexed { k, l -> l.substring(starts[k]).trimEnd().replace('*', '-').replace(':', '|') }
            val width = body.maxOf { it.length }
            val grid = body.map { it.padEnd(width, '-') }
            // bar lines: columns where most strings have '|' (a double "||" counts once)
            val barCols = (0 until width).filter { c -> grid.count { it[c] == '|' } * 2 > strings }
            val bars = barCols.filterIndexed { idx, c -> idx == barCols.lastIndex || barCols[idx + 1] != c + 1 }
            val cuts = ArrayList<Int>().apply { if (bars.firstOrNull() != 0) add(-1); addAll(bars); if (bars.lastOrNull() != width - 1) add(width) }
            // section heading and chord names just above the system
            val above = (sys.first - 1 downTo maxOf(0, sys.first - 3)).map { lines[it] }.filter { it.isNotBlank() }
            above.firstNotNullOfOrNull { SECTION.find(it)?.groupValues?.get(1)?.trim() }?.let { sections += it.replaceFirstChar { c -> c.uppercase() } to quarter }
            val chordLine = lines.getOrNull(sys.first - 1)?.takeIf { l -> l.isNotBlank() && l.trim().split(Regex("\\s+")).all { CHORD.matches(it) } }
            val sysStartQuarter = quarter
            var measureStart = quarter
            val colQuarter = HashMap<Int, Double>()
            for (m in 0 until cuts.size - 1) {
                val a = cuts[m] + 1; val b = cuts[m + 1]
                if (b - a < 3) continue
                for (c in a until b) colQuarter[c] = measureStart + (c - a).toDouble() / (b - a) * barQuarters
                for ((k, row) in grid.withIndex()) {
                    var c = a
                    var pending: JsonElement? = null
                    while (c < b) {
                        val ch = row[c]
                        if (ch.isDigit()) {
                            var e = c + 1
                            if (e < b && row[e].isDigit() && (row.substring(c, e + 1).toInt() <= 24)) e++
                            val fret = row.substring(c, e).toInt()
                            val tech = ArrayList<JsonElement>()
                            pending?.let { tech += it }; pending = null
                            // what follows the number: h/p (next note legato), / \ (slide into next), b (bend), ~ (vibrato)
                            when (row.getOrNull(e)) {
                                'h' -> pending = JsonPrimitive("hammer")
                                'p' -> pending = JsonPrimitive("pull")
                                '/', '\\', 's' -> pending = JsonPrimitive("slide")
                                'b' -> {
                                    val to = Regex("^b(\\d{1,2})").find(row.substring(e))?.groupValues?.get(1)?.toInt()
                                    tech += buildJsonObject { put("type", "bend"); put("semitones", ((to ?: (fret + 2)) - fret).coerceIn(1, 4).toDouble()) }
                                    if (to != null) e += to.toString().length + 1
                                }
                                '~' -> tech += JsonPrimitive("vibrato")
                            }
                            notes += RawNote(colQuarter.getValue(c), k, fret, tech, c, sysIndex)
                            c = e
                        } else c++
                    }
                }
                measureStart += barQuarters
            }
            if (chordLine != null) {
                Regex("\\S+").findAll(chordLine).forEach { mt ->
                    val col = mt.range.first - starts[0]
                    val near = notes.filter { it.system == sysIndex }.minByOrNull { abs(it.col - col) }
                    val q = if (near != null && abs(near.col - col) <= 8) near.quarter else colQuarter[col] ?: return@forEach
                    chordAt += q to mt.value
                }
            }
            quarter = if (measureStart > sysStartQuarter) measureStart else quarter
            sysIndex++
        }
        if (notes.isEmpty()) throw SongFormatException("The tab has no notes")

        val tuning = tuningFor(labels, strings, text)
        val tempo = listOf(TempoPoint(0.0, bpm))
        fun sec(q: Double) = Timing.beatToSec(tempo, q)
        val grouped = notes.groupBy { Math.round(it.quarter * 1000) }.toSortedMap()
        val qs = grouped.keys.map { it / 1000.0 }
        val events = grouped.values.mapIndexed { idx, ns ->
            val q = qs[idx]; val next = qs.getOrNull(idx + 1) ?: (q + 1.0)
            val songNotes = ns.distinctBy { it.line }.sortedBy { it.line }.map { n ->
                SongNote(string = n.line + 1, fret = n.fret, midi = tuning[strings - 1 - n.line] + capo + n.fret)
            }
            val tech = ns.flatMap { it.tech }.distinctBy { it.toString() }
            val chord = chordAt.firstOrNull { abs(it.first - q) < 1e-3 }?.second
            SongEvent(t = sec(q), dur = sec(minOf(next, q + 2.0)) - sec(q), chord = chord, notes = songNotes, tech = tech)
        }
        val end = sec(quarter.coerceAtLeast(qs.last() + 1.0))
        val secs = sections.distinctBy { it.second }.mapIndexed { k, (name, q) ->
            Section(name, sec(q), sections.getOrNull(k + 1)?.second?.let(::sec) ?: end)
        }
        return Song(
            title = title, artist = artist, source = source, duration = end, tuning = tuning, capo = capo, tempoMap = tempo,
            timeSig = listOf(TimeSig(0, num, den)), sections = secs, events = events,
        )
    }

    /** MIDI tuning low → high: each label's pitch class at the octave nearest standard tuning. */
    private fun tuningFor(labels: List<String>?, strings: Int, text: String): List<Int> {
        val std = STANDARD[strings] ?: List(strings) { 40 + 5 * it }
        if (labels == null || labels.size != strings) {
            return if (strings == 6 && Regex("drop\\s*-?d", RegexOption.IGNORE_CASE).containsMatchIn(text)) listOf(38) + std.drop(1) else std
        }
        return labels.reversed().mapIndexed { k, l ->
            var pc = PC.getValue(l[0].uppercaseChar())
            if (l.length > 1) pc += if (l[1] == '#') 1 else -1
            val s = std[k]
            (s - 6..s + 6).filter { ((it % 12) + 12) % 12 == ((pc % 12) + 12) % 12 }.minBy { abs(it - s) }
        }
    }
}
