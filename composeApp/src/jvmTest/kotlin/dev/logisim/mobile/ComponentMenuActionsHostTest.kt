package dev.logisim.mobile

import dev.logisim.mobile.core.ArgbImage
import dev.logisim.mobile.core.ComponentMenuItem
import dev.logisim.mobile.core.EmbeddedImage
import dev.logisim.mobile.core.ImageMenu
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
