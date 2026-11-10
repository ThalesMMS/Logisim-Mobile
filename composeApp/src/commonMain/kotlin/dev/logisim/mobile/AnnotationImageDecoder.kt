package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.SymbolAppearance
import dev.logisim.mobile.core.VisualItem
import dev.logisim.mobile.core.EmbeddedImage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message

internal expect fun decodeEmbeddedAnnotationImage(bytes: ByteArray): ImageBitmap

/** A compressed PNG of straight-alpha pixels, as the Image menu stores its results (#359). */
internal expect fun encodeAnnotationPng(image: dev.logisim.mobile.core.ArgbImage): ByteArray

internal fun validateAnnotationImage(image: EmbeddedImage): EmbeddedImage {
    try { decodeEmbeddedAnnotationImage(image.bytes) }
    catch (_: Exception) { throw LocalizedArgumentException(message("annotation_image_decode")) }
    return image
}

fun validateProjectAnnotationImages(project: dev.logisim.mobile.core.CircuitProject) {
    project.circuits.flatMap { it.components }.filter { it.kind == dev.logisim.mobile.core.AnnotationLibrary.Image && !it.attributes["image"].isNullOrEmpty() }
        .forEach { validateAnnotationImage(EmbeddedImage.fromDataUri(it.attributes["image"].orEmpty())) }
}

/** One composition owns this bounded cache; callers can create another for background export. */
class AnnotationImageCache(private val capacity: Int = 16) : VisualImageDecoder {
    private val cache = linkedMapOf<VisualPrimitive.Image, ImageBitmap>()
    private data class DrawingEntry(val component: CircuitComponent, val item: VisualItem)
    private val drawings = linkedMapOf<String, DrawingEntry>()
    private data class SymbolEntry(val document: CircuitDocument, val appearance: SymbolAppearance?)
    private val symbols = linkedMapOf<String, SymbolEntry>()
    init { require(capacity in 1..64) }

    override fun decode(image: VisualPrimitive.Image): ImageBitmap {
        cache.remove(image)?.let { cache[image] = it; return it }
        val decoded = decodeEmbeddedAnnotationImage(image.encodedBytes)
        cache[image] = decoded
        while (cache.size > 1 && (cache.size > capacity || cache.values.sumOf { it.width.toLong() * it.height } > 8_388_608L)) cache.remove(cache.keys.first())
        return decoded
    }

    internal fun symbolAppearance(document: CircuitDocument): SymbolAppearance? {
        val previous = symbols.remove(document.id)
        val entry = if (previous != null && previous.document === document) previous else SymbolEntry(document, SymbolAppearance.rendered(document))
        symbols[document.id] = entry
        if (symbols.size > capacity) symbols.remove(symbols.keys.first())
        return entry.appearance
    }

    internal fun drawingItem(component: CircuitComponent, create: () -> VisualItem): VisualItem {
        val previous = drawings.remove(component.id)
        if (previous != null && (previous.component === component || previous.component == component)) {
            drawings[component.id] = if (previous.component === component) previous else DrawingEntry(component, previous.item)
            return previous.item
        }
        val item = create()
        drawings[component.id] = DrawingEntry(component, item)
        while (drawings.size > capacity) drawings.remove(drawings.keys.first())
        return item
    }
}

@Composable
fun rememberAnnotationImageCache(): AnnotationImageCache = remember { AnnotationImageCache() }
