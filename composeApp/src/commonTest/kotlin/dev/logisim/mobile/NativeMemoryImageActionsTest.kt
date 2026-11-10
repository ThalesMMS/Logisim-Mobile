package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.MemoryImage
import dev.logisim.mobile.core.MemoryImageFormat
import dev.logisim.mobile.core.MemoryImageImportResult
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeMemoryImageActionsTest {
    @Test
    fun binaryImportUsesTheSelectedMemoryDimensionsAndEndian() {
        val assets = FakeAssets()
        var received: MemoryImageImportResult? = null
        actions(assets).importImage(memory(dataBits = 16), MemoryImageFormat.BINARY_BIG) { received = it }
        assertEquals(listOf("application/octet-stream"), assets.requestedMimeTypes)
        assertEquals(67_108_864, assets.maximumBytes)
        assets.finishOpen(opened("misleading.hex", byteArrayOf(0x12, 0x34, 0xab.toByte(), 0xcd.toByte())))
        val imported = assertIs<MemoryImageImportResult.Success>(received)
        assertEquals(MemoryImageFormat.BINARY_BIG, imported.format)
        assertEquals(2, imported.image.addressBits)
        assertEquals(16, imported.image.dataBits)
        assertEquals(mapOf(0L to 0x1234uL, 1L to 0xabcduL), imported.image.cells)
    }

    @Test
    fun littleEndianImportDoesNotInferItsFormatFromTheFilename() {
        val assets = FakeAssets()
        var received: MemoryImageImportResult? = null
        actions(assets).importImage(memory(dataBits = 16), MemoryImageFormat.BINARY_LITTLE) { received = it }
        assets.finishOpen(opened("words.hex", byteArrayOf(0x12, 0x34, 0xab.toByte(), 0xcd.toByte())))
        val imported = assertIs<MemoryImageImportResult.Success>(received)
        assertEquals(MemoryImageFormat.BINARY_LITTLE, imported.format)
        assertEquals(mapOf(0L to 0x3412uL, 1L to 0xcdabuL), imported.image.cells)
    }

    @Test
    fun addressedHexImportRetainsSparseAddresses() {
        val assets = FakeAssets()
        var received: MemoryImageImportResult? = null
        actions(assets).importImage(memory(addressBits = 4), MemoryImageFormat.HEX_WORDS_ADDRESSED) { received = it }
        assets.finishOpen(opened("memory.hex", "v3.0 hex words addressed\n03: aa\n0f: ff\n".encodeToByteArray()))
        assertEquals(mapOf(3L to 0xaauL, 15L to 0xffuL), assertIs<MemoryImageImportResult.Success>(received).image.cells)
    }

    @Test
    fun rawImportDecodesRepeatCountsWithoutChangingTheComponent() {
        val assets = FakeAssets()
        val component = memory()
        var received: MemoryImageImportResult? = null
        actions(assets).importImage(component, MemoryImageFormat.RAW) { received = it }
        assets.finishOpen(opened("memory.hex", "v2.0 raw\n0a 2*0 ff\n".encodeToByteArray()))
        assertEquals(mapOf(0L to 10uL, 3L to 255uL), assertIs<MemoryImageImportResult.Success>(received).image.cells)
        assertEquals(mapOf("addrWidth" to "2", "dataWidth" to "8"), component.attributes)
    }

    @Test
    fun cancellationReturnsNullAndKeepsTheCallersImage() {
        val assets = FakeAssets()
        val original = MemoryImage(2, 8, mapOf(0L to 0xaauL))
        var visible = original
        var callbacks = 0
        actions(assets).importImage(memory(), MemoryImageFormat.RAW) { result ->
            callbacks++
            assertNull(result)
            if (result is MemoryImageImportResult.Success) visible = result.image
        }
        assets.finishOpen(AssetOpenResult.Cancelled)
        assertEquals(1, callbacks)
        assertSame(original, visible)
        assertEquals(0xaauL, visible[0])
    }

    @Test
    fun nativeReadFailureUsesTheCurrentNoticeResolver() {
        val assets = FakeAssets()
        var language = "first"
        var received: MemoryImageImportResult? = null
        actions(assets, resolveNotice = { "$language:${it.key}" })
            .importImage(memory(), MemoryImageFormat.RAW) { received = it }
        language = "current"
        assets.finishOpen(AssetOpenResult.Failure(message("provider_revoked")))
        val diagnostic = assertIs<MemoryImageImportResult.Failure>(received).diagnostics.single()
        assertNull(diagnostic.line)
        assertEquals("current:provider_revoked", diagnostic.message)
    }

    @Test
    fun invalidImageReturnsFailureWithoutReplacingExistingWords() {
        val assets = FakeAssets()
        val original = MemoryImage(2, 4, mapOf(0L to 3uL))
        var visible = original
        var received: MemoryImageImportResult? = null
        actions(assets).importImage(memory(dataBits = 4), MemoryImageFormat.RAW) { result ->
            received = result
            if (result is MemoryImageImportResult.Success) visible = result.image
        }
        assets.finishOpen(opened("memory.hex", "v2.0 raw\n10\n".encodeToByteArray()))
        assertIs<MemoryImageImportResult.Failure>(received)
        assertSame(original, visible)
        assertEquals(3uL, visible[0])
    }

    @Test
    fun incompatibleTextHeaderIsNotSilentlyAutoDetected() {
        val assets = FakeAssets()
        var received: MemoryImageImportResult? = null
        actions(assets).importImage(memory(), MemoryImageFormat.HEX_WORDS_PLAIN) { received = it }
        assets.finishOpen(opened("memory.hex", "v2.0 raw\n1\n".encodeToByteArray()))
        assertIs<MemoryImageImportResult.Failure>(received)
    }

    @Test
    fun invalidMemoryDimensionsFailBeforeOpeningFiles() {
        val assets = FakeAssets()
        var received: MemoryImageImportResult? = null
        actions(assets).importImage(memory(addressBits = 1), MemoryImageFormat.RAW) { received = it }
        assertIs<MemoryImageImportResult.Failure>(received)
        assertEquals(0, assets.openCalls)
    }

    @Test
    fun binaryExportPreservesBytesAndUsesABinFilename() {
        val assets = FakeAssets()
        val bytes = byteArrayOf(0, 255.toByte(), 13, 10, 128.toByte())
        actions(assets).exportImage(memory(label = "Boot/ROM"), MemoryImageFormat.BINARY_LITTLE, bytes)
        bytes[0] = 99
        val exported = assertNotNull(assets.created)
        assertEquals("Boot_ROM.bin", exported.name)
        assertEquals("application/octet-stream", exported.mimeType)
        assertContentEquals(byteArrayOf(0, 255.toByte(), 13, 10, 128.toByte()), exported.bytes)
    }

    @Test
    fun textExportsKeepTheirEncodedContentsAndAppropriateExtensions() {
        val contents = "v2.0 raw\nff 00\n".encodeToByteArray()
        val hexAssets = FakeAssets()
        actions(hexAssets).exportImage(memory(label = "table.hex"), MemoryImageFormat.RAW, contents)
        val hex = assertNotNull(hexAssets.created)
        assertEquals("table.hex", hex.name)
        assertEquals("text/plain", hex.mimeType)
        assertContentEquals(contents, hex.bytes)
        val asciiAssets = FakeAssets()
        actions(asciiAssets).exportImage(memory(label = "escaped"), MemoryImageFormat.ASCII_BIG, "\\x00\\xff".encodeToByteArray())
        val ascii = assertNotNull(asciiAssets.created)
        assertEquals("escaped.txt", ascii.name)
        assertEquals("text/plain", ascii.mimeType)
        assertContentEquals("\\x00\\xff".encodeToByteArray(), ascii.bytes)
    }

    @Test
    fun exportResultsAreObservableAndCancellationIsSilent() {
        val assets = FakeAssets()
        val notices = mutableListOf<LocalizedText>()
        val failures = mutableListOf<LocalizedText>()
        val actions = actions(assets, onNotice = notices::add, onFailure = failures::add)
        actions.exportImage(memory(), MemoryImageFormat.BINARY_BIG, byteArrayOf(1))
        assets.finishCreate(AssetActionResult.Completed)
        assertEquals(listOf("native_memory_export_complete"), notices.map { it.key })
        actions.exportImage(memory(), MemoryImageFormat.BINARY_BIG, byteArrayOf(2))
        assets.finishCreate(AssetActionResult.Cancelled)
        assertEquals(1, notices.size)
        assertTrue(failures.isEmpty())
        actions.exportImage(memory(), MemoryImageFormat.BINARY_BIG, byteArrayOf(3))
        val denied = message("destination_denied")
        assets.finishCreate(AssetActionResult.Failure(denied))
        assertEquals(listOf(denied), failures)
    }

    @Test
    fun closingTheWindowPreventsAnOutstandingImportCallback() {
        val assets = FakeAssets()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var callbacks = 0
        actions(assets, scope).importImage(memory(), MemoryImageFormat.RAW) { callbacks++ }
        scope.cancel()
        assets.finishOpen(opened("memory.hex", "v2.0 raw\n1\n".encodeToByteArray()))
        assertEquals(0, callbacks)
    }

    private fun actions(
        assets: FakeAssets,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        onNotice: (LocalizedText) -> Unit = {},
        onFailure: (LocalizedText) -> Unit = {},
        resolveNotice: (LocalizedText) -> String = { it.resolve() },
    ) = NativeMemoryImageActions(assets, scope, onNotice, onFailure, resolveNotice, Dispatchers.Unconfined)

    private fun opened(name: String, bytes: ByteArray) = AssetOpenResult.Opened(DocumentAsset(name, "application/octet-stream", bytes))

    private fun memory(addressBits: Int = 2, dataBits: Int = 8, label: String = "") = CircuitComponent(
        "memory", ComponentKind.ROM, GridPoint(0, 0), label,
        mapOf("addrWidth" to addressBits.toString(), "dataWidth" to dataBits.toString()),
    )

    private class FakeAssets : DocumentAssetActions {
        var requestedMimeTypes: List<String>? = null
        var maximumBytes: Int? = null
        var openCalls = 0
        var created: DocumentAsset? = null
        private var openCallback: ((AssetOpenResult) -> Unit)? = null
        private var createCallback: ((AssetActionResult) -> Unit)? = null

        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) {
            openCalls++
            requestedMimeTypes = mimeTypes
            this.maximumBytes = maximumBytes
            openCallback = onResult
        }

        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) {
            created = asset
            createCallback = onResult
        }

        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = error("Unexpected share operation")
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = error("Unexpected print operation")

        fun finishOpen(result: AssetOpenResult) {
            val callback = assertNotNull(openCallback)
            openCallback = null
            callback(result)
        }

        fun finishCreate(result: AssetActionResult) {
            val callback = assertNotNull(createCallback)
            createCallback = null
            callback(result)
        }
    }
}
