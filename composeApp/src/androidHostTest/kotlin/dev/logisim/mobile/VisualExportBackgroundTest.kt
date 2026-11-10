package dev.logisim.mobile

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualExportOptions
import dev.logisim.mobile.core.VisualItem
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.VisualScene
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class VisualExportBackgroundTest {
    @Test
    fun svgPayloadBuildsOnWorkerAndReturnsToCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        val document = CircuitDocument(name = "Background")
        val context = EditorToolContext(
            CircuitProject(listOf(document)), document, SimulationState(), emptyList(),
            applyProject = { error("Export must not mutate the project") },
            applyDocument = { error("Export must not mutate the document") },
            selectCircuit = { error("Export must not change selection") },
            selectComponent = { error("Export must not change selection") },
            showStatus = { error("The builder must not update UI status") },
        )
        var sceneThread: Thread? = null
        val bounds = VisualBounds(10.0, 20.0, 30.0, 40.0)
        val source = object : CircuitExportSource {
            override fun bounds(context: EditorToolContext, selectionIds: Set<String>?): VisualBounds = error("SVG must use its complete scene")
            override fun draw(scope: DrawScope, context: EditorToolContext, selectionIds: Set<String>?) = error("SVG must retain vector objects")
            override fun scene(context: EditorToolContext, selectionIds: Set<String>?): VisualScene {
                sceneThread = Thread.currentThread()
                assertFalse(sceneThread === caller)
                assertEquals("Background", context.document.name)
                assertEquals(setOf("selected"), selectionIds)
                return VisualScene(listOf(VisualItem("selected", bounds, listOf(VisualPrimitive.Rectangle(bounds)))))
            }
        }

        val asset = buildVisualExportAsset(context, source, setOf("selected"), VisualExportFormat.SVG, "Background.PNG",
            VisualExportOptions(scale = 2.0, margin = 2.0, background = null), Density(1f))

        assertTrue(Thread.currentThread() === caller)
        assertTrue(sceneThread != null)
        assertEquals("Background.svg", asset.name)
        assertEquals("image/svg+xml", asset.mimeType)
        val svg = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(asset.bytes))
        assertEquals("48", svg.documentElement.getAttribute("width"))
        assertEquals("48", svg.documentElement.getAttribute("height"))
        assertEquals("8 18 24 24", svg.documentElement.getAttribute("viewBox"))
        assertEquals("selected", svg.getElementsByTagName("g").item(0).attributes.getNamedItem("data-object-id").nodeValue)
        assertEquals(1, svg.getElementsByTagName("rect").length)
    }
}
