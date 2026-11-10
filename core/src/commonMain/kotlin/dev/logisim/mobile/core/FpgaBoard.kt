package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

enum class FpgaVendor { ALTERA, XILINX, VIVADO, OPEN_FPGA, UNKNOWN }
enum class FpgaPinDirection { INPUT, OUTPUT, INOUT }
enum class FpgaPull { FLOAT, UP, DOWN }

data class FpgaRectangle(val x: Int, val y: Int, val width: Int, val height: Int)

data class FpgaElectrical(
    val ioStandard: String? = null,
    val pull: FpgaPull = FpgaPull.FLOAT,
    val driveMilliamps: Int? = null,
    val activeLow: Boolean = false,
)

data class FpgaBoardPin(
    val resourceId: String,
    val index: Int,
    val location: String,
    val direction: FpgaPinDirection,
    val electrical: FpgaElectrical,
)

data class FpgaBoardResource(
    val id: String,
    val type: String,
    val label: String,
    val rectangle: FpgaRectangle?,
    val pins: List<FpgaBoardPin>,
    val attributes: Map<String, String> = emptyMap(),
)

data class FpgaDevice(
    val vendor: FpgaVendor,
    val family: String,
    val part: String,
    val packageName: String,
    val speedGrade: String,
    val jtagPosition: Int = 1,
    val flashPosition: Int = 2,
    val flashName: String = "",
    val usbTmc: Boolean = false,
)

data class FpgaClock(
    val location: String,
    val frequencyHz: Long,
    val electrical: FpgaElectrical,
)

data class FpgaBoardDefinition(
    val name: String,
    val device: FpgaDevice,
    val clock: FpgaClock?,
    val resources: List<FpgaBoardResource>,
    val unusedPinPull: FpgaPull = FpgaPull.FLOAT,
    val originalXml: String? = null,
) {
    val pins: List<FpgaBoardPin> get() = resources.flatMap { it.pins }
}

sealed interface FpgaBoardImportResult {
    data class Success(val board: FpgaBoardDefinition, val warningNotices: List<LocalizedText> = emptyList()) : FpgaBoardImportResult {
        val warnings: List<String> get() = warningNotices.map { it.resolve(AppLanguage.PORTUGUESE) }
    }
    data class Failure(val diagnosticNotices: List<LocalizedText>) : FpgaBoardImportResult {
        val diagnostics: List<String> get() = diagnosticNotices.map { it.resolve(AppLanguage.PORTUGUESE) }
    }
}

/** Reads electrical information without executing tools or decoding the optional board picture. */
object FpgaBoardCodec {
    fun read(xml: String): FpgaBoardImportResult = try {
        val root = try {
            CircXmlParser(xml).parse()
        } catch (error: IllegalArgumentException) {
            val parserNotice = (error as? LocalizedArgumentException)?.text
            val reason = when (parserNotice?.key) {
                Messages.ErrorXmlSize -> message("fpga_core_board_xml_size")
                null -> message("fpga_core_invalid_xml")
                else -> parserNotice
            }
            throw LocalizedArgumentException(message("fpga_core_xml_import_failed", "reason" to reason))
        }
        val information = root.children.filter { it.name == "BoardInformation" }.singleOrNull()
            ?: throw LocalizedArgumentException(message("fpga_core_board_information"))
        val fpga = information.children.filter { it.name == "FPGAInformation" }.singleOrNull()
            ?: throw LocalizedArgumentException(message("fpga_core_fpga_information"))
        fun required(name: String) = fpga.attributes[name]?.takeIf { it.isNotBlank() }
            ?: throw LocalizedArgumentException(message("fpga_core_missing_parameter", "name" to name))
        val device = FpgaDevice(
            vendor = when (required("Vendor").uppercase()) {
                "ALTERA", "QUARTUS" -> FpgaVendor.ALTERA
                "XILINX" -> FpgaVendor.XILINX
                "VIVADO" -> FpgaVendor.VIVADO
                "OPENFPGA" -> FpgaVendor.OPEN_FPGA
                else -> FpgaVendor.UNKNOWN
            },
            family = required("Family"), part = required("Part"),
            packageName = required("Package"), speedGrade = required("Speedgrade"),
            jtagPosition = fpga.attributes["JTAGPos"]?.let { integer(it, "JTAGPos", 0..255) } ?: 1,
            flashPosition = fpga.attributes["FlashPos"]?.let { integer(it, "FlashPos", 0..255) } ?: 2,
            flashName = fpga.attributes["FlashName"].orEmpty(),
            usbTmc = fpga.attributes["USBTMC"]?.let {
                requireLocalized(it in setOf("true", "false")) { message("fpga_core_invalid_usbtmc") }; it == "true"
            } ?: false,
        )
        val clockNodes = information.children.filter { it.name == "ClockInformation" }
        requireLocalized(clockNodes.size <= 1) { message("fpga_core_duplicate_clock") }
        val clock = clockNodes.singleOrNull()?.let { node ->
            val location = node.attributes["FPGApin"]?.takeIf { it.isNotBlank() }
                ?: throw LocalizedArgumentException(message("fpga_core_missing_clock_pin"))
            val frequency = node.attributes["Frequency"]?.toLongOrNull()
                ?: throw LocalizedArgumentException(message("fpga_core_invalid_clock_frequency"))
            requireLocalized(frequency in 1..1_000_000_000_000L) { message("fpga_core_clock_frequency_range") }
            FpgaClock(location, frequency, electrical(node.attributes, ""))
        }
        val ioNodes = listOf("PinsInformation", "ButtonsInformation", "LEDsInformation", "IOComponents").flatMap { name ->
            val sections = root.children.filter { it.name == name }
            requireLocalized(sections.size <= 1) { message("fpga_core_duplicate_io") }
            sections.singleOrNull()?.children.orEmpty()
        }
        val resources = ioNodes.mapIndexed { resourceIndex, node ->
            val id = "resource-$resourceIndex"
            val pins = mutableListOf<FpgaBoardPin>()
            val electrical = electrical(node.attributes, "FPGAPin")
            val bounds = rectangle(node.attributes)
            fun appendPinSet(attribute: String, direction: FpgaPinDirection) {
                val raw = node.attributes[attribute] ?: return
                val locations = raw.split(',').map { it.trim() }
                requireLocalized(locations.none { it.isEmpty() } && locations.size <= 4096) { message("fpga_core_invalid_pin_list", "resource" to id) }
                for (location in locations) pins += FpgaBoardPin(id, pins.size, location, direction, electrical)
            }
            appendPinSet("InputPinSet", FpgaPinDirection.INPUT)
            appendPinSet("OutputPinSet", FpgaPinDirection.OUTPUT)
            appendPinSet("BiDirPinSet", FpgaPinDirection.INOUT)
            if (pins.isEmpty()) {
                val direction = when (node.name.lowercase()) {
                    "button", "dipswitch" -> FpgaPinDirection.INPUT
                    "led", "ledarray", "rgbled", "sevensegment", "sevensegmentscanning", "sevensegmentnodp" -> FpgaPinDirection.OUTPUT
                    else -> FpgaPinDirection.INOUT
                }
                val single = node.attributes["FPGAPinName"]
                val declaredCount = node.attributes["NrOfPins"]
                if (single != null) {
                    requireLocalized(single.isNotBlank()) { message("fpga_core_missing_resource_pin", "pin" to 0, "resource" to id) }
                    pins += FpgaBoardPin(id, 0, single, direction, electrical)
                } else if (declaredCount != null) {
                    val count = integer(declaredCount, "NrOfPins", 1..4096)
                    repeat(count) { pin ->
                        val location = node.attributes["FPGAPin_$pin"]?.takeIf { it.isNotBlank() }
                            ?: throw LocalizedArgumentException(message("fpga_core_missing_resource_pin", "pin" to pin, "resource" to id))
                        pins += FpgaBoardPin(id, pin, location, direction, electrical)
                    }
                } else {
                    requireLocalized(bounds != null && node.attributes.keys.none { it.startsWith("FPGAPin_") }) {
                        message("fpga_core_unrecognized_pinout", "resource" to id)
                    }
                }
            }
            FpgaBoardResource(id, node.name, node.attributes["Label"].orEmpty(), bounds, pins, node.attributes)
        }
        val unusedNodes = information.children.filter { it.name == "UnusedPins" }
        requireLocalized(unusedNodes.size <= 1) { message("fpga_core_duplicate_unused") }
        val unused = unusedNodes.singleOrNull()?.attributes?.get("PullBehavior")?.let(::pull) ?: FpgaPull.FLOAT
        val warnings = buildList {
            if (device.vendor == FpgaVendor.UNKNOWN) add(message("fpga_core_unknown_vendor"))
            if (resources.isEmpty()) add(message("fpga_core_no_resources"))
            for (resource in resources) {
                if (resource.pins.isEmpty()) add(message("fpga_core_resource_without_pins", "resource" to resource.id))
            }
        }
        FpgaBoardImportResult.Success(FpgaBoardDefinition(root.name, device, clock, resources, unused, xml), warnings)
    } catch (error: IllegalArgumentException) {
        FpgaBoardImportResult.Failure(listOf((error as? LocalizedArgumentException)?.text ?: message("fpga_core_invalid_xml")))
    }

    private fun electrical(attributes: Map<String, String>, prefix: String): FpgaElectrical {
        val standard = attributes[prefix + "IOStandard"]?.takeUnless { it == "Default" || it.isBlank() }
        requireLocalized(standard == null || standard in setOf("LVCMOS12", "LVCMOS15", "LVCMOS18", "LVCMOS25", "LVCMOS33", "LVTTL")) {
            message("fpga_core_unrecognized_io_standard", "standard" to standard)
        }
        val drive = attributes[prefix + "DriveStrength"]?.takeUnless { it == "Default" || it.isBlank() }?.let {
            it.removeSuffix(" mA").toIntOrNull()?.takeIf { value -> value in setOf(2, 4, 8, 16, 24) }
                ?: throw LocalizedArgumentException(message("fpga_core_invalid_drive_value", "drive" to it))
        }
        val activity = attributes["ActivityLevel"]
        requireLocalized(activity == null || activity in setOf("Active low", "Active high")) { message("fpga_core_invalid_activity") }
        return FpgaElectrical(standard, attributes[prefix + "PullBehavior"]?.let(::pull) ?: FpgaPull.FLOAT, drive, activity == "Active low")
    }

    private fun pull(value: String): FpgaPull = when (value) {
        "Float" -> FpgaPull.FLOAT
        "Pull Up" -> FpgaPull.UP
        "Pull Down" -> FpgaPull.DOWN
        else -> throw LocalizedArgumentException(message("fpga_core_invalid_pull_value", "value" to value))
    }

    private fun integer(value: String, name: String, range: IntRange): Int =
        value.toIntOrNull()?.takeIf { it in range } ?: throw LocalizedArgumentException(message("fpga_core_invalid_integer", "name" to name))

    private fun rectangle(attributes: Map<String, String>): FpgaRectangle? {
        val raw = attributes["Rect_x_y_w_h"]
        val values = if (raw != null) raw.split(',') else {
            if (attributes["LocationX"] == null && attributes["LocationY"] == null) return null
            listOf(attributes["LocationX"].orEmpty(), attributes["LocationY"].orEmpty(), attributes["Width"].orEmpty(), attributes["Height"].orEmpty())
        }
        requireLocalized(values.size == 4) { message("fpga_core_invalid_rectangle") }
        return FpgaRectangle(integer(values[0].trim(), "x", 0..100_000), integer(values[1].trim(), "y", 0..100_000),
            integer(values[2].trim(), "width", 1..100_000), integer(values[3].trim(), "height", 1..100_000))
    }
}
