package com.rel.adbhelper.features.mirror

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TouchMappingTest {
    private val portrait = VideoSize(1080, 1920)
    private val landscape = VideoSize(1920, 1080)

    @Test
    fun `view with the video's own shape maps linearly`() {
        // Half size view: 540x960.
        assertEquals(VideoPoint(0, 0), mapToVideo(0f, 0f, 540, 960, portrait))
        assertEquals(VideoPoint(540, 960), mapToVideo(270f, 480f, 540, 960, portrait))
        assertEquals(VideoPoint(1078, 1918), mapToVideo(539f, 959f, 540, 960, portrait))
    }

    @Test
    fun `a wide view letterboxes a portrait video with bars left and right`() {
        // 960x480 view: scale 0.25, video shown 270 wide, bars of 345 on each side.
        assertEquals(VideoPoint(540, 960), mapToVideo(480f, 240f, 960, 480, portrait))
        assertEquals(VideoPoint(0, 0), mapToVideo(345f, 0f, 960, 480, portrait))
        assertNull(mapToVideo(100f, 240f, 960, 480, portrait))
        assertNull(mapToVideo(900f, 240f, 960, 480, portrait))
    }

    @Test
    fun `a tall view letterboxes a landscape video with bars above and below`() {
        // 600x1000 view: scale 600/1920 = 0.3125, shown 337.5 high, top bar 331.25.
        assertNull(mapToVideo(300f, 100f, 600, 1000, landscape))
        assertNull(mapToVideo(300f, 900f, 600, 1000, landscape))
        val centre = mapToVideo(300f, 500f, 600, 1000, landscape)!!
        assertEquals(960, centre.x)
        assertEquals(540, centre.y)
    }

    @Test
    fun `the same touch maps differently once the video size is swapped by a rotation`() {
        val before = mapToVideo(100f, 50f, 540, 960, portrait)!!
        val after = mapToVideo(100f, 50f, 960, 540, landscape)!!
        assertEquals(VideoPoint(200, 100), before)
        assertEquals(VideoPoint(200, 100), after)
        // Same view as before, but video turned landscape: now letterboxed, so the point moves.
        val stale = mapToVideo(100f, 50f, 540, 960, landscape)
        assertNull(stale)
    }

    @Test
    fun `clamping moves an off-video touch to the nearest edge`() {
        assertEquals(VideoPoint(0, 960), mapToVideo(-50f, 480f, 540, 960, portrait, clamp = true))
        assertEquals(VideoPoint(1079, 1919), mapToVideo(900f, 2000f, 540, 960, portrait, clamp = true))
        // In the left bar of a letterboxed view: x clamps to the video's left edge.
        assertEquals(VideoPoint(0, 960), mapToVideo(100f, 240f, 960, 480, portrait, clamp = true))
    }

    @Test
    fun `degenerate sizes give null`() {
        assertNull(mapToVideo(1f, 1f, 0, 100, portrait))
        assertNull(mapToVideo(1f, 1f, 100, 100, VideoSize(0, 0)))
    }
}
