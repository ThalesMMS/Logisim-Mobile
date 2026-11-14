package dev.logisim.mobile

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.logisim.mobile.core.AnnotationFont
import dev.logisim.mobile.core.AttributeKey
import dev.logisim.mobile.core.AttributeSchema
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ExternalIoComponents
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentDefinition
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SocComponents
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W3-01: inspector values, labels, keyboards and choices follow Evolution; stored values never change. */
class InspectorValuesTest {
    private val registry = ComponentCatalog.registry
    private val missing = "\u0000missing"

    /** Whether [text] comes from the message catalog in [language], rather than a fallback. */
    private fun cataloged(text: LocalizedText, language: AppLanguage) = text.copy(fallback = missing).resolve(language) != missing

    private fun component(definition: ComponentDefinition, attributes: Map<String, String> = emptyMap(), id: String = "c") =
        CircuitComponent(id, definition.kind, GridPoint(100, 100), attributes = definition.defaultAttributes + attributes)

    private fun component(kind: ComponentKind, attributes: Map<String, String> = emptyMap(), id: String = "c", label: String = "") =
        component(registry.requireDefinition(kind), attributes, id).copy(label = label)

    private fun definition(fileName: String) = registry.definitions.first { it.fileName == fileName }

    private fun field(fileName: String, name: String, attributes: Map<String, String> = emptyMap()) =
        PropertyEditorModel.fields(registry, listOf(component(definition(fileName), attributes))).single { it.name == name }

    @Test
    fun everyCatalogOptionHasADisplayName() {
        val gaps = mutableListOf<String>()
        for (definition in registry.definitions) {
            val fields = runCatching { PropertyEditorModel.fields(registry, listOf(component(definition))) }.getOrNull() ?: continue
            for (field in fields.filter { !it.readOnly && it.options.isNotEmpty() }) {
                for (option in field.options) {
                    // A number is its own display name, as in Evolution's integer combo boxes.
                    val number = option.value.toLongOrNull() != null && option.label.resolve(AppLanguage.ENGLISH) == option.value
                    if (number) continue
                    for (language in listOf(AppLanguage.ENGLISH, AppLanguage.PORTUGUESE)) {
                        if (!cataloged(option.label, language)) gaps += "${definition.fileName}.${field.name}=${option.value} ($language)"
                    }
                }
                // The field keeps the serialized token; only its presentation is translated.
                assertTrue(field.value == null || field.options.any { it.value == field.value } || field.kind != PropertyInputKind.CHOICE,
                    "${definition.fileName}.${field.name} shows ${field.value}")
            }
        }
        assertEquals(emptyList(), gaps)
        fun key(fileName: String, name: String) = definition(fileName).schema.keys.single { it.name == name }
        assertEquals("Signed Decimal", key("Probe", "radix").optionLabel("10signed").resolve(AppLanguage.ENGLISH))
        val byteEnables = key("RAM", "byteenables")
        assertEquals("Whole word read/write only", byteEnables.optionLabel("NobyteEnables").resolve(AppLanguage.ENGLISH))
        assertEquals("NobyteEnables", byteEnables.format(byteEnables.default))
    }

    @Test
    fun booleansReadYesAndNo() {
        val negate = field("AND Gate", "negate0")
        assertEquals(PropertyInputKind.BOOLEAN, negate.kind)
        assertEquals("false", negate.value)
        assertEquals("No", negate.localizedValue().resolve(AppLanguage.ENGLISH))
        assertEquals("Não", negate.localizedValue().resolve(AppLanguage.PORTUGUESE))
        assertEquals(listOf("Sim", "Não"), PropertyEditorModel.standardOptions(PropertyInputKind.BOOLEAN).map { it.label.resolve(AppLanguage.PORTUGUESE) })
        assertEquals("Ja", negate.copy(value = "true").localizedValue().resolve(AppLanguage.GERMAN))
    }

    @Test
    fun everySchemaKeyHasAComponentAppropriateLabel() {
        val gaps = mutableListOf<String>()
        for (definition in registry.definitions) for (key in definition.schema.keys) {
            for (language in listOf(AppLanguage.ENGLISH, AppLanguage.PORTUGUESE)) {
                if (!cataloged(key.displayName(), language)) gaps += "${definition.fileName}.${key.name} ($language)"
                // The label is a name for people, never the raw XML name ("labelfont", "nState"), except where the
                // XML name is already the word people read. A capitalized word ("Facing" for facing) is a label.
                val shown = key.displayName().resolve(language)
                if (shown == key.name && key.name !in LABELS_EQUAL_TO_XML_NAMES) {
                    gaps += "${definition.fileName}.${key.name} ($language) reads '$shown'"
                }
            }
        }
        assertEquals(emptyList(), gaps)
        fun label(fileName: String, name: String) = definition(fileName).schema.keys.single { it.name == name }.displayName().resolve(AppLanguage.ENGLISH)
        assertEquals("Color model", label("RGB Video", "color"))
        assertEquals("Number of pins", label("PortIO", "number"))
        assertNotEquals(label("LED", "color"), label("Button", "color"))
        // Evolution names the multiplexer's size attribute with gateSizeAttr ("Gate Size") too.
        assertEquals(label("AND Gate", "size"), label("Multiplexer", "size"))
    }

    @Test
    fun internalAttributesOfAnOriginalProjectStayInTheFileButNotInTheTable() {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<project source="2.7.1" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="4" loc="(300,100)" name="RAM"/>
    <comp lib="1" loc="(100,300)" name="Controlled Buffer"/>
  </circuit>
</project>"""
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
        val before = CircCodec.writeProject(project, registry)
        val components = project.mainCircuit.components
        assertTrue(components.any { component -> component.attributes.keys.any { it.startsWith("logisim-mobile-") } },
            "The 2.7.1 RAM keeps its original-format marker")
        for (component in components) {
            val names = PropertyEditorModel.fields(registry, listOf(component)).map { it.name }
            assertTrue(names.none { it.startsWith("logisim-mobile-") }, "$names")
        }
        assertEquals(before, CircCodec.writeProject(project, registry))
    }

    @Test
    fun invalidValuesNameTheShownAttributeAndTheAllowedRange() {
        val pin = component(definition("Pin"))
        val invalid = assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(pin), "width", "70"))
        assertEquals("Invalid value for Data bits: 70. Enter a whole number from 1 to 64.", invalid.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Valor inválido para Bits de dados: 70. Digite um número inteiro de 1 a 64.", invalid.notice.resolve(AppLanguage.PORTUGUESE))
        val labelloc = assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(pin), "labelloc", "nort"))
        assertEquals("Invalid value for Label location: nort.", labelloc.notice.resolve(AppLanguage.ENGLISH))
        val readOnly = assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(pin), "nonexistent", "4"))
        assertEquals("Property nonexistent cannot be edited in this selection.", readOnly.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("A whole number from 1 to 64.", field("Pin", "width").hint?.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun keyboardsFollowWhatEachFieldAccepts() {
        val labelFont = field("Pin", "labelfont")
        assertEquals(PropertyInputKind.FONT, labelFont.kind)
        assertFalse(labelFont.multiline)
        assertEquals(ImeAction.Done, keyboardOptionsFor(labelFont).imeAction)
        assertEquals(KeyboardType.Ascii, keyboardOptionsFor(field("NE555", "r1")).keyboardType)
        assertEquals(KeyboardType.Ascii, keyboardOptionsFor(field("NE555", "c1")).keyboardType)
        val initial = keyboardOptionsFor(field("Pin", "initial"))
        assertEquals(KeyboardType.Ascii, initial.keyboardType)
        assertEquals(false, initial.autoCorrectEnabled)
        // Signed values need a minus key, which digit pads often lack.
        assertEquals(KeyboardType.Ascii, keyboardOptionsFor(field("Random", "seed")).keyboardType)
        assertEquals(KeyboardType.Ascii, keyboardOptionsFor(field("SocJtagUart", "WriteThreshold")).keyboardType)
        assertEquals(KeyboardType.Ascii, keyboardOptionsFor(field("FPConstant", "value")).keyboardType)
        assertEquals(KeyboardType.Number, keyboardOptionsFor(field("Pin", "width")).keyboardType)
        assertEquals(ImeAction.Default, keyboardOptionsFor(field("RAM", "contents")).imeAction)
        assertEquals(KeyboardType.Ascii, keyboardOptionsFor(field("DipSwitch", "positions")).keyboardType)
    }

    @Test
    fun typedValuesStartSelectedAndUnitsAreAccepted() {
        val edit = PropertyValueEdit(field("Pin", "labelfont"))
        assertEquals(TextRange(0, "SansSerif bold 16".length), edit.value.selection)
        val timer = component(definition("NE555"))
        assertEquals(PropertyEditResult.Valid(mapOf("r1" to "4700")), PropertyEditorModel.validate(registry, listOf(timer), "r1", "4.7k"))
        assertEquals(PropertyEditResult.Valid(mapOf("c1" to "10")), PropertyEditorModel.validate(registry, listOf(timer), "c1", "10uF"))
        assertEquals("Ohms. You can type k or M, such as 4.7k or 1M.", field("NE555", "r1").hint?.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun dipPositionsShowTheirPrefixWithoutStoringIt() {
        val positions = field("DipSwitch", "positions", mapOf("positions" to "ff"))
        assertEquals("ff", positions.value)
        assertEquals("0xff", positions.localizedValue().resolve(AppLanguage.ENGLISH))
        val dip = component(definition("DipSwitch"))
        assertEquals(PropertyEditResult.Valid(mapOf("positions" to "10")), PropertyEditorModel.validate(registry, listOf(dip), "positions", "0x10"))
    }

    @Test
    fun discreteNumbersAreChoices() {
        val and = field("AND Gate", "size")
        assertEquals(PropertyInputKind.CHOICE, and.kind)
        assertEquals(listOf("30", "50", "70"), and.options.map { it.value })
        assertEquals(listOf("Narrow", "Medium", "Wide"), and.options.map { it.label.resolve(AppLanguage.ENGLISH) })
        assertEquals(listOf("20", "30"), field("NOT Gate", "size").options.map { it.value })
        for (name in listOf("fpwidth")) assertEquals(listOf("8", "16", "32", "64"), field("FPAdder", name).options.map { it.value })
        assertEquals(listOf("8", "16", "32", "64"), field("FPToFP", "fpwidthin").options.map { it.value })
        assertEquals(listOf("8", "16", "32", "64"), field("FPToFP", "fpwidthout").options.map { it.value })
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 64, 128, 256).map(Int::toString), field("SocDma", "BurstSize").options.map { it.value })
        assertEquals((3..15).map { (1 shl it).toString() }, field("SocJtagUart", "WriteFifoSize").options.map { it.value })
        // Evolution's integer ranges of at most 32 values are combo boxes.
        assertEquals((1..8).map(Int::toString), field("Multiplexer", "select").options.map { it.value })
        assertEquals((2..5).map(Int::toString), field("Joystick", "bits").options.map { it.value })
        assertEquals((1..3).map(Int::toString), field("Buzzer", "channel").options.map { it.value })
        assertEquals((4..35).map(Int::toString), field("Digital Oscilloscope", "nState").options.map { it.value })
        assertEquals((10..26).map(Int::toString), field("Socmem", "MemSize").options.map { it.value })
        // Wider ranges keep typed entry with the common bit widths as presets.
        val width = field("Pin", "width")
        assertEquals(PropertyInputKind.INTEGER, width.kind)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "16", "24", "32", "64"), width.presets.map { it.value })
        val tooWide = assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(component(definition("Pin"))), "width", "70"))
        assertTrue("64" in tooWide.notice.resolve(AppLanguage.ENGLISH))
        val select = assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(component(definition("Multiplexer"))), "select", "9"))
        assertTrue("1 to 8" in select.notice.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun choosingWideWritesTheSameSizeAttribute() {
        val state = EditorState("""<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="1" loc="(200,100)" name="AND Gate"><a name="label" val="G"/></comp>
  </circuit>
</project>""")
        val gate = state.document.components.single()
        state.select(gate.id)
        val session = PropertyInspectorSession(state, state.propertyInspectorContext())
        val size = PropertyEditorModel.fields(registry, session.context.components).single { it.name == "size" }
        assertTrue(session.applyOption(state, session, size, size.options.single { it.label.resolve(AppLanguage.ENGLISH) == "Wide" }.value))
        assertTrue(CircCodec.writeProject(state.project, state.componentRegistry).contains("""<a name="size" val="70"/>"""))
    }

    @Test
    fun aMultiSelectionOffersTheValuesEveryRangeAccepts() {
        val pin = component(definition("Pin"), id = "p")
        val slider = component(definition("Slider"), id = "s")
        val width = PropertyEditorModel.fields(registry, listOf(pin, slider)).single { it.name == "width" }
        assertEquals(PropertyInputKind.CHOICE, width.kind)
        assertEquals((1..8).map(Int::toString), width.options.map { it.value })
    }

    @Test
    fun labelFontsAreValidatedInTheEditorOnly() {
        assertEquals("SansSerif bold 16", AnnotationFont("SansSerif", 16, bold = true).serialized)
        for (raw in listOf("SansSerif plain 10", "SansSerif bold 16")) assertEquals(raw, AnnotationFont.parse(raw)!!.serialized)
        val pin = component(definition("Pin"))
        assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(pin), "labelfont", "Arial 20pt"))
        assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(pin), "labelfont", "SansSerif Bold16"))
        assertEquals(PropertyEditResult.Valid(mapOf("labelfont" to "SansSerif bold 20")),
            PropertyEditorModel.validate(registry, listOf(pin), "labelfont", "SansSerif bold 20"))
        // Reading a file stays lenient: an unreadable font is kept as it is.
        val stored = component(definition("Pin"), mapOf("labelfont" to "Arial 20pt"))
        assertEquals("Arial 20pt", PropertyEditorModel.fields(registry, listOf(stored)).single { it.name == "labelfont" }.value)
    }

    @Test
    fun socComponentsChooseABusOfTheirCircuit() {
        val main = component(SocComponents.BUS, mapOf("SocBusIdentifier" to "bus-a"), id = "b1", label = "Main")
        val other = component(SocComponents.BUS, mapOf("SocBusIdentifier" to "bus-b"), id = "b2").copy(position = GridPoint(20, 400))
        val blank = component(SocComponents.BUS, id = "b3")
        assertTrue(PropertyEditorModel.fields(registry, listOf(main)).none { it.name == "SocBusIdentifier" })
        val memory = component(SocComponents.MEMORY, mapOf("SocBusSelection" to "bus-a"), id = "m")
        val circuit = listOf(main, other, blank)
        val selection = PropertyEditorModel.fields(registry, listOf(memory), circuit).single { it.name == "SocBusSelection" }
        assertEquals(PropertyInputKind.CHOICE, selection.kind)
        assertEquals(listOf("", "bus-a", "bus-b", "b3"), selection.options.map { it.value })
        assertEquals(listOf("Not connected to a bus", "Main", "SoC Bus@20,400", "SoC Bus@100,100"),
            selection.options.map { it.label.resolve(AppLanguage.ENGLISH) })
        assertEquals("Main", selection.localizedValue().resolve(AppLanguage.ENGLISH))
        assertEquals(PropertyEditResult.Valid(mapOf("SocBusSelection" to "bus-b")),
            PropertyEditorModel.validate(registry, listOf(memory), "SocBusSelection", "bus-b", circuit = circuit))
        assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(memory), "SocBusSelection", "typo", circuit = circuit))
        val dma = component(SocComponents.DMA, mapOf("DmaSrcBus" to "gone"), id = "d")
        val source = PropertyEditorModel.fields(registry, listOf(dma), circuit).single { it.name == "DmaSrcBus" }
        // A reference to a bus that is no longer in the circuit stays shown and kept.
        assertEquals("gone", source.localizedValue().resolve(AppLanguage.ENGLISH))
        assertTrue(PropertyEditorModel.fields(registry, listOf(dma), circuit).single { it.name == "DmaDstBus" }.options.size == 4)
    }

    // Round 2 of the W3-01 review.

    private val portableBusIdentifier = Regex("0x[0-9A-F]{16}[0-9a-f]+")

    private fun socProject(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Soc" name="8"/>
  <main name="main"/>
  <circuit name="main">
$body
  </circuit>
</project>"""

    @Test
    fun aBusPlacedOnMobileIsChosenByAnIdentifierEvolutionReads() {
        val state = EditorState(socProject(""))
        assertTrue(state.insertComponent(SocComponents.BUS, GridPoint(20, 20)))
        assertTrue(state.insertComponent(SocComponents.BUS, GridPoint(20, 1200)))
        assertTrue(state.insertComponent(SocComponents.MEMORY, GridPoint(20, 600)))
        val buses = state.document.components.filter { it.kind == SocComponents.BUS }
        val identifiers = buses.map { it.attributes["SocBusIdentifier"].orEmpty() }
        // Evolution's SocBusAttributes gives each new bus "0x" + its creation time + a suffix; it never reads mobile ids.
        for (identifier in identifiers) assertTrue(portableBusIdentifier.matches(identifier), identifier)
        assertEquals(2, identifiers.distinct().size)
        val memory = state.document.components.single { it.kind == SocComponents.MEMORY }
        state.select(memory.id)
        val session = PropertyInspectorSession(state, state.propertyInspectorContext())
        val selection = PropertyEditorModel.fields(registry, session.context.components, session.context.socBuses)
            .single { it.name == "SocBusSelection" }
        val first = selection.options.single { it.label.resolve(AppLanguage.ENGLISH) == "SoC Bus@20,20" }
        assertEquals(identifiers.first(), first.value)
        assertTrue(session.applyOption(state, session, selection, first.value))
        val chosen = state.document.components.single { it.kind == SocComponents.MEMORY }.attributes.getValue("SocBusSelection")
        assertEquals(identifiers.first(), chosen)
        val xml = CircCodec.writeProject(state.project, state.componentRegistry)
        assertTrue("""<a name="SocBusIdentifier" val="$chosen"/>""" in xml, xml)
        assertTrue("""<a name="SocBusSelection" val="$chosen"/>""" in xml, xml)
        val again = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
        assertEquals(xml, CircCodec.writeProject(again, registry))
    }

    @Test
    fun choosingABusSavedWithoutAnIdentifierGivesItOneInTheSameEdit() {
        // An earlier version of the port left SocBusIdentifier blank and attached components by the bus's mobile id.
        val state = EditorState(socProject("""    <comp lib="8" loc="(20,20)" logisim-mobile-id="b7" name="SocBus"/>
    <comp lib="8" loc="(20,600)" logisim-mobile-id="p1" name="SocPio"><a name="SocBusSelection" val="b7"/></comp>
    <comp lib="8" loc="(800,600)" logisim-mobile-id="m1" name="Socmem"><a name="label" val="RAM"/></comp>"""))
        val before = CircCodec.writeProject(state.project, state.componentRegistry)
        assertEquals("", state.document.components.single { it.id == "b7" }.attributes["SocBusIdentifier"].orEmpty())
        state.select("m1")
        val session = PropertyInspectorSession(state, state.propertyInspectorContext())
        val selection = PropertyEditorModel.fields(registry, session.context.components, session.context.socBuses)
            .single { it.name == "SocBusSelection" }
        assertTrue(session.applyOption(state, session, selection, selection.options.single { it.value == "b7" }.value))
        val components = state.document.components.associateBy { it.id }
        val identifier = components.getValue("b7").attributes.getValue("SocBusIdentifier")
        assertTrue(portableBusIdentifier.matches(identifier), identifier)
        assertEquals(identifier, components.getValue("m1").attributes["SocBusSelection"])
        // A component already attached by the old id stays attached to the same bus.
        assertEquals(identifier, components.getValue("p1").attributes["SocBusSelection"])
        state.undo()
        assertEquals(before, CircCodec.writeProject(state.project, state.componentRegistry), "One undo restores all three")
    }

    @Test
    fun choosingALegacyBusAlsoMovesTheSelectedComponentsOwnReferences() {
        // The usual legacy DMA: control and source bus are the bus's mobile id; the user now picks the destination.
        val state = EditorState(socProject("""    <comp lib="8" loc="(20,20)" logisim-mobile-id="b7" name="SocBus"/>
    <comp lib="8" loc="(20,600)" logisim-mobile-id="d1" name="SocDma"><a name="SocBusSelection" val="b7"/><a name="DmaSrcBus" val="b7"/></comp>"""))
        val before = CircCodec.writeProject(state.project, state.componentRegistry)
        state.select("d1")
        val session = PropertyInspectorSession(state, state.propertyInspectorContext())
        val destination = PropertyEditorModel.fields(registry, session.context.components, session.context.socBuses)
            .single { it.name == "DmaDstBus" }
        assertTrue(session.applyOption(state, session, destination, destination.options.single { it.value == "b7" }.value))
        val components = state.document.components.associateBy { it.id }
        val identifier = components.getValue("b7").attributes.getValue("SocBusIdentifier")
        assertTrue(portableBusIdentifier.matches(identifier), identifier)
        val dma = components.getValue("d1").attributes
        assertEquals(listOf(identifier, identifier, identifier), listOf(dma["SocBusSelection"], dma["DmaSrcBus"], dma["DmaDstBus"]))
        state.undo()
        assertEquals(before, CircCodec.writeProject(state.project, state.componentRegistry), "One undo restores the bus and the DMA")
    }

    @Test
    fun anAttributeTheSelectionDoesNotShareIsNamedAsTheInspectorShowsIt() {
        val state = EditorState(null)
        state.place(ExternalIoComponents.RgbVideo)
        state.tap(GridPoint(100, 100))
        state.place(ComponentKind.AND)
        state.tap(GridPoint(600, 100))
        val (video, gate) = state.document.components.takeLast(2)
        state.select(video.id)
        state.select(gate.id, additive = true)
        val before = state.project
        state.updateSelectedAttributes(mapOf("color" to "555 RGB (15 bit)"))
        assertEquals(before, state.project)
        val shown = state.statusText.resolve(AppLanguage.ENGLISH)
        assertTrue("Color model" in shown, shown)
    }

    @Test
    fun ne555RowsShowTheirQuantitiesAsTheChipsDo() {
        // EngineeringAttribute.toDisplayString in the table; the file keeps the whole ohms and microfarads.
        val resistance = field("NE555", "r1")
        assertEquals("71660", resistance.value)
        assertEquals("71.66 kΩ", resistance.localizedValue().resolve(AppLanguage.ENGLISH))
        assertEquals("4.7 kΩ", field("NE555", "r1", mapOf("r1" to "4700")).localizedValue().resolve(AppLanguage.ENGLISH))
        val capacitance = field("NE555", "c1")
        assertEquals("10", capacitance.value)
        assertEquals("10 µF", capacitance.localizedValue().resolve(AppLanguage.PORTUGUESE))
        // Other whole numbers show as they are stored.
        assertEquals("8", field("Pin", "width", mapOf("width" to "8")).localizedValue().resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun ne555OffersEvolutionsPresetsWithUnits() {
        // Evolution's NE555 R1_PRESETS and C1_PRESETS, shown as EngineeringAttribute formats them.
        val resistance = field("NE555", "r1")
        assertEquals(listOf("1000", "10000", "71660", "100000", "1000000", "10000000"), resistance.presets.map { it.value })
        assertEquals(listOf("1 kΩ", "10 kΩ", "71.66 kΩ", "100 kΩ", "1 MΩ", "10 MΩ"),
            resistance.presets.map { it.label.resolve(AppLanguage.ENGLISH) })
        val capacitance = field("NE555", "c1")
        assertEquals(listOf("10", "100", "1000"), capacitance.presets.map { it.value })
        assertEquals(listOf("10 µF", "100 µF", "1000 µF"), capacitance.presets.map { it.label.resolve(AppLanguage.ENGLISH) })
        val timer = component(definition("NE555"))
        for (preset in resistance.presets) {
            assertEquals(PropertyEditResult.Valid(mapOf("r1" to preset.value)), PropertyEditorModel.validate(registry, listOf(timer), "r1", preset.value))
        }
    }

    @Test
    fun aSchemaRejectionNamesTheShownAttribute() {
        // The Pin accepts any readable font; the Tcl component also limits it to 256 characters.
        val pin = component(definition("Pin"), id = "p")
        val tcl = component(definition("TclGeneric"), id = "t")
        val long = "F".repeat(260) + " bold 16"
        val invalid = assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(registry, listOf(pin, tcl), "labelfont", long))
        val shown = invalid.notice.resolve(AppLanguage.ENGLISH)
        assertTrue(shown.startsWith("Invalid value for Label font"), shown)
        assertFalse("labelfont" in shown, shown)
    }

    @Test
    fun rangesIntersectWhicheverComponentComesFirst() {
        val bounded = ComponentKind("TEST_BOUNDED")
        val unbounded = ComponentKind("TEST_UNBOUNDED")
        val low = ComponentKind("TEST_LOW")
        val high = ComponentKind("TEST_HIGH")
        fun definition(kind: ComponentKind, key: AttributeKey<*>) = AttributeSchema(listOf(key)).let { schema ->
            ComponentDefinition(kind, kind.toString(), "#Test", schema.defaults, schema)
        }
        val tests = ComponentRegistry(listOf(
            definition(bounded, AttributeKey.int("phaseOffset", 0, 0..Int.MAX_VALUE)),
            definition(unbounded, AttributeKey.int("phaseOffset", 0)),
            definition(low, AttributeKey.int("width", 1, 1..5)),
            definition(high, AttributeKey.int("width", 6, 6..9)),
        ))
        fun part(kind: ComponentKind, id: String) = tests.create(kind, id, GridPoint(0, 0))
        for (order in listOf(listOf(part(bounded, "a"), part(unbounded, "b")), listOf(part(unbounded, "b"), part(bounded, "a")))) {
            val offset = PropertyEditorModel.fields(tests, order).single { it.name == "phaseOffset" }
            assertFalse(offset.readOnly, "${order.map { it.kind }}")
            assertEquals(PropertyInputKind.INTEGER, offset.kind)
            assertEquals(0L..Int.MAX_VALUE.toLong(), offset.range)
            assertIs<PropertyEditResult.Invalid>(PropertyEditorModel.validate(tests, order, "phaseOffset", "-1"))
        }
        // No value fits both ranges: the attribute is shown, not offered for editing.
        val disjoint = PropertyEditorModel.fields(tests, listOf(part(low, "l"), part(high, "h"))).single { it.name == "width" }
        assertTrue(disjoint.readOnly)
        assertNull(disjoint.hint)
    }

    @Test
    fun placementToolKeyboardsFollowWhatEachFieldAccepts() {
        val timer = registry.create(definition("NE555").kind, "tool", GridPoint(0, 0))
        val fields = placementToolFields(timer, registry, darkTheme = false).associateBy { it.name }
        assertEquals(KeyboardType.Ascii, placementKeyboardOptions(fields.getValue("r1")).keyboardType)
        assertEquals(KeyboardType.Ascii, placementKeyboardOptions(fields.getValue("c1")).keyboardType)
        val random = registry.create(definition("Random").kind, "tool", GridPoint(0, 0))
        val seed = placementToolFields(random, registry, darkTheme = false).single { it.name == "seed" }
        assertEquals(KeyboardType.Ascii, placementKeyboardOptions(seed).keyboardType)
        val pin = registry.create(definition("Pin").kind, "tool", GridPoint(0, 0))
        val width = placementToolFields(pin, registry, darkTheme = false).single { it.name == "width" }
        assertEquals(KeyboardType.Number, placementKeyboardOptions(width).keyboardType)
        // The sheet's fields are single lines that apply on Done.
        for (field in fields.values + seed + width) assertEquals(ImeAction.Done, placementKeyboardOptions(field).imeAction)
    }

    private companion object {
        /**
         * XML names that are already the English word a person reads: Evolution's Slider "Direction" and PlaRom
         * "Contents" attributes are labeled Direction and Contents (Portuguese: Direção, Conteúdo).
         */
        val LABELS_EQUAL_TO_XML_NAMES = setOf("Direction", "Contents")
    }
}
