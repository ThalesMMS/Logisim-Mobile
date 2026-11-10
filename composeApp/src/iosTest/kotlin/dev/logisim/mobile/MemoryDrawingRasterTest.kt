package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.MemoryImage
import dev.logisim.mobile.core.MemoryImageCodec
import dev.logisim.mobile.core.MemoryImageReplaced
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualExportOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryDrawingRasterTest {
    @Test
    fun classicRamDrawsTheEditedAndWrittenWordAtAHighAddress() {
        val registry = ComponentCatalog.registry
        val simulator = CircuitSimulator(registry = registry)
        val attributes = mapOf("appearance" to "classic", "addrWidth" to "8", "dataWidth" to "8")
        val ram = registry.create(ComponentKind.RAM, "memory", GridPoint(0, 0), attributes)
        val drivers = registry.layout(ram).inputs.map { terminal ->
            CircuitComponent(terminal.name, ComponentKind.INPUT, terminal.position,
                attributes = mapOf("width" to terminal.width.toString()))
        }
        val document = CircuitDocument(components = drivers + ram)
        val initial = simulator.settle(document, vectorInputs = mapOf(
            "address0" to LogicVector.of(8, 0x80uL),
            "dataIn0_0" to LogicVector.of(8, 0x3cuL),
            "clock0" to LogicVector.low(1),
            "writeEnable0" to LogicVector.high(1),
            "outputEnable0" to LogicVector.high(1),
        ))
        val edited = simulator.settle(document, initial, events = mapOf(
            ram.id to listOf(MemoryImageReplaced(MemoryImage(8, 8, mapOf(0x80L to 0xa5uL)))),
        ))
        val written = simulator.settle(document, edited, vectorInputs = mapOf("clock0" to LogicVector.high(1)))

        val measurer = createRasterTextMeasurer()
        val grid = assertNotNull(memoryDisplayGrid(ram, registry)).following(0x80)
        val cell = grid.cell(0x80)
        val cellBounds = VisualBounds(cell.left.toDouble(), cell.top.toDouble(), cell.right.toDouble(), cell.bottom.toDouble())
        fun renderActiveWord(component: CircuitComponent, simulation: SimulationState): IntArray {
            val bitmap = renderDrawingBitmap(cellBounds, VisualExportOptions(margin = 0.0, background = 0xffffffffu)) {
                drawCircuitDrawing(CircuitDocument(components = listOf(component)), simulation, measurer, AnnotationImageCache())
            }
            return IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        }
        fun romWithWord(word: ULong) = registry.create(
            ComponentKind.ROM, ram.id, ram.position,
            attributes + ("contents" to MemoryImageCodec.encodeCirc(MemoryImage(8, 8, mapOf(0x80L to word)))),
        )

        val before = renderActiveWord(ram, initial)
        val afterEdit = renderActiveWord(ram, edited)
        val afterWrite = renderActiveWord(ram, written)
        assertFalse(before.contentEquals(afterEdit))
        assertFalse(afterEdit.contentEquals(afterWrite))
        assertTrue(listOf(before, afterEdit, afterWrite).all { 0xff555555.toInt() in it })
        assertContentEquals(renderActiveWord(romWithWord(0xa5uL), written), afterEdit)
        assertContentEquals(renderActiveWord(romWithWord(0x3cuL), edited), afterWrite)
    }
}
