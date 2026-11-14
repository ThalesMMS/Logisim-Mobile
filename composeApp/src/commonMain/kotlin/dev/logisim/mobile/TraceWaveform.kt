package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.TraceFrame
import dev.logisim.mobile.core.TraceRadix
import dev.logisim.mobile.core.TraceWindow
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow

/** One drawn stretch of a signal: from [x0] to [x1] pixels, holding [value] (null when it was not captured). */
internal data class TraceSegment(val x0: Float, val x1: Float, val value: LogicVector?)

/** The text written inside a bus segment: the value when it fits, or an ellipsis when only that does. */
internal data class BusSegmentLabel(val x0: Float, val x1: Float, val text: String, val elided: Boolean)

/**
 * The visible stretches of signal [index] in [window], [width] pixels wide, starting with the value held at its left
 * edge. Frames that keep the signal's value extend its stretch, so a bus changes shape only where its value changes.
 */
internal fun traceSegments(frames: List<TraceFrame>, index: Int, window: TraceWindow, width: Float): List<TraceSegment> {
    fun x(time: Long) = ((time - window.start).toDouble() / window.span * width).toFloat().coerceIn(0f, width)
    val before = frames.lastOrNull { it.time < window.start }
    val visible = listOfNotNull(before) + frames.filter { it.time in window.start..window.end }
    val changes = visible.filterIndexed { position, frame ->
        position == 0 || frame.values.getOrNull(index) != visible[position - 1].values.getOrNull(index)
    }
    return changes.mapIndexed { position, frame ->
        val next = changes.getOrNull(position + 1)?.time ?: window.end
        TraceSegment(x(frame.time), x(next), frame.values.getOrNull(index))
    }
}

/**
 * Evolution's RightPanel writes a bus value inside each segment: the formatted value centred when it fits within
 * [padding] of the rails' slanted ends, an ellipsis when only that fits, and nothing in narrower segments.
 */
internal fun busSegmentLabels(
    segments: List<TraceSegment>,
    format: (LogicVector) -> String,
    measure: (String) -> Float,
    padding: Float,
    ellipsis: String = "…",
): List<BusSegmentLabel> {
    val ellipsisWidth = measure(ellipsis)
    return segments.mapNotNull { segment ->
        val value = segment.value ?: return@mapNotNull null
        val room = segment.x1 - segment.x0 - 2 * padding
        val text = format(value)
        when {
            measure(text) <= room -> BusSegmentLabel(segment.x0, segment.x1, text, elided = false)
            ellipsisWidth <= room -> BusSegmentLabel(segment.x0, segment.x1, ellipsis, elided = true)
            else -> null
        }
    }
}

/**
 * The time ruler's ticks: multiples of the smallest 1, 2 or 5 × 10ⁿ step that keeps ticks at least [minSpacing]
 * pixels apart across [width] pixels of [window].
 */
internal fun traceRulerTicks(window: TraceWindow, width: Float, minSpacing: Float): List<Long> {
    if (width <= 0f || minSpacing <= 0f) return emptyList()
    val raw = window.span.toDouble() * minSpacing / width
    val step = niceStep(raw)
    val first = ceil(window.start.toDouble() / step).toLong() * step
    return generateSequence(first) { it + step }.takeWhile { it <= window.end }.take(1_000).toList()
}

private fun niceStep(raw: Double): Long {
    if (raw <= 1.0) return 1L
    val magnitude = 10.0.pow(kotlin.math.floor(log10(raw)))
    val fraction = raw / magnitude
    val nice = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }
    return (nice * magnitude).toLong().coerceAtLeast(1L)
}

/** The span of time the waveforms show before any zoom. */
internal const val TraceDefaultSpan = 20L

/** The room kept after the newest sample, so its value shows before the right edge: a tenth of the span. */
internal fun traceTrailingRoom(span: Long): Long = maxOf(1L, span / 10)

/**
 * The window that first shows a recording from [first] to [last] with [span]: from the origin while it fits there,
 * from its first sample while all of it fits, otherwise its last [span] units, ending just after the newest sample.
 */
internal fun traceInitialWindow(first: Long?, last: Long?, span: Long = TraceDefaultSpan): TraceWindow {
    if (first == null || last == null) return TraceWindow(0, span)
    val end = last + traceTrailingRoom(span)
    val start = when {
        end <= span -> 0L
        end - first <= span -> first
        else -> end - span
    }.coerceAtLeast(0)
    return TraceWindow(start, start + span)
}

/**
 * The window after the recording's newest sample moves from [previousLast] to [last] (C57). As Evolution's
 * RightPanel.updateSize(scrollRight) keeps the right edge in view while it was visible, a window showing the newest
 * sample scrolls with the same span to keep showing it; a window the user moved elsewhere stays put. A recording that
 * just began, or restarted at an earlier time (cleared or reset), opens on its recorded range.
 */
internal fun traceFollowWindow(window: TraceWindow, previousLast: Long?, first: Long?, last: Long?): TraceWindow {
    val room = traceTrailingRoom(window.span)
    return when {
        last == null || last == previousLast -> window
        previousLast == null || last < previousLast -> traceInitialWindow(first, last, window.span)
        previousLast !in window.start..window.end -> window
        last + room <= window.end -> window
        else -> TraceWindow(last + room - window.span, last + room)
    }
}

/** The widest text a [radix] value of [width] bits can take, as zeros for a monospace measure. */
internal fun traceValueSample(width: Int, radix: TraceRadix): String = when (radix) {
    TraceRadix.BINARY -> "0".repeat(width)
    TraceRadix.HEXADECIMAL -> "0x" + "0".repeat((width + 3) / 4)
    TraceRadix.DECIMAL -> "0".repeat(if (width >= 64) 20 else (1uL shl width).minus(1uL).toString().length.coerceAtLeast(1))
}

/** Signal colours for the waveforms and the table, from the canvas palette of the active theme and contrast preference. */
internal data class TraceColors(val low: Color, val high: Color, val unknown: Color, val error: Color, val bus: Color, val missing: Color) {
    fun line(value: LogicVector?): Color = when {
        value == null -> missing
        value.errorMask != 0uL -> error
        value.unknownMask != 0uL -> unknown
        value.width > 1 -> bus
        value.bit(0) == LogicValue.HIGH -> high
        else -> low
    }
}

@Composable
internal fun traceColors(): TraceColors {
    val dark = LocalEditorDarkTheme.current
    val preferences = LocalEditorPreferences.current?.preferences
    val colors = preferences?.effectiveColors(dark) ?: if (dark) DarkEditorColors else emptyMap()
    val palette = if (preferences?.highContrastSignals == true) CircuitSignalPalette.HighContrast else CircuitSignalPalette.Classic
    return TraceColors(
        low = colors.color(EditorColor.SIGNAL_LOW, palette.low),
        high = colors.color(EditorColor.SIGNAL_HIGH, palette.high),
        unknown = colors.color(EditorColor.SIGNAL_UNKNOWN, palette.unknown),
        error = colors.color(EditorColor.SIGNAL_ERROR, palette.error),
        // The canvas draws buses in the component ink; on the panel the text colour has the same contrast.
        bus = ChromeColors.Ink,
        missing = ChromeColors.Muted,
    )
}
