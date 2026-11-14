package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
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
import dev.logisim.mobile.core.CompanionToken
import dev.logisim.mobile.core.CompanionTransport
import dev.logisim.mobile.core.CompanionTransportException
import dev.logisim.mobile.core.FpgaBoardCodec
import dev.logisim.mobile.core.FpgaBoardImportResult
import dev.logisim.mobile.core.FpgaBuildPlan
import dev.logisim.mobile.core.FpgaCircuitRules
import dev.logisim.mobile.core.FpgaFlow
import dev.logisim.mobile.core.FpgaMappingResult
import dev.logisim.mobile.core.FpgaPinBinding
import dev.logisim.mobile.core.FpgaSignalBit
import dev.logisim.mobile.core.HdlLanguage
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.mappingCodec
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import dev.logisim.mobile.localization.Localizer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PKG-10: the FPGA mapping page loads each circuit's saved board map, reports and guards its replacement, and the board
 * editor keeps its draft and offers closed choice sets as menus. Screenshots go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class FpgaPanelHostTest {
    private val t = Localizer(AppLanguage.PORTUGUESE)
    private val boardsDirectory = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "composeApp/src/commonMain/composeResources/files/fpga-boards") }.first { it.isDirectory }

    /** A BASYS3 map made in Evolution: A on switch V17, Y on LED U16. */
    private val savedMap = """
        <boardmap boardname="BASYS3">
            <mc key="/A" map="647,323"/>
            <mc key="/Y" pmap="649_295_0"/>
        </boardmap>
    """.trimIndent()

    private fun fixture(map: String = savedMap) = """
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
    """.trimIndent()

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
        val storage = MemoryStorage()
        val catalog = FpgaBoardCatalogStore(storage).also { it.select(selected) }
        val session = FpgaMappingSession()
        val assets = FakeAssets()
        var project by mutableStateOf(initial)
        /** The circuit being edited, the main circuit when null. */
        var circuit by mutableStateOf<String?>(null)
        var visible by mutableStateOf(true)
        val notices = mutableListOf<String>()
        val tool = fpgaEditorTool(
            reviewProvider = { context ->
                FpgaCircuitRules.reviewForFpga(context.project.copy(mainCircuitName = context.document.name), HdlLanguage.VHDL)
            },
            mappingSession = session, boardCatalog = catalog, boardAssets = assets,
        )
    }

    private fun ComposeUiTest.show(harness: Harness) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.PORTUGUESE) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        if (harness.visible) {
                            val project = harness.project
                            val document = harness.circuit?.let(project::circuit) ?: project.mainCircuit
                            harness.tool.content(EditorToolContext(project, document, SimulationState(), emptyList(),
                                applyProject = { harness.project = it }, applyDocument = {}, selectCircuit = {}, selectComponent = {},
                                showStatus = {}, showNotice = { harness.notices += it.resolve(AppLanguage.PORTUGUESE) }))
                        }
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(t("fpga_editor_edit_board")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(" · VHDL", substring = true).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    private fun ComposeUiTest.scrollTo(matcher: SemanticsMatcher) {
        onAllNodes(hasScrollAction())[0].performScrollToNode(matcher)
        waitForIdle()
    }

    private fun ComposeUiTest.exists(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Waits without failing, so a screenshot can record the state either way. */
    private fun ComposeUiTest.awaitQuietly(timeout: Long = 5_000, condition: () -> Boolean): Boolean =
        runCatching { waitUntil(timeoutMillis = timeout, condition = condition) }.isSuccess

    private fun choice(label: String, selection: String) = t("fpga_choice_label", "label" to label, "selection" to selection)

    private fun ComposeUiTest.choose(label: String, selection: String, option: String, index: Int = 0) {
        scrollTo(hasText(choice(label, selection)))
        onAllNodesWithText(choice(label, selection))[index].performClick()
        waitForIdle()
        onAllNodesWithText(option).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "Missing option $option") }
        onAllNodesWithText(option).let { nodes -> nodes[nodes.fetchSemanticsNodes().lastIndex] }.performClick()
        waitForIdle()
    }

    /** Saving needs the decoded board picture, shown at the top of the editor. */
    private fun ComposeUiTest.awaitBoardPicture() {
        scrollTo(hasText(t("fpga_editor_choose_image")))
        waitUntil(timeoutMillis = 10_000) { exists(androidx.compose.ui.test.hasContentDescription(t("fpga_editor_picture"))) }
    }

    /** Opens the menu tagged [tag] and picks [option]. */
    private fun ComposeUiTest.chooseTagged(tag: String, option: String) {
        scrollTo(hasTestTag(tag))
        onNodeWithTag(tag).performClick()
        waitForIdle()
        onAllNodesWithText(option).let { nodes -> nodes[nodes.fetchSemanticsNodes().lastIndex] }.performClick()
        waitForIdle()
    }

    private val portY get() = "p_y · " + t("fpga_direction_output")

    private fun locations(harness: Harness) = harness.session.currentBindings
        .mapValues { (_, binding) -> (binding as? FpgaPinBinding.Physical)?.location }

    /** Circuits X and Y have the same pins, and each stores its own BASYS3 map. */
    private fun twoCircuits() = project("""
        <project source="5.1.0dev" version="1.0">
          <lib name="0" desc="#Wiring"/><main name="X"/>
          ${circuitXml("X", "647,323", "649_295_0")}
          ${circuitXml("Y", "609,323", "612_295_0")}
        </project>
    """.trimIndent())

    private fun circuitXml(name: String, a: String, y: String) = """
        <circuit name="$name">
          <boardmap boardname="BASYS3">
            <mc key="/A" map="$a"/>
            <mc key="/Y" pmap="$y"/>
          </boardmap>
          <comp lib="0" name="Pin" loc="(20,100)"><a name="label" val="A"/></comp>
          <comp lib="0" name="Pin" loc="(150,100)">
            <a name="label" val="Y"/><a name="type" val="output"/><a name="facing" val="west"/>
          </comp>
          <wire from="(20,100)" to="(150,100)"/>
        </circuit>
    """

    @Test
    fun savedBoardMapIsLoadedWhenThePanelOpens() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(project(fixture()), "builtin:BASYS3")
        show(harness)
        awaitQuietly { exists(hasText("V17", substring = true)) }
        screenshot("mapping-saved-map-loaded-header")
        assertTrue(exists(hasText(t("fpga_map_auto_loaded"))))
        scrollTo(hasText(portY))
        screenshot("mapping-saved-map-loaded")
        // Both bits come from the stored Evolution map without tapping a button.
        assertTrue(exists(hasText("U16", substring = true)), "The saved LED pin of Y is shown")
        assertEquals(setOf("V17", "U16"), harness.session.currentBindings.values
            .map { (it as FpgaPinBinding.Physical).location }.toSet())
        scrollTo(hasText(t("fpga_map_revert")))
        assertFalse(exists(hasText(t("fpga_map_restore"))))
        // The report describes the stored map, so it ends when the bindings are edited.
        chooseTagged("fpga-connection-p_y-0", t("fpga_unmapped"))
        // The report sits under the design summary; bring that item back before looking for it.
        scrollTo(hasText(" · VHDL", substring = true))
        assertTrue(exists(hasText(t.plural("fpga_physical_signals", 2))), "The design summary is composed")
        assertFalse(exists(hasText(t("fpga_map_auto_loaded"))), "Edited bindings are no longer the saved map")
    }

    @Test
    fun brokenSavedMapIsReportedUntilItIsSavedOver() = runDesktopComposeUiTest(390, 844) {
        // Y names a BASYS3 position with no LED, so the stored map cannot be read.
        val harness = Harness(project(fixture(savedMap.replace("649_295_0", "999_999_0"))), "builtin:BASYS3")
        show(harness)
        val banner = t("fpga_map_auto_load_failed", "board" to "BASYS3")
        waitUntil(timeoutMillis = 10_000) { exists(hasText(banner)) }
        val status = harness.session.loadStatus as FpgaMapLoadStatus.Failed
        assertTrue(status.notices.isNotEmpty())
        status.notices.forEach { notice -> assertTrue(exists(hasText(t(notice))), "Missing notice ${t(notice)}") }
        scrollTo(hasText(banner))
        screenshot("mapping-saved-map-unreadable")
        assertEquals(emptyMap(), harness.session.currentBindings, "The defaults stay")
        // Saving over the unreadable map asks first, then the report ends.
        chooseTagged("fpga-connection-p_y-0", t("fpga_open"))
        scrollTo(hasText(banner))
        assertTrue(exists(hasText(banner)), "Editing does not make the stored map readable")
        scrollTo(hasText(t("fpga_map_save")))
        onNodeWithText(t("fpga_map_save")).performClick()
        waitForIdle()
        assertTrue(exists(hasText(t("fpga_map_replace_unreadable"))))
        onNodeWithText(t("fpga_map_replace_confirm")).performClick()
        waitUntil(timeoutMillis = 10_000) { harness.notices.contains(t("fpga_map_saved")) }
        waitUntil(timeoutMillis = 10_000) {
            !exists(hasText(t("diagnostic_running"))) && exists(hasText(" · VHDL", substring = true))
        }
        assertEquals(null, harness.session.loadStatus)
        assertFalse(exists(hasText(banner)), "The saved map replaced the unreadable one")
        assertFalse(CircCodec.writeProject(harness.project).contains("999_999_0"))
        scrollTo(hasText(" · VHDL", substring = true))
        screenshot("mapping-saved-over-unreadable")
    }

    @Test
    fun eachCircuitShowsItsOwnSavedMap() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(twoCircuits(), "builtin:BASYS3")
        show(harness)
        val a = FpgaSignalBit("p_a")
        val y = FpgaSignalBit("p_y")
        waitUntil(timeoutMillis = 10_000) { locations(harness) == mapOf(a to "V17", y to "U16") }
        // An unsaved edit in X, then the same board in circuit Y of the same project.
        chooseTagged("fpga-connection-p_a-0", t("fpga_unmapped"))
        val editedX = harness.session.currentBindings
        harness.circuit = "Y"
        waitUntil(timeoutMillis = 20_000) { locations(harness) == mapOf(a to "V16", y to "E19") }
        waitUntil(timeoutMillis = 10_000) { exists(hasText(t("fpga_map_auto_loaded"))) }
        scrollTo(hasText(portY))
        screenshot("mapping-second-circuit-own-map")
        assertTrue(exists(hasText("E19", substring = true)), "Y's own LED pin is shown, not X's")
        // Saving Y writes Y's own map back: no confirmation, and X's pins never reach Y.
        scrollTo(hasText(t("fpga_map_save")))
        onNodeWithText(t("fpga_map_save")).performClick()
        waitForIdle()
        assertFalse(exists(hasText(t("fpga_map_replace_confirm"))), "Y's map is not replaced by fewer bits")
        waitUntil(timeoutMillis = 10_000) { harness.notices.contains(t("fpga_map_saved")) }
        val circuitY = checkNotNull(harness.project.circuit("Y"))
        val reread = FpgaCircuitRules.reviewForFpga(harness.project.copy(mainCircuitName = "Y"), HdlLanguage.VHDL)
            .mappingCodec(circuitY)!!.loadSaved(checkNotNull(harness.session.board.value), FpgaFlow.VIVADO)
        assertEquals(mapOf(a to "V16", y to "E19"), (reread as FpgaMappingResult.Loaded).mapping.bindings
            .mapValues { (_, binding) -> (binding as? FpgaPinBinding.Physical)?.location })
        // Back in X, its unsaved edit is intact.
        harness.circuit = "X"
        waitUntil(timeoutMillis = 20_000) { harness.session.currentBindings == editedX }
        assertEquals(mapOf(y to "U16"), locations(harness))
    }

    @Test
    fun savingAPartialMapOverTheSavedOneAsksFirst() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(project(fixture()), "builtin:BASYS3")
        show(harness)
        // Unmap Y, then try to save: the stored map binds 2 bits, the new one only 1.
        if (awaitQuietly { harness.session.currentBindings.size == 2 }) chooseTagged("fpga-connection-p_y-0", t("fpga_unmapped"))
        scrollTo(hasText(t("fpga_map_save")))
        onNodeWithText(t("fpga_map_save")).performClick()
        waitForIdle()
        screenshot("mapping-replace-confirmation")
        val prompt = t("fpga_map_replace_title", "board" to "BASYS3")
        assertTrue(exists(hasText(prompt)), "Saving fewer bits over the saved map asks first")
        assertTrue(CircCodec.writeProject(harness.project).contains("""pmap="649_295_0""""), "Nothing is written before confirming")
        onNodeWithText(t("fpga_map_replace_confirm")).performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { !CircCodec.writeProject(harness.project).contains("""pmap="649_295_0"""") }
        assertTrue(harness.notices.contains(t("fpga_map_saved")))
    }

    @Test
    fun savingTheUnchangedLoadedMapKeepsTheCircuitXml() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(project(fixture()), "builtin:BASYS3")
        show(harness)
        waitUntil(timeoutMillis = 10_000) { locations(harness).values.toSet() == setOf("V17", "U16") }
        val loaded = harness.session.currentBindings
        scrollTo(hasText(t("fpga_map_save")))
        onNodeWithText(t("fpga_map_save")).performClick()
        waitForIdle()
        assertFalse(exists(hasText(t("fpga_map_replace_confirm"))), "An unchanged map needs no confirmation")
        waitUntil(timeoutMillis = 10_000) { harness.notices.isNotEmpty() }
        assertEquals(listOf(t("fpga_map_saved")), harness.notices)
        // The re-keyed panel keeps its bindings without reloading, and saving again gives the same bytes.
        waitUntil(timeoutMillis = 10_000) {
            !exists(hasText(t("diagnostic_running"))) && exists(hasText(" · VHDL", substring = true))
        }
        scrollTo(hasText(t("fpga_map_save")))
        assertEquals(null, harness.session.pendingLoad)
        assertEquals(loaded, harness.session.currentBindings)
        val first = CircCodec.writeProject(harness.project)
        val extraXml = harness.project.mainCircuit.extraXml
        scrollTo(hasText(t("fpga_map_save")))
        onNodeWithText(t("fpga_map_save")).performClick()
        waitUntil(timeoutMillis = 10_000) { harness.notices.size == 2 }
        assertEquals(extraXml, harness.project.mainCircuit.extraXml)
        assertEquals(first, CircCodec.writeProject(harness.project))
        assertEquals(loaded, harness.session.currentBindings)
    }

    @Test
    fun boardEditorOffersIoStandardsAsAMenuAndKeepsTheDraft() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(project(fixture("")), "builtin:BASYS3")
        show(harness)
        onNodeWithText(t("fpga_editor_edit_board")).performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { exists(hasText(t("fpga_editor_save_board"))) }
        awaitBoardPicture()
        screenshot("board-editor-resource")
        runCatching { scrollTo(hasText(t("fpga_editor_io_standard"), substring = true)); screenshot("board-editor-io-standard-field") }
        // Change the first resource's I/O standard by picking it, not by retyping its name.
        scrollTo(hasTestTag("fpga-editor-standard"))
        onNodeWithTag("fpga-editor-standard").assert(hasText(choice(t("fpga_editor_io_standard"), "LVCMOS33"))).performClick()
        waitForIdle()
        screenshot("board-editor-io-standard-menu")
        onNodeWithText("LVCMOS18").performClick()
        waitForIdle()
        assertTrue(harness.session.boardEditor.dirty)
        assertEquals("LVCMOS18", harness.session.boardEditor.draft.resources.first().fields[FpgaResourceField.STANDARD])
        // Closing the sheet keeps the draft: the editor reopens with the change.
        harness.visible = false
        waitForIdle()
        harness.visible = true
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { exists(hasText(t("fpga_editor_save_board"))) }
        scrollTo(hasText(choice(t("fpga_editor_io_standard"), "LVCMOS18")))
        screenshot("board-editor-draft-after-reopen")
        awaitBoardPicture()
        onNodeWithText(t("fpga_editor_save_board")).performClick()
        waitUntil(timeoutMillis = 20_000) { harness.assets.created.isNotEmpty() }
        val xml = harness.assets.created.single().bytes.decodeToString()
        assertTrue("""FPGAPinIOStandard="LVCMOS18"""" in xml)
        val board = (FpgaBoardCodec.read(xml) as FpgaBoardImportResult.Success).board
        assertEquals("LVCMOS18", board.resources.first().pins.first().electrical.ioStandard)
        // A successful save closes the editor and resets its draft.
        assertFalse(harness.session.boardEditor.open)
    }

    @Test
    fun cancellingAnEditedBoardAsksBeforeDiscarding() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(project(fixture("")), "builtin:BASYS3")
        show(harness)
        onNodeWithText(t("fpga_editor_edit_board")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText(t("fpga_editor_save_board"))) }
        scrollTo(hasText(t("fpga_editor_remove_resource")))
        onNodeWithText(t("fpga_editor_remove_resource")).performClick()
        waitForIdle()
        screenshot("board-editor-resource-removed")
        assertTrue(exists(hasText(t("fpga_editor_resource_removed"))))
        onNodeWithText(t("fpga_editor_undo")).performClick()
        waitForIdle()
        assertFalse(exists(hasText(t("fpga_editor_resource_removed"))))
        onNodeWithText(t("fpga_editor_remove_resource")).performClick()
        waitForIdle()
        onNodeWithText(t("action_cancel")).performClick()
        waitForIdle()
        screenshot("board-editor-discard-confirmation")
        assertTrue(exists(hasText(t("fpga_editor_discard_title"))))
        onNodeWithText(t("fpga_editor_keep_editing")).performClick()
        waitForIdle()
        assertTrue(exists(hasText(t("fpga_editor_save_board"))))
    }

    @Test
    fun ledArrayAndScanningDisplaysUseStructuredControls() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(project(fixture("")), "builtin:BASYS3")
        show(harness)
        onNodeWithText(t("fpga_editor_edit_board")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText(t("fpga_editor_save_board"))) }
        scrollTo(hasText(t("fpga_editor_add_resource")))
        onNodeWithText(t("fpga_editor_add_resource")).performClick()
        waitForIdle()
        chooseTagged("fpga-editor-type", t("fpga_resource_type_ledarray"))
        scrollTo(hasTestTag("fpga-editor-display-mode"))
        screenshot("board-editor-led-array")
        chooseTagged("fpga-editor-rows", "8")
        chooseTagged("fpga-editor-columns", "8")
        chooseTagged("fpga-editor-display-mode", t("fpga_editor_mode_led_rows"))
        scrollTo(hasTestTag("fpga-editor-display-mode"))
        screenshot("board-editor-led-array-row-scanning")
        assertEquals("8,8,LedRowScanning", harness.session.boardEditor.draft.resources.last().fields[FpgaResourceField.DISPLAY])
        scrollTo(hasTestTag("fpga-editor-rotation"))
        onNodeWithTag("fpga-editor-rotation").performClick()
        waitForIdle()
        screenshot("board-editor-rotation-menu")
        onNodeWithText(t("fpga_editor_rotation_led_cw90")).performClick()
        waitForIdle()
        assertEquals("-90", harness.session.boardEditor.draft.resources.last().fields[FpgaResourceField.ROTATION])
        scrollTo(hasTestTag("fpga-editor-drive"))
        onNodeWithTag("fpga-editor-drive").performClick()
        waitForIdle()
        screenshot("board-editor-drive-menu")
        onNodeWithText(t("fpga_editor_drive_value", "drive" to 8)).performClick()
        waitForIdle()
        // One labelled field per pin the configuration drives (W2-05), instead of one comma-separated list.
        for (pin in 0 until 11) {
            scrollTo(hasTestTag("fpga-editor-pin-output-$pin"))
            onNodeWithTag("fpga-editor-pin-output-$pin").performTextReplacement("LA$pin")
        }
        waitForIdle()
        assertTrue(exists(hasText(t.plural("fpga_editor_display_pins", 11), substring = true)))
        awaitBoardPicture()
        onNodeWithText(t("fpga_editor_save_board")).performClick()
        waitUntil(timeoutMillis = 20_000) { harness.assets.created.isNotEmpty() }
        val saved = harness.assets.created.single().bytes.decodeToString()
        assertTrue("""LedArrayInfo="8,8,LedRowScanning"""" in saved)
        assertTrue("""rotation="-90"""" in saved && """FPGAPinDriveStrength="8 mA"""" in saved)
    }

    @Test
    fun displayChannelIsChosenByDigitAndSegment() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(project(fixture("")), "builtin:ALCHITRY_AU_IO")
        show(harness)
        chooseTagged("fpga-connection-p_y-0", t("fpga_scan_display"))
        scrollTo(hasTestTag("fpga-scan-summary-p_y-0"))
        screenshot("display-binding-default")
        assertTrue(exists(hasText(choice(t("fpga_scan_display"), t("fpga_scan_type_seven_segment")))), "No raw resource id")
        chooseTagged("fpga-scan-row-p_y-0", t("fpga_scan_digit_value", "digit" to 3))
        chooseTagged("fpga-scan-column-p_y-0", t("fpga_scan_segment_value", "segment" to "c"))
        val summary = t("fpga_scan_summary", "position" to t("fpga_scan_digit_value", "digit" to 3) + " · " +
            t("fpga_scan_segment_value", "segment" to "c"), "channel" to 18)
        scrollTo(hasTestTag("fpga-scan-summary-p_y-0"))
        screenshot("display-binding-digit-segment")
        assertTrue(exists(hasText(summary)), summary)
        // No free-text channel field remains.
        assertFalse(exists(hasText(t("fpga_scan_channel")) and hasSetTextAction()))
        val binding = harness.session.currentBindings.getValue(FpgaSignalBit("p_y"))
        assertEquals(18, (binding as FpgaPinBinding.Display).channel)
        // A channel outside the display, as an older map may hold, is reported instead of a position.
        harness.session.editBindings(mapOf(FpgaSignalBit("p_y") to binding.copy(channel = 999)))
        waitForIdle()
        scrollTo(hasTestTag("fpga-scan-summary-p_y-0"))
        screenshot("display-binding-invalid-channel")
        onNodeWithTag("fpga-scan-summary-p_y-0").assert(hasText(t("fpga_invalid_display_channel")))
        assertTrue(exists(hasText(choice(t("fpga_scan_digit"), t("fpga_scan_unset")))), "No digit is chosen")
    }

    @Test
    fun removingAnExternalBoardCanBeUndone() = runDesktopComposeUiTest(390, 844) {
        val xml = File(boardsDirectory, "BASYS3.xml").readText().replace("<BASYS3>", "<WORKSHOP>").replace("</BASYS3>", "</WORKSHOP>")
        val board = (FpgaBoardCodec.read(xml) as FpgaBoardImportResult.Success).board
        val harness = Harness(project(fixture("")), "builtin:BASYS3")
        val id = checkNotNull(harness.catalog.add(board).selected)
        show(harness)
        val external = t("fpga_board_catalog_external", "name" to "WORKSHOP")
        assertTrue(exists(hasText(choice(t("fpga_board_catalog"), external))))
        screenshot("catalog-external-board")
        onNodeWithText(t("fpga_board_catalog_remove")).performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { !exists(hasText(choice(t("fpga_board_catalog"), external))) }
        screenshot("catalog-external-board-removed")
        assertTrue(exists(hasText(t("fpga_board_catalog_removed", "name" to "WORKSHOP"))))
        onNodeWithText(t("fpga_editor_undo")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText(choice(t("fpga_board_catalog"), external))) }
        assertEquals(id, FpgaBoardCatalogStore(harness.storage).load().selected)
    }

    @Test
    fun controlsExposeTheirRolesAndStates() = runDesktopComposeUiTest(390, 844) {
        val harness = Harness(project(fixture("")), "builtin:BASYS3")
        show(harness)
        val boardChoice = onNodeWithText(choice(t("fpga_board_catalog"), "BASYS3"))
        boardChoice.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
        // TalkBack reads "Board: BASYS3, collapsed, drop-down list": the selection once, from the visible text, and
        // whether the menu is open (W3-05), with the expand and collapse actions Material's exposed menus offer.
        val boardConfig = boardChoice.fetchSemanticsNode().config
        assertEquals(t("fpga_port_collapsed"), boardConfig.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(SemanticsActions.Expand in boardConfig, "A closed menu can be expanded")
        assertFalse(SemanticsActions.Collapse in boardConfig)
        assertEquals(null, boardConfig.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(listOf(choice(t("fpga_board_catalog"), "BASYS3")), boardConfig.getOrNull(SemanticsProperties.Text)?.map { it.text })
        boardChoice.performClick()
        waitForIdle()
        val openConfig = onNode(hasText(choice(t("fpga_board_catalog"), "BASYS3")) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList)).fetchSemanticsNode().config
        assertEquals(t("fpga_port_expanded"), openConfig.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(SemanticsActions.Collapse in openConfig, "An open menu can be collapsed")
        // The open menu marks the current board as selected.
        onNode(hasTestTag("fpga-board-catalog-option-3")).assert(hasText("BASYS3")).assertIsSelected()
        screenshot("board-menu-selected-item")
        onNode(hasTestTag("fpga-board-catalog-option-3")).performClick()
        waitForIdle()
        scrollTo(hasText(t("fpga_clock_role_signal")))
        onNode(hasText(t("fpga_clock_role_signal")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        // The radio group carries its label, so its options are not announced without it.
        val clockGroup = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup) and
            hasContentDescription(t("fpga_clock_role")))
        assertEquals(1, clockGroup.fetchSemanticsNodes().size)
        clockGroup[0].onChildren().filterToOne(hasText(t("fpga_clock_role_signal"))).assertIsSelected()
        // One heading per port, on its first bit card.
        scrollTo(hasText(portY))
        onNodeWithText(portY).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        scrollTo(hasText(t("fpga_editor_edit_board")))
        onNodeWithText(t("fpga_editor_edit_board")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText(t("fpga_editor_save_board"))) }
        scrollTo(hasText(t("fpga_editor_active_low")))
        val activeLow = onNode(hasText(t("fpga_editor_active_low")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        activeLow.assertIsToggleable().assertHeightIsAtLeast(48.dp)
        activeLow.performClick()
        waitForIdle()
        assertTrue(harness.session.boardEditor.draft.resources.first().activeLow, "Tapping the label toggles the checkbox")
        onAllNodes(hasText(t("fpga_editor_pull_float")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .let { it[it.fetchSemanticsNodes().lastIndex] }.assertIsSelected().assertHeightIsAtLeast(48.dp)
        screenshot("board-editor-active-low")
    }

    @Test
    fun companionBuildGoalIsARadioChoice() = runDesktopComposeUiTest(390, 844) {
        val transport = object : CompanionTransport {
            override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse =
                throw CompanionTransportException("offline")
        }
        val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8042"),
            CompanionToken.parse("x".repeat(32))), transport)
        val board = (FpgaBoardCodec.read(File(boardsDirectory, "ALCHITRY_AU_IO.xml").readText()) as FpgaBoardImportResult.Success).board
        val plan = FpgaBuildPlan(board, FpgaFlow.OPEN_ECP5, "logisim_fpga_top", emptyList(), emptyList(), emptyMap(), emptyList())
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.PORTUGUESE) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) { FpgaCompanionPanel(plan, client, onBack = {}) }
                }
            }
        }
        waitForIdle()
        screenshot("companion-build-goals")
        onNode(hasText(t("fpga_companion_goal_synthesis")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        onNode(hasText(t("fpga_companion_goal_bitstream")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).performClick()
        waitForIdle()
        onNode(hasText(t("fpga_companion_goal_bitstream")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).assertIsSelected()
    }

    /** W3-05 (C129): the programming targets are radio rows in one group, and only the chosen one is selected. */
    @Test
    fun programmingTargetIsARadioChoice() = runDesktopComposeUiTest(390, 844) {
        val transport = object : CompanionTransport {
            override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
                if (!request.url.endsWith("/capabilities")) throw CompanionTransportException("offline")
                return CompanionHttpResponse(200, """{"protocolVersion":1,"operations":[{"name":"fpga-program","allowed":true,
                    "available":true,"requiresConfirmation":true,"description":"","missingTools":[],"toolAlternatives":[],
                    "programTargets":["bench","lab"]}],"limits":{"requestBytes":6000000,"inputBytes":4000000,
                    "outputBytes":4000000,"timeoutSeconds":30,"processMemoryBytes":1000000,"memoryEnforcement":"none",
                    "osSandbox":false}}""".encodeToByteArray(), "application/json")
            }
        }
        val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8042"),
            CompanionToken.parse("x".repeat(32))), transport)
        val artifact = dev.logisim.mobile.core.CompanionArtifact.fromBytes("design.bit", byteArrayOf(1, 2, 3))
        val session = FpgaProgramSession()
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.PORTUGUESE) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) { androidx.compose.foundation.layout.Column { FpgaProgramPanel(client, artifact, session) } }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { session.targets.size == 2 && !session.busy }
        waitForIdle()
        fun target(name: String) = onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        target("bench").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsNotSelected()
        target("lab").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsNotSelected()
        target("lab").assertHeightIsAtLeast(48.dp).performClick()
        waitForIdle()
        target("lab").assertIsSelected()
        target("bench").assertIsNotSelected()
        assertEquals("lab", session.target)
        // Both rows sit in one selectable group, so TalkBack reads "1 of 2" style positions within it.
        val group = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        assertEquals(1, group.fetchSemanticsNodes().size)
        group[0].onChildren().filterToOne(hasText("lab")).assertIsSelected()
        screenshot("w305-program-targets")
    }
}
