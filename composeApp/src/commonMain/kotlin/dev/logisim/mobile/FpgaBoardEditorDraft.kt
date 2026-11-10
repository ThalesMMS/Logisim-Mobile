package dev.logisim.mobile

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
import dev.logisim.mobile.core.localization.LocalizedArgumentException
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
    FREQUENCY("fpga_editor_clock_frequency"),
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
) {
    fun board(): FpgaBoardDefinition {
        fun value(field: FpgaBoardField) = fields[field].orEmpty().trim()
        fun integer(field: FpgaBoardField) = value(field).toIntOrNull() ?: invalid(field.label)
        val device = FpgaDevice(vendor, value(FpgaBoardField.FAMILY), value(FpgaBoardField.PART),
            value(FpgaBoardField.PACKAGE), value(FpgaBoardField.SPEED), integer(FpgaBoardField.JTAG),
            integer(FpgaBoardField.FLASH_POSITION), value(FpgaBoardField.FLASH_NAME), usbTmc)
        val clock = FpgaClock(value(FpgaBoardField.CLOCK_PIN),
            value(FpgaBoardField.FREQUENCY).toLongOrNull() ?: invalid(FpgaBoardField.FREQUENCY.label),
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
                FpgaBoardField.FREQUENCY to clock?.frequencyHz?.toString().orEmpty(),
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

    fun resource(id: String): FpgaBoardResource {
        fun value(field: FpgaResourceField) = fields[field].orEmpty().trim()
        val drive = value(FpgaResourceField.DRIVE).takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull() ?: invalid(FpgaResourceField.DRIVE.label)
        }
        val electrical = FpgaElectrical(value(FpgaResourceField.STANDARD).ifBlank { null }, pull, drive, activeLow)
        val pins = buildList {
            for ((direction, field) in pinFields) {
                val locations = value(field)
                if (locations.isEmpty()) continue
                for (pin in locations.split(',')) add(FpgaBoardPin(id, size, pin.trim(), direction, electrical))
            }
        }
        val extra = attributes.toMutableMap()
        extra.remove("rotation")
        extra.remove("LedArrayInfo")
        extra.remove("ScanningSevenSegInfo")
        value(FpgaResourceField.ROTATION).takeIf { it.isNotEmpty() }?.let { extra["rotation"] = it }
        displayAttribute(type)?.let { key -> value(FpgaResourceField.DISPLAY).takeIf { it.isNotEmpty() }?.let { extra[key] = it } }
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

private fun invalid(label: String): Nothing =
    throw LocalizedArgumentException(message("fpga_editor_invalid_field", "field" to message(label)))
