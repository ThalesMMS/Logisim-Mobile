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
    fun binaryValuesKeepHexadecimalAddresses() {
        val model = MemoryEditorModel(MemoryImage(16, 16, mapOf(0x10L to 0xbeefuL)), pageSize = 16)
            .copy(valueRadix = MemoryRadix.BINARY)
        assertEquals(MemoryRadix.HEXADECIMAL, model.addressRadix)
        val cell = model.cells()[0]
        assertEquals("0000", cell.addressText)
        // A typed address is still read in hexadecimal: "10" is word 16, not word 2.
        val edited = assertIs<MemoryEditResult.Success>(model.editCell("10", "1010")).model
        assertEquals(0b1010uL, edited.image[16])
        assertEquals("0000000000001010", edited.cell(16).valueText)
        assertEquals("0010", edited.cell(16).addressText)
    }

    @Test
    fun pasteAtWritesFromTheSelectedWord() {
        val selected = assertIs<MemoryEditResult.Success>(MemoryEditorModel(MemoryImage(4, 8)).select("5", "9")).model
        val pasted = assertIs<MemoryEditResult.Success>(selected.paste(selected.selection!!.first, "aa bb cc")).model
        assertEquals(listOf(0xaauL, 0xbbuL, 0xccuL), (5L..7L).map { pasted.image[it] })
        assertEquals(MemorySelection(5, 7), pasted.selection)
        assertEquals(0uL, pasted.image[4])
        assertIs<MemoryEditResult.Failure>(selected.paste(14L, "1 2 3"))
        assertIs<MemoryEditResult.Failure>(selected.paste(16L, "1"))
    }

    @Test
    fun cellsOffThePageComeFromTheCurrentImage() {
        val model = MemoryEditorModel(MemoryImage(8, 8, mapOf(0x40L to 0x5auL)), pageSize = 64)
        assertEquals(0L, model.firstAddress)
        val cell = model.cell(0x40)
        assertEquals("40", cell.addressText)
        assertEquals("5a", cell.valueText)
        assertIs<MemoryEditResult.Failure>(model.editCell("100", "1"))
    }

    @Test
    fun importedImagesMustMatchTheOpenMemoryWidths() {
        val original = MemoryEditorModel(MemoryImage(4, 8))
        assertIs<MemoryEditResult.Failure>(original.replaceImage(MemoryImage(4, 16)))
        assertIs<MemoryEditResult.Failure>(original.replaceImage(MemoryImage(8, 8)))
        val replacement = MemoryImage(4, 8, mapOf(7L to 18uL))
        assertEquals(replacement, assertIs<MemoryEditResult.Success>(original.replaceImage(replacement)).model.image)
    }

    /** Restored edits rewrite only the words the user changed, cleared words included; other words stay current. */
    @Test
    fun rebasedEditsKeepWordsThatChangedElsewhere() {
        val base = MemoryImage(4, 8, mapOf(0L to 0x0auL, 1L to 0x0buL, 2L to 0x0cuL))
        val edited = MemoryImage(4, 8, mapOf(0L to 0x12uL, 1L to 0x0buL, 5L to 0x55uL))
        val current = MemoryImage(4, 8, mapOf(0L to 0x77uL, 1L to 0x99uL, 2L to 0x09uL, 3L to 0x05uL))
        assertEquals(MemoryImage(4, 8, mapOf(0L to 0x12uL, 1L to 0x99uL, 3L to 0x05uL, 5L to 0x55uL)),
            rebaseMemoryEdits(base, edited, current))
        assertEquals(current, rebaseMemoryEdits(base, base, current))
        kotlin.test.assertFailsWith<IllegalArgumentException> { rebaseMemoryEdits(base, edited, MemoryImage(4, 16)) }
    }
}
