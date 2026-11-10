@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedStateException
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfURL
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.truncateFileAtOffset
import platform.Foundation.writeToURL

class IosDocumentAssetActionsTest {
    @Test
    fun pngSignatureDeterminesMimeWithoutTrustingTheFilename() {
        val bytes = bytes(137, 80, 78, 71, 13, 10, 26, 10, 0, 255)
        withFile(bytes, "picture.wrong") { url ->
            val asset = IosDocumentAssetActions { null }.readAsset(url, imageTypes, bytes.size)
            assertEquals("picture.wrong", asset.name)
            assertEquals("image/png", asset.mimeType)
            assertContentEquals(bytes, asset.bytes)
        }
    }

    @Test
    fun jpegSignatureAndBinaryContentsSurviveFilesRead() {
        val bytes = bytes(255, 216, 255, 224, 0, 16, 74, 70, 73, 70, 0, 255, 217)
        withFile(bytes) { url ->
            val asset = IosDocumentAssetActions { null }.readAsset(url, setOf("image/jpeg"), 1024)
            assertEquals("image/jpeg", asset.mimeType)
            assertContentEquals(bytes, asset.bytes)
        }
    }

    @Test
    fun bothGifSignaturesAreAcceptedAsGif() {
        for (version in listOf(55, 57)) {
            val bytes = bytes(71, 73, 70, 56, version, 97, 1, 0, 1, 0, 59)
            withFile(bytes) { url ->
                val asset = IosDocumentAssetActions { null }.readAsset(url, setOf("image/gif"), 1024)
                assertEquals("image/gif", asset.mimeType)
                assertContentEquals(bytes, asset.bytes)
            }
        }
    }

    @Test
    fun imageOutsideTheCallersAcceptedMimeTypesIsRejected() {
        withFile(bytes(137, 80, 78, 71, 13, 10, 26, 10)) { url ->
            val error = assertFailsWith<LocalizedStateException> {
                IosDocumentAssetActions { null }.readAsset(url, setOf("image/jpeg"), 1024)
            }
            assertEquals("ios_asset_unsupported_type", error.text.key)
        }
    }

    @Test
    fun oversizedFileIsRejectedBeforeItsUnsupportedContentsAreInspected() {
        withFile(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9)) { url ->
            val error = assertFailsWith<LocalizedArgumentException> {
                IosDocumentAssetActions { null }.readAsset(url, imageTypes, 8)
            }
            assertEquals("ios_asset_too_large", error.text.key)
        }
    }

    @Test
    fun platformSizeLimitAppliesWhenTheCallerAllowsMoreThan64MiB() {
        withFile(bytes(127, 69, 76, 70)) { url ->
            val handle = assertNotNull(NSFileHandle.fileHandleForWritingAtPath(assertNotNull(url.path)))
            try { handle.truncateFileAtOffset(64uL * 1024uL * 1024uL + 1uL) } finally { handle.closeFile() }
            val error = assertFailsWith<LocalizedArgumentException> {
                IosDocumentAssetActions { null }.readAsset(url, setOf("application/octet-stream"), Int.MAX_VALUE)
            }
            assertEquals("ios_asset_too_large", error.text.key)
        }
    }

    @Test
    fun genericElfImportPreservesEveryByteWithoutImageValidation() {
        val bytes = bytes(127, 69, 76, 70, 1, 1, 1, 0, 255, 0, 128, 10, 13, 0)
        withFile(bytes, "program.elf") { url ->
            val asset = IosDocumentAssetActions { null }.readAsset(url, setOf("application/octet-stream"), bytes.size)
            assertEquals("program.elf", asset.name)
            assertEquals("application/octet-stream", asset.mimeType)
            assertEquals(14, asset.size)
            assertContentEquals(bytes, asset.bytes)
        }
    }

    @Test
    fun binaryExportsPreserveBytesAndCallerFilename() {
        val bytes = bytes(127, 69, 76, 70, 0, 255, 128)
        val temporary = temporaryAsset(DocumentAsset("memory.bin", "application/octet-stream", bytes))
        try {
            assertEquals("memory.bin", temporary.url.lastPathComponent)
            val data = assertNotNull(NSData.dataWithContentsOfURL(temporary.url))
            assertContentEquals(bytes, assertNotNull(data.bytes).reinterpret<ByteVar>().readBytes(data.length.toInt()))
        } finally { temporary.remove() }
    }

    @Test
    fun zipExportsPreserveBytesAndUseTheZipExtension() {
        // FPGA projects and companion artifacts are exported as ZIP archives.
        val bytes = bytes(80, 75, 3, 4, 20, 0, 0, 0)
        for (name in listOf("logisim_fpga_top-fpga.zip", "fpga-artifacts")) {
            val temporary = temporaryAsset(DocumentAsset(name, "application/zip", bytes))
            try {
                assertEquals(if (name.endsWith(".zip")) name else "$name.zip", temporary.url.lastPathComponent)
                val data = assertNotNull(NSData.dataWithContentsOfURL(temporary.url))
                assertContentEquals(bytes, assertNotNull(data.bytes).reinterpret<ByteVar>().readBytes(data.length.toInt()))
            } finally { temporary.remove() }
        }
    }

    @Test
    fun bundledProjectExportsPreserveBytesAndUseTheBundleExtension() {
        val bytes = bytes(80, 75, 3, 4, 0, 255, 128)
        for (name in listOf("project", "project.lsebdl")) {
            val temporary = temporaryAsset(DocumentAsset(name, "application/x-logisim-bundle", bytes))
            try {
                assertEquals("project.lsebdl", temporary.url.lastPathComponent)
                val data = assertNotNull(NSData.dataWithContentsOfURL(temporary.url))
                assertContentEquals(bytes, assertNotNull(data.bytes).reinterpret<ByteVar>().readBytes(data.length.toInt()))
            } finally { temporary.remove() }
        }
    }

    @Test
    fun textExportsKeepMemoryFormatExtensionAndExactBytes() {
        val bytes = "v2.0 raw\n00 ff 80\n".encodeToByteArray()
        val temporary = temporaryAsset(DocumentAsset("memory.hex", "text/plain", bytes))
        try {
            assertEquals("memory.hex", temporary.url.lastPathComponent)
            val data = assertNotNull(NSData.dataWithContentsOfURL(temporary.url))
            assertContentEquals(bytes, assertNotNull(data.bytes).reinterpret<ByteVar>().readBytes(data.length.toInt()))
        } finally { temporary.remove() }
    }

    @Test
    fun nonFileUrlsAreRejectedWithoutAccessingTheirContents() {
        val url = assertNotNull(NSURL.URLWithString("https://example.invalid/picture.png"))
        val error = assertFailsWith<LocalizedArgumentException> {
            IosDocumentAssetActions { null }.readAsset(url, imageTypes, 1024)
        }
        assertEquals("ios_asset_file_url_required", error.text.key)
    }

    @Test
    fun zeroAndNegativeLimitsAreRejectedBeforeOpeningAFile() {
        val missing = NSURL.fileURLWithPath("/missing/" + NSUUID().UUIDString)
        for (limit in listOf(0, -1)) {
            val error = assertFailsWith<LocalizedArgumentException> {
                IosDocumentAssetActions { null }.readAsset(missing, imageTypes, limit)
            }
            assertEquals("ios_asset_too_large", error.text.key)
        }
    }

    @Test
    fun emptyFilesAreRejected() {
        withFile(byteArrayOf()) { url ->
            val error = assertFailsWith<LocalizedArgumentException> {
                IosDocumentAssetActions { null }.readAsset(url, setOf("application/octet-stream"), 1024)
            }
            assertEquals("ios_asset_too_large", error.text.key)
        }
    }

    private fun withFile(bytes: ByteArray, name: String = "asset.bin", action: (NSURL) -> Unit) {
        val cache = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val directory = "$cache/LogisimAssetTest-" + NSUUID().UUIDString
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        val url = NSURL.fileURLWithPath("$directory/$name")
        try {
            val data = if (bytes.isEmpty()) NSData() else bytes.usePinned {
                NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
            }
            check(data.writeToURL(url, atomically = true))
            action(url)
        } finally { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
    }

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()

    companion object {
        private val imageTypes = setOf("image/png", "image/jpeg", "image/gif")
    }
}
