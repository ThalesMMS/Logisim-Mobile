package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.MemoryComponents
import dev.logisim.mobile.core.MemoryImage
import dev.logisim.mobile.core.MemoryImageDiagnostic
import dev.logisim.mobile.core.MemoryImageFormat
import dev.logisim.mobile.core.MemoryImageImportResult
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
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

    /** HexFile.open auto-detects: the header names the format, whatever format the caller would use without one. */
    @Test
    fun headeredFilesDecodeInTheFormatTheirHeaderNames() {
        val bytesLittle = "v3.0 hex bytes plain little-endian\n34 12 cd ab\n"
        val files = listOf(
            "v2.0 raw\n0a 2*0 ff\n" to (MemoryImageFormat.RAW to mapOf(0L to 0x0auL, 3L to 0xffuL)),
            "v3.0 hex words plain\n1 2\n" to (MemoryImageFormat.HEX_WORDS_PLAIN to mapOf(0L to 1uL, 1L to 2uL)),
            bytesLittle to (MemoryImageFormat.HEX_BYTES_PLAIN_LITTLE to mapOf(0L to 0x1234uL, 1L to 0xabcduL)),
        )
        for ((text, expected) in files) {
            val dataBits = if (expected.first == MemoryImageFormat.HEX_BYTES_PLAIN_LITTLE) 16 else 8
            // The canvas "Load image…" path passes HEX_WORDS_ADDRESSED (App.kt MEMORY_LOAD).
            val assets = FakeAssets()
            var received: MemoryImageImportResult? = null
            actions(assets).importImage(memory(dataBits = dataBits), MemoryImageFormat.HEX_WORDS_ADDRESSED) { received = it }
            assets.finishOpen(opened("program.hex", text.encodeToByteArray()))
            val imported = assertIs<MemoryImageImportResult.Success>(received, text)
            assertEquals(expected.first, imported.format)
            assertEquals(expected.second, imported.image.cells)

            val chooserAssets = FakeAssets()
            var outcome: MemoryImportOutcome? = null
            actions(chooserAssets).importImageWithChooser(memory(dataBits = dataBits)) { outcome = it }
            chooserAssets.finishOpen(opened("program.hex", text.encodeToByteArray()))
            val chosen = assertIs<MemoryImportOutcome.Imported>(outcome, text).result
            assertEquals(expected.first, chosen.format)
            assertEquals(expected.second, chosen.image.cells)
        }
    }

    @Test
    fun headerlessDataAsksForAFormatAndDecodesTheKeptBytes() {
        val assets = FakeAssets()
        val component = memory(dataBits = 16)
        val actions = actions(assets)
        var outcome: MemoryImportOutcome? = null
        actions.importImageWithChooser(component) { outcome = it }
        val bytes = byteArrayOf(0x12, 0x34, 0xab.toByte(), 0xcd.toByte())
        assets.finishOpen(opened("data.bin", bytes))
        val needsFormat = assertIs<MemoryImportOutcome.NeedsFormat>(outcome)
        assertContentEquals(bytes, needsFormat.bytes)
        assertEquals(listOf("memory_codec_invalid_utf8"), needsFormat.diagnostics.map { it.detail.key })
        var decoded: MemoryImageImportResult? = null
        actions.decodeImage(component, needsFormat.bytes, MemoryImageFormat.BINARY_BIG) { decoded = it }
        val imported = assertIs<MemoryImageImportResult.Success>(decoded)
        assertEquals(MemoryImageFormat.BINARY_BIG, imported.format)
        assertEquals(mapOf(0L to 0x1234uL, 1L to 0xabcduL), imported.image.cells)

        // Headerless text also asks; an unknown v3.0 option is a decode error, not a format question.
        val textAssets = FakeAssets()
        actions(textAssets).importImageWithChooser(memory()) { outcome = it }
        textAssets.finishOpen(opened("words.txt", "1 2 3\n".encodeToByteArray()))
        assertEquals(listOf("memory_codec_missing_format"), assertIs<MemoryImportOutcome.NeedsFormat>(outcome).diagnostics.map { it.detail.key })
        val badAssets = FakeAssets()
        actions(badAssets).importImageWithChooser(memory()) { outcome = it }
        badAssets.finishOpen(opened("nibbles.hex", "v3.0 hex nibbles\n1\n".encodeToByteArray()))
        assertIs<MemoryImportOutcome.Failed>(outcome)
        val cancelAssets = FakeAssets()
        actions(cancelAssets).importImageWithChooser(memory()) { outcome = it }
        cancelAssets.finishOpen(AssetOpenResult.Cancelled)
        assertEquals(MemoryImportOutcome.Cancelled, outcome)
    }

    /** "v2.1 raw" reads as a header in every text format, so only Binary or ASCII can load it, and the chooser starts there. */
    @Test
    fun anUnsupportedHeaderStartsTheChooserOnBinary() {
        val bytes = "v2.1 raw\n0a\n".encodeToByteArray()
        val assets = FakeAssets()
        var outcome: MemoryImportOutcome? = null
        actions(assets).importImageWithChooser(memory()) { outcome = it }
        assets.finishOpen(opened("future.hex", bytes))
        val pending = assertIs<MemoryImportOutcome.NeedsFormat>(outcome)
        assertEquals(listOf("memory_codec_header_unsupported"), pending.diagnostics.map { it.detail.key })
        assertEquals(MemoryImageFormat.BINARY_BIG, initialChoice(pending))
        val failure = MemoryImageImportResult.Failure(pending.diagnostics)
        assertEquals(listOf(MemoryImageFormat.BINARY_BIG, MemoryImageFormat.BINARY_LITTLE, MemoryImageFormat.ASCII_BIG,
            MemoryImageFormat.ASCII_LITTLE), MemoryImageFormat.entries.filter(failure::decodableAs))

        // The canvas path keeps the header diagnostic for a text format and loads the bytes as Binary.
        for ((format, expected) in listOf(MemoryImageFormat.HEX_WORDS_ADDRESSED to null, MemoryImageFormat.BINARY_BIG to 0x76uL)) {
            val canvasAssets = FakeAssets()
            var received: MemoryImageImportResult? = null
            // Sixteen words hold the twelve bytes of the file.
            actions(canvasAssets).importImage(memory(addressBits = 4), format) { received = it }
            canvasAssets.finishOpen(opened("future.hex", bytes))
            if (expected == null) {
                assertEquals(listOf("memory_codec_header_unsupported"),
                    assertIs<MemoryImageImportResult.Failure>(received).diagnostics.map { it.detail.key })
            } else {
                assertEquals(expected, assertIs<MemoryImageImportResult.Success>(received).image[0])
            }
        }
        // Headerless text still starts on hex words.
        val headerless = MemoryImportOutcome.NeedsFormat("1 2\n".encodeToByteArray(),
            listOf(MemoryImageDiagnostic(1, message(Messages.MemoryCodecMissingFormat))))
        assertEquals(MemoryImageFormat.HEX_WORDS_PLAIN, initialChoice(headerless))
    }

    @Test
    fun headerlessTextOnTheCanvasPathStillUsesTheRequestedFormat() {
        val assets = FakeAssets()
        var received: MemoryImageImportResult? = null
        actions(assets).importImage(memory(addressBits = 4), MemoryImageFormat.HEX_WORDS_ADDRESSED) { received = it }
        assets.finishOpen(opened("memory.txt", "03: aa\n".encodeToByteArray()))
        val imported = assertIs<MemoryImageImportResult.Success>(received)
        assertEquals(MemoryImageFormat.HEX_WORDS_ADDRESSED, imported.format)
        assertEquals(mapOf(3L to 0xaauL), imported.image.cells)
    }

    @Test
    fun aClosedWindowIgnoresLateChooserAndDecodeResults() {
        val assets = FakeAssets()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = actions(assets, scope)
        var callbacks = 0
        actions.importImageWithChooser(memory()) { callbacks++ }
        scope.cancel()
        assets.finishOpen(opened("memory.hex", "v2.0 raw\n1\n".encodeToByteArray()))
        actions.decodeImage(memory(), byteArrayOf(1), MemoryImageFormat.BINARY_BIG) { callbacks++ }
        assertEquals(0, callbacks)
    }

    @Test
    fun unlabeledMemoriesExportUnderTheirLocalizedKind() {
        val assets = FakeAssets()
        val dual = CircuitComponent("dual", MemoryComponents.DualRam, GridPoint(0, 0), "",
            mapOf("addrWidth" to "2", "dataWidth" to "8"))
        actions(assets).exportImage(dual, MemoryImageFormat.HEX_WORDS_ADDRESSED, "v3.0 hex words addressed\n".encodeToByteArray())
        assertEquals("Dual-port RAM.hex", assertNotNull(assets.created).name)
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
