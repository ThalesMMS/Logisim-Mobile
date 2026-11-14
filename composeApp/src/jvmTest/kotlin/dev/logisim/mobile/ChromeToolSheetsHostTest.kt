package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.logisim.mobile.core.AnnotationLibrary
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.WorkspaceCatalogRepository
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.WorkspaceMutationResult
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Wave 3 package W3-03 (chrome and tool sheets), driven through the whole editor at phone size. Each scenario writes
 * what it observed to `behavior-w303-<scenario>.txt` beside its screenshots (`-PuiScreenshots=<dir>`) before asserting,
 * so a run on the original code records the flaw.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ChromeToolSheetsHostTest {
    private val fixture = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")
    private val english = AppLanguage.ENGLISH
    private val portuguese = AppLanguage.PORTUGUESE

    /** Saved by Evolution 2.7.1, so it opens with an informational report announced in the status line (C145). */
    private val versionDefaultsXml = """<project source="2.7.1" version="1.0"><lib name="0" desc="#Wiring"/><main name="main"/><circuit name="main"><comp lib="0" name="Pin" loc="(100,100)"><a name="output" val="false"/></comp></circuit></project>"""

    @Suppress("DEPRECATION")
    private class TestClipboard(var content: String? = null) : ClipboardManager {
        override fun getText(): AnnotatedString? = content?.let(::AnnotatedString)
        override fun setText(annotatedString: AnnotatedString) { content = annotatedString.text }
    }

    private class RecordingAssets : DocumentAssetActions {
        val created = mutableListOf<DocumentAsset>()
        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) = onResult(AssetOpenResult.Cancelled)
        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) { created += asset; onResult(AssetActionResult.Completed) }
        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
    }

    /** The keyboard each focused field asks the platform for. */
    private val imeRequests = mutableListOf<ImeOptions>()

    @Suppress("DEPRECATION")
    private fun ComposeUiTest.app(
        state: EditorState,
        language: AppLanguage = english,
        assets: DocumentAssetActions? = null,
        clipboard: ClipboardManager = TestClipboard(),
    ) {
        val header = EditorWorkspaceHeader("", "").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        setContent {
            InterceptPlatformTextInput({ request, next ->
                imeRequests += request.imeOptions
                next.startInputMethod(request)
            }) {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalClipboardManager provides clipboard) {
                    LogisimTheme(dark = false) {
                        App(initialXml = fixture, language = language, workspaceHeader = header, assets = assets)
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun observe(scenario: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-w303-$scenario.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (name, value) -> "$name\t$value" })
    }

    private fun text(key: String, language: AppLanguage = english, vararg arguments: Pair<String, Any?>) =
        message(key, *arguments).resolve(language)

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.activate(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.settle() {
        mainClock.advanceTimeBy(1000)
        waitForIdle()
    }

    /** Opens menu [id] from the menu bar or the compact menu, going back first when another menu is open. */
    private fun ComposeUiTest.openMenu(id: String) {
        if (exists("menu-back")) activate("menu-back")
        else if (exists("editor-project-toolbar-overflow")) activate("editor-project-toolbar-overflow")
        activate("menu-title-$id")
    }

    private fun ComposeUiTest.openTool(menu: String, id: String) {
        openMenu(menu)
        onNodeWithTag("menu-project-tool-$id").performScrollTo().performClick()
        settle()
    }

    /** Clicks the button labelled [label] inside the open tool sheet. */
    private fun ComposeUiTest.clickInSheet(label: String) {
        onNode(hasText(label) and hasAnyAncestor(hasTestTag("editor-tool-sheet")) and
            SemanticsMatcher("clickable") { it.config.contains(SemanticsActions.OnClick) }, useUnmergedTree = false)
            .performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        settle()
    }

    private fun SemanticsNode.allText(): String =
        (config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.map { it.allText() }).joinToString(" ")

    /** The nodes inside the open tool sheet whose own text is [value], with where they are drawn. */
    private fun ComposeUiTest.inSheet(value: String): List<SemanticsNode> = onAllNodes(
        hasText(value) and hasAnyAncestor(hasTestTag("editor-tool-sheet")), useUnmergedTree = true).fetchSemanticsNodes()

    private fun ComposeUiTest.sheetBounds(): Rect = onNodeWithTag("editor-tool-sheet").fetchSemanticsNode().boundsInWindow

    private fun Rect.containsRect(other: Rect) = other.left >= left - 1 && other.top >= top - 1 &&
        other.right <= right + 1 && other.bottom <= bottom + 1 && other.height > 0f

    private fun ComposeUiTest.paneTitles(): List<String> = onAllNodes(SemanticsMatcher("pane") {
        it.config.contains(SemanticsProperties.PaneTitle)
    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.PaneTitle] }

    private fun ComposeUiTest.fieldText(tag: String): String =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun ComposeUiTest.statusLiveRegion(): LiveRegionMode? =
        onNodeWithTag("editor-status", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)

    /** Every node on screen, in any window layer, whose own text is [value], apart from the status line behind sheets. */
    private fun ComposeUiTest.shownAnywhere(value: String): List<SemanticsNode> = onAllNodes(
        hasText(value) and !hasTestTag("editor-status") and !hasAnyAncestor(hasTestTag("editor-status")), useUnmergedTree = true)
        .fetchSemanticsNodes()

    // C74: a result a tool reports only through the editor status (EditorToolContext.showNotice) is shown inside the
    // open sheet, not behind it, and is announced politely.
    @Test fun aToolResultIsShownInsideTheOpenSheet() = toolResultInsideTheSheet(390, 844, "")
    @Test fun aToolResultIsShownInsideTheOpenSheetOnANarrowPhone() = toolResultInsideTheSheet(360, 780, "-360")

    private fun toolResultInsideTheSheet(width: Int, height: Int, suffix: String) = runDesktopComposeUiTest(width, height) {
        val state = EditorState(fixture)
        app(state)
        openTool("project", "annotations")
        screenshot("w303-annotation-sheet$suffix")
        val before = state.document.components.size
        clickInSheet(text("annotation_insert_text"))
        screenshot("w303-annotation-inserted-notice$suffix")
        val saved = text("annotation_saved")
        val shown = inSheet(saved)
        val sheet = sheetBounds()
        val inBounds = shown.any { sheet.containsRect(it.boundsInWindow) }
        val polite = shown.any { node -> generateSequence(node) { it.parent }.any {
            it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite } }
        // The status line behind the sheet takes the same result; it is not a second live region meanwhile.
        val statusLiveWhileOpen = statusLiveRegion()
        observe("tool-notice$suffix", "inserted" to (state.document.components.size - before), "status" to state.statusText.resolve(english),
            "shownInSheet" to shown.size, "inSheetBounds" to inBounds, "politeLiveRegion" to polite,
            "statusLiveRegionWhileOpen" to statusLiveWhileOpen)
        assertEquals(1, state.document.components.size - before)
        assertEquals(1, shown.size, "the result is shown once inside the sheet")
        assertTrue(inBounds, "the notice is drawn within the sheet")
        assertTrue(polite, "the notice is a polite live region")
        assertEquals(null, statusLiveWhileOpen, "only the sheet's notice announces the result")
        // The notice can be dismissed with a labelled 48 dp control.
        val dismiss = onNodeWithTag("editor-tool-sheet-notice-dismiss").fetchSemanticsNode()
        assertEquals(text("tool_sheet_notice_dismiss"), dismiss.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString())
        assertTrue(dismiss.size.width >= 48 * density.density && dismiss.size.height >= 48 * density.density, "${dismiss.size}")
        activate("editor-tool-sheet-notice-dismiss")
        assertEquals(0, inSheet(saved).size, "dismissed")
        clickInSheet(text("annotation_insert_text"))
        assertEquals(1, inSheet(saved).size, "the same result reported again is shown again")
        // Closing the tool takes its notice along; the status line keeps the result.
        activate("editor-tool-close")
        settle()
        assertEquals(saved, state.statusText.resolve(english))
        assertEquals(LiveRegionMode.Polite, statusLiveRegion(), "with the sheet closed the status line announces again")
        openTool("project", "annotations")
        assertEquals(0, inSheet(saved).size, "a reopened tool starts without the previous result")
    }

    // C74: HDL export's Save result (saveHdlSource -> onNotice) is shown in the sheet; Copy keeps its inline notice and
    // the sheet does not repeat it.
    @Test fun hdlSaveResultIsShownInTheSheetAndCopyIsNotRepeated() = hdlNotices(390, 844, "")
    @Test fun hdlSaveResultIsShownInTheSheetOnANarrowPhone() = hdlNotices(360, 780, "-360")

    private fun hdlNotices(width: Int, height: Int, suffix: String) = runDesktopComposeUiTest(width, height) {
        val state = EditorState(fixture)
        val assets = RecordingAssets()
        val clipboard = TestClipboard()
        app(state, assets = assets, clipboard = clipboard)
        openTool("fpga", "hdl-export")
        screenshot("w303-hdl-export$suffix")
        clickInSheet(text("hdl_save_file"))
        val savedName = assets.created.singleOrNull()?.name
        val saved = savedName?.let { text(Messages.StatusFileSaved, english, "name" to it) }
        val savedShown = saved?.let { inSheet(it) }.orEmpty()
        val sheet = sheetBounds()
        val savedRects = savedShown.map { it.boundsInWindow }
        screenshot("w303-hdl-export-saved$suffix")
        clickInSheet(text("hdl_copy"))
        val copied = inSheet(text("hdl_source_copied"))
        val copyInline = onAllNodesWithTag("hdl-export-notice", useUnmergedTree = true).fetchSemanticsNodes().map { it.allText() }
        screenshot("w303-hdl-export-copied$suffix")
        observe("hdl-notices$suffix", "created" to assets.created.map { it.name }, "savedShownInSheet" to savedShown.size,
            "savedInBounds" to savedRects.any { sheet.containsRect(it) }, "copiedShownInSheet" to copied.size,
            "copyInline" to copyInline, "clipboard" to clipboard.content?.take(40))
        assertTrue(saved != null, "Save file wrote the source")
        assertEquals(1, savedShown.size, "the saved result is shown once inside the sheet")
        assertTrue(savedRects.all { sheet.containsRect(it) }, "drawn within the sheet: $savedRects in $sheet")
        assertEquals(1, copied.size, "Copy's result is shown once, under its button: $copyInline")
        assertTrue(copyInline.any { text("hdl_source_copied") in it })
    }

    // C39: new text starts at the centre of the circuit in view, not at a fixed (100,100) that may be off-screen, and
    // X opens the number pad.
    @Test
    fun newAnnotationsStartInTheCircuitInView() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        // Scroll the canvas far from the origin, as after panning to another part of a large circuit.
        val current = state.viewport
        state.updateViewport(current.copy(view = CircuitViewport(current.view.scale.coerceAtLeast(0.5f),
            Offset(-3000f, -2400f)), initialized = true, fitted = false))
        waitForIdle()
        val view = state.viewport
        val size: IntSize = view.size
        val topLeft = view.view.world(Offset.Zero)
        val bottomRight = view.view.world(Offset(size.width.toFloat(), size.height.toFloat()))
        val centre = view.view.world(Offset(size.width / 2f, size.height / 2f))
        val expectedX = (centre.x / 10f).roundToInt() * 10
        val expectedY = (centre.y / 10f).roundToInt() * 10
        openTool("project", "annotations")
        val shownX = fieldText("annotation-x")
        val shownY = fieldText("annotation-y")
        onNodeWithTag("annotation-x").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        val keyboard = imeRequests.lastOrNull()?.keyboardType
        screenshot("w303-annotation-position")
        clickInSheet(text("annotation_insert_text"))
        val inserted = state.document.components.lastOrNull { it.kind == AnnotationLibrary.Text }
        val position = inserted?.position
        val visible = position != null && position.x >= topLeft.x && position.x <= bottomRight.x &&
            position.y >= topLeft.y && position.y <= bottomRight.y
        observe("annotation-position", "viewSize" to size, "visibleWorld" to "$topLeft..$bottomRight",
            "expected" to "$expectedX,$expectedY", "shown" to "$shownX,$shownY", "inserted" to position,
            "visible" to visible, "keyboardType" to keyboard, "imeRequests" to imeRequests.size)
        assertTrue(size.width > 0 && size.height > 0, "the canvas was measured")
        assertEquals("$expectedX,$expectedY", "$shownX,$shownY", "X and Y start at the centre of the view")
        assertTrue(visible, "the inserted text lies in the circuit in view: $position")
        assertEquals(KeyboardType.Number, keyboard, "X opens the number pad")
    }

    // C159: the placement attributes sheet, the inspector, a tool and Simulation options each announce their own title.
    @Test
    fun everySheetAnnouncesItsOwnTitle() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, portuguese)
        val generic = text("a11y_sheet_pane", portuguese)
        val titles = mutableMapOf<String, List<String>>()

        state.place(ComponentKind.INPUT)
        waitForIdle()
        activate("placement-attributes")
        settle()
        titles["placement"] = paneTitles()
        screenshot("w303-placement-sheet-title")
        activate("placement-attributes-done")
        settle()
        state.changeMode(EditorMode.INTERACT)
        waitForIdle()

        val component = state.document.components.first()
        state.select(component.id)
        waitForIdle()
        activate("tool-properties")
        settle()
        titles["inspector"] = paneTitles()
        onNodeWithTag("editor-sheet-scrim").performSemanticsAction(SemanticsActions.OnClick)
        settle()

        openTool("project", "annotations")
        titles["tool"] = paneTitles()
        activate("editor-tool-close")
        settle()

        openMenu("simulate")
        onNodeWithTag("menu-simulation-options").performScrollTo().performClick()
        settle()
        titles["simulationOptions"] = paneTitles()
        observe("sheet-titles", *titles.map { (name, value) -> name to value }.toTypedArray())

        val placementTitle = text("placement_tool_attributes_title", portuguese,
            "name" to ComponentKind.INPUT.localizedLabel().resolve(portuguese))
        assertTrue(placementTitle in titles.getValue("placement"), "${titles["placement"]}")
        assertTrue(text("sheet_properties_of", portuguese, "name" to component.kind.localizedLabel().resolve(portuguese)) in
            titles.getValue("inspector"), "${titles["inspector"]}")
        assertTrue(text("annotation_title", portuguese) in titles.getValue("tool"), "${titles["tool"]}")
        assertTrue(text(Messages.SimulationOptionsTitle, portuguese) in titles.getValue("simulationOptions"), "${titles["simulationOptions"]}")
        titles.forEach { (name, value) -> assertFalse(generic in value, "$name is announced as the generic bottom sheet: $value") }
    }

    // C159: a short window opens Signals as a sheet titled Signals.
    @Test
    fun theSignalsSheetAnnouncesItsTitle() = runDesktopComposeUiTest(844, 390) {
        val state = EditorState(fixture)
        app(state, portuguese)
        activate("tool-signals")
        settle()
        val titles = paneTitles()
        screenshot("w303-signals-sheet-title")
        observe("signals-title", "titles" to titles)
        assertTrue(text(Messages.SignalsHeading, portuguese) in titles, "$titles")
        assertFalse(text("a11y_sheet_pane", portuguese) in titles)
    }

    // C145-R2: Details explains the adjustments text; while the status line says the simulation is unsettled it leaves.
    @Test
    fun importDetailsLeaveWhileTheStatusSaysUnsettled() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(versionDefaultsXml)
        app(state)
        val before = exists("import-report-status-details")
        screenshot("w303-import-details")
        state.publishRun(state.captureRunSnapshot(), state.simulation.copy(oscillating = true))
        waitForIdle()
        val status = onNodeWithTag("editor-status", useUnmergedTree = true).fetchSemanticsNode().allText()
        val during = exists("import-report-status-details")
        screenshot("w303-import-details-unsettled")
        observe("import-details-unsettled", "detailsBefore" to before, "status" to status, "detailsWhileUnsettled" to during)
        assertTrue(before, "Details sits beside the adjustments status")
        assertTrue(text(Messages.SimulationUnsettled) in status, status)
        assertFalse(during, "Details does not sit beside 'Simulation unsettled'")
    }

    // C145-R3: the adjustments are announced once; a recreated window (saved instance state) does not announce them again.
    @Test
    fun adjustmentsAreNotAnnouncedAgainAfterRecreation() = runDesktopComposeUiTest(390, 844) {
        val dismissals = mutableStateOf(ImportReportDismissals())
        val generation = mutableStateOf(0)
        val key = ImportReportDismissals.key(WorkspaceProjectId("and-demo"), versionDefaultsXml)
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides english) {
                LogisimTheme(dark = false) {
                    key(generation.value) {
                        App(initialXml = versionDefaultsXml, language = english,
                            importReportDismissals = dismissals.value, importReportKey = key)
                    }
                }
            }
        }
        settle()
        fun status() = onNodeWithTag("editor-status", useUnmergedTree = true).fetchSemanticsNode().allText()
        val count = EditorState(versionDefaultsXml).importReport?.migrations?.size ?: 0
        val adjustments = LocalizedText(ImportReportStatusKey, quantity = count.toLong()).resolve(english)
        val first = status()
        val firstDetails = exists("import-report-status-details")
        // Recreation: the list goes through the saved-state Saver and the editor is rebuilt from the same XML.
        val saved = with(ImportReportDismissals.Saver) { SaverScope { true }.save(dismissals.value) }
        dismissals.value = saved?.let { ImportReportDismissals.Saver.restore(it) } ?: ImportReportDismissals()
        generation.value++
        settle()
        val second = status()
        val secondDetails = exists("import-report-status-details")
        screenshot("w303-import-after-recreation")
        observe("import-recreation", "first" to first, "firstDetails" to firstDetails, "second" to second,
            "secondDetails" to secondDetails, "saved" to saved)
        assertTrue(adjustments in first, first)
        assertTrue(firstDetails)
        assertFalse(adjustments in second, "announced again after recreation: $second")
        assertFalse(secondDetails)
    }

    // C74: the Memory editor's Save file result (NativeMemoryImageActions.onNotice) is shown where the user is: under
    // Save file in the exchange dialog over the sheet, once, not only in the status line behind the sheet.
    @Test fun memorySaveResultIsShownOnce() = memorySave(390, 844, "")
    @Test fun memorySaveResultIsShownOnceOnANarrowPhone() = memorySave(360, 780, "-360")

    private fun memorySave(width: Int, height: Int, suffix: String) = runDesktopComposeUiTest(width, height) {
        val state = EditorState(romProject)
        state.select(state.document.components.single().id)
        val assets = RecordingAssets()
        app(state, assets = assets)
        openTool("simulate", "memory-editor")
        onNodeWithTag("memory-exchange").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        settle()
        onNodeWithTag("memory-save-file").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 10_000) { assets.created.isNotEmpty() }
        settle()
        val complete = text("native_memory_export_complete")
        val shown = shownAnywhere(complete)
        val underSave = onAllNodesWithTag("memory-save-notice", useUnmergedTree = true).fetchSemanticsNodes().map { it.allText() }
        val polite = shown.any { node -> generateSequence(node) { it.parent }.any {
            it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite } }
        screenshot("w303-memory-save$suffix")
        activate("memory-exchange-close")
        settle()
        val afterClose = shownAnywhere(complete).size
        screenshot("w303-memory-save-closed$suffix")
        observe("memory-save$suffix", "created" to assets.created.map { it.name }, "status" to state.statusText.resolve(english),
            "shown" to shown.size, "underSave" to underSave, "polite" to polite, "shownAfterClose" to afterClose)
        assertEquals(1, assets.created.size)
        assertEquals(complete, state.statusText.resolve(english), "the status line keeps the result")
        assertEquals(1, shown.size, "the result is shown once where the user is")
        assertTrue(underSave.any { complete in it }, "under Save file: $underSave")
        assertTrue(polite, "and announced politely")
        assertEquals(0, afterClose, "the sheet does not show and announce it a second time")
    }

    // W3-03: the test vector export dialog covers the sheet, so its Copy is confirmed inside the dialog, once.
    @Test fun vectorCopyIsConfirmedInsideTheExportDialog() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        val clipboard = TestClipboard()
        app(state, clipboard = clipboard)
        openTool("simulate", "test-vectors")
        onNodeWithTag("vector-text").performTextReplacement("A Y\n0 0\n")
        settle()
        onNode(hasText(text(Messages.VectorExportVectors)) and hasAnyAncestor(hasTestTag("editor-tool-sheet")) and
            SemanticsMatcher("clickable") { it.config.contains(SemanticsActions.OnClick) })
            .performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        settle()
        val dialogOpen = onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        onNode(hasText(text(Messages.TraceCopy)) and hasAnyAncestor(isDialog()) and
            SemanticsMatcher("clickable") { it.config.contains(SemanticsActions.OnClick) }).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        val copied = text(Messages.VectorCopied)
        // The export dialog, not the tool sheet (itself a dialog) behind it.
        val exportDialog = isDialog() and hasAnyDescendant(hasText(text(Messages.VectorExportTitle)))
        val inDialog = onAllNodes(hasText(copied) and hasAnyAncestor(exportDialog), useUnmergedTree = true).fetchSemanticsNodes()
        val shown = shownAnywhere(copied)
        val polite = inDialog.any { node -> generateSequence(node) { it.parent }.any {
            it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite } }
        screenshot("w303-vector-copy-dialog")
        observe("vector-copy-dialog", "dialogOpen" to dialogOpen, "clipboard" to clipboard.content, "inDialog" to inDialog.size,
            "shown" to shown.size, "polite" to polite, "status" to state.statusText.resolve(english))
        assertTrue(dialogOpen)
        assertTrue(clipboard.content.orEmpty().isNotEmpty(), "Copy puts the vectors on the clipboard")
        assertEquals(1, inDialog.size, "the result is shown inside the dialog the user is in")
        assertEquals(1, shown.size, "and only there")
        assertTrue(polite, "and announced politely")
    }

    // C74: a read-only Generate is refused inside the Analysis sheet once, not once inline and again in the banner.
    @Test
    fun readOnlyGenerateIsShownOnceInTheAnalysisSheet() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture, initialReadOnly = true)
        app(state)
        openTool("project", "combinational-analysis")
        onNode(hasText("Read circuit") and hasAnyAncestor(hasTestTag("editor-tool-sheet")) and
            SemanticsMatcher("clickable") { it.config.contains(SemanticsActions.OnClick) }).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { exists("analysis-cell-0-1") }
        onNodeWithContentDescription("More analysis actions").performClick()
        waitForIdle()
        onNodeWithText("Generate circuit").performClick()
        waitForIdle()
        settle()
        val refusal = text("document_read_only_preserved")
        val shown = inSheet(refusal)
        val sheet = sheetBounds()
        screenshot("w303-analysis-read-only")
        observe("analysis-read-only", "status" to state.statusText.resolve(english), "shownInSheet" to shown.size,
            "inBounds" to shown.all { sheet.containsRect(it.boundsInWindow) }, "banner" to exists("editor-tool-sheet-notice"))
        assertEquals(1, shown.size, "the refusal is shown once in the sheet")
        assertTrue(shown.all { sheet.containsRect(it.boundsInWindow) })
        assertFalse(exists("editor-tool-sheet-notice"), "the banner does not repeat the inline refusal")
    }

    // C74: Export drawing's Save result is shown once in the sheet.
    @Test
    fun visualExportSaveIsShownOnce() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        val assets = RecordingAssets()
        app(state, assets = assets)
        openTool("file", "visual-export")
        onNodeWithTag("visual-export-save").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 20_000) { assets.created.isNotEmpty() }
        settle()
        val saved = text("visual_export_saved")
        val shown = inSheet(saved)
        screenshot("w303-visual-export-saved")
        observe("visual-export-saved", "created" to assets.created.map { it.name }, "shownInSheet" to shown.size,
            "banner" to exists("editor-tool-sheet-notice"))
        assertEquals(1, assets.created.size)
        assertEquals(1, shown.size, "the saved result is shown once in the sheet")
    }

    // C39: the panel reads the centre of the view when it opens; panning does not recompose the editor and the open
    // tool (the tool context no longer carries the viewport as a value).
    @Test
    fun panningDoesNotRecomposeTheOpenTool() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        var compositions = 0
        var runtimes = 0
        // Both read the context they are given, so neither is skipped when it changes.
        val probe = EditorTool("w303-probe", "Probe", runtime = { context -> runtimes++; context.document.id.length }) { context ->
            compositions++
            androidx.compose.material3.Text("probe ${context.document.id}")
        }
        val header = EditorWorkspaceHeader("", "").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides english) {
                LogisimTheme(dark = false) {
                    App(initialXml = fixture, language = english, workspaceHeader = header, editorTools = listOf(probe))
                }
            }
        }
        waitForIdle()
        openTool("project", "w303-probe")
        val opened = compositions
        val runtimesOpened = runtimes
        val centreBefore = state.visibleCenter
        repeat(5) { step ->
            val current = state.viewport
            state.updateViewport(current.copy(view = CircuitViewport(current.view.scale,
                current.view.pan + Offset(40f * (step + 1), 0f)), initialized = true, fitted = false))
            waitForIdle()
        }
        observe("pan-recomposition", "centre" to "$centreBefore -> ${state.visibleCenter}", "opened" to opened,
            "afterPanning" to compositions, "runtimesOpened" to runtimesOpened, "runtimesAfterPanning" to runtimes)
        assertTrue(opened > 0)
        assertTrue(centreBefore != state.visibleCenter, "the view moved")
        assertEquals(opened, compositions, "panning recomposed the open tool")
        assertEquals(runtimesOpened, runtimes, "panning recomposed the editor's tool runtimes")
    }

    // C145-R1 in the shipping workspace flow: Project XML pasted into a saved project opens a new session that says the
    // project was imported, not that a file named "Project XML" was opened.
    @Test
    fun workspaceProjectXmlImportIsAnImportedProject() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        seedProject(storage)
        workspace(storage, TestClipboard(versionDefaultsXml))
        menu("file", "xml")
        activate("editor-xml-paste")
        activate("editor-xml-import")
        waitUntil(timeoutMillis = 10_000) { !exists("editor-xml-import") }
        settle()
        val count = EditorState(versionDefaultsXml).importReport?.migrations?.size?.toLong() ?: 0L
        val status = onNodeWithTag("editor-status", useUnmergedTree = true).fetchSemanticsNode().allText()
        screenshot("w303-workspace-xml-import")
        val imported = LocalizedText(ImportReportImportedStatusKey, quantity = count).resolve(english)
        observe("workspace-xml-import", "count" to count, "status" to status)
        assertTrue(count > 0, "the pasted project needs adjustments")
        assertEquals(imported, status)
        assertFalse(text(Messages.ProjectXmlTitle) in status, status)
    }

    // C159: the Projects sheet announces its own title.
    @Test
    fun theProjectsSheetAnnouncesItsTitle() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        seedProject(storage)
        workspace(storage, TestClipboard(), portuguese)
        menu("file", "projects")
        val titles = paneTitles()
        screenshot("w303-projects-sheet-title")
        observe("projects-title", "titles" to titles)
        assertTrue(text("workspace_projects", portuguese) in titles, "$titles")
        assertFalse(text("a11y_sheet_pane", portuguese) in titles, "$titles")
    }

    private class MemoryStorage : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        private val lock = ReentrantLock()
        override fun <T> withExclusiveAccess(action: () -> T): T = lock.withLock(action)
        override fun read(name: String): String? = lock.withLock { files[name] }
        override fun listNames(): List<String> = lock.withLock { files.keys.toList() }
        override fun writeAtomically(name: String, contents: String) = lock.withLock { files[name] = contents }
        override val supportsDelete get() = true
        override fun delete(name: String) { lock.withLock { files.remove(name) } }
    }

    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun seedProject(storage: WorkspaceCatalogStorage) {
        val repository = WorkspaceCatalogRepository(storage, validate = { CircCodec.readProject(it) is CircProjectImportResult.Success })
        assertIs<WorkspaceMutationResult.Success>(repository.create("Saved", fixture))
    }

    @Suppress("DEPRECATION")
    private fun ComposeUiTest.workspace(storage: WorkspaceCatalogStorage, clipboard: ClipboardManager, language: AppLanguage = english) {
        val lifecycle = TestLifecycle()
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalClipboardManager provides clipboard) {
                CatalogWorkspaceHost(storage = storage, language = language)
            }
        }
        waitUntil(timeoutMillis = 10_000) { exists("editor-canvas") }
        settle()
    }

    private fun ComposeUiTest.menu(menu: String, action: String) {
        openMenu(menu)
        onNodeWithTag("menu-$action").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        settle()
    }

    private val romProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="4" loc="(200,100)" name="ROM"><a name="addrWidth" val="4"/><a name="dataWidth" val="8"/>
      <a name="contents">addr/data: 4 8&#10;f 1e 1d 2c e0 f0 6*0 1&#10;4 3 2&#10;</a></comp>
  </circuit>
</project>"""
}
