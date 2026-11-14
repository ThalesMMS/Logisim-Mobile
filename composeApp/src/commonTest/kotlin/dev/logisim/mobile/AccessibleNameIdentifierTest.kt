package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C160: TalkBack names components by label, or kind and position; the internal id is only a last-resort tiebreak. */
class AccessibleNameIdentifierTest {
    private val registry = ComponentCatalog.registry

    private fun graph(vararg components: CircuitComponent) =
        CircuitAccessibilityGraph(CircuitDocument(components = components.toList()), registry)

    @Test
    fun anUnlabeledGateIsNamedByKindAndPositionWithoutItsId() {
        val graph = graph(CircuitComponent("c12", ComponentKind.AND, GridPoint(120, 80)))
        val component = graph.component("c12")!!
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.PORTUGUESE)) {
            val spoken = component.nameText.resolve(language)
            assertFalse("c12" in spoken, "$language: $spoken")
            assertTrue(spoken.endsWith("(120, 80)"), "$language: $spoken")
        }
        assertFalse("c12" in component.name, component.name)
        assertFalse("c12" in graph.connectedPortText(component.ports.first().id)!!.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun duplicateLabelsAreToldApartByPositionAndOnlyStackedOnesByTheirId() {
        val graph = graph(
            CircuitComponent("c1", ComponentKind.AND, GridPoint(40, 40), "A"),
            CircuitComponent("c2", ComponentKind.AND, GridPoint(200, 40), "A"),
            CircuitComponent("c3", ComponentKind.OR, GridPoint(300, 300)),
            CircuitComponent("c4", ComponentKind.OR, GridPoint(300, 300)),
        )
        assertEquals("A (40, 40)", graph.component("c1")!!.name)
        assertEquals("A (200, 40)", graph.component("c2")!!.name)
        // Two unlabeled OR gates stacked on one point cannot be told apart otherwise.
        val stacked = listOf("c3", "c4").map { graph.component(it)!!.nameText.resolve(AppLanguage.ENGLISH) }
        assertEquals(2, stacked.distinct().size, stacked.toString())
        assertTrue(stacked[0].contains("c3") && stacked[1].contains("c4"), stacked.toString())
    }
}
