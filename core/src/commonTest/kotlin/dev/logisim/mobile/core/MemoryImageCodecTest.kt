package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoryImageCodecTest {
    @Test
    fun allDesktopFormatsPreserveOddAndFullWidthWords() {
        for (width in listOf(1, 3, 8, 10, 16, 32, 64)) {
            val mask = if (width == 64) ULong.MAX_VALUE else (1uL shl width) - 1uL
            val image = MemoryImage(2, width, mapOf(0L to 1uL, 1L to mask, 2L to mask / 3uL, 3L to 1uL))
            for (format in MemoryImageFormat.entries) {
                val encoded = MemoryImageCodec.encode(image, format)
                val decoded = assertIs<MemoryImageImportResult.Success>(
                    MemoryImageCodec.decode(encoded, 2, width, if (format.header == null) format else null),
                    "$format / $width",
                )
                assertEquals(image, decoded.image, "$format / $width")
                assertEquals(format, decoded.format)
            }
        }
    }

    @Test
    fun byteFormatsUsePackedBitStreamsWithExplicitEndian() {
        val image = MemoryImage(2, 16, mapOf(0L to 0x1234uL, 1L to 0xabcduL))
        assertContentEquals(byteArrayOf(0x12, 0x34, 0xab.toByte(), 0xcd.toByte()),
            MemoryImageCodec.encode(image, MemoryImageFormat.BINARY_BIG))
        assertContentEquals(byteArrayOf(0x34, 0x12, 0xcd.toByte(), 0xab.toByte()),
            MemoryImageCodec.encode(image, MemoryImageFormat.BINARY_LITTLE))
        val odd = MemoryImage(2, 10, mapOf(0L to 0x155uL, 1L to 0x2aauL))
        assertContentEquals(byteArrayOf(0x55, 0x6a, 0xa0.toByte()),
            MemoryImageCodec.encode(odd, MemoryImageFormat.BINARY_BIG))
        val decoded = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decode(
            byteArrayOf(0x55, 0x6a, 0xa0.toByte()), 2, 10, MemoryImageFormat.BINARY_BIG))
        assertEquals(0x155uL, decoded.image[0])
        assertEquals(0x2aauL, decoded.image[1])
    }

    @Test
    fun addressedBytesUseByteAddressesRatherThanWordAddresses() {
        val decoded = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeText(
            "v3.0 hex bytes addressed big-endian\n02: abcd  printable annotation\n00: 1234 # comment\n", 2, 16))
        assertEquals(0x1234uL, decoded.image[0])
        assertEquals(0xabcduL, decoded.image[1])
        assertEquals(0uL, decoded.image[2])
    }

    @Test
    fun rawCountsAreDecimalAndCommentsDoNotChangeTheImage() {
        val decoded = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeText(
            "v2.0 raw\n10*0 f # word ten\n2*a\n", 4, 4))
        assertEquals(mapOf(10L to 15uL, 11L to 10uL, 12L to 10uL), decoded.image.cells)
        val addressed = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeText(
            "v3.0 hex words\na : 0x5 6  annotation\n0: 3\n", 4, 4))
        assertEquals(MemoryImageFormat.HEX_WORDS_ADDRESSED, addressed.format)
        assertEquals(mapOf(0L to 3uL, 10L to 5uL, 11L to 6uL), addressed.image.cells)
    }

    @Test
    fun circContentsPreserveDimensionsAndSparseWords() {
        val decoded = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeCirc(
            "addr/data: 4 8\n0f 1e 1d 2c e0 f0 6*0 01 04 03 02\n"))
        assertEquals(4, decoded.image.addressBits)
        assertEquals(8, decoded.image.dataBits)
        assertEquals(0x0fuL, decoded.image[0])
        assertEquals(2uL, decoded.image[15])
        assertEquals(decoded.image, assertIs<MemoryImageImportResult.Success>(
            MemoryImageCodec.decodeCirc(MemoryImageCodec.encodeCirc(decoded.image))).image)
    }

    @Test
    fun escapedAsciiSupportsControlCodesAndRejectsMalformedEscapes() {
        val result = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeText(
            "A\\0\\n\\t\\xFF\\\\", 3, 8, MemoryImageFormat.ASCII_BIG))
        assertEquals(listOf(65uL, 0uL, 10uL, 9uL, 255uL, 92uL), (0L..5L).map { result.image[it] })
        for (text in listOf("\\", "\\x1", "\\xGG", "\\q")) {
            assertIs<MemoryImageImportResult.Failure>(MemoryImageCodec.decodeText(text, 3, 8, MemoryImageFormat.ASCII_BIG))
        }
    }

    @Test
    fun overflowAndInvalidTokensRejectTheEntireImportWithLineNumbers() {
        for (text in listOf("v2.0 raw\n1 100\n", "v3.0 hex words plain\nzz\n", "v2.0 raw\n17*0\n")) {
            val failure = assertIs<MemoryImageImportResult.Failure>(MemoryImageCodec.decodeText(text, 4, 8))
            assertEquals(2, failure.diagnostics.single().line)
        }
        assertIs<MemoryImageImportResult.Failure>(MemoryImageCodec.decodeText("v3.0 hex bytes plain\na\n", 2, 8))
        assertIs<MemoryImageImportResult.Failure>(MemoryImageCodec.decodeText("v3.0 hex words words plain\n0\n", 2, 8))
        assertIs<MemoryImageImportResult.Failure>(MemoryImageCodec.decodeText("v2.0 raw\n-1\n", 2, 8))
        assertIs<MemoryImageImportResult.Failure>(MemoryImageCodec.decode(byteArrayOf(0xff.toByte()), 2, 1, MemoryImageFormat.BINARY_BIG))
    }

    @Test
    fun resourceLimitsAreExplicitAndCheckedBeforeLargeExpansion() {
        val limits = MemoryImageLimits(maxInputBytes = 64, maxNonZeroWords = 2, maxExportBytes = 64)
        assertIs<MemoryImageImportResult.Failure>(MemoryImageCodec.decodeText("v2.0 raw\n3*1\n", 4, 8, limits = limits))
        assertIs<MemoryImageImportResult.Failure>(MemoryImageCodec.decode(byteArrayOf(1, 2, 3), 4, 8, MemoryImageFormat.BINARY_BIG, limits))
        val largeGap = MemoryImage(24, 8, mapOf(16_777_215L to 1uL))
        val compact = MemoryImageCodec.encodeCirc(largeGap)
        assertTrue(compact.length < 80)
        assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeCirc(compact))
        assertFailsWith<IllegalArgumentException> { MemoryImageCodec.encode(largeGap, MemoryImageFormat.BINARY_BIG, limits) }
    }

    @Test
    fun imageOwnershipAndExplicitResizeCannotMutateTheOriginal() {
        val original = mutableMapOf(0L to 255uL, 12L to 7uL)
        val image = MemoryImage(4, 8, original)
        original[0] = 0uL
        assertEquals(255uL, image[0])
        val resized = image.resized(2, 4)
        assertEquals(mapOf(0L to 15uL), resized.cells)
        assertEquals(7uL, image[12])
        assertEquals(emptyMap(), image.cleared().cells)
        assertFailsWith<IllegalArgumentException> { image.withCells(mapOf(16L to 1uL)) }
        assertFailsWith<IllegalArgumentException> { MemoryImage(4, 4, mapOf(0L to 16uL)) }
    }
}
