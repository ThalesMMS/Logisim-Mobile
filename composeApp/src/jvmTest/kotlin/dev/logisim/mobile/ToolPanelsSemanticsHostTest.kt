package dev.logisim.mobile

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.Dp
import dev.logisim.mobile.core.ExtensionApproval
import dev.logisim.mobile.core.ExtensionRegistration
import dev.logisim.mobile.core.GrayCodeExtension
import dev.logisim.mobile.core.MobileExtensionCatalog
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.HdlIpLibrary
import dev.logisim.mobile.core.localization.LocalizedText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ExtraIoKinds
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.PlaRomTable
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.TclConsoleReply
import dev.logisim.mobile.core.TclConsoleRequest
import dev.logisim.mobile.core.TclExecutionClient
import dev.logisim.mobile.core.TclStepReply
import dev.logisim.mobile.core.TclStepRequest
import dev.logisim.mobile.core.TestVectorFormat
import dev.logisim.mobile.core.TraceRecorder
import dev.logisim.mobile.core.TraceSignals
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The PKG-12 tool panels at phone size (390x844, 360x780 for the replace confirmation): labelled checkboxes, choices
 * with selection semantics, test vectors kept with the tool and opened from a file, results shown and announced inside
 * the sheet and revealed only when they change, and a confirmed trace Clear.
 * Screenshots and merged semantics dumps (what TalkBack reads) go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class ToolPanelsSemanticsHostTest {
    private fun ComposeUiTest.show(content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                LogisimTheme(dark = false) { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.dump(name: String) {
        val roots = onAllNodes(isRoot())
        val text = (0 until roots.fetchSemanticsNodes().size).joinToString("\n\n") { roots[it].printToString() }
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "$name-semantics.txt").writeText(text)
    }

    private fun EditorState.context(
        navigate: (EditorLocation) -> Unit = ::focusLocation,
        closeTool: () -> Unit = {},
        sendComponentEvent: (String, ComponentEvent) -> Boolean = { _, _ -> true },
    ) = EditorToolContext(project, document, simulation, document.components.filter { it.id in selectedIds },
        ::applyProject, ::applyDocument, ::selectCircuit, { selectComponent(it) }, ::reportStatus,
        projectSimulation = rootSimulation, documentIdentity = documentIdentity, selectedWires = selectedWires,
        sendComponentEvent = sendComponentEvent, navigate = navigate, closeTool = closeTool, showNotice = ::reportStatus)

    /** Every checkbox, switch or toggle that TalkBack can focus must say what it controls. */
    private fun ComposeUiTest.assertTogglesAreLabelled() {
        val unlabeled = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).fetchSemanticsNodes()
            .filter { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().none { it.text.isNotBlank() } &&
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().none { it.isNotBlank() }
            }
        assertTrue(unlabeled.isEmpty(), "Unlabeled toggleables: ${unlabeled.map { it.config }}")
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
    private val hasLiveRegion = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    private val widthMismatch: String get() {
        val child = CircuitDocument("child", components = listOf(
            CircuitComponent("source", ComponentKind.INPUT, GridPoint(0, 0), attributes = mapOf("width" to "8")),
            CircuitComponent("sink", ComponentKind.OUTPUT, GridPoint(100, 0), attributes = mapOf("width" to "4"))),
            wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0))))
        val main = CircuitDocument("main", components = listOf(CircuitComponent("instance",
            ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = "child")))
        return CircCodec.writeProject(CircuitProject(listOf(main, child)))
    }

    @Test
    fun traceFileOptionsAreLabelledCheckboxesAndAFormatRadioGroup() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val recording = TraceFileRecording(TraceRecorder()) {}
        val files = object : TraceFileActions {
            override fun select(append: Boolean, onResult: (TraceFileOpenResult) -> Unit) = onResult(TraceFileOpenResult.Cancelled)
        }
        show { Column(Modifier.padding(16.dp)) { TraceFileControls(state.context(), true, recording, files) {} } }
        screenshot("after-trace-file-options")
        dump("after-trace-file-options")
        assertTogglesAreLabelled()
        val header = onNode(hasText("Include header") and isToggleable())
        header.assertIsOn()
        // Tapping the label toggles the option.
        onNodeWithText("Include header", useUnmergedTree = true).performClick()
        header.assertIsOff()
        val timestamped = onNode(hasText("Timestamped (every sample)") and hasRole(Role.RadioButton))
        val legacy = onNode(hasText("Logisim 2.7.1 format (changes only)") and hasRole(Role.RadioButton))
        timestamped.assertIsSelected()
        legacy.performClick()
        legacy.assertIsSelected()
        timestamped.assertIsNotSelected()
    }

    @Test
    fun diagnosticsFiltersAreSelectableChipsAndNavigationClosesTheSheet() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(widthMismatch)
        val tool = diagnosticsEditorTool()
        val events = mutableListOf<String>()
        var open by mutableStateOf(true)
        show {
            if (open) tool.content(state.context(navigate = { state.focusLocation(it); events += "navigate" },
                closeTool = { events += "close"; open = false }))
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Navigate to the origin").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText("Include unconnected input warnings") and isToggleable()).assertIsOn()
        assertTogglesAreLabelled()
        onNodeWithTag("diagnostic-category-all").assertIsSelected().assert(hasRole(Role.RadioButton))
        onNodeWithText("Electrical").performClick()
        waitForIdle()
        onNodeWithText("Electrical").assertIsSelected()
        onNodeWithTag("diagnostic-category-all").assertIsNotSelected()
        screenshot("after-diagnostics-filters")
        dump("after-diagnostics-filters")

        onAllNodesWithText("Navigate to the origin")[0].performClick()
        waitForIdle()
        assertEquals(listOf("navigate", "close"), events)
        assertTrue(state.fitSelection, "The canvas frames the diagnostic's origin")
        // The filter belongs to the tool, so reopening the sheet keeps it.
        open = true
        waitForIdle()
        onNodeWithText("Electrical").assertIsSelected()
    }

    @Test
    fun hdlImportShowsEveryChoiceAndAnnouncesItsResultUnderTheButton() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(null)
        val tool = hdlImportTool({ null })
        show { tool.content(state.context()) }
        for (format in listOf("VERILOG", "SYSTEM_VERILOG", "BLIF")) onNodeWithTag("hdl-import-format-$format").assert(hasRole(Role.RadioButton))
        listOf("Verilog", "SystemVerilog", "BLIF").forEach { onNodeWithText(it).assertIsDisplayed() }
        onNodeWithTag("hdl-import-format-VERILOG").assertIsSelected()
        onNodeWithTag("hdl-import-undefined-REJECT").assertIsSelected()
        // The option labels come from their own keys, capitalized by the catalog rather than by code.
        mapOf("REJECT" to "Reject", "ZERO" to "Replace with 0", "ONE" to "Replace with 1").forEach { (policy, label) ->
            onNodeWithTag("hdl-import-undefined-$policy").assert(hasText(label)).assert(hasRole(Role.RadioButton))
        }
        onNodeWithTag("hdl-import-memory-NATIVE").assertIsSelected().assertIsEnabled()
        screenshot("after-hdl-import-choices")
        dump("after-hdl-import-choices")

        onNodeWithTag("hdl-import-format-BLIF").performClick()
        waitForIdle()
        onNodeWithTag("hdl-import-format-BLIF").assertIsSelected()
        onNodeWithTag("hdl-import-format-VERILOG").assertIsNotSelected()
        // BLIF has no native memories: the policy shows "expand" and cannot be changed, as before.
        onNodeWithTag("hdl-import-memory-EXPAND").assertIsSelected().assertIsNotEnabled()
        screenshot("after-hdl-import-blif")

        onNodeWithTag("hdl-import-source").performTextInput(".model m\n.end\n")
        onNodeWithTag("hdl-import-add-file").performScrollTo().performClick()
        onNodeWithTag("hdl-import-prepare").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("hdl-import-notice").assertIsDisplayed().assert(hasLiveRegion)
            .assert(hasText("Connect the companion in the project tools."))
        screenshot("after-hdl-import-notice")
    }

    @Test
    fun hdlExportLanguageShowsDisplayNamesWithSelection() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        show { HdlExportPanel(state.context()) }
        assertTrue(onAllNodesWithText("VERILOG").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithText("✓ VHDL").fetchSemanticsNodes().isEmpty())
        onNodeWithTag("hdl-export-language-vhdl").assertIsSelected().assert(hasRole(Role.RadioButton))
        onNodeWithText("Verilog").assertIsDisplayed()
        onNodeWithTag("hdl-export-language-verilog").performClick()
        waitForIdle()
        onNodeWithTag("hdl-export-language-verilog").assertIsSelected()
        onNodeWithTag("hdl-export-language-vhdl").assertIsNotSelected()
        screenshot("after-hdl-export-language")
        dump("after-hdl-export-language")
        onNodeWithTag("hdl-export-file").performClick()
        waitForIdle()
        onNodeWithTag("hdl-export-file-option-0").assertIsSelected()
        screenshot("after-hdl-export-files")
    }

    @Test
    fun testVectorsOpenFromAFileAndSurviveClosingTheSheet() = runDesktopComposeUiTest(390, 844) {
        val project = CircuitProject(listOf(
            CircuitDocument("main", listOf(
                CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0), "A"),
                CircuitComponent("b", ComponentKind.INPUT, GridPoint(0, 40), "B"),
                CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(100, 0), "Y"),
            )),
            CircuitDocument("HalfAdder"), CircuitDocument("FullAdder"), CircuitDocument("RegisterFile"),
        ))
        val state = EditorState(CircCodec.writeProject(project))
        val created = mutableListOf<DocumentAsset>()
        val assets = object : DocumentAssetActions {
            override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) {
                assertTrue("text/plain" in mimeTypes)
                onResult(AssetOpenResult.Opened(DocumentAsset("and.txt", "text/plain", "A B Y\n0 0 0".encodeToByteArray())))
            }
            override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) { created += asset; onResult(AssetActionResult.Completed) }
            override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
            override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
        }
        val tool = testVectorEditorTool(assets = assets)
        var open by mutableStateOf(true)
        show { if (open) tool.content(state.context()) }
        fun vectorText() = onNodeWithTag("vector-text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        onNodeWithTag("vector-open-file").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5_000) { vectorText() == "A B Y\n0 0 0" }
        onNodeWithTag("vector-notice").assert(hasText("Vector file and.txt opened."))
        screenshot("after-test-vectors-opened")

        open = false
        waitForIdle()
        open = true
        waitForIdle()
        assertEquals("A B Y\n0 0 0", vectorText(), "Reopening the sheet keeps the vectors")
        screenshot("after-test-vectors-reopened")

        onNodeWithTag("vector-circuit").performClick()
        waitForIdle()
        assertEquals(4, state.project.circuits.size)
        state.project.circuits.forEachIndexed { index, circuit ->
            onNodeWithTag("vector-circuit-option-$index").assert(hasText(circuit.name))
        }
        onNodeWithTag("vector-circuit-option-0").assertIsSelected()
        onNodeWithTag("vector-circuit-option-1").assertIsNotSelected()
        screenshot("after-test-vectors-circuits")
        dump("after-test-vectors-circuits")
        onNodeWithTag("vector-circuit-option-0").performClick()
        waitForIdle()

        onNodeWithText("Export vectors").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("test-vectors.txt"), created.map { it.name })
    }

    @Test
    fun socValidationIsShownInsideTheSheet() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/soc/program-rv32.circ"))
        state.select(state.document.components.first { it.kind.name.contains("RV32", ignoreCase = true) }.id)
        val tool = createSocDebuggerTool()
        show { EditorModalSheet(onDismissRequest = {}, modifier = Modifier.testTag("editor-tool-sheet")) { tool.content(state.context()) } }
        val address = onNodeWithTag("soc-address")
        address.performScrollTo()
        address.performTextReplacement("zz")
        waitForIdle()
        address.performScrollTo()
        onNodeWithTag("soc-read").assertIsNotEnabled()
        onNodeWithTag("soc-write").assertIsNotEnabled()
        val error = onAllNodesWithText("Invalid address.", useUnmergedTree = true)[0]
        error.assertIsDisplayed()
        val sheet = onNodeWithTag("editor-tool-sheet").fetchSemanticsNode().boundsInWindow
        val shown = error.fetchSemanticsNode().boundsInWindow
        assertTrue(sheet.contains(shown.topLeft) && sheet.contains(shown.bottomRight.copy(x = shown.right - 1, y = shown.bottom - 1)),
            "The error $shown is drawn inside the sheet $sheet")
        onNodeWithTag("soc-read").performScrollTo()
        error.assertIsDisplayed()
        screenshot("after-soc-invalid-address")
        dump("after-soc-invalid-address")

        // W2-06: the register is chosen from x0 zero … x31 t6, so only the value can be invalid.
        onNodeWithTag("soc-register-index").assert(hasText("x1  ra"))
        val value = onNodeWithTag("soc-register-value")
        value.performScrollTo()
        value.performTextReplacement("zz")
        waitForIdle()
        value.performScrollTo()
        onNodeWithText("Set register").assertIsNotEnabled()
        // The field says why the button is disabled, inside the sheet.
        val valueError = onAllNodesWithText("Enter up to 8 hexadecimal digits.", useUnmergedTree = true)[0]
        valueError.assertIsDisplayed()
        val shownValueError = valueError.fetchSemanticsNode().boundsInWindow
        assertTrue(sheet.contains(shownValueError.topLeft), "The register value error $shownValueError is inside the sheet $sheet")
        screenshot("after-soc-invalid-register")
    }

    @Test
    fun tclConsoleResultIsAnnouncedUnderTheConsoleButtons() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(null)
        val client = object : TclExecutionClient {
            override val hostLabel = "Test host"
            override val available = true
            override suspend fun runConsole(request: TclConsoleRequest) = TclConsoleReply("42", "", "", request.source.sha256)
            override suspend fun step(request: TclStepRequest): TclStepReply = error("unused")
            override suspend fun cancel(requestId: String) = Unit
        }
        val tool = tclEditorTool(client)
        show { tool.content(state.context()) }
        onNodeWithText("Run console on the Mac").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("tcl-console-notice", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onNodeWithTag("tcl-console-notice", useUnmergedTree = true).assertIsDisplayed().assert(hasLiveRegion).assert(hasText("Result: 42"))
        screenshot("after-tcl-console-result")
        dump("after-tcl-console-result")
    }

    @Test
    fun traceClearAsksBeforeDiscardingSamples() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val signal = TraceSignals.fromProject(state.project).first { it.width == 1 }
        val recorder = TraceRecorder(listOf(signal))
        fun sample(high: Boolean) = SimulationState(vectorValues = mapOf(signal.reference.componentId to
            if (high) LogicVector.high(1) else LogicVector.low(1)))
        for (time in 0L..9L) recorder.capture(sample(time % 2 == 0L), time)
        show { TracePanel(state.project, sample(false), recorder, onExport = {}, sampleTime = 9) }
        assertEquals(10, recorder.frames.size)
        // Clear moved to the end of the toolbar, after the exports, away from Pause capture.
        val waveDrom = onNodeWithText("Export WaveDrom").fetchSemanticsNode().positionInRoot
        val clear = onNodeWithTag("trace-clear").fetchSemanticsNode().positionInRoot
        assertTrue(clear.x > waveDrom.x, "Clear is the last toolbar action")
        onNodeWithTag("trace-clear").performScrollTo()
        screenshot("after-trace-toolbar")
        onNodeWithTag("trace-clear").performClick()
        waitForIdle()
        onNodeWithText("10 captured samples will be discarded. This cannot be undone.").assertIsDisplayed()
        assertEquals(10, recorder.frames.size)
        screenshot("after-trace-clear-confirmation")
        dump("after-trace-clear-confirmation")
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertEquals(10, recorder.frames.size)
        onNodeWithTag("trace-clear").performClick()
        waitForIdle()
        onNodeWithTag("trace-clear-confirm").performClick()
        waitForIdle()
        assertEquals(1, recorder.frames.size)
    }

    @Test
    fun traceSignalPickerRowsAreLabelledCheckboxes() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val recorder = TraceRecorder()
        show { TracePanel(state.project, state.rootSimulation, recorder, onExport = {}) }
        onNodeWithText("Select signals").performClick()
        waitForIdle()
        assertTogglesAreLabelled()
        val first = TraceSignals.fromProject(state.project).first()
        val row = onNodeWithTag("trace-signal-${first.reference.key}")
        row.assertIsToggleable().assert(hasRole(Role.Checkbox)).assertIsOff()
        row.performClick()
        waitForIdle()
        row.assertIsOn()
        assertEquals(listOf(first.reference), recorder.signals.map { it.reference })
        screenshot("after-trace-signal-picker")
        dump("after-trace-signal-picker")
    }

    @Test
    fun helpExampleResultIsShownAndAnnouncedInsideTheExample() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(null)
        val tool = helpExamplesEditorTool(onOpenExample = { _, _ -> false })
        show { tool.content(state.context()) }
        onNodeWithText("Examples").performClick()
        waitForIdle()
        onNode(hasScrollToKeyAction()).performScrollToKey("bus-conflict")
        waitForIdle()
        onNodeWithTag("help-verify-bus-conflict").performClick()
        waitUntil(timeoutMillis = 30_000) {
            onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "help-result-bus-conflict") and hasLiveRegion,
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()
        onNodeWithTag("help-result-bus-conflict", useUnmergedTree = true).assertIsDisplayed().assert(hasLiveRegion)
        screenshot("after-help-example-result")
        dump("after-help-example-result")
        // The result is also sent to the editor status line for when the sheet is closed.
        val shown = onNodeWithTag("help-result-bus-conflict", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }
        assertEquals(shown, state.statusText.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun plaRomCellsDescribeTermInputAndState() = runDesktopComposeUiTest(390, 844) {
        var component by mutableStateOf(CircuitComponent("pla", ExtraIoKinds.PlaRom, GridPoint(0, 0),
            attributes = mapOf("inputs" to "4", "and" to "2", "outputs" to "2", "Contents" to "")))
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                ExtraIoControls(component, null, onEvent = {}) { component = component.copy(attributes = component.attributes + it) }
            }
        }
        assertTogglesAreLabelled()
        val input = onNodeWithContentDescription("Term 1, input I3")
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "unused"))
        input.performClick()
        waitForIdle()
        onNodeWithContentDescription("Term 1, input I3").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "negated"))
        val output = onNode(hasContentDescription("Term 1 to output O1") and isToggleable())
        output.assertIsToggleable().assertIsOff()
        output.performClick()
        waitForIdle()
        output.assertIsOn()
        // The stored matrix keeps Evolution's Contents encoding.
        val expected = PlaRomTable.decode(4, 2, 2, "").withLiteral(0, 0, 1).withConnection(0, 0, true).encode()
        assertEquals(expected, component.attributes["Contents"])
        screenshot("after-pla-rom-matrix")
        dump("after-pla-rom-matrix")
    }

    @Test
    fun aNoticeIsRevealedWhenItChangesButNotWhenThePanelIsComposedAgain() = runDesktopComposeUiTest(390, 844) {
        var notice by mutableStateOf<ToolNotice?>(LocalizedText.literal("Kept result").asResult())
        var shown by mutableStateOf(true)
        var scroll: ScrollState? = null
        show {
            if (shown) {
                val state = rememberScrollState().also { scroll = it }
                Column(Modifier.fillMaxSize().verticalScroll(state)) {
                    Spacer(Modifier.height(1500.dp))
                    ToolNoticeText(notice, Modifier.testTag("notice"))
                }
            }
        }
        fun offset() = checkNotNull(scroll).value
        assertEquals(0, offset(), "A notice the tool kept does not scroll the panel when it opens")
        notice = LocalizedText.literal("New result").asResult()
        waitForIdle()
        assertTrue(offset() > 0, "A new result is brought into view")

        // Reopening the sheet composes the panel again on the notice the tool kept: it opens at its top.
        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        assertEquals(0, offset())
        notice = LocalizedText.literal("Working…").asProgress()
        waitForIdle()
        assertEquals(0, offset(), "Progress is shown without moving the panel")
        notice = null
        waitForIdle()
        notice = LocalizedText.literal("Done").asResult()
        waitForIdle()
        assertTrue(offset() > 0, "A result after a cleared notice is revealed")
    }

    @Test
    fun aListItemReturningWithItsResultDoesNotScrollTheList() = runDesktopComposeUiTest(390, 844) {
        val list = LazyListState()
        val kept = LocalizedText.literal("Example verified").asResult()
        show {
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(30, key = { it }) { index ->
                    Column {
                        Text("Example $index", Modifier.height(300.dp))
                        ToolNoticeText(if (index == 3) kept else null, Modifier.testTag("result-$index"))
                    }
                }
            }
        }
        assertTrue(onAllNodesWithTag("result-3", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // The user scrolls down: the example with a result enters at the bottom, with its result still below the edge.
        runOnIdle { list.dispatchRawDelta(120f) }
        waitForIdle()
        assertTrue(onAllNodesWithText("Example 3").fetchSemanticsNodes().isNotEmpty())
        assertEquals(0 to 120, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset,
            "The returning item must not pull the list to its result")
    }

    @Test
    fun hdlSimulationBackendIsAChoiceAndSourceEditsDoNotInterruptTyping() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(null)
        val tool = hdlSimulationTool { null }
        show {
            val context = state.context()
            tool.runtime(context)
            tool.content(context)
        }
        val vhdl = onNodeWithTag("hdl-sim-backend-VHDL")
        val blif = onNodeWithTag("hdl-sim-backend-BLIF")
        vhdl.assertIsSelected().assert(hasRole(Role.RadioButton))
        blif.assertIsNotSelected().assert(hasRole(Role.RadioButton))
        fun source() = onNodeWithTag("hdl-sim-source").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        fun exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

        // A VHDL entity with a generic gets a typed field per generic (W2-06). Editing that field reports why a
        // session cannot start the same way as a source edit: as progress under the source buttons, with no live
        // region and no scrolling to it, so typing is not interrupted (W2-10 R12-3).
        onNodeWithTag("hdl-sim-source").performTextReplacement("""library ieee;
use ieee.std_logic_1164.all;
entity and_gate is
  generic(N: positive := 1);
  port(a, b: in std_logic; y: out std_logic);
end entity;
architecture rtl of and_gate is
begin
  y <= a and b;
end architecture;
""")
        waitForIdle()
        onNodeWithTag("hdl-sim-notice", useUnmergedTree = true)
            .assert(hasText("Source changed. Apply the edit before starting another session."))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        val generic = onNodeWithTag("hdl-sim-generic-N")
        generic.performScrollTo().performClick()
        generic.performTextInput("4")
        waitForIdle()
        onNodeWithTag("hdl-sim-notice", useUnmergedTree = true)
            .assert(hasText("Parameters changed. Apply the edit before starting another session."))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        generic.assertIsFocused().assert(hasText("4"))
        generic.performTextInput("8")
        waitForIdle()
        generic.assertIsFocused().assert(hasText("48"))
        onNodeWithTag("hdl-sim-notice", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        screenshot("after-hdl-sim-generic-edit")

        blif.performScrollTo().performClick()
        waitForIdle()
        blif.assertIsSelected()
        vhdl.assertIsNotSelected()
        assertTrue(source().startsWith(".model"), "Choosing BLIF loads the BLIF example")
        assertTrue(!exists("hdl-sim-generic-N") && !exists("hdl-sim-generics"), "BLIF has no generics")

        // Editing the source reports why a session cannot start, as progress: no live region, no scrolling to it.
        onNodeWithTag("hdl-sim-source").performTextInput("# edited\n")
        waitForIdle()
        onNodeWithTag("hdl-sim-notice", useUnmergedTree = true)
            .assert(hasText("Source changed. Apply the edit before starting another session."))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        screenshot("after-hdl-sim-source-edit")
        dump("after-hdl-sim-source-edit")

        onNodeWithTag("hdl-sim-apply").performScrollTo().performClick()
        waitForIdle()
        assertEquals(1, state.document.components.count { it.kind == HdlIpLibrary.BLIF }, "Insert adds the BLIF component")
        // The edit result is announced under the source buttons, above Prepare (SOURCE anchor).
        val saved = onNodeWithTag("hdl-sim-notice", useUnmergedTree = true)
        saved.assert(hasText("Source and parameters saved in the component.")).assert(hasLiveRegion)
        val notice = saved.fetchSemanticsNode().positionInRoot
        val prepare = onNodeWithTag("hdl-sim-prepare").fetchSemanticsNode().positionInRoot
        assertTrue(notice.y < prepare.y, "The source result sits above Prepare: $notice vs $prepare")
        assertEquals(1, onAllNodesWithTag("hdl-sim-notice", useUnmergedTree = true).fetchSemanticsNodes().size)
        screenshot("after-hdl-sim-source-saved")
    }

    @Test
    fun socUnavailableInstanceIsReportedUnderTheProgramButtons() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/soc/program-rv32.circ"))
        state.select(state.document.components.first { it.kind.name.contains("RV32", ignoreCase = true) }.id)
        val tool = createSocDebuggerTool()
        show { tool.content(state.context(sendComponentEvent = { _, _ -> false })) }
        assertTrue(onAllNodesWithTag("soc-notice-program", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        onNode(hasText("Step") and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithTag("soc-notice-program", useUnmergedTree = true).assertIsDisplayed()
            .assert(hasText("The selected instance is not running.")).assert(hasLiveRegion)
        assertEquals("The selected instance is not running.", state.statusText.resolve(AppLanguage.ENGLISH))
        screenshot("after-soc-instance-unavailable")
    }

    private fun vectorProject() = CircCodec.writeProject(CircuitProject(listOf(CircuitDocument("main", listOf(
        CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0), "A"),
        CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(100, 0), "Y"),
    )))))

    private class RecordingAssets(var onOpen: (onResult: (AssetOpenResult) -> Unit) -> Unit) : DocumentAssetActions {
        val created = mutableListOf<DocumentAsset>()
        var opens = 0
        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) { opens++; onOpen(onResult) }
        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) { created += asset; onResult(AssetActionResult.Completed) }
        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
    }

    private fun ComposeUiTest.vectorText() = onNodeWithTag("vector-text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun openingAVectorFileAsksBeforeReplacingTypedVectors() = runDesktopComposeUiTest(360, 780) {
        val state = EditorState(vectorProject())
        val assets = RecordingAssets { it(AssetOpenResult.Opened(DocumentAsset("pass.txt", "text/plain", "A Y\n0 0".encodeToByteArray()))) }
        val tool = testVectorEditorTool(assets = assets)
        show { tool.content(state.context()) }
        onNodeWithTag("vector-text").performTextReplacement("A Y\n1 1")
        onNodeWithTag("vector-open-file").performClick()
        waitForIdle()
        onNodeWithText("Replace typed vectors?").assertIsDisplayed()
        assertEquals(0, assets.opens, "The picker waits for the answer")
        screenshot("after-test-vectors-replace-confirmation")
        dump("after-test-vectors-replace-confirmation")
        onNodeWithTag("vector-replace-cancel").assert(hasText("Cancel")).performClick()
        waitForIdle()
        assertEquals(0, assets.opens)
        assertEquals("A Y\n1 1", vectorText(), "Cancel keeps the typed vectors")

        onNodeWithTag("vector-open-file").performClick()
        waitForIdle()
        onNodeWithTag("vector-replace-confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { vectorText() == "A Y\n0 0" }
        assertEquals(1, assets.opens)

        // The opened text is saved in a file, so opening another one asks nothing; nor does text saved by Export vectors.
        onNodeWithTag("vector-open-file").performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("Replace typed vectors?").fetchSemanticsNodes().isEmpty())
        waitUntil(timeoutMillis = 5_000) { assets.opens == 2 }
        onNodeWithTag("vector-text").performTextReplacement("A Y\n1 0")
        onNodeWithText("Export vectors").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("test-vectors.txt"), assets.created.map { it.name })
        onNodeWithTag("vector-open-file").performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("Replace typed vectors?").fetchSemanticsNodes().isEmpty())
        waitUntil(timeoutMillis = 5_000) { assets.opens == 3 }
    }

    @Test
    fun aVectorFileRequestNeverLeavesThePanelDisabled() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(vectorProject())
        var pending: ((AssetOpenResult) -> Unit)? = null
        val assets = RecordingAssets { pending = it }
        val tool = testVectorEditorTool(assets = assets)
        var open by mutableStateOf(true)
        show { if (open) tool.content(state.context()) }
        onNodeWithTag("vector-open-file").performClick()
        waitForIdle()
        onNodeWithTag("vector-open-file").assertIsNotEnabled()
        // The sheet closes while the picker is up, then the picker answers into the cancelled sheet scope.
        open = false
        waitForIdle()
        runOnIdle { checkNotNull(pending)(AssetOpenResult.Opened(DocumentAsset("late.txt", "text/plain", "A Y\n0 0".encodeToByteArray()))) }
        waitForIdle()
        val status = state.statusText.resolve(AppLanguage.ENGLISH)
        open = true
        waitForIdle()
        screenshot("after-test-vectors-late-file")
        onNodeWithTag("vector-open-file").assertIsEnabled()
        onNodeWithTag("vector-check").assertIsEnabled()
        // The chosen file is not dropped (W2-10 R12-4): it is in the editor on reopen, and the status line said so
        // while the sheet was closed.
        assertEquals("A Y\n0 0", vectorText(), "The file picked while the sheet was closed is in the editor")
        onNodeWithTag("vector-notice", useUnmergedTree = true).assert(hasText("Vector file late.txt opened."))
        assertEquals("Vector file late.txt opened.", status)

        // A picker that throws ends the request and says so under the button.
        assets.onOpen = { throw IllegalStateException() }
        onNodeWithTag("vector-open-file").performClick()
        waitForIdle()
        onNodeWithTag("vector-open-file").assertIsEnabled()
        onNodeWithTag("vector-notice", useUnmergedTree = true).assert(hasText("The vector file could not be opened."))
    }

    /** W2-10 R12-4 follow-up: a file that arrives after the editor switched documents stays off the new document's status line. */
    @Test
    fun aVectorFilePickedAfterTheDocumentChangedStaysOffTheNewStatusLine() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(vectorProject())
        var pending: ((AssetOpenResult) -> Unit)? = null
        val tool = testVectorEditorTool(assets = RecordingAssets { pending = it })
        var open by mutableStateOf(true)
        show {
            if (open) {
                // Like App, the context is current only while the editor still shows the document it was built for.
                val identity = state.documentIdentity
                tool.content(state.context().copy(isCurrentEditorContext = { state.documentIdentity == identity }))
            }
        }
        onNodeWithTag("vector-open-file").performClick()
        waitForIdle()
        // The sheet closes while the picker is up, and the editor moves on to another document before the picker answers.
        open = false
        waitForIdle()
        runOnIdle { state.newDocument() }
        val status = state.statusText.resolve(AppLanguage.ENGLISH)
        runOnIdle { checkNotNull(pending)(AssetOpenResult.Opened(DocumentAsset("late.txt", "text/plain", "A Y\n0 0".encodeToByteArray()))) }
        waitForIdle()
        assertEquals(status, state.statusText.resolve(AppLanguage.ENGLISH), "The new document's status line does not report the old file")
        open = true
        waitForIdle()
        onNodeWithTag("vector-open-file").assertIsEnabled()
        assertTrue(vectorText() != "A Y\n0 0", "The old document's file does not reach the new document's editor")
        assertTrue(onAllNodesWithText("Vector file late.txt opened.").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun vectorResultsAreSavedAndWithoutAPickerTheTextIsShownForCopying() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(vectorProject())
        val assets = RecordingAssets { it(AssetOpenResult.Cancelled) }
        var picker by mutableStateOf(true)
        val withPicker = testVectorEditorTool(assets = assets)
        val withoutPicker = testVectorEditorTool()
        show { (if (picker) withPicker else withoutPicker).content(state.context()) }
        onNodeWithTag("vector-text").performTextReplacement("A Y\n0 0")
        onNodeWithTag("vector-check").performClick()
        waitForIdle()
        onNodeWithText("Export results").performScrollTo().performClick()
        waitForIdle()
        val results = assets.created.single()
        assertEquals("test-vector-results.csv" to "text/csv", results.name to results.mimeType)
        onNodeWithTag("vector-notice", useUnmergedTree = true).assert(hasText("File test-vector-results.csv saved.")).assert(hasLiveRegion)

        picker = false
        waitForIdle()
        assertTrue(onAllNodesWithTag("vector-open-file").fetchSemanticsNodes().isEmpty(), "No picker, no Open button")
        onNodeWithTag("vector-text").performTextReplacement("A Y\n1 0")
        onNodeWithText("Export vectors").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Export test").assertIsDisplayed()
        screenshot("after-test-vectors-copy-dialog")
        dump("after-test-vectors-copy-dialog")
        // The dialog shows the exported table, written in the vector file format, for copying.
        onNode(hasAnyAncestor(isDialog()) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .assert(hasText(TestVectorFormat.write(TestVectorFormat.read("A Y\n1 0"))))
        onNodeWithText("Copy").assertIsDisplayed()
        onNodeWithText("Close").performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("Export test").fetchSemanticsNodes().isEmpty())
    }

    private fun ComposeUiTest.showIn(language: AppLanguage, content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
        waitForIdle()
    }

    /** W2-10 R12-1: text opened from a file and then edited is confirmed too, and the dialog says only the edits are unsaved. */
    @Test
    fun editingAnOpenedVectorFileAsksAndSaysTheChangesAreUnsaved() = runDesktopComposeUiTest(360, 780) {
        val state = EditorState(vectorProject())
        val assets = RecordingAssets { it(AssetOpenResult.Opened(DocumentAsset("a.txt", "text/plain", "A Y\n0 0".encodeToByteArray()))) }
        val tool = testVectorEditorTool(assets = assets)
        show { tool.content(state.context()) }
        onNodeWithTag("vector-open-file").performClick()
        waitUntil(timeoutMillis = 5_000) { vectorText() == "A Y\n0 0" }
        onNodeWithTag("vector-text").performTextReplacement("A Y\n0 0\n1 1")
        onNodeWithTag("vector-open-file").performClick()
        waitForIdle()
        onNodeWithText("Replace typed vectors?").assertIsDisplayed()
        screenshot("after-test-vectors-edited-file-replace")
        dump("after-test-vectors-edited-file-replace")
        onNodeWithText("Opening a file replaces the vectors in the editor. Your changes to them are not saved in a file.")
            .assertIsDisplayed()
        assertTrue(onAllNodesWithText("They were not opened from or saved to a file.", substring = true).fetchSemanticsNodes().isEmpty())
        assertEquals(1, assets.opens, "The picker waits for the answer")
        onNodeWithTag("vector-replace-cancel").performClick()
        waitForIdle()
        assertEquals("A Y\n0 0\n1 1", vectorText())
    }

    @Test
    fun portugueseReplaceConfirmationFitsANarrowPhone() = runDesktopComposeUiTest(360, 780) {
        val state = EditorState(vectorProject())
        val assets = RecordingAssets { it(AssetOpenResult.Opened(DocumentAsset("a.txt", "text/plain", "A Y\n0 0".encodeToByteArray()))) }
        val tool = testVectorEditorTool(assets = assets)
        showIn(AppLanguage.PORTUGUESE) { tool.content(state.context()) }
        onNodeWithTag("vector-open-file").performClick()
        waitUntil(timeoutMillis = 5_000) { vectorText() == "A Y\n0 0" }
        onNodeWithTag("vector-text").performTextReplacement("A Y\n1 1")
        onNodeWithTag("vector-open-file").performClick()
        waitForIdle()
        screenshot("after-test-vectors-replace-confirmation-pt")
        onNodeWithText("Abrir um arquivo substitui os vetores do editor. Suas alterações não estão salvas em um arquivo.")
            .assertIsDisplayed()
        onNodeWithTag("vector-replace-confirm").assertIsDisplayed()
        onNodeWithTag("vector-replace-cancel").assertIsDisplayed()
    }

    /** The vertical scroll offset of the scrolling panel that holds a node matching [inside]. */
    private fun ComposeUiTest.panelScroll(inside: SemanticsMatcher): Float =
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(inside))
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    /** Shrinks the viewport so it ends just under [control]: whatever is placed below it starts out of view. */
    private fun ComposeUiTest.endViewportUnder(control: SemanticsMatcher, viewport: MutableState<Dp?>) {
        val bottom = onNode(control).fetchSemanticsNode().boundsInRoot.bottom
        viewport.value = with(density) { bottom.toDp() } + 4.dp
        waitForIdle()
    }

    /** The surface passes its full size down as a minimum, so the shorter viewport sits in a box that does not. */
    @Composable
    private fun Viewport(height: Dp?, content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) {
        Box(if (height == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(height)) { content() }
    }

    /** W2-10 R12-3: the SoC program notice stays composed while empty, so its first failure is scrolled into view. */
    @Test
    fun socFirstProgramFailureIsScrolledIntoView() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/soc/program-rv32.circ"))
        state.select(state.document.components.first { it.kind.name.contains("RV32", ignoreCase = true) }.id)
        val tool = createSocDebuggerTool()
        val viewport = mutableStateOf<Dp?>(null)
        show { Viewport(viewport.value) { tool.content(state.context(sendComponentEvent = { _, _ -> false })) } }
        val step = hasText("Step") and hasClickAction()
        endViewportUnder(step, viewport)
        assertEquals(0f, panelScroll(step))
        onNode(step).performClick()
        waitForIdle()
        onNodeWithTag("soc-notice-program", useUnmergedTree = true).assertIsDisplayed()
            .assert(hasText("The selected instance is not running."))
        assertTrue(panelScroll(step) > 0f, "The first failure is brought into view")
        screenshot("after-soc-first-failure-revealed")
    }

    /** W2-10 R12-3: the Extensions report stays composed while empty, so the first report is scrolled into view. */
    @Test
    fun extensionsFirstReportIsScrolledIntoView() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val tool = extensionsEditorTool(MobileExtensionCatalog(state.componentRegistry))
        val viewport = mutableStateOf<Dp?>(null)
        show { Viewport(viewport.value) { tool.content(state.context()) } }
        val inspect = hasText("Inspect project libraries") and hasClickAction()
        endViewportUnder(inspect, viewport)
        assertEquals(0f, panelScroll(inspect))
        onNode(inspect).performClick()
        waitForIdle()
        onNodeWithText("No incompatible external libraries found.").assertIsDisplayed().assert(hasLiveRegion)
        assertTrue(panelScroll(inspect) > 0f, "The first report is brought into view")
        screenshot("after-extensions-first-report-revealed")
    }

    /** W2-10 R12-3: the Extensions bind notice stays composed while empty, so the first bind result is scrolled into view. */
    @Test
    fun extensionsFirstBindResultIsScrolledIntoView() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(null)
        val provider = GrayCodeExtension.provider
        val manifest = provider.manifest
        val installed = MobileExtensionCatalog(state.componentRegistry)
            .install(provider, ExtensionApproval(manifest.fingerprint, manifest.capabilities, manifest.licenseId))
        val catalog = assertIs<ExtensionRegistration.Installed>(installed).catalog
        val tool = extensionsEditorTool(catalog, providers = listOf(provider))
        // A project that still references the Logisim 2.7.1 Gray Code JAR the extension declares it replaces.
        val legacyXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <project source="2.7.1" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <lib desc="jar#gray-1.0.jar#com.cburch.gray.Components" name="1"><tool name="Gray Code Incrementer"/></lib>
              <main name="main"/>
              <circuit name="main">
                <comp lib="1" loc="(200,100)" name="Gray Code Incrementer"/>
              </circuit>
            </project>
        """.trimIndent()
        val viewport = mutableStateOf<Dp?>(null)
        show { Viewport(viewport.value) { tool.content(state.context().copy(extensionDocumentXml = { legacyXml })) } }
        val bind = hasText("Use this extension for the legacy library") and hasClickAction()
        onNode(bind).performScrollTo().assertIsEnabled()
        endViewportUnder(bind, viewport)
        val before = panelScroll(bind)
        onNode(bind).performClick()
        waitForIdle()
        val linked = hasText("Legacy library linked: ", substring = true)
        onNode(linked).assertIsDisplayed().assert(hasLiveRegion)
        val bound = hasText("Legacy library linked in this session") and hasClickAction()
        onNode(bound).assertIsNotEnabled()
        assertTrue(panelScroll(bound) > before, "The first bind result is brought into view")
        screenshot("after-extensions-first-bind-revealed")
    }

    /** W2-10 R12-3: the HDL SESSION anchor stays composed while empty, so the first session result is scrolled into view. */
    @Test
    fun hdlFirstSessionResultIsScrolledIntoView() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(null)
        val tool = hdlSimulationTool { null }
        val viewport = mutableStateOf<Dp?>(null)
        show {
            Viewport(viewport.value) {
                val context = state.context()
                tool.runtime(context)
                tool.content(context)
            }
        }
        onNodeWithTag("hdl-sim-apply").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("hdl-sim-notice", useUnmergedTree = true).assert(hasText("Source and parameters saved in the component."))
        val prepare = hasTestTag("hdl-sim-prepare")
        onNode(prepare).performScrollTo().assertIsEnabled()
        endViewportUnder(prepare, viewport)
        val before = panelScroll(prepare)
        onNode(prepare).performClick()
        waitForIdle()
        val notice = onNodeWithTag("hdl-sim-notice", useUnmergedTree = true)
        notice.assert(hasText("Connect the companion in the project tools.")).assert(hasLiveRegion).assertIsDisplayed()
        val shown = notice.fetchSemanticsNode().positionInRoot
        val sample = onNodeWithTag("hdl-sim-step").fetchSemanticsNode().positionInRoot
        assertTrue(shown.y > sample.y, "The session result sits under Sample: $shown vs $sample")
        assertTrue(panelScroll(prepare) > before, "The first session result is brought into view")
        screenshot("after-hdl-sim-first-session-result-revealed")
    }
}
