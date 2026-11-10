package dev.logisim.mobile

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualExport
import dev.logisim.mobile.core.VisualExportOptions
import dev.logisim.mobile.core.VisualPaint
import dev.logisim.mobile.core.VisualPathCommand
import dev.logisim.mobile.core.VisualPng
import dev.logisim.mobile.core.VisualPdf
import dev.logisim.mobile.core.VisualPdfPage
import dev.logisim.mobile.core.VisualItem
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.VisualScene
import dev.logisim.mobile.core.VisualTextAlign

fun interface VisualImageDecoder {
    fun decode(image: VisualPrimitive.Image): ImageBitmap
}

/** Used by the interactive canvas and offscreen export, with coordinates in circuit units. */
fun DrawScope.drawVisualScene(scene: VisualScene, textMeasurer: TextMeasurer, imageDecoder: VisualImageDecoder? = null) {
    scene.items.forEach { item -> item.primitives.forEach { drawVisualPrimitive(it, textMeasurer, imageDecoder) } }
}

/** Draw a measured label with the same font and anchor rules as scene and export text. */
fun DrawScope.drawVisualText(text: VisualPrimitive.Text, textMeasurer: TextMeasurer) {
    drawVisualPrimitive(text, textMeasurer, null)
}

fun renderVisualBitmap(
    scene: VisualScene,
    textMeasurer: TextMeasurer,
    options: VisualExportOptions = VisualExportOptions(),
    imageDecoder: VisualImageDecoder? = null,
): ImageBitmap {
    val frame = VisualExport.frame(scene, options, raster = true)
    return renderDrawingBitmap(frame.circuitBounds, options.copy(selection = null)) {
        drawVisualScene(VisualScene(frame.items), textMeasurer, imageDecoder)
    }
}

/** The caller supplies the same world-coordinate drawing lambda as the interactive canvas. */
fun renderDrawingBitmap(
    bounds: VisualBounds,
    options: VisualExportOptions = VisualExportOptions(),
    density: Density = Density(1f, 1f),
    draw: DrawScope.() -> Unit,
): ImageBitmap {
    require(options.selection == null) { "Apply selection to drawing bounds and the drawing callback before exporting." }
    val frame = VisualExport.frame(VisualScene(listOf(VisualItem("drawing", bounds, emptyList()))), options, raster = true)
    val bitmap = ImageBitmap(frame.pixelWidth, frame.pixelHeight, ImageBitmapConfig.Argb8888)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(frame.pixelWidth.toFloat(), frame.pixelHeight.toFloat())) {
        drawRect(Color((frame.background ?: 0u).toInt()), blendMode = BlendMode.Src)
        withTransform({
            scale(frame.scale.toFloat(), frame.scale.toFloat(), Offset.Zero)
            translate(-frame.bounds.left.toFloat(), -frame.bounds.top.toFloat())
        }) {
            val clip = frame.clip
            if (clip == null) draw()
            else clipRect(clip.left.toFloat(), clip.top.toFloat(), clip.right.toFloat(), clip.bottom.toFloat()) { draw() }
        }
    }
    return bitmap
}

fun renderVisualPng(
    scene: VisualScene,
    textMeasurer: TextMeasurer,
    options: VisualExportOptions = VisualExportOptions(),
    imageDecoder: VisualImageDecoder? = null,
): ByteArray = renderVisualBitmap(scene, textMeasurer, options, imageDecoder).pngBytes()

fun renderDrawingPng(bounds: VisualBounds, options: VisualExportOptions = VisualExportOptions(), density: Density = Density(1f, 1f), draw: DrawScope.() -> Unit): ByteArray =
    renderDrawingBitmap(bounds, options, density, draw).pngBytes()

fun renderDrawingPdf(bounds: VisualBounds, options: VisualExportOptions = VisualExportOptions(), page: VisualPdfPage = VisualPdfPage(), density: Density = Density(1f, 1f), draw: DrawScope.() -> Unit): ByteArray {
    val bitmap = renderDrawingBitmap(bounds, options, density, draw)
    return VisualPdf.encode(bitmap.width, bitmap.height, bitmap.argbPixels(), page)
}

private fun ImageBitmap.argbPixels() = IntArray(width * height).also { readPixels(it) }
private fun ImageBitmap.pngBytes() = VisualPng.encode(width, height, argbPixels())

/** Actual measured extents, including line layout, for a scene item's export bounds. */
fun TextMeasurer.visualTextBounds(text: VisualPrimitive.Text): VisualBounds {
    val measured = measureVisual(text)
    return measured.visualBounds(text)
}

private fun TextLayoutResult.visualBounds(text: VisualPrimitive.Text): VisualBounds =
    text.layoutBounds(size.width.toDouble(), size.height.toDouble(), firstBaseline.toDouble())

private fun TextMeasurer.measureVisual(text: VisualPrimitive.Text): TextLayoutResult {
    val family = when (text.font.resolvedFamily) {
        "monospace" -> FontFamily.Monospace
        "serif" -> FontFamily.Serif
        "cursive" -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }
    return measure(text.displayText, TextStyle(
        color = Color(text.color.toInt()),
        fontSize = text.font.size.toFloat().sp,
        fontWeight = if (text.font.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (text.font.italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = family,
        lineHeight = (text.font.size * text.font.lineHeight).toFloat().sp,
        textAlign = when (text.align) { VisualTextAlign.START -> TextAlign.Left; VisualTextAlign.CENTER -> TextAlign.Center; VisualTextAlign.END -> TextAlign.Right },
    ), softWrap = false, density = Density(1f, 1f), layoutDirection = LayoutDirection.Ltr)
}

private fun DrawScope.drawVisualPrimitive(value: VisualPrimitive, measurer: TextMeasurer, imageDecoder: VisualImageDecoder?) {
    when (value) {
        is VisualPrimitive.Path -> {
            val path = Path()
            value.commands.forEach { command ->
                when (command) {
                    is VisualPathCommand.MoveTo -> path.moveTo(command.point.x.toFloat(), command.point.y.toFloat())
                    is VisualPathCommand.LineTo -> path.lineTo(command.point.x.toFloat(), command.point.y.toFloat())
                    is VisualPathCommand.QuadTo -> path.quadraticTo(command.control.x.toFloat(), command.control.y.toFloat(), command.end.x.toFloat(), command.end.y.toFloat())
                    is VisualPathCommand.CubicTo -> path.cubicTo(command.firstControl.x.toFloat(), command.firstControl.y.toFloat(), command.secondControl.x.toFloat(), command.secondControl.y.toFloat(), command.end.x.toFloat(), command.end.y.toFloat())
                    VisualPathCommand.Close -> path.close()
                }
            }
            value.paint.fill?.let { drawPath(path, Color(it.toInt())) }
            value.paint.stroke?.takeIf { value.paint.strokeWidth > 0 }?.let { withTikzDash(value.paint.dash) { drawPath(path, Color(it.toInt()), style = value.paint.stroke()) } }
        }
        is VisualPrimitive.Rectangle -> {
            val topLeft = Offset(value.bounds.left.toFloat(), value.bounds.top.toFloat())
            val size = Size(value.bounds.width.toFloat(), value.bounds.height.toFloat())
            val radius = minOf(value.radius, value.bounds.width / 2, value.bounds.height / 2).toFloat()
            value.paint.fill?.let { drawRoundRect(Color(it.toInt()), topLeft, size, CornerRadius(radius, radius)) }
            value.paint.stroke?.takeIf { value.paint.strokeWidth > 0 }?.let {
                withTikzDash(value.paint.dash) {
                    drawRoundRect(Color(it.toInt()), topLeft, size, CornerRadius(radius, radius), style = value.paint.stroke())
                }
            }
        }
        is VisualPrimitive.Ellipse -> {
            val topLeft = Offset(value.bounds.left.toFloat(), value.bounds.top.toFloat())
            val size = Size(value.bounds.width.toFloat(), value.bounds.height.toFloat())
            value.paint.fill?.let { drawOval(Color(it.toInt()), topLeft, size) }
            value.paint.stroke?.takeIf { value.paint.strokeWidth > 0 }?.let { withTikzDash(value.paint.dash) { drawOval(Color(it.toInt()), topLeft, size, style = value.paint.stroke()) } }
        }
        is VisualPrimitive.Text -> {
            val measured = measurer.measureVisual(value)
            val bounds = measured.visualBounds(value)
            drawCircuitText(measured, topLeft = Offset(bounds.left.toFloat(), bounds.top.toFloat()))
        }
        is VisualPrimitive.Image -> {
            if (value.bounds.width == 0.0 || value.bounds.height == 0.0) return
            val decoder = requireNotNull(imageDecoder) { "An embedded image decoder is required." }
            val bitmap = try {
                decoder.decode(value)
            } catch (_: Exception) {
                drawRect(Color.LightGray, Offset(value.bounds.left.toFloat(), value.bounds.top.toFloat()),
                    Size(value.bounds.width.toFloat(), value.bounds.height.toFloat()))
                return
            }
            val sx = value.bounds.width.toFloat() / bitmap.width
            val sy = value.bounds.height.toFloat() / bitmap.height
            val factor = if (value.scaleMode == "cover") maxOf(sx, sy) else minOf(sx, sy)
            val scaleX = if (value.scaleMode == "stretch") sx else factor
            val scaleY = if (value.scaleMode == "stretch") sy else factor
            val left = value.bounds.left.toFloat() + (value.bounds.width.toFloat() - bitmap.width * scaleX) / 2
            val top = value.bounds.top.toFloat() + (value.bounds.height.toFloat() - bitmap.height * scaleY) / 2
            val paint: DrawScope.() -> Unit = {
                withTransform({ translate(left, top); scale(scaleX, scaleY, Offset.Zero) }) { drawImage(bitmap) }
            }
            if (value.scaleMode == "cover") clipRect(value.bounds.left.toFloat(), value.bounds.top.toFloat(), value.bounds.right.toFloat(), value.bounds.bottom.toFloat()) { paint() }
            else paint()
        }
        is VisualPrimitive.Group -> withTransform({
            translate(value.transform.translateX.toFloat(), value.transform.translateY.toFloat())
            rotate(value.transform.rotationDegrees.toFloat(), Offset.Zero)
            scale(value.transform.scaleX.toFloat(), value.transform.scaleY.toFloat(), Offset.Zero)
        }) { value.children.forEach { drawVisualPrimitive(it, measurer, imageDecoder) } }
    }
}

private fun VisualPaint.stroke() = Stroke(strokeWidth.toFloat(), cap = StrokeCap.Round, join = StrokeJoin.Round,
    pathEffect = if (dash.isEmpty()) null else PathEffect.dashPathEffect(dash.map { it.toFloat() }.toFloatArray()))
