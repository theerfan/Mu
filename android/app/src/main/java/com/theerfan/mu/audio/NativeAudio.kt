package com.theerfan.mu.audio

/** JNI surface of libmu_native (android/app/src/main/cpp/jni_bridge.cpp). Never call from an audio thread. */
object NativeAudio {
    init { System.loadLibrary("mu_native") }

    /** Opens the Oboe full-duplex pair; returns the sample rate, or a negative oboe::Result. */
    external fun open(unprocessed: Boolean): Int
    external fun start(): Boolean
    external fun stop()
    external fun close()
    /** [sr, outBuf, outBurst, inputPreset, sessionId, xruns, dropped, framesWritten, tsValid, disconnected, exclOut, exclIn, outLatMs, inLatMs] */
    external fun status(out: DoubleArray)
    external fun setUserOffsetMs(ms: Float)
    external fun startTuner()
    /** out = [freqHz, confidence, levelDb, voiced]; returns false if not in tuner mode. */
    external fun tuner(out: FloatArray): Boolean
    /** Clicks at song times (song time 0 = now + leadIn); collect = record mic onsets for calibration. */
    external fun startClicks(times: DoubleArray, accents: BooleanArray, leadInSec: Double, gain: Float, collect: Boolean)
    external fun onsets(out: DoubleArray): Int
    /** [ok, offsetMs, spreadMs, used, total] */
    external fun robustOffset(detected: DoubleArray, refs: DoubleArray, maxDistSec: Double): FloatArray
    external fun loadSong(times: DoubleArray, counts: IntArray, midis: IntArray)
    external fun startPlay(clickTimes: DoubleArray, accents: BooleanArray, leadInSec: Double, gain: Float): Boolean
    /** Fills out with up to out.size/10 judgements (see play.Judgement.unpack); returns the count. */
    external fun poll(out: FloatArray): Int
    external fun finishPlay(out: FloatArray): Int
    /**
     * Renders the backing for the next play: the band from synth notes (voice 0 pluck, 1 bass, 2 drums, 3 keys) or,
     * if [pcm] is given, from a mono recording at [pcmRate] (stretched to [speed]; it reaches song time 0 at
     * [recStartSec]), plus the guide (your part). Times are practice-speed seconds; slow, call off the main thread.
     */
    external fun renderBacking(
        bandT: DoubleArray, bandDur: DoubleArray, bandMidi: IntArray, bandVel: FloatArray, bandVoice: IntArray,
        guideT: DoubleArray, guideDur: DoubleArray, guideMidi: IntArray, guideVel: FloatArray,
        lengthSec: Double, prerollSec: Double, pcm: ShortArray?, pcmRate: Int, speed: Double, recStartSec: Double,
    )
    /** Backing volumes 0..1 (band, guide); glides smoothly. */
    external fun setBackingGains(band: Float, guide: Float)
    external fun stopAll()
    /** Song time (s) of the sound reaching the speaker now. */
    external fun songTime(): Double
}
