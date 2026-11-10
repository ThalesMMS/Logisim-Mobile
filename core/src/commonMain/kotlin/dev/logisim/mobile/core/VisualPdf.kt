package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlin.math.min

data class VisualPdfPage(val widthPoints: Double = 595.0, val heightPoints: Double = 842.0, val marginPoints: Double = 24.0) {
    init {
        require(widthPoints.isFinite() && heightPoints.isFinite() && marginPoints.isFinite())
        require(widthPoints in 1.0..14_400.0 && heightPoints in 1.0..14_400.0)
        require(marginPoints >= 0 && marginPoints * 2 < min(widthPoints, heightPoints))
    }
}

/** A raster's rows are stored in display order, from top to bottom. */
data class VisualPdfRaster(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height == argb.size.toLong())
        require(argb.size <= 67_108_864) { "PDF raster exceeds the pixel limit." }
    }
}

/** The optional header raster is prepared at two pixels per point. */
data class VisualPdfSheet(
    val drawing: VisualPdfRaster,
    val header: VisualPdfRaster? = null,
    val drawingPixelsPerPoint: Double = 1.0,
) {
    init { require(drawingPixelsPerPoint.isFinite() && drawingPixelsPerPoint > 0.0) }
}

/** Circuits Evolution offers to print: those with a drawing, in project order. */
fun CircuitProject.printableCircuits(): List<CircuitDocument> =
    circuits.filter { it.components.isNotEmpty() || it.wires.isNotEmpty() }

/** Page header of a printed circuit, as in Evolution's print dialog. */
object PrintHeader {
    /** Evolution's dialog starts with this English template in every language. */
    const val DEFAULT = "%n (%p of %P)"

    /** `%n` is the circuit name, `%p` the page, `%P` the page count and `%%` a percent sign. */
    fun expand(template: String, name: String, page: Int, pages: Int): String = buildString {
        var index = 0
        while (index < template.length) {
            val char = template[index++]
            if (char != '%' || index == template.length) {
                append(char)
                continue
            }
            when (val token = template[index++]) {
                'n' -> append(name)
                'p' -> append(page)
                'P' -> append(pages)
                '%' -> append('%')
                else -> append('%').append(token)
            }
        }
    }
}

object VisualPdf {
    fun encode(width: Int, height: Int, argb: IntArray, page: VisualPdfPage = VisualPdfPage()): ByteArray =
        encodeDocument(1, page, rotateToFit = false, printable = false) {
            VisualPdfSheet(VisualPdfRaster(width, height, argb))
        }

    /** Prepare one sheet at a time so the caller need not retain the unencoded rasters. */
    fun encodePages(
        pageCount: Int,
        page: VisualPdfPage = VisualPdfPage(),
        rotateToFit: Boolean = false,
        sheet: (Int) -> VisualPdfSheet,
    ): ByteArray = encodeDocument(pageCount, page, rotateToFit, printable = true, sheet)

    private fun encodeDocument(
        pageCount: Int,
        page: VisualPdfPage,
        rotateToFit: Boolean,
        printable: Boolean,
        sheet: (Int) -> VisualPdfSheet,
    ): ByteArray {
        require(pageCount > 0)
        if (printable) requireLocalized(pageCount <= 512) { message("visual_export_pdf_job_limit") }
        val objects = mutableListOf(
            "<< /Type /Catalog /Pages 2 0 R >>".encodeToByteArray(),
            ByteArray(0),
        )
        val pageIds = mutableListOf<Int>()
        var rasterPixels = 0L

        fun addImage(image: VisualPdfRaster): Int {
            val (width, height, argb) = image
            rasterPixels += argb.size
            if (printable) requireLocalized(rasterPixels <= 15_000_000) { message("visual_export_pdf_job_limit") }
            val alpha = argb.any { it ushr 24 != 255 }
            val imageId = objects.size + 1
            val rgb = zlib(argb.size * 3) { index ->
                val channel = when (index % 3) { 0 -> 16; 1 -> 8; else -> 0 }
                (argb[index / 3] ushr channel) and 255
            }
            val properties = "/Type /XObject /Subtype /Image /Width $width /Height $height"
            objects += stream("$properties /ColorSpace /DeviceRGB /BitsPerComponent 8 /Interpolate false /Filter /FlateDecode" +
                if (alpha) " /SMask ${imageId + 1} 0 R" else "", rgb)
            if (alpha) objects += stream("$properties /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode",
                zlib(argb.size) { index -> argb[index] ushr 24 })
            return imageId
        }

        repeat(pageCount) { index ->
            val pageId = objects.size + 1
            pageIds += pageId
            objects += ByteArray(0)
            val current = sheet(index)
            val (drawing, headerImage) = current
            val drawingId = addImage(drawing)
            val headerId = headerImage?.let(::addImage)
            val resources = "/Circuit $drawingId 0 R" + (headerId?.let { " /Header $it 0 R" } ?: "")
            val contentsId = objects.size + 1
            val contents = pageContents(page, current, rotateToFit, printable)
            objects += stream("", contents.encodeToByteArray())
            objects[pageId - 1] = ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${number(page.widthPoints)} ${number(page.heightPoints)}]" +
                " /Resources << /XObject << $resources >> >> /Contents $contentsId 0 R >>").encodeToByteArray()
        }
        val kids = pageIds.joinToString(" ") { "$it 0 R" }
        objects[1] = "<< /Type /Pages /Kids [$kids] /Count $pageCount >>".encodeToByteArray()
        val header = byteArrayOf(37, 80, 68, 70, 45, 49, 46, 52, 10, 37, -30, -29, -49, -45, 10)
        val starts = IntArray(objects.size)
        val pieces = mutableListOf<ByteArray>(header)
        var offset = header.size.toLong()
        objects.forEachIndexed { index, body ->
            require(offset <= Int.MAX_VALUE)
            starts[index] = offset.toInt()
            val begin = "${index + 1} 0 obj\n".encodeToByteArray()
            val end = "\nendobj\n".encodeToByteArray()
            pieces += begin; pieces += body; pieces += end
            offset += begin.size + body.size + end.size
        }
        val xref = buildString {
            append("xref\n0 ").append(objects.size + 1).append("\n0000000000 65535 f \n")
            starts.forEach { append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
            append("trailer\n<< /Size ").append(objects.size + 1).append(" /Root 1 0 R >>\nstartxref\n").append(offset).append("\n%%EOF\n")
        }.encodeToByteArray()
        pieces += xref
        offset += xref.size
        require(offset <= Int.MAX_VALUE) { "PDF exceeds the encoder size limit." }
        val result = ByteArray(offset.toInt())
        var write = 0
        pieces.forEach { it.copyInto(result, write); write += it.size }
        return result
    }

    private data class SheetFit(val width: Double, val height: Double, val scale: Double, val headerScale: Double)

    private fun pageContents(
        page: VisualPdfPage,
        sheet: VisualPdfSheet,
        rotateToFit: Boolean,
        printable: Boolean,
    ): String {
        val (drawing, header, drawingPixelsPerPoint) = sheet
        val margin = page.marginPoints
        fun fit(width: Double, height: Double): SheetFit {
            val availableWidth = width - 2 * margin
            val availableHeight = height - 2 * margin
            val headerScale = header?.let { minOf(0.5, availableWidth / it.width, availableHeight / (4 * it.height)) } ?: 0.0
            // As in Evolution's print layout, the drawing starts right below the header line.
            val drawingHeight = availableHeight - (header?.height ?: 0) * headerScale
            return SheetFit(width, height, min(availableWidth / drawing.width, drawingHeight / drawing.height), headerScale)
        }

        val naturalScale = 1.0 / drawingPixelsPerPoint
        val upright = fit(page.widthPoints, page.heightPoints)
        val sideways = if (rotateToFit && upright.scale < naturalScale / 1.1) fit(page.heightPoints, page.widthPoints) else null
        val rotate = sideways != null && sideways.scale >= upright.scale * 1.1
        val fit = if (rotate) checkNotNull(sideways) else upright
        val scale = if (printable) min(naturalScale, fit.scale) else fit.scale
        val width = drawing.width * scale
        val height = drawing.height * scale
        val top = fit.height - margin
        val headerHeight = (header?.height ?: 0) * fit.headerScale
        val y = if (printable) top - headerHeight - height else (fit.height - height) / 2

        fun StringBuilder.image(name: String, w: Double, h: Double, bottom: Double) {
            append("q\n${number(w)} 0 0 ${number(h)} ${number((fit.width - w) / 2)} ${number(bottom)} cm\n/$name Do\nQ\n")
        }

        return buildString {
            if (rotate) {
                if (page.heightPoints > page.widthPoints) append("q\n0 1 -1 0 ${number(page.widthPoints)} 0 cm\n")
                else append("q\n0 -1 1 0 0 ${number(page.heightPoints)} cm\n")
            }
            if (header != null) image("Header", header.width * fit.headerScale, headerHeight, top - headerHeight)
            image("Circuit", width, height, y)
            if (rotate) append("Q\n")
        }
    }

    private fun stream(dictionary: String, bytes: ByteArray): ByteArray {
        val prefix = "<< $dictionary /Length ${bytes.size} >>\nstream\n".encodeToByteArray()
        val suffix = "\nendstream".encodeToByteArray()
        return ByteArray(prefix.size + bytes.size + suffix.size).also { prefix.copyInto(it); bytes.copyInto(it, prefix.size); suffix.copyInto(it, prefix.size + bytes.size) }
    }

    private fun zlib(length: Int, at: (Int) -> Int): ByteArray {
        val blocks = (length.toLong() + 65534) / 65535
        val size = 2L + length + blocks * 5 + 4
        require(length > 0 && size <= Int.MAX_VALUE)
        val bytes = ByteArray(size.toInt())
        var write = 0
        fun byte(value: Int) { bytes[write++] = value.toByte() }
        byte(0x78); byte(0x01)
        var offset = 0
        var a = 1L
        var b = 0L
        var batch = 0
        while (offset < length) {
            val count = minOf(65535, length - offset)
            byte(if (offset + count == length) 1 else 0)
            byte(count); byte(count ushr 8); byte(count.inv()); byte(count.inv() ushr 8)
            repeat(count) {
                val value = at(offset++) and 255
                byte(value); a += value; b += a
                if (++batch == 5552) { a %= 65521; b %= 65521; batch = 0 }
            }
        }
        val adler = (((b % 65521) shl 16) or (a % 65521)).toInt()
        byte(adler ushr 24); byte(adler ushr 16); byte(adler ushr 8); byte(adler)
        check(write == bytes.size)
        return bytes
    }
    private fun number(value: Double): String {
        if (value.toLong().toDouble() == value) return value.toLong().toString()
        val raw = value.toString()
        val exponentAt = raw.indexOfAny(charArrayOf('E', 'e'))
        if (exponentAt < 0) return raw
        val negative = raw.startsWith('-')
        val mantissa = raw.substring(if (negative) 1 else 0, exponentAt)
        val exponent = raw.substring(exponentAt + 1).toInt()
        val digits = mantissa.replace(".", "")
        val point = mantissa.substringBefore('.').length + exponent
        val expanded = when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
        return if (negative) "-$expanded" else expanded
    }
}
