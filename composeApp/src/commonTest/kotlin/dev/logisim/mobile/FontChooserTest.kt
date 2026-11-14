package dev.logisim.mobile

import dev.logisim.mobile.core.AnnotationFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontChooserTest {
    @Test
    fun aChosenFontIsTheStringThatUsedToBeTyped() {
        assertEquals("SansSerif bold 16", FontChoice("SansSerif", FontStyleChoice.BOLD, 16).serialized)
        assertEquals("Serif bolditalic 24", FontChoice("Serif", FontStyleChoice.BOLD_ITALIC, 24).serialized)
        assertEquals("Monospaced plain 8", FontChoice("Monospaced", FontStyleChoice.PLAIN, 8).serialized)
    }

    @Test
    fun everyFamilyStyleAndPresetRoundTripsThroughTheAttributeParser() {
        for (family in FONT_FAMILIES) for (style in FontStyleChoice.entries) for (size in FontChoice.SIZE_PRESETS + listOf(1, 256)) {
            val choice = FontChoice(family, style, size)
            assertEquals(choice, AnnotationFont.parse(choice.serialized)?.let(FontChoice::from))
            assertEquals(choice, FontChoice.parse(choice.serialized))
        }
    }

    @Test
    fun anUnknownFamilyIsKeptAsACustomEntry() {
        val arial = FontChoice.parse("Arial italic 10")!!
        assertEquals("Arial", arial.family)
        assertEquals("Arial italic 10", arial.serialized)
        assertTrue(FontChoice.isCustomFamily("Arial"))
        assertFalse(FontChoice.isCustomFamily("Dialog"))
        assertEquals(FONT_FAMILIES + "Arial", FontChoice.families("Arial"))
        assertEquals(FONT_FAMILIES, FontChoice.families("Serif"))
        assertEquals("DejaVu Sans", FontChoice.parse("DejaVu Sans bold 12")!!.family)
    }

    @Test
    fun theChooserReportsOnlyRealChanges() {
        assertNull(FontChoice.update("SansSerif plain 12", "SansSerif plain 12"))
        // An imported spelling stays as it was read unless the font itself changes.
        assertNull(FontChoice.update("SansSerif PLAIN 12", "SansSerif plain 12"))
        assertEquals("SansSerif bold 12", FontChoice.update("SansSerif plain 12", "SansSerif bold 12"))
        assertEquals("Arial plain 13", FontChoice.update("Arial plain 12", "Arial plain 13"))
        assertNull(FontChoice.update("SansSerif plain 12", "not a font"))
    }
}
