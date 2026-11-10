package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoFrameSpansTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()

    @Test
    fun emptyFrameAndClearedPixelsDoNotEmitRuns() {
        assertEquals(emptyList(), VideoFrame(4096, 4096).runs())
        val painted = VideoFrame(4, 3).withPixel(2, 1, red)
        assertEquals(emptyList(), painted.withPixel(2, 1, VideoFrame.BLACK).runs())
        assertEquals(emptyList(), painted.clear().runs())
    }

    @Test
    fun adjacentPixelsOfTheSameColorFormOneRunAndBlackSeparatesRuns() {
        val frame = VideoFrame(8, 2)
            .withPixel(0, 0, red)
            .withPixel(1, 0, red)
            .withPixel(3, 0, red)
            .withPixel(4, 0, green)
            .withPixel(5, 0, green)
            .withPixel(7, 0, green)

        assertEquals(listOf(
            VideoColorRun(0, 0, 2, red),
            VideoColorRun(3, 0, 1, red),
            VideoColorRun(4, 0, 2, green),
            VideoColorRun(7, 0, 1, green),
        ), frame.runs())
    }

    @Test
    fun sparseRowsAreEmittedInTopToBottomOrderRegardlessOfWriteOrder() {
        val frame = VideoFrame(6, 4096)
            .withPixel(4, 4095, red)
            .withPixel(3, 500, green)
            .withPixel(1, 2, red)
            .withPixel(2, 500, green)

        assertEquals(listOf(
            VideoColorRun(1, 2, 1, red),
            VideoColorRun(2, 500, 2, green),
            VideoColorRun(4, 4095, 1, red),
        ), frame.runs())
    }

    @Test
    fun resizingClipsRunsAndLeavesNewPixelsBlack() {
        val frame = VideoFrame(6, 4)
            .withPixel(1, 1, red)
            .withPixel(2, 1, red)
            .withPixel(3, 1, red)
            .withPixel(5, 3, green)

        val cropped = frame.resize(3, 2)
        assertEquals(listOf(VideoColorRun(1, 1, 2, red)), cropped.runs())
        assertEquals(listOf(VideoColorRun(1, 1, 2, red)), cropped.resize(8, 8).runs())
        assertEquals(listOf(
            VideoColorRun(1, 1, 3, red),
            VideoColorRun(5, 3, 1, green),
        ), frame.runs())
    }

    @Test
    fun laterWritesDoNotChangePreviouslyVisitedFrames() {
        val frame = VideoFrame(4, 3).withPixel(1, 1, red)
        val original = frame.runs()
        val changed = frame.withPixel(2, 1, red).withPixel(0, 0, green)

        assertEquals(listOf(VideoColorRun(1, 1, 1, red)), original)
        assertEquals(original, frame.runs())
        assertEquals(listOf(
            VideoColorRun(0, 0, 1, green),
            VideoColorRun(1, 1, 2, red),
        ), changed.runs())
    }

    private fun VideoFrame.runs(): List<VideoColorRun> = buildList { forEachColorRun { add(it) } }
}
