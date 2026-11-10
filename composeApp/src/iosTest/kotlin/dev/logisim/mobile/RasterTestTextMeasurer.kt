package dev.logisim.mobile

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

@OptIn(InternalComposeUiApi::class)
internal fun createRasterTextMeasurer(): TextMeasurer {
    // Offscreen tests bypass the UIKit host that normally registers graphics and text.
    registerSkikoComposeImplementation()
    return TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
}
