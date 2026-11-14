package dev.logisim.mobile

import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColorPickerModelTest {
    private fun assertRoundTrip(rgb: Int, alpha: Int? = null) {
        val model = ColorPickerModel(rgb, alpha)
        assertEquals(model, ColorPickerModel.fromHsv(model.toHsv(), alpha), "#" + rgb.toString(16).padStart(6, '0'))
    }

    @Test
    fun everyDefaultColorSurvivesHueSaturationAndValue() {
        for (highContrast in listOf(false, true)) for (role in EditorColor.entries) {
            assertRoundTrip(role.defaultColor(highContrast).toArgb() and 0xFFFFFF)
        }
        DarkEditorColors.values.forEach { assertRoundTrip(it) }
        (PALETTE + MATERIAL_SWATCHES).forEach { assertRoundTrip(ColorPickerModel.parse(it)!!.rgb, 0x80) }
    }

    @Test
    fun sampledRgbCubeRoundTrips() {
        for (r in 0..255 step 15) for (g in 0..255 step 17) for (b in 0..255 step 5) assertRoundTrip((r shl 16) or (g shl 8) or b)
        for (channel in 0..255) {
            assertRoundTrip(channel shl 16)
            assertRoundTrip((channel shl 8) or 255)
            assertRoundTrip((channel shl 16) or (channel shl 8) or channel)
        }
    }

    @Test
    fun hueSaturationAndValueAreTheUsualOnes() {
        assertEquals(Hsv(0.0, 0.0, 0.0), ColorPickerModel(0).toHsv())
        assertEquals(Hsv(0.0, 1.0, 1.0), ColorPickerModel(0xff0000).toHsv())
        assertEquals(Hsv(120.0, 1.0, 1.0), ColorPickerModel(0x00ff00).toHsv())
        assertEquals(Hsv(240.0, 1.0, 1.0), ColorPickerModel(0x0000ff).toHsv())
        assertEquals(0xff8000, ColorPickerModel.fromHsv(Hsv(30.0, 1.0, 1.0)).rgb)
        assertEquals(0xff0000, ColorPickerModel.fromHsv(Hsv(360.0, 1.0, 1.0)).rgb)
    }

    @Test
    fun hexIsLowerCaseWithTheAlphaLast() {
        assertEquals("#00d200", ColorPickerModel.parse("#00D200")!!.hex)
        assertEquals(ColorPickerModel(0x00d200), ColorPickerModel.parse("00d200"))
        val translucent = ColorPickerModel.parse("#f0000080")!!
        assertEquals(ColorPickerModel(0xf00000, 0x80), translucent)
        assertEquals("#f0000080", translucent.hex)
        assertEquals("#f00000", translucent.rgbHex)
        assertEquals("#000000", ColorPickerModel(0).hex)
        assertEquals("#00000000", ColorPickerModel(0, 0).hex)
        // The preferences store the same RGB that the picker returns (editorColorHex keeps its own upper-case form).
        assertEquals(parseEditorColor(editorColorHex(0x00d200)), ColorPickerModel.parse(editorColorHex(0x00d200))!!.rgb)
        listOf("", "#", "#12345", "#1234567", "#12345g", "#123456789", "red").forEach { assertNull(ColorPickerModel.parse(it), it) }
    }

    @Test
    fun aMarkOnADarkSwatchIsWhite() {
        assertEquals(true, ColorPickerModel(0x000080).isDark)
        assertEquals(false, ColorPickerModel(0xffff00).isDark)
    }

    @Test
    fun everySwatchHasASpokenName() {
        assertEquals((PALETTE + MATERIAL_SWATCHES).toSet(), SWATCH_NAMES.keys)
        // A key missing from the catalog resolves to itself.
        SWATCH_NAMES.values.forEach { key ->
            val english = dev.logisim.mobile.core.localization.message(key).resolve(dev.logisim.mobile.core.localization.AppLanguage.ENGLISH)
            assertEquals(true, english != key && english.isNotBlank(), key)
        }
    }
}
