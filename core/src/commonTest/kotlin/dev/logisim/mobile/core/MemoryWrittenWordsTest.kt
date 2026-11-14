package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** W2-10 R09-3: the editor keeps the words its edits wrote, and Restore edits writes all of them back. */
class MemoryWrittenWordsTest {
    private fun ranges(vararg added: LongRange) = added.fold(MemoryAddressRanges()) { result, range -> result + range }

    @Test
    fun rangesStaySortedAndMergeWhenTheyOverlapOrTouch() {
        assertTrue(MemoryAddressRanges().isEmpty)
        assertEquals(listOf(2L..4L), ranges(2L..2L, 3L..3L, 4L..4L).ranges, "Adjacent words become one range")
        assertEquals(listOf(0L..1L, 5L..9L, 20L..20L), ranges(20L..20L, 5L..7L, 0L..1L, 6L..9L).ranges)
        assertEquals(listOf(0L..30L), ranges(20L..20L, 5L..7L, 0L..1L, 2L..30L).ranges, "A wide range swallows the ones inside it")
        assertEquals(ranges(1L..3L), ranges(1L..3L) + LongRange.EMPTY)
        val written = ranges(0L..1L, 5L..9L, 20L..20L)
        assertTrue(listOf(0L, 1L, 5L, 7L, 9L, 20L).all { it in written })
        assertTrue(listOf(2L, 4L, 10L, 19L, 21L).none { it in written })
        assertEquals(listOf(0L, 1L, 5L, 6L, 7L, 8L, 9L, 20L), written.addresses().toList())
    }

    @Test
    fun wordEditsFillsReplacementsAndPastesRecordTheWordsTheyWrite() {
        val start = MemoryEditorModel(MemoryImage(4, 8, mapOf(3L to 0x11uL)))
        assertTrue(start.written.isEmpty)
        // A word written with the value it already holds still counts.
        val edited = assertIs<MemoryEditResult.Success>(start.editCell("3", "11")).model
        assertEquals(listOf(3L..3L), edited.written.ranges)
        val selected = assertIs<MemoryEditResult.Success>(edited.select("8", "a")).model
        val filled = assertIs<MemoryEditResult.Success>(selected.fillSelection("00")).model
        assertEquals(listOf(3L..3L, 8L..10L), filled.written.ranges)
        val pasted = assertIs<MemoryEditResult.Success>(filled.paste(4L, "1 2 3 4")).model
        assertEquals(listOf(3L..10L), pasted.written.ranges)
        val replaced = assertIs<MemoryEditResult.Success>(pasted.select("e", "f")).model.replaceSelection(listOf("5", "6"))
        assertEquals(listOf(3L..10L, 14L..15L), assertIs<MemoryEditResult.Success>(replaced).model.written.ranges)
        // A failed edit writes nothing.
        assertIs<MemoryEditResult.Failure>(pasted.editCell("0", "1ff"))
        assertFalse(0L in pasted.written)
    }

    /** The finding's case: Fill 0x0–0xf with 00 over a memory where only word 3 held another value. */
    @Test
    fun restoringAFillWritesEveryFilledWordOverTheCurrentContents() {
        val base = MemoryImage(4, 8, mapOf(3L to 0x11uL))
        val filled = assertIs<MemoryEditResult.Success>(
            assertIs<MemoryEditResult.Success>(MemoryEditorModel(base).select("0", "f")).model.fillSelection("00")).model
        // Meanwhile the simulation writes word 5.
        val current = MemoryImage(4, 8, mapOf(3L to 0x11uL, 5L to 0x33uL))
        assertEquals(MemoryImage(4, 8, mapOf(5L to 0x33uL)), rebaseMemoryEdits(base, filled.image, current),
            "Without the written words only the value difference comes back")
        assertEquals(MemoryImage(4, 8), rebaseMemoryEdits(base, filled.image, current, filled.written))
    }

    @Test
    fun wordsNobodyWroteKeepTheirCurrentValue() {
        val base = MemoryImage(4, 8, mapOf(0L to 0x0auL))
        val edited = assertIs<MemoryEditResult.Success>(MemoryEditorModel(base).editCell("1", "22")).model
        val current = MemoryImage(4, 8, mapOf(0L to 0x77uL, 2L to 0x99uL))
        assertEquals(MemoryImage(4, 8, mapOf(0L to 0x77uL, 1L to 0x22uL, 2L to 0x99uL)),
            rebaseMemoryEdits(base, edited.image, current, edited.written))
    }
}
