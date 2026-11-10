package dev.logisim.mobile

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.VisualExportOptions
import dev.logisim.mobile.core.WireSegment
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RendererDINExportTest {
    @Test
    fun exportSourceBuildsDinSvgWithTheSavedRotationAndConnectedWire() = runBlocking {
        val component = CircuitComponent("gate", ComponentKind.NAND, GridPoint(100, 200), attributes = mapOf("size" to "50", "facing" to "south"))
        val wire = WireSegment(GridPoint(40, 140), GridPoint(80, 140))
        val document = CircuitDocument("din", listOf(component), listOf(wire))
        val context = EditorToolContext(
            CircuitProject(listOf(document)), document, SimulationState(), emptyList(),
            applyProject = { error("Export must not mutate the project") },
            applyDocument = { error("Export must not mutate the document") },
            selectCircuit = { error("Export must not change selection") },
            selectComponent = { error("Export must not change selection") },
            showStatus = { error("The builder must not update UI status") },
        )
        val source = RenderedCircuitExportSource(ComponentCatalog.registry, unusedFontResolver, Density(1f), LayoutDirection.Ltr,
            CircuitDrawingStyle(gateShape = EditorGateShape.DIN40700))
        val scene = assertNotNull(source.scene(context, null))
        assertEquals(listOf(circuitDrawingWireId(wire), "gate"), scene.items.map { it.id })
        val asset = buildVisualExportAsset(context, source, null, VisualExportFormat.SVG, "din.svg", VisualExportOptions(), Density(1f))
        assertEquals("image/svg+xml", asset.mimeType)
        val svg = asset.bytes.decodeToString()
        assertTrue(svg.contains("rotate(90)"))
        assertTrue(svg.contains("M-60 -25 C"))
        assertTrue(svg.contains("M40 140 L80 140"))
        assertEquals(document, context.document)
        val ansiSource = RenderedCircuitExportSource(ComponentCatalog.registry, unusedFontResolver, Density(1f), LayoutDirection.Ltr)
        assertNull(ansiSource.scene(context, null))
    }

    private val unusedFontResolver = Proxy.newProxyInstance(
        FontFamily.Resolver::class.java.classLoader, arrayOf(FontFamily.Resolver::class.java),
    ) { _, _, _ -> error("The unlabeled vector fixture does not require platform fonts") } as FontFamily.Resolver
}
