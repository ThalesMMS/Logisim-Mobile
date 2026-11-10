package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidthIncompatibilityTest {
    private fun pin(id: String, point: GridPoint, width: Int, output: Boolean = false) =
        CircuitComponent(id, if (output) ComponentKind.OUTPUT else ComponentKind.INPUT, point,
            attributes = mapOf("width" to width.toString()))

    @Test
    fun reportsTheConflictingPointsWiresAndCommonWidthLikeWidthIncompatibilityData() {
        val a = pin("a", GridPoint(0, 0), 8)
        val b = pin("b", GridPoint(100, 0), 8, output = true)
        val c = pin("c", GridPoint(50, 50), 4, output = true)
        val wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(50, 0)), WireSegment(GridPoint(50, 0), GridPoint(100, 0)),
            WireSegment(GridPoint(50, 0), GridPoint(50, 50)))
        val topology = CircuitTopology.elaborate(CircuitDocument("widths", listOf(a, b, c), wires))
        val conflict = topology.widthIncompatibilities().single()
        assertEquals(setOf(WidthIncompatibilityPoint(GridPoint(0, 0), 8), WidthIncompatibilityPoint(GridPoint(100, 0), 8),
            WidthIncompatibilityPoint(GridPoint(50, 50), 4)), conflict.points.toSet())
        // getCommonBitWidth(): 8 wins two to one, so only the 4-bit pin is highlighted.
        assertEquals(8, conflict.commonWidth)
        assertEquals(wires.toSet(), conflict.wires)
        assertTrue(GridPoint(50, 0) in conflict.netPoints)
        // PokeTool highlights every wire of the bundle.
        assertEquals(wires.toSet(), topology.wireSet(wires.first()))
    }

    @Test
    fun tiedWidthsHaveNoCommonWidthAndCaptionsJoinWidthsAtOnePoint() {
        val exception = WidthIncompatibility(listOf(WidthIncompatibilityPoint(GridPoint(0, 0), 4),
            WidthIncompatibilityPoint(GridPoint(0, 0), 8), WidthIncompatibilityPoint(GridPoint(10, 0), 1)), emptySet(), emptySet())
        assertNull(exception.commonWidth)
        assertEquals("4/8", exception.caption(0))
        assertEquals("1", exception.caption(2))
        assertEquals(listOf(true, false, true), exception.points.indices.map(exception::isFirstAt))
    }

    @Test
    fun matchingWidthsAreValid() {
        val a = pin("a", GridPoint(0, 0), 4)
        val b = pin("b", GridPoint(40, 0), 4, output = true)
        val document = CircuitDocument("ok", listOf(a, b), listOf(WireSegment(a.position, b.position)))
        assertEquals(emptyList(), CircuitTopology.elaborate(document).widthIncompatibilities())
    }

    @Test
    fun karnaughGroupsBecomeEvolutionCoverRectanglesWithCyclingColors() {
        // Cover rectangles follow KMapGroupInfo.build: grow right on the first row, then down.
        assertEquals(listOf(KarnaughArea(1, 0, 2, 2)), karnaughAreas(listOf(
            listOf(false, true, true, false), listOf(false, true, true, false))))
        // A group that wraps around the edges is drawn as separate rectangles.
        assertEquals(listOf(KarnaughArea(0, 0, 1, 1), KarnaughArea(3, 0, 1, 1)), karnaughAreas(listOf(
            listOf(true, false, false, true))))
        val table = TruthTable(listOf("A", "B"), listOf("Y"), listOf(
            listOf(TruthCell.ZERO), listOf(TruthCell.ONE), listOf(TruthCell.ONE), listOf(TruthCell.ONE)))
        val map = KarnaughMap.from(table, 0, BooleanMinimizer.minimize(table, 0, NormalForm.SOP))
        assertEquals(2, map.groups.size)
        assertEquals(listOf(0, 1), map.groups.map { it.colorIndex })
        assertEquals(0, KarnaughGroup(16, emptyList()).colorIndex)
        assertEquals(15, KarnaughGroup(31, emptyList()).colorIndex)
    }

    @Test
    fun newTextTakesTheTextToolColorUnlessItIsTheCatalogDefault() {
        val registry = ComponentCatalog.registry
        val text = registry.createNew(ComponentKind.TEXT, "t", GridPoint(0, 0), CreationDefaults(textColor = "#ff0000"))
        assertEquals("#ff0000", text.attributes["color"])
        val plain = registry.createNew(ComponentKind.TEXT, "p", GridPoint(0, 0), CreationDefaults())
        assertEquals(null, plain.attributes["color"]?.takeIf { it != CreationDefaults.DEFAULT_TEXT_COLOR })
        assertEquals(emptyMap(), CreationDefaults().textAttributes())
    }
}
