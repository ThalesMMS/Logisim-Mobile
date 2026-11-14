package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.ProjectToolbar
import dev.logisim.mobile.core.ProjectToolbarItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W3-02 (C8): the tool-defaults dialog picks values as the inspector does and writes the same serialized strings. */
class ProjectToolDefaultsTest {
    private val registry = ComponentCatalog.registry
    private fun prototype(kind: ComponentKind) = CircuitComponent("p", kind, GridPoint(0, 0))

    @Test
    fun choosingNorthAndWideGivesTheStringsTypingWould() {
        val and = prototype(ComponentKind.AND)
        val model = ProjectToolDefaults(and, registry)
        val fields = model.fields(emptyMap())
        // The picker offers Evolution's names for the serialized values.
        val size = fields.single { it.name == "size" }
        assertEquals(listOf("30", "50", "70"), size.options.map { it.value })
        assertEquals(PropertyInputKind.DIRECTION, fields.single { it.name == "facing" }.kind)
        var draft = model.edit(emptyMap(), "facing", "north")
        draft = model.edit(draft, "size", "70")
        val picked = model.updates(draft)
        assertEquals(mapOf("facing" to "north", "size" to "70"), picked)
        // The text path: what the inspector's validation makes of the typed strings.
        val typed = listOf("facing" to "north", "size" to "70").flatMap { (name, raw) ->
            (PropertyEditorModel.validate(registry, listOf(and), name, raw) as PropertyEditResult.Valid).updates.entries
        }.associate { it.key to it.value }
        assertEquals(typed, picked)
        val item = ProjectToolbarItem.Tool("#Gates", "AND Gate")
        val project = CircuitProject(listOf(CircuitDocument("main")))
        assertEquals(CircCodec.writeProject(ProjectToolbar.apply(project, listOf(ProjectToolbar.withToolAttributes(item, typed)))),
            CircCodec.writeProject(ProjectToolbar.apply(project, listOf(ProjectToolbar.withToolAttributes(item, picked!!)))))
        // Choosing the original value again is no change.
        assertNull(model.updates(model.edit(model.edit(emptyMap(), "facing", "north"), "facing", "east")))
    }

    @Test
    fun gateNegationsFollowTheDraftedInputCount() {
        val model = ProjectToolDefaults(prototype(ComponentKind.AND), registry)
        val before = model.fields(emptyMap()).map { it.name }
        assertTrue(before.size <= 10, "$before")
        assertFalse("negate10" in before)
        val inputs = before.count { it.startsWith("negate") }
        val more = model.fields(mapOf("inputs" to "8")).count { it.name.startsWith("negate") }
        assertEquals(8, more, "Drafting 8 inputs offers their 8 negations ($inputs before)")
        assertEquals(mapOf("inputs" to "8", "negate7" to "true"), model.updates(mapOf("inputs" to "8", "negate7" to "true")))
    }

    @Test
    fun onlyToolsWithALabelOfferOne() {
        listOf(ComponentKind.SPLITTER, ComponentKind.TEXT).forEach { kind ->
            assertFalse(ProjectToolDefaults(prototype(kind), registry).fields(emptyMap()).any { it.name == "label" }, "$kind")
        }
        assertTrue(ProjectToolDefaults(prototype(IoKinds.Led), registry).fields(emptyMap()).any { it.name == "label" })
        assertEquals(PropertyInputKind.COLOR,
            ProjectToolDefaults(prototype(IoKinds.Led), registry).fields(emptyMap()).single { it.name == "color" }.kind)
    }

    @Test
    fun subcircuitFontIsAFontAndChecked() {
        val model = ProjectToolDefaults(CircuitComponent("p", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "half"), registry)
        val fields = model.fields(emptyMap()).associateBy { it.name }
        assertEquals(PropertyInputKind.FONT, fields.getValue("labelfont").kind)
        assertEquals(PropertyInputKind.BOOLEAN, fields.getValue("labelvisible").kind)
        assertEquals(mapOf("labelfont" to "Serif italic 12"), model.updates(mapOf("labelfont" to "Serif italic 12")))
        assertNull(model.updates(mapOf("labelfont" to "big")))
    }
}
