package dev.logisim.mobile

import androidx.compose.ui.graphics.ImageBitmap
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.SocBusReply
import dev.logisim.mobile.core.SocBusRequest
import dev.logisim.mobile.core.SocComponents
import dev.logisim.mobile.core.SocDeviceComponentState
import dev.logisim.mobile.core.SocVgaDevice
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualExportOptions
import kotlin.test.Test
import kotlin.test.assertEquals

/** Checks the shared editor/export dispatch through real Skia pixels. */
class SocDrawingRasterTest {
    @Test
    fun vgaDrawsTheLiveFramebufferThroughTheCircuitRenderer() {
        val component = CircuitComponent("vga", SocComponents.VGA, GridPoint(20, 30))
        val device = SocVgaDevice("vga", bufferAddress = 0x1000u)
        device.observe(SocBusRequest(0x1000u, write = true, read = false, writeData = 0x00ff0000u), SocBusReply())
        val state = SimulationState(componentStates = mapOf("vga" to SocDeviceComponentState(device)))
        val bitmap = draw(CircuitDocument(components = listOf(component)), state, VisualBounds(0.0, 0.0, 200.0, 210.0))
        val pixels = bitmap.pixels()

        assertEquals(0xffff0000.toInt(), pixels[50 * bitmap.width + 25])
        assertEquals(0xff000000.toInt(), pixels[90 * bitmap.width + 70])
        assertEquals(0xffffffff.toInt(), pixels[180 * bitmap.width + 70])
    }

    @Test
    fun bothCpuKindsDrawTheirRegisterPanelThroughTheCircuitRenderer() {
        for (kind in listOf(SocComponents.RV32, SocComponents.NIOS2)) {
            val component = CircuitComponent("cpu", kind, GridPoint(20, 30))
            val bitmap = draw(CircuitDocument(components = listOf(component)), SimulationState(), VisualBounds(0.0, 0.0, 700.0, 720.0))
            val pixels = bitmap.pixels()

            assertEquals(0xffffff00.toInt(), pixels[330 * bitmap.width + 210], kind.name)
            assertEquals(0xff0000ff.toInt(), pixels[45 * bitmap.width + 90], kind.name)
        }
    }

    private fun draw(document: CircuitDocument, state: SimulationState, bounds: VisualBounds): ImageBitmap {
        val measurer = createRasterTextMeasurer()
        return renderDrawingBitmap(bounds, VisualExportOptions(margin = 0.0, background = 0xffffffffu)) {
            drawCircuitDrawing(document, state, measurer, AnnotationImageCache())
        }
    }

    private fun ImageBitmap.pixels(): IntArray = IntArray(width * height).also { readPixels(it) }
}
