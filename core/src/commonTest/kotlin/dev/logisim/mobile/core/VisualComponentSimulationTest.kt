package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualComponentSimulationTest {
    @Test
    fun visualPixelWidthsDoNotBecomeSignalWidths() {
        val registry = ComponentCatalog.registry.withDefinitions(AnnotationLibrary.definitions())
        val video = registry.create(ExternalIoComponents.RgbVideo, "video", GridPoint(200, 200))
        val image = registry.create(AnnotationLibrary.Image, "image", GridPoint(1000, 1000),
            mapOf("width" to "4096", "height" to "4096"))
        val output = registry.create(ComponentKind.OUTPUT, "output", GridPoint(500, 500),
            mapOf("width" to "8"))
        val document = CircuitDocument(components = listOf(video, image, output))
        val state = CircuitSimulator(registry = registry).settle(document)

        assertFalse(state.oscillating)
        assertTrue(state.notices.isEmpty(), state.diagnostics.toString())
        assertEquals(128, (state.componentStates.getValue("video") as VideoState).frame.width)
        assertEquals(4096, registry.layout(image).bounds.right - image.position.x)
        assertEquals("?", state.vectorValues.getValue("video").display)
        assertEquals("?", state.vectorValues.getValue("image").display)
        assertEquals("????????", state.vectorValues.getValue("output").display)
    }
}
