package dev.logisim.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.logisim.mobile.core.AnnotationEdits
import dev.logisim.mobile.core.AnnotationFont
import dev.logisim.mobile.core.AnnotationLibrary
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.FileWorkspaceCatalogStorage
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SymbolAppearance
import dev.logisim.mobile.core.SymbolObject
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Colors, fonts, choices and destructive actions outside the property inspector (#C97, C98, C100, C105, C107, C39, C96):
 * colors are picked from swatches instead of retyping hex, preference menus expose their role and current value,
 * resets ask first, and the appearance draft survives closing its sheet. Screenshots go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class PickersHostTest {
    private var ui: ComposeUiTest? = null

    private fun ComposeUiTest.host(language: AppLanguage = AppLanguage.ENGLISH, dark: Boolean = false, content: @Composable () -> Unit) {
        ui = this
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language, LocalEditorDarkTheme provides dark) {
                LogisimTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        waitForIdle()
    }

    /** Scrolls every enclosing scroll container (vertical sheets hold horizontal rows) until the node is centered. */
    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction {
        // Outer containers first: an offscreen horizontal row is brought into view before it scrolls its item.
        repeat(3) {
            val target = fetchSemanticsNode()
            var parent = target.parent
            while (parent != null) {
                val scroll = parent.config.getOrNull(SemanticsActions.ScrollBy)
                if (scroll != null) {
                    // Unclipped bounds: boundsInRoot is empty for a node scrolled out of view.
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

    private fun ComposeUiTest.stateOf(tag: String): String? =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun ComposeUiTest.descriptionOf(tag: String): String =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    /** Width and height of a semantics node, in dp: a clickable's node has the bounds of its clickable modifier. */
    private fun ComposeUiTest.sizeOf(node: androidx.compose.ui.semantics.SemanticsNode) =
        with(density) { node.size.width.toDp() to node.size.height.toDp() }

    private fun ComposeUiTest.assertTouchSized(matcher: SemanticsMatcher, what: String) {
        val nodes = onAllNodes(matcher).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty(), "No $what")
        for (node in nodes) {
            val (width, height) = sizeOf(node)
            assertTrue(width >= 48.dp && height >= 48.dp,
                "$what ${node.config.getOrNull(SemanticsProperties.TestTag)} is $width x $height")
        }
    }

    private val colorSwatch = SemanticsMatcher("a palette swatch") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.matches(Regex("color-swatch-#[0-9a-f]{6}")) == true
    }

    private fun ComposeUiTest.shapeTags() = onAllNodes(SemanticsMatcher("a shape choice") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("appearance-shape-") == true
    }).fetchSemanticsNodes().map { checkNotNull(it.config.getOrNull(SemanticsProperties.TestTag)) }.toSet()

    private fun preferencesTest(width: Int, suffix: String) = runDesktopComposeUiTest(width, 844) {
        var preferences by mutableStateOf(EditorPreferences(gateShape = EditorGateShape.ANSI))
        var changes = 0
        host {
            EditorPreferencesPanel(preferences, { preferences = it; changes++ }, {}, Modifier.fillMaxSize())
        }
        // The section toggle says whether its rows are shown.
        assertEquals("Collapsed", stateOf("preferences-colors"))
        onNodeWithText("Canvas colors").reveal().performClick()
        waitForIdle()
        assertEquals("Expanded", stateOf("preferences-colors"))
        screenshot("preferences-canvas-colors$suffix")

        // Signal high is its own row with a swatch; one swatch tap stores the color, with no typing.
        onNodeWithTag("preferences-color-signalHigh").reveal()
        screenshot("preferences-canvas-colors-signals$suffix")
        assertEquals("Default", stateOf("preferences-color-signalHigh"))
        // The Current swatch and an unchanged Apply leave a default color at its default (no custom value is stored).
        onNodeWithTag("preferences-color-signalHigh").performClick()
        waitForIdle()
        onNodeWithTag("color-swatch-current").reveal().performClick()
        waitForIdle()
        onNodeWithTag("preferences-color-signalHigh").performClick()
        waitForIdle()
        onNodeWithTag("color-picker-apply").performClick()
        waitForIdle()
        assertEquals(0, changes)
        assertTrue(preferences.colors.isEmpty())
        assertTrue(onAllNodesWithTag("preferences-color-restore-signalHigh").fetchSemanticsNodes().isEmpty())

        onNodeWithTag("preferences-color-signalHigh").performClick()
        waitForIdle()
        screenshot("preferences-color-picker$suffix")
        assertTouchSized(colorSwatch, "Swatch")
        onNodeWithTag("color-swatch-#ff8000").reveal().performClick()
        waitForIdle()
        assertEquals(0xff8000, preferences.colors[EditorColor.SIGNAL_HIGH])
        assertEquals(1, changes)
        assertEquals("Custom", stateOf("preferences-color-signalHigh"))
        assertTrue(onNodeWithTag("preferences-color-signalHigh").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains("#ff8000") })
        // The K-map group colors are a compact grid of swatches.
        onNodeWithTag("preferences-color-kmap16").reveal()
        screenshot("preferences-canvas-colors-kmap$suffix")
    }

    @Test
    fun canvasColorsArePickedFromSwatchRows() = preferencesTest(390, "")

    @Test
    fun canvasColorsFitANarrowPhone() = preferencesTest(360, "-360")

    @Test
    fun darkTextColorStoresTheLightReference() = runDesktopComposeUiTest(390, 844) {
        var preferences by mutableStateOf(EditorPreferences(gateShape = EditorGateShape.ANSI, themeMode = EditorThemeMode.DARK))
        host(dark = true) { EditorPreferencesPanel(preferences, { preferences = it }, {}, Modifier.fillMaxSize()) }
        onNodeWithText("Canvas colors").reveal().performClick()
        waitForIdle()
        onNodeWithTag("preferences-color-textTool").reveal().performClick()
        waitForIdle()
        screenshot("preferences-color-picker-dark-text")
        onNodeWithTag("color-swatch-#ffff00").performClick()
        waitForIdle()
        // The dark theme shows the luma inversion; the stored TEXT_TOOL_COLOR stays the light reference (#340).
        assertEquals(ThemeTextColor.invertRgb(0xffff00), preferences.colors[EditorColor.TEXT_TOOL])
        assertNull(preferences.darkColors[EditorColor.TEXT_TOOL])
    }

    @Test
    fun preferenceMenusExposeTheirRoleAndCurrentValue() = runDesktopComposeUiTest(390, 844) {
        var preferences by mutableStateOf(EditorPreferences(gateShape = EditorGateShape.ANSI))
        host { EditorPreferencesPanel(preferences, { preferences = it }, {}, Modifier.fillMaxSize()) }
        screenshot("preferences-top")
        onNodeWithTag("preferences-theme").performClick()
        waitForIdle()
        screenshot("preferences-theme-menu")
        // The current value is the selected item of the open menu.
        onAllNodes(hasText(SYSTEM) and isSelected()).assertCountEquals(1)
        onAllNodes(hasText("Dark") and isSelected()).assertCountEquals(0)
        onNodeWithTag("preferences-theme").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
        // The row keeps its "Title: value" description, which the end-to-end harnesses read, and announces the value
        // once: no state description repeats it.
        assertEquals(listOf("Theme: $SYSTEM"), onNodeWithTag("preferences-theme").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription))
        assertNull(stateOf("preferences-theme"))
        onAllNodesWithText("Dark")[0].performClick()
        waitForIdle()
        assertEquals(EditorThemeMode.DARK, preferences.themeMode)
    }

    @Test
    fun restoreActionsAskFirst() = runDesktopComposeUiTest(390, 844) {
        var preferences by mutableStateOf(EditorPreferences(gateShape = EditorGateShape.ANSI,
            colors = mapOf(EditorColor.CANVAS to 0x101010, EditorColor.SIGNAL_HIGH to 0xff8000)))
        var restores = 0
        host { EditorPreferencesPanel(preferences, { preferences = it }, { restores++ }, Modifier.fillMaxSize()) }
        onNodeWithText("Restore editor defaults").reveal().performClick()
        waitForIdle()
        screenshot("preferences-restore-defaults-confirm")
        assertEquals(0, restores)
        onNodeWithTag("confirm-dialog-cancel").performClick()
        waitForIdle()
        assertEquals(0, restores)
        onNodeWithText("Restore editor defaults").reveal().performClick()
        waitForIdle()
        onNodeWithTag("confirm-dialog-confirm").performClick()
        waitForIdle()
        assertEquals(1, restores)

        // Restoring every color of the theme asks too when more than one is customized.
        onNodeWithText("Canvas colors").reveal().performClick()
        waitForIdle()
        onNodeWithTag("preferences-colors-restore").reveal().performClick()
        waitForIdle()
        screenshot("preferences-restore-colors-confirm")
        // The shared text tool color is not customized here, so the message does not say it is restored.
        onNode(hasText("2 custom colors", substring = true)).assertExists()
        onAllNodes(hasText("text tool", substring = true)).assertCountEquals(0)
        assertEquals(2, preferences.colors.size)
        onNodeWithTag("confirm-dialog-confirm").performClick()
        waitForIdle()
        assertEquals(emptyMap(), preferences.colors)
    }

    @Test
    fun removingTheTemplateAsksFirst() = runDesktopComposeUiTest(390, 844) {
        val directory = Files.createTempDirectory("template-host").toFile()
        val store = ProjectTemplateStore(FileWorkspaceCatalogStorage(directory), ComponentCatalog.registry)
        store.install(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ").encodeToByteArray())
        val assets = object : DocumentAssetActions {
            override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) = onResult(AssetOpenResult.Cancelled)
            override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
            override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
            override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
        }
        host { Column { ProjectTemplateSettings(store, assets) } }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Remove template").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Remove template").performClick()
        waitForIdle()
        screenshot("template-remove-confirm")
        assertNotNull(store.read())
        onNodeWithTag("confirm-dialog-confirm").performClick()
        waitUntil(timeoutMillis = 10_000) { store.read() == null }
        directory.deleteRecursively()
    }

    @Test
    fun aLongMenuOpensAtItsCurrentValue() = runDesktopComposeUiTest(390, 360) {
        val last = EditorLocale.available.last()
        val label = checkNotNull(last.language).nativeName
        var preferences by mutableStateOf(EditorPreferences(gateShape = EditorGateShape.ANSI, locale = last))
        host { EditorPreferencesPanel(preferences, { preferences = it }, {}, Modifier.fillMaxSize()) }
        onNode(hasContentDescription("Language: $label")).performClick()
        waitForIdle()
        screenshot("preferences-language-menu-current")
        // The current value is far below the third item, yet the open menu shows it.
        onNode(hasText(label) and isSelected()).assertIsDisplayed()
    }

    @Test
    fun colorPickerSwatchesAreTouchSizedOnANarrowPhone() = runDesktopComposeUiTest(360, 780) {
        host {
            ColorPickerDialog(title = "Signal high", initial = ColorPickerModel(0x00ff00), default = ColorPickerModel(0x00d200),
                alphaEnabled = true, onPick = {}, onDismiss = {})
        }
        screenshot("color-picker-360")
        assertTouchSized(colorSwatch, "Swatch")
        // TalkBack names each swatch and reads each slider as the value shown beside it.
        assertEquals("Red: #f00000", descriptionOf("color-swatch-#f00000"))
        assertEquals("Amber: #ffc107", descriptionOf("color-swatch-#ffc107"))
        assertEquals("120\u00b0", stateOf("color-picker-hue"))
        assertEquals("100%", stateOf("color-picker-saturation"))
        assertEquals("100%", stateOf("color-picker-alpha"))
    }

    @Test
    fun colorPickerFitsALandscapePhone() = runDesktopComposeUiTest(568, 320) {
        var picked: ColorPickerModel? = null
        host {
            ColorPickerDialog(title = "Signal high", initial = ColorPickerModel(0x00ff00), default = ColorPickerModel(0x00d200),
                alphaEnabled = false, onPick = { picked = it }, onDismiss = {})
        }
        screenshot("color-picker-568x320")
        assertTouchSized(colorSwatch, "Swatch")
        onNodeWithTag("color-picker-apply").assertIsDisplayed()
        onNodeWithTag("color-swatch-#000080").reveal().performClick()
        waitForIdle()
        assertEquals(ColorPickerModel(0x000080), picked)
    }

    private class OpenTemplate(private val bytes: ByteArray) : DocumentAssetActions {
        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) =
            onResult(AssetOpenResult.Opened(DocumentAsset("lab-template.circ", "application/octet-stream", bytes)))
        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Cancelled)
    }

    @Test
    fun removingTheTemplateNamesItAndSaysWhatNewProjectsUse() = runDesktopComposeUiTest(390, 844) {
        val directory = Files.createTempDirectory("template-name-host").toFile()
        val store = ProjectTemplateStore(FileWorkspaceCatalogStorage(directory), ComponentCatalog.registry)
        val assets = OpenTemplate(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ").encodeToByteArray())
        host { Column { ProjectTemplateSettings(store, assets) } }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText("Choose .circ template") and hasClickAction()).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Disabled) == null
            }
        }
        onNodeWithText("Choose .circ template").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Remove template").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(ProjectTemplateType.CUSTOM, store.type())
        // With the template selected, removing it returns new projects to the plain template; the dialog names the file.
        onNodeWithText("Remove template").performClick()
        waitForIdle()
        screenshot("template-remove-confirm-named")
        onNode(hasText("\u201clab-template.circ\u201d", substring = true)).assertExists()
        onNode(hasText("plain template", substring = true)).assertExists()
        onNodeWithTag("confirm-dialog-cancel").performClick()
        waitForIdle()
        // With the empty template selected, new projects keep it, and the dialog does not say otherwise.
        onNodeWithTag("project-template-empty").performClick()
        waitUntil(timeoutMillis = 10_000) { store.type() == ProjectTemplateType.EMPTY }
        waitForIdle()
        onNodeWithText("Remove template").performClick()
        waitForIdle()
        screenshot("template-remove-confirm-empty-selected")
        onNode(hasText("\u201clab-template.circ\u201d", substring = true)).assertExists()
        onAllNodes(hasText("plain template", substring = true)).assertCountEquals(0)
        onNodeWithTag("confirm-dialog-confirm").performClick()
        waitUntil(timeoutMillis = 10_000) { store.read() == null }
        assertEquals(ProjectTemplateType.EMPTY, store.type())
        directory.deleteRecursively()
    }

    private fun toolContext(state: EditorState) = EditorToolContext(state.project, state.document, state.simulation,
        state.document.components.filter { it.id in state.selectedIds }, { state.applyProject(it) }, { state.applyDocument(it) },
        state::selectCircuit, { state.selectComponent(it) }, {}, documentIdentity = state.documentIdentity)

    @Test
    fun appearanceShapesUseSwatchesMenusAndRadioFacing() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val tool = appearanceEditorTool()
        var open by mutableStateOf(true)
        host { if (open) Column { tool.content(toolContext(state)) } }
        screenshot("appearance-initial")
        onNodeWithText("+ Rectangle").reveal().performClick()
        waitForIdle()
        onNodeWithTag("appearance-remove-object").reveal()
        screenshot("appearance-shape-properties")
        onNodeWithText("West", substring = true).reveal()
        screenshot("appearance-anchor-facing")

        // No control uses a check mark as its only state.
        onAllNodes(hasText("✓", substring = true)).assertCountEquals(0)
        val gridRow = with(density) { onNodeWithTag("appearance-show-grid").fetchSemanticsNode().size.height.toDp() }
        assertTrue(gridRow >= 48.dp, "The show-grid row is $gridRow tall")
        // The -/+ steppers and the object choices are full touch targets, not 40 dp ones.
        assertTouchSized(hasContentDescription("Increase ", substring = true), "Step button")
        assertTouchSized(hasContentDescription("Decrease ", substring = true), "Step button")
        assertTouchSized(hasTestTag("appearance-select-anchor"), "Anchor choice")
        assertTouchSized(SemanticsMatcher("a shape choice") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("appearance-shape-") == true
        }, "Shape choice")

        onNodeWithTag("appearance-object-stroke-width").reveal().performClick()
        waitForIdle()
        screenshot("appearance-stroke-width-menu")
        onNodeWithTag("appearance-stroke-width-2").performClick()
        waitForIdle()
        onNodeWithTag("appearance-object-stroke").reveal().performClick()
        waitForIdle()
        screenshot("appearance-stroke-picker")
        // The pick is in the draft at once: there is no "Update object" step.
        onNodeWithTag("color-swatch-#f00000").performClick()
        waitForIdle()
        onNodeWithTag("appearance-facing-west").reveal().performClick()
        waitForIdle()
        onNodeWithTag("appearance-facing-west").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        onNodeWithTag("appearance-facing-west").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        onNodeWithTag("appearance-facing-east").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        screenshot("appearance-facing-west")

        // Closing the sheet keeps the unapplied draft; reopening offers to apply or discard it.
        open = false
        waitForIdle()
        open = true
        waitForIdle()
        onNodeWithTag("appearance-unapplied-banner").reveal()
        screenshot("appearance-reopened-draft")
        onNodeWithTag("appearance-facing-west").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        onNodeWithText("Use classic appearance").reveal().performClick()
        waitForIdle()
        screenshot("appearance-classic-confirm")
        onNodeWithTag("confirm-dialog-cancel").performClick()
        waitForIdle()

        onNodeWithTag("appearance-apply").performClick()
        waitForIdle()
        val applied = SymbolAppearance.read(state.document)
        assertEquals("west", applied.anchor.facing)
        val rect = applied.objects.filterIsInstance<SymbolObject.Shape>().last { it.tag == "rect" }
        assertEquals("2", rect.attributes["stroke-width"])
        assertEquals("#f00000", rect.attributes["stroke"])
        assertTrue(onAllNodesWithTag("appearance-unapplied-banner").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun resettingOrDiscardingTheDraftAsksOrRestoresTheLoadedAppearance() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val tool = appearanceEditorTool()
        var open by mutableStateOf(true)
        host { if (open) Column { tool.content(toolContext(state)) } }
        val loaded = shapeTags()
        onNodeWithText("+ Rectangle").reveal().performClick()
        waitForIdle()
        val edited = shapeTags()
        assertTrue(edited.size == loaded.size + 1, "$loaded -> $edited")
        // Reset draft replaces unapplied edits, so it asks; Cancel keeps them, Reset replaces them with the classic shape.
        onNodeWithTag("appearance-reset-draft").reveal().performClick()
        waitForIdle()
        screenshot("appearance-reset-draft-confirm")
        onNodeWithTag("confirm-dialog-cancel").performClick()
        waitForIdle()
        assertEquals(edited, shapeTags())
        onNodeWithTag("appearance-reset-draft").reveal().performClick()
        waitForIdle()
        onNodeWithTag("confirm-dialog-confirm").performClick()
        waitForIdle()
        assertEquals(loaded, shapeTags())
        // A draft kept over closing the sheet can be discarded from the banner: the loaded appearance comes back.
        onNodeWithText("+ Rectangle").reveal().performClick()
        waitForIdle()
        open = false
        waitForIdle()
        open = true
        waitForIdle()
        onNodeWithTag("appearance-unapplied-discard").reveal().performClick()
        waitForIdle()
        assertTrue(onAllNodesWithTag("appearance-unapplied-banner").fetchSemanticsNodes().isEmpty())
        assertEquals(loaded, shapeTags())
        assertTrue(!SymbolAppearance.isCustom(state.document))
    }

    @Test
    fun theDraftSurvivesAPreferenceChangeWhileTheSheetIsClosed() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        var highContrast by mutableStateOf(false)
        var open by mutableStateOf(true)
        host {
            // The editor's tool list is rebuilt when a drawing preference changes; the appearance draft must not be.
            val tools = rememberAdditionalEditorTools(ComponentCatalog.registry, highContrastSignals = highContrast)
            val tool = tools.first { it.id == "appearance" }
            if (open) Column { tool.content(toolContext(state)) }
        }
        onNodeWithText("+ Rectangle").reveal().performClick()
        waitForIdle()
        val edited = shapeTags()
        open = false
        waitForIdle()
        highContrast = true
        waitForIdle()
        open = true
        waitForIdle()
        onNodeWithTag("appearance-unapplied-banner").reveal()
        assertEquals(edited, shapeTags())
    }

    @Test
    fun annotationTextUsesTheFontChooserSwatchesAndRadioAlignment() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        host { Column { AnnotationEditorPanel(toolContext(state)) } }
        screenshot("annotation-panel")
        onNodeWithText("Insert text").reveal()
        screenshot("annotation-panel-font")

        onNodeWithTag("annotation-halign-center").reveal().performClick()
        waitForIdle()
        onNodeWithTag("annotation-halign-center").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        onNodeWithTag("annotation-halign-center").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        onNodeWithTag("annotation-halign-left").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        // The chooser builds the string that used to be typed: "SansSerif bold 16".
        onNodeWithTag("annotation-font-style-b").reveal().performClick()
        onNodeWithTag("annotation-font-size-preset-16").reveal().performClick()
        waitForIdle()
        onNodeWithTag("annotation-color").reveal().performClick()
        waitForIdle()
        screenshot("annotation-color-picker")
        onNodeWithTag("color-swatch-#f00000").performClick()
        waitForIdle()
        onNodeWithText("Insert text").reveal()
        screenshot("annotation-chosen")
        onNodeWithText("Insert text").performClick()
        waitForIdle()
        val text = state.document.components.last { it.kind == AnnotationLibrary.Text }
        assertEquals("SansSerif bold 16", text.attributes["font"])
        assertEquals("#f00000", text.attributes["color"])
        assertEquals("center", text.attributes["halign"])
        onAllNodes(hasText("✓", substring = true)).assertCountEquals(0)
    }

    @Test
    fun annotationFontUsesTheAppearanceEditorsControls() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        state.applyDocument(AnnotationEdits.insertText(state.document, "annotation-named", GridPoint(100, 100), "Named",
            checkNotNull(AnnotationFont.parse("Arial bold 14")), "#336699"))
        state.selectComponent("annotation-named")
        host { Column { AnnotationEditorPanel(toolContext(state)) } }
        onNodeWithTag("annotation-color").reveal()
        assertEquals("Text color: #336699", descriptionOf("annotation-color"))
        // Same family menu and size presets as the appearance editor: "Custom: Arial", Evolution's 8-24 presets.
        onNodeWithTag("annotation-font-family").reveal()
        assertEquals("Custom: Arial", stateOf("annotation-font-family"))
        for (preset in FontChoice.SIZE_PRESETS) onNodeWithTag("annotation-font-size-preset-$preset").assertExists()
        onNodeWithTag("annotation-font-size-preset-14").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        onNodeWithTag("annotation-font-family").performClick()
        waitForIdle()
        screenshot("annotation-font-family-menu")
        onAllNodes(hasText("\u2713", substring = true)).assertCountEquals(0)
        onNode(hasText("Custom: Arial") and isSelected()).assertExists()
        onNodeWithTag("annotation-font-family-Serif").performClick()
        waitForIdle()
        onNodeWithTag("annotation-color").reveal().performClick()
        waitForIdle()
        assertEquals("#336699", onNodeWithTag("color-picker-hex").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text)
        onNodeWithTag("color-swatch-#f00000").reveal().performClick()
        waitForIdle()
        onNodeWithText("Update text").reveal().performClick()
        waitForIdle()
        val updated = state.document.components.single { it.id == "annotation-named" }
        assertEquals("Serif bold 14", updated.attributes["font"])
        assertEquals("#f00000", updated.attributes["color"])
    }

    @Test
    fun colorPickerInPortuguese() = runDesktopComposeUiTest(390, 844) {
        var picked: ColorPickerModel? = null
        host(AppLanguage.PORTUGUESE) {
            ColorPickerDialog(title = "Cor do sinal alto", initial = ColorPickerModel(0x00d200), default = ColorPickerModel(0x00d200),
                alphaEnabled = true, onPick = { picked = it }, onDismiss = {})
        }
        screenshot("color-picker-pt")
        onNodeWithTag("color-picker-hex").reveal()
        screenshot("color-picker-pt-advanced")
        onNodeWithTag("color-swatch-#0000ff").reveal().performClick()
        waitForIdle()
        assertEquals(ColorPickerModel(0x0000ff), picked)
    }

    @Test
    fun everyFixtureFontRoundTripsThroughTheChooser() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "fixtures").isDirectory }
        val pattern = Regex("""name="(?:font|labelfont)" val="([^"]*)"""")
        val fonts = java.io.File(root, "fixtures").walkTopDown().filter { it.isFile && it.extension in setOf("circ", "xml") }
            .flatMap { file -> pattern.findAll(file.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue(fonts.isNotEmpty())
        for (font in fonts) {
            val choice = assertNotNull(FontChoice.parse(font), font)
            assertEquals(font, choice.serialized)
            // Offering the same font back reports no change, so the attribute keeps its text.
            assertNull(FontChoice.update(font, choice.serialized))
        }
    }

    @Test
    fun evolutionAppearanceRoundTripsAfterOpeningTheEditor() = runDesktopComposeUiTest(390, 844) {
        val xml = HostScreenshots.fixture("fixtures/evolution-names/mobile-example-sap1.circ")
        val state = EditorState(xml)
        val circuit = state.project.circuits.first { c -> c.extraXml.any { "text-anchor" in it } }
        state.selectCircuit(circuit.name)
        val before = CircCodec.writeProject(state.project, state.componentRegistry)
        var open by mutableStateOf(true)
        val tool = appearanceEditorTool()
        host { if (open) Column { tool.content(toolContext(state)) } }
        val textShape = SymbolAppearance.read(state.document).objects.filterIsInstance<SymbolObject.Shape>().first { it.tag == "text" }
        onNodeWithTag("appearance-shape-${textShape.id}").reveal().performClick()
        waitForIdle()
        onNodeWithTag("appearance-remove-object").reveal()
        screenshot("appearance-evolution-text")
        open = false
        waitForIdle()
        assertEquals(before, CircCodec.writeProject(state.project, state.componentRegistry))
    }
}

private const val SYSTEM = "Follow system light/dark setting"
