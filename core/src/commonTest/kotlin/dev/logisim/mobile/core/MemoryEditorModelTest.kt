package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MemoryEditorModelTest {
    @Test
    fun navigatePagesAndNumericalBasesExposeActualAddresses() {
        val start = MemoryEditorModel(MemoryImage(8, 8, mapOf(255L to 15uL)), pageSize = 16)
        val navigated = assertIs<MemoryEditResult.Success>(start.navigate("ff")).model
        assertEquals(240L, navigated.firstAddress)
        assertEquals(MemorySelection(255, 255), navigated.selection)
        assertEquals("ff", navigated.cells().last().addressText)
        assertEquals("0f", navigated.cells().last().valueText)
        assertEquals("00001111", navigated.copy(valueRadix = MemoryRadix.BINARY).cells().last().valueText)
        assertEquals(240L, navigated.page(20).firstAddress)
        assertEquals(0L, navigated.page(-20).firstAddress)
        assertEquals(0L, start.firstAddress)
    }

    @Test
    fun cellAndRangeEditsAreAtomicAndDoNotTruncateInvalidValues() {
        val original = MemoryEditorModel(MemoryImage(4, 4, mapOf(0L to 1uL)), pageSize = 8)
        val selected = assertIs<MemoryEditResult.Success>(original.select("2", "4")).model
        val filled = assertIs<MemoryEditResult.Success>(selected.fillSelection("a")).model
        assertEquals(listOf(10uL, 10uL, 10uL), (2L..4L).map { filled.image[it] })
        assertEquals("a a a", filled.selectionText())
        assertEquals(0uL, original.image[2])
        assertIs<MemoryEditResult.Failure>(selected.fillSelection("10"))
        assertIs<MemoryEditResult.Failure>(selected.replaceSelection(listOf("1", "2")))
        assertIs<MemoryEditResult.Failure>(selected.replaceSelection(listOf("1", "zz", "3")))
        assertEquals(0uL, selected.image[2])
    }

    @Test
    fun pasteChecksTheWholeRangeBeforeApplying() {
        val original = MemoryEditorModel(MemoryImage(4, 8))
        val result = assertIs<MemoryEditResult.Success>(original.paste("c", "01 0x20,ff;03")).model
        assertEquals(listOf(1uL, 32uL, 255uL, 3uL), (12L..15L).map { result.image[it] })
        assertEquals(MemorySelection(12, 15), result.selection)
        assertIs<MemoryEditResult.Failure>(original.paste("e", "01 02 03"))
        assertIs<MemoryEditResult.Failure>(original.paste("c", "01 100 03"))
        assertEquals(emptyMap(), original.image.cells)
    }

    @Test
    fun importedImagesMustMatchTheOpenMemoryWidths() {
        val original = MemoryEditorModel(MemoryImage(4, 8))
        assertIs<MemoryEditResult.Failure>(original.replaceImage(MemoryImage(4, 16)))
        assertIs<MemoryEditResult.Failure>(original.replaceImage(MemoryImage(8, 8)))
        val replacement = MemoryImage(4, 8, mapOf(7L to 18uL))
        assertEquals(replacement, assertIs<MemoryEditResult.Success>(original.replaceImage(replacement)).model.image)
    }
}
