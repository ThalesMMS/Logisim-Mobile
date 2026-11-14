package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CompanionClient
import dev.logisim.mobile.core.CompanionConnection
import dev.logisim.mobile.core.CompanionEndpoint
import dev.logisim.mobile.core.CompanionHttpRequest
import dev.logisim.mobile.core.CompanionHttpResponse
import dev.logisim.mobile.core.CompanionJob
import dev.logisim.mobile.core.CompanionJobState
import dev.logisim.mobile.core.CompanionJsonNull
import dev.logisim.mobile.core.CompanionToken
import dev.logisim.mobile.core.CompanionTransport
import dev.logisim.mobile.core.FpgaBoardCodec
import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.FpgaBoardImportResult
import dev.logisim.mobile.core.FpgaBuildPlan
import dev.logisim.mobile.core.FpgaCircuitRules
import dev.logisim.mobile.core.FpgaFlow
import dev.logisim.mobile.core.FpgaPinBinding
import dev.logisim.mobile.core.FpgaPinDirection
import dev.logisim.mobile.core.FpgaRectangle
import dev.logisim.mobile.core.FpgaSignalBit
import dev.logisim.mobile.core.HdlLanguage
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import dev.logisim.mobile.localization.Localizer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Wave 2 (W2-05): the FPGA pin picker, whole-port mapping, readiness, the interactive board editor, per-pin fields,
 * live validation, units, localized identifiers, progress and the remembered companion host. Screenshots go to
 * `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class FpgaWave2HostTest {
    private val t = Localizer(AppLanguage.PORTUGUESE)
    private val boardsDirectory = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "composeApp/src/commonMain/composeResources/files/fpga-boards") }.first { it.isDirectory }

    private fun boardFile(name: String): FpgaBoardDefinition =
        (FpgaBoardCodec.read(File(boardsDirectory, "$name.xml").readText()) as FpgaBoardImportResult.Success).board

    private val savedMap = """
        <boardmap boardname="BASYS3">
            <mc key="/A" map="647,323"/>
            <mc key="/Y" pmap="649_295_0"/>
        </boardmap>
    """.trimIndent()

    /** A one-bit input A and output Y. */
    private fun oneBit(map: String = "") = project("""
        <project source="5.1.0dev" version="1.0">
          <lib name="0" desc="#Wiring"/><main name="main"/>
          <circuit name="main">
            $map
            <comp lib="0" name="Pin" loc="(20,100)"><a name="label" val="A"/></comp>
            <comp lib="0" name="Pin" loc="(150,100)">
              <a name="label" val="Y"/><a name="type" val="output"/><a name="facing" val="west"/>
            </comp>
            <wire from="(20,100)" to="(150,100)"/>
          </circuit>
        </project>
    """.trimIndent())

    /** A four-bit input SW wired to a four-bit output LED. */
    private fun fourBit() = project("""
        <project source="5.1.0dev" version="1.0">
          <lib name="0" desc="#Wiring"/><main name="main"/>
          <circuit name="main">
            <comp lib="0" name="Pin" loc="(20,100)"><a name="label" val="SW"/><a name="width" val="4"/></comp>
            <comp lib="0" name="Pin" loc="(150,100)">
              <a name="label" val="LED"/><a name="type" val="output"/><a name="facing" val="west"/><a name="width" val="4"/>
            </comp>
            <wire from="(20,100)" to="(150,100)"/>
          </circuit>
        </project>
    """.trimIndent())

    private fun project(xml: String) = (CircCodec.readProject(xml) as CircProjectImportResult.Success).project

    private class MemoryStorage : WorkspaceCatalogStorage {
        val files = mutableMapOf<String, String>()
        override fun <T> withExclusiveAccess(action: () -> T): T = action()
        override fun listNames(): List<String> = files.keys.toList()
        override fun read(name: String): String? = files[name]
        override fun writeAtomically(name: String, contents: String) { files[name] = contents }
    }

    private class FakeAssets : DocumentAssetActions {
        val created = mutableListOf<DocumentAsset>()
        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) =
            onResult(AssetOpenResult.Cancelled)
        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) {
            created += asset
            onResult(AssetActionResult.Completed)
        }
        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
    }

    private inner class Harness(initial: CircuitProject, selected: String) {
        val catalog = FpgaBoardCatalogStore(MemoryStorage()).also { it.select(selected) }
        val session = FpgaMappingSession()
        val assets = FakeAssets()
        var project by mutableStateOf(initial)
        var visible by mutableStateOf(true)
        val tool = fpgaEditorTool(
            reviewProvider = { context ->
                FpgaCircuitRules.reviewForFpga(context.project.copy(mainCircuitName = context.document.name), HdlLanguage.VHDL)
            },
            onExport = {}, onSynthesize = {}, mappingSession = session, boardCatalog = catalog, boardAssets = assets,
        )
    }

    private fun ComposeUiTest.show(harness: Harness, language: AppLanguage = AppLanguage.PORTUGUESE) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        if (harness.visible) {
                            val project = harness.project
                            harness.tool.content(EditorToolContext(project, project.mainCircuit, SimulationState(), emptyList(),
                                applyProject = { harness.project = it }, applyDocument = {}, selectCircuit = {}, selectComponent = {},
                                showStatus = {}, showNotice = {}))
                        }
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 20_000) { exists(hasText(t("fpga_editor_edit_board"))) }
        waitUntil(timeoutMillis = 20_000) { exists(hasText(" · VHDL", substring = true)) }
        waitForIdle()
    }

    private fun ComposeUiTest.exists(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.awaitQuietly(timeout: Long = 5_000, condition: () -> Boolean): Boolean =
        runCatching { waitUntil(timeoutMillis = timeout, condition = condition) }.isSuccess

    private fun ComposeUiTest.scrollTo(matcher: SemanticsMatcher, list: SemanticsMatcher = hasScrollAction()) {
        onAllNodes(list)[0].performScrollToNode(matcher)
        waitForIdle()
    }

    private fun choice(label: String, selection: String) = t("fpga_choice_label", "label" to label, "selection" to selection)

    /** Opens the menu tagged [tag] and picks the first of [options] it offers. */
    private fun ComposeUiTest.chooseTagged(tag: String, vararg options: String) {
        scrollTo(hasTestTag(tag))
        onNodeWithTag(tag).performClick()
        waitForIdle()
        val option = options.first { exists(hasText(it)) }
        onAllNodesWithText(option).let { nodes -> nodes[nodes.fetchSemanticsNodes().lastIndex] }.performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openBoardEditor(harness: Harness) {
        scrollTo(hasText(t("fpga_editor_edit_board")))
        onNodeWithText(t("fpga_editor_edit_board")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText(t("fpga_editor_save_board"))) }
        scrollTo(hasText(t("fpga_editor_choose_image")))
        waitUntil(timeoutMillis = 10_000) { exists(hasContentDescription(t("fpga_editor_picture"))) }
        assertTrue(harness.session.boardEditor.open)
    }

    /** The node's own size, also when the zoomed board has panned it out of view. */
    private fun ComposeUiTest.assertAtLeast48(node: SemanticsNodeInteraction) {
        val size = node.fetchSemanticsNode().size
        with(density) {
            assertTrue(size.width.toDp() >= 48.dp && size.height.toDp() >= 48.dp, "Touch target ${size.width.toDp()} x ${size.height.toDp()}")
        }
    }

    private fun physical(harness: Harness, port: String, bit: Int = 0) =
        harness.session.currentBindings[FpgaSignalBit(port, bit)] as? FpgaPinBinding.Physical

    /** C117: the board picker is full screen, zooms, takes a near tap and commits only through its confirm button. */
    @Test
    fun boardPinPickerZoomsAndConfirmsTheTappedResource() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(oneBit(), "builtin:BASYS3")
        val board = boardFile("BASYS3")
        val led = board.resources.first { it.pins.singleOrNull()?.location == "U16" }
        val neighbour = board.resources.first { it.pins.singleOrNull()?.location == "E19" }
        show(harness)
        chooseTagged("fpga-connection-p_y-0", t("fpga_physical_pin"))
        scrollTo(hasTestTag("fpga-signal-p_y-0"))
        screenshot("c117-signal-card")
        if (!awaitQuietly { exists(hasTestTag("fpga-pin-picker")) }) {
            // The previous picker: an AlertDialog opened from a separate button, without zoom.
            runCatching {
                val open = hasText(t("fpga_board_map_open")) and hasAnyAncestor(hasTestTag("fpga-signal-p_y-0"))
                scrollTo(open)
                onNode(open).performClick()
                waitForIdle()
                screenshot("c117-board-dialog")
            }
        }
        waitUntil(timeoutMillis = 10_000) { exists(hasTestTag("fpga-board-resource-${led.id}")) }
        screenshot("c117-picker-default-zoom")
        // A tap 4 dp right of the LED centre, at the default zoom, still picks that LED and never its neighbour.
        val viewport = onNodeWithTag("fpga-pin-board").fetchSemanticsNode()
        val scale = viewport.size.width / 740f
        val rect = checkNotNull(led.rectangle)
        val centre = Offset((rect.x + rect.width / 2f) * scale, (rect.y + rect.height / 2f) * scale)
        onNodeWithTag("fpga-pin-board").performTouchInput { click(centre + Offset(4.dp.toPx(), 0f)) }
        waitForIdle()
        screenshot("c117-picker-candidate")
        val use = onNodeWithTag("fpga-pin-picker-use")
        val label = use.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        assertTrue("U16" in label && "E19" !in label, "The confirm button names the tapped LED: $label")
        assertEquals(null, physical(harness, "p_y")?.location?.takeIf { it.isNotEmpty() }, "Nothing is bound before confirming")
        // Pinch and the zoom buttons enlarge the LEDs to at least 48 dp.
        onNodeWithTag("fpga-pin-board").performTouchInput {
            pinch(center - Offset(20f, 0f), center - Offset(200f, 0f), center + Offset(20f, 0f), center + Offset(200f, 0f))
        }
        waitForIdle()
        repeat(4) {
            val zoomIn = onAllNodes(hasContentDescription(t("fpga_pin_picker_zoom_in")) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            if (zoomIn.fetchSemanticsNodes().isNotEmpty()) zoomIn[0].performClick()
            waitForIdle()
        }
        assertAtLeast48(onNodeWithTag("fpga-board-resource-${led.id}"))
        assertAtLeast48(onNodeWithTag("fpga-board-resource-${neighbour.id}"))
        screenshot("c117-picker-zoomed")
        onNodeWithTag("fpga-pin-picker-use").performClick()
        waitForIdle()
        assertEquals(FpgaPinBinding.Physical("U16", led.id), physical(harness, "p_y"))
        assertFalse(exists(hasTestTag("fpga-pin-picker")), "Confirming closes the picker")
        scrollTo(hasTestTag("fpga-signal-p_y-0"))
        screenshot("c117-pin-chosen")
    }

    /** C122: the pin is chosen from a full list without a 60-entry cap, never typed. */
    @Test
    fun pinListOffersEveryCompatiblePinWithoutTyping() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(oneBit(), "builtin:BASYS3")
        val board = boardFile("BASYS3")
        val compatible = board.pins.filter { it.direction == FpgaPinDirection.INPUT || it.direction == FpgaPinDirection.INOUT }
        assertTrue(compatible.size > 60)
        val target = compatible.last()
        show(harness)
        chooseTagged("fpga-connection-p_a-0", t("fpga_physical_pin"))
        if (!awaitQuietly { exists(hasTestTag("fpga-pin-picker")) }) {
            runCatching {
                scrollTo(hasText(t.plural("fpga_choose_compatible_pin", compatible.size.toLong())))
                onNodeWithText(t.plural("fpga_choose_compatible_pin", compatible.size.toLong())).performClick()
                waitForIdle()
                screenshot("c122-capped-dropdown")
            }
        }
        onNodeWithText(t("fpga_pin_picker_tab_list")).performClick()
        waitForIdle()
        screenshot("c122-pin-list")
        onNodeWithTag("fpga-pin-list").performScrollToNode(hasTestTag("fpga-pin-option-${target.resourceId}-${target.index}"))
        onNodeWithTag("fpga-pin-option-${target.resourceId}-${target.index}").performClick()
        waitForIdle()
        screenshot("c122-pin-list-past-60")
        onNodeWithTag("fpga-pin-picker-use").performClick()
        waitForIdle()
        assertEquals(FpgaPinBinding.Physical(target.location, target.resourceId), physical(harness, "p_a"))
        // The card summarises the pin; no editable text field remains for it.
        assertFalse(exists(hasSetTextAction() and hasAnyAncestor(hasTestTag("fpga-signal-p_a-0"))))
        scrollTo(hasTestTag("fpga-signal-p_a-0"))
        screenshot("c122-pin-summary")
    }

    /** C118: one tap maps a whole port onto a DIP switch; Unmap all clears every bit. */
    @Test
    fun wholePortMapsOntoADipSwitchAndUnmapAllClearsIt() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(fourBit(), "builtin:ALCHITRY_AU_IO")
        val board = boardFile("ALCHITRY_AU_IO")
        val dip = board.resources.first { it.type.equals("DIPSwitch", true) }
        show(harness)
        scrollTo(hasTestTag("fpga-port-p_sw"))
        screenshot("c118-ports")
        assertTrue(exists(hasTestTag("fpga-port-p_sw")), "Each multi-bit port has one summary header")
        assertFalse(exists(hasTestTag("fpga-signal-p_sw-0")), "Its bit cards are collapsed")
        onNodeWithTag("fpga-port-map-p_sw").assert(hasText(t("fpga_port_map_whole")))
        onNodeWithTag("fpga-port-map-p_sw").performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasTestTag("fpga-board-resource-${dip.id}")) }
        onNodeWithTag("fpga-board-resource-${dip.id}").performClick()
        waitForIdle()
        screenshot("c118-picker-whole-port")
        onNodeWithTag("fpga-pin-picker-use-port").performClick()
        waitForIdle()
        val expected = (0 until 4).associate { bit ->
            FpgaSignalBit("p_sw", bit) to FpgaPinBinding.Physical(dip.pins[bit].location, dip.id)
        }
        assertEquals(expected, harness.session.currentBindings.filterKeys { it.port == "p_sw" })
        assertFalse(exists(hasTestTag("fpga-diagnostic-unmapped-port-p_sw")), "No unmapped bit is left in p_sw")
        scrollTo(hasTestTag("fpga-port-p_sw"))
        screenshot("c118-port-mapped")
        scrollTo(hasTestTag("fpga-unmap-all"))
        onNodeWithTag("fpga-unmap-all").performClick()
        waitForIdle()
        screenshot("c118-unmap-all-confirmation")
        // The dialog's button is the last node with its text; the list's "Unmap all" sits behind it.
        onAllNodesWithText(t("fpga_unmap_all_confirm")).let { it[it.fetchSemanticsNodes().lastIndex] }.performClick()
        waitForIdle()
        assertTrue(harness.session.currentBindings.isEmpty())
        scrollTo(hasTestTag("fpga-port-p_sw"))
        screenshot("c118-unmapped-all")
    }

    /** C119: a clock input without frequency is named as the blocker, and a problem row scrolls to its bit. */
    @Test
    fun readinessNamesTheClockProblemAndProblemsNavigateToTheirBit() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(oneBit(savedMap), "builtin:BASYS3")
        show(harness)
        waitUntil(timeoutMillis = 10_000) { physical(harness, "p_y")?.location == "U16" }
        scrollTo(hasText(t("fpga_clock_role_clock")))
        onNode(hasText(t("fpga_clock_role_clock")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).performClick()
        waitForIdle()
        val zero = t.plural("fpga_mapping_problems", 0)
        val statusNodes = onAllNodes(hasTestTag("fpga-mapping-status")).fetchSemanticsNodes()
        if (statusNodes.isEmpty()) {
            runCatching {
                scrollTo(hasText(t("fpga_validate_mapping")))
                onNodeWithText(t("fpga_validate_mapping")).performClick()
                waitForIdle()
                screenshot("c119-status-before")
            }.onFailure { println("c119 before capture: $it") }
        } else screenshot("c119-clock-blocker")
        assertFalse(exists(hasText(zero)), "The status does not claim zero problems while the clock blocks generation")
        onNodeWithTag("fpga-mapping-status").assertIsDisplayed()
        assertTrue(exists(hasText(t("fpga_blocker_clock_frequency", "port" to "p_a"), substring = true)))
        screenshot("c119-clock-blocker")
        // Back to a signal input, then unmap the 4-bit LED port of another design and jump to it from its problem.
        val buses = Harness(fourBit(), "builtin:BASYS3")
        harness.project = buses.project
        waitUntil(timeoutMillis = 20_000) { runCatching { scrollTo(hasTestTag("fpga-port-p_led")) }.isSuccess }
        scrollTo(hasTestTag("fpga-diagnostic-unmapped-port-p_led"))
        screenshot("c119-problem-rows")
        onNodeWithTag("fpga-diagnostic-unmapped-port-p_led").performClick()
        waitForIdle()
        onNodeWithTag("fpga-signal-p_led-0").assertIsDisplayed()
        screenshot("c119-problem-navigated")
    }

    /** C120: tapping the preview selects a resource; dragging on free space draws a new one. */
    @Test
    fun boardPreviewSelectsByTapAndDrawsANewResourceByDragging() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(oneBit(), "builtin:BASYS3")
        val board = boardFile("BASYS3")
        show(harness)
        openBoardEditor(harness)
        val editor = harness.session.boardEditor
        val before = editor.draft.resources.size
        val preview = onNodeWithTag("fpga-editor-preview")
        screenshot("c120-preview")
        val size = preview.fetchSemanticsNode().size
        val scale = size.width / 740f
        // Free space found from the board's own rectangles.
        val free = freeArea(board, 60, 40)
        // Until "Drag to edit" is on, a swipe that starts on the picture is left to the list: nothing is drawn.
        val dragMode = onNodeWithTag("fpga-editor-drag-mode")
        dragMode.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Off))
        preview.performTouchInput {
            swipe(Offset(free.x * scale, free.y * scale), Offset((free.x + free.width) * scale, (free.y + free.height) * scale), 600)
        }
        waitForIdle()
        assertEquals(before, editor.draft.resources.size, "A swipe without drag mode adds no resource")
        assertFalse(exists(hasText(t("fpga_resource_type_led"))), "No resource type dialog opens")
        scrollTo(hasTestTag("fpga-editor-drag-mode"))
        onNodeWithTag("fpga-editor-drag-mode").performClick()
        waitForIdle()
        assertAtLeast48(onNodeWithTag("fpga-editor-drag-mode"))
        scrollTo(hasTestTag("fpga-editor-preview"))
        screenshot("c120-drag-mode-on")
        onNodeWithTag("fpga-editor-preview").performTouchInput {
            swipe(Offset(free.x * scale, free.y * scale), Offset((free.x + free.width) * scale, (free.y + free.height) * scale), 600)
        }
        waitForIdle()
        screenshot("c120-new-resource-type")
        if (exists(hasText(t("fpga_resource_type_led")))) {
            onAllNodesWithText(t("fpga_resource_type_led")).let { it[it.fetchSemanticsNodes().lastIndex] }.performClick()
            waitForIdle()
        }
        assertEquals(before + 1, editor.draft.resources.size, "Dragging on free space adds a resource")
        val drawn = checkNotNull(editor.draft.resources.last().rectangle)
        assertTrue(kotlin.math.abs(drawn.x - free.x) <= 2 && kotlin.math.abs(drawn.y - free.y) <= 2 &&
            kotlin.math.abs(drawn.width - free.width) <= 3 && kotlin.math.abs(drawn.height - free.height) <= 3, "$drawn vs $free")
        assertEquals("Led", editor.draft.resources.last().type)
        assertEquals(editor.draft.resources.lastIndex, editor.selected)
        scrollTo(hasTestTag("fpga-editor-preview"))
        screenshot("c120-new-resource")
        // A tap on the U16 LED selects it.
        val ledIndex = board.resources.indexOfFirst { it.pins.singleOrNull()?.location == "U16" }
        val led = checkNotNull(board.resources[ledIndex].rectangle)
        onNodeWithTag("fpga-editor-preview").performTouchInput {
            click(Offset((led.x + led.width / 2f) * scale, (led.y + led.height / 2f) * scale))
        }
        waitForIdle()
        assertEquals(ledIndex, editor.selected)
        // The resource menu shows the tapped resource, numbered from 1.
        scrollTo(hasTestTag("fpga-editor-resource"))
        onNodeWithTag("fpga-editor-resource").assert(hasText(choice(t("fpga_editor_select_resource"), t(editor.draft.resources[ledIndex].menuName(ledIndex)))))
        // The exact position is four number fields.
        for (key in listOf("fpga_editor_rect_x", "fpga_editor_rect_y", "fpga_editor_rect_width", "fpga_editor_rect_height")) {
            scrollTo(hasText(t(key)) and hasSetTextAction(), hasScrollAction())
        }
        screenshot("c120-exact-position")
    }

    private fun freeArea(board: FpgaBoardDefinition, width: Int, height: Int): FpgaRectangle {
        val rects = board.resources.mapNotNull { it.rectangle }
        for (y in 10 until 400 - height step 10) for (x in 10 until 740 - width step 10) {
            if (rects.none { it.x < x + width + 4 && x - 4 < it.x + it.width && it.y < y + height + 4 && y - 4 < it.y + it.height })
                return FpgaRectangle(x, y, width, height)
        }
        error("No free area")
    }

    /** C121: a seven-segment display shows one labelled field per segment and no input fields. */
    @Test
    fun sevenSegmentResourceShowsOneLabelledFieldPerSegment() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(oneBit(), "builtin:BASYS3")
        show(harness)
        openBoardEditor(harness)
        scrollTo(hasText(t("fpga_editor_add_resource")))
        onNodeWithText(t("fpga_editor_add_resource")).performClick()
        waitForIdle()
        chooseTagged("fpga-editor-type", t("fpga_resource_type_sevensegment"), "SevenSegment")
        scrollTo(hasTestTag("fpga-editor-type"))
        screenshot("c121-seven-segment")
        val segments = listOf("A", "B", "C", "D", "E", "F", "G").map { t("fpga_editor_segment", "segment" to it) } +
            t("fpga_editor_segment_dp")
        for (label in segments) scrollTo(hasText(label) and hasSetTextAction())
        scrollTo(hasText(segments.last()) and hasSetTextAction())
        screenshot("c121-seven-segment-fields")
        assertFalse(exists(hasText(t("fpga_editor_input_pins")) and hasSetTextAction()), "No input pins for a display")
        onNode(hasText(segments[0]) and hasSetTextAction()).performTextReplacement("AA1")
        waitForIdle()
        // Segment A is the first entry of OutputPinSet; the other positions wait for their pins.
        val outputs = harness.session.boardEditor.draft.resources.last().pinEntries(FpgaResourceField.OUTPUT)
        assertEquals(listOf("AA1", "", "", "", "", "", "", ""), outputs)
    }

    /** C123: Save selects the resource that has the error and shows it on its field. */
    @Test
    fun saveFailureSelectsTheResourceAndMarksItsField() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(oneBit(), "builtin:BASYS3")
        show(harness)
        openBoardEditor(harness)
        val editor = harness.session.boardEditor
        editor.selected = 3
        val resource = editor.draft.resources[3]
        editor.editResource(resource.copy(fields = resource.fields + (FpgaResourceField.RECTANGLE to "730,295,50,14")))
        editor.selected = 0
        waitForIdle()
        scrollTo(hasText(t("fpga_editor_select_resource"), substring = true))
        screenshot("c123-before-save")
        onNodeWithText(t("fpga_editor_save_board")).performClick()
        waitForIdle()
        awaitQuietly(3_000) { editor.selected == 3 }
        screenshot("c123-after-save")
        assertEquals(3, editor.selected, "Save selects the resource with the error")
        val outside = t("fpga_editor_rect_outside", "width" to 740, "height" to 400)
        assertTrue(exists(hasText(outside, substring = true)), "The field shows its error")
        // The summary names the resource as the menu numbers it, from 1.
        assertTrue(exists(hasText(t(resource.menuName(3)) + ": " + outside)), t(resource.menuName(3)))
        assertEquals("4. ", t(resource.menuName(3)).take(3))
        scrollTo(hasTestTag("fpga-editor-rect-0"))
        screenshot("c123-field-error")
        assertTrue(harness.assets.created.isEmpty())
    }

    /** C123: an unsupported rotation read from a file is shown at the orientation control, not only on Save. */
    @Test
    fun unsupportedRotationIsShownAtTheOrientationField() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(fourBit(), "builtin:ALCHITRY_AU_IO")
        show(harness)
        openBoardEditor(harness)
        val editor = harness.session.boardEditor
        val index = editor.draft.resources.indexOfFirst { it.showsRotation }
        assertTrue(index >= 0)
        editor.selected = index
        val resource = editor.draft.resources[index]
        editor.editResource(resource.copy(fields = resource.fields + (FpgaResourceField.ROTATION to "45")))
        waitForIdle()
        scrollTo(hasTestTag("fpga-editor-rotation-error"))
        onNodeWithTag("fpga-editor-rotation-error").performScrollTo()
        waitForIdle()
        onNodeWithTag("fpga-editor-rotation-error").assert(hasText(t("fpga_editor_value_not_supported")))
        screenshot("c123-rotation-error")
        assertTrue(harness.assets.created.isEmpty())
    }

    /** C118: "Map whole port" is offered only where one resource holds the port; one-pin boards choose each bit's pin. */
    @Test
    fun portHeaderNamesWholePortMappingOnlyWhenAResourceHoldsThePort() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(fourBit(), "builtin:BASYS3")
        show(harness)
        scrollTo(hasTestTag("fpga-port-map-p_led"))
        onNodeWithTag("fpga-port-map-p_led").assert(hasText(t("fpga_port_choose_pins")))
        screenshot("c118-choose-pins")
        onNodeWithTag("fpga-port-map-p_led").performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasTestTag("fpga-pin-picker")) }
        assertTrue(exists(hasText(t("fpga_pin_picker_title", "signal" to "p_led[0]", "board" to "BASYS3"))))
        assertFalse(exists(hasTestTag("fpga-pin-picker-use-port")), "No LED of BASYS3 holds four bits")
        onNodeWithText(t("fpga_board_map_close")).performClick()
        waitForIdle()
        assertTrue("p_led" in harness.session.expandedPorts, "The bit cards open for one-by-one mapping")
        // ALCHITRY_AU_IO's DIP switch holds a 4-bit input: there the header keeps its whole-port action.
        val alchitry = boardFile("ALCHITRY_AU_IO")
        val port = FpgaCircuitRules.reviewForFpga(fourBit(), HdlLanguage.VHDL).design!!.ports.single { it.name == "p_sw" }
        assertTrue(fpgaWholePortFits(alchitry, port))
    }

    /**
     * W3-05 (C118): on BASYS3, whose switches are one-pin resources, "Choose pins" walks through the port: Use moves
     * the picker to the next unmapped bit, and a tapped switch can take the remaining bits onto the switches that
     * follow it in the board file, so a bus is mapped in a few taps instead of one picker per bit.
     */
    @Test
    fun choosePinsWalksThroughThePortAndMapsTheRestOntoTheFollowingSwitches() = runDesktopComposeUiTest(390, 844) { walkScenario("") }

    @Test
    fun choosePinsWalksThroughThePortOnANarrowPhone() = runDesktopComposeUiTest(360, 780) { walkScenario("-360") }

    private fun ComposeUiTest.walkScenario(suffix: String) {
        val harness = Harness(fourBit(), "builtin:BASYS3")
        val board = boardFile("BASYS3")
        fun switch(location: String) = board.resources.first { it.pins.singleOrNull()?.location == location }
        val sw0 = switch("V17")
        show(harness)
        scrollTo(hasTestTag("fpga-port-map-p_sw"))
        onNodeWithTag("fpga-port-map-p_sw").assert(hasText(t("fpga_port_choose_pins"))).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasTestTag("fpga-board-resource-${sw0.id}")) }
        onNodeWithTag("fpga-board-resource-${sw0.id}").performClick()
        waitForIdle()
        onNodeWithTag("fpga-pin-picker-use").performClick()
        waitForIdle()
        assertEquals(FpgaPinBinding.Physical("V17", sw0.id), physical(harness, "p_sw", 0))
        // The picker stays open on the next bit instead of closing after each one.
        assertTrue(exists(hasTestTag("fpga-pin-picker")), "Use moves on to the next bit")
        assertTrue(exists(hasText(t("fpga_pin_picker_title", "signal" to "p_sw[1]", "board" to "BASYS3"))))
        screenshot("w305-picker-next-bit$suffix")
        val sw1 = switch("V16")
        waitUntil(timeoutMillis = 10_000) { exists(hasTestTag("fpga-board-resource-${sw1.id}")) }
        // A tap on the board after the picker moved on reaches the new bit (the tap detector once kept the first one).
        onNodeWithTag("fpga-board-resource-${sw1.id}").performClick()
        waitForIdle()
        assertTrue(onNodeWithTag("fpga-pin-picker-use").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            .orEmpty().joinToString().contains("V16"), "The tapped switch is the candidate for p_sw[1]")
        assertTrue(exists(hasTestTag("fpga-pin-picker-progress") and hasText(t.plural("fpga_pin_picker_progress", 4,
            "port" to "p_sw", "mapped" to 1))), "The picker says how much of the port is mapped")
        screenshot("w305-picker-run$suffix")
        onNodeWithTag("fpga-pin-picker-use-run").assert(hasText(t.plural("fpga_pin_picker_use_run", 3,
            "range" to "p_sw[1–3]", "first" to "V16", "last" to "W17"))).performClick()
        waitForIdle()
        assertEquals(listOf("V17", "V16", "W16", "W17"), (0 until 4).map { physical(harness, "p_sw", it)?.location })
        assertEquals(listOf(sw0.id, sw1.id, switch("W16").id, switch("W17").id), (0 until 4).map { physical(harness, "p_sw", it)?.resourceId })
        assertFalse(exists(hasTestTag("fpga-pin-picker")), "Mapping the rest of the port closes the picker")
        assertFalse(exists(hasTestTag("fpga-diagnostic-unmapped-port-p_sw")), "No unmapped bit is left in p_sw")
        scrollTo(hasTestTag("fpga-port-p_sw"))
        screenshot("w305-port-mapped-by-run$suffix")
    }

    /** C117/C122: the picker's tab is fixed when it opens; a board image decoded later never takes the list away. */
    @Test
    fun pickerKeepsTheListWhenTheBoardImageArrivesLater() = runDesktopComposeUiTest(390, 844) {
        val board = boardFile("BASYS3")
        val port = checkNotNull(FpgaCircuitRules.reviewForFpga(oneBit(), HdlLanguage.VHDL).design).ports.single { it.name == "p_y" }
        var bitmap by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.PORTUGUESE) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        FpgaPinPickerDialog(board, bitmap, port, 0, emptyMap(), onDismiss = {}, onChoose = {}, onChoosePort = {})
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { exists(hasTestTag("fpga-pin-list")) }
        assertFalse(exists(hasText(t("fpga_pin_picker_tab_board"))), "Without a decoded image there is only the list")
        val filter = hasText(t("fpga_filter_pins")) and hasSetTextAction()
        onNode(filter).performTextReplacement("U16")
        waitForIdle()
        bitmap = androidx.compose.ui.graphics.ImageBitmap(740, 400)
        waitForIdle()
        assertTrue(exists(hasText(t("fpga_pin_picker_tab_board"))), "The decoded image adds its tab")
        assertTrue(exists(hasTestTag("fpga-pin-list")), "The list stays open")
        assertFalse(exists(hasTestTag("fpga-pin-board")))
        assertTrue(exists(filter and hasText("U16")), "The filter text is kept")
        screenshot("c117-picker-tab-kept")
    }

    /** C124: the board clock frequency is edited with a unit, shown in the largest whole unit. */
    @Test
    fun clockFrequencyIsShownInMegahertz() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(oneBit(), "builtin:BASYS3")
        show(harness)
        openBoardEditor(harness)
        scrollTo(hasText(t("fpga_editor_clock_pin")) and hasSetTextAction())
        screenshot("c124-clock-section")
        assertTrue(exists(hasText("100") and hasSetTextAction()), "100 MHz is shown as 100")
        assertTrue(exists(hasText("MHz") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)))
    }

    /** At 360 dp the pinned status bar, the port header and the picker keep 48 dp targets without clipping. */
    @Test
    fun narrowPhoneKeepsTheMappingActionsReadable() = runDesktopComposeUiTest(360, 780) {
        val harness = Harness(fourBit(), "builtin:ALCHITRY_AU_IO")
        show(harness)
        scrollTo(hasTestTag("fpga-port-p_led"))
        screenshot("narrow-360-mapping")
        // ALCHITRY_AU_IO uses the Vivado export only; the companion build button shows for open-source flows.
        assertAtLeast48(onNode(hasText(t("fpga_export_project")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)))
        assertAtLeast48(onNodeWithTag("fpga-port-map-p_led"))
        assertAtLeast48(onNodeWithTag("fpga-port-unmap-p_led"))
        onNodeWithTag("fpga-port-map-p_led").performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasTestTag("fpga-pin-board")) }
        screenshot("narrow-360-picker")
        onNodeWithText(t("fpga_pin_picker_tab_list")).performClick()
        waitForIdle()
        screenshot("narrow-360-picker-list")
        onNodeWithText(t("fpga_board_map_close")).performClick()
        waitForIdle()
        openBoardEditor(harness)
        screenshot("narrow-360-board-editor")
    }

    /** C124: the board tick offers Evolution's tick rates as a menu next to the typed value. */
    @Test
    fun tickFrequencyOffersEvolutionsRates() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(project(HostScreenshots.fixture("scripts/ios_fpga_ui/fpga-tick.circ")), "builtin:BASYS3")
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.PORTUGUESE) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        val project = harness.project
                        harness.tool.content(EditorToolContext(project, project.mainCircuit, SimulationState(), emptyList(),
                            applyProject = { harness.project = it }, applyDocument = {}, selectCircuit = {}, selectComponent = {},
                            showStatus = {}, showNotice = {}))
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 20_000) { runCatching { scrollTo(hasTestTag("fpga-tick-presets")) }.isSuccess }
        val field = hasText(t("fpga_tick_frequency_input")) and hasSetTextAction()
        assertTrue(exists(field and hasText("10000000")), "The saved download frequency is typed in the field")
        onNodeWithTag("fpga-tick-presets").assert(hasText(choice(t("fpga_tick_presets"), t("fpga_tick_preset_custom"))))
        onNodeWithTag("fpga-tick-presets").performClick()
        waitForIdle()
        screenshot("c124-tick-presets")
        val fourKilohertz = dev.logisim.mobile.core.TickFrequencies.supported.reversed().indexOf(4000.0)
        onNodeWithTag("fpga-tick-presets-option-$fourKilohertz").performScrollTo().assert(hasText("4 kHz")).performClick()
        waitForIdle()
        // The preset is stored as its frequency, 4000 Hz, not parsed from the label.
        assertTrue(exists(field and hasText("4000")))
        onNodeWithTag("fpga-tick-presets").assert(hasText(choice(t("fpga_tick_presets"), "4 kHz")))
        screenshot("c124-tick-preset-chosen")
    }

    /** C125: vendor names, diagnostic codes and job states are not shown as raw identifiers. */
    @Test
    fun fpgaPanelShowsNoRawIdentifiers() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(oneBit(), "builtin:BASYS3")
        show(harness)
        val raw = Regex("^(ALTERA|XILINX|VIVADO|OPEN_FPGA|UNKNOWN) · ")
        val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text].map { text -> text.text } }
        scrollTo(hasText(" · VHDL", substring = true))
        screenshot("c125-board-summary")
        assertTrue(texts.none { raw.containsMatchIn(it) }, "Raw vendor: ${texts.filter { raw.containsMatchIn(it) }}")
        assertTrue(exists(hasText(t("fpga_vendor_vivado") + " · ", substring = true)))
        val allTexts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text].map { text -> text.text } }
        assertTrue(allTexts.none { it.startsWith("unmapped-port:") }, "No diagnostic code prefix")
    }

    /** C127: a pending request shows a progress bar in the companion and program panels. */
    @Test
    fun pendingRequestsShowProgress() = runDesktopComposeUiTest(390, 844) {
        val board = boardFile("ALCHITRY_AU_IO")
        val plan = FpgaBuildPlan(board, FpgaFlow.OPEN_ECP5, "logisim_fpga_top",
            listOf(dev.logisim.mobile.core.FpgaSourceFile("logisim_fpga_top.v", "module logisim_fpga_top(); endmodule")),
            emptyList(), emptyMap(), emptyList())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transport = object : CompanionTransport {
            override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
                gate.await()
                throw dev.logisim.mobile.core.CompanionTransportException("offline")
            }
        }
        val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8042"),
            CompanionToken.parse("x".repeat(32))), transport)
        val session = FpgaCompanionSession()
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.PORTUGUESE) {
                LogisimTheme(dark = false) { Surface(Modifier.fillMaxSize()) { FpgaCompanionPanel(plan, client, onBack = {}, session = session) } }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithText(t("fpga_companion_prepare")).performClick()
        mainClock.advanceTimeBy(500)
        screenshot("c127-companion-contacting")
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        assertTrue(session.busy)
        assertTrue(exists(progress), "A progress bar shows while the Mac is contacted")
        gate.complete(Unit)
        mainClock.autoAdvance = true
        waitUntil(timeoutMillis = 5_000) { !session.busy }
        assertFalse(exists(progress))
        // A running job shows progress and the note that closing the panel pauses status queries.
        mainClock.autoAdvance = false
        session.job = CompanionJob("a".repeat(32), "fpga-synthesize", CompanionJobState.RUNNING, CompanionJsonNull, null, emptyList())
        mainClock.advanceTimeBy(100)
        assertTrue(exists(progress))
        assertTrue(exists(hasText(t("fpga_companion_no_programming"))))
        assertTrue(exists(hasText(t("fpga_companion_job_status", "state" to t("fpga_companion_state_running")))))
        screenshot("c127-companion-running")
        // The job error carries its code as a tag (fpga-diagnostic-<code>), which the Android tool-error scenario finds.
        session.job = CompanionJob("a".repeat(32), "fpga-synthesize", CompanionJobState.FAILED, CompanionJsonNull,
            dev.logisim.mobile.core.CompanionJobError("fpga-stage-failed", "nextpnr could not place the design", CompanionJsonNull), emptyList())
        mainClock.advanceTimeBy(100)
        onNodeWithTag("fpga-diagnostic-fpga-stage-failed").assert(hasText(t("fpga_companion_error_detail",
            "code" to "fpga-stage-failed", "detail" to "nextpnr could not place the design")))
        screenshot("c127-companion-failed")
    }

    /** C127/C125: the program panel shows progress while it contacts the host and a localized job state. */
    @Test
    fun programPanelShowsProgressAndLocalizedJobState() = runDesktopComposeUiTest(390, 844) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transport = object : CompanionTransport {
            override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
                gate.await()
                throw dev.logisim.mobile.core.CompanionTransportException("offline")
            }
        }
        val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8042"),
            CompanionToken.parse("x".repeat(32))), transport)
        val artifact = dev.logisim.mobile.core.CompanionArtifact.fromBytes("design.bit", byteArrayOf(1, 2, 3))
        val session = FpgaProgramSession()
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.PORTUGUESE) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) { androidx.compose.foundation.layout.Column { FpgaProgramPanel(client, artifact, session) } }
                }
            }
        }
        mainClock.advanceTimeBy(500)
        screenshot("c127-program-contacting")
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        assertTrue(session.busy)
        assertTrue(exists(progress), "A progress bar shows while the program panel contacts the host")
        assertTrue(exists(hasText(t("fpga_companion_contacting"))))
        gate.complete(Unit)
        mainClock.autoAdvance = true
        waitUntil(timeoutMillis = 5_000) { !session.busy }
        assertTrue(exists(hasText(t("fpga_companion_transport_error", "code" to "offline"))),
            "Transport failures read like the companion panel's")
        mainClock.autoAdvance = false
        // A job submitted 75 s ago whose host reported a line of output: elapsed time and that line show under the bar.
        session.submittedAt = kotlin.time.TimeSource.Monotonic.markNow() - kotlin.time.Duration.parse("75s")
        session.job = CompanionJob("a".repeat(32), "fpga-program", CompanionJobState.RUNNING, CompanionJsonNull, null,
            listOf(dev.logisim.mobile.core.CompanionJobLog("stdout", "Opening cable\nProgramming 50%\n")))
        mainClock.advanceTimeBy(100)
        screenshot("c127-program-running")
        assertFalse(exists(hasText("running")), "No raw job state")
        assertTrue(exists(hasText(t("fpga_companion_job_status", "state" to t("fpga_companion_state_running")))))
        assertTrue(exists(progress), "A running job shows progress")
        val elapsed = onNodeWithTag("fpga-program-elapsed").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(elapsed.startsWith(t("fpga_companion_elapsed", "minutes" to 1, "seconds" to "15").dropLast(1)), elapsed)
        onNodeWithTag("fpga-program-latest-log").assert(hasText(t("fpga_companion_latest_log", "line" to "Programming 50%")))
        // A failed job names its error with the code tag the E2E harness finds.
        session.job = CompanionJob("a".repeat(32), "fpga-program", CompanionJobState.FAILED, CompanionJsonNull,
            dev.logisim.mobile.core.CompanionJobError("fpga-program-failed", "cable busy", CompanionJsonNull), emptyList())
        mainClock.advanceTimeBy(100)
        onNodeWithTag("fpga-diagnostic-fpga-program-failed")
            .assert(hasText(t("fpga_companion_error_detail", "code" to "fpga-program-failed", "detail" to "cable busy")))
        assertFalse(exists(hasTestTag("fpga-program-elapsed")), "A finished job shows no elapsed time")
        assertFalse(exists(progress))
        screenshot("c127-program-failed")
    }

    /** R10-1: the segmented choice's label is announced once, by its group. */
    @Test
    fun segmentedChoiceLabelIsAnnouncedOnce() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(oneBit(), "builtin:BASYS3")
        show(harness)
        scrollTo(hasText(t("fpga_clock_role_signal")))
        val group = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup) and
            hasContentDescription(t("fpga_clock_role")))
        assertEquals(1, group.fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText(t("fpga_clock_role")).fetchSemanticsNodes().size,
            "The visible label is not a second TalkBack stop")
    }

    /** X7: the pairing panel starts from the last verified host, and offers the recent ones. */
    @Test
    fun pairingPanelRemembersTheVerifiedHost() = runDesktopComposeUiTest(390, 844) {
        var visible by mutableStateOf(true)
        var preferences by mutableStateOf(EditorPreferences())
        val transport = object : CompanionTransport {
            override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse = CompanionHttpResponse(200,
                ("""{"protocolVersion":1,"operations":[],"limits":{"requestBytes":6000000,"inputBytes":4000000,""" +
                    """"outputBytes":4000000,"timeoutSeconds":30,"processMemoryBytes":1000000,"memoryEnforcement":"none","osSandbox":false}}""")
                    .encodeToByteArray(), "application/json")
        }
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.PORTUGUESE,
                LocalEditorPreferences provides EditorPreferencesAccess(preferences) { preferences = it }) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        if (visible) CompanionPairingPanel(onConnected = { _, _ -> }, onDisconnected = {},
                            clientFactory = { CompanionClient(it, transport) })
                    }
                }
            }
        }
        waitForIdle()
        screenshot("x7-pairing-first")
        onNode(hasText("http://127.0.0.1:8042") and hasSetTextAction()).performTextReplacement("https://mac.local:8042")
        onNode(hasText(t(dev.logisim.mobile.core.localization.Messages.CompanionUiToken)) and hasSetTextAction())
            .performTextReplacement("x".repeat(32))
        onNodeWithText(t(dev.logisim.mobile.core.localization.Messages.CompanionUiVerify)).performClick()
        waitUntil(timeoutMillis = 5_000) { preferences.companionRecentHosts.isNotEmpty() }
        assertEquals(listOf("https://mac.local:8042"), preferences.companionRecentHosts)
        assertFalse(preferences.extraValues.values.any { "x".repeat(32) in it }, "The token is never stored")
        // A fresh panel, as after Disconnect or a relaunch, starts from the verified host.
        visible = false
        waitForIdle()
        visible = true
        waitForIdle()
        screenshot("x7-pairing-reopened")
        assertTrue(exists(hasText("https://mac.local:8042") and hasSetTextAction()), "The host is remembered")
        assertTrue(exists(hasTestTag("companion-recent-host")))
    }
}
