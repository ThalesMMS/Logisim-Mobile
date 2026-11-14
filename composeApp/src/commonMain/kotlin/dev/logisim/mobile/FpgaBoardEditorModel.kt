package dev.logisim.mobile

import androidx.compose.ui.text.input.KeyboardType
import dev.logisim.mobile.core.FpgaBoardPicture
import dev.logisim.mobile.core.FpgaBoardWriter
import dev.logisim.mobile.core.FpgaPull
import dev.logisim.mobile.core.FpgaRectangle
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The units of Evolution's board clock dialog (FpgaIoInformationSettingsDialog): Hz, kHz and MHz. */
internal enum class FpgaFrequencyUnit(val factor: Long, val symbol: String, internal val decimals: Int) {
    HZ(1, "Hz", 0), KHZ(1_000, "kHz", 3), MHZ(1_000_000, "MHz", 6);

    companion object {
        /** getFrequencyIndex: the largest unit that keeps the stored frequency whole; MHz for a new board. */
        fun of(hz: Long?): FpgaFrequencyUnit = when {
            hz == null -> MHZ
            hz % 1_000 != 0L -> HZ
            hz % 1_000_000 != 0L -> KHZ
            else -> MHZ
        }
    }
}

/**
 * [text] in [unit] as whole hertz, accepting "." or "," as the decimal separator; null when it is not a positive
 * number or names a fraction of a hertz. The XML Frequency stays the integer Evolution writes.
 */
internal fun fpgaFrequencyHz(text: String, unit: FpgaFrequencyUnit): Long? {
    val value = text.trim().replace(',', '.')
    if (!Regex("[0-9]{1,13}(\\.[0-9]{1,12})?").matches(value)) return null
    val whole = value.substringBefore('.')
    val fraction = value.substringAfter('.', "")
    if (fraction.drop(unit.decimals).any { it != '0' }) return null
    val hz = (whole + fraction.take(unit.decimals).padEnd(unit.decimals, '0')).toLongOrNull() ?: return null
    return hz.takeIf { it in 1..1_000_000_000_000L }
}

/** The number shown for [hz] in [unit]: whole where it can be, otherwise the exact decimal. */
internal fun fpgaFrequencyText(hz: Long, unit: FpgaFrequencyUnit): String {
    if (hz % unit.factor == 0L) return (hz / unit.factor).toString()
    val fraction = (hz % unit.factor).toString().padStart(unit.decimals, '0').trimEnd('0')
    return "${hz / unit.factor}.$fraction"
}

/** Keyboards of the board editor's typed fields: whole numbers get the number pad, the frequency a decimal pad. */
internal fun FpgaBoardField.keyboardType(): KeyboardType = when (this) {
    FpgaBoardField.JTAG, FpgaBoardField.FLASH_POSITION -> KeyboardType.Number
    FpgaBoardField.FREQUENCY -> KeyboardType.Decimal
    else -> KeyboardType.Text
}

/** The rectangle is typed as four separate whole numbers; the iOS number pad has no comma or minus. */
internal fun FpgaResourceField.keyboardType(): KeyboardType = when (this) {
    FpgaResourceField.RECTANGLE -> KeyboardType.Number
    else -> KeyboardType.Text
}

/**
 * Board picture geometry for the interactive preview. Resources live in picture pixels (740 x 400 for Evolution's
 * mapping picture); the preview converts touches once per gesture, so a rectangle never drifts by rounding.
 */
internal object FpgaBoardGeometry {
    /** Evolution's dialog refuses rectangles narrower or lower than 2 pixels. */
    const val MIN_SIZE = 2

    /** A view point in picture pixels, for a view [viewWidth] x [viewHeight] showing a [width] x [height] picture. */
    fun toPicture(x: Float, y: Float, viewWidth: Float, viewHeight: Float, width: Int, height: Int): Pair<Float, Float> =
        x * width / viewWidth to y * height / viewHeight

    fun toView(x: Float, y: Float, viewWidth: Float, viewHeight: Float, width: Int, height: Int): Pair<Float, Float> =
        x * viewWidth / width to y * viewHeight / height

    /** The rectangle a drag from ([x0], [y0]) to ([x1], [y1]) draws, in any direction, inside the picture. */
    fun fromDrag(x0: Float, y0: Float, x1: Float, y1: Float, width: Int, height: Int): FpgaRectangle {
        val left = min(x0, x1).roundToInt().coerceIn(0, width - MIN_SIZE)
        val top = min(y0, y1).roundToInt().coerceIn(0, height - MIN_SIZE)
        val right = max(x0, x1).roundToInt().coerceIn(left + MIN_SIZE, width)
        val bottom = max(y0, y1).roundToInt().coerceIn(top + MIN_SIZE, height)
        return FpgaRectangle(left, top, right - left, bottom - top)
    }

    /** [rectangle] moved by whole pixels, kept inside the picture. */
    fun moved(rectangle: FpgaRectangle, dx: Int, dy: Int, width: Int, height: Int): FpgaRectangle = rectangle.copy(
        x = (rectangle.x + dx).coerceIn(0, max(0, width - rectangle.width)),
        y = (rectangle.y + dy).coerceIn(0, max(0, height - rectangle.height)),
    )

    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** [rectangle] with [corner] dragged by whole pixels; the opposite corner stays, and the size never drops below 2. */
    fun resized(rectangle: FpgaRectangle, corner: Corner, dx: Int, dy: Int, width: Int, height: Int): FpgaRectangle {
        var left = rectangle.x
        var top = rectangle.y
        var right = rectangle.x + rectangle.width
        var bottom = rectangle.y + rectangle.height
        when (corner) {
            Corner.TOP_LEFT -> { left = (left + dx).coerceIn(0, right - MIN_SIZE); top = (top + dy).coerceIn(0, bottom - MIN_SIZE) }
            Corner.TOP_RIGHT -> { right = (right + dx).coerceIn(left + MIN_SIZE, width); top = (top + dy).coerceIn(0, bottom - MIN_SIZE) }
            Corner.BOTTOM_LEFT -> { left = (left + dx).coerceIn(0, right - MIN_SIZE); bottom = (bottom + dy).coerceIn(top + MIN_SIZE, height) }
            Corner.BOTTOM_RIGHT -> { right = (right + dx).coerceIn(left + MIN_SIZE, width); bottom = (bottom + dy).coerceIn(top + MIN_SIZE, height) }
        }
        return FpgaRectangle(left, top, right - left, bottom - top)
    }

    fun contains(rectangle: FpgaRectangle, x: Float, y: Float): Boolean =
        x >= rectangle.x && x <= rectangle.x + rectangle.width && y >= rectangle.y && y <= rectangle.y + rectangle.height

    /** The topmost (last drawn) rectangle under the point, as Evolution's board picture finds the resource to edit. */
    fun hit(x: Float, y: Float, rectangles: List<FpgaRectangle?>): Int? =
        rectangles.indices.lastOrNull { index -> rectangles[index]?.let { contains(it, x, y) } == true }

    /** The corner of [rectangle] within [radius] picture pixels of the point, nearest first. */
    fun corner(rectangle: FpgaRectangle, x: Float, y: Float, radius: Float): Corner? {
        val corners = listOf(
            Corner.TOP_LEFT to (rectangle.x to rectangle.y),
            Corner.TOP_RIGHT to (rectangle.x + rectangle.width to rectangle.y),
            Corner.BOTTOM_LEFT to (rectangle.x to rectangle.y + rectangle.height),
            Corner.BOTTOM_RIGHT to (rectangle.x + rectangle.width to rectangle.y + rectangle.height),
        )
        return corners.map { (corner, point) -> corner to max(abs(point.first - x), abs(point.second - y)) }
            .filter { it.second <= radius }.minByOrNull { it.second }?.first
    }

    fun overlaps(a: FpgaRectangle, b: FpgaRectangle): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    /** The other resources [rectangle] overlaps (FpgaBoardOverlap); a warning only, as loaded boards may overlap. */
    fun overlapping(rectangle: FpgaRectangle, rectangles: List<FpgaRectangle?>, except: Int?): List<Int> =
        rectangles.indices.filter { it != except && rectangles[it]?.let { other -> overlaps(rectangle, other) } == true }

    /** A free [w] x [h] spot for a new resource, scanning from the top left; the centre when the board is full. */
    fun freeSpot(rectangles: List<FpgaRectangle?>, w: Int, h: Int, width: Int = FpgaBoardPicture.MAPPING_WIDTH,
        height: Int = FpgaBoardPicture.MAPPING_HEIGHT): FpgaRectangle {
        for (y in 10..(height - h - 10) step 10) for (x in 10..(width - w - 10) step 10) {
            val candidate = FpgaRectangle(x, y, w, h)
            if (rectangles.none { it != null && overlaps(candidate, it) }) return candidate
        }
        return FpgaRectangle((width - w) / 2, (height - h) / 2, w, h)
    }
}

/**
 * The pins each resource type expects, from Evolution's IoComponentTypes: the default count per direction, whether it
 * is configurable (DIP switches and I/O ports), and the pin labels of getInputLabel, getOutputLabel and getIoLabel.
 */
internal object FpgaPinSchema {
    data class Section(val count: Int?, val configurable: Boolean, val label: (index: Int, count: Int) -> LocalizedText)

    private fun generic(index: Int, count: Int): LocalizedText =
        if (count == 1) message("fpga_editor_pin_single") else message("fpga_editor_pin_n", "index" to index)

    private val segments = listOf("A", "B", "C", "D", "E", "F", "G")

    private fun segment(index: Int, @Suppress("UNUSED_PARAMETER") count: Int): LocalizedText =
        segments.getOrNull(index)?.let { message("fpga_editor_segment", "segment" to it) } ?: message("fpga_editor_segment_dp")

    private fun rgb(index: Int, count: Int): LocalizedText = when (index) {
        0 -> message("fpga_editor_rgb_red")
        1 -> message("fpga_editor_rgb_green")
        2 -> message("fpga_editor_rgb_blue")
        else -> generic(index, count)
    }

    /** DipSwitch.getInputLabel numbers switches from 1. */
    private fun switch(index: Int, @Suppress("UNUSED_PARAMETER") count: Int): LocalizedText = message("fpga_editor_switch_n", "index" to index + 1)

    private val localBusInputs = listOf("SP6_LB_nCS3_i", "SP6_LB_nADV_ALE_i", "SP6_LB_RE_nOE_i", "SP6_LB_nWE_i")

    /** ReptarLocalBus labels are signal names, shown as they are. */
    private fun localBus(field: FpgaResourceField): (Int, Int) -> LocalizedText = { index, _ ->
        LocalizedText.literal(when (field) {
            FpgaResourceField.INPUT -> localBusInputs.getOrNull(index) ?: "Addr_LB_i_${index + 11}"
            FpgaResourceField.OUTPUT -> listOf("SP6_LB_WAIT3_o", "IRQ_o").getOrElse(index) { "Undefined" }
            else -> "Addr_Data_LB_io_$index"
        })
    }

    /** Null for a direction the type does not use, and for every direction of an unknown type. */
    fun section(type: String, field: FpgaResourceField, displayPins: Int?): Section? {
        fun only(direction: FpgaResourceField, section: Section) = section.takeIf { field == direction }
        return when (type.lowercase()) {
            "led" -> only(FpgaResourceField.OUTPUT, Section(1, false, ::generic))
            "button" -> only(FpgaResourceField.INPUT, Section(1, false, ::generic))
            "pin" -> only(FpgaResourceField.INOUT, Section(1, false, ::generic))
            "sevensegment" -> only(FpgaResourceField.OUTPUT, Section(8, false, ::segment))
            "sevensegmentnodp" -> only(FpgaResourceField.OUTPUT, Section(7, false, ::segment))
            "sevensegmentscanning", "ledarray" -> only(FpgaResourceField.OUTPUT, Section(displayPins, false, ::generic))
            "dipswitch" -> only(FpgaResourceField.INPUT, Section(8, true, ::switch))
            "rgbled" -> only(FpgaResourceField.OUTPUT, Section(3, false, ::rgb))
            "portio" -> only(FpgaResourceField.INOUT, Section(8, true, ::generic))
            "localbus" -> when (field) {
                FpgaResourceField.INPUT -> Section(13, false, localBus(field))
                FpgaResourceField.OUTPUT -> Section(2, false, localBus(field))
                FpgaResourceField.INOUT -> Section(16, false, localBus(field))
                else -> null
            }
            else -> null
        }
    }

    /** Evolution's count combos offer 1 to 128 pins. */
    val counts = 1..128

    val pinFields = listOf(FpgaResourceField.INPUT, FpgaResourceField.OUTPUT, FpgaResourceField.INOUT)
}

/** How the editor shows one direction's pins. */
internal sealed interface FpgaPinLayout {
    /** One labelled field per pin; a configurable count has a count menu. */
    data class PerPin(val count: Int, val configurable: Boolean, val label: (Int, Int) -> LocalizedText) : FpgaPinLayout
    /** A comma-separated list, for pins that do not match the type (a custom board), with the count the type expects. */
    data class FreeList(val expected: Int?) : FpgaPinLayout
    data object Hidden : FpgaPinLayout
}

/** The pin entries of [field] by position; blank entries stay, so a pin keeps its label while others are empty. */
internal fun FpgaResourceEditorDraft.pinEntries(field: FpgaResourceField): List<String> {
    val raw = fields[field].orEmpty()
    return if (raw.isBlank()) emptyList() else raw.split(',').map(String::trim)
}

/**
 * Whether the pins fit the type's schema: no pins in a direction the type does not use, and no more than a fixed
 * count. A board whose resources do not fit stays editable as free lists, and nothing is truncated without asking.
 */
internal val FpgaResourceEditorDraft.fitsPinSchema: Boolean get() = FpgaPinSchema.pinFields.all { field ->
    val section = FpgaPinSchema.section(type, field, requiredDisplayPins)
    val entries = pinEntries(field)
    when {
        section == null -> entries.isEmpty()
        section.configurable -> entries.size <= FpgaPinSchema.counts.last
        else -> section.count != null && entries.size <= section.count
    }
}

internal fun FpgaResourceEditorDraft.pinLayout(field: FpgaResourceField): FpgaPinLayout {
    val section = FpgaPinSchema.section(type, field, requiredDisplayPins)
    val entries = pinEntries(field)
    if (!fitsPinSchema) return if (entries.isEmpty() && section == null) FpgaPinLayout.Hidden else FpgaPinLayout.FreeList(section?.count)
    return when {
        section == null -> FpgaPinLayout.Hidden
        section.configurable -> FpgaPinLayout.PerPin(entries.size.takeIf { it > 0 } ?: section.count ?: 1, true, section.label)
        else -> FpgaPinLayout.PerPin(section.count ?: 0, false, section.label)
    }
}

/** Sets pin [index] of [count]; the list keeps its positions, joined with commas as InputPinSet and the others are. */
internal fun FpgaResourceEditorDraft.withPin(field: FpgaResourceField, index: Int, count: Int, value: String): FpgaResourceEditorDraft {
    val entries = pinEntries(field).toMutableList()
    while (entries.size < count) entries += ""
    entries[index] = value.trim()
    return copy(fields = fields + (field to entries.joinToString(",")))
}

/** A configurable count: fewer pins drop the last entries, more add empty ones. */
internal fun FpgaResourceEditorDraft.withPinCount(field: FpgaResourceField, count: Int): FpgaResourceEditorDraft {
    val entries = pinEntries(field).take(count).toMutableList()
    while (entries.size < count) entries += ""
    return copy(fields = fields + (field to entries.joinToString(",")))
}

/** Keeps the first [count] pins, the explicit choice offered when a type expects fewer than the list has. */
internal fun FpgaResourceEditorDraft.truncatedPins(field: FpgaResourceField, count: Int): FpgaResourceEditorDraft =
    copy(fields = fields + (field to pinEntries(field).take(count).joinToString(",")))

/** The four rectangle numbers as typed; a malformed value gives empty parts. */
internal val FpgaResourceEditorDraft.rectangleParts: List<String> get() {
    val parts = fields[FpgaResourceField.RECTANGLE].orEmpty().split(',').map(String::trim)
    return List(4) { parts.getOrElse(it) { "" } }
}

internal fun FpgaResourceEditorDraft.withRectanglePart(index: Int, value: String): FpgaResourceEditorDraft =
    copy(fields = fields + (FpgaResourceField.RECTANGLE to rectangleParts.toMutableList().also { it[index] = value.trim() }.joinToString(",")))

internal fun FpgaResourceEditorDraft.withRectangle(rectangle: FpgaRectangle): FpgaResourceEditorDraft =
    copy(fields = fields + (FpgaResourceField.RECTANGLE to "${rectangle.x},${rectangle.y},${rectangle.width},${rectangle.height}"))

/** IoComponentTypes.INPUT_COMPONENT_SET; a pull read from a file stays visible on other types. */
internal val FpgaResourceEditorDraft.showsPull: Boolean get() =
    type.lowercase() in setOf("button", "pin", "dipswitch") || pull != FpgaPull.FLOAT ||
        FpgaBoardWriter.resourceTypes.none { it.equals(type, true) }

/** Evolution asks for the activity level of every type outside IN_OUT_COMPONENT_SET; a file's value stays visible. */
internal val FpgaResourceEditorDraft.showsActivity: Boolean get() = type.lowercase() !in setOf("pin", "portio") || activeLow

/**
 * A problem with one field of the board or of resource [resourceIndex]; [pin] is the position of a per-pin entry.
 * [blank] marks a value not entered yet, shown after the first Save so a new board does not start in red.
 */
internal data class FpgaEditorFieldError(
    val field: Enum<*>?,
    val resourceIndex: Int?,
    val text: LocalizedText,
    val pin: Int? = null,
    val blank: Boolean = false,
)

private val physicalPin = Regex("[A-Za-z0-9_]+")
private val rotations = setOf(0, 90, -90, 180)

/**
 * The board writer's and codec's rules, checked field by field so the editor can mark each one live. The writer
 * (FpgaBoardWriter.write) stays the final authority on Save.
 */
internal fun FpgaBoardEditorDraft.validate(
    pictureWidth: Int = FpgaBoardPicture.MAPPING_WIDTH,
    pictureHeight: Int = FpgaBoardPicture.MAPPING_HEIGHT,
): List<FpgaEditorFieldError> = buildList {
    fun value(field: FpgaBoardField) = fields[field].orEmpty().trim()
    fun board(field: FpgaBoardField?, text: LocalizedText, blank: Boolean = false) = add(FpgaEditorFieldError(field, null, text, blank = blank))
    val name = value(FpgaBoardField.NAME)
    if (!(name.length in 1..120 && (name.first().isLetter() || name.first() == '_') && name.all { it.isLetterOrDigit() || it in "_.-" }))
        board(FpgaBoardField.NAME, message("fpga_editor_invalid_board_name"), blank = name.isEmpty())
    for (field in listOf(FpgaBoardField.JTAG, FpgaBoardField.FLASH_POSITION)) {
        if (value(field).toIntOrNull() == null) board(field, message("fpga_editor_whole_number"), blank = value(field).isEmpty())
    }
    if (frequencyHz == null) board(FpgaBoardField.FREQUENCY, message("fpga_editor_frequency_invalid"), blank = value(FpgaBoardField.FREQUENCY).isEmpty())
    if (!physicalPin.matches(value(FpgaBoardField.CLOCK_PIN)))
        board(FpgaBoardField.CLOCK_PIN, message("fpga_core_missing_clock_pin"), blank = value(FpgaBoardField.CLOCK_PIN).isEmpty())
    if (resources.isEmpty()) board(null, message("fpga_core_no_resources"))
    resources.forEachIndexed { index, resource -> addAll(resource.validate(index, pictureWidth, pictureHeight)) }
}

internal fun FpgaResourceEditorDraft.validate(index: Int, pictureWidth: Int, pictureHeight: Int): List<FpgaEditorFieldError> = buildList {
    fun error(field: FpgaResourceField?, text: LocalizedText, pin: Int? = null, blank: Boolean = false) =
        add(FpgaEditorFieldError(field, index, text, pin, blank))
    fun value(field: FpgaResourceField) = fields[field].orEmpty().trim()
    if (FpgaBoardWriter.resourceTypes.none { it.equals(type, ignoreCase = true) })
        error(null, message("fpga_editor_invalid_resource_type", "type" to type))
    val numbers = rectangleParts.map { it.toIntOrNull() }
    if (numbers.any { it == null }) {
        error(FpgaResourceField.RECTANGLE, message("fpga_editor_rect_numbers"), blank = rectangleParts.all { it.isEmpty() })
    } else {
        val (x, y, width, height) = numbers.map { it!! }
        if (width <= 0 || height <= 0) error(FpgaResourceField.RECTANGLE, message("fpga_editor_rect_size"))
        else if (x < 0 || y < 0 || x.toLong() + width > pictureWidth || y.toLong() + height > pictureHeight)
            error(FpgaResourceField.RECTANGLE, message("fpga_editor_rect_outside", "width" to pictureWidth, "height" to pictureHeight))
    }
    value(FpgaResourceField.DRIVE).takeIf { it.isNotEmpty() }?.let { drive ->
        if (drive.toIntOrNull() !in FpgaBoardChoices.driveMilliamps) error(FpgaResourceField.DRIVE, message("fpga_editor_value_not_supported"))
    }
    if (showsRotation) value(FpgaResourceField.ROTATION).takeIf { it.isNotEmpty() }?.let { rotation ->
        if (rotation.toIntOrNull() !in rotations) error(FpgaResourceField.ROTATION, message("fpga_editor_value_not_supported"))
    }
    FpgaResourceEditorDraft.displayAttribute(type)?.let {
        val settings = FpgaDisplaySettings.parse(value(FpgaResourceField.DISPLAY))
        val rows = settings.rows.toIntOrNull()
        val columns = settings.columns.toIntOrNull()
        val ledArray = type.equals("LedArray", ignoreCase = true)
        val modes = (if (ledArray) FpgaBoardChoices.ledArrayModes else FpgaBoardChoices.scanningModes).map { mode -> mode.first }
        if (rows == null || rows !in 1..128 || columns == null || (columns !in 1..128 && (ledArray || columns != -1)) ||
            settings.mode !in modes)
            error(FpgaResourceField.DISPLAY, message("fpga_editor_invalid_field", "field" to message("fpga_editor_display_configuration")))
    }
    requiredDisplayPins?.let { required ->
        // Per-pin fields report each missing pin themselves; a free list is checked as a whole.
        if (displayPinsMatch == false && pinLayout(FpgaResourceField.OUTPUT) !is FpgaPinLayout.PerPin)
            error(FpgaResourceField.OUTPUT, LocalizedText("fpga_editor_display_pins", emptyMap(), required.toLong()))
    }
    val seen = mutableMapOf<String, Pair<FpgaResourceField, Int>>()
    for (field in FpgaPinSchema.pinFields) {
        val perPin = pinLayout(field) as? FpgaPinLayout.PerPin
        val entries = pinEntries(field)
        // A section left entirely empty is what the writer accepts for a board read from a file (MAX_V_CONSOLE's RGB
        // LED has no pins); a new resource, or a partly filled section whose gaps would shift the pins, must be complete.
        val complete = perPin != null && (entries.any { it.isNotEmpty() } || attributes.isEmpty())
        val positions = if (complete) (0 until perPin!!.count).map { it to entries.getOrElse(it) { "" } }
            else entries.withIndex().filter { it.value.isNotEmpty() }.map { it.index to it.value }
        for ((position, location) in positions) {
            val pin = position.takeIf { perPin != null }
            when {
                location.isEmpty() -> error(field, message("fpga_editor_pin_required"), pin, blank = true)
                !physicalPin.matches(location) -> error(field, message("fpga_editor_pin_invalid"), pin)
                seen.put(location.uppercase(), field to position) != null ->
                    error(field, message("fpga_editor_pin_duplicate", "pin" to location), pin)
            }
        }
    }
}

/** "4. LED": the resource as the editor's resource menu names it, numbered from 1. */
internal fun FpgaResourceEditorDraft.menuName(index: Int): LocalizedText =
    message("fpga_editor_resource_name", "index" to index + 1,
        "name" to (fields[FpgaResourceField.LABEL]?.takeIf { it.isNotBlank() }?.let(LocalizedText::literal) ?: FpgaLabels.resourceType(type)))
