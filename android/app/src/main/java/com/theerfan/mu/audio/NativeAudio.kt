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
    external fun stopAll()
    /** Song time (s) of the sound reaching the speaker now. */
    external fun songTime(): Double
}
