package dev.logisim.mobile

import dev.logisim.mobile.core.AttributeKey
import dev.logisim.mobile.core.AttributeSchema
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentDefinition
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentLayout
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.Direction
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PropertyEditorModelTest {
    private val kind = ComponentKind("test.properties")
    private val width = AttributeKey.int("width", 8, 1..16)
    private val enabled = AttributeKey.boolean("enabled", true)
    private val direction = AttributeKey.direction(default = Direction.NORTH)
    private val gain = AttributeKey.double("gain", 0.5) { it.isFinite() && it > 0.0 }
    private val note = AttributeKey.text("note", "hello")
    private val registry = ComponentRegistry(listOf(ComponentDefinition(kind, "Properties", "#Test",
        schema = AttributeSchema(listOf(width, enabled, direction, gain, note)),
        layout = { component, _ -> ComponentLayout(emptyList(), ComponentBounds(component.position.x, component.position.y, component.position.x + 40, component.position.y + 40)) })))

    private fun component(id: String = "c1", attributes: Map<String, String> = emptyMap()) =
        CircuitComponent(id, kind, GridPoint(100, 100), attributes = attributes)

    @Test
    fun fieldsUseDeclaredTypesAndDefaults() {
        val fields = PropertyEditorModel.fields(registry, listOf(component()))
        assertEquals(listOf("width", "enabled", "facing", "gain", "note"), fields.map { it.name })
        // A range of at most 32 values is a choice, as Evolution's integer-range combo box (C6).
        assertEquals(listOf(PropertyInputKind.CHOICE, PropertyInputKind.BOOLEAN, PropertyInputKind.DIRECTION,
            PropertyInputKind.DECIMAL, PropertyInputKind.TEXT), fields.map { it.kind })
        assertEquals((1..16).map(Int::toString), fields.first().options.map { it.value })
        assertEquals(listOf("8", "true", "north", "0.5", "hello"), fields.map { it.value })
    }

    @Test
    fun mixedValuesAreExplicitAndUnknownAttributesRemainReadOnly() {
        val fields = PropertyEditorModel.fields(registry, listOf(
            component(attributes = mapOf("width" to "4", "future" to "keep")),
            component("c2", mapOf("width" to "8", "future" to "keep")),
        ))
        assertTrue(fields.single { it.name == "width" }.mixed)
        assertEquals(PropertyEditorField("future", PropertyInputKind.TEXT, "keep", true), fields.single { it.name == "future" })
    }

    @Test
    fun editedNumbersAreNormalizedBeforeApplying() {
        val result = PropertyEditorModel.validate(registry, listOf(component()), "width", "08")
        assertEquals(PropertyEditResult.Valid(mapOf("width" to "8")), result)
    }

    @Test
    fun invalidValuesReportTheSpecificProperty() {
        val result = assertIs<PropertyEditResult.Invalid>(
            PropertyEditorModel.validate(registry, listOf(component()), "width", "17"))
        // The message names the attribute as the table shows it, with the accepted range (C150, C6).
        assertTrue(result.message.contains("Bits de dados"))
        assertTrue(result.message.contains("17"))
        assertTrue(result.message.contains("1 a 16"))
    }

    @Test
    fun multiSelectionValidatesAgainstEveryComponentSchema() {
        val narrow = ComponentKind("test.narrow")
        val both = registry.withAdditional(listOf(ComponentDefinition(narrow, "Narrow", "#Test",
            schema = AttributeSchema(listOf(AttributeKey.int("width", 4, 1..4))),
            layout = { component, _ -> ComponentLayout(emptyList(), ComponentBounds(component.position.x, component.position.y, component.position.x + 20, component.position.y + 20)) })))
        val selected = listOf(component(), CircuitComponent("c2", narrow, GridPoint(200, 100)))
        assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(both, selected, "width", "8"))
        assertEquals(PropertyEditResult.Valid(mapOf("width" to "4")), PropertyEditorModel.validate(both, selected, "width", "4"))
        assertEquals(listOf("width"), PropertyEditorModel.fields(both, selected).filterNot { it.readOnly }.map { it.name })
    }

    @Test
    fun unregisteredTypesAreReportedWithoutInventingEditableProperties() {
        val missing = CircuitComponent("missing", ComponentKind("unknown"), GridPoint(0, 0), attributes = mapOf("width" to "8"))
        assertEquals(listOf("unknown"), PropertyEditorModel.unregisteredKinds(registry, listOf(missing)))
        assertTrue(PropertyEditorModel.fields(registry, listOf(missing)).all { it.readOnly })
        assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(missing), "width", "4"))
    }

    @Test
    fun editingKnownPropertiesPreservesUnknownValues() {
        val original = component(attributes = mapOf("future" to "opaque", "width" to "8"))
        val update = assertIs<PropertyEditResult.Valid>(PropertyEditorModel.validate(registry, listOf(original), "width", "4"))
        val changed = registry.reconfigure(original, update.updates)
        assertEquals(mapOf("future" to "opaque", "width" to "4"), changed.attributes)
        assertEquals(mapOf("future" to "opaque", "width" to "8"), original.attributes)
    }

    @Test
    fun aParserFailureIsReportedAsAnInvalidFieldInsteadOfEscaping() {
        val throwing = AttributeKey("custom", 0, { raw -> raw.toInt() }, Int::toString)
        val special = ComponentRegistry(listOf(ComponentDefinition(kind, "Custom", "#Test",
            schema = AttributeSchema(listOf(throwing)))))
        val result = assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(special, listOf(component()), "custom", "word"))
        assertEquals("Valor inválido para custom: word.", result.message)
    }

    @Test
    fun layoutConstraintsAreCheckedBeforeTheEditIsAccepted() {
        val constrained = ComponentRegistry(listOf(ComponentDefinition(kind, "Constrained", "#Test",
            schema = AttributeSchema(listOf(width)), layout = { _, attributes ->
                require(attributes[width] != 13) { "width 13 has no terminal layout" }
                ComponentLayout(emptyList(), ComponentBounds(0, 0, 40, 40))
            })))
        val result = assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(constrained, listOf(component()), "width", "13"))
        assertEquals("width 13 has no terminal layout", result.message)
    }
}
