package dev.logisim.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.TraceRecorder
import dev.logisim.mobile.core.TraceSignals
import dev.logisim.mobile.core.WorkspaceCatalogResult
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.WorkspaceProjectInfo
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import dev.logisim.mobile.localization.rememberLocalizer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W2-09 (C156, C158, C160, C161): the Material scheme follows the chrome palette in both modes, the armed tool and the
 * active circuit carry an accent cue and selected semantics, no internal id reaches visible text or TalkBack, and
 * commands use one word per action. Screenshots go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeVocabularyHostTest {
    private val fixture = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")
    private val pt = AppLanguage.PORTUGUESE

    /** A main circuit with an unlabeled AND gate and a subcircuit instance labeled Sub1 holding pins A and Y. */
    private val nested = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="type" val="input"/><a name="label" val="IN"/></comp>
    <comp lib="1" loc="(200,200)" name="AND Gate"/>
    <comp loc="(300,100)" name="half"><a name="label" val="Sub1"/></comp>
  </circuit>
  <circuit name="half">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="type" val="input"/><a name="label" val="A"/></comp>
    <comp lib="0" loc="(200,100)" name="Pin"><a name="type" val="output"/><a name="facing" val="west"/><a name="label" val="Y"/></comp>
    <wire from="(100,100)" to="(200,100)"/>
  </circuit>
</project>"""

    private class MemoryPreferences(preferences: EditorPreferences) : PreferencesStorage {
        private var stored: String? = EditorPreferencesCodec.write(preferences)
        override fun load() = stored
        override fun save(serialized: String) { stored = serialized }
    }

    private fun text(key: String, vararg arguments: Pair<String, Any?>) = message(key, *arguments).resolve(pt)

    private fun ComposeUiTest.app(state: EditorState, dark: Boolean, xml: String = fixture) {
        val header = EditorWorkspaceHeader("Projeto", "Salvo").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        val storage = MemoryPreferences(EditorPreferences(themeMode = if (dark) EditorThemeMode.DARK else EditorThemeMode.LIGHT))
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides pt) {
                LogisimTheme(dark = dark) {
                    App(initialXml = xml, language = pt, workspaceHeader = header, preferencesStorage = storage)
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.panel(dark: Boolean, content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides pt, LocalEditorDarkTheme provides dark) {
                LogisimTheme(dark = dark) { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
        waitForIdle()
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    /** Every text and content description on screen, merged and unmerged, for leak checks. */
    private fun ComposeUiTest.spokenAndShown(): List<String> {
        val nodes = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        return nodes.flatMap { node: SemanticsNode ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                listOfNotNull(node.config.getOrNull(SemanticsProperties.StateDescription))
        }
    }

    private fun observe(name: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-$name.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (fact, value) -> "$fact\t$value" })
    }

    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    /**
     * The trace picker's group headings, as TalkBack finds them by heading navigation, and each row as TalkBack reads
     * it: one checkbox merging the signal's name and instance path. The list is lazy, so it is scrolled to the end.
     */
    private val radixDescriptions = linkedMapOf<String, String>()

    private fun ComposeUiTest.pickerContents(): Pair<List<String>, List<List<String>>> {
        val headings = linkedMapOf<String, String>()
        val rows = linkedMapOf<String, List<String>>()
        radixDescriptions.clear()
        fun tagged(prefix: String, unmerged: Boolean) = onAllNodes(SemanticsMatcher("tag $prefix") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }, useUnmergedTree = unmerged).fetchSemanticsNodes()
        var unchanged = 0
        while (unchanged < 2) {
            val before = headings.size + rows.size
            for (node in tagged("trace-group-", unmerged = true)) {
                headings[node.config[SemanticsProperties.TestTag]] = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
                assertTrue(node.config.contains(SemanticsProperties.Heading), "A picker group title is a heading")
            }
            for (node in tagged("trace-signal-", unmerged = false)) {
                assertEquals(Role.Checkbox, node.config.getOrNull(SemanticsProperties.Role), "A picker row is one checkbox")
                rows[node.config[SemanticsProperties.TestTag]] = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }
            for (node in tagged("trace-radix-", unmerged = true)) {
                radixDescriptions[node.config[SemanticsProperties.TestTag]] = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
            }
            unchanged = if (headings.size + rows.size == before) unchanged + 1 else 0
            onNodeWithTag("trace-picker-list").performTouchInput { swipeUp() }
            waitForIdle()
        }
        return headings.values.toList() to rows.values.toList()
    }

    // --- Editor chrome: the header, the armed tool and the library (C156, C158) ---

    private fun editorArmed(width: Int, height: Int, dark: Boolean, name: String) = runDesktopComposeUiTest(width, height) {
        val state = EditorState(fixture)
        app(state, dark)
        state.place(ComponentKind.AND)
        waitForIdle()
        screenshot(name)
    }

    @Test fun editorPhoneLight() = editorArmed(390, 844, dark = false, "editor-phone-light")
    @Test fun editorPhoneDark() = editorArmed(390, 844, dark = true, "editor-phone-dark")
    @Test fun editorPhoneNarrowDark() = editorArmed(360, 780, dark = true, "editor-phone360-dark")

    private fun tabletArmed(dark: Boolean) = runDesktopComposeUiTest(1180, 820) {
        val state = EditorState(fixture)
        app(state, dark)
        state.place(ComponentKind.AND)
        waitForIdle()
        // The active circuit row in the Circuits tab (the explorer itself opens on the Library tab, C18).
        onNodeWithTag("explorer-tab-project").performClick()
        waitForIdle()
        screenshot("editor-tablet-circuits-${theme(dark)}")
        onNodeWithTag("explorer-tab-library").performClick()
        waitForIdle()
        // The armed tool's library row says it is selected, as ChromeButton already does. The list is lazy: the row is
        // scrolled into view within it before it can be found.
        val insertAnd = text(Messages.A11yInsertComponent, "name" to ComponentKind.AND.localizedLabel())
        onNodeWithTag("library-list").performScrollToNode(hasContentDescription(insertAnd))
        waitForIdle()
        val row = onNode(hasContentDescription(insertAnd))
        screenshot("editor-tablet-armed-${theme(dark)}")
        val armed = row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)
        observe("editor-tablet-armed-${theme(dark)}", "libraryRowSelected" to armed)
        row.assert(selected)
        val insertOr = text(Messages.A11yInsertComponent, "name" to ComponentKind.OR.localizedLabel())
        onNodeWithTag("library-list").performScrollToNode(hasContentDescription(insertOr))
        waitForIdle()
        onNode(hasContentDescription(insertOr)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test fun editorTabletArmedLight() = tabletArmed(dark = false)
    @Test fun editorTabletArmedDark() = tabletArmed(dark = true)

    private fun tabletInspector(dark: Boolean) = runDesktopComposeUiTest(1180, 820) {
        val state = EditorState(fixture)
        app(state, dark)
        state.select(state.document.components.first { it.label == "E2E_NOT" }.id)
        waitForIdle()
        screenshot("editor-tablet-inspector-${theme(dark)}")
    }

    @Test fun editorTabletInspectorLight() = tabletInspector(dark = false)
    @Test fun editorTabletInspectorDark() = tabletInspector(dark = true)

    private fun phoneInspector(dark: Boolean) = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, dark)
        state.select(state.document.components.first { it.label == "E2E_NOT" }.id)
        waitForIdle()
        onNodeWithTag("tool-properties").performClick()
        waitForIdle()
        screenshot("editor-phone-inspector-sheet-${theme(dark)}")
    }

    @Test fun editorPhoneInspectorLight() = phoneInspector(dark = false)
    @Test fun editorPhoneInspectorDark() = phoneInspector(dark = true)

    // --- Paste confirmation word (C161) ---

    @Test
    fun pasteIsConfirmedWithPasteHere() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, dark = false)
        state.select(state.document.components.first { it.label == "E2E_NOT" }.id)
        state.copySelected()
        state.paste()
        waitForIdle()
        screenshot("paste-confirm-light")
        val confirm = onNodeWithTag("paste-confirm").fetchSemanticsNode()
        val shown = (confirm.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            onAllNodes(SemanticsMatcher("paste confirm text") { it.parent?.config?.getOrNull(SemanticsProperties.TestTag) == "paste-confirm" },
                useUnmergedTree = true).fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } })
            .distinct()
        observe("paste-confirm", "label" to shown)
        assertEquals(listOf("Colar aqui"), shown)
    }

    // --- Memory editor: a selected range in both themes (C156) ---

    private fun memorySheet(dark: Boolean) = runDesktopComposeUiTest(390, 844) {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="4" loc="(200,100)" name="ROM">
      <a name="addrWidth" val="4"/>
      <a name="dataWidth" val="8"/>
      <a name="contents">addr/data: 4 8
0a 0b 0c
</a>
    </comp>
  </circuit>
</project>"""
        val state = EditorState(xml)
        state.select(state.document.components.single { it.kind == ComponentKind.ROM }.id)
        val tool = memoryEditorTool(state.componentRegistry, null)
        val open = mutableStateOf(true)
        panel(dark) {
            if (open.value) {
                val strings = rememberLocalizer()
                val context = EditorToolContext(state.project, state.document, state.simulation,
                    state.document.components.filter { it.id in state.selectedIds },
                    { state.applyProject(it) }, { state.applyDocument(it) }, state::selectCircuit, { state.selectComponent(it) },
                    state::reportStatus, projectSimulation = state.rootSimulation, documentIdentity = state.documentIdentity,
                    sendComponentEvent = { id, event -> state.sendComponentEvent(id, event) },
                    showNotice = state::reportStatus, closeTool = { open.value = false })
                EditorModalSheet(onDismissRequest = { open.value = false }, modifier = Modifier.testTag("editor-tool-sheet")) {
                    Row(Modifier.fillMaxWidth().heightIn(max = 700.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(strings(tool.titleText), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                TextButton(onClick = { open.value = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(strings(Messages.ActionClose))
                                }
                            }
                            tool.content(context)
                        }
                    }
                }
            }
        }
        onNodeWithTag("memory-cell-2").performScrollTo().performTouchInput { longClick() }
        waitForIdle()
        onNodeWithTag("memory-cell-5").performScrollTo().performClick()
        waitForIdle()
        screenshot("memory-range-${theme(dark)}")
        listOf("2", "3", "4", "5").forEach { onNodeWithTag("memory-cell-$it").assert(selected) }
        onNodeWithTag("memory-cell-6").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test fun memoryRangeLight() = memorySheet(dark = false)
    @Test fun memoryRangeDark() = memorySheet(dark = true)

    // --- A plain Material menu and the new/rename circuit dialogs (C156, C161) ---

    private fun circuitMenu(dark: Boolean) = runDesktopComposeUiTest(390, 844) {
        val project = (CircCodec.readProject(nested) as CircProjectImportResult.Success).project
        val rows = ProjectNavigation.rows(project)
        panel(dark) {
            ProjectNavigationPanel(project, rows.first().target, onNavigate = {}, onCreate = {}, onRename = { _, _ -> },
                onMove = { _, _ -> }, onDelete = {}, onSetMain = {}, modifier = Modifier.fillMaxSize())
        }
        screenshot("navigation-active-${theme(dark)}")
        val manage = text(Messages.A11yManageCircuit, "name" to rows.first().label)
        onNode(hasContentDescription(manage)).performClick()
        waitForIdle()
        screenshot("menu-circuit-${theme(dark)}")
        onNodeWithText(text(Messages.ActionRename)).performClick()
        waitForIdle()
        val renameButtons = dialogButtons()
        screenshot("dialog-rename-circuit-${theme(dark)}")
        onNodeWithText(text(Messages.ActionCancel)).performClick()
        waitForIdle()
        // Evolution's Add Circuit… (C31), which adds a circuit to this project.
        onNodeWithTag("circuits-add").performClick()
        waitForIdle()
        val createButtons = dialogButtons()
        screenshot("dialog-new-circuit-${theme(dark)}")
        observe("circuit-dialogs-${theme(dark)}", "rename" to renameButtons, "create" to createButtons)
        assertEquals(listOf("Renomear", "Cancelar"), renameButtons)
        assertEquals(listOf("Criar", "Cancelar"), createButtons)
    }

    /** The texts of the open dialog's buttons, confirm first. */
    private fun ComposeUiTest.dialogButtons(): List<String> {
        val buttons = onAllNodes(SemanticsMatcher("dialog button") {
            it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button &&
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty()
        }).fetchSemanticsNodes().map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
        val known = setOf("Salvar", "Criar", "Renomear", "Cancelar")
        return buttons.filter { it in known }.sortedBy { if (it == "Cancelar") 1 else 0 }
    }

    @Test fun circuitMenuAndDialogsLight() = circuitMenu(dark = false)
    @Test fun circuitMenuAndDialogsDark() = circuitMenu(dark = true)

    // --- Trace: human signal names, no component ids or instance paths (C160) ---

    @Test
    fun traceNamesSignalsWithoutInternalIds() = runDesktopComposeUiTest(390, 844) {
        val project = (CircCodec.readProject(nested) as CircProjectImportResult.Success).project
        val ids = project.circuits.flatMap { circuit -> circuit.components.map { it.id } }.toSet()
        val signals = TraceSignals.fromProject(project)
        val recorder = TraceRecorder(signals)
        val state = EditorState(nested)
        panel(dark = false) { TracePanel(project, state.rootSimulation, recorder, onExport = {}) }
        screenshot("trace-timeline")
        val timeline = spokenAndShown()
        onNodeWithText(text(Messages.TraceOrderSignals)).performClick()
        waitForIdle()
        screenshot("trace-order-dialog")
        val ordered = spokenAndShown()
        onNodeWithText(text(Messages.TraceDone)).performClick()
        waitForIdle()
        onNodeWithText(text(Messages.TraceSelectSignals)).performClick()
        waitForIdle()
        screenshot("trace-signal-picker")
        val picker = spokenAndShown()
        fun leaks(texts: List<String>) = texts.filter { shown ->
            "//0x" in shown || ids.any { id -> Regex("(^|[^A-Za-z0-9])${Regex.escape(id)}([^A-Za-z0-9]|$)").containsMatchIn(shown) }
        }.distinct()
        // The integrated picker (W2-03) groups signals under instance headings and repeats the path on each nested row.
        val (headings, rows) = pickerContents()
        observe("trace-ids", "ids" to ids.sorted(), "timeline" to leaks(timeline), "order" to leaks(ordered), "picker" to leaks(picker),
            "headings" to headings, "rows" to rows)
        assertEquals(emptyList(), leaks(timeline), "timeline")
        assertEquals(emptyList(), leaks(ordered), "order dialog")
        assertEquals(emptyList(), leaks(picker), "signal picker")
        assertTrue("main › Sub1" in headings, "The picker groups the subcircuit's signals under its instance label path: $headings")
        assertTrue(listOf("A [1]", "main › Sub1") in rows, "The picker names the subcircuit pin by its label and instance label path: $rows")
    }

    /** Two subcircuit instances with the same label Sub1, each holding an input A, and two inputs labeled B. */
    private val twins = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="type" val="input"/><a name="label" val="B"/></comp>
    <comp lib="0" loc="(100,200)" name="Pin"><a name="type" val="input"/><a name="label" val="B"/></comp>
    <comp loc="(300,100)" name="half"><a name="label" val="Sub1"/></comp>
    <comp loc="(300,200)" name="half"><a name="label" val="Sub1"/></comp>
  </circuit>
  <circuit name="half">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="type" val="input"/><a name="label" val="A"/></comp>
  </circuit>
</project>"""

    @Test
    fun traceTellsApartSignalsWithTheSameName() = runDesktopComposeUiTest(390, 844) {
        val project = (CircCodec.readProject(twins) as CircProjectImportResult.Success).project
        val ids = project.circuits.flatMap { circuit -> circuit.components.map { it.id } }.toSet()
        val signals = TraceSignals.fromProject(project)
        val recorder = TraceRecorder(signals)
        val state = EditorState(twins)
        panel(dark = false) { TracePanel(project, state.rootSimulation, recorder, onExport = {}) }
        fun leaks(texts: List<String>) = texts.filter { shown ->
            ids.any { id -> Regex("(^|[^A-Za-z0-9])${Regex.escape(id)}([^A-Za-z0-9]|$)").containsMatchIn(shown) }
        }.distinct()
        // Each timeline row has its name and a canvas whose description TalkBack reads.
        val signalPrefix = text(Messages.TraceSignalA11y, "signal" to "", "instance" to "").substringBefore(",").trim()
        val spoken = spokenAndShown().filter { it.startsWith("$signalPrefix ") }
        val shown = signals.indices.map { index ->
            onNodeWithTag("trace-row-title-$index", useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
        }
        screenshot("trace-same-names-timeline")
        onNodeWithText(text(Messages.TraceOrderSignals)).performClick()
        waitForIdle()
        screenshot("trace-same-names-order")
        val moveUp = text(Messages.TraceMoveSignalUp, "signal" to "", "reference" to "").substringBefore(" ")
        val ordered = spokenAndShown()
        val moves = ordered.filter { it.startsWith(moveUp + " ") && it != text(Messages.ActionMoveUp) && it != text(Messages.ActionMoveDown) }
        onNodeWithText(text(Messages.TraceDone)).performClick()
        waitForIdle()
        onNodeWithText(text(Messages.TraceSelectSignals)).performClick()
        waitForIdle()
        screenshot("trace-same-names-picker")
        val (headings, rows) = pickerContents()
        val picked = spokenAndShown()
        observe("trace-same-names", "spoken" to spoken, "shown" to shown, "moves" to moves, "headings" to headings, "rows" to rows,
            "leaks" to leaks(spoken + shown + ordered + picked + headings + rows.flatten()))
        assertEquals(signals.size, spoken.size, "One TalkBack description per timeline row: $spoken")
        assertEquals(spoken.size, spoken.distinct().size, "Rows with the same label must be spoken differently: $spoken")
        assertEquals(signals.size, shown.distinct().size, "Rows with the same label must be shown differently: $shown")
        assertTrue(moves.size >= 4, "The order dialog shows move buttons: $moves")
        assertEquals(moves.size, moves.distinct().size, "Each move button must name its own signal: $moves")
        assertEquals(headings.size, headings.distinct().size, "Instances with the same label get their own picker heading: $headings")
        assertTrue(headings.size >= 3, "The picker groups the main circuit and both instances: $headings")
        assertEquals(signals.size, rows.size, "One picker row per signal: $rows")
        assertEquals(rows.size, rows.distinct().size, "Picker rows with the same label must read differently: $rows")
        val radix = radixDescriptions.values.toList()
        assertEquals(signals.size, radix.size, "Every selected signal has its radix choice: $radix")
        assertEquals(radix.size, radix.distinct().size, "Radix choices of same-named signals must be spoken differently: $radix")
        assertEquals(emptyList(), leaks(spoken + shown + ordered + picked + headings + rows.flatten()), "No component id is used to tell them apart")
    }

    // --- Appearance objects named by shape type (C160) ---

    @Test
    fun appearanceObjectsAreNamedByShape() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        val tool = appearanceEditorTool()
        panel(dark = false) {
            Column {
                tool.content(EditorToolContext(state.project, state.document, state.simulation,
                    state.document.components.filter { it.id in state.selectedIds }, { state.applyProject(it) }, { state.applyDocument(it) },
                    state::selectCircuit, { state.selectComponent(it) }, {}, documentIdentity = state.documentIdentity))
            }
        }
        listOf("+ Retângulo", "+ Retângulo", "+ Elipse").forEach { add ->
            onNodeWithText(add).performScrollTo().performClick()
            waitForIdle()
        }
        val chips = onAllNodes(SemanticsMatcher("a shape choice") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("appearance-shape-") == true
        }).fetchSemanticsNodes().map { node ->
            (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()).joinToString()
        }
        onNodeWithTag("appearance-shape-shape-1").performScrollTo()
        screenshot("appearance-objects")
        observe("appearance-objects", "chips" to chips)
        // The symbol's own outline (a curve and a rectangle) comes first, then the three added shapes.
        assertEquals(listOf("Curva quadrática 1", "Retângulo 1", "Retângulo 2", "Retângulo 3", "Elipse 1"), chips)
    }

    // --- Workspace rows: when the project was last changed, not the project UUID (C160) ---

    @Test
    fun workspaceRowsShowNoProjectId() = runDesktopComposeUiTest(390, 844) {
        val id = WorkspaceProjectId("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        val catalog = WorkspaceCatalogResult(listOf(
            WorkspaceProjectInfo(id, "Somador", revision = 7, createdAtMillis = 1_700_000_000_000, updatedAtMillis = 1_763_100_000_000),
        ), activeProjectId = null)
        // A fixed clock three days after the last change, so the relative time does not depend on today's date.
        val now = 1_763_100_000_000 + 3 * 24 * 60 * 60 * 1000L
        panel(dark = false) {
            WorkspacePanel(catalog, onOpen = {}, onCreate = { _, _ -> }, onDuplicate = { _, _ -> },
                onRename = { _, _, _, _ -> }, onDelete = { _, _ -> }, onRestore = { _, _ -> }, nowMillis = { now })
        }
        screenshot("workspace-row")
        val shown = spokenAndShown()
        val edited = shown.filter { it.startsWith("Editado") }
        observe("workspace-row", "edited" to edited)
        assertTrue(shown.none { "3fa85f64" in it }, shown.toString())
        assertEquals(listOf("Editado há 3 dias"), edited, "the row shows when the project was last changed: $shown")
    }
}
