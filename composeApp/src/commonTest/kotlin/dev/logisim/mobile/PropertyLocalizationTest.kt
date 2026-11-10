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
        assertEquals("Enabled", enabled.localizedValue().resolve(AppLanguage.ENGLISH))
        assertEquals("Ativado", enabled.localizedValue().resolve(AppLanguage.PORTUGUESE))
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
        assertEquals(Messages.InspectorValueInvalid, invalid.notice.key)
        assertEquals("Invalid value for width: $input.", invalid.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Valor inválido para width: $input.", invalid.notice.resolve(AppLanguage.PORTUGUESE))
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
