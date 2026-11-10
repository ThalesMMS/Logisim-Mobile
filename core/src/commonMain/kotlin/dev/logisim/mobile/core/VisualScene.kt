package dev.logisim.mobile.core

/** Vector geometry in circuit coordinates, independent of the viewport and platform. */
data class VisualPoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
}

data class VisualBounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() })
        require(right >= left && bottom >= top)
    }
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    fun union(other: VisualBounds) = VisualBounds(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
    fun expanded(amount: Double) = VisualBounds(left - amount, top - amount, right + amount, bottom + amount)
}

data class VisualPaint(
    val fill: UInt? = null,
    val stroke: UInt? = 0xff000000u,
    val strokeWidth: Double = 1.0,
    val dash: List<Double> = emptyList(),
) {
    init {
        require(strokeWidth.isFinite() && strokeWidth >= 0.0)
        require(dash.all { it.isFinite() && it > 0.0 })
    }
}

enum class VisualTextAlign { START, CENTER, END }
enum class VisualTextVerticalAlign { BASELINE, TOP, CENTER, BOTTOM }

data class VisualFont(
    val family: String = "sans-serif",
    val size: Double = 12.0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val lineHeight: Double = 1.2,
) {
    val resolvedFamily: String get() {
        val name = family.lowercase().replace(" ", "")
        return when {
            "mono" in name || name == "dialoginput" -> "monospace"
            "sans" in name || name == "dialog" -> "sans-serif"
            "serif" in name -> "serif"
            "cursive" in name || "script" in name -> "cursive"
            else -> "sans-serif"
        }
    }
    init {
        require(family.isNotBlank() && family.none { it.code < 32 })
        require(size.isFinite() && size > 0.0)
        require(lineHeight.isFinite() && lineHeight > 0.0)
    }
}

data class VisualTransform(
    val translateX: Double = 0.0,
    val translateY: Double = 0.0,
    val rotationDegrees: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
) {
    init { require(listOf(translateX, translateY, rotationDegrees, scaleX, scaleY).all { it.isFinite() }) }
}

sealed interface VisualPathCommand {
    data class MoveTo(val point: VisualPoint) : VisualPathCommand
    data class LineTo(val point: VisualPoint) : VisualPathCommand
    data class QuadTo(val control: VisualPoint, val end: VisualPoint) : VisualPathCommand
    data class CubicTo(val firstControl: VisualPoint, val secondControl: VisualPoint, val end: VisualPoint) : VisualPathCommand
    data object Close : VisualPathCommand
}

sealed interface VisualPrimitive {
    data class Path(val commands: List<VisualPathCommand>, val paint: VisualPaint = VisualPaint()) : VisualPrimitive {
        init { require(commands.isNotEmpty() && commands.first() is VisualPathCommand.MoveTo) }
    }
    data class Rectangle(val bounds: VisualBounds, val paint: VisualPaint = VisualPaint(), val radius: Double = 0.0) : VisualPrimitive {
        init { require(radius.isFinite() && radius >= 0.0) }
    }
    data class Ellipse(val bounds: VisualBounds, val paint: VisualPaint = VisualPaint()) : VisualPrimitive
    /** Position is the horizontal and vertical text anchor in circuit coordinates. */
    data class Text(
        val text: String,
        val position: VisualPoint,
        val font: VisualFont = VisualFont(),
        val color: UInt = 0xff000000u,
        val align: VisualTextAlign = VisualTextAlign.START,
        val verticalAlign: VisualTextVerticalAlign = VisualTextVerticalAlign.BASELINE,
    ) : VisualPrimitive {
        val displayText: String get() = text.replace("\r\n", "\n").replace('\r', '\n')
        init { require(text.none { it.code < 32 && it !in "\t\n\r" }) }

        /** Use measured layout dimensions, including all lines, for both drawing and clipping. */
        fun layoutBounds(width: Double, height: Double, firstBaseline: Double): VisualBounds {
            val left = position.x - when (align) {
                VisualTextAlign.START -> 0.0
                VisualTextAlign.CENTER -> width / 2.0
                VisualTextAlign.END -> width
            }
            val top = position.y - when (verticalAlign) {
                VisualTextVerticalAlign.BASELINE -> firstBaseline
                VisualTextVerticalAlign.TOP -> 0.0
                VisualTextVerticalAlign.CENTER -> height / 2.0
                VisualTextVerticalAlign.BOTTOM -> height
            }
            return VisualBounds(left, top, left + width, top + height)
        }
    }
    class Image(val bounds: VisualBounds, val mimeType: String, encodedBytes: ByteArray, val scaleMode: String = "stretch") : VisualPrimitive {
        private val content = encodedBytes.copyOf()
        private val cachedHash = 31 * (31 * bounds.hashCode() + mimeType.hashCode()) + content.contentHashCode() + scaleMode.hashCode()
        val encodedBytes: ByteArray get() = content.copyOf()
        init {
            require(mimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif"))
            require(scaleMode in setOf("stretch", "fit", "cover"))
            require(content.isNotEmpty() && content.size <= 32 * 1024 * 1024)
        }
        override fun equals(other: Any?) = this === other || other is Image && bounds == other.bounds && mimeType == other.mimeType && scaleMode == other.scaleMode && content.contentEquals(other.content)
        override fun hashCode() = cachedHash
    }
    data class Group(val transform: VisualTransform, val children: List<VisualPrimitive>) : VisualPrimitive
}

/** Bounds include stroke and measured text extents. IDs tie selections to document objects. */
data class VisualItem(val id: String, val bounds: VisualBounds, val primitives: List<VisualPrimitive>) {
    init { require(id.isNotBlank()) }
}

data class VisualScene(val items: List<VisualItem>) {
    init { require(items.map { it.id }.distinct().size == items.size) { "Duplicate visual item IDs." } }
}
