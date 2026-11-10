package dev.logisim.mobile.core

data class VideoColorRun(val x: Int, val y: Int, val length: Int, val argb: Int)

/** Immutable sparse rows keep a single pixel write from copying the whole display. */
class VideoFrame private constructor(
    val width: Int,
    val height: Int,
    private val rows: Map<Int, IntArray>,
) {
    private val orderedRowIndices by lazy { rows.keys.sorted() }

    constructor(width: Int, height: Int) : this(width, height, emptyMap()) {
        require(width in 2..4096 && height in 2..4096)
    }

    fun argbAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return rows[y]?.get(x) ?: BLACK
    }

    fun withPixel(x: Int, y: Int, color: Int): VideoFrame {
        require(x in 0 until width && y in 0 until height)
        if (argbAt(x, y) == color) return this
        val row = rows[y]?.copyOf() ?: IntArray(width) { BLACK }
        row[x] = color
        return VideoFrame(width, height, rows + (y to row))
    }

    fun resize(width: Int, height: Int): VideoFrame {
        require(width in 2..4096 && height in 2..4096)
        if (this.width == width && this.height == height) return this
        val retained = rows.filterKeys { it < height }.mapValues { (_, old) ->
            IntArray(width) { x -> if (x < old.size) old[x] else BLACK }
        }
        return VideoFrame(width, height, retained)
    }

    fun clear(): VideoFrame = if (rows.isEmpty()) this else VideoFrame(width, height)

    /** Visits nonblack horizontal runs in row/column order without expanding absent rows. */
    fun forEachColorRun(action: (VideoColorRun) -> Unit) {
        for (y in orderedRowIndices) {
            val row = rows.getValue(y)
            var x = 0
            while (x < row.size) {
                val start = x
                val color = row[x++]
                while (x < row.size && row[x] == color) x++
                if (color != BLACK) action(VideoColorRun(start, y, x - start, color))
            }
        }
    }

    fun copyArgbPixels(): IntArray = IntArray(width * height) { index -> argbAt(index % width, index / width) }

    companion object { const val BLACK: Int = -16777216 }
}

data class VideoState(
    val frame: VideoFrame,
    val lastClock: LogicValue? = null,
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val cursorColor: Int = 0,
    val cursorVisible: Boolean = true,
) : ComponentState

enum class VideoColorFormat(val fileValue: String, val bits: Int) {
    RGB888("888 RGB (24 bit)", 24), RGB555("555 RGB (15 bit)", 15), RGB565("565 RGB (16 bit)", 16),
    RGB444("444 RGB (12 bit)", 12), RGB111("8-Color RGB (3 bit)", 3), ATARI("Atari 2600 (7 bit)", 7),
    XTERM16("XTerm16 (4 bit)", 4), XTERM256("XTerm256 (8 bit)", 8), GRAY4("Grayscale (4 bit)", 4),
    VGA256("VGA256 (8 bit)", 8);

    fun argb(value: Int): Int {
        fun channel(value: Int, bits: Int): Int = (value * 255 + ((1 shl bits) - 1) / 2) / ((1 shl bits) - 1)
        fun rgb(r: Int, g: Int, b: Int) = VideoFrame.BLACK or (r shl 16) or (g shl 8) or b
        return when (this) {
            RGB888 -> VideoFrame.BLACK or (value and 0xffffff)
            RGB555 -> rgb(channel((value shr 10) and 31, 5), channel((value shr 5) and 31, 5), channel(value and 31, 5))
            RGB565 -> rgb(channel((value shr 11) and 31, 5), channel((value shr 5) and 63, 6), channel(value and 31, 5))
            RGB444 -> rgb(channel((value shr 8) and 15, 4), channel((value shr 4) and 15, 4), channel(value and 15, 4))
            RGB111 -> rgb(channel((value shr 2) and 1, 1), channel((value shr 1) and 1, 1), channel(value and 1, 1))
            GRAY4 -> (value and 15).let { rgb(it * 17, it * 17, it * 17) }
            // Java's IndexColorModel masks a 7-bit pixel with 0xFF, so an undefined value (-1) reaches an
            // empty entry past the 128 Atari colors, which Evolution paints as black.
            ATARI -> VideoFrame.BLACK or (VideoPalettes.atari.getOrNull(value and 255) ?: 0)
            XTERM16 -> VideoFrame.BLACK or VideoPalettes.xterm16[value and 15]
            XTERM256 -> VideoFrame.BLACK or VideoPalettes.xterm256[value and 255]
            VGA256 -> VideoFrame.BLACK or VideoPalettes.vga256[value and 255]
        }
    }

    companion object {
        fun fromFileValue(value: String): VideoColorFormat = entries.firstOrNull { it.fileValue == value }
            ?: throw IllegalArgumentException("Formato de cor de vídeo desconhecido: $value")
    }
}
