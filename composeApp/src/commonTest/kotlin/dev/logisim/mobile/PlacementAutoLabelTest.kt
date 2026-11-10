package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlacementAutoLabelTest {
    @Test
    fun skipsOccupiedNamesAndKeepsAssignedLabelsThroughHistoryAndExport() {
        val existing = CircuitComponent("existing", ComponentKind.LED, GridPoint(100, 100), "led2")
        val editor = open(CircuitDocument("main", listOf(existing)))
        editor.place(ComponentKind.LED)
        val original = editor.exportXml()
        assertTrue(editor.setPlacementAutoLabel("LED1"))
        assertEquals(original, editor.exportXml())

        editor.tap(GridPoint(100, 140))
        editor.tap(GridPoint(100, 180))
        editor.tap(GridPoint(100, 220))
        val labels = listOf("led2", "LED1", "LED3", "LED4")
        assertEquals(labels, editor.document.components.map { it.label })
        assertEquals(labels, EditorState(editor.exportXml()).document.components.map { it.label })

        editor.undo()
        assertEquals(labels.dropLast(1), editor.document.components.map { it.label })
        editor.redo()
        assertEquals(labels, editor.document.components.map { it.label })
        editor.undo()
        editor.tap(GridPoint(100, 260))
        assertEquals(labels.dropLast(1) + "LED5", editor.document.components.map { it.label })
    }

    @Test
    fun scopesSequenceToCircuitAndToolAndPreservesExplicitLabels() {
        val editor = open(CircuitDocument("main"), CircuitDocument("other"))
        editor.place(ComponentKind.LED)
        assertTrue(editor.setPlacementAutoLabel("Sensor"))
        assertFalse(editor.setPlacementAutoLabel("2 invalid"))
        editor.tap(GridPoint(100, 100))
        assertEquals("Sensor", editor.document.components.single().label)

        editor.place(ComponentKind.BUTTON)
        editor.tap(GridPoint(200, 100))
        assertEquals("", editor.document.components.last().label)

        editor.place(CircuitComponent("tool", ComponentKind.LED, GridPoint(0, 0), "fixed"))
        editor.tap(GridPoint(300, 100))
        assertEquals("fixed", editor.document.components.last().label)

        editor.place(ComponentKind.LED)
        editor.navigate(CircuitNavigationTarget("other", "other"))
        editor.tap(GridPoint(100, 100))
        assertEquals("", editor.document.components.single().label)

        editor.navigate(CircuitNavigationTarget("main", "main"))
        editor.tap(GridPoint(400, 100))
        assertEquals("Sensor_1", editor.document.components.last().label)
        assertTrue(editor.setPlacementAutoLabel(""))
        editor.tap(GridPoint(500, 100))
        assertEquals("", editor.document.components.last().label)
    }

    @Test
    fun usesTheNextAvailableAutoLabelAsTheMatrixBase() {
        val existing = CircuitComponent("existing", ComponentKind.LED, GridPoint(100, 100), "LED1")
        val editor = open(CircuitDocument("main", listOf(existing)))
        editor.place(ComponentKind.LED)
        assertTrue(editor.setPlacementAutoLabel("led1"))
        editor.togglePlacementMatrix()
        editor.tap(GridPoint(100, 200))
        val placement = assertNotNull(editor.pendingMatrixPlacement)
        assertEquals("led2", placement.defaultBaseLabel)
        assertTrue(editor.confirmMatrixPlacement(placement, 2, 1, 50, 50, placement.defaultBaseLabel))
        assertEquals(listOf("LED1", "led2_X0_Y0", "led2_X1_Y0"), editor.document.components.map { it.label })
    }

    private fun open(vararg circuits: CircuitDocument) =
        EditorState(CircCodec.writeProject(CircuitProject(circuits.toList()))).apply {
            afterPlacement = EditorAfterPlacement.KEEP_TOOL
        }
}
