package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VisualExportToolsTest {
    @Test
    fun savePassesExactFilenameMimeAndImmutableContentToNativeAdapter() {
        val native = RecordingAssets()
        val bytes = byteArrayOf(1, 2, 3)
        val payload = DocumentAsset("soma.png", "image/png", bytes)
        bytes[0] = 99
        payload.bytes[1] = 99
        var result: AssetActionResult? = null
        native.visualExportDelivery().deliver(payload, VisualExportAction.SAVE) { result = it }
        assertEquals("save", native.action)
        assertEquals("soma.png", native.asset?.name)
        assertEquals("image/png", native.asset?.mimeType)
        assertContentEquals(byteArrayOf(1, 2, 3), native.asset?.bytes)
        assertEquals(AssetActionResult.Completed, result)
    }

    @Test
    fun shareCancellationRemainsCancellation() {
        val native = RecordingAssets(result = AssetActionResult.Cancelled)
        var result: AssetActionResult? = null
        native.visualExportDelivery().deliver(DocumentAsset("soma.svg", "image/svg+xml", byteArrayOf(60, 115, 118, 103, 62)), VisualExportAction.SHARE) { result = it }
        assertEquals("share", native.action)
        assertEquals("image/svg+xml", native.asset?.mimeType)
        assertEquals(AssetActionResult.Cancelled, result)
    }

    @Test
    fun onlyPdfIsPassedToNativePrinting() {
        val native = RecordingAssets(supportsPrint = true)
        val delivery = native.visualExportDelivery()
        assertTrue(delivery.supports(VisualExportAction.PRINT))
        var result: AssetActionResult? = null
        delivery.deliver(DocumentAsset("soma.png", "image/png", byteArrayOf(1)), VisualExportAction.PRINT) { result = it }
        assertEquals(null, native.action)
        assertIs<AssetActionResult.Failure>(result)
        delivery.deliver(DocumentAsset("soma.pdf", "application/pdf", byteArrayOf(37, 80, 68, 70)), VisualExportAction.PRINT) { result = it }
        assertEquals("print", native.action)
        assertEquals("soma.pdf", native.asset?.name)
        assertContentEquals(byteArrayOf(37, 80, 68, 70), native.asset?.bytes)
        assertEquals(AssetActionResult.Completed, result)
    }

    @Test
    fun unsupportedNativePrintingIsDisabledAndCannotBeDispatched() {
        val native = RecordingAssets()
        val delivery = native.visualExportDelivery()
        assertFalse(delivery.supports(VisualExportAction.PRINT))
        var result: AssetActionResult? = null
        delivery.deliver(DocumentAsset("soma.pdf", "application/pdf", byteArrayOf(1)), VisualExportAction.PRINT) { result = it }
        assertEquals(null, native.action)
        assertIs<AssetActionResult.Failure>(result)
    }

    @Test
    fun changingFormatReplacesKnownExtensionAndPreservesUnicodeName() {
        assertEquals("Adição.pdf", visualExportFilename("  Adição.PNG  ", VisualExportFormat.PDF))
        assertEquals("SAP1.v2.svg", visualExportFilename("SAP1.v2", VisualExportFormat.SVG))
        assertEquals("programa.png", visualExportFilename("programa", VisualExportFormat.PNG))
        for (invalid in listOf("", "  ", ".", "..", ".png", "folder/image", "folder\\image", "broken\nname")) {
            assertFailsWith<LocalizedArgumentException> { visualExportFilename(invalid, VisualExportFormat.PNG) }
        }
    }

    @Test
    fun batchNamesUseCircuitNamesWithoutAliasingAfterSanitization() {
        assertEquals(listOf("Main.gif", "main-2.gif", "north_gate.gif", "north_gate-2.gif", "calculo.v2.gif"),
            visualBatchFilenames(listOf("Main", "main", "north/gate", "north_gate", "calculo.v2"), VisualExportFormat.GIF))
        assertEquals("drawing.jpg", visualExportFilename("drawing.jpeg", VisualExportFormat.JPEG))
    }

    @Test
    fun batchSaveUsesOneNativeRequestAndReturnsProviderFailure() {
        val failure = AssetActionResult.Failure(dev.logisim.mobile.core.localization.LocalizedText.literal("B.gif: full"))
        val native = RecordingAssets(result = failure, supportsBatchCreate = true)
        val delivery = native.visualExportDelivery()
        val assets = listOf(DocumentAsset("A.gif", "image/gif", byteArrayOf(1)), DocumentAsset("B.gif", "image/gif", byteArrayOf(2)))
        var result: AssetActionResult? = null
        assertTrue(delivery.supportsBatchSave)
        delivery.saveBatch(assets) { result = it }
        assertEquals(assets, native.batch)
        assertEquals(null, native.action)
        assertEquals(failure, result)
    }

    private class RecordingAssets(
        override val supportsPrint: Boolean = false,
        val result: AssetActionResult = AssetActionResult.Completed,
        override val supportsBatchCreate: Boolean = false,
    ) : DocumentAssetActions {
        var batch: List<DocumentAsset>? = null
        var action: String? = null
        var asset: DocumentAsset? = null
        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) = error("No import is expected during export")
        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = record("save", asset, onResult)
        override fun createBatch(assets: List<DocumentAsset>, onResult: (AssetActionResult) -> Unit) {
            batch = assets
            onResult(result)
        }
        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = record("share", asset, onResult)
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = record("print", asset, onResult)
        private fun record(action: String, asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) {
            this.action = action
            this.asset = asset
            onResult(result)
        }
    }
}
