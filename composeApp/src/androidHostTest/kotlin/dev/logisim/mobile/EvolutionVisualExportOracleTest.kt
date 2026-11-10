package dev.logisim.mobile

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.VisualExportOptions
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Writes the Mobile exports compared with Evolution by scripts/oracle/visual-export/run.py into
 * composeApp/build/evolution-visual-export. The host JVM has no Android graphics, so this covers the
 * vector scene path (SVG) and records which other paths need the platform canvas; EvolutionVectorExportHostTest
 * (jvmTest, Skia) covers the canvas-recorded SVG and TikZ.
 */
class EvolutionVisualExportOracleTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "fixtures/visual-export").isDirectory }
    private val output = File(root, "composeApp/build/evolution-visual-export").apply { mkdirs() }

    private fun context(path: String): EditorToolContext {
        val project = (CircCodec.readProject(File(root, path).readText()) as CircProjectImportResult.Success).project
        val document = assertNotNull(project.circuit(project.mainCircuitName))
        return EditorToolContext(project, document, SimulationState(), emptyList(),
            applyProject = { error("Export must not mutate the project") },
            applyDocument = { error("Export must not mutate the document") },
            selectCircuit = { error("Export must not change selection") },
            selectComponent = { error("Export must not change selection") },
            showStatus = { error("The builder must not update UI status") })
    }

    private fun source(shape: EditorGateShape = EditorGateShape.ANSI) = RenderedCircuitExportSource(ComponentCatalog.registry,
        unusedFontResolver, Density(1f), LayoutDirection.Ltr, CircuitDrawingStyle(gateShape = shape))

    @Test
    fun gateCircuitExportsTheSvgComparedWithEvolution() = runBlocking {
        val context = context("scripts/oracle/visual-export/svg-gates.circ")
        // ANSI gates (Evolution's default shape) have no vector scene; their SVG is recorded from the canvas (jvmTest).
        assertNull(source().scene(context, null))
        assertNotNull(source(EditorGateShape.IEC).scene(context, null), "IEC gates and wires form a complete vector scene")
        // Evolution expands the circuit bounds by 5 and draws at scale 1; the Mobile margin is set to match.
        val asset = buildVisualExportAsset(context, source(EditorGateShape.IEC), null, VisualExportFormat.SVG, "svg-gates",
            VisualExportOptions(scale = 1.0, margin = 5.0), Density(1f))
        File(output, "svg-gates-iec.svg").writeBytes(asset.bytes)
        File(output, "svg-gates-scene.txt").writeText("ansi=null\niec=complete\n")
    }

    @Test
    fun qualificationCircuitHasNoCompleteVectorScene() {
        val context = context("fixtures/visual-export/qualification.circ")
        // Custom symbols, pins and other components have no vector scene; their SVG is recorded from the canvas (jvmTest).
        assertNull(source().scene(context, null))
        val kinds = context.document.components.map { it.kind.name }.distinct().sorted()
        File(output, "qualification-scene.txt").writeText("scene=null\nkinds=${kinds.joinToString(",")}\n")
    }

    @Test
    fun rasterAndTikzNeedThePlatformCanvas() {
        val context = context("scripts/oracle/visual-export/svg-gates.circ")
        val failure = runCatching {
            renderDrawingTikz(assertNotNull(source().bounds(context, null)), VisualExportOptions(scale = 1.0, margin = 5.0), Density(1f)) {
                source().draw(this, context, null)
            }
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("not mocked"), "Unexpected TikZ result on the host: $failure")
        File(output, "host-canvas.txt").writeText("tikz=${failure?.message}\n")
    }

    private val unusedFontResolver = Proxy.newProxyInstance(
        FontFamily.Resolver::class.java.classLoader, arrayOf(FontFamily.Resolver::class.java),
    ) { _, _, _ -> error("The unlabeled vector fixture does not require platform fonts") } as FontFamily.Resolver
}
