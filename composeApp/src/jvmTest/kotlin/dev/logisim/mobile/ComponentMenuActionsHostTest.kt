package dev.logisim.mobile

import dev.logisim.mobile.core.ArgbImage
import dev.logisim.mobile.core.ComponentMenuItem
import dev.logisim.mobile.core.EmbeddedImage
import dev.logisim.mobile.core.ImageMenu
import dev.logisim.mobile.core.MemoryImage
import dev.logisim.mobile.core.MemoryImageCodec
import dev.logisim.mobile.core.MemoryImageFormat
import dev.logisim.mobile.core.MemoryImageImportResult
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Image and PLA ROM menu items through EditorState (#359), decoding and encoding the PNG with Skia as iOS does.
 * scripts/oracle/base-tools compares ImageMenu's pixels with Evolution's ImageUtil.
 */
class ComponentMenuActionsHostTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "scripts/oracle/base-tools").isDirectory }
    private val badge = File(root, "scripts/oracle/base-tools/badge.png").readBytes()
    private val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(badge)

    private fun editor(width: Int, height: Int) = EditorState("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Base" name="6"/>
  <lib desc="#Input/Output-Extra" name="8"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="6" loc="(100,100)" name="Image">
      <a name="image" val="$dataUri"/>
      <a name="width" val="$width"/>
      <a name="height" val="$height"/>
    </comp>
    <comp lib="8" loc="(400,100)" name="PlaRom">
      <a name="Contents" val="1*3"/>
    </comp>
  </circuit>
</project>""")

    private fun EditorState.image() = document.components.first { it.kind == dev.logisim.mobile.core.AnnotationLibrary.Image }
    private fun stored(component: dev.logisim.mobile.core.CircuitComponent): ArgbImage = assertNotNull(annotationMenuImage(component))

    @Test
    fun resetSizeAndTransparencyAreUndoableEdits() {
        val state = editor(40, 30)
        val id = state.image().id
        val original = stored(state.image())
        assertNull(state.applyImageMenu(ComponentMenuItem.IMAGE_RESET_SIZE, id))
        assertEquals(listOf("48", "32"), listOf(state.image().attributes["width"], state.image().attributes["height"]))
        state.undo()
        assertEquals(listOf("40", "30"), listOf(state.image().attributes["width"], state.image().attributes["height"]))

        assertNull(state.applyImageMenu(ComponentMenuItem.IMAGE_MAKE_TRANSPARENT, id))
        val transparent = stored(state.image())
        val expected = ImageMenu.makeWhiteTransparent(original)
        // PNG keeps straight alpha; a fully transparent pixel's colour is not part of the comparison.
        assertContentEquals(expected.pixels.map { if (it ushr 24 == 0) 0 else it }, transparent.pixels.map { if (it ushr 24 == 0) 0 else it })
        assertNotEquals(original.pixels.toList(), transparent.pixels.toList())
        state.undo()
        assertContentEquals(original.pixels, stored(state.image()).pixels)
    }

    @Test
    fun optimizeConfirmsAResizeAndReportsAnOptimizedImage() {
        val state = editor(40, 30)
        val id = state.image().id
        val original = stored(state.image())
        val pending = assertNotNull(state.applyImageMenu(ComponentMenuItem.IMAGE_OPTIMIZE, id))
        assertEquals(ImageMenu.Optimization.Resize(48, 32, 40, 26, 40 to 26), pending.resize)
        state.confirmImageOptimization(pending)
        assertEquals(listOf("40", "26"), listOf(state.image().attributes["width"], state.image().attributes["height"]))
        val optimized = stored(state.image())
        assertEquals(40 to 26, optimized.width to optimized.height)
        assertContentEquals(ImageMenu.optimize(original, 40, 26, "fit").pixels, optimized.pixels)
        assertEquals(EmbeddedImage.fromDataUri(state.image().attributes.getValue("image")).mimeType, "image/png")

        val roomy = editor(64, 64)
        val before = roomy.image()
        assertNull(roomy.applyImageMenu(ComponentMenuItem.IMAGE_OPTIMIZE, before.id))
        assertEquals(before, roomy.image())
        assertEquals("The image is already optimized for its current frame size (48x32).", roomy.statusText.resolve())
    }

    /**
     * Load image… on a ROM (MemMenu.doLoad -> HexFile.open, which auto-detects the format). App.kt's MEMORY_LOAD
     * passes HEX_WORDS_ADDRESSED and applies a Success through applyMemoryEditorImage; this mirrors that path with a
     * Logisim 2.7.1 "v2.0 raw" program file, which used to fail with "The header does not match the selected format."
     */
    @Test
    fun memoryLoadDetectsTheHeaderOfAV2RawProgram() {
        val state = EditorState("""<?xml version="1.0" encoding="UTF-8"?>
<project source="2.7.1" version="1.0">
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="4" loc="(200,100)" name="ROM"><a name="addrWidth" val="4"/><a name="dataWidth" val="8"/></comp>
  </circuit>
</project>""")
        val rom = state.document.components.single()
        val notices = mutableListOf<LocalizedText>()
        val assets = object : DocumentAssetActions {
            override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) =
                onResult(AssetOpenResult.Opened(DocumentAsset("program.hex", "text/plain", "v2.0 raw\n0a ff\n".encodeToByteArray())))
            override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = error("create")
            override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = error("share")
            override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = error("print")
        }
        val files = NativeMemoryImageActions(assets, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onFailure = { notices += it }, workerDispatcher = Dispatchers.Unconfined)
        val context = EditorToolContext(state.project, state.document, state.simulation, listOf(rom),
            { state.applyProject(it) }, { state.applyDocument(it) }, state::selectCircuit, { state.selectComponent(it) },
            state::reportStatus, documentIdentity = state.documentIdentity,
            sendComponentEvent = { id, event -> state.sendComponentEvent(id, event) }, showNotice = state::reportStatus)
        var format: MemoryImageFormat? = null
        files.importImage(rom, MemoryImageFormat.HEX_WORDS_ADDRESSED) { result ->
            when (result) {
                is MemoryImageImportResult.Success -> runCatching {
                    format = result.format
                    if (!applyMemoryEditorImage(context, rom.id, result.image, importedImage = result)) notices += message("memory_tool_not_applied")
                }.onFailure { notices += it.localizedDescription(message("memory_tool_invalid")) }
                is MemoryImageImportResult.Failure -> notices += LocalizedText.join(result.diagnostics.map { it.notice })
                null -> Unit
            }
        }
        println("MEMORY_LOAD v2.0 raw: format=$format notices=${notices.map { it.resolve() }}")
        assertEquals(emptyList<String>(), notices.map { it.resolve() })
        assertEquals(MemoryImageFormat.RAW, format)
        assertEquals(MemoryImageCodec.encodeCirc(MemoryImage(4, 8, mapOf(0L to 0x0auL, 1L to 0xffuL))),
            state.document.components.single().attributes["contents"])
    }

    @Test
    fun plaRomClearEmptiesTheMatrixOnce() {
        val state = editor(40, 30)
        val rom = state.document.components.first { it.kind == dev.logisim.mobile.core.ExtraIoKinds.PlaRom }
        assertEquals(true, state.updateComponentAttributes(rom.id, mapOf("Contents" to ""), dev.logisim.mobile.core.localization.LocalizedText.literal("clear")))
        assertEquals("", state.document.components.first { it.id == rom.id }.attributes["Contents"].orEmpty())
        assertEquals(false, state.updateComponentAttributes(rom.id, mapOf("Contents" to ""), dev.logisim.mobile.core.localization.LocalizedText.literal("clear")))
        state.undo()
        assertEquals("1*3", state.document.components.first { it.id == rom.id }.attributes["Contents"])
    }
}
