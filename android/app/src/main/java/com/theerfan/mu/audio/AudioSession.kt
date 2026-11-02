package com.theerfan.mu.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Owns the native engine lifecycle. Screens that need audio call acquire()/release(); the streams run while at
 * least one screen holds them. Input preset: UNPROCESSED if the device advertises it, else VOICE_RECOGNITION;
 * any AGC / noise suppressor / echo canceller attached to the input session is disabled.
 */
object AudioSession {
    private const val TAG = "MuAudio"
    private var users = 0
    private val effects = mutableListOf<AudioEffect>()
    var sampleRate = 0; private set
    var lastError: String? = null; private set
    var effectsReport = ""; private set

    @Synchronized
    fun acquire(context: Context): Boolean {
        if (users > 0) { users++; return true }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sr = NativeAudio.open(unprocessed)
        if (sr <= 0) { lastError = "Audio open failed ($sr)"; return false }
        sampleRate = sr
        disableEffects()
        if (!NativeAudio.start()) { NativeAudio.close(); lastError = "Audio start failed"; return false }
        NativeAudio.setUserOffsetMs(CalibrationStore(context).effective(AudioRoute.current(context)))
        lastError = null; users = 1
        return true
    }

    @Synchronized
    fun release() {
        if (users == 0) return
        if (--users == 0) {
            NativeAudio.stopAll(); NativeAudio.close()
            effects.forEach { runCatching { it.release() } }; effects.clear()
        }
    }

    fun status(): DoubleArray = DoubleArray(14).also { NativeAudio.status(it) }

    private fun disableEffects() {
        val sid = status()[4].toInt()
        val report = StringBuilder()
        if (sid <= 0) { effectsReport = "no input session"; return }
        fun tryDisable(name: String, available: Boolean, make: () -> AudioEffect?) {
            if (!available) { report.append("$name n/a; "); return }
            val e = runCatching { make() }.getOrNull()
            if (e == null) { report.append("$name none; "); return }
            runCatching { e.enabled = false }.onFailure { Log.w(TAG, "disable $name", it) }
            effects += e; report.append("$name off; ")
        }
        tryDisable("AGC", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sid) }
        tryDisable("NS", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sid) }
        tryDisable("AEC", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sid) }
        effectsReport = report.toString().trim()
    }
}
