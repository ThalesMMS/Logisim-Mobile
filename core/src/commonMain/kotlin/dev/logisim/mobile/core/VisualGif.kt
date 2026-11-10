package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/**
 * Writes an opaque single-frame GIF. The palette is built like the ImageIO GIF writer that Evolution's
 * ExportImage uses: an octree over the pixels, scanned from the last pixel to the first, keeps every
 * colour when there are at most 256 and otherwise merges the least-used deepest branches.
 */
object VisualGif {
    fun encode(width: Int, height: Int, argb: IntArray): ByteArray {
        requireLocalized(width in 1..65535 && height in 1..65535) { message("visual_export_dimensions_large") }
        require(width.toLong() * height == argb.size.toLong())
        val capacity = 820 + (argb.size.toLong() * 2)
        requireLocalized(capacity <= Int.MAX_VALUE) { message("visual_export_dimensions_large") }
        val bytes = ByteArray(capacity.toInt())
        var position = 0
        fun byte(value: Int) { bytes[position++] = value.toByte() }
        fun word(value: Int) { byte(value); byte(value ushr 8) }
        "GIF87a".forEach { byte(it.code) }
        word(width); word(height); byte(0xf7); byte(0); byte(0)
        val palette = OctreePalette(argb)
        repeat(256) { index ->
            val color = if (index < palette.colors.size) palette.colors[index] else 0
            byte(color ushr 16); byte(color ushr 8); byte(color)
        }
        byte(0x2c); word(0); word(0); word(width); word(height); byte(0)
        byte(8) // Minimum LZW code size for the global 256-color palette.

        var blockStart = position++
        fun compressedByte(value: Int) {
            byte(value)
            if (position - blockStart == 256) {
                bytes[blockStart] = 255.toByte()
                blockStart = position++
            }
        }
        var bits = 0
        var bitCount = 0
        var codeWidth = 9
        fun code(value: Int) {
            bits = bits or (value shl bitCount)
            bitCount += codeWidth
            while (bitCount >= 8) {
                compressedByte(bits)
                bits = bits ushr 8
                bitCount -= 8
            }
        }

        val clear = 256
        val end = 257
        var next = end + 1
        val keys = IntArray(8192) { -1 }
        val codes = IntArray(keys.size)
        code(clear)
        var prefix = palette.index(argb[0])
        for (i in 1 until argb.size) {
            val suffix = palette.index(argb[i])
            val key = (prefix shl 8) or suffix
            var slot = (key * 0x9e3779b9.toInt()) ushr 19
            while (keys[slot] >= 0 && keys[slot] != key) slot = (slot + 1) and (keys.size - 1)
            if (keys[slot] == key) {
                prefix = codes[slot]
                continue
            }
            code(prefix)
            if (next == 4096) {
                code(clear)
                keys.fill(-1)
                next = end + 1
                codeWidth = 9
            } else {
                // The decoder learns the prior pair when it reads the current code.
                if (next == (1 shl codeWidth)) codeWidth++
                keys[slot] = key
                codes[slot] = next++
            }
            prefix = suffix
        }
        code(prefix)
        if (codeWidth < 12 && next == (1 shl codeWidth)) codeWidth++
        code(end)
        if (bitCount > 0) compressedByte(bits)
        val finalBlockSize = position - blockStart - 1
        bytes[blockStart] = finalBlockSize.toByte()
        if (finalBlockSize > 0) byte(0) // Image-data sub-block terminator.
        byte(0x3b)
        return bytes.copyOf(position)
    }

}

/** Octree colour quantizer with ImageIO's PaletteBuilder rules (256 colours, leaves below level 8). */
internal class OctreePalette(argb: IntArray, private val required: Int = 256) {
    private class Node(val level: Int) {
        var leaf = level > MAX_LEVEL
        var childCount = 0
        val children = arrayOfNulls<Node>(8)
        var count = 0L
        var red = 0L
        var green = 0L
        var blue = 0L
        var index = 0
        var nextReducible: Node? = null

        fun leafChildCount(): Int = if (leaf) 0 else children.sumOf { child ->
            when {
                child == null -> 0
                child.leaf -> 1
                else -> child.leafChildCount()
            }
        }
    }

    private val reducible = arrayOfNulls<Node>(MAX_LEVEL + 1)
    private var root: Node? = null
    private var size = 0
    val colors: IntArray
    private var lastColor = 0
    private var lastIndex = -1

    init {
        // ImageIO walks the image from the bottom-right pixel; a run of one colour only changes counts.
        var i = argb.size - 1
        while (i >= 0) {
            val color = argb[i] and 0xffffff
            var run = 1
            while (i - run >= 0 && argb[i - run] and 0xffffff == color) run++
            var remaining = run
            while (remaining > 0) {
                if (size <= required && remaining > 1 && root != null && reaches(color)) {
                    root = insert(root, color, 0, remaining.toLong())
                    remaining = 0
                } else {
                    root = insert(root, color, 0, 1)
                    remaining--
                    if (size > required) reduce()
                }
            }
            i -= run
        }
        // ImageIO would emit an oversized palette if its last reduction hit a leaf; reduce until it fits.
        while (size > required && reducible.any { it != null }) reduce()
        val entries = mutableListOf<Int>()
        fun assign(node: Node) {
            if (node.leaf) {
                node.index = entries.size
                entries += ((node.red / node.count).toInt() and 0xff shl 16) or
                    ((node.green / node.count).toInt() and 0xff shl 8) or ((node.blue / node.count).toInt() and 0xff)
            } else {
                node.children.forEach { it?.let(::assign) }
            }
        }
        root?.let(::assign)
        colors = entries.toIntArray()
    }

    /** Palette index of an opaque pixel. */
    fun index(argb: Int): Int {
        val color = argb and 0xffffff
        if (color == lastColor && lastIndex >= 0) return lastIndex
        var node = root!!
        while (!node.leaf) node = node.children[branch(color, node.level)]!!
        lastColor = color
        lastIndex = node.index
        return node.index
    }

    /** True when inserting [color] adds no node, so repeated inserts cannot trigger a reduction. */
    private fun reaches(color: Int): Boolean {
        var node = root ?: return false
        while (!node.leaf) node = node.children[branch(color, node.level)] ?: return false
        return true
    }

    private fun insert(existing: Node?, color: Int, level: Int, weight: Long): Node {
        val node = existing ?: Node(level).also { if (it.leaf) size++ }
        node.count += weight
        node.red += weight * ((color ushr 16) and 0xff)
        node.green += weight * ((color ushr 8) and 0xff)
        node.blue += weight * (color and 0xff)
        if (!node.leaf) {
            val branch = branch(color, level)
            if (node.children[branch] == null) {
                node.childCount++
                if (node.childCount == 2) {
                    node.nextReducible = reducible[level]
                    reducible[level] = node
                }
            }
            node.children[branch] = insert(node.children[branch], color, level + 1, weight)
        }
        return node
    }

    private fun reduce() {
        var level = reducible.size - 1
        while (level >= 0 && reducible[level] == null) level--
        if (level < 0) return
        var node = reducible[level]!!
        // PaletteBuilder's search keeps the predecessor of the first node with a smaller count.
        var cursor = node
        var minimum = cursor.count
        while (cursor.nextReducible != null) {
            if (minimum > cursor.nextReducible!!.count) {
                node = cursor
                minimum = cursor.count
            }
            cursor = cursor.nextReducible!!
        }
        if (node === reducible[level]) {
            reducible[level] = node.nextReducible
        } else {
            val next = node.nextReducible!!
            node.nextReducible = next.nextReducible
            node = next
        }
        if (node.leaf) return
        size -= node.leafChildCount() - 1
        node.leaf = true
        // Like PaletteBuilder.freeTree, detached descendants stay in the reducible lists with no children.
        fun free(n: Node) {
            n.children.forEach { it?.let(::free) }
            n.children.fill(null)
        }
        free(node)
        node.childCount = 0
    }

    private fun branch(color: Int, level: Int): Int {
        val shift = MAX_LEVEL - level
        return (((color ushr 16) ushr shift and 1) shl 2) or (((color ushr 8 and 0xff) ushr shift and 1) shl 1) or
            ((color and 0xff) ushr shift and 1)
    }

    private companion object {
        const val MAX_LEVEL = 8
    }
}
