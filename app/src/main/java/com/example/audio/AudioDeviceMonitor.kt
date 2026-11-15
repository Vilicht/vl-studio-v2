package com.example.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioDevice(val id: Int, val label: String, val type: Int, val isInput: Boolean)

/**
 * Mendeteksi perangkat audio lewat AudioManager.registerAudioDeviceCallback: headphone kabel,
 * USB (USB-C), dan Bluetooth, termasuk saat dicolok/dicabut ketika siaran berlangsung.
 *
 * Batasan Android yang jujur:
 *  - INPUT (mic) bisa dipilih aplikasi: dipakai lewat AudioRecord.setPreferredDevice.
 *  - OUTPUT hanya ditampilkan. Aplikasi biasa tidak bisa memindahkan output audio aplikasi/game lain;
 *    itu diatur sistem. Audio game tetap tertangkap lewat AudioPlaybackCapture apa pun outputnya.
 */
class AudioDeviceMonitor(context: Context) {
    private val tag = "VilichtAudioDevices"
    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    private val _inputs = MutableStateFlow<List<AudioDevice>>(emptyList())
    val inputs: StateFlow<List<AudioDevice>> = _inputs.asStateFlow()
    private val _outputs = MutableStateFlow<List<AudioDevice>>(emptyList())
    val outputs: StateFlow<List<AudioDevice>> = _outputs.asStateFlow()

    /** Pesan singkat terakhir untuk UI, mis. "Headphone dicabut". */
    private val _event = MutableStateFlow<String?>(null)
    val event: StateFlow<String?> = _event.asStateFlow()

    private var started = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            refresh()
            added.firstOrNull { relevant(it) }?.let { _event.value = "Terhubung: ${label(it)}" }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            // Perangkat pilihan dicabut: kembali ke otomatis. Tidak ada crash, siaran tetap jalan.
            val pref = StreamAudioParams.preferredMicDeviceId
            if (pref != 0 && removed.any { it.id == pref }) {
                StreamAudioParams.preferredMicDeviceId = 0
                StreamAudioParams.micRoutingVersion++
                _event.value = "Mic pilihan dilepas, kembali ke otomatis"
            } else {
                removed.firstOrNull { relevant(it) }?.let { _event.value = "Dilepas: ${label(it)}" }
            }
            refresh()
        }
    }

    fun start() {
        if (started) return
        started = true
        try { am.registerAudioDeviceCallback(callback, main) } catch (e: Exception) { Log.w(tag, "register gagal: ${e.message}") }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        try { am.unregisterAudioDeviceCallback(callback) } catch (_: Exception) {}
    }

    fun consumeEvent() { _event.value = null }

    /** Pilih mic. id = 0 untuk otomatis. Berlaku langsung, juga saat live. */
    fun selectInput(id: Int) {
        StreamAudioParams.preferredMicDeviceId = id
        StreamAudioParams.micRoutingVersion++
        routeBluetoothIfNeeded(id)
    }

    private fun routeBluetoothIfNeeded(id: Int) {
        // Mic Bluetooth butuh jalur SCO/komunikasi. Upaya terbaik; hasilnya bergantung perangkat.
        try {
            val dev = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
            val bt = dev != null && (dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (bt) {
                    val out = am.availableCommunicationDevices.firstOrNull { it.type == dev!!.type }
                    if (out != null) am.setCommunicationDevice(out)
                } else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (bt) am.startBluetoothSco() else am.stopBluetoothSco()
            }
        } catch (e: Exception) {
            Log.w(tag, "routing Bluetooth gagal: ${e.message}")
        }
    }

    private fun refresh() {
        try {
            _inputs.value = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { relevant(it) }
                .map { AudioDevice(it.id, label(it), it.type, true) }
            _outputs.value = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { relevant(it) }
                .map { AudioDevice(it.id, label(it), it.type, false) }
        } catch (e: Exception) {
            Log.w(tag, "refresh gagal: ${e.message}")
        }
    }

    private fun relevant(d: AudioDeviceInfo): Boolean = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_ANALOG -> true
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }

    private fun label(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Mic bawaan"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker bawaan"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset kabel"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphone kabel"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (A2DP)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (mic)"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line"
            else -> "Bluetooth LE"
        }
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() }
        return if (name != null && !kind.startsWith("Mic bawaan") && !kind.startsWith("Speaker")) "$kind — $name" else kind
    }
}
