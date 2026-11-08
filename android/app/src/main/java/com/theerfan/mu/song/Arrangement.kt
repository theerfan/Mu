package com.theerfan.mu.song

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.roundToInt

/** Practice versions generated from one tab, easiest first. */
enum class Level(val key: String, val title: String, val blurb: String) {
    BASICS("basics", "Basics", "One bass note per chord change and only the notes on the beat. Learn the changes and the groove first."),
    SIMPLIFIED("simplified", "Simplified", "Chords cut down to 3-string shapes from the real voicing, and fast runs thinned out."),
    FULL("full", "Full", "Every note as written."),
}

data class Version(val level: Level, val song: Song, val profile: DifficultyProfile) {
    /** Key for best scores; the full version keeps the plain song id so earlier results still count. */
    fun progressKey(songId: String) = if (level == Level.FULL) songId else "$songId@${level.key}"
}

object Arrangements {
    /** The distinct versions of a song (a level identical to an easier one is left out), easiest first. */
    fun versions(song: Song): List<Version> {
        val out = ArrayList<Version>()
        for (level in Level.entries) {
            val s = when (level) { Level.BASICS -> basics(song); Level.SIMPLIFIED -> simplified(song); Level.FULL -> song }
            if (s.events.isEmpty()) continue
            if (out.any { sameNotes(it.song, s) }) { if (level == Level.FULL) out.removeAll { sameNotes(it.song, s) } else continue }
            out += Version(level, s, Difficulty.profile(s))
        }
        return out
    }

    private fun sameNotes(a: Song, b: Song) = a.events.size == b.events.size &&
        a.events.zip(b.events).all { (x, y) -> x.t == y.t && x.notes.map { it.string to it.fret } == y.notes.map { it.string to it.fret } }

    /**
     * Basics: for chords, only the lowest note, and only when the chord changes or a bar starts; single notes only when
     * they fall on a beat and at least a beat after the last kept one.
     */
    fun basics(song: Song): Song {
        val beats = Timing.beatTimes(song.tempoMap, song.endTime).toDoubleArray()
        val num = song.timeSig.firstOrNull()?.num?.coerceAtLeast(1) ?: 4
        val keep = ArrayList<SongEvent>()
        var lastChord: Set<Int>? = null
        var lastT = Double.NEGATIVE_INFINITY
        for (e in song.events) {
            val bi = nearestBeat(beats, e.t)
            val onBeat = bi >= 0 && abs(beats[bi] - e.t) < 0.08 * beatLen(beats, bi)
            val onBar = onBeat && bi % num == 0
            val gapOk = lastT == Double.NEGATIVE_INFINITY || e.t - lastT >= 0.95 * beatLen(beats, maxOf(bi, 0))
            if (e.isChord) {
                val pcs = e.notes.map { it.midi % 12 }.toSet()
                if ((pcs != lastChord || onBar) && gapOk) { keep += e.copy(notes = listOf(e.notes.maxBy { it.string }), tech = emptyList()); lastT = e.t }
                lastChord = pcs
            } else if (onBeat && gapOk) { keep += e; lastT = e.t; lastChord = null }
        }
        return song.copy(events = stretch(keep, song.endTime))
    }

    /** Simplified: chords keep their 3 lowest strings (triad / power shape of the real voicing); runs faster than half a beat are thinned. */
    fun simplified(song: Song): Song {
        val beats = Timing.beatTimes(song.tempoMap, song.endTime).toDoubleArray()
        val keep = ArrayList<SongEvent>()
        var lastT = Double.NEGATIVE_INFINITY
        for (e in song.events) {
            val half = 0.48 * beatLen(beats, maxOf(nearestBeat(beats, e.t), 0))
            if (e.t - lastT < half) continue
            keep += if (e.notes.size > 3) {
                val notes = e.notes.sortedByDescending { it.string }.take(3).sortedBy { it.string }
                val reduced = e.copy(notes = notes)
                // the cut-down shape may be a different chord (Cmaj7 → C, Am → A5): name what is actually played
                if (e.chord == null || notes.map { it.midi % 12 }.toSet() == e.notes.map { it.midi % 12 }.toSet()) reduced else reduced.copy(chord = Difficulty.chordName(reduced))
            } else e
            lastT = e.t
        }
        return song.copy(events = stretch(keep, song.endTime))
    }

    /** Let each kept event ring until the next one, so the highway shows sustains instead of gaps. */
    private fun stretch(evs: List<SongEvent>, end: Double) = evs.mapIndexed { i, e ->
        val next = evs.getOrNull(i + 1)?.t ?: end
        e.copy(dur = maxOf(e.dur, (next - e.t) * 0.9))
    }

    private fun nearestBeat(beats: DoubleArray, t: Double): Int {
        if (beats.isEmpty()) return -1
        val i = Timing.firstAtOrAfter(beats, t)
        return when { i <= 0 -> 0; i >= beats.size -> beats.size - 1; t - beats[i - 1] < beats[i] - t -> i - 1; else -> i }
    }
    private fun beatLen(beats: DoubleArray, i: Int) = when {
        beats.size < 2 -> 0.5
        i + 1 < beats.size -> beats[i + 1] - beats[i]
        else -> beats[i] - beats[i - 1]
    }
}

enum class ChordKind(val label: String) { POWER("Power"), OPEN("Open"), BARRE("Barre"), EXTENDED("Extended") }

/** One reason a song is hard, with a 0–1 weight for the meter and a sentence a player understands. */
data class Factor(val title: String, val amount: Float, val detail: String)

data class DifficultyProfile(
    val level: Int,                       // 1..10
    val avgNps: Double, val peakNps: Double,
    val chords: Map<ChordKind, List<String>>, val chordShare: Double,
    val changeSec: Double?, val fastestChangeSec: Double?,
    val maxNotes: Int, val minFret: Int, val maxFret: Int, val bigShifts: Int,
    val techniques: Map<String, Int>,
) {
    val label get() = when (level) { in 1..2 -> "Beginner"; in 3..4 -> "Easy"; in 5..6 -> "Intermediate"; in 7..8 -> "Hard"; else -> "Expert" }

    /** The reasons, most significant first. */
    fun factors(): List<Factor> = buildList {
        add(Factor("Speed", (peakNps / 5.0).toFloat().coerceIn(0f, 1f), when {
            peakNps < 1.0 -> "Relaxed: about one note every %.1f s.".format(1 / maxOf(avgNps, 0.1))
            else -> "Up to %.1f notes a second in the busiest part (%.1f on average).".format(peakNps, avgNps)
        }))
        if (chords.isNotEmpty()) {
            val hardest = ChordKind.entries.last { it in chords }
            val amount = when (hardest) { ChordKind.POWER -> 0.3f; ChordKind.OPEN -> 0.35f; ChordKind.EXTENDED -> 0.65f; ChordKind.BARRE -> 0.85f }
            add(Factor("Chords", amount, ChordKind.entries.filter { it in chords }.joinToString("  ·  ") { k ->
                "${k.label}: " + chords.getValue(k).take(5).joinToString(", ") { Difficulty.chordSymbol(it) } + if (chords.getValue(k).size > 5) "…" else ""
            }))
        } else add(Factor("Chords", 0f, "No chords: single notes only."))
        changeSec?.let { c ->
            add(Factor("Chord changes", (1.6 / maxOf(fastestChangeSec ?: c, 0.2) / 3).toFloat().coerceIn(0f, 1f),
                "A new chord every %.1f s on average, the quickest in %.1f s.".format(c, fastestChangeSec ?: c)))
        }
        add(Factor("Notes at once", ((maxNotes - 1) / 5f).coerceIn(0f, 1f),
            if (maxNotes <= 1) "One string at a time." else "Up to $maxNotes strings together."))
        add(Factor("Fretboard", (maxFret / 15f + bigShifts / 30f).coerceIn(0f, 1f), when {
            maxFret <= 4 -> "Stays in the first frets (0–$maxFret)."
            bigShifts == 0 -> "Frets $minFret–$maxFret in one hand position."
            else -> "Frets $minFret–$maxFret with $bigShifts big jump${if (bigShifts == 1) "" else "s"} along the neck."
        }))
        if (techniques.isNotEmpty()) add(Factor("Techniques", (techniques.values.sum() / 12f).coerceIn(0.15f, 1f),
            techniques.entries.sortedByDescending { it.value }.joinToString(", ") { "${techName(it.key)} ×${it.value}" }))
    }.sortedByDescending { it.amount }
}

private fun techName(t: String) = when (t) {
    "hammer" -> "Hammer-ons"; "pull" -> "Pull-offs"; "slide" -> "Slides"; "bend" -> "Bends"; "vibrato" -> "Vibrato"
    "palm_mute" -> "Palm mutes"; "harmonic" -> "Harmonics"; "dead" -> "Muted notes"; else -> t.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

object Difficulty {
    private val EXTENDED = Regex("(maj|m)?(7|9|11|13)|sus|add|dim|aug|°|ø|\\+")

    fun chordKind(e: SongEvent): ChordKind {
        val frets = e.notes.map { it.fret }
        val pcs = e.notes.map { it.midi % 12 }.toSet()
        val name = e.chord ?: ""
        val fretted = frets.filter { it > 0 }
        val lowest = fretted.minOrNull() ?: 0
        val barre = 0 !in frets && e.notes.size >= 4 && fretted.count { it == lowest } >= 2
        val power = pcs.size <= 2 && e.notes.size <= 3 && e.notes.map { it.midi }.sorted().let { m -> m.size >= 2 && (m[1] - m[0]) % 12 == 7 }
        return when {
            barre -> ChordKind.BARRE
            EXTENDED.containsMatchIn(name.drop(1)) || pcs.size >= 4 -> ChordKind.EXTENDED
            power -> ChordKind.POWER
            else -> ChordKind.OPEN
        }
    }

    fun profile(song: Song): DifficultyProfile {
        val ev = song.events
        val dur = maxOf(song.endTime - (ev.firstOrNull()?.t ?: 0.0), 1.0)
        val avg = ev.size / dur
        // busiest 4-second window
        var peak = 0.0; var j = 0
        for (i in ev.indices) { while (ev[i].t - ev[j].t > 4.0) j++; peak = maxOf(peak, (i - j + 1) / 4.0) }
        peak = maxOf(peak, minOf(avg, ev.size / 4.0))
        val chordEvents = ev.filter { it.isChord }
        // tabs that name their chords (chord name on the first strum) are judged by those names; partial strums in
        // between belong to the named chord. Unnamed tabs fall back to recognisable shapes (major, minor, power).
        val named = chordEvents.any { it.chord != null }
        val chords = LinkedHashMap<ChordKind, MutableList<String>>()
        for (e in chordEvents) {
            val name = e.chord ?: if (named) null else chordName(e).takeIf { '(' !in it }
            if (name == null) continue
            val list = chords.getOrPut(chordKind(e)) { mutableListOf() }
            if (name !in list) list += name
        }
        // chord changes: a new name (named tabs) or a new pitch-class set (unnamed)
        val changes = ArrayList<Double>(); var prev: Any? = null
        for (e in chordEvents) {
            val key: Any = if (named) e.chord ?: continue else e.notes.map { it.midi % 12 }.toSet()
            if (key != prev) { changes += e.t; prev = key }
        }
        val gaps = changes.zipWithNext { a, b -> b - a }.filter { it > 0.05 }
        val frets = ev.flatMap { e -> e.notes.map { it.fret } }
        val positions = ev.mapNotNull { e -> e.notes.map { it.fret }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average() }
        val shifts = positions.zipWithNext().count { (a, b) -> abs(a - b) >= 5 }
        val tech = HashMap<String, Int>()
        for (e in ev) {
            val names = e.techNames + e.notes.flatMap { n -> n.tech.mapNotNull { t -> (t as? JsonPrimitive)?.contentOrNull ?: (t as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull } }
            for (t in names.distinct()) if (!t.startsWith("strum") && !t.startsWith("arpeggio")) tech[t] = (tech[t] ?: 0) + 1
        }
        val maxNotes = ev.maxOfOrNull { it.notes.size } ?: 0
        val changeAvg = if (gaps.isEmpty()) null else gaps.average()
        val fastest = gaps.minOrNull()
        // level: speed carries most of it, then chord vocabulary, change rate, polyphony, reach and techniques
        var score = 1.0 + minOf(peak, 6.0) * 0.9
        score += when { ChordKind.BARRE in chords -> 2.0; ChordKind.EXTENDED in chords -> 1.2; chords.isNotEmpty() -> 0.6; else -> 0.0 }
        fastest?.let { score += when { it < 0.8 -> 1.5; it < 1.6 -> 0.8; else -> 0.2 } }
        score += (maxNotes - 2).coerceIn(0, 4) * 0.3
        score += when { (frets.maxOrNull() ?: 0) > 12 -> 1.5; (frets.maxOrNull() ?: 0) > 5 -> 0.7; else -> 0.0 } + minOf(shifts, 10) * 0.08
        score += minOf(tech.values.sum(), 10) * 0.12
        return DifficultyProfile(
            level = score.roundToInt().coerceIn(1, 10), avgNps = avg, peakNps = peak, chords = chords, chordShare = chordEvents.size / maxOf(ev.size, 1).toDouble(),
            changeSec = changeAvg, fastestChangeSec = fastest, maxNotes = maxNotes, minFret = frets.minOrNull() ?: 0, maxFret = frets.maxOrNull() ?: 0,
            bigShifts = shifts, techniques = tech,
        )
    }

    /** A plain name for unnamed chords: root (lowest note) + major/minor/5 when recognisable, else the notes. */
    fun chordName(e: SongEvent): String {
        val root = e.notes.minOf { it.midi } % 12
        val iv = e.notes.map { (it.midi - root + 120) % 12 }.toSet()
        return PITCH[root] + when {
            4 in iv && 7 in iv -> ""
            3 in iv && 7 in iv -> "m"
            iv == setOf(0, 7) -> "5"
            else -> "(" + e.notes.sortedBy { it.midi }.joinToString("") { PITCH[it.midi % 12] } + ")"
        }
    }

    /**
     * Lead-sheet spelling. Major is just the root, minor is "m", major 7ths use Δ, and accidentals are ♯ and ♭
     * rather than words like Maj or min.
     */
    fun chordSymbol(raw: String): String {
        val parts = raw.trim().split("/")
        if (parts.isEmpty() || parts[0].isEmpty()) return raw
        val head = symbolOne(parts[0])
        if (parts.size == 1) return head
        val bass = parts[1]
        val bassSym = if (bass.isNotEmpty() && bass[0] in "ABCDEFGabcdefg") symbolOne(bass) else bass
        return "$head/$bassSym"
    }

    private val PITCH = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    private val LETTER = intArrayOf(9, 11, 0, 2, 4, 5, 7) // A B C D E F G

    private fun symbolOne(token: String): String {
        val s = token.trim()
        if (s.isEmpty() || s[0].uppercaseChar() !in 'A'..'G') return s.replace("#", "♯").replace("b", "♭")
        val letter = LETTER[s[0].uppercaseChar() - 'A']
        var i = 1
        var acc = 0
        if (i < s.length && (s[i] == '#' || s[i] == '♯')) { acc = 1; i++ }
        else if (i < s.length && (s[i] == 'b' || s[i] == '♭') && (i + 1 >= s.length || !s[i + 1].isDigit())) { acc = -1; i++ }
        var q = s.substring(i)
        // Longer words first, so "major7" becomes Δ7 and a plain "major" can still collapse to the bare root.
        q = q.replace("major", "Δ", ignoreCase = true)
        q = q.replace("minor", "m", ignoreCase = true)
        q = q.replace("maj", "Δ", ignoreCase = true)
        q = q.replace("min", "m", ignoreCase = true)
        q = q.replace("dim", "°", ignoreCase = true)
        q = q.replace("aug", "+", ignoreCase = true)
        q = Regex("^M(?=\\d)").replace(q, "Δ")
        if (q == "Δ" || q == "M" || q.equals("mi", ignoreCase = true)) q = if (q.equals("mi", ignoreCase = true)) "m" else ""
        q = q.replace("#", "♯")
        q = Regex("b(?=\\d)").replace(q, "♭")
        return PITCH[(letter + acc).mod(12)] + q
    }
}
