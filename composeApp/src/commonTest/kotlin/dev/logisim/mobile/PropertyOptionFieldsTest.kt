package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WiringLibrary
import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Option attributes (Evolution's Attributes.forOption) reach the inspector as CHOICE fields with their accepted
 * values and display names, so the user picks a value instead of typing the serialized one.
 */
class PropertyOptionFieldsTest {
    private val registry = ComponentCatalog.registry
    private fun pin(id: String, attributes: Map<String, String> = emptyMap()) =
        registry.create(ComponentKind.INPUT, id, GridPoint(100, 100), attributes, id)

    private fun PropertyEditorField.labels(language: AppLanguage) = options.map { it.label.resolve(language) }

    @Test
    fun labelLocationIsAChoiceOfItsFiveValuesWithLocalizedNames() {
        val field = PropertyEditorModel.fields(registry, listOf(pin("p", mapOf("labelloc" to "west")))).single { it.name == "labelloc" }
        assertEquals(PropertyInputKind.CHOICE, field.kind)
        assertEquals(setOf("north", "south", "east", "west", "center"), field.options.map { it.value }.toSet())
        assertEquals("west", field.value)
        assertEquals("Oeste", field.localizedValue().resolve(AppLanguage.PORTUGUESE))
        assertEquals("West", field.localizedValue().resolve(AppLanguage.ENGLISH))
        assertTrue("Centro" in field.labels(AppLanguage.PORTUGUESE))
    }

    @Test
    fun everyOptionAttributeOfThePinIsAChoiceAndKeepsItsSerializedValue() {
        val fields = PropertyEditorModel.fields(registry, listOf(pin("p"))).associateBy { it.name }
        for (name in listOf("type", "behavior", "labelloc", "radix", "appearance")) {
            val field = fields.getValue(name)
            assertEquals(PropertyInputKind.CHOICE, field.kind, name)
            assertTrue(field.value in field.options.map { it.value }, name)
        }
        assertEquals(listOf("Simples", "Três estados", "Pull-up", "Pull-down"), fields.getValue("behavior").labels(AppLanguage.PORTUGUESE))
        assertEquals("Signed Decimal", fields.getValue("radix").options.single { it.value == "10signed" }.label.resolve(AppLanguage.ENGLISH))
        // Choosing still validates and stores the serialized value.
        val valid = assertIs<PropertyEditResult.Valid>(PropertyEditorModel.validate(registry, listOf(pin("p")), "radix", "10signed"))
        assertEquals(mapOf("radix" to "10signed"), valid.updates)
        assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(pin("p")), "radix", "Signed Decimal"))
    }

    @Test
    fun gateSizesAreTheSizesTheGateAcceptsWithEvolutionsNames() {
        val and = registry.create(ComponentKind.AND, "a", GridPoint(100, 100))
        val not = registry.create(ComponentKind.NOT, "n", GridPoint(200, 100))
        val andSize = PropertyEditorModel.fields(registry, listOf(and)).single { it.name == "size" }
        val notSize = PropertyEditorModel.fields(registry, listOf(not)).single { it.name == "size" }
        assertEquals(listOf("30", "50", "70"), andSize.options.map { it.value })
        assertEquals(listOf("Narrow", "Medium", "Wide"), andSize.labels(AppLanguage.ENGLISH))
        // 30 is Wide on a NOT gate, as in Evolution.
        assertEquals(listOf("20", "30"), notSize.options.map { it.value })
        assertEquals(listOf("Narrow", "Wide"), notSize.labels(AppLanguage.ENGLISH))
        // The two gates accept different sizes, so a selection of both cannot set one for all.
        assertNull(PropertyEditorModel.fields(registry, listOf(and, not)).firstOrNull { it.name == "size" && !it.readOnly })
    }

    @Test
    fun multiSelectionOffersAnOptionOnlyWhenEverySchemaAcceptsTheSameValues() {
        val pins = PropertyEditorModel.fields(registry, listOf(pin("a", mapOf("labelloc" to "west")), pin("b", mapOf("labelloc" to "north"))))
        val location = pins.single { it.name == "labelloc" }
        assertTrue(location.mixed)
        assertEquals(5, location.options.size)
        // A pin's "type" (input/output) and a transistor's "type" (P/N) share a name but not their values.
        val transistor = registry.create(WiringLibrary.Transistor, "t", GridPoint(300, 100))
        assertNull(PropertyEditorModel.fields(registry, listOf(pin("a"), transistor)).firstOrNull { it.name == "type" && !it.readOnly })
    }

    @Test
    fun gatesListNegationsOnlyForTheirInputsWithEvolutionsNames() {
        val and = registry.create(ComponentKind.AND, "a", GridPoint(100, 100), mapOf("inputs" to "3"))
        val fields = PropertyEditorModel.fields(registry, listOf(and))
        assertEquals(listOf("negate0", "negate1", "negate2"), fields.map { it.name }.filter { it.startsWith("negate") })
        assertEquals("Negar 1", dev.logisim.mobile.core.localization.localizedAttributeName("negate0").resolve(AppLanguage.PORTUGUESE))
        assertEquals("Negate 3", dev.logisim.mobile.core.localization.localizedAttributeName("negate2").resolve(AppLanguage.ENGLISH))
        // A stored negation beyond the inputs stays in the file but is not offered.
        val stale = and.copy(attributes = and.attributes + ("negate5" to "true"))
        assertTrue(PropertyEditorModel.fields(registry, listOf(stale)).none { it.name == "negate5" })
    }

    @Test
    fun splitterBitsAreChoicesOfNoneOrAnOutputUpToTheIncomingWidth() {
        val splitter = registry.create(ComponentKind.SPLITTER, "s", GridPoint(100, 100), mapOf("incoming" to "4", "fanout" to "2"))
        val bits = PropertyEditorModel.fields(registry, listOf(splitter)).filter { it.name.startsWith("bit") && it.name.drop(3).all(Char::isDigit) }
        assertEquals(listOf("bit0", "bit1", "bit2", "bit3"), bits.map { it.name })
        assertTrue(bits.all { it.kind == PropertyInputKind.CHOICE })
        // The default distribution sends bits 0-1 to output 0 and bits 2-3 to output 1; the editor shows it.
        assertEquals(listOf("0", "0", "1", "1"), bits.map { it.value })
        assertEquals(listOf("none", "0", "1"), bits.first().options.map { it.value })
        assertEquals(listOf("Nenhum", "Saída 0", "Saída 1"), bits.first().labels(AppLanguage.PORTUGUESE))
        assertIs<PropertyEditResult.Valid>(PropertyEditorModel.validate(registry, listOf(splitter), "bit3", "none"))
        assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(splitter), "bit3", "7"))
    }

    @Test
    fun memoriesListTheAttributesEvolutionShowsForEachKindAndMode() {
        val rom = registry.create(ComponentKind.ROM, "rom", GridPoint(100, 100))
        val romNames = PropertyEditorModel.fields(registry, listOf(rom)).filterNot { it.readOnly }.map { it.name }
        assertTrue(listOf("enables", "trigger", "databus", "asyncread", "clearpin", "type").none { it in romNames }, romNames.toString())
        val ram = registry.create(ComponentKind.RAM, "ram", GridPoint(100, 100), mapOf("enables" to "byte", "dataWidth" to "16"))
        val byteNames = PropertyEditorModel.fields(registry, listOf(ram)).filterNot { it.readOnly }.map { it.name }
        assertTrue(listOf("trigger", "asyncread", "readbehav", "byteenables").all { it in byteNames }, byteNames.toString())
        assertTrue("line" !in byteNames)
        val lines = PropertyEditorModel.fields(registry, listOf(ram.copy(attributes = ram.attributes + ("enables" to "line"))))
            .filterNot { it.readOnly }.map { it.name }
        assertTrue("line" in lines && "misaligned" in lines && "byteenables" !in lines, lines.toString())
    }
}
