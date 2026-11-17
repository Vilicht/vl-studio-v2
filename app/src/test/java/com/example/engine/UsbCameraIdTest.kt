package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbCameraIdTest {

    // ---------------------------------------------------------------- format & parse

    @Test fun formatUsesLowerCaseHexPaddedToFourDigits() {
        assertEquals("uvc:046d:0825", UsbCameraId(0x046d, 0x0825).format())
        assertEquals("uvc:0001:0002", UsbCameraId(0x1, 0x2).format())
    }

    @Test fun secondIdenticalDeviceGetsOrdinalSuffix() {
        assertEquals("uvc:046d:0825#2", UsbCameraId(0x046d, 0x0825, 1).format())
        assertEquals("uvc:046d:0825#3", UsbCameraId(0x046d, 0x0825, 2).format())
    }

    @Test fun parseRoundTripsEveryFormat() {
        listOf(
            UsbCameraId(0x046d, 0x0825),
            UsbCameraId(0x046d, 0x0825, 1),
            UsbCameraId(0xFFFF, 0x0000, 4)
        ).forEach { assertEquals(it, UsbCameraId.parse(it.format())) }
    }

    @Test fun parseAcceptsUpperCaseHex() {
        assertEquals(UsbCameraId(0xABCD, 0xEF01), UsbCameraId.parse("uvc:ABCD:EF01"))
    }

    @Test fun parseRejectsAnythingThatIsNotAUsbCameraId() {
        assertNull(UsbCameraId.parse(null))
        assertNull(UsbCameraId.parse(""))
        assertNull(UsbCameraId.parse("0"))            // ID Camera2 biasa
        assertNull(UsbCameraId.parse("uvc:"))
        assertNull(UsbCameraId.parse("uvc:046d"))
        assertNull(UsbCameraId.parse("uvc:zz:01"))
        assertNull(UsbCameraId.parse("uvc:046d:0825#0"))
        assertNull(UsbCameraId.parse("uvc:046d:0825#abc"))
        assertNull(UsbCameraId.parse("uvc:10000:0001"))  // di luar 16 bit
    }

    @Test fun isUsbOnlyMatchesTheUvcPrefix() {
        assertTrue(UsbCameraId.isUsb("uvc:046d:0825"))
        assertFalse(UsbCameraId.isUsb("1"))
        assertFalse(UsbCameraId.isUsb(null))
    }

    // ---------------------------------------------------------------- penomoran perangkat kembar

    @Test fun identicalDevicesAreNumberedByDeviceNameRegardlessOfInputOrder() {
        val a = UsbEntry(0x046d, 0x0825, "/dev/bus/usb/001/009")
        val b = UsbEntry(0x046d, 0x0825, "/dev/bus/usb/001/004")
        val other = UsbEntry(0x1bcf, 0x2c99, "/dev/bus/usb/001/007")

        val first = assignUsbIds(listOf(a, b, other)).associate { it.first to it.second.format() }
        val second = assignUsbIds(listOf(b, other, a)).associate { it.first to it.second.format() }

        assertEquals("uvc:046d:0825", first[b])        // nama perangkat lebih kecil = indeks 0
        assertEquals("uvc:046d:0825#2", first[a])
        assertEquals("uvc:1bcf:2c99", first[other])    // tunggal: tanpa sufiks
        assertEquals(first, second)
    }

    // ---------------------------------------------------------------- pemilih mode UVC

    private val mjpeg = UvcMode.FRAME_MJPEG
    private val raw = UvcMode.FRAME_UNCOMPRESSED

    private val typicalWebcam = listOf(
        UvcMode(mjpeg, 1920, 1080, 30, listOf(30, 15)),
        UvcMode(mjpeg, 1280, 720, 30, listOf(60, 30, 15)),
        UvcMode(mjpeg, 640, 480, 30, listOf(30)),
        UvcMode(raw, 1920, 1080, 5, listOf(5)),
        UvcMode(raw, 1280, 720, 10, listOf(10)),
        UvcMode(raw, 640, 480, 30, listOf(30))
    )

    @Test fun picksLargestMjpegModeWithinPixelBudget() {
        val s = UvcModeSelector.select(typicalWebcam, 1280 * 720, 30)!!
        assertEquals(1280, s.mode.width)
        assertTrue(s.mode.isMjpeg)
        assertEquals(30, s.fps)
    }

    @Test fun fpsIsAlwaysOneOfTheModesAdvertisedValues() {
        // UVCCamera.setPreviewSize menolak fps yang tidak diiklankan: 45 harus jatuh ke 30, bukan dikirim apa adanya
        val s = UvcModeSelector.select(typicalWebcam, 1280 * 720, 45)!!
        assertTrue(s.fps in s.mode.supportedFps)
        assertEquals(30, s.fps)
        assertEquals(60, UvcModeSelector.select(typicalWebcam, 1280 * 720, 60)!!.fps)
    }

    @Test fun largerBudgetSelectsFullHd() {
        val s = UvcModeSelector.select(typicalWebcam, 1920 * 1080, 30)!!
        assertEquals(1920, s.mode.width)
        assertTrue(s.mode.isMjpeg)
    }

    @Test fun budgetSmallerThanEveryModeFallsBackToTheSmallestMode() {
        assertEquals(640 * 480, UvcModeSelector.select(typicalWebcam, 100 * 100, 30)!!.mode.pixels)
    }

    @Test fun prefersAModeThatCanDeliverTheTargetFpsOverAHigherResolution() {
        val rawOnly = listOf(
            UvcMode(raw, 1920, 1080, 5, listOf(5)),
            UvcMode(raw, 1280, 720, 10, listOf(10)),
            UvcMode(raw, 640, 480, 30, listOf(30))
        )
        val s = UvcModeSelector.select(rawOnly, 1920 * 1080, 30)!!
        assertEquals(640, s.mode.width)   // 1080p5 / 720p10 tidak layak untuk siaran 30 fps
        assertEquals(30, s.fps)
    }

    @Test fun whenNoModeReachesTargetFpsTakesTheFastestOne() {
        val slow = listOf(UvcMode(raw, 1280, 720, 10, listOf(10)), UvcMode(raw, 640, 480, 15, listOf(15)))
        val s = UvcModeSelector.select(slow, 1280 * 720, 30)!!
        assertEquals(640, s.mode.width)
        assertEquals(15, s.fps)
    }

    @Test fun mjpegBeatsUncompressedWhenEverythingElseIsEqual() {
        val s = UvcModeSelector.select(
            listOf(UvcMode(raw, 1280, 720, 30, listOf(30)), UvcMode(mjpeg, 1280, 720, 30, listOf(30))),
            1280 * 720, 30
        )!!
        assertTrue(s.mode.isMjpeg)
    }

    @Test fun emptyOrDegenerateModeListsYieldNull() {
        assertNull(UvcModeSelector.select(emptyList(), 1280 * 720, 30))
        assertNull(UvcModeSelector.select(listOf(UvcMode(mjpeg, 0, 0, 30)), 1280 * 720, 30))
    }

    @Test fun fpsForClampsBelowAndToleratesMissingFpsList() {
        assertEquals(15, UvcModeSelector.fpsFor(UvcMode(mjpeg, 640, 480, 15, listOf(15, 30)), 5))
        assertEquals(0, UvcModeSelector.fpsFor(UvcMode(mjpeg, 640, 480, 0), 30))
    }
}
