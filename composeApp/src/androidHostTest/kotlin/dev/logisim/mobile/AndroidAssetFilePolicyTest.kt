package dev.logisim.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.ByteArrayInputStream

class AndroidAssetFilePolicyTest {
    @Test fun exportNamesKeepTheirActualFormatWithoutCircuitSuffix() {
        assertEquals("diagram.png", AndroidAssetFilePolicy.fileName("../../diagram", "image/png"))
        assertEquals("drawing.svg", AndroidAssetFilePolicy.fileName("C:\\private\\drawing.svg", "image/svg+xml"))
        assertEquals("print.pdf", AndroidAssetFilePolicy.fileName("print.pdf", "application/pdf"))
        assertEquals("drawing.tex", AndroidAssetFilePolicy.fileName("drawing.TEX", "application/x-tex"))
        assertEquals("application/x-tex", AndroidAssetFilePolicy.createPickerType("drawing.tex", "application/x-tex"))
        assertEquals("photo.png", AndroidAssetFilePolicy.fileName("photo.PNG", "image/png"))
        assertEquals("export.jpg", AndroidAssetFilePolicy.fileName("\n..", "image/jpeg"))
        assertEquals("firmware.elf", AndroidAssetFilePolicy.fileName("firmware.elf", "application/x-elf"))
    }

    @Test fun binarySignatureTakesPriorityOverProviderMetadata() {
        assertEquals("image/png", AndroidAssetFilePolicy.detectedMime(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), "bad.pdf", "application/pdf"))
        assertEquals("image/jpeg", AndroidAssetFilePolicy.detectedMime(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), "photo.png", "image/png"))
        assertEquals("application/pdf", AndroidAssetFilePolicy.detectedMime("%PDF-1.4".encodeToByteArray(), "print.png", "image/png"))
        assertEquals("application/x-elf", AndroidAssetFilePolicy.detectedMime(byteArrayOf(0x7f, 0x45, 0x4c, 0x46), "a", null))
    }

    @Test fun archiveSignaturePreservesJarNameOnlyInTheJavaArchivePicker() {
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val javaPicker = listOf("application/java-archive", "application/octet-stream")
        val name = "C:\\private\\Gray.JAR"
        val mime = AndroidAssetFilePolicy.detectedMime(zip, name, "application/zip", javaPicker)
        assertEquals("application/java-archive", mime)
        assertEquals("Gray.JAR", AndroidAssetFilePolicy.fileName(name, mime))
        assertEquals("application/zip", AndroidAssetFilePolicy.detectedMime(zip, "Gray.jar", mime,
            listOf("application/octet-stream")))
        assertEquals("application/zip", AndroidAssetFilePolicy.detectedMime(zip, "bundle.zip", mime, javaPicker))
    }

    @Test fun memoryTextAndBinaryFormatsKeepTheirNamesAndPayloads() {
        val bytes = byteArrayOf(0, 1, 0x80.toByte(), 0xff.toByte())
        for ((name, mime) in listOf("memory.bin" to "application/octet-stream", "memory.txt" to "text/plain",
            "memory.hex" to "text/plain", "memory.mem" to "text/plain")) {
            assertEquals(name, AndroidAssetFilePolicy.fileName(name, mime))
            assertEquals(mime, AndroidAssetFilePolicy.mime(mime))
            assertTrue(DocumentAsset(name, mime, bytes).bytes.contentEquals(bytes))
        }
        val longHexName = AndroidAssetFilePolicy.fileName("m".repeat(220) + ".hex", "text/plain")
        assertEquals(180, longHexName.length)
        assertTrue(longHexName.endsWith(".hex"))
        assertEquals(listOf("*/*"), AndroidAssetFilePolicy.pickerTypes(listOf("application/octet-stream")))
        assertEquals(listOf("image/png", "image/jpeg"), AndroidAssetFilePolicy.pickerTypes(listOf("image/png", "image/jpeg")))
        assertEquals("application/octet-stream", AndroidAssetFilePolicy.createPickerType("memory.hex", "text/plain"))
        assertEquals("application/octet-stream", AndroidAssetFilePolicy.createPickerType("memory.mem", "text/plain"))
        assertEquals("text/plain", AndroidAssetFilePolicy.createPickerType("memory.txt", "text/plain"))
        assertEquals("image/svg+xml", AndroidAssetFilePolicy.createPickerType("diagram.svg", "image/svg+xml"))
    }

    @Test fun acceptedTypesUseCallerScopeAndAllowRawBinaryLoaders() {
        assertTrue(AndroidAssetFilePolicy.accepts("image/png", listOf("image/*")))
        assertFalse(AndroidAssetFilePolicy.accepts("application/pdf", listOf("image/*")))
        assertTrue(AndroidAssetFilePolicy.accepts("application/x-elf", listOf("application/octet-stream")))
        assertEquals("image/png", AndroidAssetFilePolicy.mime("IMAGE/PNG; charset=binary"))
        assertNull(AndroidAssetFilePolicy.mime("file:///private/path"))
        assertNull(AndroidAssetFilePolicy.mime("text/" + "x".repeat(300)))
        assertEquals(67_108_864, AndroidAssetFilePolicy.MAXIMUM_BYTES)
    }

    @Test fun documentAssetOwnsItsBytesAcrossCallerMutations() {
        val source = byteArrayOf(1, 2, 3)
        val asset = DocumentAsset("raw", "application/octet-stream", source)
        source[0] = 99
        val retrieved = asset.bytes
        retrieved[1] = 99
        assertTrue(asset.bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(3, asset.size)
    }

    @Test fun binaryReadAcceptsTheExactLimitWithoutTruncatingAnyByte() {
        val expected = byteArrayOf(0, 127, 0x80.toByte(), 0xff.toByte())
        assertTrue(AndroidAssetFilePolicy.readBounded(ByteArrayInputStream(expected), 4).contentEquals(expected))
        assertTrue(AndroidAssetFilePolicy.readBounded(ByteArrayInputStream(byteArrayOf()), 4).isEmpty())
    }

    @Test fun binaryReadRejectsOneByteBeyondTheLimit() {
        val error = assertFailsWith<AssetStorageException> {
            AndroidAssetFilePolicy.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3)
        }
        assertEquals("android_asset_too_large", error.notice.key)
    }

    @Test fun invalidLimitsAreRejectedBeforeReadingSourceBytes() {
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val error = assertFailsWith<AssetStorageException> { AndroidAssetFilePolicy.readBounded(stream, 0) }
        assertEquals("android_asset_invalid_limit", error.notice.key)
        assertEquals(3, stream.available())
    }
}
