package dev.logisim.mobile

import dev.logisim.mobile.core.CompanionArtifact
import dev.logisim.mobile.core.CompanionClient
import dev.logisim.mobile.core.CompanionConnection
import dev.logisim.mobile.core.CompanionEndpoint
import dev.logisim.mobile.core.CompanionHttpRequest
import dev.logisim.mobile.core.CompanionHttpResponse
import dev.logisim.mobile.core.CompanionJob
import dev.logisim.mobile.core.CompanionJobState
import dev.logisim.mobile.core.CompanionJsonCodec
import dev.logisim.mobile.core.CompanionToken
import dev.logisim.mobile.core.CompanionTransport
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.FpgaBuildPlan
import dev.logisim.mobile.core.FpgaDevice
import dev.logisim.mobile.core.FpgaFlow
import dev.logisim.mobile.core.FpgaSourceFile
import dev.logisim.mobile.core.FpgaVendor
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AdditionalEditorToolsTest {
    @Test
    fun staticToolsIncludeRealVisualExportWithoutNativeAssets() {
        val visual = circuitVisualExportEditorTool(ComponentCatalog.registry, null)
        val tools = createAdditionalStaticEditorTools(null, AdditionalEditorToolsConnection(), {}, visual)
        assertEquals(listOf("legal-notices", "companion-pairing", "soc-debugger", "annotations", "appearance", "visual-export"), tools.map { it.id })
        assertSame(visual, tools.last())
        assertEquals(tools.size, tools.map { it.id }.distinct().size)
    }

    @Test
    fun concreteVisualExportToolIsIncludedWithoutRecreatingIt() {
        val visual = EditorTool("visual-export", "Export") { }
        val tools = createAdditionalStaticEditorTools(FakeAssets(), AdditionalEditorToolsConnection(), {}, visual)
        assertSame(visual, tools.last())
        assertEquals(listOf("legal-notices", "companion-pairing", "soc-debugger", "assembly-viewer", "annotations", "appearance", "visual-export"), tools.map { it.id })
    }

    @Test
    fun pairingACompanionKeepsTheOfflineFpgaMappingAndBoardText() {
        val tools = AdditionalFpgaToolsState()
        val mapping = tools.mapping
        mapping.boardXml.value = "<BoardInformation name=\"Configured offline\"/>"
        tools.bind(null)
        val client = companionClient(8042)
        val paired = tools.bind(client)
        assertSame(mapping, tools.mapping)
        assertEquals("<BoardInformation name=\"Configured offline\"/>", tools.mapping.boardXml.value)
        assertSame(client, paired.client)
    }

    @Test
    fun pendingBusyAndActiveFpgaWorkKeepsItsOriginalHostAndSession() {
        val tools = AdditionalFpgaToolsState()
        val originalClient = companionClient(8042)
        val nextClient = companionClient(8043)
        val original = tools.bind(originalClient)
        original.session.displayedPlan = fpgaPlan()
        assertSame(original, tools.bind(null))
        assertSame(original, tools.bind(nextClient))
        original.session.displayedPlan = null
        original.session.busy = true
        assertSame(original, tools.bind(null))
        assertSame(original, tools.bind(nextClient))
        original.session.busy = false
        original.session.job = fpgaJob(CompanionJobState.RUNNING)
        assertSame(original, tools.bind(null))
        assertSame(original, tools.bind(nextClient))
        assertSame(originalClient, tools.bind(nextClient).client)
    }

    @Test
    fun leavingFinishedFpgaWorkAllowsANewHostWithANewSessionAndTheSameMapping() {
        val tools = AdditionalFpgaToolsState()
        val original = tools.bind(companionClient(8042))
        val mapping = tools.mapping
        mapping.boardXml.value = "Retained board XML"
        original.session.displayedPlan = fpgaPlan()
        original.session.busy = true
        original.session.job = fpgaJob(CompanionJobState.RUNNING)
        val nextClient = companionClient(8043)
        assertSame(original, tools.bind(nextClient))
        original.session.displayedPlan = null
        original.session.busy = false
        original.session.job = fpgaJob(CompanionJobState.CANCELLED)
        val next = tools.bind(nextClient)
        assertSame(nextClient, next.client)
        assertNotSame(original.session, next.session)
        assertEquals(null, next.session.job)
        assertSame(mapping, tools.mapping)
        assertEquals("Retained board XML", tools.mapping.boardXml.value)
    }

    @Test
    fun programmingKeepsTheOriginalHostEvenWithoutAnOpenBuildPlan() {
        val tools = AdditionalFpgaToolsState()
        val originalClient = companionClient(8042)
        val nextClient = companionClient(8043)
        val original = tools.bind(originalClient)
        original.session.program.busy = true
        assertSame(original, tools.bind(null))
        assertSame(original, tools.bind(nextClient))
        original.session.program.busy = false
        original.session.program.job = fpgaJob(CompanionJobState.RUNNING, "fpga-program")
        assertSame(originalClient, tools.bind(nextClient).client)
        original.session.program.job = fpgaJob(CompanionJobState.CANCELLED, "fpga-program")
        val next = tools.bind(nextClient)
        assertSame(nextClient, next.client)
        assertNotSame(original.session, next.session)
    }

    @Test
    fun boardPickerRequestsBoundedXmlBytesAndPreservesUtf8Text() {
        val xml = "<BoardInformation name=\"Placa Ç\"/>"
        val assets = FakeAssets().apply {
            openResult = AssetOpenResult.Opened(DocumentAsset("board.xml", "application/xml", xml.encodeToByteArray()))
        }
        val picked = mutableListOf<String>()
        val failures = mutableListOf<LocalizedText>()
        pickFpgaBoard(assets, picked::add, failures::add)
        assertEquals(listOf("application/xml", "text/xml", "application/octet-stream"), assets.openMimeTypes)
        assertEquals(2_000_000, assets.openLimit)
        assertEquals(listOf(xml), picked)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun cancellingTheBoardPickerKeepsTheCurrentBoardAndReportsNoFailure() {
        val assets = FakeAssets()
        var picked = false
        var failed = false
        pickFpgaBoard(assets, { picked = true }, { failed = true })
        assertTrue(!picked && !failed)
    }

    @Test
    fun invalidUtf8AndNativePickerFailuresDoNotBecomeBoardText() {
        val assets = FakeAssets().apply {
            openResult = AssetOpenResult.Opened(DocumentAsset("invalid.xml", "application/xml", byteArrayOf(0xc3.toByte(), 0x28)))
        }
        val failures = mutableListOf<LocalizedText>()
        var picked = false
        pickFpgaBoard(assets, { picked = true }, failures::add)
        assertTrue(!picked)
        assertEquals("additional_fpga_board_utf8", failures.single().key)
        val nativeFailure = message("native-picker-failed")
        assets.openResult = AssetOpenResult.Failure(nativeFailure)
        pickFpgaBoard(assets, { picked = true }, failures::add)
        assertTrue(!picked)
        assertSame(nativeFailure, failures.last())
    }

    @Test
    fun savePassesTheAssetBytesAndMimeToNativeStorageBeforeReportingCompletion() {
        val assets = FakeAssets().apply { actionResult = AssetActionResult.Completed }
        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        val asset = DocumentAsset("circuit-fpga.zip", "application/zip", bytes)
        val notices = mutableListOf<LocalizedText>()
        val failures = mutableListOf<LocalizedText>()
        saveAdditionalAsset(assets, asset, notices::add, failures::add)
        assertEquals("circuit-fpga.zip", assets.created.single().name)
        assertEquals("application/zip", assets.created.single().mimeType)
        assertContentEquals(bytes, assets.created.single().bytes)
        assertEquals(message("status_file_saved", "name" to "circuit-fpga.zip"), notices.single())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun saveCancellationAndFailureNeverReportSuccess() {
        val assets = FakeAssets()
        val notices = mutableListOf<LocalizedText>()
        val failures = mutableListOf<LocalizedText>()
        val asset = DocumentAsset("project-fpga.zip", "application/zip", byteArrayOf(1))
        saveAdditionalAsset(assets, asset, notices::add, failures::add)
        assertTrue(notices.isEmpty() && failures.isEmpty())
        val failure = message("native-save-failed")
        assets.actionResult = AssetActionResult.Failure(failure)
        saveAdditionalAsset(assets, asset, notices::add, failures::add)
        assertTrue(notices.isEmpty())
        assertSame(failure, failures.single())
    }

    @Test
    fun generatedFpgaPlanExportsAsAZipWithTheOriginalSourcePathsAndUtf8Bytes() {
        val asset = fpgaBuildPlanAsset(fpgaPlan())
        assertEquals("user_top-fpga.zip", asset.name)
        assertEquals("application/zip", asset.mimeType)
        val files = storedZipFiles(asset.bytes)
        assertEquals(setOf("hierarchy/user_top.v"), files.keys)
        assertContentEquals("// Saída\nmodule user_top; endmodule\n".encodeToByteArray(), files.getValue("hierarchy/user_top.v"))
    }

    @Test
    fun companionArchiveRetainsBinaryBitstreamBytesAndRejectsDuplicateNames() {
        val artifact = CompanionArtifact.read(CompanionJsonCodec.parseObject("""
            {"name":"design.bit","size":5,"sha256":"0150a92bb1212cd00516b65fde0704614760000963874fcbb11eaa734ee87809","base64":"AAF/gP8="}
        """.trimIndent()))
        val asset = fpgaArtifactArchiveAsset(listOf(artifact))
        assertEquals("fpga-artifacts.zip", asset.name)
        assertEquals("application/zip", asset.mimeType)
        val files = storedZipFiles(asset.bytes)
        assertEquals(setOf("design.bit"), files.keys)
        assertContentEquals(byteArrayOf(0, 1, 127, -128, -1), files.getValue("design.bit"))
        assertFailsWith<IllegalArgumentException> { fpgaArtifactArchiveAsset(listOf(artifact, artifact)) }
    }

    private fun fpgaPlan() = FpgaBuildPlan(
        FpgaBoardDefinition("Export", FpgaDevice(FpgaVendor.OPEN_FPGA, "ice40", "hx1k", "tq144", "1"), null, emptyList()),
        FpgaFlow.OPEN_ICE40, "user_top",
        listOf(FpgaSourceFile("hierarchy/user_top.v", "// Saída\nmodule user_top; endmodule\n")),
        emptyList(), emptyMap(), emptyList(),
    )

    private fun fpgaJob(state: CompanionJobState, operation: String = "fpga-synthesize") = CompanionJob(
        "a".repeat(32), operation, state, CompanionJsonCodec.parse("null"), null, emptyList(),
    )

    private fun companionClient(port: Int) = CompanionClient(
        CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:$port"), CompanionToken.parse("test-token-with-at-least-32-characters")),
        transport = object : CompanionTransport {
            override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse = error("No network request is expected")
        },
    )

    private class FakeAssets : DocumentAssetActions {
        var openResult: AssetOpenResult = AssetOpenResult.Cancelled
        var actionResult: AssetActionResult = AssetActionResult.Cancelled
        var openMimeTypes: List<String>? = null
        var openLimit: Int? = null
        val created = mutableListOf<DocumentAsset>()
        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) {
            openMimeTypes = mimeTypes
            openLimit = maximumBytes
            onResult(openResult)
        }
        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) {
            created += asset
            onResult(actionResult)
        }
        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = error("No share is expected")
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = error("No print is expected")
    }

    private fun storedZipFiles(bytes: ByteArray): Map<String, ByteArray> {
        fun u16(index: Int): Int = (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8)
        fun u32(index: Int): Int = u16(index) or (u16(index + 2) shl 16)
        val files = linkedMapOf<String, ByteArray>()
        var offset = 0
        while (u32(offset) == 0x04034b50) {
            assertEquals(0, u16(offset + 8), "The exported ZIP stores source bytes without a lossy text conversion")
            val length = u32(offset + 18)
            val nameLength = u16(offset + 26)
            val dataOffset = offset + 30 + nameLength + u16(offset + 28)
            val name = bytes.copyOfRange(offset + 30, offset + 30 + nameLength).decodeToString()
            files[name] = bytes.copyOfRange(dataOffset, dataOffset + length)
            offset = dataOffset + length
        }
        assertEquals(0x02014b50, u32(offset))
        return files
    }
}
