package dev.logisim.mobile

/**
 * Evolution's TextColorAttribute and TextColorChooser (d58a61e0): a Text color and TEXT_TOOL_COLOR are
 * stored as the light-theme reference. In the dark theme they are shown, and typed, as the luma-inverted
 * color the canvas draws (ColorUtil.getThemeTextColor); the stored value is the inverse of the typed one.
 */
internal object ThemeTextColor {
    private val pattern = Regex("#([0-9a-fA-F]{6})([0-9a-fA-F]{2})?")

    /** ColorUtil.getLuminanceInvertedColor of a 0xRRGGBB value. */
    fun invertRgb(rgb: Int): Int = (luminanceInvertedColor(0xFF000000u or (rgb and 0xFFFFFF).toUInt()) and 0xFFFFFFu).toInt()

    /** TextColorAttribute.toDisplayString: [stored] as the theme shows it; malformed values are left alone. */
    fun display(stored: String, dark: Boolean): String {
        if (!dark) return stored
        val match = pattern.matchEntire(stored.trim()) ?: return stored
        return hex(invertRgb(match.groupValues[1].toInt(16))) + match.groupValues[2]
    }

    /**
     * TextColorChooser.getStoredColor: the value to store for a typed [displayed] color. When the RGB of
     * [displayed] matches what [stored] shows, the stored RGB is kept (inversion is not exact after clipping)
     * and only the alpha suffix follows the input.
     */
    fun store(displayed: String, stored: String?, dark: Boolean): String {
        if (!dark) return displayed
        val match = pattern.matchEntire(displayed.trim()) ?: return displayed
        val rgb = match.groupValues[1].toInt(16)
        val original = stored?.let { pattern.matchEntire(it.trim()) }?.groupValues?.get(1)?.toInt(16)
        val reference = if (original != null && invertRgb(original) == rgb) original else invertRgb(rgb)
        return hex(reference) + match.groupValues[2]
    }

    private fun hex(rgb: Int): String = "#" + rgb.toString(16).padStart(6, '0')
}
