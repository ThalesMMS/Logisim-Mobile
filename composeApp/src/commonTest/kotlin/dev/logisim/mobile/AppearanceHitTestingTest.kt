package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SymbolAnchor
import dev.logisim.mobile.core.SymbolAppearance
import dev.logisim.mobile.core.SymbolObject
import dev.logisim.mobile.core.SymbolPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppearanceHitTestingTest {
    @Test
    fun overlappingPortTargetsChooseTheNearestRealPin() {
        val appearance = SymbolAppearance(SymbolAnchor(GridPoint(-500, -500)), listOf(
            SymbolPort("left", GridPoint(100, 100)), SymbolPort("right", GridPoint(120, 100))), emptyList())

        assertEquals("port-right", appearancePreviewHit(appearance, Offset(118f, 100f), Offset.Zero, 1f, 24f))
        assertEquals("port-left", appearancePreviewHit(appearance, Offset(102f, 100f), Offset.Zero, 1f, 24f))
        assertEquals("port-right", appearancePreviewHit(appearance.copy(ports = appearance.ports.reversed()),
            Offset(118f, 100f), Offset.Zero, 1f, 24f))
    }

    @Test
    fun aSelectedAnchorRemainsReachableBesideAPortWithoutStealingDistantTouches() {
        val appearance = SymbolAppearance(SymbolAnchor(GridPoint(100, 100)),
            listOf(SymbolPort("input", GridPoint(110, 100))), emptyList())

        assertEquals("port-input", appearancePreviewHit(appearance, Offset(108f, 100f), Offset.Zero, 1f, 24f))
        assertEquals("anchor", appearancePreviewHit(appearance, Offset(108f, 100f), Offset.Zero, 1f, 24f, "anchor"))
        assertEquals("port-input", appearancePreviewHit(appearance, Offset(130f, 100f), Offset.Zero, 1f, 24f, "anchor"))
    }

    @Test
    fun anchorsAndPortsAreSelectableOverTheBodyOfTheSymbol() {
        val appearance = SymbolAppearance(SymbolAnchor(GridPoint(100, 100)),
            listOf(SymbolPort("output", GridPoint(160, 100))), listOf(rectangle("body", 70, 70, 140, 80)))

        assertEquals("anchor", appearancePreviewHit(appearance, Offset(110f, 105f), Offset.Zero, 1f, 24f))
        assertEquals("port-output", appearancePreviewHit(appearance, Offset(163f, 105f), Offset.Zero, 1f, 24f))
        assertEquals("body", appearancePreviewHit(appearance, Offset(125f, 140f), Offset.Zero, 1f, 24f))
    }

    @Test
    fun overlappingShapesSelectTheTopmostLayerAndFollowReordering() {
        val back = rectangle("back", 100, 100, 100, 100)
        val front = rectangle("front", 150, 150, 100, 100)
        val appearance = SymbolAppearance(SymbolAnchor(GridPoint(-500, -500)), emptyList(), listOf(back, front))

        assertEquals("front", appearancePreviewHit(appearance, Offset(175f, 175f), Offset.Zero, 1f, 24f))
        assertEquals("back", appearancePreviewHit(appearance.copy(objects = listOf(front, back)),
            Offset(175f, 175f), Offset.Zero, 1f, 24f))
        assertEquals("back", appearancePreviewHit(appearance, Offset(110f, 110f), Offset.Zero, 1f, 24f))
        assertNull(appearancePreviewHit(appearance, Offset(300f, 300f), Offset.Zero, 1f, 24f))
    }

    @Test
    fun portTouchRadiusStaysTwentyFourDpAcrossZoomDensityAndPan() {
        val appearance = SymbolAppearance(SymbolAnchor(GridPoint(-500, -500)),
            listOf(SymbolPort("data", GridPoint(100, 80))), emptyList())
        val origin = Offset(37f, -19f)
        for (density in listOf(1f, 2f, 3f)) {
            for (zoom in listOf(0.25f, 1f, 4f)) {
                val center = origin + Offset(100f * zoom, 80f * zoom)
                assertEquals("port-data", appearancePreviewHit(appearance, center + Offset(23f * density, 0f),
                    origin, zoom, 24f * density), "density=$density, zoom=$zoom")
                assertNull(appearancePreviewHit(appearance, center + Offset(24f * density, 0f),
                    origin, zoom, 24f * density), "density=$density, zoom=$zoom")
            }
        }
    }

    @Test
    fun portTouchTargetsUseRadialDistanceRatherThanASquare() {
        val appearance = SymbolAppearance(SymbolAnchor(GridPoint(-500, -500)),
            listOf(SymbolPort("data", GridPoint(100, 100))), emptyList())

        assertEquals("port-data", appearancePreviewHit(appearance, Offset(116f, 116f), Offset.Zero, 1f, 24f))
        assertNull(appearancePreviewHit(appearance, Offset(117f, 117f), Offset.Zero, 1f, 24f))
    }

    private fun rectangle(id: String, x: Int, y: Int, width: Int, height: Int) = SymbolObject.Shape(id, "rect", mapOf(
        "x" to x.toString(), "y" to y.toString(), "width" to width.toString(), "height" to height.toString()))
}
