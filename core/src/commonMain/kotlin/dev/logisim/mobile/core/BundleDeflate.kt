package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

/** Bounded raw DEFLATE decoding for portable ZIP bundles. */
internal object BundleDeflate {
    private val lengthBase = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val lengthExtra = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val distanceBase = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val distanceExtra = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val codeOrder = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    fun inflate(compressed: ByteArray, expectedSize: Int): ByteArray {
        requireLocalized(expectedSize in 0..CircBundleCodec.MAX_FILE_BYTES) { message(Messages.CodecDeflateSizeInvalid) }
        val reader = Bits(compressed)
        val output = ByteArray(expectedSize)
        var written = 0
        fun append(value: Int) { requireLocalized(written < output.size) { message(Messages.CodecDeflateSizeExceeded) }; output[written++] = value.toByte() }
        var last: Boolean
        do {
            last = reader.read(1) != 0
            val blockType = reader.read(2)
            when (blockType) {
                0 -> {
                    reader.align()
                    val length = reader.read(16)
                    requireLocalized(length xor reader.read(16) == 0xffff) { message(Messages.CodecDeflateUncompressedInvalid) }
                    repeat(length) { append(reader.read(8)) }
                }
                1, 2 -> {
                    val dynamic = blockType == 2
                    val literals: Huffman
                    val distances: Huffman
                    if (dynamic) {
                        val literalCount = reader.read(5) + 257
                        val distanceCount = reader.read(5) + 1
                        val codeCount = reader.read(4) + 4
                        requireLocalized(literalCount <= 286 && distanceCount <= 32) { message(Messages.CodecDeflateTreesInvalid) }
                        val codeLengths = IntArray(19)
                        repeat(codeCount) { codeLengths[codeOrder[it]] = reader.read(3) }
                        val codeTree = Huffman(codeLengths)
                        val lengths = IntArray(literalCount + distanceCount)
                        var index = 0
                        while (index < lengths.size) {
                            val symbol = codeTree.decode(reader)
                            if (symbol <= 15) lengths[index++] = symbol
                            else {
                                val count = when (symbol) { 16 -> reader.read(2) + 3; 17 -> reader.read(3) + 3; 18 -> reader.read(7) + 11; else -> throw LocalizedArgumentException(message(Messages.CodecDeflateCodeLengthInvalid)) }
                                requireLocalized(index + count <= lengths.size && (symbol != 16 || index > 0)) { message(Messages.CodecDeflateRepetitionInvalid) }
                                val length = if (symbol == 16) lengths[index - 1] else 0
                                repeat(count) { lengths[index++] = length }
                            }
                        }
                        requireLocalized(lengths[256] != 0) { message(Messages.CodecDeflateEndMissing) }
                        literals = Huffman(lengths.copyOfRange(0, literalCount))
                        distances = Huffman(lengths.copyOfRange(literalCount, lengths.size))
                    } else {
                        literals = Huffman(IntArray(288) { if (it <= 143) 8 else if (it <= 255) 9 else if (it <= 279) 7 else 8 })
                        distances = Huffman(IntArray(32) { 5 })
                    }
                    while (true) {
                        val symbol = literals.decode(reader)
                        if (symbol < 256) append(symbol)
                        else if (symbol == 256) break
                        else {
                            requireLocalized(symbol in 257..285) { message(Messages.CodecDeflateLengthInvalid) }
                            val length = lengthBase[symbol - 257] + reader.read(lengthExtra[symbol - 257])
                            val distanceSymbol = distances.decode(reader)
                            requireLocalized(distanceSymbol in 0..29) { message(Messages.CodecDeflateDistanceInvalid) }
                            val distance = distanceBase[distanceSymbol] + reader.read(distanceExtra[distanceSymbol])
                            requireLocalized(distance <= written) { message(Messages.CodecDeflateWindowReference) }
                            repeat(length) { append(output[written - distance].toInt()) }
                        }
                    }
                }
                else -> throw LocalizedArgumentException(message(Messages.CodecDeflateReservedBlock))
            }
        } while (!last)
        requireLocalized(written == expectedSize && reader.usedBytes == compressed.size) { message(Messages.CodecDeflateSizeInconsistent) }
        return output
    }

    private class Bits(private val bytes: ByteArray) {
        private var bit = 0
        val usedBytes: Int get() = (bit + 7) / 8
        fun read(count: Int): Int {
            requireLocalized(count in 0..16 && bit.toLong() + count <= bytes.size.toLong() * 8) { message(Messages.CodecDeflateTruncated) }
            var value = 0
            repeat(count) { shift ->
                value = value or (((bytes[bit / 8].toInt() ushr (bit % 8)) and 1) shl shift)
                bit++
            }
            return value
        }
        fun align() { bit = (bit + 7) and -8 }
    }

    private class Huffman(lengths: IntArray) {
        private val codes = Array(16) { mutableMapOf<Int, Int>() }
        private val maximum = lengths.maxOrNull() ?: 0
        init {
            requireLocalized(maximum <= 15) { message(Messages.CodecHuffmanCodeLimit) }
            val counts = IntArray(16)
            lengths.forEach { counts[it]++ }
            counts[0] = 0
            val next = IntArray(16)
            var code = 0
            for (bits in 1..15) { code = (code + counts[bits - 1]) shl 1; next[bits] = code; requireLocalized(code + counts[bits] <= 1 shl bits) { message(Messages.CodecHuffmanTreeOverloaded) } }
            lengths.forEachIndexed { symbol, size -> if (size != 0) {
                var original = next[size]++
                var reversed = 0
                repeat(size) { reversed = (reversed shl 1) or (original and 1); original = original ushr 1 }
                codes[size][reversed] = symbol
            } }
        }
        fun decode(reader: Bits): Int {
            var code = 0
            for (length in 1..maximum) {
                code = code or (reader.read(1) shl (length - 1))
                codes[length][code]?.let { return it }
            }
            throw LocalizedArgumentException(message(Messages.CodecHuffmanCodeInvalid))
        }
    }
}
