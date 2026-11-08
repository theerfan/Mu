package com.theerfan.mu.online

import android.content.Context
import com.theerfan.mu.song.AsciiTab
import com.theerfan.mu.song.BackingInfo
import com.theerfan.mu.song.BackingPart
import com.theerfan.mu.song.Section
import com.theerfan.mu.song.Song
import com.theerfan.mu.song.SongEvent
import com.theerfan.mu.song.SongFormatException
import com.theerfan.mu.song.SongNote
import com.theerfan.mu.song.TempoPoint
import com.theerfan.mu.song.TimeSig
import com.theerfan.mu.song.Timing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/** Minimal blocking HTTP GET (call off the main thread). Handles servers that gzip without being asked. */
object Http {
    fun get(url: String, timeoutMs: Int = 15_000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "Mu-practice-app")
        c.setRequestProperty("Accept-Encoding", "gzip")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} from ${URL(url).host}")
            val bytes = c.inputStream.use { it.readBytes() }
            return if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()).use { it.readBytes() } else bytes
        } finally { c.disconnect() }
    }
    fun text(url: String) = String(get(url), Charsets.UTF_8)
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }
private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.dbl(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.arr(k: String) = this[k] as? JsonArray
private fun JsonObject.obj(k: String) = this[k] as? JsonObject

/** A search hit from any source. */
sealed class OnlineSong(val title: String, val artist: String) {
    class Songsterr(val songId: Int, title: String, artist: String, val tracks: List<SongsterrApi.Track>) : OnlineSong(title, artist)
    class Classtab(val file: String, title: String, composer: String, val easy: Boolean) : OnlineSong(title, composer)
}

// ------------------------------------------------------------------------------------------------ Songsterr

/**
 * Songsterr's public web API (unofficial, used by their own site; may change or block clients at any time).
 * search → /api/songs, revision → /api/meta/{id}, track data → CDN JSON per track.
 */
object SongsterrApi {
    data class Track(
        val index: Int, val name: String, val instrument: String, val instrumentId: Int, val tuning: List<Int>,
        val difficulty: Int?, val views: Int, val isVocal: Boolean,
    ) {
        val isDrums get() = instrumentId == 1024
        val isBass get() = instrumentId in 32..39
        val isGuitar get() = instrumentId in 24..31 && !isVocal
        /** Short role from the track name, e.g. "Rhythm Guitar" out of "Noel Gallagher | Takamine | Rhythm Guitar". */
        val role get() = name.split('|').map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: instrument
    }

    private const val CDN = "https://dqsljvtekg760.cloudfront.net"

    fun search(query: String): List<OnlineSong.Songsterr> {
        val body = Http.text("https://www.songsterr.com/api/songs?pattern=${URLEncoder.encode(query, "UTF-8")}&size=30")
        return json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val tracks = tracksOf(o)
            if (tracks.none { it.isGuitar }) null
            else OnlineSong.Songsterr(o.int("songId") ?: return@mapNotNull null, o.str("title") ?: "?", o.str("artist") ?: "", tracks)
        }
    }

    private fun tracksOf(o: JsonObject) = o.arr("tracks").orEmpty().mapIndexedNotNull { i, t ->
        val to = t as? JsonObject ?: return@mapIndexedNotNull null
        Track(i, to.str("name").orEmpty(), to.str("instrument").orEmpty(), to.int("instrumentId") ?: -1,
            to.arr("tuning")?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty(), to.int("difficulty"), to.int("views") ?: 0,
            to.bool("isVocalTrack") || to.str("hash")?.startsWith("vocals") == true)
    }

    /** Downloads the chosen track as the playable part; bass, drums, vocal melody and other guitars become the band. */
    fun download(song: OnlineSong.Songsterr, trackIndex: Int): Song {
        val meta = json.parseToJsonElement(Http.text("https://www.songsterr.com/api/meta/${song.songId}")).jsonObject
        val rev = meta.int("revisionId") ?: throw IOException("No revision for this song")
        val image = meta.str("image") ?: throw IOException("No track data for this song")
        val tracks = tracksOf(meta)
        fun fetch(i: Int) = json.parseToJsonElement(Http.text("$CDN/${song.songId}/$rev/$image/$i.json")).jsonObject
        val main = fetch(trackIndex)
        // backing: the drum kit and bass Songsterr marks as the main ones, the vocal melody and two other guitars
        fun main(key: String, ok: (Track) -> Boolean) = meta.int(key)?.let { i -> tracks.firstOrNull { it.index == i && ok(it) } }
            ?: tracks.filter(ok).sortedWith(compareByDescending<Track> { "drum" in it.name.lowercase() || "bass" in it.name.lowercase() }.thenByDescending { it.views }).firstOrNull()
        val picks = buildList {
            main("popularTrackDrum") { it.isDrums }?.let { add(it) }
            main("popularTrackBass") { it.isBass }?.let { add(it) }
            tracks.filter { it.isVocal }.maxByOrNull { it.views }?.let { add(it) }
            addAll(tracks.filter { it.isGuitar && it.index != trackIndex }.sortedByDescending { it.views }.take(2))
        }
        val others = picks.mapNotNull { t -> runCatching { t to fetch(t.index) }.getOrNull() }
        val chosen = tracks.firstOrNull { it.index == trackIndex }
        return SongsterrConverter.convert(main, others, title = song.title, artist = song.artist, trackName = chosen?.role ?: "Guitar")
    }
}

/** Songsterr track JSON (measures → voices → beats → notes, durations as fractions of a whole note) → Mu song. */
object SongsterrConverter {
    private class Note(val q: Double, val durQ: Double, val string: Int, val fret: Int, val tech: List<JsonElement>, val chord: String?)

    private class Parsed(val notes: List<Note>, val tempo: List<TempoPoint>, val sigs: List<TimeSig>, val sections: List<Pair<String, Double>>, val endQ: Double)

    private fun parse(track: JsonObject, drums: Boolean): Parsed {
        val measures = track.arr("measures").orEmpty().map { it.jsonObject }
        var num = 4; var den = 4
        val starts = ArrayList<Double>(); val sigs = ArrayList<TimeSig>(); val sections = ArrayList<Pair<String, Double>>()
        var q = 0.0
        for ((mi, m) in measures.withIndex()) {
            m.arr("signature")?.let { s -> num = s[0].jsonPrimitive.intOrNull ?: num; den = s[1].jsonPrimitive.intOrNull ?: den; sigs += TimeSig(mi, num, den) }
            starts += q
            m.obj("marker")?.str("text")?.takeIf { it.isNotBlank() }?.let { sections += it.trim() to q }
            q += num * 4.0 / den
        }
        val endQ = q
        val tempo = track.obj("automations")?.arr("tempo").orEmpty().mapNotNull { a ->
            val o = a.jsonObject; val mi = o.int("measure") ?: return@mapNotNull null
            val pos = o.dbl("position") ?: 0.0; val bpm = o.dbl("bpm") ?: return@mapNotNull null
            val len = (starts.getOrNull(mi + 1) ?: endQ) - (starts.getOrNull(mi) ?: 0.0)
            TempoPoint((starts.getOrNull(mi) ?: 0.0) + (if (pos in 0.0..1.0) pos * len else 0.0), bpm)
        }.sortedBy { it.beat }.ifEmpty { listOf(TempoPoint(0.0, 120.0)) }
        val notes = ArrayList<Note>()
        val legatoNext = HashMap<Int, String>()   // string → technique for the next note on it (hammer/pull/slide)
        val lastFret = HashMap<Int, Int>()
        for ((mi, m) in measures.withIndex()) for (v in m.arr("voices").orEmpty()) {
            var bq = starts[mi]
            for (b in v.jsonObject.arr("beats").orEmpty()) {
                val bo = b.jsonObject
                val d = bo.arr("duration")
                val durQ = if (d != null && d.size == 2) 4.0 * (d[0].jsonPrimitive.doubleOrNull ?: 1.0) / (d[1].jsonPrimitive.doubleOrNull ?: 4.0) else 1.0
                if (!bo.bool("rest")) {
                    val chord = bo.obj("chord")?.str("text")?.takeIf { it.isNotBlank() }
                    val beatTech = buildList<JsonElement> {
                        if (bo.bool("palmMute")) add(JsonPrimitive("palm_mute"))
                        when (bo.str("pickStroke") ?: bo.obj("brushStroke")?.str("direction")) { "down" -> add(JsonPrimitive("strum_down")); "up" -> add(JsonPrimitive("strum_up")) }
                    }
                    for (n in bo.arr("notes").orEmpty()) {
                        val no = n.jsonObject
                        if (no.bool("rest") || no.bool("tie") || (no.bool("dead") && !drums)) continue
                        val s = (no.dbl("string") ?: continue).toInt(); val fret = no.int("fret") ?: continue
                        val tech = ArrayList<JsonElement>(beatTech)
                        legatoNext.remove(s)?.let { t -> tech += JsonPrimitive(if (t == "hp") (if (fret < (lastFret[s] ?: fret)) "pull" else "hammer") else t) }
                        if (no.bool("hp")) legatoNext[s] = "hp"
                        if (no.str("slide") != null) legatoNext[s] = "slide"
                        no.obj("bend")?.let { bd -> tech += buildJsonObject { put("type", "bend"); put("semitones", (bd.dbl("tone") ?: 100.0) / 100.0) } }
                        if (no.str("leftHandVibrato") != null || no.bool("vibrato")) tech += JsonPrimitive("vibrato")
                        if (no.bool("harmonic") || no.str("harmonic") != null) tech += JsonPrimitive("harmonic")
                        lastFret[s] = fret
                        notes += Note(bq, durQ, s, fret, tech, chord)
                    }
                }
                bq += durQ
            }
        }
        return Parsed(notes.sortedBy { it.q }, tempo, sigs.ifEmpty { listOf(TimeSig(0, 4, 4)) }, sections, endQ)
    }

    fun convert(main: JsonObject, others: List<Pair<SongsterrApi.Track, JsonObject>>, title: String, artist: String, trackName: String): Song {
        val p = parse(main, drums = false)
        if (p.notes.isEmpty()) throw SongFormatException("This track has no notes")
        val tuningHighToLow = main.arr("tuning")?.mapNotNull { it.jsonPrimitive.intOrNull }?.takeIf { it.isNotEmpty() } ?: listOf(64, 59, 55, 50, 45, 40)
        val strings = tuningHighToLow.size
        val capo = main.int("capo") ?: 0
        fun sec(q: Double) = Timing.beatToSec(p.tempo, q)
        val byTime = p.notes.groupBy { Math.round(it.q * 960) }.toSortedMap()
        val qs = byTime.keys.map { it / 960.0 }
        val events = byTime.values.mapIndexed { i, ns ->
            val q = qs[i]
            val notes = ns.distinctBy { it.string }.filter { it.string in 0 until strings }.sortedBy { it.string }.map { n ->
                SongNote(string = n.string + 1, fret = n.fret, midi = tuningHighToLow[n.string] + capo + n.fret,
                    tech = if (ns.size > 1) n.tech.filterNot { it is JsonPrimitive && it.content.startsWith("strum") } else emptyList())
            }
            val evTech = if (ns.size > 1) ns.flatMap { it.tech }.filter { it is JsonPrimitive && it.content.startsWith("strum") }.distinct() else ns.first().tech
            val dq = ns.maxOf { it.durQ }
            SongEvent(t = sec(q), dur = sec(q + dq) - sec(q), chord = ns.firstNotNullOfOrNull { it.chord }, notes = notes, tech = evTech)
        }.filter { it.notes.isNotEmpty() }
        val end = sec(p.endQ)
        val parts = others.mapNotNull { (t, js) ->
            val bp = parse(js, drums = t.isDrums)
            if (bp.notes.isEmpty()) return@mapNotNull null
            val tun = js.arr("tuning")?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()
            val cap = js.int("capo") ?: 0
            val kind = when { t.isDrums -> "drums"; t.isBass -> "bass"; t.isVocal -> "keys"; else -> "guitar" }
            val vel = if (t.isVocal) 0.45 else 0.8
            BackingPart(t.role, kind, bp.notes.mapNotNull { n ->
                val midi = if (t.isDrums) n.fret else tun.getOrNull(n.string)?.plus(cap + n.fret) ?: return@mapNotNull null
                listOf(r3(sec(n.q)), r3(sec(n.q + n.durQ) - sec(n.q)), midi.toDouble(), vel)
            })
        }
        val sections = p.sections.mapIndexed { i, (name, q) -> Section(name, sec(q), p.sections.getOrNull(i + 1)?.second?.let(::sec) ?: end) }
        return Song(
            title = title, artist = artist, source = "songsterr", track = trackName, duration = end, tuning = tuningHighToLow.reversed(), capo = capo,
            tempoMap = p.tempo, timeSig = p.sigs, sections = sections, events = events, backing = if (parts.isEmpty()) null else BackingInfo(parts = parts),
        )
    }

    private fun r3(x: Double) = Math.round(x * 1000) / 1000.0
}

// ------------------------------------------------------------------------------------------------ Classtab

/** classtab.org: ~4000 free classical guitar tabs as plain text, listed on one index page (cached for a week). */
object ClasstabApi {
    private const val BASE = "https://www.classtab.org/"

    fun index(context: Context): List<OnlineSong.Classtab> {
        val f = File(context.cacheDir, "classtab_index.html")
        val html = if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) f.readText()
        else Http.text(BASE + "index_old.htm").also { f.writeText(it) }
        return parseIndex(html)
    }

    /** Composer headings are `<b>Name &#160;(...)</b>` lines; entries are `<a href="x.txt">Title</a>` lines under them. */
    fun parseIndex(html: String): List<OnlineSong.Classtab> {
        val out = ArrayList<OnlineSong.Classtab>()
        var composer = ""
        val head = Regex("^<b>([^<]+)</b>")
        val entry = Regex("<a href=\"([^\"/]+\\.txt)\">(.+?)</a>")
        for (line in html.lineSequence()) {
            if (!line.contains(".txt\"")) {
                head.find(line.trim())?.let { composer = decode(it.groupValues[1]).substringBefore('(').trim() }
                continue
            }
            val m = entry.find(line) ?: continue
            if (!line.trimStart().startsWith("<a href")) continue    // "see ..." cross references
            out += OnlineSong.Classtab(m.groupValues[1], decode(m.groupValues[2].replace(Regex("<[^>]+>"), "")), composer, line.contains("<b>easy</b>"))
        }
        return out
    }

    fun search(context: Context, query: String): List<OnlineSong.Classtab> {
        val words = norm(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return index(context).filter { e -> val hay = norm(e.title + " " + e.artist + " " + e.file); words.all { it in hay } }.take(60)
    }

    fun download(e: OnlineSong.Classtab): Song = AsciiTab.parse(Http.text(BASE + e.file), e.title, e.artist, source = "classtab")

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), " ")

    /** HTML entities used on the page: numeric ones and accented letters (&eacute; …). */
    fun decode(s: String): String {
        val marks = mapOf("acute" to "́", "grave" to "̀", "uml" to "̈", "tilde" to "̃", "circ" to "̂", "ring" to "̊", "cedil" to "̧")
        val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "nbsp" to " ", "apos" to "'", "szlig" to "ß", "oslash" to "ø", "Oslash" to "Ø", "aelig" to "æ")
        val r = Regex("&(#\\d+|[A-Za-z]+);").replace(s) { m ->
            val k = m.groupValues[1]
            when {
                k.startsWith("#") -> k.drop(1).toIntOrNull()?.let { if (it == 160) " " else String(Character.toChars(it)) } ?: m.value
                named.containsKey(k) -> named.getValue(k)
                else -> marks.entries.firstOrNull { k.length > it.key.length && k.endsWith(it.key) }?.let { k.dropLast(it.key.length) + it.value } ?: m.value
            }
        }
        return Normalizer.normalize(r, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim()
    }
}

/** Recognises shared links: a Songsterr tab page or a Classtab .txt file. */
object SharedLinks {
    private val songsterr = Regex("songsterr\\.com/\\S*?-s(\\d+)(?:t(\\d+))?")
    private val classtab = Regex("classtab\\.org/([^\\s/?#]+\\.txt)")

    sealed class Link { data class Songsterr(val songId: Int, val track: Int?) : Link(); data class Classtab(val file: String) : Link() }

    fun find(text: String): Link? {
        songsterr.find(text)?.let { return Link.Songsterr(it.groupValues[1].toInt(), it.groupValues[2].toIntOrNull()) }
        classtab.find(text)?.let { return Link.Classtab(it.groupValues[1]) }
        return null
    }

    /** Downloads what a shared link points at (track: the given one, else the most viewed guitar track). */
    fun download(link: Link): Song = when (link) {
        is Link.Classtab -> AsciiTab.parse(Http.text("https://www.classtab.org/${link.file}"), link.file.removeSuffix(".txt").replace('_', ' '), source = "classtab")
        is Link.Songsterr -> {
            val meta = json.parseToJsonElement(Http.text("https://www.songsterr.com/api/meta/${link.songId}")).jsonObject
            val song = OnlineSong.Songsterr(link.songId, meta.str("title") ?: "Song", meta.str("artist") ?: "", emptyList())
            val tracks = meta.arr("tracks").orEmpty()
            val track = link.track ?: meta.int("popularTrackGuitar") ?: tracks.indexOfFirst { (it.jsonObject.int("instrumentId") ?: -1) in 24..31 }.coerceAtLeast(0)
            SongsterrApi.download(song, track)
        }
    }
}
