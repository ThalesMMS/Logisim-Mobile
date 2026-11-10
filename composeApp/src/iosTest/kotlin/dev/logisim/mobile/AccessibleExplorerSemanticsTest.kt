package dev.logisim.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentDefinition
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentLayout
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ComponentTerminal
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.PortDirection
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #328: the Explorer terminal and wire buttons carry their spoken label in `contentDescription`.
 * Compose iOS joins that description with merged child text, so the visible caption must not
 * contribute text; the description Android's TalkBack reads stays the same.
 */
@OptIn(ExperimentalTestApi::class)
class AccessibleExplorerSemanticsTest {
    private val kind = ComponentKind("TEST_EXPLORER_PIN")
    private val registry = ComponentRegistry(listOf(
        ComponentDefinition(kind, "Test pin", "#Test", layout = { component, _ -> ComponentLayout(
            listOf(ComponentTerminal("data", component.position,
                if (component.attributes["direction"] == "in") PortDirection.INPUT else PortDirection.OUTPUT, 1, "in")),
            ComponentBounds(component.position.x - 5, component.position.y - 5, component.position.x + 5, component.position.y + 5)) }),
    ))
    private val document = CircuitDocument(
        components = listOf(
            CircuitComponent("c4", kind, GridPoint(40, 0), "Y", mapOf("direction" to "in")),
            CircuitComponent("a", kind, GridPoint(0, 0), "A", mapOf("direction" to "out")),
        ),
        wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(40, 0))),
    )
    private val actions = CircuitAccessibilityActions({}, {}, { true }, { true }, {}, { true }, { _, _ -> true }, { _, _ -> true })

    private fun SemanticsNodeInteraction.assertSingleLabel(expected: String) {
        val config = fetchSemanticsNode().config
        assertEquals(listOf(expected), config.getOrNull(SemanticsProperties.ContentDescription))
        assertNull(config.getOrNull(SemanticsProperties.Text), "The visible caption must not repeat the label")
    }

    @Test
    fun terminalAndWireButtonsExposeTheirLabelOnce() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                AccessibleCircuitExplorer(document, SimulationState(), registry, emptySet(), emptySet(), actions)
            }
        }
        onNodeWithText("Terminals of Y").performScrollTo().performClick()
        val terminal = onNodeWithTag(AccessiblePortId("c4", "data").key).performScrollTo()
        terminal.assertSingleLabel("Start a connection at Y, in")
        val state = terminal.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertTrue(state.orEmpty().startsWith("Input in, 1 bit"), "The value keeps direction and width: $state")

        onNodeWithText("Wires").performScrollTo().performClick()
        val wire = onNodeWithTag("a11y-wire-0").performScrollTo()
        val description = wire.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(1, description?.size)
        assertEquals(true, description?.single()?.startsWith("Wire 1"))
        wire.assertSingleLabel(description!!.single())
    }
}
