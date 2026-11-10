package dev.logisim.mobile.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpatialIndexTest {
    @Test
    fun matchesLinearIntersectionAcrossSeededScenesAndViewports() {
        val random = Random(480011)
        val items = List(2_000) { index ->
            val left = random.nextDouble(-100_000.0, 100_000.0)
            val top = random.nextDouble(-100_000.0, 100_000.0)
            SpatialItem(index, SpatialRect(left, top, left + random.nextDouble(0.0, 4_000.0),
                top + random.nextDouble(0.0, 4_000.0)))
        }
        val index = SpatialIndex(items)
        repeat(250) {
            val left = random.nextDouble(-110_000.0, 110_000.0)
            val top = random.nextDouble(-110_000.0, 110_000.0)
            val viewport = SpatialRect(left, top, left + 20_000.0, top + 20_000.0)
            assertEquals(items.filter { it.bounds.intersects(viewport) }.map { it.value }, index.query(viewport))
        }
    }

    @Test
    fun preservesPaintingOrderAndFindsThinWiresOnViewportEdges() {
        val items = listOf(
            SpatialItem("last-painted-first-in-index", SpatialRect(100.0, 100.0, 120.0, 120.0)),
            SpatialItem("long-wire", SpatialRect(-1e9, 100.0, 1e9, 100.0)),
            SpatialItem("vertical-wire", SpatialRect(100.0, -1e9, 100.0, 1e9)),
            SpatialItem("point", SpatialRect(100.0, 100.0, 100.0, 100.0)),
        )
        val index = SpatialIndex(items)
        assertEquals(items.map { it.value }, index.query(SpatialRect(100.0, 100.0, 100.0, 100.0)))
        assertEquals(listOf("long-wire"), index.query(SpatialRect(900.0, 100.0, 1_000.0, 110.0)))
        assertEquals(emptyList(), index.query(SpatialRect(900.0, 200.0, 1_000.0, 300.0)))
    }

    @Test
    fun emptyAndFullSceneQueriesAreStable() {
        val bounds = SpatialRect(-10.0, -10.0, 10.0, 10.0)
        assertEquals(emptyList(), SpatialIndex<String>(emptyList()).query(bounds))
        val source = mutableListOf(SpatialItem("a", bounds), SpatialItem("b", bounds))
        val index = SpatialIndex(source)
        source.clear()
        assertEquals(2, index.size)
        assertEquals(listOf("a", "b"), index.query(bounds))
    }

    @Test
    fun rejectsInvalidBoundsAtTheBoundary() {
        assertFailsWith<IllegalArgumentException> { SpatialRect(1.0, 0.0, 0.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { SpatialRect(Double.NaN, 0.0, 1.0, 1.0) }
    }
}
