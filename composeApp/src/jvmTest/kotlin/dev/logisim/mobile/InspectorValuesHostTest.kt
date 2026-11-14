package dev.logisim.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W3-01: the inspector's values and validation follow Evolution. Booleans read Yes/No, errors name the attribute as
 * the table shows it and say what is allowed, discrete numbers are chosen from a menu or a preset, typed entry opens
 * focused with its value selected and applies on Done, label fonts are chosen and validated, and SoC components pick
 * their bus from the circuit's buses. Screenshots go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class InspectorValuesHostTest {
    private fun project(lib: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  $lib
  <main name="main"/>
  <circuit name="main">
$body
  </circuit>
</project>"""

    private fun ComposeUiTest.inspector(state: EditorState, language: AppLanguage = AppLanguage.ENGLISH) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        val session = rememberPropertyInspectorSession(state)
                        Box {
                            TypedPropertyInspector(state.componentRegistry, session.context.components, session,
                                InspectorPresentation.SHEET, onLabel = {}, onLabelFocusChanged = {},
                                onOpenField = { session.open(it) },
                                onChooseOption = { field, value -> session.applyOption(state, session, field, value) })
                            PropertyInspectorDialog(session) { session.applyValue(state, session, it) }
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.valueOf(field: String): String? =
        onNodeWithTag("inspector-row-$field").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun EditorState.component(label: String) = document.components.first { it.label == label }

    /** The presets of [field] form one row below its text field, whatever their number. */
    private fun ComposeUiTest.presetsSitBelowTheField(field: String, preset: String) {
        val text = onNodeWithTag("inspector-value-field").fetchSemanticsNode().boundsInRoot
        val row = onNodeWithTag("inspector-presets-$field").fetchSemanticsNode().boundsInRoot
        val chip = onNodeWithTag("inspector-preset-$field-$preset").fetchSemanticsNode()
        val target = density.density * 48f
        assertTrue(row.top >= text.top + target, "presets $row under the field $text")
        assertTrue(row.height < 2 * target, "one row: $row")
        assertTrue(chip.size.height >= target - 0.5f, "48dp chip: ${chip.size}")
    }

    private val pins = project("""<lib desc="#Wiring" name="0"/>""",
        """    <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="P"/></comp>""")

    @Test
    fun booleansReadYesAndNoAsInEvolution() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(project("""<lib desc="#Gates" name="1"/>""",
            """    <comp lib="1" loc="(200,100)" name="AND Gate"><a name="label" val="G"/></comp>"""))
        state.select(state.component("G").id)
        inspector(state, AppLanguage.PORTUGUESE)
        // Evolution's booleanTrueOption and booleanFalseOption: "Sim" and "Não", not "Ativado" and "Desativado".
        assertEquals("Não", valueOf("negate0"))
        onNodeWithTag("inspector-row-negate0").performClick()
        waitForIdle()
        assertEquals("true", state.component("G").attributes["negate0"])
        assertEquals("Sim", valueOf("negate0"))
        screenshot("w3-01-boolean-yes-no")
    }

    @Test
    fun anInvalidWidthNamesTheShownAttributeAndItsRange() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        state.select(state.component("P").id)
        inspector(state)
        onNodeWithTag("inspector-row-width").performClick()
        waitForIdle()
        // Common bit widths are one tap away, in one row under the field so the field and buttons do not move.
        presetsSitBelowTheField("width", "16")
        screenshot("w3-01-width-presets-row")
        onNodeWithTag("inspector-preset-width-16").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("inspector-value-field").assert(hasText("16"))
        onNodeWithTag("inspector-value-field").performTextReplacement("70")
        waitForIdle()
        onNodeWithText("Invalid value for Data bits: 70. Enter a whole number from 1 to 64.").assertExists()
        onAllNodesWithText("Apply").onLast().assertIsNotEnabled()
        screenshot("w3-01-width-invalid-range")
        onNodeWithTag("inspector-value-field").performTextReplacement("16")
        waitForIdle()
        onNodeWithTag("inspector-value-field").performImeAction()
        waitForIdle()
        assertEquals("16", state.component("P").attributes["width"])
    }

    @Test
    fun typedValuesOpenFocusedSelectedAndApplyOnDone() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(project("""<lib desc="#Input/Output-Extra" name="5"/>""",
            """    <comp lib="5" loc="(200,100)" name="NE555"><a name="label" val="T"/></comp>"""))
        state.select(state.component("T").id)
        inspector(state)
        onNodeWithTag("inspector-row-r1").performClick()
        waitForIdle()
        val field = onNodeWithTag("inspector-value-field")
        field.assertIsFocused()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, "71660".length)))
        // The resistance accepts Evolution's SI prefixes, explained under the field.
        onNodeWithText("Ohms. You can type k or M, such as 4.7k or 1M.").assertExists()
        // Evolution's R1 presets, labeled with their units; one tap puts the stored ohms in the field.
        onNodeWithTag("inspector-preset-r1-71660").assert(hasText("71.66 kΩ"))
        presetsSitBelowTheField("r1", "10000000")
        screenshot("w3-01-ne555-resistance")
        onNodeWithTag("inspector-preset-r1-10000000").performScrollTo().performClick()
        waitForIdle()
        field.assert(hasText("10000000"))
        field.performTextReplacement("4.7k")
        waitForIdle()
        field.performImeAction()
        waitForIdle()
        assertTrue(onAllNodesWithTag("inspector-value-field").fetchSemanticsNodes().isEmpty(), "Done applies and closes the dialog")
        assertEquals("4700", state.component("T").attributes["r1"])
    }

    @Test
    fun theCommonValuesHeadingStaysWhileTheChipsScroll() = runDesktopComposeUiTest(360, 780) {
        val state = EditorState(project("""<lib desc="#Input/Output-Extra" name="5"/>""",
            """    <comp lib="5" loc="(200,100)" name="NE555"><a name="label" val="T"/></comp>"""))
        state.select(state.component("T").id)
        inspector(state, AppLanguage.PORTUGUESE)
        // The table shows the resistance as the chips do; the file keeps whole ohms.
        assertEquals("71.66 kΩ", valueOf("r1"))
        onNodeWithTag("inspector-row-r1").performClick()
        waitForIdle()
        val row = onNodeWithTag("inspector-presets-r1").fetchSemanticsNode().boundsInRoot
        val heading = onNodeWithTag("inspector-presets-heading-r1", useUnmergedTree = true).fetchSemanticsNode()
        val before = heading.boundsInRoot
        val presets = listOf("1000", "10000", "71660", "100000", "1000000", "10000000")
        fun fullyShown() = presets.count { preset ->
            val chip = onNodeWithTag("inspector-preset-r1-$preset").fetchSemanticsNode().boundsInRoot
            chip.left >= row.left - 0.5f && chip.right <= row.right + 0.5f
        }
        val shownAtStart = fullyShown()
        screenshot("w3-01-ne555-presets-360-pt")
        onNodeWithTag("inspector-preset-r1-10000000").performScrollTo()
        waitForIdle()
        val after = onNodeWithTag("inspector-presets-heading-r1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        screenshot("w3-01-ne555-presets-360-pt-scrolled")
        println("presets 360 pt: row=$row heading=$before shownAtStart=$shownAtStart")
        assertEquals("Valores comuns", heading.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
        assertEquals(before, after, "the heading does not scroll away with the chips")
        assertTrue(before.left >= row.left && before.width <= density.density * 72f + 0.5f, "a narrow heading: $before in $row")
        assertTrue(row.height < 2 * density.density * 48f, "still one row: $row")
        assertTrue(shownAtStart >= 2, "chips shown beside the heading: $shownAtStart")
    }

    @Test
    fun discreteNumbersAreChosenFromAMenu() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(project("""<lib desc="#Plexers" name="2"/><lib desc="#FPArithmetic" name="3"/>""",
            """    <comp lib="2" loc="(200,100)" name="Multiplexer"><a name="label" val="M"/></comp>
    <comp lib="3" loc="(400,100)" name="FPAdder"><a name="label" val="F"/></comp>"""))
        state.select(state.component("F").id)
        inspector(state)
        onNodeWithTag("inspector-row-fpwidth").performClick()
        waitForIdle()
        assertEquals(listOf("8", "16", "32", "64"), listOf("8", "16", "32", "64", "24").filter { exists("inspector-option-fpwidth-$it") })
        screenshot("w3-01-fpwidth-menu")
        onNodeWithTag("inspector-option-fpwidth-64").performClick()
        waitForIdle()
        assertEquals("64", state.component("F").attributes["fpwidth"])
        assertTrue(CircCodec.writeProject(state.project, state.componentRegistry).contains("""<a name="fpwidth" val="64"/>"""))
    }

    @Test
    fun smallRangesAreChosenFromAMenu() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(project("""<lib desc="#Plexers" name="2"/>""",
            """    <comp lib="2" loc="(200,100)" name="Multiplexer"><a name="label" val="M"/></comp>"""))
        state.select(state.component("M").id)
        inspector(state)
        // Evolution's integer-range combo box: select bits 1 to 8.
        onNodeWithTag("inspector-row-select").performClick()
        waitForIdle()
        assertEquals((1..8).map(Int::toString), (0..9).map(Int::toString).filter { exists("inspector-option-select-$it") })
        screenshot("w3-01-mux-select-menu")
        onNodeWithTag("inspector-option-select-3").performClick()
        waitForIdle()
        assertEquals("3", state.component("M").attributes["select"])
    }

    @Test
    fun dipPositionsShowTheirHexadecimalPrefix() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(CircCodec.writeProject(dev.logisim.mobile.core.CircuitProject(listOf(dev.logisim.mobile.core.CircuitDocument("main",
            listOf(CircuitComponent("dip", dev.logisim.mobile.core.IoKinds.DipSwitch, GridPoint(100, 100), "D",
                mapOf("number" to "8", "resetbehavior" to "last", "positions" to "ff"))))))))
        state.select(state.component("D").id)
        inspector(state)
        assertEquals("0xff", valueOf("positions"))
        // Display only: the stored value keeps Evolution's bare hexadecimal digits.
        assertEquals("ff", state.component("D").attributes["positions"])
        screenshot("w3-01-dip-positions")
    }

    @Test
    fun labelFontsAreChosenAndAnUnreadableFontIsRejected() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        state.select(state.component("P").id)
        inspector(state)
        onNodeWithTag("inspector-row-labelfont").performClick()
        waitForIdle()
        onNodeWithTag("font-size-preset-20").performClick()
        waitForIdle()
        screenshot("w3-01-labelfont-chooser")
        onAllNodesWithText("Apply").onLast().performClick()
        waitForIdle()
        assertEquals("SansSerif bold 20", state.component("P").attributes["labelfont"])

        state.updateSelectedAttributes(mapOf("labelfont" to "Arial 20pt"))
        waitForIdle()
        onNodeWithTag("inspector-row-labelfont").performClick()
        waitForIdle()
        onNodeWithTag("inspector-value-field").performTextReplacement("Arial 20pt")
        waitForIdle()
        onAllNodesWithText("Apply").onLast().assertIsNotEnabled()
        screenshot("w3-01-labelfont-rejected")
    }

    @Test
    fun socComponentsChooseTheirBusFromTheCircuit() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(project("""<lib desc="#Soc" name="8"/>""",
            """    <comp lib="8" loc="(20,20)" name="SocBus"><a name="label" val="Main"/><a name="SocBusIdentifier" val="bus-a"/></comp>
    <comp lib="8" loc="(20,400)" name="SocBus"><a name="SocBusIdentifier" val="bus-b"/></comp>
    <comp lib="8" loc="(800,250)" name="Socmem"><a name="label" val="RAM"/><a name="SocBusSelection" val="bus-a"/></comp>"""))
        state.select(state.component("Main").id)
        inspector(state)
        // The identifier stays in the file but is not editable: changing it would detach every attached component.
        assertTrue(!exists("inspector-row-SocBusIdentifier"))
        screenshot("w3-01-soc-bus-inspector")
    }

    @Test
    fun socMemoryPicksABusByItsName() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(project("""<lib desc="#Soc" name="8"/>""",
            """    <comp lib="8" loc="(20,20)" name="SocBus"><a name="label" val="Main"/><a name="SocBusIdentifier" val="bus-a"/></comp>
    <comp lib="8" loc="(20,400)" name="SocBus"><a name="SocBusIdentifier" val="bus-b"/></comp>
    <comp lib="8" loc="(800,250)" name="Socmem"><a name="label" val="RAM"/><a name="SocBusSelection" val="bus-a"/></comp>"""))
        state.select(state.component("RAM").id)
        inspector(state)
        assertEquals("Main", valueOf("SocBusSelection"))
        onNodeWithTag("inspector-row-SocBusSelection").performClick()
        waitForIdle()
        assertTrue(exists("inspector-option-SocBusSelection-"))
        assertTrue(exists("inspector-option-SocBusSelection-bus-a"))
        onNodeWithText("SoC Bus@20,400").assertExists()
        screenshot("w3-01-soc-bus-menu")
        onNodeWithTag("inspector-option-SocBusSelection-bus-b").performClick()
        waitForIdle()
        assertEquals("bus-b", state.component("RAM").attributes["SocBusSelection"])
        assertTrue(CircCodec.writeProject(state.project, state.componentRegistry).contains("""<a name="SocBusSelection" val="bus-b"/>"""))
    }

    @Test
    fun aBusPlacedOnMobileIsChosenFromTheMenuByItsPortableIdentifier() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(project("""<lib desc="#Soc" name="8"/>""", ""))
        assertTrue(state.insertComponent(dev.logisim.mobile.core.SocComponents.BUS, GridPoint(20, 20)))
        assertTrue(state.insertComponent(dev.logisim.mobile.core.SocComponents.MEMORY, GridPoint(20, 600)))
        val bus = state.document.components.single { it.kind == dev.logisim.mobile.core.SocComponents.BUS }
        val identifier = bus.attributes.getValue("SocBusIdentifier")
        // Evolution's SocBusAttributes format: "0x", the creation time as 16 hex digits, then a suffix.
        assertTrue(Regex("0x[0-9A-F]{16}[0-9a-f]+").matches(identifier), identifier)
        val memory = state.document.components.single { it.kind == dev.logisim.mobile.core.SocComponents.MEMORY }
        state.select(memory.id)
        inspector(state)
        onNodeWithTag("inspector-row-SocBusSelection").performClick()
        waitForIdle()
        screenshot("w3-01-placed-bus-menu")
        onNodeWithTag("inspector-option-SocBusSelection-$identifier").performClick()
        waitForIdle()
        assertEquals(identifier, state.document.components.single { it.id == memory.id }.attributes["SocBusSelection"])
        assertEquals("SoC Bus@20,20", valueOf("SocBusSelection"))
        val xml = CircCodec.writeProject(state.project, state.componentRegistry)
        assertTrue("""<a name="SocBusIdentifier" val="$identifier"/>""" in xml && """<a name="SocBusSelection" val="$identifier"/>""" in xml)
    }

    @Test
    fun subcircuitToolDefaultsChooseTheLabelFont() = runDesktopComposeUiTest(390, 844) {
        var applied: Map<String, String>? = null
        val prototype = CircuitComponent("proto", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "sub")
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        ProjectToolDefaultsDialog(prototype, dev.logisim.mobile.core.ComponentCatalog.registry, onApply = { applied = it }, onDismiss = {})
                    }
                }
            }
        }
        waitForIdle()
        // The font row opens its chooser in place, as the inspector's font row does.
        onNodeWithTag("project-tool-default-labelfont").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("project-tool-default-labelfont-size-preset-24").performScrollTo().performClick()
        waitForIdle()
        screenshot("w3-01-tool-defaults-font")
        onAllNodesWithText("Apply").onLast().performClick()
        waitForIdle()
        assertEquals(mapOf("labelfont" to "SansSerif bold 24"), applied)
    }

    @Test
    fun inspectorValueColumnsInPortuguese() {
        val xml = project("""<lib desc="#Wiring" name="0"/><lib desc="#Memory" name="4"/><lib desc="#Base" name="6"/>""",
            """    <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="P"/></comp>
    <comp lib="4" loc="(300,100)" name="RAM"><a name="label" val="R"/></comp>
    <comp lib="4" loc="(300,400)" name="Counter"><a name="label" val="C"/></comp>
    <comp lib="6" loc="(100,500)" name="Text"><a name="text" val="Hi"/></comp>""")
        for (width in listOf(390, 360)) for (label in listOf("P", "R", "C", "")) runDesktopComposeUiTest(width, 844) {
            val state = EditorState(xml)
            state.select(state.component(label).id)
            inspector(state, AppLanguage.PORTUGUESE)
            screenshot("w3-01-inspector-${label.ifEmpty { "Text" }}-pt-$width")
        }
    }

    @Test
    fun anOriginalRamShowsNoInternalRows() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState("""<?xml version="1.0" encoding="UTF-8"?>
<project source="2.7.1" version="1.0">
  <lib desc="#Gates" name="1"/>
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="4" loc="(300,100)" name="RAM"/>
    <comp lib="1" loc="(100,300)" name="Controlled Buffer"/>
  </circuit>
</project>""")
        val ram = state.document.components.first { it.kind == dev.logisim.mobile.core.ComponentKind.RAM }
        assertTrue(ram.attributes.keys.any { it.startsWith("logisim-mobile-") })
        state.select(ram.id)
        inspector(state)
        assertTrue(onAllNodes(hasTestTagStartingWith("inspector-row-logisim-mobile-")).fetchSemanticsNodes().isEmpty())
        screenshot("w3-01-original-ram-inspector")
    }

    private fun hasTestTagStartingWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    @Test
    fun socFixturesRoundTripWithTheirBusReferences() {
        for (path in listOf("all-components.circ", "program-rv32.circ", "program-nios2.circ")) {
            val xml = HostScreenshots.fixture("fixtures/soc/$path")
            val registry = dev.logisim.mobile.core.ComponentCatalog.registry
            val project = (CircCodec.readProject(xml, registry) as dev.logisim.mobile.core.CircProjectImportResult.Success).project
            val written = CircCodec.writeProject(project, registry)
            val again = (CircCodec.readProject(written, registry) as dev.logisim.mobile.core.CircProjectImportResult.Success).project
            assertEquals(written, CircCodec.writeProject(again, registry), path)
            for (reference in Regex("""name="Soc(BusSelection|BusIdentifier)" val="[^"]*"""").findAll(xml)) {
                assertTrue(reference.value in written, "$path keeps ${reference.value}")
            }
        }
    }
}
