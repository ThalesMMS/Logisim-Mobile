package dev.logisim.mobile

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import dev.logisim.mobile.core.AnnotationLibrary
import dev.logisim.mobile.core.AnnotationVisuals
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.VisualItem
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.VisualScene

/** Use the font engine's extents for clipping and exports; document geometry remains desktop compatible. */
fun TextMeasurer.annotationVisualItem(component: CircuitComponent): VisualItem {
    val item = AnnotationVisuals.item(component)
    val text = item.primitives.singleOrNull() as? VisualPrimitive.Text ?: return item
    return item.copy(bounds = visualTextBounds(text))
}

/** Returns false for electrical components, allowing the host renderer to continue its normal dispatch. */
fun DrawScope.drawAnnotationComponent(component: CircuitComponent, textMeasurer: TextMeasurer, imageDecoder: VisualImageDecoder): Boolean {
    if (!AnnotationLibrary.isAnnotation(component)) return false
    drawVisualScene(VisualScene(listOf(textMeasurer.annotationVisualItem(component))), textMeasurer, imageDecoder)
    return true
}
