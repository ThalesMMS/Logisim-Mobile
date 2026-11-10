package dev.logisim.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.FpgaBoardPicture
import dev.logisim.mobile.core.FpgaPinBinding
import dev.logisim.mobile.core.FpgaPinDirection
import dev.logisim.mobile.localization.Localizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberFpgaBoardBitmap(xml: String?): ImageBitmap? = key(xml) {
    produceState<ImageBitmap?>(null) {
        if (xml != null) {
            value = withContext(Dispatchers.Default) {
                try {
                    FpgaBoardPicture.read(xml)?.let { decodeEmbeddedAnnotationImage(it.imageBytes()) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: IllegalStateException) {
                    null
                }
            }
        }
    }.value
}

@Composable
internal fun FpgaBoardPinPicker(
    board: FpgaBoardDefinition,
    bitmap: ImageBitmap,
    direction: FpgaPinDirection,
    binding: FpgaPinBinding.Physical?,
    signal: String,
    text: Localizer,
    onBinding: (FpgaPinBinding.Physical) -> Unit,
    /** Lowercase pin locations bound to other signals, drawn with Evolution's mapped colour. */
    otherMapped: Set<String> = emptySet(),
) {
    val colors = LocalEditorPreferences.current?.preferences?.effectiveColors(LocalEditorDarkTheme.current).orEmpty()
    val mapWidth = FpgaBoardPicture.MAPPING_WIDTH
    val mapHeight = FpgaBoardPicture.MAPPING_HEIGHT
    val resources = remember(board, direction) {
        board.resources.mapNotNull { resource ->
            val rectangle = resource.rectangle ?: return@mapNotNull null
            if (rectangle.x >= mapWidth || rectangle.y >= mapHeight) return@mapNotNull null
            val pins = resource.pins.filter { it.direction == direction || it.direction == FpgaPinDirection.INOUT }
            if (pins.isEmpty()) null else Triple(resource, rectangle, pins)
        }
    }
    if (resources.isEmpty()) return
    // Resources this signal cannot use but other signals map stay visible, like Evolution's mapped areas.
    val mappedOnly = remember(board, direction, otherMapped) {
        board.resources.mapNotNull { resource ->
            val rectangle = resource.rectangle ?: return@mapNotNull null
            if (rectangle.x >= mapWidth || rectangle.y >= mapHeight || resources.any { it.first.id == resource.id }) return@mapNotNull null
            if (resource.pins.none { it.location.lowercase() in otherMapped }) null else rectangle
        }
    }
    var open by remember(board, direction, signal) { mutableStateOf(false) }
    var expandedResource by remember(board, direction, signal) { mutableStateOf<String?>(null) }
    val mappedLocation = binding?.location?.takeIf(String::isNotBlank)
    val currentPin = mappedLocation?.let { text("fpga_selected_pin", "pin" to it) }
    OutlinedButton(
        onClick = { expandedResource = null; open = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
            if (currentPin != null) stateDescription = currentPin
        },
    ) {
        Text(text("fpga_board_map_open"))
    }
    if (!open) return

    fun choose(pin: FpgaPinBinding.Physical) {
        onBinding(pin)
        open = false
    }
    val pinGroup = resources.firstOrNull { it.first.id == expandedResource }
    val dismiss = { open = false }
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            val title = if (pinGroup == null) text("fpga_board_map_title", "board" to board.name, "signal" to signal)
                else text("fpga_board_map_resource_title", "resource" to pinGroup.first.label.ifBlank { pinGroup.first.type })
            Text(title)
        },
        text = {
            if (pinGroup == null) {
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(mapWidth.toFloat() / mapHeight).clipToBounds()) {
                    val boardWidth = maxWidth
                    val boardHeight = maxHeight
                    Image(bitmap, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    val mappedTint = colors.fpgaColor(EditorColor.FPGA_MAPPED)
                    mappedOnly.forEach { rect ->
                        Box(Modifier
                            .absoluteOffset(boardWidth * (rect.x.toFloat() / mapWidth), boardHeight * (rect.y.toFloat() / mapHeight))
                            .size(boardWidth * (rect.width.coerceAtMost(mapWidth - rect.x).toFloat() / mapWidth),
                                boardHeight * (rect.height.coerceAtMost(mapHeight - rect.y).toFloat() / mapHeight))
                            .background(mappedTint.copy(alpha = 100f / 255f)))
                    }
                    resources.forEach { (resource, rect, pins) ->
                        val label = resource.label.ifBlank { resource.type }
                        val pin = pins.singleOrNull()
                        val description = if (pin != null) {
                            text("fpga_board_map_resource", "resource" to label, "pin" to pin.location)
                        } else {
                            text.plural("fpga_board_map_resource_pins", pins.size.toLong(), "resource" to label)
                        }
                        val selected = pins.any { candidate ->
                            candidate.location.equals(mappedLocation, ignoreCase = true) &&
                                (binding?.resourceId == null || binding.resourceId == resource.id)
                        }
                        // Evolution FpgaIoInformationContainer: the current map, other maps, then selectable resources.
                        val mapped = pins.any { it.location.lowercase() in otherMapped }
                        val tint = colors.fpgaColor(when {
                            selected -> EditorColor.FPGA_SELECTED_MAPPED
                            mapped -> EditorColor.FPGA_SELECTABLE_MAPPED
                            else -> EditorColor.FPGA_SELECT
                        })
                        Box(Modifier
                            .absoluteOffset(boardWidth * (rect.x.toFloat() / mapWidth), boardHeight * (rect.y.toFloat() / mapHeight))
                            .size(boardWidth * (rect.width.coerceAtMost(mapWidth - rect.x).toFloat() / mapWidth),
                                boardHeight * (rect.height.coerceAtMost(mapHeight - rect.y).toFloat() / mapHeight))
                            .selectable(selected, role = Role.Button) {
                                if (pin == null) expandedResource = resource.id
                                else choose(FpgaPinBinding.Physical(pin.location, resource.id))
                            }
                            .semantics { contentDescription = description }
                            .background(tint.copy(alpha = if (selected) 0.72f else 0.25f))
                            .border(if (selected) 2.dp else 1.dp, tint))
                    }
                }
            } else {
                val (resource, _, pins) = pinGroup
                LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(pins) { pin ->
                        OutlinedButton(onClick = { choose(FpgaPinBinding.Physical(pin.location, resource.id)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(text("fpga_board_map_pin", "pin" to pin.location, "index" to pin.index))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (pinGroup == null) TextButton(onClick = dismiss) { Text(text("fpga_board_map_close")) }
            else TextButton(onClick = { expandedResource = null }) { Text(text("fpga_board_map_back")) }
        },
    )
}
