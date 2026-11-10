package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.*

/** The editor wrapper draws pins, labels and selection. This draws only the SoC body. */
internal fun DrawScope.drawSocComponent(component: CircuitComponent, state: ComponentState?, measurer: TextMeasurer, printerView: Boolean = false, ink: Color = Color.Black): Boolean {
    if (component.kind !in SocComponents.kinds) return false
    val attributes = SocComponents.attributes(component)
    val bounds = SocComponents.definition(component.kind).layout(component, attributes).bounds
    val origin = Offset(bounds.left.toFloat(), bounds.top.toFloat())
    val size = Size((bounds.right - bounds.left).toFloat(), (bounds.bottom - bounds.top).toFloat())
    fun box(x: Int, y: Int, width: Int, height: Int, fill: Color = Color.White) {
        val at = origin + Offset(x.toFloat(), y.toFloat())
        drawRect(fill, at, Size(width.toFloat(), height.toFloat()))
        drawRect(ink, at, Size(width.toFloat(), height.toFloat()), style = Stroke(1f))
    }
    fun text(value: String, x: Int, y: Int, color: Color = ink, fontSize: Int = 12) {
        drawCircuitText(measurer.measure(value, TextStyle(color = color, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp)), topLeft = origin + Offset(x.toFloat(), y.toFloat()))
    }
    fun UInt.hex() = toString(16).padStart(8, '0').uppercase()
    drawRect(Color.White, origin, size)
    drawRect(ink, origin, size, style = Stroke(2f))
    val device = (state as? SocDeviceComponentState)?.device
    when (component.kind) {
        SocComponents.BUS -> {
            val trace = (state as? SocBusComponentState)?.bus?.trace.orEmpty().takeLast(attributes[SocComponents.TraceSize]).asReversed()
            text("SoC bus   ${attributes[SocComponents.BusIdentifier]}", 8, 5)
            if (attributes[SocComponents.TraceVisible]) for ((index, transaction) in trace.withIndex()) {
                val request = transaction.request
                val operation = if (request.read && request.write) "R/W" else if (request.write) "W" else "R"
                val result = transaction.reply.error?.name ?: transaction.reply.data.hex()
                text("${transaction.sequence}  $operation ${request.address.hex()} ${request.writeData.hex()}  $result", 8, 35 + index * 30,
                    if (transaction.reply.successful) ink else Color.Red)
            }
        }
        SocComponents.RV32, SocComponents.NIOS2 -> {
            val rv = (state as? SocRv32ComponentState)?.cpu
            val nios = (state as? SocNios2ComponentState)?.cpu
            val pc = rv?.pc ?: nios?.pc ?: attributes[SocComponents.ResetVector]
            val registers = rv?.registers ?: nios?.registers ?: List(32) { 0u }
            val valid = rv?.knownRegisters ?: nios?.knownRegisters ?: 1u
            val trace = rv?.trace ?: nios?.trace.orEmpty()
            val stopped = rv?.stopped ?: nios?.stopped ?: true
            if (attributes[SocComponents.StateVisible] && !printerView) {
                box(50, 10, 160, 495, Color.Yellow)
                box(50, 10, 160, 15, Color.Blue)
                text("Registers", 85, 10, Color.Yellow)
                for (index in 0..31) {
                    text("${if (component.kind == SocComponents.RV32) "x" else "r"}$index", 54, 27 + index * 15, fontSize = 10)
                    box(90, 26 + index * 15, 82, 13)
                    text(if (valid and (1u shl index) != 0u) (if (index == 0) 0u else registers[index]).hex() else "????????", 92, 26 + index * 15, Color.Blue, 10)
                }
                box(220, 10, 180, 30, Color.Yellow)
                text("PC ${pc.hex()}", 228, 17, Color.Blue)
                box(220, 50, 415, 455, Color.Yellow)
                box(220, 50, 415, 15, Color.Blue)
                text("Execution trace", 330, 50, Color.Yellow)
                text("PC        Binary     Instruction", 225, 68, fontSize = 11)
                for ((index, entry) in trace.take(21).withIndex()) {
                    box(225, 83 + index * 20, 405, 18)
                    text("${entry.pc.hex()} ${entry.instruction.hex()} ${entry.assembly}", 228, 84 + index * 20,
                        if (entry.diagnostic == null) ink else Color.Red, 11)
                }
            } else {
                box(50, 10, 590, 590, Color.LightGray)
                text(if (component.kind == SocComponents.RV32) "RV32IM" else "Nios II", 280, 290)
            }
            box(50, 600, 280, 20, Color.LightGray)
            text("Bus ${attributes[SocComponents.BusSelection]}", 54, 602)
            if (!printerView) {
                box(340, 600, 270, 20, if (stopped) Color(0xFFFFA500) else Color.Green)
                text(if (stopped) "Stopped" else "Running", 430, 602)
            }
        }
        SocComponents.VGA -> {
            val vga = device as? SocVgaDevice
            val dims = attributes[SocComponents.DisplayMode].split('x').map(String::toInt)
            val width = vga?.width ?: dims[0]
            val height = vga?.height ?: dims[1]
            drawRect(Color.Black, origin + Offset(5f, 20f), Size(width.toFloat(), height.toFloat()))
            if (vga != null) for ((index, color) in vga.argbPixels) {
                drawRect(Color(color.toLong()), origin + Offset((5 + index % width).toFloat(), (20 + index / width).toFloat()), Size(1f, 1f))
            }
            text("VGA ${width}x$height", 8, 2)
        }
        SocComponents.MEMORY -> {
            text("SoC memory", 8, 4)
            text("${attributes[SocComponents.StartAddress].hex()} - ${(attributes[SocComponents.StartAddress] + (1u shl attributes[SocComponents.MemorySize]) - 1u).hex()}", 8, 22)
            text("Bus ${attributes[SocComponents.BusSelection]}", 8, 42)
        }
        SocComponents.PIO -> {
            text("PIO ${attributes[SocComponents.Width]} bit", 8, 22)
            text("${attributes[SocComponents.StartAddress].hex()}  ${attributes[SocComponents.PioDirection]}", 8, 44)
            (device as? SocPioDevice)?.let { text("IN ${it.snapshot.inputs.hex()} OUT ${it.outputBits.hex()}", 8, 64); text("IRQ ${if (it.irq) 1 else 0}", 8, 84) }
        }
        SocComponents.DMA -> {
            text("DMA ${attributes[SocComponents.StartAddress].hex()}", 8, 20)
            (device as? SocDmaDevice)?.let { text("${it.bytesDone} bytes  ${if (it.isBusy) "Busy" else if (it.isDone) "Done" else "Idle"}", 8, 45) }
            text("Bus ${attributes[SocComponents.BusSelection]}", 8, 70)
        }
        SocComponents.UART -> {
            text("JTAG UART ${attributes[SocComponents.StartAddress].hex()}", 8, 15)
            (device as? SocJtagUartDevice)?.let { text("RX ${it.readQueue.size}  TX ${it.writeQueue.size}  IRQ ${if (it.irq) 1 else 0}", 8, 35) }
        }
    }
    return true
}
