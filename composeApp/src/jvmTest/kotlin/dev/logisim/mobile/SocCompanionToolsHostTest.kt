package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CompanionClient
import dev.logisim.mobile.core.CompanionConnection
import dev.logisim.mobile.core.CompanionEndpoint
import dev.logisim.mobile.core.CompanionHttpRequest
import dev.logisim.mobile.core.CompanionHttpResponse
import dev.logisim.mobile.core.CompanionJsonArray
import dev.logisim.mobile.core.CompanionJsonCodec
import dev.logisim.mobile.core.CompanionJsonObject
import dev.logisim.mobile.core.CompanionJsonString
import dev.logisim.mobile.core.CompanionToken
import dev.logisim.mobile.core.CompanionTransport
import dev.logisim.mobile.core.CompanionTransportException
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.ExtensionCapability
import dev.logisim.mobile.core.GrayCodeExtension
import dev.logisim.mobile.core.MobileExtensionCatalog
import dev.logisim.mobile.core.Nios2Assembler
import dev.logisim.mobile.core.Rv32Assembler
import dev.logisim.mobile.core.SocAssemblyResult
import dev.logisim.mobile.core.SocLoadAssemblyEvent
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * W2-06 at phone size (390x844): the SoC program listing with disassembly and ABI register names, the result of a
 * manual bus transaction under its buttons, the extension review in plain words, the HDL import name guard, one
 * field per VHDL generic and the JAR pin toggles. Screenshots go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class SocCompanionToolsHostTest {
    private fun ComposeUiTest.show(language: AppLanguage = AppLanguage.ENGLISH, content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
        waitForIdle()
    }

    private fun EditorState.context(
        sendComponentEvent: (String, ComponentEvent) -> Boolean = ::sendComponentEvent,
        extensionXml: (() -> String)? = null,
    ) = EditorToolContext(project, document, simulation, document.components.filter { it.id in selectedIds },
        ::applyProject, ::applyDocument, ::selectCircuit, { selectComponent(it) }, ::reportStatus,
        projectSimulation = rootSimulation, documentIdentity = documentIdentity, selectedWires = selectedWires,
        sendComponentEvent = sendComponentEvent, showNotice = ::reportStatus, extensionDocumentXml = extensionXml)

    private fun ComposeUiTest.texts(): List<String> = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun ComposeUiTest.descriptions(): List<String> = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    /** The text of the node tagged [tag], as TalkBack reads it from the merged tree. */
    private fun ComposeUiTest.textOf(tag: String): String =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    /** Any internal component id shown as its own word ("import-3", "c12"), which Evolution never shows. */
    private fun leakedIds(shown: List<String>, components: List<CircuitComponent>): List<String> = shown.filter { text ->
        components.any { Regex("(?<![A-Za-z0-9_-])" + Regex.escape(it.id) + "(?![A-Za-z0-9_-])").containsMatchIn(text) }
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun EditorState.assemble(kind: String, source: String, nios2: Boolean) {
        val cpu = document.components.first { it.kind.name.equals(kind, ignoreCase = true) }
        select(cpu.id)
        val assembled = (if (nios2) Nios2Assembler.assemble(source, 0u) else Rv32Assembler.assemble(source, 0u))
        assertTrue(assembled is SocAssemblyResult.Assembled, assembled.toString())
        assertTrue(sendComponentEvent(cpu.id, SocLoadAssemblyEvent(assembled)))
    }

    /** C79: each listing row shows the instruction, and the registers carry their ABI names. */
    @Test
    fun socListingShowsDisassemblyAndAbiRegisterNames() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/soc/program-rv32.circ"))
        state.assemble("SOC_RV32IM", "addi t0, t0, 7\nli t0, 7\nbeq a0, zero, 0\n", nios2 = false)
        val tool = createSocDebuggerTool()
        show { tool.content(state.context()) }
        onNodeWithText("Show loaded program").performScrollTo().performClick()
        waitForIdle()
        screenshot("w206-soc-01-rv32-listing")
        val shown = texts()
        assertTrue(shown.any { it.startsWith("addi") && "t0,t0,7" in it }, shown.toString())
        assertTrue(shown.any { it.startsWith("li") && "t0,7" in it }, shown.toString())
        assertTrue(shown.any { it.startsWith("beqz") && "a0" in it }, shown.toString())
        onNodeWithText("Registers").performScrollTo()
        waitForIdle()
        screenshot("w206-soc-02-rv32-registers")
        assertTrue(texts().any { it.startsWith("x5  t0") }, texts().filter { it.startsWith("x") }.toString())
        assertTrue(texts().any { it.startsWith("x10 a0") })
        // The whole grid, down to x31 t6, fits the phone in two columns without sideways scrolling.
        onNodeWithTag("soc-register-grid").performScrollTo()
        waitForIdle()
        screenshot("w206-soc-02b-rv32-register-grid")
        assertRegisterColumns(2, 390)
        onNodeWithTag("soc-register-index").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("soc-register-index-option-10").performScrollTo().assert(hasText("x10 a0", substring = true)).performClick()
        waitForIdle()
        onNodeWithText("Set register").assertIsEnabled()
        screenshot("w206-soc-03-rv32-register-choice")
    }

    private fun ComposeUiTest.registerCell(name: String) =
        onAllNodes(hasText("$name ", substring = true), useUnmergedTree = true).filterToOne(SemanticsMatcher("register $name") {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> Regex("^$name +\\S* +[0-9A-F?]{8}$").matches(text.text) }
        })

    /** x0..x(columns-1) share a row, x(columns) starts the next one, and no cell reaches past the window. */
    private fun ComposeUiTest.assertRegisterColumns(columns: Int, widthDp: Int) {
        val cells = (0..columns).map { registerCell("x$it").fetchSemanticsNode().boundsInRoot }
        for (index in 1 until columns) assertEquals(cells[0].top, cells[index].top, "x$index is on the first row")
        assertTrue(cells[columns].top > cells[0].top, "x$columns starts the second row")
        val right = (0..31).maxOf { registerCell("x$it").fetchSemanticsNode().boundsInRoot.right }
        assertTrue(right <= widthDp * density.density, "The grid ends at $right, inside $widthDp dp")
    }

    /** C79 at 360 dp: the narrowest phone still shows two whole columns of number, ABI name and value. */
    @Test
    fun socRegisterGridFitsANarrowPhone() = runDesktopComposeUiTest(360, 780) {
        val state = EditorState(HostScreenshots.fixture("fixtures/soc/program-rv32.circ"))
        state.select(state.document.components.first { it.kind.name == "SOC_RV32IM" }.id)
        val tool = createSocDebuggerTool()
        show { tool.content(state.context()) }
        onNodeWithTag("soc-register-grid").performScrollTo()
        waitForIdle()
        screenshot("w206-soc-02c-rv32-register-grid-360")
        assertRegisterColumns(2, 360)
    }

    /** C79 at 600 dp and wider (a tablet sheet): four columns, as Evolution's CpuDrawSupport draws them. */
    @Test
    fun socRegisterGridUsesFourColumnsOnATablet() = runDesktopComposeUiTest(1180, 820) {
        val state = EditorState(HostScreenshots.fixture("fixtures/soc/program-rv32.circ"))
        state.select(state.document.components.first { it.kind.name == "SOC_RV32IM" }.id)
        val tool = createSocDebuggerTool()
        show { tool.content(state.context()) }
        onNodeWithTag("soc-register-grid").performScrollTo()
        waitForIdle()
        screenshot("w206-soc-02d-rv32-register-grid-tablet")
        assertRegisterColumns(4, 1180)
    }

    @Test
    fun socNiosListingRewritesAddOfZeroAsNop() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/soc/program-nios2.circ"))
        state.assemble("SOC_NIOS2", "add r0, r0, r0\naddi r5, zero, 7\n", nios2 = true)
        val tool = createSocDebuggerTool()
        show { tool.content(state.context()) }
        onNodeWithText("Show loaded program").performScrollTo().performClick()
        waitForIdle()
        screenshot("w206-soc-04-nios2-listing")
        val shown = texts()
        assertTrue(shown.any { it == "nop" }, shown.toString())
        assertTrue(shown.any { it.startsWith("movi") && "r5,7" in it }, shown.toString())
        assertTrue(texts().any { it.startsWith("r1  at") }, texts().filter { it.startsWith("r") }.toString())
    }

    /** C83: the reply to Read or Write is shown under the buttons, not only at the end of the bus trace. */
    @Test
    fun socManualTransactionResultIsShownUnderTheButtons() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/soc/program-rv32.circ"))
        state.select(state.document.components.first { it.kind.name == "SOC_MEMORY" }.id)
        val tool = createSocDebuggerTool()
        show { tool.content(state.context()) }
        onNodeWithTag("soc-data").performScrollTo().performTextReplacement("CAFEBABE")
        onNodeWithTag("soc-write").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("soc-read").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("soc-read").performScrollTo()
        screenshot("w206-soc-05-manual-read")
        val result = onNodeWithTag("soc-bus-result", useUnmergedTree = true)
        result.assertIsDisplayed().assert(hasText("CAFEBABE", substring = true))
        val button = onNodeWithTag("soc-read").fetchSemanticsNode().boundsInRoot
        val shown = result.fetchSemanticsNode().boundsInRoot
        assertTrue(shown.top >= button.bottom && shown.top - button.bottom < 80f * density.density,
            "The result $shown is directly under the Read button $button")
        // The same Read again is a new reply, so the live region changes and TalkBack confirms it again.
        val first = textOf("soc-bus-result")
        onNodeWithTag("soc-read").performClick()
        waitForIdle()
        val second = textOf("soc-bus-result")
        assertTrue("CAFEBABE" in second, second)
        assertNotEquals(first, second, "A repeated identical Read must change the announced result")
    }

    /** C91: each requested capability is named and explained; the SoC chips name components without ids. */
    @Test
    fun extensionReviewExplainsEachCapability() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(null)
        val gray = GrayCodeExtension.provider
        val everything = gray.copy(manifest = gray.manifest.copy(capabilities = ExtensionCapability.entries.toSet()))
        val tool = extensionsEditorTool(MobileExtensionCatalog(ComponentCatalog.registry), listOf(everything))
        show { tool.content(state.context()) }
        onNodeWithText("Accept license and register extension").performScrollTo()
        screenshot("w206-ext-01-capabilities")
        val shown = texts().joinToString("\n")
        for (capability in ExtensionCapability.entries) assertTrue(capability.serialized !in shown, "${capability.serialized} in\n$shown")
        assertTrue("Pure logic" in shown, shown)
        assertTrue("Companion JAR" in shown, shown)
    }

    @Test
    fun socChipsNameComponentsWithoutInternalIds() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/soc/program-rv32.circ"))
        val tool = createSocDebuggerTool()
        show { tool.content(state.context()) }
        screenshot("w206-soc-06-chips")
        val ids = state.document.components.map { it.id }.toSet()
        val chips = texts().filter { " · " in it || ids.any { id -> it.endsWith(id) } }
        assertTrue(chips.isEmpty(), chips.toString())
        assertTrue(leakedIds(texts() + descriptions(), state.document.components).isEmpty(), texts().toString())
    }

    /** C91: the memory map, the bus trace and the trace comparison name components too, never by internal id. */
    @Test
    fun socSheetShowsNoInternalIdsAnywhere() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/soc/program-rv32.circ"))
        state.select(state.document.components.first { it.kind.name == "SOC_MEMORY" }.id)
        val tool = createSocDebuggerTool()
        show { tool.content(state.context()) }
        // A read puts the memory in every trace, as initiator and as responder.
        onNodeWithTag("soc-read").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("soc-memory-map-toggle").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("soc-memory-map").performScrollTo()
        screenshot("w206-soc-08-memory-map")
        onNodeWithTag("soc-trace-bus-selectors").performScrollTo()
        waitForIdle()
        screenshot("w206-soc-09-trace-comparison")
        onRoot().performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f) } // to the end of the sheet
        waitForIdle()
        screenshot("w206-soc-10-trace-column")
        val shown = texts() + descriptions()
        assertTrue(shown.any { "SoC Memory" in it }, shown.toString())
        val leaked = leakedIds(shown, state.document.components)
        assertTrue(leaked.isEmpty(), "Internal ids shown: $leaked")
    }

    /** C95: Add proposes a new name, and replacing a staged file with other text asks first. */
    @Test
    fun hdlImportDoesNotSilentlyReplaceAStagedFile() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(null)
        val tool = hdlImportTool({ null })
        show { tool.content(state.context()) }
        onNodeWithTag("hdl-import-source").performTextReplacement("module a; endmodule\n")
        onNodeWithTag("hdl-import-add-file").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("hdl-import-source").performScrollTo().performTextReplacement("module b; endmodule\n")
        onNodeWithTag("hdl-import-add-file").performScrollTo().performClick()
        waitForIdle()
        screenshot("w206-hdl-01-two-files")
        val names = onAllNodes(hasTestTag("hdl-import-staged-design.v") or hasTestTag("hdl-import-staged-design2.v"), useUnmergedTree = true)
            .fetchSemanticsNodes().size
        val dialog = onAllNodesWithText("Replace design.v?").fetchSemanticsNodes().isNotEmpty()
        assertTrue(names == 2 || dialog, "Two staged files or a confirmation")
        // The same name with other text asks before replacing.
        onNodeWithTag("hdl-import-file-name").performScrollTo().performTextReplacement("design.v")
        onNodeWithTag("hdl-import-source").performScrollTo().performTextReplacement("module c; endmodule\n")
        onNodeWithTag("hdl-import-add-file").performScrollTo().performClick()
        waitForIdle()
        screenshot("w206-hdl-02-replace-confirmation")
        onNodeWithText("Replace design.v?").assertIsDisplayed()
    }

    /** C95: a loaded file is updated without a dialog; unsaved text and a picked file never replace silently. */
    @Test
    fun hdlImportEditingAndPickerAskBeforeReplacing() = runDesktopComposeUiTest(390, 844) {
        val picked = DocumentAsset("design.v", "application/octet-stream", "module picked; endmodule\n".encodeToByteArray())
        val assets = object : DocumentAssetActions {
            override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) =
                onResult(AssetOpenResult.Opened(picked))
            override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
            override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
            override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
        }
        val state = EditorState(null)
        val tool = hdlImportTool({ null }, assets = assets)
        show { tool.content(state.context()) }
        val source = onNodeWithTag("hdl-import-source")
        source.performTextReplacement("module a; endmodule\n")
        onNodeWithTag("hdl-import-add-file").performScrollTo().performClick()
        waitForIdle()
        val row = onNode(hasText("design.v", substring = true) and hasClickAction() and !hasSetTextAction())

        // Loaded back for editing: the row is marked, and Add updates it without a dialog.
        row.performScrollTo().performClick()
        waitForIdle()
        row.assert(hasStateDescription("Editing"))
        screenshot("w206-hdl-05-editing-marker")
        source.performScrollTo().performTextReplacement("module a2; endmodule\n")
        onNodeWithTag("hdl-import-add-file").performScrollTo().performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("Replace design.v?").fetchSemanticsNodes().isEmpty(), "Updating the loaded file asks nothing")
        row.performScrollTo().performClick()
        waitForIdle()
        source.assert(hasText("module a2; endmodule", substring = true))

        // Text typed in the editor is not thrown away by opening a staged file.
        source.performScrollTo().performTextReplacement("module unsaved; endmodule\n")
        onNodeWithTag("hdl-import-file-name").performScrollTo().performTextReplacement("other.v")
        // Another name no longer edits design.v, so its marker goes.
        row.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        row.performScrollTo().performClick()
        waitForIdle()
        screenshot("w206-hdl-06-discard-confirmation")
        onNodeWithText("Discard the text in the editor?").assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        source.assert(hasText("module unsaved; endmodule", substring = true))

        // A picked file with a staged name and other text asks first too.
        onNodeWithText("Choose source file").performScrollTo().performClick()
        waitForIdle()
        screenshot("w206-hdl-07-picker-replace-confirmation")
        onNodeWithText("Replace design.v?").assertIsDisplayed()
        onNodeWithTag("hdl-import-replace-confirm").performClick()
        waitForIdle()
        row.performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Discard the text in the editor?").assertIsDisplayed()
        onNodeWithTag("hdl-import-discard-confirm").performClick()
        waitForIdle()
        source.assert(hasText("module picked; endmodule", substring = true))
        // The same file picked again replaces nothing, so nothing is asked.
        onNodeWithText("Choose source file").performScrollTo().performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("Replace design.v?").fetchSemanticsNodes().isEmpty())
    }

    /**
     * C89 time generics: a value without a unit is Evolution's femtosecond count, shown in the largest exact unit,
     * and the field writes a femtosecond count back, which Evolution's VhdlGenericAttribute (Long.parseLong) reads.
     */
    @Test
    fun timeGenericsAreFemtosecondsAsInEvolution() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(null)
        val tool = hdlSimulationTool { null }
        show {
            tool.runtime(state.context())
            tool.content(state.context())
        }
        val source = """library ieee;
use ieee.std_logic_1164.all;
entity delayed is
  generic(DELAY: time := 5 ns; WIDTH: positive := 8; LEVEL: std_logic := '1'; MASK: std_logic_vector(3 downto 0) := "0101");
  port(a: in std_logic; y: out std_logic);
end entity;
architecture rtl of delayed is
begin
  y <= a after DELAY;
end architecture;
"""
        onNodeWithTag("hdl-sim-source").performTextReplacement(source)
        waitForIdle()
        // An override from an Evolution file: vhdl_DELAY="10000000" is 10 ns.
        onNodeWithText("Edit as text").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("hdl-sim-generics").performTextReplacement("DELAY=10000000")
        onNodeWithText("Edit as fields").performScrollTo().performClick()
        waitForIdle()
        val number = onNodeWithTag("hdl-sim-generic-DELAY")
        number.performScrollTo()
        screenshot("w206-hdl-08-time-generic")
        number.assert(hasText("10"))
        onNodeWithTag("hdl-sim-generic-DELAY-unit").assert(hasText("ns"))
        // Editing the number keeps the unit and stores femtoseconds, never "25 ns".
        number.performTextReplacement("25")
        waitForIdle()
        onNodeWithTag("hdl-sim-generic-DELAY-unit").assert(hasText("ns"))
        // A std_logic generic is picked from its nine values; a vector is typed as bits and stored with VHDL's quotes.
        onNodeWithTag("hdl-sim-generic-LEVEL").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("hdl-sim-generic-LEVEL-option-3").assert(hasText("'0'")).performClick()
        waitForIdle()
        onNodeWithTag("hdl-sim-generic-MASK").performScrollTo().performTextReplacement("10z1")
        waitForIdle()
        onNodeWithTag("hdl-sim-generic-MASK").assert(hasText("10Z1"))
        onNodeWithTag("hdl-sim-apply").performScrollTo().performClick()
        waitForIdle()
        val inserted = state.document.components.single { it.kind.name == "VHDL_ENTITY" }
        assertEquals("25000000", inserted.attributes["generic.DELAY"])
        // Exactly what the NAME=value text wrote for the same values.
        assertEquals(hdlGenericAttributes("DELAY=25000000\nLEVEL='0'\nMASK=\"10Z1\""),
            inserted.attributes.filterKeys { it.startsWith("generic.") })
        // A count that no larger unit divides stays in fs.
        onNodeWithText("Edit as text").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("hdl-sim-generics").performTextReplacement("DELAY=5")
        onNodeWithText("Edit as fields").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("hdl-sim-generic-DELAY").assert(hasText("5"))
        onNodeWithTag("hdl-sim-generic-DELAY-unit").assert(hasText("fs"))
        // Changing the unit rescales the stored count: 5 ps is 5000 fs.
        onNodeWithTag("hdl-sim-generic-DELAY-unit").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("hdl-sim-generic-DELAY-unit-option-1").performClick()
        waitForIdle()
        screenshot("w206-hdl-09-time-generic-ps")
        onNodeWithText("Edit as text").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("hdl-sim-generics").assert(hasText("DELAY=5000"))
    }

    /** C89: one labelled field per declared generic, with its default as placeholder, and none for BLIF. */
    @Test
    fun vhdlGenericsHaveOneFieldEachAndBlifHasNone() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(null)
        val tool = hdlSimulationTool { null }
        show {
            tool.runtime(state.context())
            tool.content(state.context())
        }
        val source = """library ieee;
use ieee.std_logic_1164.all;
entity counter is
  generic(WIDTH: positive := 8; ENABLED: boolean := true);
  port(a: in std_logic; y: out std_logic);
end entity;
architecture rtl of counter is
begin
  y <= a;
end architecture;
"""
        onNodeWithTag("hdl-sim-source").performTextReplacement(source)
        waitForIdle()
        onNodeWithTag("hdl-sim-generic-WIDTH").performScrollTo()
        screenshot("w206-hdl-03-generic-fields")
        onNodeWithTag("hdl-sim-generic-WIDTH").assert(hasText("WIDTH", substring = true))
        onNodeWithText("Default: 8", useUnmergedTree = true).assertExists()
        // The focused empty field shows the default as its placeholder.
        onNodeWithTag("hdl-sim-generic-WIDTH").performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("8", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "The default is the placeholder")
        onNodeWithTag("hdl-sim-generic-ENABLED").assertExists()
        onNodeWithTag("hdl-sim-generic-WIDTH").performTextReplacement("16")
        waitForIdle()
        onNodeWithTag("hdl-sim-apply").performScrollTo().performClick()
        waitForIdle()
        val inserted = state.document.components.single { it.kind.name == "VHDL_ENTITY" }
        assertEquals(mapOf("generic.WIDTH" to "16"), inserted.attributes.filterKeys { it.startsWith("generic.") || it.startsWith("vhdl_") })

        state.selectComponent(null)
        waitForIdle()
        onNodeWithTag("hdl-sim-backend-BLIF").performScrollTo().performClick()
        waitForIdle()
        screenshot("w206-hdl-04-blif-no-generics")
        assertTrue(!exists("hdl-sim-generics") && !exists("hdl-sim-generic-WIDTH"), "BLIF has no generics")
    }

    /** C90: a 1-bit pin is a 0/1/?/E choice, a short multi-bit value says what is missing, and the frames stay the same. */
    @Test
    fun jarPinsUseTogglesAndExplainIncompleteValues() = runDesktopComposeUiTest(390, 844) {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<project source="3.8.0" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="jar#lib.jar#com.example.Lib" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="en"/></comp>
    <comp lib="0" loc="(100,200)" name="Pin"><a name="label" val="data"/><a name="width" val="8"/></comp>
  </circuit>
</project>
"""
        val planned = mutableListOf<CompanionJsonObject>()
        val transport = object : CompanionTransport {
            override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
                if (request.url.endsWith("/capabilities")) return CompanionHttpResponse(200, """
                    {"protocolVersion":1,"operations":[{"name":"jar-run","allowed":true,"available":true,
                    "missingTools":[],"toolAlternatives":[],"requiresConfirmation":true,"description":"JAR"}],
                    "limits":{"requestBytes":6291456,"inputBytes":4194304,"outputBytes":2097152,"timeoutSeconds":60,
                    "processMemoryBytes":2147483648,"memoryEnforcement":"supervised-process-group","osSandbox":false}}
                """.trimIndent().encodeToByteArray(), "application/json")
                planned += CompanionJsonCodec.parseObject(request.body!!.decodeToString())
                throw CompanionTransportException("offline")
            }
        }
        val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8042"),
            CompanionToken.parse("x".repeat(32))), transport)
        val assets = object : DocumentAssetActions {
            override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) =
                onResult(AssetOpenResult.Opened(DocumentAsset("lib.jar", "application/java-archive", byteArrayOf(1, 2, 3))))
            override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
            override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
            override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
        }
        val state = EditorState(null)
        val tool = jarCompanionTool(assets) { client }
        show { tool.content(state.context(extensionXml = { xml })) }
        screenshot("w206-jar-01-pins")
        // Each segment says which pin it sets, so explore-by-touch does not read a bare "0".
        onNodeWithTag("jar-input-0-0").assert(hasContentDescription("en: 0"))
        onNodeWithTag("jar-input-0-?").assert(hasContentDescription("en: Unknown (?)"))
        onNodeWithTag("jar-input-0-1").performClick()
        waitForIdle()
        onNodeWithTag("jar-input-0-1").assertIsSelected()
        onNodeWithTag("jar-record-step").performClick()
        waitForIdle()
        onNodeWithText("Recorded steps: 1").assertIsDisplayed()

        // A bit short: the pin says what it needs, and Record step waits for it.
        onNodeWithTag("jar-input-1").performScrollTo().performTextReplacement("0000000")
        waitForIdle()
        screenshot("w206-jar-02-incomplete-pin")
        onNodeWithText("Needs 8 bits (7 entered)", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("jar-record-step").assertIsNotEnabled()
        onNodeWithTag("jar-input-1").performTextReplacement("00000000")
        waitForIdle()
        onNodeWithTag("jar-record-step").assertIsEnabled()

        onNodeWithTag("jar-choose-library").performClick()
        waitForIdle()
        onNodeWithTag("jar-review").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { planned.isNotEmpty() }
        val frames = planned.first().obj("parameters").fields["frames"]
        // The same frame the text fields produced when "1" was typed for en.
        assertEquals(CompanionJsonArray(listOf(CompanionJsonObject(mapOf("en" to CompanionJsonString("1"),
            "data" to CompanionJsonString("00000000"))))), frames)
    }

    /**
     * W3-05 (C90): a multi-bit JAR pin takes a hexadecimal value that expands to its bits, so a 16-bit pin needs four
     * characters instead of sixteen. The bit field stays for ? and E, and the frames sent are unchanged.
     */
    @Test
    fun jarMultiBitPinTakesAHexValue() = runDesktopComposeUiTest(390, 844) { jarHexScenario("w305-jar-hex-pins") }

    @Test
    fun jarMultiBitPinTakesAHexValueOnANarrowPhone() = runDesktopComposeUiTest(360, 780) { jarHexScenario("w305-jar-hex-pins-360") }

    private fun ComposeUiTest.jarHexScenario(shot: String) {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<project source="3.8.0" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="jar#lib.jar#com.example.Lib" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="en"/></comp>
    <comp lib="0" loc="(100,200)" name="Pin"><a name="label" val="data"/><a name="width" val="16"/></comp>
    <comp lib="0" loc="(100,300)" name="Pin"><a name="label" val="mode"/><a name="width" val="6"/></comp>
  </circuit>
</project>
"""
        val planned = mutableListOf<CompanionJsonObject>()
        val transport = object : CompanionTransport {
            override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
                if (request.url.endsWith("/capabilities")) return CompanionHttpResponse(200, """
                    {"protocolVersion":1,"operations":[{"name":"jar-run","allowed":true,"available":true,
                    "missingTools":[],"toolAlternatives":[],"requiresConfirmation":true,"description":"JAR"}],
                    "limits":{"requestBytes":6291456,"inputBytes":4194304,"outputBytes":2097152,"timeoutSeconds":60,
                    "processMemoryBytes":2147483648,"memoryEnforcement":"supervised-process-group","osSandbox":false}}
                """.trimIndent().encodeToByteArray(), "application/json")
                planned += CompanionJsonCodec.parseObject(request.body!!.decodeToString())
                throw CompanionTransportException("offline")
            }
        }
        val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8042"),
            CompanionToken.parse("x".repeat(32))), transport)
        val assets = object : DocumentAssetActions {
            override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) =
                onResult(AssetOpenResult.Opened(DocumentAsset("lib.jar", "application/java-archive", byteArrayOf(1, 2, 3))))
            override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
            override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
            override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
        }
        val state = EditorState(null)
        val tool = jarCompanionTool(assets) { client }
        show { tool.content(state.context(extensionXml = { xml })) }
        fun bits(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        // Four hexadecimal digits fill the sixteen bits, most significant first.
        onNodeWithTag("jar-input-1-hex").performScrollTo().performTextReplacement("beef")
        waitForIdle()
        assertEquals("1011111011101111", bits("jar-input-1"))
        assertEquals("BEEF", bits("jar-input-1-hex"))
        // A 6-bit pin takes at most 3F; a digit that would need a seventh bit is refused.
        onNodeWithTag("jar-input-2-hex").performScrollTo().performTextReplacement("2a")
        waitForIdle()
        assertEquals("101010", bits("jar-input-2"))
        onNodeWithTag("jar-input-2-hex").performTextReplacement("4A")
        waitForIdle()
        assertEquals("101010", bits("jar-input-2"))
        // TalkBack reads which pin the hexadecimal field sets.
        onNodeWithTag("jar-input-2-hex").assert(hasText("mode (hex)"))
        screenshot(shot)
        // An unknown bit has no hexadecimal form: the hex field empties and the bits stay as typed.
        onNodeWithTag("jar-input-2").performTextReplacement("10?010")
        waitForIdle()
        assertEquals("", bits("jar-input-2-hex"))
        onNodeWithTag("jar-record-step").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Recorded steps: 1").assertIsDisplayed()
        onNodeWithTag("jar-choose-library").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("jar-review").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { planned.isNotEmpty() }
        val frames = planned.first().obj("parameters").fields["frames"]
        // The same frame typing the bits would give.
        assertEquals(CompanionJsonArray(listOf(CompanionJsonObject(mapOf("en" to CompanionJsonString("0"),
            "data" to CompanionJsonString("1011111011101111"), "mode" to CompanionJsonString("10?010"))))), frames)
    }

    @Test
    fun portugueseSocListingPhone() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/soc/program-rv32.circ"))
        state.assemble("SOC_RV32IM", "addi t0, t0, 7\nbeq a0, zero, 0\n", nios2 = false)
        val tool = createSocDebuggerTool()
        show(AppLanguage.PORTUGUESE) { tool.content(state.context()) }
        onNodeWithText("Mostrar programa carregado").performScrollTo().performClick()
        waitForIdle()
        screenshot("w206-soc-07-listing-pt")
        assertTrue(texts().any { it.startsWith("addi") })
    }
}

