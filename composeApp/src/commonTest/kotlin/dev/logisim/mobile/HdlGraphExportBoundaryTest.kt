package dev.logisim.mobile

import dev.logisim.mobile.core.HdlSourceFile
import dev.logisim.mobile.core.localization.LocalizedText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HdlGraphExportBoundaryTest {
    @Test
    fun rawRecoveryDoesNotWriteHdlFromTheEmptyPlaceholder() {
        val actions = Assets()
        var notice: LocalizedText? = null
        saveHdlSource(actions, HdlSourceFile("main.v", "module main; endmodule"), {}, { notice = it },
            ProjectGraphExportPolicy(EditorProjectAccess.PRESERVED_RAW))
        assertNull(actions.asset)
        assertEquals("project_graph_unavailable", notice?.key)
    }

    @Test
    fun supportedReadOnlyProjectCanCreateTheExactHdlArtifact() {
        val actions = Assets()
        val source = HdlSourceFile("folder/main.vhd", "entity main is end main;")
        saveHdlSource(actions, source, {}, { error("unexpected failure") },
            ProjectGraphExportPolicy(EditorProjectAccess.READ_ONLY))
        assertEquals("main.vhd", actions.asset?.name)
        assertEquals("text/x-vhdl", actions.asset?.mimeType)
        assertContentEquals("entity main is end main;".encodeToByteArray(), actions.asset?.bytes)
    }

    @Test
    fun completionForAnOlderDocumentDoesNotPublishStatusInTheNewOne() {
        val actions = Assets()
        var identity = 7L
        var notice: LocalizedText? = null
        var failure: LocalizedText? = null
        shareHdlSource(actions, HdlSourceFile("main.v", "module main; endmodule"),
            { notice = it }, { failure = it }, ProjectGraphExportPolicy(EditorProjectAccess.EDITABLE) { identity == 7L })
        assertEquals("main.v", actions.asset?.name)
        identity = 8
        actions.completed!!.invoke(AssetActionResult.Completed)
        assertNull(notice)
        assertNull(failure)
    }

    private class Assets : DocumentAssetActions {
        var asset: DocumentAsset? = null
        var completed: ((AssetActionResult) -> Unit)? = null
        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) = Unit
        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) {
            this.asset = asset; completed = onResult
        }
        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) {
            this.asset = asset; completed = onResult
        }
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
    }
}
