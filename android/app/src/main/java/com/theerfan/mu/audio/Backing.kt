package com.theerfan.mu.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.theerfan.mu.song.Song
import com.theerfan.mu.song.Timing

enum class BandMode(val label: String) { OFF("Off"), SYNTH("Band"), RECORDING("Recording") }
enum class GuideMode(val label: String) { OFF("Off"), AUTO("When I fall behind"), ALWAYS("Always") }

/** Synth voices understood by the native renderer (mu::Voice). */
object Voice { const val PLUCK = 0; const val BASS = 1; const val DRUMS = 2; const val KEYS = 3 }

class SynthNotes(val t: DoubleArray, val dur: DoubleArray, val midi: IntArray, val vel: FloatArray, val voice: IntArray) {
    val size get() = t.size
    companion object {
        fun of(list: List<Note>) = SynthNotes(
            list.map { it.t }.toDoubleArray(), list.map { it.dur }.toDoubleArray(), list.map { it.midi }.toIntArray(),
            list.map { it.vel }.toFloatArray(), list.map { it.voice }.toIntArray(),
        )
    }
    data class Note(val t: Double, val dur: Double, val midi: Int, val vel: Float, val voice: Int)
}

/** Builds what the backing synth plays, in practice-speed seconds. */
object BackingBuilder {
    /** The band: the song's own backing parts when it has them, otherwise a generated bass + drum groove. */
    fun band(song: Song, speed: Double): SynthNotes {
        val parts = song.backing?.parts.orEmpty().filter { it.notes.isNotEmpty() }
        val notes = if (parts.isNotEmpty()) parts.flatMap { p ->
            val voice = when (p.kind) { "bass" -> Voice.BASS; "drums" -> Voice.DRUMS; "keys" -> Voice.KEYS; else -> Voice.PLUCK }
            p.notes.filter { it.size >= 3 }.map { n -> SynthNotes.Note(n[0], n[1], n[2].toInt(), (n.getOrElse(3) { 0.8 }).toFloat() * if (voice == Voice.PLUCK) 0.6f else 1f, voice) }
        } else groove(song)
        return SynthNotes.of(notes.map { it.copy(t = it.t / speed, dur = it.dur / speed) }.sortedBy { it.t })
    }

    /** Your part on a plucked string (chords lightly strummed), used as the guide. */
    fun guide(song: Song, speed: Double): SynthNotes = SynthNotes.of(song.events.flatMap { e ->
        e.notes.sortedByDescending { it.string }.mapIndexed { i, n -> SynthNotes.Note((e.t + i * 0.012) / speed, maxOf(e.dur, 0.2) / speed, n.midi, 0.75f, Voice.PLUCK) }
    })

    /**
     * Generated accompaniment for single-track songs: kick on 1 (and 3 in 4/4), snare on the backbeats, eighth-note
     * hats, and a bass note on each bar (and on every chord change) one or two octaves under the lowest note played.
     */
    fun groove(song: Song): List<SynthNotes.Note> {
        val beats = Timing.beatTimes(song.tempoMap, song.endTime)
        val num = song.timeSig.firstOrNull()?.num?.coerceAtLeast(1) ?: 4
        val out = ArrayList<SynthNotes.Note>()
        for ((i, t) in beats.withIndex()) {
            val pos = i % num
            val next = beats.getOrNull(i + 1) ?: (t + (beats.getOrNull(1) ?: 0.5))
            if (pos == 0 || (num == 4 && pos == 2)) out += SynthNotes.Note(t, 0.3, 36, 0.9f, Voice.DRUMS)
            if ((num == 4 && (pos == 1 || pos == 3)) || (num == 2 && pos == 1)) out += SynthNotes.Note(t, 0.2, 38, 0.7f, Voice.DRUMS)
            out += SynthNotes.Note(t, 0.05, 42, if (pos == 0) 0.5f else 0.35f, Voice.DRUMS)
            out += SynthNotes.Note((t + next) / 2, 0.05, 42, 0.25f, Voice.DRUMS)
        }
        // bass anchors: chord changes and bar starts, each taking the lowest note sounding there
        val ev = song.events
        if (ev.isEmpty()) return out
        val anchors = ArrayList<Pair<Double, Int>>()
        var lastPcs: Set<Int>? = null
        for (e in ev) {
            val pcs = e.notes.map { it.midi % 12 }.toSet()
            if (e.isChord && pcs != lastPcs) anchors += e.t to e.notes.minOf { it.midi }
            if (e.isChord) lastPcs = pcs
        }
        for ((i, t) in beats.withIndex()) if (i % num == 0) {
            val within = ev.filter { it.t >= t - 0.05 && it.t < (beats.getOrNull(i + num) ?: song.endTime) }
            val low = (within.ifEmpty { listOfNotNull(ev.lastOrNull { it.t < t }) }).flatMap { e -> e.notes.map { it.midi } }.minOrNull() ?: continue
            val chordHere = anchors.lastOrNull { it.first <= t + 0.05 }
            anchors += t to (chordHere?.second ?: low)
        }
        val sorted = anchors.sortedBy { it.first }.distinctBy { (it.first * 100).toLong() }
        for ((k, a) in sorted.withIndex()) {
            var m = a.second; while (m > 43) m -= 12
            val end = sorted.getOrNull(k + 1)?.first ?: song.endTime
            out += SynthNotes.Note(a.first, (end - a.first) * 0.95, m, 0.85f, Voice.BASS)
        }
        return out
    }
}

/** Per-song backing choices (SharedPreferences "backing"). */
class BackingPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("backing", Context.MODE_PRIVATE)
    fun band(id: String) = runCatching { BandMode.valueOf(prefs.getString("$id.band", null) ?: "") }.getOrDefault(BandMode.SYNTH)
    fun guide(id: String) = runCatching { GuideMode.valueOf(prefs.getString("$id.guide", null) ?: "") }.getOrDefault(GuideMode.AUTO)
    fun volume(id: String) = prefs.getFloat("$id.vol", 0.6f)
    fun recording(id: String): Uri? = prefs.getString("$id.uri", null)?.let(Uri::parse)
    fun recordingName(id: String) = prefs.getString("$id.uriName", null)
    /** Where song time 0 (the first beat of the tab) is in the recording, seconds. */
    fun recStart(id: String) = prefs.getFloat("$id.recStart", 0f)
    fun set(id: String, band: BandMode? = null, guide: GuideMode? = null, volume: Float? = null, recStart: Float? = null) = prefs.edit().apply {
        band?.let { putString("$id.band", it.name) }; guide?.let { putString("$id.guide", it.name) }
        volume?.let { putFloat("$id.vol", it) }; recStart?.let { putFloat("$id.recStart", it) }
    }.apply()
    fun setRecording(id: String, uri: Uri, name: String) = prefs.edit().putString("$id.uri", uri.toString()).putString("$id.uriName", name).apply()
}

/** Decodes an audio file (MP3, M4A/AAC, OGG, WAV, FLAC — whatever the device codecs handle) to mono 16-bit PCM. */
object AudioDecoder {
    class Pcm(val samples: ShortArray, val rate: Int) { val seconds get() = samples.size.toDouble() / rate }

    fun decode(context: Context, uri: Uri, maxSeconds: Int = 900): Pcm {
        val ex = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r").use { fd ->
            ex.setDataSource(fd!!.fileDescriptor)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("No audio track in this file")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var out = ShortArray(rate * 60); var n = 0
            val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false
            try {
                while (!outDone) {
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val size = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                            if (size < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (oi >= 0) {
                        val buf = codec.getOutputBuffer(oi)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = buf.remaining() / ch
                        if (n + frames > out.size) out = out.copyOf(maxOf(out.size * 2, n + frames))
                        for (f in 0 until frames) { var acc = 0; for (c in 0 until ch) acc += buf.get(f * ch + c); out[n + f] = (acc / ch).toShort() }
                        n += frames
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || n > maxSeconds * rate) outDone = true
                    }
                }
            } finally { codec.stop(); codec.release(); ex.release() }
            return Pcm(out.copyOf(n), rate)
        }
    }
}
