package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

import kotlin.io.encoding.Base64

data class AnnotationFont(val family: String = "SansSerif", val size: Int = 12, val bold: Boolean = false, val italic: Boolean = false) {
    init { require(family.isNotBlank() && size in 1..256) }
    val serialized: String get() = "$family ${when { bold && italic -> "bolditalic"; bold -> "bold"; italic -> "italic"; else -> "plain" }} $size"

    companion object {
        fun parse(raw: String): AnnotationFont? {
            val match = Regex("(.+)\\s+(plain|bold|italic|bolditalic)\\s+(\\d+)", RegexOption.IGNORE_CASE).matchEntire(raw.trim()) ?: return null
            val size = match.groupValues[3].toIntOrNull()?.takeIf { it in 1..256 } ?: return null
            val style = match.groupValues[2].lowercase()
            return AnnotationFont(match.groupValues[1], size, "bold" in style, "italic" in style)
        }
    }
}

/** The document stores image bytes, never a temporary file path or a remote URL. */
class EmbeddedImage private constructor(val mimeType: String, val width: Int, val height: Int, bytes: ByteArray) {
    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()
    val dataUri: String get() = "data:$mimeType;base64,${Base64.Default.encode(content)}"

    companion object {
        const val MAX_BYTES = 1_000_000
        const val MAX_DIMENSION = 4096

        fun fromDataUri(value: String): EmbeddedImage {
            val match = Regex("data:(image/(?:png|jpeg|gif));base64,([A-Za-z0-9+/=\\s]+)").matchEntire(value)
                ?: throw LocalizedArgumentException(message("annotation_image_embedded"))
            val encoded = match.groupValues[2].filterNot(Char::isWhitespace)
            requireLocalized(encoded.length <= ((MAX_BYTES + 2) / 3) * 4) { message("annotation_image_limit") }
            return fromBytes(Base64.Default.decode(encoded), match.groupValues[1])
        }

        fun fromBytes(bytes: ByteArray, mimeType: String): EmbeddedImage {
            requireLocalized(bytes.size in 10..MAX_BYTES) { message("annotation_image_incomplete") }
            fun u(index: Int) = bytes[index].toInt() and 255
            val dimensions = when (mimeType) {
                "image/png" -> {
                    requireLocalized(bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) &&
                        bytes.copyOfRange(12, 16).decodeToString() == "IHDR") { message("annotation_png_header") }
                    fun intAt(offset: Int): Int = (u(offset) shl 24) or (u(offset + 1) shl 16) or (u(offset + 2) shl 8) or u(offset + 3)
                    intAt(16) to intAt(20)
                }
                "image/gif" -> {
                    requireLocalized(bytes.copyOfRange(0, 6).decodeToString() in setOf("GIF87a", "GIF89a")) { message("annotation_gif_header") }
                    (u(6) or (u(7) shl 8)) to (u(8) or (u(9) shl 8))
                }
                "image/jpeg" -> {
                    requireLocalized(u(0) == 255 && u(1) == 216) { message("annotation_jpeg_header") }
                    var offset = 2
                    var result: Pair<Int, Int>? = null
                    while (offset < bytes.size && result == null) {
                        requireLocalized(u(offset++) == 255) { message("annotation_jpeg_marker") }
                        while (offset < bytes.size && u(offset) == 255) offset++
                        requireLocalized(offset < bytes.size) { message("annotation_jpeg_incomplete") }
                        val marker = u(offset++)
                        if (marker in 0xd0..0xd9 || marker == 0x01) continue
                        requireLocalized(offset + 2 <= bytes.size) { message("annotation_jpeg_incomplete") }
                        val length = (u(offset) shl 8) or u(offset + 1)
                        requireLocalized(length >= 2 && offset + length <= bytes.size) { message("annotation_jpeg_segment") }
                        if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                            requireLocalized(length >= 8) { message("annotation_jpeg_dimensions") }
                            result = ((u(offset + 5) shl 8) or u(offset + 6)) to ((u(offset + 3) shl 8) or u(offset + 4))
                        } else {
                            requireLocalized(marker != 0xda && marker != 0xd9) { message("annotation_jpeg_dimensions") }
                            offset += length
                        }
                    }
                    result ?: throw LocalizedArgumentException(message("annotation_jpeg_dimensions"))
                }
                else -> throw LocalizedArgumentException(message("annotation_image_format", "mime" to mimeType))
            }
            requireLocalized(dimensions.first in 1..MAX_DIMENSION && dimensions.second in 1..MAX_DIMENSION) { message("annotation_image_dimensions") }
            return EmbeddedImage(mimeType, dimensions.first, dimensions.second, bytes)
        }
    }
}

object AnnotationLibrary {
    val Image = ComponentKind("IMAGE")
    val Text = ComponentKind.TEXT
    private val textKey = AttributeKey.text("text")
    private val fontKey = AttributeKey("font", AnnotationFont(), AnnotationFont::parse, AnnotationFont::serialized)
    private val colorKey = AttributeKey.text("color", "#000000") { Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(it) }
    private val horizontalKey = AttributeKey.choice("halign", "center", setOf("left", "center", "right"))
    private val verticalKey = AttributeKey.choice("valign", "base", setOf("top", "center", "base", "bottom"))
    private val imageKey = AttributeKey("image", "", { raw ->
        if (raw.isNotEmpty()) EmbeddedImage.fromDataUri(raw)
        raw
    }, { it })
    private val widthKey = AttributeKey.int("width", 64, 1..4096).labeled("attribute_label_pixel_width")
    private val heightKey = AttributeKey.int("height", 64, 1..4096)
    private val scaleKey = AttributeKey.choice("scale", "fit", setOf("fit", "stretch", "cover"))

    fun definitions(): List<ComponentDefinition> = listOf(
        ComponentDefinition(Text, "Text", "#Base", schema = AttributeSchema(listOf(textKey, fontKey, colorKey, horizontalKey, verticalKey)),
            layout = { component, _ -> ComponentLayout(emptyList(), bounds(component)) }, supportsRotation = false, isCombinational = true),
        ComponentDefinition(Image, "Image", "#Base", schema = AttributeSchema(listOf(imageKey, widthKey, heightKey, scaleKey,
            AttributeKey.text("license", "Unspecified"), AttributeKey.text("attribution"))),
            layout = { component, _ -> ComponentLayout(emptyList(), bounds(component)) }, supportsRotation = false, isCombinational = true),
    )

    /**
     * The font a Text without a `font` attribute takes: Evolution's StdAttr.DEFAULT_LABEL_FONT (SansSerif bold 16),
     * or SansSerif plain 12 in original Logisim 2.7.1 (and Holy Cross) projects.
     */
    fun defaultTextFont(originalLogisim: Boolean): String = if (originalLogisim) "SansSerif plain 12" else "SansSerif bold 16"

    fun isAnnotation(component: CircuitComponent): Boolean = component.kind == Text || component.kind == Image

    fun bounds(component: CircuitComponent): ComponentBounds {
        val position = component.position
        if (component.kind == Image) return ComponentBounds(position.x, position.y,
            position.x + component.attributeInt("width", 64), position.y + component.attributeInt("height", 64))
        require(component.kind == Text)
        val text = component.attributes["text"] ?: component.label
        if (text.isEmpty()) return ComponentBounds(position.x, position.y, position.x, position.y)
        val font = AnnotationFont.parse(component.attributes["font"] ?: "SansSerif plain 12") ?: AnnotationFont()
        val lines = text.split('\n').let { if (text.endsWith('\n')) it.dropLast(1) else it }
        val width = font.size * (lines.maxOfOrNull { line -> line.sumOf { if (it == '\t') 4 else 1 } } ?: 0) * 2 / 3
        val height = font.size * lines.size
        val x = when (component.attributes["halign"] ?: "center") { "left" -> 0; "right" -> -width; else -> -width / 2 }
        val y = when (component.attributes["valign"] ?: "base") { "top" -> 0; "center" -> -height / 2; else -> -height }
        return ComponentBounds(position.x + x, position.y + y, position.x + x + width, position.y + y + height)
    }
}
