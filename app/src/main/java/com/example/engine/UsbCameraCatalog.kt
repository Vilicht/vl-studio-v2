package com.example.engine

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import java.util.Locale

/**
 * Menemukan webcam USB (kelas UVC) yang sedang tercolok. Tidak membuka perangkat dan tidak butuh izin USB:
 * hanya membaca daftar dari UsbManager. Pembukaan kamera dilakukan [UvcCameraLayerSource].
 */
object UsbCameraCatalog {
    private const val TAG = "VilichtUsbCam"

    /** UVC = punya antarmuka kelas Video (0x0E), termasuk perangkat komposit yang kelas perangkatnya 0xEF. */
    fun isVideoDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }

    /** Webcam USB yang tercolok sekarang beserta ID stabilnya. Kosong bila USB host tidak tersedia. */
    fun attached(context: Context): List<Pair<UsbDevice, UsbCameraId>> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        val devices = try {
            manager.deviceList.values.filter { isVideoDevice(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Tidak bisa membaca daftar USB: ${e.message}")
            return emptyList()
        }
        val idByName = assignUsbIds(devices.map { UsbEntry(it.vendorId, it.productId, it.deviceName) })
            .associate { (entry, id) -> entry.deviceName to id }
        return devices.mapNotNull { d -> idByName[d.deviceName]?.let { d to it } }
    }

    fun idOf(context: Context, device: UsbDevice): UsbCameraId? =
        attached(context).firstOrNull { it.first.deviceName == device.deviceName }?.second

    fun find(context: Context, id: UsbCameraId): UsbDevice? =
        attached(context).firstOrNull { it.second == id }?.first

    /** Entri untuk picker kamera di UI; memakai kelas [CameraInfo] yang sama dengan kamera Camera2. */
    fun list(context: Context): List<CameraInfo> = attached(context).map { (device, id) ->
        CameraInfo(
            id = id.format(),
            label = "Kamera USB (UVC): ${nameOf(device, id)}  [${id.format()}]",
            facing = CameraCharacteristics.LENS_FACING_EXTERNAL,
            sensorOrientation = 0
        )
    }

    private fun nameOf(device: UsbDevice, id: UsbCameraId): String {
        val product = try { device.productName } catch (_: Exception) { null }
        return product?.takeIf { it.isNotBlank() }
            ?: String.format(Locale.ROOT, "%04x:%04x", id.vendorId, id.productId)
    }
}
