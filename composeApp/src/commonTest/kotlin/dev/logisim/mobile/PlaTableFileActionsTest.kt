package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.GatesLibrary
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlaTableFileActionsTest {
    private fun pla(label: String = "", inputs: Int = 3, outputs: Int = 2) = CircuitComponent("pla", GatesLibrary.Pla,
        GridPoint(0, 0), label, mapOf("in_width" to inputs.toString(), "out_width" to outputs.toString()))

    @Test
    fun exportsTheEditedTableInEvolutionFormat() {
        val assets = Assets()
        PlaTableFileActions(listOf(pla("decoder")), assets).export("1x0 10 # a\n011 01\n") { _, _ -> error("failure") }
        val created = assertNotNull(assets.created)
        assertEquals("decoder.txt", created.name)
        assertEquals("text/plain", created.mimeType)
        assertEquals("# Logisim PLA program table\n1x0 10 # a\n011 01\n", created.bytes.decodeToString())

        var failure: Pair<String, LocalizedText>? = null
        PlaTableFileActions(listOf(pla()), assets).export("") { title, notice -> failure = title to notice }
        assertEquals("pla.txt", assets.created?.name)
        assets.createResult(AssetActionResult.Failure(message("asset_write_failed")))
        assertEquals("pla_save_error_title", failure?.first)
    }

    @Test
    fun importsOnlyAFileWithThePlaWidths() {
        val assets = Assets()
        val actions = PlaTableFileActions(listOf(pla()), assets)
        var loaded: String? = null
        var failure: Pair<String, LocalizedText>? = null
        actions.import({ loaded = it }) { title, notice -> failure = title to notice }
        assertEquals(listOf("text/plain", "application/octet-stream"), assets.mimeTypes)
        assets.openResult(AssetOpenResult.Opened(DocumentAsset("t.txt", "text/plain",
            "# Logisim PLA program table\n1x0 10\n".encodeToByteArray())))
        assertEquals("1x0 10\n", loaded)
        assertNull(failure)

        loaded = null
        actions.import({ loaded = it }) { title, notice -> failure = title to notice }
        assets.openResult(AssetOpenResult.Opened(DocumentAsset("t.txt", "text/plain", "10 1\n".encodeToByteArray())))
        assertNull(loaded)
        assertEquals("pla_load_error_title", failure?.first)
        assertEquals("pla_unexpected_input_width", failure?.second?.key)

        failure = null
        actions.import({ loaded = it }) { title, notice -> failure = title to notice }
        assets.openResult(AssetOpenResult.Cancelled)
        assertNull(loaded)
        assertNull(failure)

        assertFalse(PlaTableFileActions(listOf(pla(inputs = 3), pla(inputs = 4)), assets).canImport)
    }

    private class Assets : DocumentAssetActions {
        var created: DocumentAsset? = null
        var mimeTypes: List<String>? = null
        private var open: ((AssetOpenResult) -> Unit)? = null
        private var create: ((AssetActionResult) -> Unit)? = null
        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) {
            this.mimeTypes = mimeTypes; open = onResult
        }
        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) { created = asset; create = onResult }
        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = error("share")
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = error("print")
        fun openResult(result: AssetOpenResult) = assertNotNull(open).invoke(result)
        fun createResult(result: AssetActionResult) = assertNotNull(create).invoke(result)
    }
}
