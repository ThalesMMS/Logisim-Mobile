package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer
import kotlin.math.roundToInt

internal data class WirePoke(val wire: WireSegment, val point: GridPoint) {
    fun signal(simulation: SimulationState): LogicVector? =
        simulation.portVectors[wire.from] ?: simulation.portVectors[wire.to]

    fun description(value: LogicVector?) = message(Messages.A11yWireValue, "value" to accessibleSignal(value))
}

@Composable
internal fun WirePokeCallout(
    poke: WirePoke,
    simulation: SimulationState,
    view: CircuitViewport,
    viewportSize: IntSize,
    primaryRadix: WirePokeRadix,
    secondaryRadix: WirePokeRadix?,
    isSimulationRunning: Boolean,
) {
    val anchor = view.pan + Offset(poke.point.x.toFloat(), poke.point.y.toFloat()) * view.scale
    if (anchor.x < 0f || anchor.y < 0f || anchor.x >= viewportSize.width || anchor.y >= viewportSize.height) return
    val value = poke.signal(simulation)
    val characters = currentValueCharacters()
    val readings = value?.let { wirePokeReadings(it, primaryRadix, secondaryRadix, characters) }
    val strings = rememberLocalizer()
    val description = if (readings == null) strings(poke.description(null)) else strings(
        Messages.A11yWireFormattedReadings, "signal" to poke.description(value), "readings" to readings.joinToString(", "),
    )
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    Layout(modifier = Modifier.fillMaxSize(), content = {
        Text(
            text = readings?.joinToString(" / ") ?: characters.unknownChar.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurface,
            modifier = Modifier.background(colors.surface, shape)
                .border(1.dp, colors.outline, shape)
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .clearAndSetSemantics {
                    contentDescription = description
                    if (!isSimulationRunning) liveRegion = LiveRegionMode.Polite
                },
        )
    }) { measurables, constraints ->
        val callout = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = 8.dp.roundToPx()
        val x = (anchor.x.roundToInt() - callout.width / 2).coerceIn(0, constraints.maxWidth - callout.width)
        val above = anchor.y.roundToInt() - callout.height - gap
        val y = (if (above >= 0) above else anchor.y.roundToInt() + gap)
            .coerceIn(0, constraints.maxHeight - callout.height)
        layout(constraints.maxWidth, constraints.maxHeight) { callout.place(x, y) }
    }
}
