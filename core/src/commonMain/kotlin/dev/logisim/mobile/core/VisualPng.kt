package dev.logisim.mobile.core

/** Encodes straight-alpha ARGB pixels using PNG RGBA8 and bounded, stored DEFLATE blocks. */
object VisualPng {
    fun encode(width: Int, height: Int, argb: IntArray): ByteArray {
        require(width > 0 && height > 0 && width.toLong() * height == argb.size.toLong())
        val rowBytes = 1L + width.toLong() * 4
        val rawLength = rowBytes * height
        val blockCount = (rawLength + 65534) / 65535
        val zlibLength = 2L + rawLength + blockCount * 5 + 4
        val outputLength = 8L + 25 + 13 + 12 + zlibLength + 12
        require(outputLength <= Int.MAX_VALUE) { "PNG exceeds the encoder size limit." }
        val bytes = ByteArray(outputLength.toInt())
        var cursor = 0
        fun byte(value: Int) { bytes[cursor++] = value.toByte() }
        fun integer(value: Int) { byte(value ushr 24); byte(value ushr 16); byte(value ushr 8); byte(value) }
        fun chunk(type: String, length: Int, data: () -> Unit) {
            integer(length)
            val start = cursor
            type.forEach { byte(it.code) }
            data()
            check(cursor - start == length + 4)
            integer(crc(bytes, start, length + 4))
        }
        intArrayOf(137, 80, 78, 71, 13, 10, 26, 10).forEach(::byte)
        chunk("IHDR", 13) { integer(width); integer(height); byte(8); byte(6); byte(0); byte(0); byte(0) }
        chunk("sRGB", 1) { byte(0) }
        chunk("IDAT", zlibLength.toInt()) {
            byte(0x78); byte(0x01)
            var remaining = rawLength
            var pixel = 0
            var rowPosition = 0
            var channel = 0
            var a = 1L
            var b = 0L
            var adlerBatch = 0
            while (remaining > 0) {
                val length = minOf(65535L, remaining).toInt()
                byte(if (remaining == length.toLong()) 1 else 0)
                byte(length); byte(length ushr 8); byte(length.inv()); byte(length.inv() ushr 8)
                repeat(length) {
                    val value = if (rowPosition == 0) 0 else {
                        val shift = when (channel) { 0 -> 16; 1 -> 8; 2 -> 0; else -> 24 }
                        val result = (argb[pixel] ushr shift) and 255
                        channel++
                        if (channel == 4) { channel = 0; pixel++ }
                        result
                    }
                    byte(value)
                    a += value
                    b += a
                    adlerBatch++
                    if (adlerBatch == 5552) { a %= 65521; b %= 65521; adlerBatch = 0 }
                    rowPosition++
                    if (rowPosition.toLong() == rowBytes) rowPosition = 0
                }
                remaining -= length
            }
            integer((((b % 65521) shl 16) or (a % 65521)).toInt())
        }
        chunk("IEND", 0) {}
        check(cursor == bytes.size)
        return bytes
    }

    private val crcTable = IntArray(256) { value ->
        var c = value
        repeat(8) { c = if ((c and 1) == 0) c ushr 1 else (c ushr 1) xor 0xedb88320.toInt() }
        c
    }
    private fun crc(bytes: ByteArray, start: Int, length: Int): Int {
        var value = -1
        for (index in start until start + length) value = crcTable[(value xor bytes[index].toInt()) and 255] xor (value ushr 8)
        return value.inv()
    }
}
