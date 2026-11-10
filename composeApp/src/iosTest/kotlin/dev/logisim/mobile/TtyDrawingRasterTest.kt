package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircImportResult
import dev.logisim.mobile.core.IoLogic
import dev.logisim.mobile.core.IoTerminalState
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualExportOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TtyDrawingRasterTest {
    @Test
    fun blankTerminalDrawsTheCaretAfterLineFeedAndSpace() {
        val terminal = assertIs<CircImportResult.Success>(CircCodec.read("""
            <project source="2.7.1" version="1.0">
              <lib desc="#I/O" name="0"/>
              <main name="main"/>
              <circuit name="main">
                <comp lib="0" loc="(100,100)" name="TTY">
                  <a name="rows" val="2"/><a name="cols" val="8"/>
                </comp>
              </circuit>
            </project>
        """.trimIndent())).document.components.single()
        val empty = IoTerminalState(rows = 2, columns = 8)
        val nextLine = IoLogic.addTerminal(empty, '\n')
        val afterSpace = IoLogic.addTerminal(nextLine, ' ')
        assertEquals(empty.displayRows, nextLine.displayRows)

        val measurer = createRasterTextMeasurer()
        val bounds = VisualBounds(104.0, 72.0, 166.0, 105.0)
        val options = VisualExportOptions(margin = 0.0, background = 0xffffffffu)
        fun render(state: IoTerminalState, printerView: Boolean = false): IntArray {
            val bitmap = renderDrawingBitmap(bounds, options) {
                drawIoComponent(terminal, state, 0, measurer, printerView)
            }
            return IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        }

        val print = render(empty, printerView = true)
        assertContentEquals(print, render(nextLine, printerView = true))
        assertContentEquals(print, render(afterSpace, printerView = true))

        fun caretPixels(state: IoTerminalState): Set<Int> {
            val live = render(state)
            return live.indices.filter { live[it] != print[it] }.toSet()
        }
        val first = caretPixels(empty)
        assertTrue(first.isNotEmpty())
        val second = caretPixels(nextLine)
        val rasterWidth = (bounds.right - bounds.left).toInt()
        assertEquals(first.map { it + 15 * rasterWidth }.toSet(), second)
        val third = caretPixels(afterSpace)
        assertTrue(third.isNotEmpty())
        assertEquals(second.min() / rasterWidth, third.min() / rasterWidth)
        assertTrue(third.min() % rasterWidth > second.min() % rasterWidth)
    }
}
