package dev.logisim.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.FpgaBoardPicture
import dev.logisim.mobile.core.FpgaBoardPin
import dev.logisim.mobile.core.FpgaBoardResource
import dev.logisim.mobile.core.FpgaDesignPort
import dev.logisim.mobile.core.FpgaPinBinding
import dev.logisim.mobile.core.FpgaPinDirection
import dev.logisim.mobile.core.FpgaRectangle
import dev.logisim.mobile.core.FpgaSignalBit
import dev.logisim.mobile.core.scannedDisplay
import dev.logisim.mobile.localization.Localizer
import dev.logisim.mobile.localization.rememberLocalizer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Picture-space hit testing for the board pin picker; the tap target never depends on how small a resource is drawn. */
internal object FpgaBoardHitTest {
    /** Material's minimum touch target: a tap within half of it from a resource still reaches that resource. */
    const val TOUCH_RADIUS_DP = 24f

    /** Distance from ([x], [y]) to the rectangle, zero inside it. */
    fun distance(x: Float, y: Float, rectangle: FpgaRectangle): Float {
        val dx = max(max(rectangle.x - x, 0f), x - (rectangle.x + rectangle.width))
        val dy = max(max(rectangle.y - y, 0f), y - (rectangle.y + rectangle.height))
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * The index of the rectangle a tap at ([x], [y]) picture pixels picks: the smallest one containing the point, so a
     * small resource drawn over a large one stays reachable, otherwise the nearest within [radius] picture pixels.
     * Ties go to the earlier rectangle, so overlapping resources resolve the same way every time.
     */
    fun pick(x: Float, y: Float, rectangles: List<FpgaRectangle>, radius: Float): Int? {
        fun area(rectangle: FpgaRectangle) = rectangle.width.toLong() * rectangle.height
        val containing = rectangles.indices.filter { distance(x, y, rectangles[it]) == 0f }
        if (containing.isNotEmpty()) return containing.minWith(compareBy({ area(rectangles[it]) }, { it }))
        return rectangles.indices.filter { distance(x, y, rectangles[it]) <= radius }
            .minWithOrNull(compareBy({ distance(x, y, rectangles[it]) }, { area(rectangles[it]) }, { it }))
    }

    /** [TOUCH_RADIUS_DP] in picture pixels when one picture pixel is drawn [screenPixelsPerPicturePixel] wide. */
    fun radius(touchRadiusPx: Float, screenPixelsPerPicturePixel: Float): Float =
        if (screenPixelsPerPicturePixel <= 0f) 0f else touchRadiusPx / screenPixelsPerPicturePixel
}

/** Pins of [board] a signal of [direction] can use: same direction or bidirectional. */
internal fun FpgaBoardResource.compatiblePins(direction: FpgaPinDirection): List<FpgaBoardPin> =
    pins.filter { it.direction == direction || it.direction == FpgaPinDirection.INOUT }

/**
 * The pins a whole port of [width] bits takes on [resource] from [offset], in FpgaMappingCodec.whole()'s order: the
 * direct-direction pin of each index, otherwise the bidirectional one. Null when the resource cannot hold the port.
 */
internal fun fpgaWholePortPins(resource: FpgaBoardResource, direction: FpgaPinDirection, width: Int, offset: Int = 0): List<FpgaBoardPin>? {
    if (width < 1 || offset < 0 || resource.scannedDisplay() != null) return null
    val direct = resource.pins.filter { it.direction == direction }
    val bidirectional = resource.pins.filter { it.direction == FpgaPinDirection.INOUT }
    return List(width) { index -> direct.getOrNull(offset + index) ?: bidirectional.getOrNull(offset + index) ?: return null }
}

/** The first pins at which a whole port of [width] bits fits on [resource]; empty when it never fits. */
internal fun fpgaWholePortOffsets(resource: FpgaBoardResource, direction: FpgaPinDirection, width: Int): List<Int> {
    val capacity = max(resource.pins.count { it.direction == direction }, resource.pins.count { it.direction == FpgaPinDirection.INOUT })
    return (0..(capacity - width)).filter { fpgaWholePortPins(resource, direction, width, it) != null }
}

/** Whether one resource of [board] can take every bit of [port], so "Map whole port" can keep its promise. */
internal fun fpgaWholePortFits(board: FpgaBoardDefinition, port: FpgaDesignPort): Boolean =
    port.width > 1 && board.resources.any { fpgaWholePortOffsets(it, port.direction, port.width).isNotEmpty() }

/**
 * Maps every bit of [port] onto [resource] from [offset], as Evolution's complete map (and its DIP switch offset rule)
 * does. Bit i takes the pin at offset + i; the bindings stay per bit, so the saved boardmap is the one per-bit choices
 * would write.
 */
internal fun fpgaMapWholePort(
    bindings: Map<FpgaSignalBit, FpgaPinBinding>,
    port: FpgaDesignPort,
    resource: FpgaBoardResource,
    offset: Int = 0,
): Map<FpgaSignalBit, FpgaPinBinding>? {
    val pins = fpgaWholePortPins(resource, port.direction, port.width, offset) ?: return null
    return bindings + pins.mapIndexed { bit, pin -> FpgaSignalBit(port.name, bit) to FpgaPinBinding.Physical(pin.location, resource.id) }
}

/**
 * The one-pin resources that follow [start] in [board]'s file order, [start] first: same type, one pin a signal of
 * [direction] can use each, until the first resource that differs. BASYS3 lists its switches SW0 to SW15 (and its
 * LEDs LD0 to LD15) this way, so a run is the row of switches from the tapped one. Empty when [start] is not such a
 * resource.
 */
internal fun fpgaResourceRun(board: FpgaBoardDefinition, start: FpgaBoardResource, direction: FpgaPinDirection): List<FpgaBoardResource> {
    fun single(resource: FpgaBoardResource) = resource.scannedDisplay() == null && resource.pins.size == 1 &&
        resource.compatiblePins(direction).size == 1 && resource.type.equals(start.type, ignoreCase = true)
    val from = board.resources.indexOfFirst { it.id == start.id }
    if (from < 0 || !single(start)) return emptyList()
    return board.resources.drop(from).takeWhile(::single)
}

/**
 * Maps bits [fromBit] onward of [port] onto [run], one resource per bit, as many as the run holds; other bindings stay.
 * The bindings are the per-bit choices one picker per bit would make, so the saved boardmap is the same. Null when no
 * bit is mapped.
 */
internal fun fpgaMapPortRun(
    bindings: Map<FpgaSignalBit, FpgaPinBinding>,
    port: FpgaDesignPort,
    fromBit: Int,
    run: List<FpgaBoardResource>,
): Map<FpgaSignalBit, FpgaPinBinding>? {
    val count = minOf(run.size, port.width - fromBit)
    if (fromBit < 0 || count < 1) return null
    return bindings + (0 until count).associate { index ->
        val resource = run[index]
        FpgaSignalBit(port.name, fromBit + index) to FpgaPinBinding.Physical(resource.compatiblePins(port.direction).single().location, resource.id)
    }
}

/** The next bit of [port] after [after] without a binding, wrapping to the first; null once every bit is mapped. */
internal fun fpgaNextUnmappedBit(port: FpgaDesignPort, after: Int, bindings: Map<FpgaSignalBit, FpgaPinBinding>): Int? =
    (1..port.width).map { (after + it) % port.width }.firstOrNull { FpgaSignalBit(port.name, it) !in bindings }

/** The picker's choice: a resource, and one of its compatible pins once known. */
private data class FpgaPinCandidate(val resource: FpgaBoardResource, val pin: FpgaBoardPin?)

/**
 * One full-screen picker for a physical pin: the board image, zoomable with pinch and the +/- buttons, and a list of
 * every compatible pin grouped by resource. A tap only proposes a pin; the "Use" button binds it. With [port] wider
 * than one bit, the chosen resource can also take the whole port.
 */
@Composable
internal fun FpgaPinPickerDialog(
    board: FpgaBoardDefinition,
    bitmap: ImageBitmap?,
    port: FpgaDesignPort,
    bit: Int,
    bindings: Map<FpgaSignalBit, FpgaPinBinding>,
    onDismiss: () -> Unit,
    onChoose: (FpgaPinBinding.Physical) -> Unit,
    onChoosePort: (Map<FpgaSignalBit, FpgaPinBinding>) -> Unit,
    /** The picker walks through the port: Use moves on to the next unmapped bit, and the progress is shown. */
    walking: Boolean = false,
) {
    val text = rememberLocalizer()
    val signal = FpgaSignalBit(port.name, bit)
    val title = if (port.width == 1) port.name else "${port.name}[$bit]"
    val current = bindings[signal] as? FpgaPinBinding.Physical
    val resources = remember(board, port.direction) {
        board.resources.filter { it.compatiblePins(port.direction).isNotEmpty() }
    }
    val otherMapped = remember(bindings, signal) {
        bindings.filterKeys { it != signal }.values.mapNotNullTo(mutableSetOf()) { (it as? FpgaPinBinding.Physical)?.location?.lowercase() }
    }
    val initial = remember(board, current) {
        current?.takeIf { it.location.isNotBlank() }?.let { binding ->
            resources.firstNotNullOfOrNull { resource ->
                if (binding.resourceId != null && binding.resourceId != resource.id) null
                else resource.compatiblePins(port.direction).firstOrNull { it.location.equals(binding.location, true) }
                    ?.let { FpgaPinCandidate(resource, it) }
            }
        }
    }
    var candidate by remember(board, signal) { mutableStateOf(initial) }
    val drawable = bitmap != null && resources.any { it.rectangle != null }
    // The tab is fixed when the picker opens: the board image if it is already decoded, otherwise the list. An image
    // decoded later adds its tab but never switches away from the list, its filter or its scroll position.
    var boardTab by remember(board, signal) { mutableStateOf(drawable) }
    var offset by remember(board, signal, candidate?.resource?.id) { mutableStateOf(0) }
    val properties = DialogProperties(usePlatformDefaultWidth = false)
    Dialog(onDismissRequest = onDismiss, properties = appDialogProperties(properties)) {
        Surface(Modifier.appDialogViewport(onDismiss, properties).fillMaxSize().exportEditorTestTags().testTag("fpga-pin-picker")) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text("fpga_pin_picker_title", "signal" to title, "board" to board.name), Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(text("fpga_board_map_close")) }
                }
                if (walking && port.width > 1) {
                    val mapped = (0 until port.width).count { FpgaSignalBit(port.name, it) in bindings }
                    Text(text.plural("fpga_pin_picker_progress", port.width.toLong(), "port" to port.name, "mapped" to mapped),
                        // Read when a bit is mapped and the picker moves on.
                        Modifier.padding(horizontal = 16.dp).testTag("fpga-pin-picker-progress").semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (drawable) {
                    TabRow(selectedTabIndex = if (boardTab) 0 else 1) {
                        Tab(selected = boardTab, onClick = { boardTab = true }, modifier = Modifier.heightIn(min = 48.dp),
                            text = { Text(text("fpga_pin_picker_tab_board")) })
                        Tab(selected = !boardTab, onClick = { boardTab = false }, modifier = Modifier.heightIn(min = 48.dp),
                            text = { Text(text("fpga_pin_picker_tab_list")) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (boardTab && bitmap != null) {
                        BoardTab(board, bitmap, resources, port.direction, candidate, otherMapped, text) { candidate = it }
                    } else {
                        PinListTab(resources, port.direction, candidate, otherMapped, text) { candidate = it }
                    }
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chosen = candidate
                    val resourceName = chosen?.let { text(FpgaLabels.resource(it.resource)) }
                    val pin = chosen?.pin
                    Text(when {
                        chosen == null -> text("fpga_pin_picker_hint")
                        pin == null -> text("fpga_pin_picker_choose_pin", "resource" to resourceName)
                        pin.location.lowercase() in otherMapped -> text("fpga_pin_picker_candidate_used", "resource" to resourceName, "pin" to pin.location)
                        else -> text("fpga_pin_picker_candidate", "resource" to resourceName, "pin" to pin.location)
                    }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (chosen != null && port.width > 1 && port.clockFrequencyHz == null) {
                        val offsets = fpgaWholePortOffsets(chosen.resource, port.direction, port.width)
                        if (offsets.isNotEmpty()) {
                            val start = offset.takeIf { it in offsets } ?: offsets.first()
                            if (offsets.size > 1) {
                                val labels = offsets.map { value ->
                                    value to text("fpga_pin_picker_start_value", "index" to value,
                                        "pin" to fpgaWholePortPins(chosen.resource, port.direction, port.width, value)!!.first().location)
                                }
                                FpgaChoice(text("fpga_pin_picker_start"), labels.first { it.first == start }.second, labels,
                                    selectedValue = start, testTag = "fpga-pin-picker-start") { offset = it }
                            }
                            val replaced = (0 until port.width).count { FpgaSignalBit(port.name, it) in bindings }
                            if (replaced > 0) Text(text.plural("fpga_pin_picker_replaces", replaced.toLong(), "port" to port.name),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = {
                                fpgaMapWholePort(bindings, port, chosen.resource, start)?.let(onChoosePort)
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fpga-pin-picker-use-port")) {
                                Text(text.plural("fpga_pin_picker_use_port", port.width.toLong(), "port" to port.name, "resource" to resourceName),
                                    textAlign = TextAlign.Center)
                            }
                        } else {
                            // One-pin resources (BASYS3's switches and LEDs): this bit and the ones after it take the
                            // tapped resource and those that follow it in the board file, one bit each.
                            val run = remember(board, chosen.resource.id, port.direction) { fpgaResourceRun(board, chosen.resource, port.direction) }
                            val count = minOf(run.size, port.width - bit)
                            if (count >= 2) {
                                val replaced = (bit until bit + count).count { FpgaSignalBit(port.name, it) in bindings }
                                if (replaced > 0) Text(text.plural("fpga_pin_picker_replaces", replaced.toLong(), "port" to port.name),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { fpgaMapPortRun(bindings, port, bit, run)?.let(onChoosePort) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fpga-pin-picker-use-run")) {
                                    Text(text.plural("fpga_pin_picker_use_run", count.toLong(),
                                        "range" to "${port.name}[$bit–${bit + count - 1}]",
                                        "first" to run.first().compatiblePins(port.direction).single().location,
                                        "last" to run[count - 1].compatiblePins(port.direction).single().location),
                                        textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    Button(onClick = { pin?.let { onChoose(FpgaPinBinding.Physical(it.location, chosen.resource.id)) } },
                        enabled = pin != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fpga-pin-picker-use")) {
                        Text(if (pin != null) text("fpga_pin_picker_use", "resource" to resourceName, "pin" to pin.location)
                            else text("fpga_pin_picker_use_disabled"), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

private const val MAX_ZOOM = 8f

@Composable
private fun BoardTab(
    board: FpgaBoardDefinition,
    bitmap: ImageBitmap,
    resources: List<FpgaBoardResource>,
    direction: FpgaPinDirection,
    candidate: FpgaPinCandidate?,
    otherMapped: Set<String>,
    text: Localizer,
    onCandidate: (FpgaPinCandidate) -> Unit,
) {
    val colors = LocalEditorPreferences.current?.preferences?.effectiveColors(LocalEditorDarkTheme.current).orEmpty()
    val mapWidth = FpgaBoardPicture.MAPPING_WIDTH
    val mapHeight = FpgaBoardPicture.MAPPING_HEIGHT
    val drawn = remember(resources) {
        resources.mapNotNull { resource ->
            val rectangle = resource.rectangle?.takeIf { it.x < mapWidth && it.y < mapHeight } ?: return@mapNotNull null
            resource to rectangle
        }
    }
    // Resources this signal cannot use but other signals map stay visible, like Evolution's mapped areas.
    val mappedOnly = remember(board, drawn, otherMapped) {
        board.resources.mapNotNull { resource ->
            val rectangle = resource.rectangle?.takeIf { it.x < mapWidth && it.y < mapHeight } ?: return@mapNotNull null
            if (drawn.any { it.first.id == resource.id } || resource.pins.none { it.location.lowercase() in otherMapped }) null else rectangle
        }
    }
    var zoom by remember(board) { mutableFloatStateOf(1f) }
    var pan by remember(board) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    /** The +/- buttons zoom toward the chosen resource, or the middle of the view, keeping it in sight. */
    fun zoomTo(next: Float) {
        val target = next.coerceIn(1f, MAX_ZOOM)
        val width = viewport.width.toFloat()
        val height = viewport.height.toFloat()
        if (width <= 0f || height <= 0f) { zoom = target; return }
        val before = width * zoom / mapWidth
        val after = width * target / mapWidth
        val anchor = candidate?.resource?.rectangle?.let { Offset(it.x + it.width / 2f, it.y + it.height / 2f) }
            ?: Offset((width / 2 - pan.x) / before, (height / 2 - pan.y) / before)
        val contentWidth = width * target
        val contentHeight = contentWidth * mapHeight / mapWidth
        pan = Offset((width / 2 - anchor.x * after).coerceIn(minOf(0f, width - contentWidth), 0f),
            (height / 2 - anchor.y * after).coerceIn(minOf(0f, height - contentHeight), 0f))
        zoom = target
    }
    fun choose(resource: FpgaBoardResource) {
        val pins = resource.compatiblePins(direction)
        onCandidate(FpgaPinCandidate(resource, pins.singleOrNull() ?: candidate?.pin?.takeIf { it in pins }))
    }
    // The tap detector outlives a recomposition: when the picker moves on to the next bit, a tap must reach that bit's
    // candidate, not the callback it captured first.
    val tappedCandidate by rememberUpdatedState(candidate)
    val tappedOnCandidate by rememberUpdatedState(onCandidate)
    fun chooseTapped(resource: FpgaBoardResource) {
        val pins = resource.compatiblePins(direction)
        tappedOnCandidate(FpgaPinCandidate(resource, pins.singleOrNull() ?: tappedCandidate?.pin?.takeIf { it in pins }))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { zoomTo(zoom / 2f) }, enabled = zoom > 1f,
                modifier = Modifier.size(48.dp).semantics { contentDescription = text("fpga_pin_picker_zoom_out") },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−") }
            Text(text("fpga_pin_picker_zoom_value", "zoom" to ((zoom * 10).roundToInt() / 10.0).let { if (it % 1.0 == 0.0) it.toInt() else it }),
                Modifier.widthIn(min = 48.dp), textAlign = TextAlign.Center)
            OutlinedButton(onClick = { zoomTo(zoom * 2f) }, enabled = zoom < MAX_ZOOM,
                modifier = Modifier.size(48.dp).semantics { contentDescription = text("fpga_pin_picker_zoom_in") },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+") }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }, enabled = zoom > 1f || pan != Offset.Zero,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(text("fpga_pin_picker_zoom_fit")) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            val density = LocalDensity.current
            val viewWidthPx = constraints.maxWidth.toFloat()
            val viewHeightPx = constraints.maxHeight.toFloat()
            val contentWidthPx = viewWidthPx * zoom
            val contentHeightPx = contentWidthPx * mapHeight / mapWidth
            fun clamp(value: Offset) = Offset(
                value.x.coerceIn(minOf(0f, viewWidthPx - contentWidthPx), 0f),
                value.y.coerceIn(minOf(0f, viewHeightPx - contentHeightPx), 0f),
            )
            val shown = clamp(pan)
            val scale = contentWidthPx / mapWidth
            val touchRadiusPx = with(density) { FpgaBoardHitTest.TOUCH_RADIUS_DP.dp.toPx() }
            val rectangles = drawn.map { it.second }
            Box(Modifier.fillMaxSize().testTag("fpga-pin-board").onSizeChanged { viewport = it }
                .pointerInput(drawn, contentWidthPx, shown) {
                    detectTapGestures { tap ->
                        val x = (tap.x - shown.x) / scale
                        val y = (tap.y - shown.y) / scale
                        FpgaBoardHitTest.pick(x, y, rectangles, FpgaBoardHitTest.radius(touchRadiusPx, scale))
                            ?.let { chooseTapped(drawn[it].first) }
                    }
                }
                .pointerInput(viewWidthPx, viewHeightPx) {
                    detectTransformGestures { centroid, change, factor, _ ->
                        val next = (zoom * factor).coerceIn(1f, MAX_ZOOM)
                        val applied = next / zoom
                        // Keep the picture point under the fingers in place while zooming.
                        val anchored = (pan - centroid) * applied + centroid + change
                        zoom = next
                        val width = viewWidthPx * next
                        val height = width * mapHeight / mapWidth
                        pan = Offset(anchored.x.coerceIn(minOf(0f, viewWidthPx - width), 0f),
                            anchored.y.coerceIn(minOf(0f, viewHeightPx - height), 0f))
                    }
                }) {
                val contentWidth = with(density) { contentWidthPx.toDp() }
                val contentHeight = with(density) { contentHeightPx.toDp() }
                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset { IntOffset(shown.x.roundToInt(), shown.y.roundToInt()) }
                    .requiredSize(contentWidth, contentHeight)) {
                    Image(bitmap, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    val mappedTint = colors.fpgaColor(EditorColor.FPGA_MAPPED)
                    mappedOnly.forEach { rect ->
                        Box(Modifier.absoluteOffset(contentWidth * (rect.x.toFloat() / mapWidth), contentHeight * (rect.y.toFloat() / mapHeight))
                            .size(contentWidth * (rect.width.coerceAtMost(mapWidth - rect.x).toFloat() / mapWidth),
                                contentHeight * (rect.height.coerceAtMost(mapHeight - rect.y).toFloat() / mapHeight))
                            .background(mappedTint.copy(alpha = 100f / 255f)))
                    }
                    drawn.forEach { (resource, rect) ->
                        val pins = resource.compatiblePins(direction)
                        val label = text(FpgaLabels.resource(resource))
                        val description = pins.singleOrNull()?.let { text("fpga_board_map_resource", "resource" to label, "pin" to it.location) }
                            ?: text.plural("fpga_board_map_resource_pins", pins.size.toLong(), "resource" to label)
                        val selected = candidate?.resource?.id == resource.id
                        val mapped = pins.any { it.location.lowercase() in otherMapped }
                        val tint = colors.fpgaColor(when {
                            selected -> EditorColor.FPGA_SELECTED_MAPPED
                            mapped -> EditorColor.FPGA_SELECTABLE_MAPPED
                            else -> EditorColor.FPGA_SELECT
                        })
                        // Taps reach the board's hit test, which also accepts near misses; TalkBack uses the click action.
                        Box(Modifier
                            .absoluteOffset(contentWidth * (rect.x.toFloat() / mapWidth), contentHeight * (rect.y.toFloat() / mapHeight))
                            .size(contentWidth * (rect.width.coerceAtMost(mapWidth - rect.x).toFloat() / mapWidth),
                                contentHeight * (rect.height.coerceAtMost(mapHeight - rect.y).toFloat() / mapHeight))
                            .testTag("fpga-board-resource-${resource.id}")
                            .semantics {
                                contentDescription = description
                                role = Role.Button
                                this.selected = selected
                                onClick { choose(resource); true }
                            }
                            .background(tint.copy(alpha = if (selected) 0.72f else 0.25f))
                            .border(if (selected) 3.dp else 1.dp, tint))
                    }
                }
            }
        }
        val chosen = candidate
        val pins = chosen?.resource?.compatiblePins(direction).orEmpty()
        if (chosen != null && pins.size > 1) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp).selectableGroup()) {
                items(pins, key = { "${it.resourceId}-${it.index}" }) { pin ->
                    PinRow(chosen.resource, pin, chosen.pin == pin, pin.location.lowercase() in otherMapped, text) {
                        onCandidate(FpgaPinCandidate(chosen.resource, pin))
                    }
                }
            }
        }
    }
}

@Composable
private fun PinListTab(
    resources: List<FpgaBoardResource>,
    direction: FpgaPinDirection,
    candidate: FpgaPinCandidate?,
    otherMapped: Set<String>,
    text: Localizer,
    onCandidate: (FpgaPinCandidate) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val groups = resources.map { resource ->
        val name = text(FpgaLabels.resource(resource))
        resource to resource.compatiblePins(direction).filter { pin ->
            filter.isBlank() || "${pin.location} $name ${resource.type}".contains(filter.trim(), ignoreCase = true)
        }
    }.filter { it.second.isNotEmpty() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(filter, { filter = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            label = { Text(text("fpga_filter_pins")) }, singleLine = true)
        if (groups.isEmpty()) Text(text("fpga_no_compatible_pin"), Modifier.padding(vertical = 12.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("fpga-pin-list").selectableGroup()) {
            for ((resource, pins) in groups) {
                // A resource with several pins gets a heading; a one-pin resource names itself in its row.
                val single = resource.compatiblePins(direction).size == 1
                if (!single) item(key = "group-${resource.id}") {
                    Text(text(FpgaLabels.resource(resource)), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
                }
                items(pins, key = { "${it.resourceId}-${it.index}" }) { pin ->
                    PinRow(resource, pin, candidate?.resource?.id == resource.id && candidate.pin == pin,
                        pin.location.lowercase() in otherMapped, text, named = single) { onCandidate(FpgaPinCandidate(resource, pin)) }
                }
            }
        }
    }
}

/** One pin, read by TalkBack as a radio button with its location, index, use elsewhere and electrical settings. */
@Composable
private fun PinRow(
    resource: FpgaBoardResource,
    pin: FpgaBoardPin,
    selected: Boolean,
    used: Boolean,
    text: Localizer,
    named: Boolean = false,
    onSelect: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fpga-pin-option-${resource.id}-${pin.index}")
        .selectable(selected, role = Role.RadioButton, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(text("fpga_board_map_pin", "pin" to pin.location, "index" to pin.index))
            val details = listOfNotNull(
                text(FpgaLabels.resource(resource)).takeIf { named },
                text("fpga_pin_used_elsewhere").takeIf { used },
                fpgaElectricalSummary(pin, text),
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall,
                color = if (used) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun fpgaElectricalSummary(pin: FpgaBoardPin, text: Localizer): String {
    val electrical = pin.electrical
    val polarity = if (electrical.activeLow) text("fpga_active_low") else text("fpga_active_high")
    return listOfNotNull(electrical.ioStandard, polarity, electrical.driveMilliamps?.let { text("fpga_editor_drive_value", "drive" to it) })
        .joinToString(" · ")
}
