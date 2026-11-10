package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.logisim.mobile.core.AnnotationLibrary
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentMenuItem
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.SocArchitecture
import dev.logisim.mobile.core.SocComponents
import dev.logisim.mobile.core.SocCpuDebugState
import dev.logisim.mobile.core.SocEvaluation
import dev.logisim.mobile.core.SocExecutableImage
import dev.logisim.mobile.core.SocExecutableSegment
import dev.logisim.mobile.core.SocProgramListing
import dev.logisim.mobile.core.SocRv32ComponentState
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The canvas routes a Poke tap on an input pin to the bit or digit under the pointer (#345) and a long press to
 * Evolution's Menu Tool popups (#347) and the other MenuExtenders (#359), through the shared CircuitCanvas the
 * Android and iOS editors use.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasPokeAndMenuTest {
    private val registry = ComponentCatalog.registry
    private val hex = registry.create(ComponentKind.INPUT, "h8", GridPoint(100, 100),
        mapOf("width" to "8", "radix" to "16", "appearance" to "classic", "facing" to "east"), "h8")
    private val ram = registry.create(ComponentKind.RAM, "ram", GridPoint(300, 300))
    private val document = CircuitDocument(components = listOf(hex, ram))

    private class Recorder {
        val pokes = mutableListOf<Pair<String, Int?>>()
        val menu = mutableListOf<Pair<ComponentMenuItem, String>>()
    }

    private fun ComposeUiTest.canvas(recorder: Recorder, mode: EditorMode, document: CircuitDocument = this@CanvasPokeAndMenuTest.document,
                                     simulation: SimulationState = SimulationState()): () -> CanvasViewportState {
        var viewport by mutableStateOf(CanvasViewportState())
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                CircuitCanvas(document, simulation, null, null, null, { _, _ -> }, {}, mode,
                    Modifier.fillMaxSize().testTag("canvas"), onCanvasTap = {}, viewportState = viewport,
                    onViewportChange = { viewport = it }, onSelectArea = { _, _, _ -> }, onSelectComponent = { _, _ -> },
                    onSelectWire = { _, _ -> }, onMoveSelection = { _, _ -> }, keepConnectionsWhenMoving = true,
                    onCommand = {}, onTogglePlacementMatrix = {}, wirePokePrimaryRadix = WirePokeRadix.BINARY,
                    wirePokeSecondaryRadix = null, onViewSubcircuit = {}, registry = registry, showZoomControls = false,
                    onPokePin = { id, bit -> recorder.pokes += id to bit },
                    onComponentMenu = { item, id -> recorder.menu += item to id })
            }
        }
        waitForIdle()
        return { viewport }
    }

    private fun CanvasViewportState.screen(x: Float, y: Float) = Offset(x * view.scale + view.pan.x, y * view.scale + view.pan.y)

    @Test
    fun tapPokesTheHexDigitUnderThePointer() = runComposeUiTest {
        val recorder = Recorder()
        val viewport = canvas(recorder, EditorMode.INTERACT)
        // Classic 8-bit hex pin at (100,100): body (80,90)-(100,110); digit 0 on the right, digit 1 to its left.
        onNodeWithTag("canvas").performTouchInput { click(viewport().screen(97f, 100f)) }
        waitForIdle()
        onNodeWithTag("canvas").performTouchInput { click(viewport().screen(87f, 100f)) }
        waitForIdle()
        assertEquals(listOf<Pair<String, Int?>>("h8" to 0, "h8" to 4), recorder.pokes)
    }

    @Test
    fun longPressOpensEvolutionsComponentMenu() = runComposeUiTest {
        val recorder = Recorder()
        val viewport = canvas(recorder, EditorMode.EDIT)
        val body = ram.bounds(registry)
        onNodeWithTag("canvas").performTouchInput {
            longClick(viewport().screen((body.left + body.right) / 2f, (body.top + body.bottom) / 2f))
        }
        waitForIdle()
        val expected = listOf("delete", "show_attributes", "memory_edit", "memory_clear", "memory_load", "memory_save")
        val shown = ComponentMenuItem.entries.map { it.name.lowercase() }.filter {
            onAllNodesWithTag("canvas-menu-$it").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(expected, shown)
        onNodeWithTag("canvas-menu-memory_clear").performTouchInput { click() }
        waitForIdle()
        assertEquals(listOf(ComponentMenuItem.MEMORY_CLEAR to "ram"), recorder.menu)
    }

    @Test
    fun longPressOnEmptyCanvasOpensNoMenu() = runComposeUiTest {
        val recorder = Recorder()
        val viewport = canvas(recorder, EditorMode.EDIT)
        // Between the pin's body and the RAM's: no component or wire there.
        val body = ram.bounds(registry)
        onNodeWithTag("canvas").performTouchInput { longClick(viewport().screen((100f + body.left) / 2f, (110f + body.top) / 2f)) }
        waitForIdle()
        assertEquals(0, onAllNodesWithTag("canvas-context-menu").fetchSemanticsNodes().size)
    }

    @Test
    fun longPressShowsTheSocAndImageExtenders() = runComposeUiTest {
        val recorder = Recorder()
        val cpu = registry.create(SocComponents.RV32, "cpu", GridPoint(100, 100))
        val image = registry.create(AnnotationLibrary.Image, "picture", GridPoint(100, 600))
        // SocUpStateInterface.programLoaded: a CPU holding a program also offers Show loaded program.
        val loaded = (SocEvaluation.initialState(cpu) as SocRv32ComponentState).copy(debugger = SocCpuDebugState(program =
            SocProgramListing(SocExecutableImage(SocArchitecture.RV32IM, 0u, listOf(SocExecutableSegment(0u, byteArrayOf(0x13, 0, 0, 0), 4u))))))
        val viewport = canvas(recorder, EditorMode.EDIT, CircuitDocument(components = listOf(cpu, image)),
            SimulationState(componentStates = mapOf("cpu" to loaded)))
        fun shown() = ComponentMenuItem.entries.map { it.name.lowercase() }.filter {
            onAllNodesWithTag("canvas-menu-$it").fetchSemanticsNodes().isNotEmpty()
        }
        fun press(component: CircuitComponent) {
            val body = component.bounds(registry)
            onNodeWithTag("canvas").performTouchInput {
                longClick(viewport().screen((body.left + body.right) / 2f, (body.top + body.bottom) / 2f))
            }
            waitForIdle()
        }
        press(cpu)
        assertEquals(listOf("delete", "show_attributes", "soc_assembler", "soc_read_elf", "soc_show_program"), shown())
        onNodeWithTag("canvas-menu-soc_show_program").performTouchInput { click() }
        waitForIdle()
        press(image)
        assertEquals(listOf("delete", "show_attributes", "image_reset_size", "image_optimize", "image_make_transparent"), shown())
        onNodeWithTag("canvas-menu-image_optimize").performTouchInput { click() }
        waitForIdle()
        assertEquals(listOf(ComponentMenuItem.SOC_SHOW_PROGRAM to "cpu", ComponentMenuItem.IMAGE_OPTIMIZE to "picture"), recorder.menu)
    }
}
