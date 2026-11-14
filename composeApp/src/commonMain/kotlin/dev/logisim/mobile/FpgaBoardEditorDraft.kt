package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.FpgaBoardPin
import dev.logisim.mobile.core.FpgaBoardResource
import dev.logisim.mobile.core.FpgaClock
import dev.logisim.mobile.core.FpgaDevice
import dev.logisim.mobile.core.FpgaElectrical
import dev.logisim.mobile.core.FpgaPinDirection
import dev.logisim.mobile.core.FpgaPull
import dev.logisim.mobile.core.FpgaRectangle
import dev.logisim.mobile.core.FpgaVendor
import dev.logisim.mobile.core.fpgaDisplayPinCount
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

internal enum class FpgaBoardField(val label: String) {
    NAME("fpga_editor_board_name"),
    FAMILY("fpga_editor_family"),
    PART("fpga_editor_part"),
    PACKAGE("fpga_editor_package"),
    SPEED("fpga_editor_speed"),
    JTAG("fpga_editor_jtag"),
    FLASH_NAME("fpga_editor_flash_name"),
    FLASH_POSITION("fpga_editor_flash_position"),
    CLOCK_PIN("fpga_editor_clock_pin"),
    FREQUENCY("fpga_editor_frequency"),
    CLOCK_STANDARD("fpga_editor_io_standard"),
}

internal data class FpgaBoardEditorDraft(
    val original: FpgaBoardDefinition?,
    val fields: Map<FpgaBoardField, String>,
    val vendor: FpgaVendor = original?.device?.vendor ?: FpgaVendor.VIVADO,
    val usbTmc: Boolean = original?.device?.usbTmc ?: false,
    val clockPull: FpgaPull = original?.clock?.electrical?.pull ?: FpgaPull.FLOAT,
    val unusedPull: FpgaPull = original?.unusedPinPull ?: FpgaPull.FLOAT,
    val resources: List<FpgaResourceEditorDraft> = original?.resources.orEmpty().map(FpgaResourceEditorDraft::from),
    /** The unit [FpgaBoardField.FREQUENCY] is typed in; the board stores whole hertz. */
    val frequencyUnit: FpgaFrequencyUnit = FpgaFrequencyUnit.of(original?.clock?.frequencyHz),
) {
    /** The typed clock frequency in whole hertz, or null when it is not one. */
    val frequencyHz: Long? get() = fpgaFrequencyHz(fields[FpgaBoardField.FREQUENCY].orEmpty(), frequencyUnit)

    fun board(): FpgaBoardDefinition {
        fun value(field: FpgaBoardField) = fields[field].orEmpty().trim()
        fun integer(field: FpgaBoardField) = value(field).toIntOrNull() ?: invalid(field.label)
        val device = FpgaDevice(vendor, value(FpgaBoardField.FAMILY), value(FpgaBoardField.PART),
            value(FpgaBoardField.PACKAGE), value(FpgaBoardField.SPEED), integer(FpgaBoardField.JTAG),
            integer(FpgaBoardField.FLASH_POSITION), value(FpgaBoardField.FLASH_NAME), usbTmc)
        val clock = FpgaClock(value(FpgaBoardField.CLOCK_PIN),
            frequencyHz ?: invalid(FpgaBoardField.FREQUENCY.label),
            FpgaElectrical(value(FpgaBoardField.CLOCK_STANDARD).ifBlank { null }, clockPull))
        return FpgaBoardDefinition(value(FpgaBoardField.NAME), device, clock,
            resources.mapIndexed { index, resource -> resource.resource("resource-$index") }, unusedPull, original?.originalXml)
    }

    companion object {
        fun from(board: FpgaBoardDefinition?): FpgaBoardEditorDraft {
            val device = board?.device
            val clock = board?.clock
            return FpgaBoardEditorDraft(board, mapOf(
                FpgaBoardField.NAME to (board?.name ?: "NewBoard"),
                FpgaBoardField.FAMILY to device?.family.orEmpty(),
                FpgaBoardField.PART to device?.part.orEmpty(),
                FpgaBoardField.PACKAGE to device?.packageName.orEmpty(),
                FpgaBoardField.SPEED to device?.speedGrade.orEmpty(),
                FpgaBoardField.JTAG to (device?.jtagPosition ?: 1).toString(),
                FpgaBoardField.FLASH_NAME to device?.flashName.orEmpty(),
                FpgaBoardField.FLASH_POSITION to (device?.flashPosition ?: 2).toString(),
                FpgaBoardField.CLOCK_PIN to clock?.location.orEmpty(),
                FpgaBoardField.FREQUENCY to clock?.frequencyHz?.let { fpgaFrequencyText(it, FpgaFrequencyUnit.of(it)) }.orEmpty(),
                FpgaBoardField.CLOCK_STANDARD to clock?.electrical?.ioStandard.orEmpty(),
            ))
        }
    }
}

internal enum class FpgaResourceField(val label: String) {
    LABEL("fpga_editor_resource_label"),
    RECTANGLE("fpga_editor_rectangle"),
    INPUT("fpga_editor_input_pins"),
    OUTPUT("fpga_editor_output_pins"),
    INOUT("fpga_editor_inout_pins"),
    STANDARD("fpga_editor_io_standard"),
    DRIVE("fpga_editor_drive"),
    ROTATION("fpga_editor_rotation"),
    DISPLAY("fpga_editor_display_configuration"),
}

internal data class FpgaResourceEditorDraft(
    val type: String = "Pin",
    val fields: Map<FpgaResourceField, String> = mapOf(FpgaResourceField.RECTANGLE to "0,0,20,20"),
    val pull: FpgaPull = FpgaPull.FLOAT,
    val activeLow: Boolean = false,
    val attributes: Map<String, String> = emptyMap(),
) {
    val displayLabel: String get() = fields[FpgaResourceField.LABEL].orEmpty().ifBlank { type }

    val rectangle: FpgaRectangle? get() {
        val parts = fields[FpgaResourceField.RECTANGLE].orEmpty().split(',')
        if (parts.size != 4) return null
        val (x, y, width, height) = parts.map { it.trim().toIntOrNull() ?: return null }
        return FpgaRectangle(x, y, width, height)
    }

    /** Output pins the display configuration needs, or null for other types and configurations Mobile cannot drive. */
    val requiredDisplayPins: Int? get() =
        displayAttribute(type)?.let { fpgaDisplayPinCount(type, fields[FpgaResourceField.DISPLAY].orEmpty()) }

    /** The pin locations entered in [field]. A stray comma adds no pin. */
    fun locations(field: FpgaResourceField): List<String> =
        fields[field].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)

    /**
     * Whether the pins fit the display configuration: output pins only, exactly as many as it drives. Null where no
     * configuration applies. The editor's error marks and Save use this one check.
     */
    val displayPinsMatch: Boolean? get() = requiredDisplayPins?.let { required ->
        locations(FpgaResourceField.INPUT).isEmpty() && locations(FpgaResourceField.INOUT).isEmpty() &&
            locations(FpgaResourceField.OUTPUT).size == required
    }

    /**
     * Evolution's dialog shows the drive strength only for output components. A value read from a file stays visible
     * on other types too, so it is never kept or written unseen.
     */
    val showsDrive: Boolean get() =
        type.lowercase() in FpgaBoardChoices.outputTypes || fields[FpgaResourceField.DRIVE].orEmpty().isNotBlank()

    /** IoComponentTypes.hasRotationAttribute. */
    val showsRotation: Boolean get() = type.lowercase() in FpgaBoardChoices.rotationTypes

    /**
     * Changes the type. Rotation and drive strength stay in the draft even where the new type hides them, so changing
     * back restores them; a hidden rotation is not written (see [resource]). A display type starts from Evolution's
     * default configuration when it has none.
     */
    fun withType(next: String): FpgaResourceEditorDraft {
        val keepsDisplay = displayAttribute(next) == displayAttribute(type)
        val values = if (keepsDisplay) fields else fields - FpgaResourceField.DISPLAY
        val display = FpgaDisplaySettings.defaultFor(next)
            ?.takeIf { values[FpgaResourceField.DISPLAY].isNullOrBlank() }
            ?.let { mapOf(FpgaResourceField.DISPLAY to it) }.orEmpty()
        return copy(type = next, fields = values + display)
    }

    fun resource(id: String): FpgaBoardResource {
        fun value(field: FpgaResourceField) = fields[field].orEmpty().trim()
        val drive = value(FpgaResourceField.DRIVE).takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull()?.takeIf { drive -> drive in FpgaBoardChoices.driveMilliamps } ?: invalid(FpgaResourceField.DRIVE.label)
        }
        val electrical = FpgaElectrical(value(FpgaResourceField.STANDARD).ifBlank { null }, pull, drive, activeLow)
        val pins = buildList {
            for ((direction, field) in pinFields) {
                for (pin in locations(field)) add(FpgaBoardPin(id, size, pin, direction, electrical))
            }
        }
        val extra = attributes.toMutableMap()
        extra.remove("rotation")
        extra.remove("LedArrayInfo")
        extra.remove("ScanningSevenSegInfo")
        // FpgaIoInformationContainer writes the rotation only for types that have one, so a hidden value is never saved.
        if (showsRotation) value(FpgaResourceField.ROTATION).takeIf { it.isNotEmpty() }?.let { extra["rotation"] = it }
        displayAttribute(type)?.let { key -> value(FpgaResourceField.DISPLAY).takeIf { it.isNotEmpty() }?.let { extra[key] = it } }
        // Evolution's dialog regenerates the pin fields for the configuration; here the pin list must match it.
        requiredDisplayPins?.let { required ->
            if (displayPinsMatch == false) {
                throw LocalizedArgumentException(LocalizedText("fpga_editor_display_pin_count",
                    mapOf("resource" to value(FpgaResourceField.LABEL).ifBlank { type }), required.toLong()))
            }
        }
        return FpgaBoardResource(id, type, value(FpgaResourceField.LABEL),
            rectangle ?: invalid(FpgaResourceField.RECTANGLE.label), pins, extra)
    }

    companion object {
        private val pinFields = listOf(FpgaPinDirection.INPUT to FpgaResourceField.INPUT,
            FpgaPinDirection.OUTPUT to FpgaResourceField.OUTPUT, FpgaPinDirection.INOUT to FpgaResourceField.INOUT)

        fun displayAttribute(type: String) = when (type.lowercase()) {
            "ledarray" -> "LedArrayInfo"
            "sevensegmentscanning" -> "ScanningSevenSegInfo"
            else -> null
        }

        fun from(resource: FpgaBoardResource): FpgaResourceEditorDraft {
            val electrical = resource.pins.firstOrNull()?.electrical ?: FpgaElectrical()
            val fields = mapOf(
                FpgaResourceField.LABEL to resource.label,
                FpgaResourceField.RECTANGLE to resource.rectangle?.let { "${it.x},${it.y},${it.width},${it.height}" }.orEmpty(),
                FpgaResourceField.STANDARD to electrical.ioStandard.orEmpty(),
                FpgaResourceField.DRIVE to electrical.driveMilliamps?.toString().orEmpty(),
                FpgaResourceField.ROTATION to resource.attributes["rotation"].orEmpty(),
                FpgaResourceField.DISPLAY to displayAttribute(resource.type)?.let { resource.attributes[it] }.orEmpty(),
            ) + pinFields.associate { (direction, field) ->
                field to resource.pins.filter { it.direction == direction }.joinToString(",") { it.location }
            }
            return FpgaResourceEditorDraft(resource.type, fields, electrical.pull, electrical.activeLow, resource.attributes)
        }
    }
}

/** One option of a closed choice set. [title] is a message; [fromFile] marks a loaded value outside the set. */
internal data class FpgaBoardOption(val value: String, val title: LocalizedText, val fromFile: Boolean = false)

/**
 * The closed sets of Evolution's FpgaIoInformationSettingsDialog. The stored strings are the ones the board writer
 * already reads, so a board keeps its XML attributes when only the presentation changes. An empty value stands for
 * Evolution's "Default" (no attribute written for a board read from a file).
 */
internal object FpgaBoardChoices {
    /** IoStandards.BEHAVIOR_STRINGS. */
    val ioStandards = listOf("LVCMOS12", "LVCMOS15", "LVCMOS18", "LVCMOS25", "LVCMOS33", "LVTTL")
    /** DriveStrength.BEHAVIOR_STRINGS, stored as the bare number; the writer appends " mA". */
    val driveMilliamps = listOf(2, 4, 8, 16, 24)
    /** IoComponentTypes.OUTPUT_COMPONENT_SET. */
    val outputTypes = setOf("led", "pin", "rgbled", "sevensegment", "sevensegmentnodp", "sevensegmentscanning", "ledarray")
    /** IoComponentTypes.hasRotationAttribute. */
    val rotationTypes = setOf("dipswitch", "sevensegment", "sevensegmentnodp", "sevensegmentscanning", "ledarray")
    /** LedArrayDriving and SevenSegmentScanningDriving, in Evolution's order. */
    val ledArrayModes = listOf("LedDefault" to "fpga_editor_mode_led_default", "LedRowScanning" to "fpga_editor_mode_led_rows",
        "LedColumnScanning" to "fpga_editor_mode_led_columns", "RgbDefault" to "fpga_editor_mode_rgb_default",
        "RgbRowScanning" to "fpga_editor_mode_rgb_rows", "RgbColScanning" to "fpga_editor_mode_rgb_columns")
    val scanningModes = listOf("SevenSegDecoded" to "fpga_editor_mode_seg_decoded",
        "SevenSegScanningActiveLow" to "fpga_editor_mode_seg_low", "SevenSegScanningActiveHi" to "fpga_editor_mode_seg_high")
    /** The combo ranges of Evolution's dialog; the writer accepts up to 128, so larger loaded values stay as they are. */
    val arraySizes = 1..32
    val digits = 2..15
    val decodedBits = 1..5

    fun ioStandard(current: String): List<FpgaBoardOption> =
        withCurrent(listOf(FpgaBoardOption("", message("fpga_editor_default"))) +
            ioStandards.map { FpgaBoardOption(it, LocalizedText.literal(it)) }, current)

    fun drive(current: String): List<FpgaBoardOption> =
        withCurrent(listOf(FpgaBoardOption("", message("fpga_editor_default"))) +
            driveMilliamps.map { FpgaBoardOption(it.toString(), message("fpga_editor_drive_value", "drive" to it)) }, current)

    /**
     * Evolution's rotation combo: zero, clockwise (-90), counter-clockwise (90) and 180, labelled by where the first
     * switch, segment a or LED ends up. Zero is stored as no attribute, as Evolution writes it; a file's explicit "0"
     * is kept.
     */
    fun rotation(type: String, current: String): List<FpgaBoardOption> {
        val prefix = when (type.lowercase()) {
            "dipswitch" -> "fpga_editor_rotation_dip_"
            "ledarray" -> "fpga_editor_rotation_led_"
            else -> "fpga_editor_rotation_seg_"
        }
        val zero = if (current == "0") "0" else ""
        return withCurrent(listOf(zero to "zero", "-90" to "cw90", "90" to "ccw90", "180" to "180").map { (value, suffix) ->
            FpgaBoardOption(value, message(prefix + suffix))
        }, current)
    }

    fun numbers(range: IntRange, current: String): List<FpgaBoardOption> =
        withCurrent(range.map { FpgaBoardOption(it.toString(), LocalizedText.literal(it.toString())) }, current)

    fun modes(modes: List<Pair<String, String>>, current: String): List<FpgaBoardOption> =
        withCurrent(modes.map { (value, key) -> FpgaBoardOption(value, message(key)) }, current)

    /** A loaded value outside the set is offered as "<value> (from file)", so it is never rewritten silently. */
    private fun withCurrent(options: List<FpgaBoardOption>, current: String): List<FpgaBoardOption> =
        if (current.isEmpty() || options.any { it.value == current }) options
        else options + FpgaBoardOption(current, message("fpga_editor_from_file", "value" to current), fromFile = true)
}

/**
 * The "rows,columns,Mode" attribute of an LED array or a scanning seven-segment display, edited field by field. For a
 * scanning display the rows are digits and the columns the decoder's address bits, -1 without a decoder.
 */
internal data class FpgaDisplaySettings(val rows: String, val columns: String, val mode: String) {
    /** validateDisplay's join: no spaces. */
    val encoded: String get() = "$rows,$columns,$mode"

    fun withRows(value: String, sevenSegment: Boolean): FpgaDisplaySettings =
        copy(rows = value).let { if (sevenSegment) it.withDecoderWidth() else it }

    /** The columns, or a scanning display's address bits, which never drop below what its digits need. */
    fun withColumns(value: String, sevenSegment: Boolean): FpgaDisplaySettings =
        copy(columns = value).let { if (sevenSegment) it.withDecoderWidth() else it }

    fun withMode(value: String, sevenSegment: Boolean): FpgaDisplaySettings = when {
        !sevenSegment -> copy(mode = value)
        value == DECODED -> copy(mode = value, columns = columns.takeIf { it != "-1" } ?: "1").withDecoderWidth()
        mode == DECODED -> copy(mode = value, columns = "-1")
        else -> copy(mode = value)
    }

    /** Evolution raises the address bits to what the digit count needs (updateScanningRequirements). */
    private fun withDecoderWidth(): FpgaDisplaySettings {
        if (mode != DECODED) return this
        val digits = rows.toIntOrNull() ?: return this
        val needed = (32 - (digits - 1).coerceAtLeast(0).countLeadingZeroBits()).coerceAtLeast(1)
        val current = columns.toIntOrNull() ?: return copy(columns = needed.toString())
        return if (current < needed) copy(columns = needed.toString()) else this
    }

    companion object {
        const val DECODED = "SevenSegDecoded"

        fun parse(raw: String): FpgaDisplaySettings {
            val parts = raw.split(',').map(String::trim)
            return if (parts.size == 3) FpgaDisplaySettings(parts[0], parts[1], parts[2]) else FpgaDisplaySettings("", "", "")
        }

        /** Evolution starts a new LED array as a single-pin 8 x 8 array and a scanning display as four digits. */
        fun defaultFor(type: String): String? = when (FpgaResourceEditorDraft.displayAttribute(type)) {
            "LedArrayInfo" -> "8,8,LedDefault"
            "ScanningSevenSegInfo" -> "4,-1,SevenSegScanningActiveLow"
            else -> null
        }
    }
}

internal data class FpgaRemovedResource(val index: Int, val draft: FpgaBoardEditorDraft)

/**
 * The board editor's work lives with the FPGA tool's session, not in the sheet's composition: dismissing the sheet
 * keeps the draft, and reopening the tool returns to it.
 */
class FpgaBoardEditorSession {
    internal var open by mutableStateOf(false)
        private set
    internal var board by mutableStateOf<FpgaBoardDefinition?>(null)
        private set
    internal var draft by mutableStateOf(FpgaBoardEditorDraft.from(null))
        private set
    internal var replacement by mutableStateOf<BoardImage?>(null)
    internal var selected by mutableStateOf(0)
    /** The last removed resource, until the next edit. */
    internal var removed by mutableStateOf<FpgaRemovedResource?>(null)
        private set
    internal var confirmingDiscard by mutableStateOf(false)
    /** The draft before the last move or resize on the board picture, until the next edit. */
    internal var rectangleUndo by mutableStateOf<FpgaBoardEditorDraft?>(null)
        private set
    /** After a Save attempt, missing values are marked too, not only wrong ones. */
    internal var showAllErrors by mutableStateOf(false)

    internal val dirty: Boolean get() = draft != FpgaBoardEditorDraft.from(board) || replacement != null

    internal fun start(board: FpgaBoardDefinition?) {
        close()
        this.board = board
        draft = FpgaBoardEditorDraft.from(board)
        open = true
    }

    internal fun edit(next: FpgaBoardEditorDraft) {
        draft = next
        removed = null
        rectangleUndo = null
    }

    /** A rectangle moved or resized on the picture; Undo restores it, as accidental drags must not cost placed resources. */
    internal fun changeRectangle(index: Int, rectangle: FpgaRectangle) {
        val resource = draft.resources.getOrNull(index) ?: return
        if (resource.rectangle == rectangle) return
        val before = draft
        edit(draft.copy(resources = draft.resources.mapIndexed { position, current ->
            if (position == index) current.withRectangle(rectangle) else current
        }))
        selected = index
        rectangleUndo = before
    }

    internal fun undoRectangle() {
        draft = rectangleUndo ?: return
        rectangleUndo = null
    }

    internal fun editResource(updated: FpgaResourceEditorDraft) = edit(draft.copy(resources = draft.resources.mapIndexed { index, current ->
        if (index == selected) updated else current
    }))

    /** A new resource starts in a free spot of the picture, not on top of the others at 0,0. */
    internal fun addResource() {
        val spot = FpgaBoardGeometry.freeSpot(draft.resources.map { it.rectangle }, 20, 20)
        edit(draft.copy(resources = draft.resources + FpgaResourceEditorDraft().withRectangle(spot)))
        selected = draft.resources.lastIndex
    }

    /** A resource drawn on the picture, with the type chosen for it. */
    internal fun addResource(rectangle: FpgaRectangle, type: String) {
        edit(draft.copy(resources = draft.resources + FpgaResourceEditorDraft().withType(type).withRectangle(rectangle)))
        selected = draft.resources.lastIndex
    }

    internal fun removeResource() {
        val index = selected
        if (index !in draft.resources.indices) return
        val before = draft
        edit(draft.copy(resources = draft.resources.filterIndexed { position, _ -> position != index }))
        removed = FpgaRemovedResource(index, before)
        selected = index.coerceAtMost(draft.resources.lastIndex).coerceAtLeast(0)
    }

    internal fun undoRemove() {
        val removal = removed ?: return
        draft = removal.draft
        selected = removal.index
        removed = null
    }

    /** Cancel closes a clean editor, and asks first when there are unsaved changes. */
    internal fun cancel() {
        if (dirty) confirmingDiscard = true else close()
    }

    internal fun close() {
        open = false
        board = null
        draft = FpgaBoardEditorDraft.from(null)
        replacement = null
        selected = 0
        removed = null
        confirmingDiscard = false
        rectangleUndo = null
        showAllErrors = false
    }
}

private fun invalid(label: String): Nothing =
    throw LocalizedArgumentException(message("fpga_editor_invalid_field", "field" to message(label)))
