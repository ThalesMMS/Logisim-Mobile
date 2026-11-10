package dev.logisim.mobile

import androidx.compose.ui.unit.IntRect
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.PortDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CircuitAccessibilityViewportTest {
    @Test
    fun virtualTargetFollowsPanAndZoomAndKeepsItsCenter() {
        val rect = accessibilityScreenBounds(ComponentBounds(10, 20, 30, 40), CircuitAccessibilityViewport(2f, 100f, -20f, 500, 400), 48f)
        assertEquals(AccessibilityScreenBounds(116f, 16f, 164f, 64f), rect)
        assertEquals(48f, rect.width)
        assertEquals(48f, rect.height)
    }

    @Test
    fun edgeTargetsRemainFullSizeInsideTheCanvas() {
        val viewport = CircuitAccessibilityViewport(1f, 0f, 0f, 390, 300)
        assertEquals(AccessibilityScreenBounds(342f, 86f, 390f, 134f),
            accessibilityTargetBounds(ComponentBounds(380, 100, 420, 120), viewport, 48f))
        assertEquals(AccessibilityScreenBounds(0f, 86f, 48f, 134f),
            accessibilityTargetBounds(ComponentBounds(-30, 100, 10, 120), viewport, 48f))
        assertEquals(AccessibilityScreenBounds(86f, 0f, 134f, 48f),
            accessibilityTargetBounds(ComponentBounds(100, -30, 120, 10), viewport, 48f))
        assertEquals(AccessibilityScreenBounds(86f, 252f, 134f, 300f),
            accessibilityTargetBounds(ComponentBounds(100, 290, 120, 330), viewport, 48f))
    }

    @Test
    fun oversizedTargetsFitTheViewportWithoutChangingVisibilityGeometry() {
        val viewport = CircuitAccessibilityViewport(1f, 0f, 0f, 390, 300)
        val bounds = ComponentBounds(-100, -100, 500, 500)
        assertEquals(AccessibilityScreenBounds(0f, 0f, 390f, 300f), accessibilityTargetBounds(bounds, viewport, 48f))
        assertEquals(AccessibilityScreenBounds(-100f, -100f, 500f, 500f), accessibilityScreenBounds(bounds, viewport, 0f))
        assertEquals(AccessibilityScreenBounds(0f, 0f, 32f, 20f),
            accessibilityTargetBounds(ComponentBounds(30, 10, 50, 30), viewport.copy(width = 32, height = 20), 48f))
    }

    @Test
    fun selectedTargetsKeepTheirFullWidthAndCrowdedPortsBecomeActions() {
        val viewport = CircuitAccessibilityViewport(1f, 0f, 0f, 390, 300)
        fun component(id: String, x: Int) = AccessibleComponent(
            CircuitComponent(id, ComponentKind.LED, GridPoint(x, 100)), id, "LED",
            ComponentBounds(x - 10, 90, x + 10, 110), emptyList(),
        )
        fun port(owner: String, name: String, x: Int) = AccessiblePort(
            AccessiblePortId(owner, name), name, GridPoint(x, 100), PortDirection.INPUT, 1, emptyList(), emptyList(),
        )
        val ledInput = port("led", "in", 135)
        val edgeInput = port("edge", "in", 0)
        val isolated = port("edge", "far", 75)
        val overlappingPort = port("edge", "near", 80)
        val nodes = AccessibilityOverlayNodes(
            listOf(component("edge", 2), component("neighbor", 120), component("led", 148)),
            listOf(edgeInput, isolated, overlappingPort, ledInput),
        )

        val targets = accessibilityOverlayTargets(nodes, viewport, 48f, setOf("edge", "led"))

        assertEquals(IntRect(0, 76, 48, 124), targets.components["edge"])
        assertEquals(IntRect(124, 76, 172, 124), targets.components["led"])
        assertEquals(setOf("edge", "neighbor", "led"), targets.components.keys)
        assertDisjoint(targets.components.values + targets.ports.values)
        assertEquals(mapOf(isolated.id to IntRect(51, 76, 99, 124)), targets.ports)
        assertEquals(mapOf("edge" to listOf(edgeInput, overlappingPort), "led" to listOf(ledInput)), targets.inlinePorts)
    }

    private fun stacked(id: String, bounds: ComponentBounds = ComponentBounds(100, 100, 140, 140)) = AccessibleComponent(
        CircuitComponent(id, ComponentKind.AND, GridPoint(bounds.right, bounds.top + 20)), id, "AND", bounds, emptyList(),
    )

    private fun assertDisjoint(rects: Collection<IntRect>) {
        val all = rects.toList()
        for (i in all.indices) for (j in i + 1 until all.size) {
            val (a, b) = all[i] to all[j]
            assertFalse(a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top, "$a overlaps $b")
        }
    }

    @Test
    fun aDuplicateOnTopOfItsOriginalGetsItsOwnTarget() {
        val viewport = CircuitAccessibilityViewport(1f, 0f, 0f, 390, 300)
        val nodes = AccessibilityOverlayNodes(listOf(stacked("original"), stacked("copy")), emptyList())

        val targets = accessibilityOverlayTargets(nodes, viewport, 48f, setOf("copy"))

        assertEquals(IntRect(96, 96, 144, 144), targets.components["copy"])
        val displaced = assertNotNull(targets.components["original"])
        assertEquals(48, displaced.width)
        assertEquals(48, displaced.height)
        assertDisjoint(targets.components.values)
        assertTrue(displaced.left >= 0 && displaced.top >= 0 && displaced.right <= 390 && displaced.bottom <= 300)
    }

    @Test
    fun displacedTargetsAvoidTheTerminalsOfTheSelectedComponent() {
        val viewport = CircuitAccessibilityViewport(1f, 0f, 0f, 390, 300)
        val pin = AccessiblePort(AccessiblePortId("copy", "in"), "in", GridPoint(72, 120), PortDirection.INPUT, 1, emptyList(), emptyList())
        val nodes = AccessibilityOverlayNodes(listOf(stacked("original"), stacked("copy")), listOf(pin))

        val targets = accessibilityOverlayTargets(nodes, viewport, 48f, setOf("copy"))

        assertEquals(IntRect(48, 96, 96, 144), targets.ports[pin.id])
        assertEquals(setOf("original", "copy"), targets.components.keys)
        assertDisjoint(targets.components.values + targets.ports.values)
    }

    @Test
    fun everyStackedComponentGetsATargetUntilTheCanvasIsFull() {
        val bounds = ComponentBounds(0, 0, 20, 20)
        val manyOnRoom = AccessibilityOverlayNodes((0 until 6).map { stacked("c$it", bounds) }, emptyList())

        val roomy = accessibilityOverlayTargets(manyOnRoom, CircuitAccessibilityViewport(1f, 0f, 0f, 390, 300), 48f, emptySet())
        assertEquals(6, roomy.components.size)
        assertDisjoint(roomy.components.values)

        val full = accessibilityOverlayTargets(manyOnRoom, CircuitAccessibilityViewport(1f, 0f, 0f, 96, 96), 48f, emptySet())
        assertEquals(4, full.components.size)
        assertDisjoint(full.components.values)
    }

    @Test
    fun denseCanvasTerminalActionsIncludeTheExistingActionsInTheNativeLimit() {
        assertFalse(needsCanvasTerminalChooser(portActions = 27, otherActions = 4))
        assertTrue(needsCanvasTerminalChooser(portActions = 28, otherActions = 4))
        assertFalse(needsCanvasTerminalChooser(portActions = 28, otherActions = 3))
        assertTrue(needsCanvasTerminalChooser(portActions = 64, otherActions = 2))
    }

    @Test
    fun highContrastSignalColorsPassGraphicalContrastAgainstTheCanvas() {
        val palette = CircuitSignalPalette.HighContrast
        for (color in listOf(palette.low, palette.high, palette.unknown, palette.error, palette.bus)) {
            assertTrue(circuitContrastRatio(color, palette.background) >= 3.0)
        }
        assertTrue(circuitContrastRatio(CircuitSignalPalette.Classic.high, CircuitSignalPalette.Classic.background) < 3.0)
        assertEquals(palette.error, palette.color(LogicVector.error(8)))
        assertEquals(palette.unknown, palette.color(LogicVector.unknown(8)))
        assertEquals(palette.bus, palette.color(LogicVector.of(8, 1uL)))
        assertEquals(palette.high, palette.color(LogicValue.HIGH))
    }
}
