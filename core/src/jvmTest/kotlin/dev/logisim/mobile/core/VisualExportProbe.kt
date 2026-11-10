package dev.logisim.mobile.core

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/** Standalone interoperability check; the PDF is rendered by Poppler, not by this encoder. */
object VisualExportProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2) { "Usage: VisualExportProbe OUTPUT_DIRECTORY [PDFTOPPM]" }
        val directory = File(args[0]).apply { mkdirs() }
        val renderer = args.getOrNull(1) ?: "pdftoppm"
        val pixels = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xff123456.toInt(), 0xffffffff.toInt(), 0xff000000.toInt())
        File(directory, "reference.png").writeBytes(VisualPng.encode(3, 2, pixels))
        val pdf = File(directory, "reference.pdf").apply { writeBytes(VisualPdf.encode(3, 2, pixels, VisualPdfPage(60.0, 40.0, 0.0))) }
        val destination = File(directory, "pdf-rendered")
        val process = ProcessBuilder(renderer, "-r", "72", "-png", "-singlefile", pdf.absolutePath, destination.absolutePath).inheritIO().start()
        check(process.waitFor() == 0) { "PDF renderer failed." }
        val decoded = ImageIO.read(File(directory, "pdf-rendered.png"))
        check(decoded.width == 60 && decoded.height == 40) { "Unexpected PDF dimensions ${decoded.width}x${decoded.height}." }
        pixels.forEachIndexed { index, expected ->
            check(decoded.getRGB((index % 3) * 20 + 10, (index / 3) * 20 + 10) == expected) { "PDF pixel $index differs: ${decoded.getRGB((index % 3) * 20 + 10, (index / 3) * 20 + 10).toUInt().toString(16)}." }
        }
        val alphaPixels = intArrayOf(0x00ff0000, 0x80ff0000.toInt(), 0xff0000ff.toInt(), 0x7f00ff00)
        val alphaPdf = File(directory, "alpha.pdf").apply { writeBytes(VisualPdf.encode(2, 2, alphaPixels, VisualPdfPage(40.0, 40.0, 0.0))) }
        val alphaDestination = File(directory, "alpha-rendered")
        check(ProcessBuilder(renderer, "-r", "72", "-png", "-singlefile", alphaPdf.absolutePath, alphaDestination.absolutePath).inheritIO().start().waitFor() == 0)
        val alphaImage = ImageIO.read(File(directory, "alpha-rendered.png"))
        alphaPixels.forEachIndexed { index, expected ->
            val actual = alphaImage.getRGB((index % 2) * 20 + 10, (index / 2) * 20 + 10)
            val alpha = expected ushr 24
            for (shift in listOf(16, 8, 0)) {
                val channel = expected ushr shift and 255
                val composited = (channel * alpha + 255 * (255 - alpha) + 127) / 255
                check(abs((actual ushr shift and 255) - composited) <= 1) { "PDF alpha channel mismatch at $index." }
            }
        }
        val image = VisualPrimitive.Image(VisualBounds(0.0, 0.0, 3.0, 2.0), "image/png", File(directory, "reference.png").readBytes())
        File(directory, "reference.svg").writeBytes(VisualExport.svg(VisualScene(listOf(VisualItem("image", image.bounds, listOf(image)))), VisualExportOptions(margin = 0.0)))
        println("PASS: PDF rendered at exact dimensions; 6 opaque samples and 4 alpha-composited samples match.")
    }
}
