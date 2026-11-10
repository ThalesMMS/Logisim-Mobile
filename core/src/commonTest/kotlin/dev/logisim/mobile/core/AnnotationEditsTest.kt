package dev.logisim.mobile.core

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnnotationEditsTest {
    private val pixel = Base64.Default.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==")

    @Test fun embeddedImagesAreIndependentCopiesAndNeverExternalPaths() {
        val image = EmbeddedImage.fromBytes(pixel, "image/png")
        assertEquals(1, image.width)
        assertEquals(1, image.height)
        val encoded = image.dataUri
        image.bytes[0] = 0
        assertEquals(encoded, image.dataUri)
        assertTrue(EmbeddedImage.fromDataUri(encoded).bytes.contentEquals(pixel))
        assertFailsWith<IllegalArgumentException> { EmbeddedImage.fromDataUri("/tmp/some-image.png") }
        assertFailsWith<IllegalArgumentException> { EmbeddedImage.fromDataUri("https://example.com/image.png") }
        assertFailsWith<IllegalArgumentException> { EmbeddedImage.fromBytes(pixel, "image/jpeg") }
    }

    @Test fun textBoundsUseDesktopAlignmentAndFontEstimation() {
        val document = AnnotationEdits.insertText(CircuitDocument(), "t", GridPoint(100, 100), "abcd\nx", AnnotationFont(size = 12))
        val text = document.components.single()
        assertEquals(ComponentBounds(100, 100, 132, 124), AnnotationLibrary.bounds(text))
        val centered = AnnotationEdits.edit(document, "t", mapOf("halign" to "center", "valign" to "center"))
        assertEquals(ComponentBounds(84, 88, 116, 112), AnnotationLibrary.bounds(centered.components.single()))
        assertFailsWith<IllegalArgumentException> { AnnotationEdits.edit(document, "t", mapOf("font" to "SansSerif plain 0")) }
        assertFailsWith<IllegalArgumentException> { AnnotationEdits.edit(document, "t", mapOf("color" to "red")) }
    }

    @Test fun imageSizeScaleAndAttributionAreDocumentAttributes() {
        val image = EmbeddedImage.fromBytes(pixel, "image/png")
        val document = AnnotationEdits.insertImage(CircuitDocument(), "i", GridPoint(20, 30), image, 120, 80, "cover", "Circuit photo")
        val component = document.components.single()
        assertEquals(ComponentBounds(20, 30, 140, 110), AnnotationLibrary.bounds(component))
        assertEquals("cover", component.attributes["scale"])
        assertEquals("Circuit photo", component.attributes["attribution"])
        assertEquals(image.dataUri, component.attributes["image"])
        assertFailsWith<IllegalArgumentException> { AnnotationEdits.edit(document, "i", mapOf("width" to "0")) }
    }

    @Test fun alignmentAndDistributionAreAtomicUndoableEdits() {
        var document = CircuitDocument()
        for ((id, point) in listOf("a" to GridPoint(0, 10), "b" to GridPoint(70, 30), "c" to GridPoint(100, 90))) {
            document = AnnotationEdits.insertText(document, id, point, "a", AnnotationFont(size = 12))
        }
        val ids = setOf("a", "b", "c")
        val editor = CircuitEditor(document)
        val aligned = AnnotationEdits.align(document, ids, AlignmentAxis.VERTICAL, AlignmentEdge.START)
        assertEquals(listOf(10, 10, 10), aligned.components.map { it.position.y })
        editor.replace(aligned)
        assertTrue(editor.undo())
        assertEquals(document, editor.document)
        val distributed = AnnotationEdits.distribute(document, ids, AlignmentAxis.HORIZONTAL)
        assertEquals(listOf(0, 50, 100), distributed.components.map { it.position.x })
        assertEquals(listOf(0, 70, 100), document.components.map { it.position.x })
    }

    @Test fun layersPreserveRelativeOrderAndGroupsPersistWithTheirMembers() {
        var document = CircuitDocument(components = listOf("a", "b", "c", "d").map { CircuitComponent(it, ComponentKind.TEXT, GridPoint(0, 0)) })
        document = AnnotationEdits.group(document, setOf("a", "c"), "group-1")
        assertEquals(setOf("a", "c"), AnnotationEdits.groupMembers(document, "a"))
        assertEquals(listOf("b", "d", "a", "c"), AnnotationEdits.reorder(document, setOf("a", "c"), LayerMove.FRONT).components.map { it.id })
        assertEquals(listOf("a", "c", "b", "d"), AnnotationEdits.reorder(document, setOf("a", "c"), LayerMove.BACK).components.map { it.id })
        assertEquals(listOf("b", "a", "d", "c"), AnnotationEdits.reorder(document, setOf("a", "c"), LayerMove.FORWARD).components.map { it.id })
        assertEquals(setOf("a"), AnnotationEdits.groupMembers(AnnotationEdits.group(document, setOf("a", "c"), null), "a"))
    }

    @Test fun imageAndTextDefinitionsHaveNoSimulationTerminals() {
        for (definition in AnnotationLibrary.definitions()) {
            val component = CircuitComponent("a", definition.kind, GridPoint(0, 0))
            assertTrue(definition.layout(component, definition.schema.parse(emptyMap())).ports.isEmpty())
            assertTrue(definition.isCombinational)
        }
    }

    @Test fun imageErrorsKeepTheirIdentityWhenTheLanguageChanges() {
        val failure = assertFailsWith<dev.logisim.mobile.core.localization.LocalizedArgumentException> { EmbeddedImage.fromDataUri("/tmp/missing.png") }
        assertEquals("The image must be embedded as PNG, JPEG, or GIF in Base64.", failure.text.resolve(dev.logisim.mobile.core.localization.AppLanguage.ENGLISH))
        assertEquals("A imagem precisa estar incorporada como PNG, JPEG ou GIF em Base64.", failure.text.resolve(dev.logisim.mobile.core.localization.AppLanguage.PORTUGUESE))
    }

    @Test fun anEmptyDesktopImageHasAFrameBeforeContentIsChosen() {
        val component = CircuitComponent("image", AnnotationLibrary.Image, GridPoint(20, 30))
        val item = AnnotationVisuals.item(component)
        assertEquals(VisualBounds(20.0, 30.0, 84.0, 94.0), item.bounds)
        assertTrue(item.primitives.single() is VisualPrimitive.Rectangle)
    }

    @Test fun invalidImageAttributesDoNotRepeatTheEncodedPayloadInTheError() {
        val failure = assertFailsWith<IllegalArgumentException> {
            AnnotationLibrary.definitions().last().schema.parse(mapOf("image" to "data:image/png;base64," + "A".repeat(4096)))
        }
        assertTrue(failure.message.orEmpty().length < 200)
    }
}
