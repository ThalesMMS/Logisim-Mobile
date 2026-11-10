package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TextToolRasterTest {
    @Test
    fun renderedLabelOutsideThePinBodyIsAnEditTarget() {
        val registry = ComponentCatalog.registry
        val measurer = createRasterTextMeasurer()
        val pin = CircuitComponent("pin", ComponentKind.INPUT, GridPoint(120, 120), label = "Counter enable",
            attributes = mapOf("labelloc" to "west", "labelfont" to "Serif bold 18"))
        val document = CircuitDocument("main", components = listOf(pin))
        val body = classicComponentBounds(pin, registry)
        val label = requireNotNull(classicComponentLabel(pin, body))
        val text = measurer.visualTextBounds(label)
        val labelPoint = Offset(((text.left + text.right) / 2).toFloat(), ((text.top + text.bottom) / 2).toFloat())

        assertFalse(body.contains(labelPoint))
        assertEquals(TextToolTarget.Existing(pin.id), measurer.textToolTarget(document, labelPoint, registry))
        assertNull(measurer.textToolTarget(document, body.center, registry))
        assertEquals(TextToolTarget.Empty(GridPoint(229, 164)), measurer.textToolTarget(document, Offset(229f, 164f), registry))
    }
}
