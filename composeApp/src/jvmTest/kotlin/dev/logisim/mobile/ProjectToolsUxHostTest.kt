package dev.logisim.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.toSize
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ExtraIoEvent
import dev.logisim.mobile.core.ExtraIoKinds
import dev.logisim.mobile.core.ExtraSliderState
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.ProjectMouseButton
import dev.logisim.mobile.core.ProjectMouseGesture
import dev.logisim.mobile.core.ProjectMouseMappings
import dev.logisim.mobile.core.ProjectToolbar
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Preferences, export and project tools (W2-08): bounded export scale with the output size (C104), headed preference
 * groups (C106), readable compatibility labels (C110), selectable mouse mappings with Remove (C112), large values that
 * open their editors (C12), toolbar insertion (X4), precise joystick and slider controls (X5) and prefixed font chips
 * (R04-2). Screenshots go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectToolsUxHostTest {
    private var ui: ComposeUiTest? = null

    private fun ComposeUiTest.host(language: AppLanguage = AppLanguage.ENGLISH, content: @Composable () -> Unit) {
        ui = this
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
        waitForIdle()
    }

    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction {
        repeat(3) {
            val target = fetchSemanticsNode()
            var parent = target.parent
            while (parent != null) {
                val scroll = parent.config.getOrNull(SemanticsActions.ScrollBy)
                if (scroll != null) {
                    val viewport = androidx.compose.ui.geometry.Rect(parent.positionInRoot, parent.size.toSize())
                    val bounds = androidx.compose.ui.geometry.Rect(fetchSemanticsNode().positionInRoot, target.size.toSize())
                    val vertical = parent.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                    val delta = if (vertical) bounds.center.y - viewport.center.y else bounds.center.x - viewport.center.x
                    if (kotlin.math.abs(delta) > 1f) ui?.runOnIdle { scroll.action?.invoke(if (vertical) 0f else delta, if (vertical) delta else 0f) }
                    ui?.waitForIdle()
                }
                parent = parent.parent
            }
        }
        return this
    }

    private fun toolContext(state: EditorState) = EditorToolContext(state.project, state.document, state.simulation,
        state.document.components.filter { it.id in state.selectedIds }, { state.applyProject(it) }, { state.applyDocument(it) },
        state::selectCircuit, { state.selectComponent(it) }, {}, documentIdentity = state.documentIdentity)

    private fun SemanticsNode.text(): String =
        (config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "") + (config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "")

    // C104 ------------------------------------------------------------------------------------------------------------

    @Test
    fun exportScaleIsASliderWithPresetsAndTheOutputSize() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val tool = circuitVisualExportEditorTool(ComponentCatalog.registry, OpenNothing)
        host { Column { tool.content(toolContext(state)) } }
        screenshot("export-scale-initial")
        onNodeWithTag("visual-export-scale-slider").reveal().assertIsDisplayed()
        assertEquals("100%", onNodeWithTag("visual-export-scale-percent").fetchSemanticsNode().text())
        val initialSize = onNodeWithTag("visual-export-pixel-size").fetchSemanticsNode().text()
        assertTrue(Regex("\\d+ × \\d+ px").containsMatchIn(initialSize), initialSize)

        onNodeWithTag("visual-export-scale-preset-200").reveal().performClick()
        waitForIdle()
        assertEquals("200%", onNodeWithTag("visual-export-scale-percent").fetchSemanticsNode().text())
        screenshot("export-scale-200")

        // Typing a percent-sized number shows the problem before Save, which is disabled.
        onNode(hasText("Scale") and hasSetTextAction()).reveal().performTextReplacement("200")
        waitForIdle()
        onNodeWithTag("visual-export-pixel-limit").reveal().assertIsDisplayed()
        onNodeWithTag("visual-export-save").assertIsNotEnabled()
        screenshot("export-scale-too-large")
        onNode(hasText("Scale") and hasSetTextAction()).performTextReplacement("1,5")
        waitForIdle()
        onNodeWithTag("visual-export-save").assertIsEnabled()
        assertEquals("150%", onNodeWithTag("visual-export-scale-percent").fetchSemanticsNode().text())
        onNode(hasText("Scale") and hasSetTextAction()).performTextReplacement("")
        waitForIdle()
        onNodeWithTag("visual-export-save").assertIsNotEnabled()
    }

    private fun hasSetTextAction() = SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)

    // C106 ------------------------------------------------------------------------------------------------------------

    private fun preferencesHeadings(width: Int, language: AppLanguage, expected: List<String>, suffix: String) =
        runDesktopComposeUiTest(width, 844) {
            var preferences by mutableStateOf(EditorPreferences())
            host(language) { EditorPreferencesPanel(preferences, { preferences = it }, {}, Modifier.fillMaxSize()) }
            screenshot("preferences-groups$suffix")
            val headings = onAllNodes(isHeading()).fetchSemanticsNodes()
            assertEquals(expected, headings.map { it.text() })
            // Wire Poke radix is a Simulation setting, not a value character.
            val y = { node: SemanticsNode -> node.positionInRoot.y }
            val simulation = headings.single { it.text() == expected[4] }
            val next = headings[headings.indexOf(simulation) + 1]
            val wire = onNode(hasContentDescription(if (language == AppLanguage.ENGLISH) "Wire Poke primary radix" else "Base principal ao inspecionar fios", substring = true))
                .fetchSemanticsNode()
            assertTrue(y(wire) > y(simulation) && y(wire) < y(next), "Wire poke radix is outside Simulation")
            onNode(hasText(expected[4])).reveal()
            screenshot("preferences-simulation$suffix")
        }

    @Test
    fun preferencesAreGroupedUnderHeadings() = preferencesHeadings(390, AppLanguage.ENGLISH,
        listOf("Editor preferences", "International", "Window", "Editing", "Simulation", "Layout", "FPGA Commander", "New projects"), "")

    @Test
    fun preferencesGroupsInPortugueseOnANarrowPhone() = preferencesHeadings(360, AppLanguage.PORTUGUESE,
        listOf("Preferências do editor", "Internacional", "Janela", "Edição", "Simulação", "Layout", "Configurações do Comandante FPGA", "Novos projetos"), "-pt-360")

    // C110 ------------------------------------------------------------------------------------------------------------

    @Test
    fun compatibilityPageShowsLibraryNamesAndIssueLinks() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val tool = helpExamplesEditorTool(onOpenExample = { _, _ -> false })
        host { Column { tool.content(toolContext(state)) } }
        onNode(hasText("Compatibility") and hasClickAction()).performClick()
        waitForIdle()
        screenshot("help-compatibility")
        val list = onNode(hasScrollToIndexAction())
        val count = list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount ?: 40
        val shown = mutableSetOf<String>()
        for (index in 0 until count) {
            runCatching { list.performScrollToIndex(index) }
            waitForIdle()
            onAllNodes(SemanticsMatcher("any text") { it.config.contains(SemanticsProperties.Text) }).fetchSemanticsNodes()
                .forEach { shown += it.text() }
        }
        screenshot("help-compatibility-libraries")
        assertTrue(shown.none { '#' in it }, shown.filter { '#' in it }.toString())
        assertTrue(shown.any { it.startsWith("Gates:") }, shown.toString())
        assertTrue(shown.any { it.startsWith("Plexers:") }, shown.toString())
        assertTrue(onAllNodes(hasContentDescription("Open issue ", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    // C112 ------------------------------------------------------------------------------------------------------------

    @Test
    fun mouseMappingsShowTheEditedRowAndRemoveIt() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(MOUSE_PROJECT)
        val gesture = ProjectMouseGesture(ProjectMouseButton.SECONDARY, ctrl = true)
        var applied: dev.logisim.mobile.core.CircuitProject? = null
        host { ProjectMouseMappingsPanel(state.project, state.componentRegistry, { applied = it }, {}, Modifier.fillMaxSize()) }
        val row = onNode(hasText("Ctrl + Secondary", substring = true) and hasClickAction())
        row.performClick()
        waitForIdle()
        screenshot("mouse-mapping-selected")
        row.assert(isSelected())
        onNode(hasText("Shift + Middle", substring = true) and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        onNodeWithText("Remove mapping").performClick()
        waitForIdle()
        screenshot("mouse-mapping-removed")
        onNodeWithText("Apply").performClick()
        waitForIdle()
        val result = checkNotNull(applied)
        assertFalse(gesture in ProjectMouseMappings.read(result))
        assertTrue(ProjectMouseGesture(ProjectMouseButton.MIDDLE, shift = true) in ProjectMouseMappings.read(result))
    }

    // X4 --------------------------------------------------------------------------------------------------------------

    @Test
    fun aToolIsInsertedAfterAChosenEntryFromAGroupedList() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(TOOLBAR_PROJECT)
        val tool = projectToolbarEditorTool(state.componentRegistry, state)
        host { Column { tool.content(toolContext(state)) } }
        screenshot("toolbar-editor")
        // Three taps: the row's menu, "Insert tool after", the tool.
        onNodeWithTag("project-toolbar-more-1").performClick()
        waitForIdle()
        screenshot("toolbar-row-menu")
        onNodeWithTag("project-toolbar-insert-after").performClick()
        waitForIdle()
        screenshot("toolbar-add-list")
        // The list is grouped by library under headings, starting with the tool modes.
        val headings = onAllNodes(isHeading()).fetchSemanticsNodes().map { it.text() }
        assertEquals(listOf("Tools", "Wiring"), headings.take(2), headings.toString())
        assertTrue(onNodeWithTag("project-toolbar-inserting").fetchSemanticsNode().text().startsWith("Choose a tool to insert after OR"))
        onNode(hasText("Find a tool") and hasSetTextAction()).performTextReplacement("flip")
        waitForIdle()
        assertEquals("Memory", onAllNodes(isHeading()).fetchSemanticsNodes().first().text())
        screenshot("toolbar-add-list-filtered")
        onNode(hasText("D flip-flop") and hasClickAction()).performClick()
        waitForIdle()
        screenshot("toolbar-inserted")
        onNodeWithText("Apply").performClick()
        waitForIdle()
        val names = ProjectToolbar.read(state.project).map { it.name }
        assertEquals(listOf("AND Gate", "OR Gate", "D Flip-Flop", "NOT Gate", "XOR Gate", "Pin", "Clock"), names)
    }

    // X5 --------------------------------------------------------------------------------------------------------------

    @Test
    fun joystickHasDiagonalsAndExactAxes() = runDesktopComposeUiTest(390, 844) {
        val events = mutableListOf<IoInputEvent>()
        val joystick = CircuitComponent("j", IoKinds.Joystick, GridPoint(100, 100))
        host {
            IoInputPanel(joystick, null, { events += it }, SimulationRunStatus(), { false }, InspectorPresentation.SHEET,
                null, { null }, { InspectorFocusState() })
        }
        screenshot("joystick-pad")
        onNodeWithContentDescription("Up-left").performClick()
        waitForIdle()
        assertTrue(IoInputEvent.MoveJoystick(-14, -14) in events, events.toString())
        val x = onNode(hasContentDescription("X position") and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        x.performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        waitForIdle()
        assertTrue(events.any { it is IoInputEvent.MoveJoystick && it.x == 3 }, events.toString())
    }

    @Test
    fun sliderStepsFollowTheWidthAndAnnounceTheValue() = runDesktopComposeUiTest(390, 844) {
        val events = mutableListOf<ExtraIoEvent>()
        val slider = CircuitComponent("s", ExtraIoKinds.Slider, GridPoint(100, 100), attributes = mapOf("width" to "2"))
        host { ExtraIoControls(slider, ExtraSliderState(170), { events += it }, {}) }
        screenshot("slider-2-bit")
        val node = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNode()
        assertEquals(2, node.config[SemanticsProperties.ProgressBarRangeInfo].steps)
        // The output value as the label shows it (binary 10 is 2), not "67 percent".
        assertEquals("10", node.config.getOrNull(SemanticsProperties.StateDescription))
        // The value label beside it is not a second announcement.
        assertTrue(onAllNodesWithText("Value: 10").fetchSemanticsNodes().isEmpty())
        assertTrue(node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().isNotEmpty())
    }

    // C12 -------------------------------------------------------------------------------------------------------------

    private fun ComposeUiTest.inspector(state: EditorState, opened: MutableList<String>, available: Boolean = true) = host {
        val session = rememberPropertyInspectorSession(state)
        Box {
            TypedPropertyInspector(state.componentRegistry, session.context.components, session, InspectorPresentation.SHEET,
                onLabel = {}, onLabelFocusChanged = {}, onOpenField = { session.open(it) },
                onChooseOption = { field, value -> session.applyOption(state, session, field, value) },
                onOpenEditor = { opened += it; available })
            PropertyInspectorDialog(session) { session.applyValue(state, session, it) }
        }
    }

    @Test
    fun memoryContentsShowASummaryInsteadOfTheRawText() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(ROM_PROJECT)
        state.select(state.document.components.single().id)
        val opened = mutableListOf<String>()
        inspector(state, opened)
        screenshot("inspector-rom")
        val row = onNodeWithTag("inspector-row-contents").reveal()
        val summary = row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
        assertFalse("addr/data" in summary, summary)
        assertEquals("16 words × 8 bits", summary)
        // The row opens the memory editor, not a text field.
        row.performClick()
        waitForIdle()
        assertEquals(listOf("memory-editor"), opened)
        assertTrue(onAllNodesWithTag("inspector-value-field").fetchSemanticsNodes().isEmpty())
        // The raw text stays one tap away, and is still validated before Apply.
        onNodeWithTag("inspector-edit-text-contents").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag("inspector-value-field").assertIsDisplayed()
        screenshot("inspector-rom-as-text")
    }

    @Test
    fun aLargeImageOpensItsEditorWithoutATextField() = runDesktopComposeUiTest(390, 844) {
        // A noise PNG of about 900 KB: the table shows its type and size, and building the inspector creates no text field.
        val noise = java.awt.image.BufferedImage(640, 470, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val random = java.util.Random(7)
        for (y in 0 until noise.height) for (x in 0 until noise.width) noise.setRGB(x, y, random.nextInt(0x1000000))
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(noise, "png", it) }.toByteArray()
        assertTrue(png.size in 850_000..990_000, "PNG is ${png.size} bytes")
        val payload = java.util.Base64.getEncoder().encodeToString(png)
        val xml = HostScreenshots.fixture("fixtures/visual-export/qualification.circ")
            .replace(Regex("data:image/png;base64,[A-Za-z0-9+/=]+"), "data:image/png;base64,$payload")
        val state = EditorState(xml)
        state.select(state.document.components.first { it.kind == dev.logisim.mobile.core.AnnotationLibrary.Image }.id)
        val opened = mutableListOf<String>()
        val started = System.nanoTime()
        inspector(state, opened)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        screenshot("inspector-image")
        val summary = onNodeWithTag("inspector-row-image").reveal().fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription).orEmpty()
        assertEquals("PNG image, ${(png.size + 512) / 1024} KiB", summary)
        assertTrue(onAllNodesWithTag("inspector-value-field").fetchSemanticsNodes().isEmpty())
        assertTrue(elapsed < 10_000, "Opening the inspector took $elapsed ms")
        onNodeWithTag("inspector-row-image").performClick()
        waitForIdle()
        assertEquals(listOf("annotations"), opened)
    }

    @Test
    fun aPlaTableWithoutADedicatedEditorOpensTheTextEditor() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState("""<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="1" loc="(200,100)" name="PLA"><a name="in_width" val="2"/><a name="out_width" val="1"/><a name="table" val="11 1&#10;00 1&#10;"/></comp>
  </circuit>
</project>""")
        state.select(state.document.components.single().id)
        val opened = mutableListOf<String>()
        inspector(state, opened)
        assertEquals("2 lines", onNodeWithTag("inspector-row-table").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        onNodeWithTag("inspector-row-table").performClick()
        waitForIdle()
        assertTrue(opened.isEmpty())
        onNodeWithTag("inspector-value-field").assertIsDisplayed()
    }

    // C104: the size shown is the size exported -----------------------------------------------------------------------

    @Test
    fun theShownOutputSizeIsTheRenderedSize() {
        val bounds = dev.logisim.mobile.core.VisualBounds(10.0, 20.0, 133.3, 61.2)
        for ((scale, margin) in listOf(1.0 to 10.0, 2.0 to 0.0, 0.25 to 10.0, 1.4142 to 3.5)) {
            val size = dev.logisim.mobile.core.VisualExport.pixelSize(bounds, scale, margin)
            val bitmap = renderDrawingBitmap(bounds, dev.logisim.mobile.core.VisualExportOptions(scale = scale, margin = margin)) {}
            assertEquals(size.width.toInt() to size.height.toInt(), bitmap.width to bitmap.height, "scale $scale margin $margin")
        }
    }

    // R04-3 -----------------------------------------------------------------------------------------------------------

    @Test
    fun anotherEditorStateDoesNotInheritTheAppearanceDraft() = runDesktopComposeUiTest(390, 844) {
        val first = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val second = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        var editor by mutableStateOf(first)
        var open by mutableStateOf(true)
        host {
            val tools = rememberAdditionalEditorTools(ComponentCatalog.registry, editorScope = editor)
            val tool = tools.first { it.id == "appearance" }
            if (open) Column { tool.content(toolContext(editor)) }
        }
        onNodeWithText("+ Rectangle").reveal().performClick()
        waitForIdle()
        open = false
        waitForIdle()
        // Both editors start at document identity 0 with the same circuit id; the second must not see the first's draft.
        assertEquals(first.documentIdentity, second.documentIdentity)
        editor = second
        open = true
        waitForIdle()
        assertTrue(onAllNodesWithTag("appearance-unapplied-banner").fetchSemanticsNodes().isEmpty())
        open = false
        waitForIdle()
        editor = first
        open = true
        waitForIdle()
    }

    // R04-2 -----------------------------------------------------------------------------------------------------------

    @Test
    fun fontChipsTakeTheFieldsPrefix() = runDesktopComposeUiTest(390, 844) {
        var font by mutableStateOf("SansSerif plain 12")
        host { FontChoiceField(font, { font = it }, tag = "annotation-font") }
        onNodeWithTag("annotation-font-style-b").performClick()
        waitForIdle()
        assertEquals("SansSerif bold 12", font)
        assertTrue(onAllNodesWithTag("font-style-b").fetchSemanticsNodes().isEmpty())
    }

    private object OpenNothing : DocumentAssetActions {
        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) = onResult(AssetOpenResult.Cancelled)
        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
    }

    private companion object {
        const val MOUSE_PROJECT = """
            <project source="2.7.1" version="1.0">
              <lib name="0" desc="#Wiring"/><lib name="6" desc="#Base"/>
              <main name="main"/>
              <mappings>
                <tool lib="6" map="Ctrl Button3" name="Poke Tool"/>
                <tool lib="0" map="Shift Button2" name="Pin"><a name="width" val="8"/></tool>
              </mappings>
              <circuit name="main"><comp lib="0" loc="(100,100)" name="Pin"/></circuit>
            </project>"""

        const val TOOLBAR_PROJECT = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Wiring" name="0"/><lib desc="#Gates" name="1"/><lib desc="#Memory" name="4"/>
  <main name="main"/>
  <toolbar>
    <tool lib="1" name="AND Gate"/><tool lib="1" name="OR Gate"/><tool lib="1" name="NOT Gate"/>
    <tool lib="1" name="XOR Gate"/><tool lib="0" name="Pin"/><tool lib="0" name="Clock"/>
  </toolbar>
  <circuit name="main"/>
</project>"""

        const val ROM_PROJECT = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="4" loc="(200,100)" name="ROM"><a name="addrWidth" val="4"/><a name="dataWidth" val="8"/>
      <a name="contents">addr/data: 4 8&#10;f 1e 1d 2c e0 f0 6*0 1&#10;4 3 2&#10;</a></comp>
  </circuit>
</project>"""
    }
}
