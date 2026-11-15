package com.example.composition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionMathTest {
    private val eps = 1e-3f

    private fun pxAspect(r: NRect, c: CanvasSpec) = (r.w * c.width) / (r.h * c.height)

    @Test fun containKeepsContentAspectForAllCombinations() {
        for (m in listOf(FitMode.CONTAIN, FitMode.COVER))
            for (ca in listOf(4f / 3f, 16f / 9f, 9f / 16f, 1f, 3f / 4f))
                for (va in listOf(16f / 9f, 9f / 16f, 1f)) {
                    val r = CompositionMath.contentRectInViewport(m, ca, va)
                    assertEquals(ca, r.w * va / r.h, eps)
                }
    }

    @Test fun containFitsInsideAndCoverCoversViewport() {
        val contain = CompositionMath.contentRectInViewport(FitMode.CONTAIN, 16f / 9f, 9f / 16f)
        assertTrue(contain.w <= 1f + eps && contain.h <= 1f + eps)
        val cover = CompositionMath.contentRectInViewport(FitMode.COVER, 16f / 9f, 9f / 16f)
        assertTrue(cover.w >= 1f && cover.h >= 1f)
    }

    @Test fun stretchAndUnknownAspectFillViewport() {
        assertEquals(NRect(0f, 0f, 1f, 1f), CompositionMath.contentRectInViewport(FitMode.STRETCH, 16f / 9f, 9f / 16f))
        assertEquals(NRect(0f, 0f, 1f, 1f), CompositionMath.contentRectInViewport(FitMode.CONTAIN, 0f, 2f))
    }

    @Test fun letterboxIsNotDrawnAndCenterMapsToCenter() {
        assertNull(CompositionMath.viewportToContent(0.5f, 0.02f, FitMode.CONTAIN, 16f / 9f, 9f / 16f))
        val mid = CompositionMath.viewportToContent(0.5f, 0.5f, FitMode.CONTAIN, 16f / 9f, 9f / 16f)
        assertNotNull(mid)
        assertEquals(0.5f, mid!!.first, eps)
        assertEquals(0.5f, mid.second, eps)
    }

    @Test fun zoomMapsCornerInward() {
        val z = CompositionMath.viewportToContent(0f, 0f, FitMode.STRETCH, 1f, 1f, zoom = 2f)!!
        assertEquals(0.25f, z.first, eps)
        assertEquals(0.25f, z.second, eps)
    }

    @Test fun canvasChangeKeepsSourcePixelAspect() {
        val game = CompositionMath.remapBox(NRect(0f, 0f, 1f, 1f), CanvasSpec.LANDSCAPE, CanvasSpec.PORTRAIT)
        assertEquals(16f / 9f, pxAspect(game, CanvasSpec.PORTRAIT), eps)
        assertEquals(0.5f, game.x + game.w / 2f, eps)
        assertEquals(0.5f, game.y + game.h / 2f, eps)
        val cam = NRect(0.7f, 0.68f, 0.28f, 0.28f * 16f / 9f * 3f / 4f)
        val before = pxAspect(cam, CanvasSpec.PORTRAIT)
        for (target in listOf(CanvasSpec.SQUARE, CanvasSpec.LANDSCAPE, CanvasSpec.custom(1440, 1080))) {
            assertEquals(before, pxAspect(CompositionMath.remapBox(cam, CanvasSpec.PORTRAIT, target), target), eps)
        }
    }

    @Test fun encodeSizeKeepsRatioAndIsEven() {
        assertEquals(CanvasSpec(720, 1280), CanvasSpec.PORTRAIT.encodeSize(1280))
        assertEquals(CanvasSpec(1280, 720), CanvasSpec.LANDSCAPE.encodeSize(1280))
        assertEquals(CanvasSpec(720, 720), CanvasSpec.SQUARE.encodeSize(1280, 720))
        assertEquals(CanvasSpec(1280, 960), CanvasSpec.custom(1440, 1080).encodeSize(1280))
        assertEquals(CanvasSpec(240, 3840), CanvasSpec.custom(7, 99999))
        assertEquals(CanvasSpec(1080, 1920), CanvasSpec.custom(1081, 1921))
    }

    @Test fun quickBoxes() {
        val c = CanvasSpec.PORTRAIT
        val fit = CompositionMath.fitBoxToCanvas(4f / 3f, c, false)
        assertEquals(4f / 3f, pxAspect(fit, c), eps)
        assertEquals(1f, fit.w, eps)
        val fill = CompositionMath.fitBoxToCanvas(4f / 3f, c, true)
        assertEquals(1f, fill.h, eps)
        assertTrue(fill.w > 1f)
        assertEquals(16f / 9f, pxAspect(CompositionMath.fullWidthBox(16f / 9f, c), c), eps)
        val l = CanvasSpec.LANDSCAPE
        val fh = CompositionMath.fullHeightBox(9f / 16f, l)
        assertEquals(1f, fh.h, eps)
        assertEquals(9f / 16f, pxAspect(fh, l), eps)
    }

    @Test fun fullyCoveredDetection() {
        val small = NRect(0.2f, 0.2f, 0.2f, 0.2f)
        assertTrue(CompositionMath.isFullyCovered(small, listOf(NRect(0f, 0f, 1f, 1f))))
        assertTrue(!CompositionMath.isFullyCovered(small, listOf(NRect(0.25f, 0f, 1f, 1f))))
        assertTrue(!CompositionMath.isFullyCovered(small, emptyList()))
    }

    @Test fun layerOrderMoves() {
        val o = listOf("bg", "game", "logo", "cam")
        assertEquals(listOf("game", "logo", "cam", "bg"), CompositionMath.moveInOrder(o, "bg", 3))
        assertEquals(listOf("cam", "bg", "game", "logo"), CompositionMath.moveInOrder(o, "cam", 0))
        assertEquals("bg", CompositionMath.moveInOrder(o, "bg", 99).last())
        assertEquals(o, CompositionMath.moveInOrder(o, "zzz", 1))
    }
}
