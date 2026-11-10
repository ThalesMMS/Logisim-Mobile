package dev.logisim.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.systemAppLanguage
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Prints an immutable private PDF, including the page ranges requested by the print framework. */
class AndroidPdfPrintAdapter(
    context: Context,
    private val file: File,
    private val documentName: String,
    private val onFinished: () -> Unit,
) : PrintDocumentAdapter() {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val finished = AtomicBoolean()
    @Volatile private var attributes: PrintAttributes? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        attributes = newAttributes
        submit(onRejected = { if (!finished.get()) callback.onLayoutCancelled() }) {
            try {
                checkCancelled(cancellationSignal)
                val pageCount = openRenderer().use { it.pageCount }
                check(pageCount > 0)
                checkCancelled(cancellationSignal)
                val info = PrintDocumentInfo.Builder(documentName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount).build()
                post { if (cancelled(cancellationSignal)) callback.onLayoutCancelled()
                    else callback.onLayoutFinished(info, oldAttributes != newAttributes) }
            } catch (_: OperationCanceledException) {
                post { callback.onLayoutCancelled() }
            } catch (_: Exception) {
                post { callback.onLayoutFailed(failureText()) }
            } catch (_: OutOfMemoryError) {
                post { callback.onLayoutFailed(failureText()) }
            }
        }
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        val requested = pages.copyOf()
        val printAttributes = attributes
        submit(onRejected = {
            runCatching { destination.close() }
            if (!finished.get()) callback.onWriteCancelled()
        }) {
            try {
                checkCancelled(cancellationSignal)
                checkNotNull(printAttributes)
                val written = openRenderer().use { renderer ->
                    val selected = (0 until renderer.pageCount).filter { index -> requested.any { index in it.start..it.end } }
                    check(selected.isNotEmpty())
                    val output = PrintedPdfDocument(application, printAttributes)
                    try {
                        for (index in selected) {
                            checkCancelled(cancellationSignal)
                            renderer.openPage(index).use { source ->
                                val page = output.startPage(index)
                                try {
                                    drawPage(source, page.canvas, output, printAttributes, cancellationSignal,
                                        MAX_BITMAP_PIXELS / selected.size)
                                } finally {
                                    output.finishPage(page)
                                }
                            }
                        }
                        checkCancelled(cancellationSignal)
                        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { stream ->
                            output.writeTo(CancelableOutputStream(stream) { checkCancelled(cancellationSignal) })
                            stream.flush()
                        }
                    } finally { output.close() }
                    pageRanges(selected)
                }
                checkCancelled(cancellationSignal)
                post { if (cancelled(cancellationSignal)) callback.onWriteCancelled()
                    else callback.onWriteFinished(written) }
            } catch (_: OperationCanceledException) {
                post { callback.onWriteCancelled() }
            } catch (_: Exception) {
                post { callback.onWriteFailed(failureText()) }
            } catch (_: OutOfMemoryError) {
                post { callback.onWriteFailed(failureText()) }
            } finally {
                runCatching { destination.close() }
            }
        }
    }

    override fun onFinish() {
        if (finished.compareAndSet(false, true)) {
            // Queued writes still run their cancellation/close path before the worker terminates.
            io.shutdown()
            if (Looper.myLooper() == Looper.getMainLooper()) onFinished() else main.post(onFinished)
        }
    }

    private fun drawPage(
        source: PdfRenderer.Page,
        canvas: android.graphics.Canvas,
        output: PrintedPdfDocument,
        printAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        pixelBudget: Double,
    ) {
        val content = output.pageContentRect
        require(content.width() > 0 && content.height() > 0 && source.width > 0 && source.height > 0)
        val scale = min(content.width().toDouble() / source.width, content.height().toDouble() / source.height)
        val width = source.width * scale
        val height = source.height * scale
        val resolution = printAttributes.resolution
        val dpi = maxOf(resolution?.horizontalDpi ?: 150, resolution?.verticalDpi ?: 150).coerceIn(72, 300)
        val desiredWidth = ceil(width * dpi / 72.0).coerceAtLeast(1.0)
        val desiredHeight = ceil(height * dpi / 72.0).coerceAtLeast(1.0)
        val reduction = min(1.0, sqrt(pixelBudget / (desiredWidth * desiredHeight)))
        val bitmapWidth = floor(desiredWidth * reduction).toInt().coerceAtLeast(1)
        val bitmapHeight = floor(desiredHeight * reduction).toInt().coerceAtLeast(1)
        require(bitmapWidth.toLong() * bitmapHeight <= MAX_BITMAP_PIXELS.toLong())
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        try {
            checkCancelled(cancellationSignal)
            bitmap.eraseColor(Color.WHITE)
            val matrix = Matrix().apply {
                setScale(bitmapWidth.toFloat() / source.width, bitmapHeight.toFloat() / source.height)
            }
            source.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            checkCancelled(cancellationSignal)
            if (printAttributes.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME) {
                toMonochrome(bitmap, cancellationSignal)
            }
            // PrintedPdfDocument positions this canvas at the content rectangle's origin.
            val left = (content.width() - width) / 2.0
            val top = (content.height() - height) / 2.0
            val target = RectF(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, null, target, paint)
        } finally {
            bitmap.recycle()
        }
    }

    private fun toMonochrome(bitmap: Bitmap, cancellationSignal: CancellationSignal?) {
        val pixels = IntArray(min(bitmap.width, 8192))
        for (y in 0 until bitmap.height) {
            checkCancelled(cancellationSignal)
            var x = 0
            while (x < bitmap.width) {
                val count = min(pixels.size, bitmap.width - x)
                bitmap.getPixels(pixels, 0, count, x, y, count, 1)
                for (index in 0 until count) {
                    val color = pixels[index]
                    val gray = (77 * Color.red(color) + 150 * Color.green(color) + 29 * Color.blue(color)) shr 8
                    pixels[index] = Color.argb(Color.alpha(color), gray, gray, gray)
                }
                bitmap.setPixels(pixels, 0, count, x, y, count, 1)
                x += count
            }
        }
    }

    private fun openRenderer(): PdfRenderer {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try { PdfRenderer(descriptor) }
        catch (error: Throwable) { descriptor.close(); throw error }
    }

    private fun cancelled(signal: CancellationSignal?): Boolean =
        finished.get() || signal?.isCanceled == true || Thread.currentThread().isInterrupted

    private fun checkCancelled(signal: CancellationSignal?) {
        if (cancelled(signal)) throw OperationCanceledException()
    }

    private fun post(action: () -> Unit) {
        main.post { if (!finished.get()) action() }
    }

    private fun submit(onRejected: () -> Unit, action: () -> Unit) {
        if (finished.get()) { onRejected(); return }
        try { io.execute(action) }
        catch (_: RejectedExecutionException) { onRejected() }
    }

    private fun failureText(): String = message("android_asset_print_failed").resolve(systemAppLanguage())

    private fun pageRanges(pages: List<Int>): Array<PageRange> {
        val ranges = mutableListOf<PageRange>()
        var start = pages.first()
        var end = start
        for (page in pages.drop(1)) {
            if (page == end + 1) end = page
            else { ranges += PageRange(start, end); start = page; end = page }
        }
        ranges += PageRange(start, end)
        return ranges.toTypedArray()
    }

    private class CancelableOutputStream(output: OutputStream, private val checkCancelled: () -> Unit) : FilterOutputStream(output) {
        override fun write(value: Int) { checkCancelled(); out.write(value) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkCancelled()
            out.write(buffer, offset, length)
        }
    }

    private companion object {
        const val MAX_BITMAP_PIXELS = 16_000_000.0
    }
}
