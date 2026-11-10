package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

object AnnotationVisuals {
    fun item(component: CircuitComponent): VisualItem {
        require(AnnotationLibrary.isAnnotation(component))
        val box = AnnotationLibrary.bounds(component)
        val bounds = VisualBounds(box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble())
        val primitive = if (component.kind == AnnotationLibrary.Image) {
            val source = component.attributes["image"].orEmpty()
            if (source.isEmpty()) VisualPrimitive.Rectangle(bounds, VisualPaint(null, 0xff808080u))
            else {
                val image = EmbeddedImage.fromDataUri(source)
                VisualPrimitive.Image(bounds, image.mimeType, image.bytes, component.attributes["scale"] ?: "fit")
            }
        } else {
            val font = AnnotationFont.parse(component.attributes["font"] ?: "SansSerif plain 12") ?: AnnotationFont()
            VisualPrimitive.Text(component.attributes["text"] ?: component.label, VisualPoint(component.position.x.toDouble(), component.position.y.toDouble()),
                VisualFont(font.family, font.size.toDouble(), font.bold, font.italic), parseColor(component.attributes["color"] ?: "#000000") ?: 0xff000000u,
                when (component.attributes["halign"] ?: "center") { "left" -> VisualTextAlign.START; "right" -> VisualTextAlign.END; else -> VisualTextAlign.CENTER },
                verticalAlign = when (component.attributes["valign"] ?: "base") { "top" -> VisualTextVerticalAlign.TOP; "center" -> VisualTextVerticalAlign.CENTER; "bottom" -> VisualTextVerticalAlign.BOTTOM; else -> VisualTextVerticalAlign.BASELINE })
        }
        return VisualItem(component.id, bounds, listOf(primitive))
    }

    fun parseColor(value: String): UInt? = when (value.lowercase()) {
        "none" -> null
        "black" -> 0xff000000u
        "white" -> 0xffffffffu
        "gray" -> 0xff808080u
        "red" -> 0xffff0000u
        "green" -> 0xff008000u
        "blue" -> 0xff0000ffu
        else -> {
            requireLocalized(value.startsWith('#')) { message("annotation_invalid_color") }
            val number = value.drop(1).toULongOrNull(16) ?: throw LocalizedArgumentException(message("annotation_invalid_color"))
            when (value.length) { 7 -> (0xff000000uL or number).toUInt(); 9 -> ((number and 255uL) shl 24 or (number shr 8)).toUInt(); else -> throw LocalizedArgumentException(message("annotation_invalid_color")) }
        }
    }

}
