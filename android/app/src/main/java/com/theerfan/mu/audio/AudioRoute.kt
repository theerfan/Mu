package com.theerfan.mu.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Output route key used to store latency calibration per route (ARCHITECTURE.md §4). */
enum class AudioRoute(val label: String) {
    SPEAKER("Phone speaker"), WIRED("Wired headphones"), USB("USB audio"), BLUETOOTH("Bluetooth");

    companion object {
        fun current(context: Context): AudioRoute {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val types = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
            return when {
                types.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == 26 /* BLE_HEADSET */ || it == 27 /* BLE_SPEAKER */ } -> BLUETOOTH
                types.any { it == AudioDeviceInfo.TYPE_USB_HEADSET || it == AudioDeviceInfo.TYPE_USB_DEVICE } -> USB
                types.any { it == AudioDeviceInfo.TYPE_WIRED_HEADSET || it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES } -> WIRED
                else -> SPEAKER
            }
        }
    }
}

/** Per-route offsets (ms): loopback residual and tap-along offset. Effective = tap if set, else loopback, else 0. */
class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("calibration", Context.MODE_PRIVATE)
    fun loopback(r: AudioRoute): Float? = prefs.getFloat("${r.name}_loopback", Float.NaN).takeIf { !it.isNaN() }
    fun tap(r: AudioRoute): Float? = prefs.getFloat("${r.name}_tap", Float.NaN).takeIf { !it.isNaN() }
    fun setLoopback(r: AudioRoute, ms: Float) = prefs.edit().putFloat("${r.name}_loopback", ms).apply()
    fun setTap(r: AudioRoute, ms: Float) = prefs.edit().putFloat("${r.name}_tap", ms).apply()
    fun clear(r: AudioRoute) = prefs.edit().remove("${r.name}_loopback").remove("${r.name}_tap").apply()
    fun effective(r: AudioRoute): Float = tap(r) ?: loopback(r) ?: 0f
}
