package dev.logisim.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The inspector edits option attributes (label location, gate size, pin type...) by choosing from a menu of
 * Evolution's display names, and booleans with a switch, instead of retyping the serialized value. Each choice is
 * one undoable edit and the file keeps the serialized value. Screenshots go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class InspectorOptionEditorsHostTest {
    private val fixture = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")

    private fun ComposeUiTest.inspector(state: EditorState, language: AppLanguage = AppLanguage.PORTUGUESE) {
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

    private fun EditorState.component(label: String) = document.components.first { it.label == label }

    @Test
    fun labelLocationIsChosenFromAMenuAndAppliedAsOneUndoableEdit() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        val pin = state.component("E2E_IN")
        state.select(pin.id)
        inspector(state)
        // Option values show Evolution's display names in the app language, not the serialized values.
        assertEquals("Oeste", valueOf("labelloc"))
        assertEquals("Entrada", valueOf("type"))
        assertEquals("Simples", valueOf("behavior"))
        assertEquals("Binário", valueOf("radix"))
        screenshot("after-inspector-pin")

        onNodeWithTag("inspector-row-labelloc").performClick()
        waitForIdle()
        onNodeWithTag("inspector-options-labelloc").assertIsDisplayed()
        val offered = listOf("north", "south", "east", "west", "center")
            .filter { onAllNodesWithTag("inspector-option-labelloc-$it").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(setOf("north", "south", "east", "west", "center"), offered.toSet())
        onNodeWithTag("inspector-option-labelloc-west").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        screenshot("after-labelloc-menu")

        onNodeWithTag("inspector-option-labelloc-north").performClick()
        waitForIdle()
        assertEquals("north", state.component("E2E_IN").attributes["labelloc"])
        assertEquals("Norte", valueOf("labelloc"))
        assertTrue(CircCodec.writeProject(state.project, state.componentRegistry).contains("""<a name="labelloc" val="north"/>"""))
        screenshot("after-labelloc-north")

        state.undo()
        waitForIdle()
        assertEquals("west", state.component("E2E_IN").attributes["labelloc"])
    }

    @Test
    fun gateSizeOffersOnlyTheSizesTheGateAccepts() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.select(state.component("E2E_NOT").id)
        inspector(state, AppLanguage.ENGLISH)
        // A NOT gate accepts 20 and 30 (Evolution's Narrow and Wide); typing another number is no longer possible.
        assertEquals("Wide", valueOf("size"))
        onNodeWithTag("inspector-row-size").performClick()
        waitForIdle()
        assertEquals(listOf(true, true, false), listOf("20", "30", "50").map {
            onAllNodesWithTag("inspector-option-size-$it").fetchSemanticsNodes().isNotEmpty()
        })
        screenshot("after-gate-size-menu")
        onNodeWithTag("inspector-option-size-20").performClick()
        waitForIdle()
        assertEquals("20", state.component("E2E_NOT").attributes["size"])
        assertEquals("Narrow", valueOf("size"))
    }

    @Test
    fun booleansToggleWithASwitch() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState("""<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="1" loc="(200,100)" name="AND Gate"><a name="label" val="G"/></comp>
  </circuit>
</project>""")
        state.select(state.component("G").id)
        inspector(state)
        assertEquals("Não", valueOf("negate0"))
        onNodeWithTag("inspector-row-negate0").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch))
        onNodeWithTag("inspector-row-negate0").performClick()
        waitForIdle()
        assertEquals("true", state.component("G").attributes["negate0"])
        assertEquals("Sim", valueOf("negate0"))
        screenshot("after-boolean-switch")
    }

    @Test
    fun colorsArePickedFromSwatchesAndFontsFromAChooser() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState("""<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Base" name="6"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="6" loc="(200,100)" name="Text"><a name="text" val="Hi"/></comp>
  </circuit>
</project>""")
        val text = state.document.components.single()
        state.select(text.id)
        inspector(state)
        // Text has no label attribute, so the inspector offers no Label field.
        assertTrue(onAllNodesWithTag("inspector-label").fetchSemanticsNodes().isEmpty())
        assertEquals("SansSerif · Negrito · 16", valueOf("font"))
        screenshot("after-text-inspector")

        onNodeWithTag("inspector-row-color").performClick()
        waitForIdle()
        onNodeWithTag("color-swatch-#f00000").performClick()
        waitForIdle()
        screenshot("after-color-palette")
        onNodeWithText("Aplicar").performClick()
        waitForIdle()
        assertEquals("#f00000", state.document.components.single().attributes["color"])

        onNodeWithTag("inspector-row-font").performClick()
        waitForIdle()
        onNodeWithTag("font-style-").performClick()
        onNodeWithContentDescription("Aumentar tamanho").performClick()
        waitForIdle()
        screenshot("after-font-chooser")
        onNodeWithText("Aplicar").performClick()
        waitForIdle()
        assertEquals("SansSerif plain 17", state.document.components.single().attributes["font"])
        assertEquals("SansSerif · Normal · 17", valueOf("font"))
    }
}
