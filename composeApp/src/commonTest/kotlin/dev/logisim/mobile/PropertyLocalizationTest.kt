package dev.logisim.mobile

import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PropertyLocalizationTest {
    @Test
    fun labelsAndBooleanDirectionValuesTranslateWithoutChangingSerializedData() {
        val direction = PropertyEditorField("facing", PropertyInputKind.DIRECTION, "north")
        assertEquals("Facing", localizedAttributeName(direction.name).resolve(AppLanguage.ENGLISH))
        assertEquals("Direção", localizedAttributeName(direction.name).resolve(AppLanguage.PORTUGUESE))
        assertEquals("North", direction.localizedValue().resolve(AppLanguage.ENGLISH))
        assertEquals("Norte", direction.localizedValue().resolve(AppLanguage.PORTUGUESE))
        assertEquals("north", direction.value)
        val enabled = PropertyEditorField("enabled", PropertyInputKind.BOOLEAN, "true")
        // Evolution's booleanTrueOption: Yes, Sim.
        assertEquals("Yes", enabled.localizedValue().resolve(AppLanguage.ENGLISH))
        assertEquals("Sim", enabled.localizedValue().resolve(AppLanguage.PORTUGUESE))
        assertEquals("true", enabled.value)
    }

    @Test
    fun invalidPropertyMessagesRemainStructuredAndKeepUserInputLiteral() {
        val kind = ComponentKind("test.localized")
        val registry = ComponentRegistry(listOf(ComponentDefinition(kind, "Test", "#Test",
            schema = AttributeSchema(listOf(AttributeKey.int("width", 8, 1..64))))))
        val input = "{name} → שלום " + "long ".repeat(40)
        val invalid = assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry,
            listOf(CircuitComponent("a", kind, GridPoint(0, 0))), "width", input))
        // The message names the attribute as the table shows it and says which values it accepts.
        assertEquals("inspector_value_invalid_range", invalid.notice.key)
        assertEquals("Invalid value for Data bits: $input. Enter a whole number from 1 to 64.", invalid.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Valor inválido para Bits de dados: $input. Digite um número inteiro de 1 a 64.", invalid.notice.resolve(AppLanguage.PORTUGUESE))
        val unbounded = ComponentRegistry(listOf(ComponentDefinition(kind, "Test", "#Test",
            schema = AttributeSchema(listOf(AttributeKey.text("width", "8") { it.length < 3 })))))
        val plain = assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(unbounded,
            listOf(CircuitComponent("a", kind, GridPoint(0, 0))), "width", "1234"))
        assertEquals(Messages.InspectorValueInvalid, plain.notice.key)
        assertEquals("Invalid value for Data bits: 1234.", plain.notice.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun unknownFieldsAndTextValuesRemainUserData() {
        val raw = "Custom {name} → שלום"
        assertEquals(raw, localizedAttributeName(raw).resolve(AppLanguage.ENGLISH))
        assertEquals(raw, localizedAttributeName(raw).resolve(AppLanguage.PORTUGUESE))
        assertEquals("raw_invalid", localizedAttributeName("raw_invalid").resolve(AppLanguage.ENGLISH))
        assertEquals("value_invalid", localizedAttributeName("value_invalid").resolve(AppLanguage.PORTUGUESE))
        val field = PropertyEditorField("note", PropertyInputKind.TEXT, "north")
        assertEquals("north", field.localizedValue().resolve(AppLanguage.PORTUGUESE))
        assertTrue(PropertyEditorField("width", PropertyInputKind.INTEGER, null).mixed)
        assertEquals("Different values", PropertyEditorField("width", PropertyInputKind.INTEGER, null)
            .localizedValue().resolve(AppLanguage.ENGLISH))
    }
}
