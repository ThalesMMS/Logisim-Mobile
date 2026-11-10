package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppearanceSelectionEditsTest {
    private fun rectangle(id: String, x: String, color: String) = SymbolObject.Shape(
        id, "rect", mapOf("id" to id, "x" to x, "y" to "0", "width" to "40", "height" to "30", "fill" to color),
    )

    @Test fun overlappingShapesKeepRelativeOrderAndMarkersAcrossGroupEdits() {
        val red = rectangle("red", "0", "#ff0000")
        val blue = rectangle("shape-1", "10", "#0000ff")
        val green = rectangle("green", "20", "#00ff00")
        val opaque = SymbolObject.Opaque("<!-- keep -->")
        val original = SymbolAppearance(
            SymbolAnchor(GridPoint(0, 40)), listOf(SymbolPort("input", GridPoint(0, 10))),
            listOf(red, blue, opaque, green),
        )
        val selected = linkedSetOf("green", "red", "anchor", "port-input")
        val clipboard = AppearanceSelectionEdits.shapes(original, selected)
        assertEquals(listOf(red, green), clipboard)
        assertEquals(listOf(blue, opaque, red, green), AppearanceSelectionEdits.layer(original, selected, front = true).objects)
        assertEquals(listOf(red, green, blue, opaque), AppearanceSelectionEdits.layer(original, selected, front = false).objects)

        val (duplicated, duplicateIds) = AppearanceSelectionEdits.insert(original, clipboard, duplicate = true)
        val (pasted, pastedIds) = AppearanceSelectionEdits.insert(duplicated, clipboard, duplicate = false)
        assertEquals(listOf("shape-2", "shape-3"), duplicateIds.toList())
        assertEquals(listOf("shape-4", "shape-5"), pastedIds.toList())
        assertEquals(listOf(10, 30), AppearanceSelectionEdits.shapes(duplicated, duplicateIds).map { SymbolAppearance.shapeBounds(it).left })
        assertEquals(listOf(20, 40), AppearanceSelectionEdits.shapes(pasted, pastedIds).map { SymbolAppearance.shapeBounds(it).left })
        assertTrue(AppearanceSelectionEdits.shapes(pasted, pastedIds).none { "id" in it.attributes })

        val cut = AppearanceSelectionEdits.remove(original, selected)
        assertEquals(listOf(blue, opaque), cut.objects)
        val (restored, restoredIds) = AppearanceSelectionEdits.insert(cut, clipboard, duplicate = false)
        assertEquals(listOf(0, 20), AppearanceSelectionEdits.shapes(restored, restoredIds).map { SymbolAppearance.shapeBounds(it).left })
        for (appearance in listOf(duplicated, pasted, cut, restored)) {
            assertEquals(original.anchor, appearance.anchor)
            assertEquals(original.ports, appearance.ports)
            assertTrue(opaque in appearance.objects)
        }
    }
}
