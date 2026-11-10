package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** Writes a board that both the Mobile and Evolution readers can load. */
object FpgaBoardWriter {
    val resourceTypes = listOf("Led", "Button", "Pin", "SevenSegment", "SevenSegmentNoDp", "SevenSegmentScanning",
        "DIPSwitch", "RgbLed", "LedArray", "PortIo", "LocalBus")
    private val resourceSections = setOf("PinsInformation", "ButtonsInformation", "LEDsInformation", "IOComponents")
    private val physicalPin = Regex("[A-Za-z0-9_]+")

    fun write(board: FpgaBoardDefinition, picture: FpgaBoardPicture?): FpgaBoardImportResult = try {
        val name = board.name
        requireLocalized(name.length in 1..120 && (name.first().isLetter() || name.first() == '_') &&
            name.all { it.isLetterOrDigit() || it in "_.-" }) { message("fpga_editor_invalid_board_name") }
        requireLocalized(board.device.vendor != FpgaVendor.UNKNOWN) { message("fpga_core_unknown_vendor") }
        val clock = board.clock ?: throw LocalizedArgumentException(message("fpga_core_missing_clock_pin"))
        val clockPin = clock.location.trim()
        requireLocalized(physicalPin.matches(clockPin)) { message("fpga_core_missing_clock_pin") }
        val image = picture ?: throw LocalizedArgumentException(message("fpga_editor_invalid_image"))
        requireLocalized(board.resources.isNotEmpty()) { message("fpga_core_no_resources") }
        val original = board.originalXml?.let { CircXmlParser(it).parse() }
        val originalInfo = original?.children?.singleOrNull { it.name == "BoardInformation" }
        fun infoAttributes(section: String) = originalInfo?.children?.singleOrNull { it.name == section }?.attributes.orEmpty()
        val loaded = original != null
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            startElement(board.name, original?.attributes.orEmpty())
            append(">\n")
            startElement("BoardInformation", originalInfo?.attributes.orEmpty())
            append(">\n")
            fpgaBoardXmlElement("ClockInformation", infoAttributes("ClockInformation").toMutableMap().apply {
                put("Frequency", clock.frequencyHz.toString())
                put("FPGApin", clockPin)
                putUnlessReadAsAbsent(loaded, "PullBehavior", clock.electrical.pull.xml, FpgaPull.FLOAT.xml)
                putUnlessReadAsAbsent(loaded, "IOStandard", clock.electrical.ioStandard ?: "Default", "Default")
            })
            val device = board.device
            fpgaBoardXmlElement("FPGAInformation", infoAttributes("FPGAInformation") + mapOf(
                "Vendor" to if (device.vendor == FpgaVendor.OPEN_FPGA) "OPENFPGA" else device.vendor.name,
                "Part" to device.part, "Family" to device.family, "Package" to device.packageName,
                "Speedgrade" to device.speedGrade, "USBTMC" to device.usbTmc.toString(),
                "JTAGPos" to device.jtagPosition.toString(), "FlashName" to device.flashName,
                "FlashPos" to device.flashPosition.toString()))
            fpgaBoardXmlElement("UnusedPins", infoAttributes("UnusedPins").toMutableMap().apply {
                putUnlessReadAsAbsent(loaded, "PullBehavior", board.unusedPinPull.xml, FpgaPull.FLOAT.xml)
            })
            originalInfo?.children?.filter { it.name !in setOf("ClockInformation", "FPGAInformation", "UnusedPins") }
                ?.forEach { append(it.rawXml).append('\n') }
            append("</BoardInformation>\n<IOComponents>\n")
            for (resource in board.resources) appendResource(resource, image)
            append("</IOComponents>\n")
            image.appendXml(this)
            original?.children?.filter { it.name !in resourceSections && it.name != "BoardInformation" && it.name != "BoardPicture" }
                ?.forEach { append(it.rawXml).append('\n') }
            append("</").append(board.name).append(">\n")
        }
        FpgaBoardCodec.read(xml)
    } catch (error: IllegalArgumentException) {
        FpgaBoardImportResult.Failure(listOf((error as? LocalizedArgumentException)?.text ?: message("fpga_core_invalid_xml")))
    }

    private fun StringBuilder.appendResource(resource: FpgaBoardResource, picture: FpgaBoardPicture) {
        requireLocalized(resourceTypes.any { it.equals(resource.type, ignoreCase = true) }) {
            message("fpga_editor_invalid_resource_type", "type" to resource.type)
        }
        val box = resource.rectangle
        requireLocalized(box != null && box.x >= 0 && box.y >= 0 && box.width > 0 && box.height > 0 &&
            box.x.toLong() + box.width <= picture.width && box.y.toLong() + box.height <= picture.height) {
            message("fpga_editor_resource_rectangle", "resource" to resource.label.ifBlank { resource.id })
        }
        requireNotNull(box)
        val attributes = resource.attributes.filterKeys { key ->
            key !in setOf("LocationX", "LocationY", "Width", "Height", "FPGAPinName", "NrOfPins",
                "InputPinSet", "OutputPinSet", "BiDirPinSet") && !key.startsWith("FPGAPin_")
        }.toMutableMap()
        val rotation = attributes["rotation"]
        requireLocalized(rotation == null || rotation.toIntOrNull() in setOf(0, 90, -90, 180)) {
            message("fpga_editor_invalid_field", "field" to message("fpga_editor_rotation"))
        }
        validateDisplay(resource.type, attributes)
        attributes["Rect_x_y_w_h"] = listOf(box.x, box.y, box.width, box.height).joinToString(",")
        if (resource.label.isNotEmpty()) attributes["Label"] = resource.label else attributes.remove("Label")
        val pinLocations = resource.pins.map { it.location }
        requireLocalized(pinLocations.all(physicalPin::matches) && pinLocations.distinctBy { it.uppercase() }.size == pinLocations.size) {
            message("fpga_editor_resource_pins", "resource" to resource.label.ifBlank { resource.id })
        }
        for ((direction, key) in listOf(FpgaPinDirection.INPUT to "InputPinSet", FpgaPinDirection.OUTPUT to "OutputPinSet",
            FpgaPinDirection.INOUT to "BiDirPinSet")) {
            val pins = resource.pins.filter { it.direction == direction }
            if (pins.isNotEmpty()) attributes[key] = pins.joinToString(",") { it.location }
        }
        val electrical = resource.pins.firstOrNull()?.electrical
        // A resource read from a board file carries its placement; a resource added in the editor does not.
        val loaded = listOf("Rect_x_y_w_h", "LocationX").any { it in resource.attributes }
        if (electrical != null) {
            requireLocalized(resource.pins.all { it.electrical == electrical }) {
                message("fpga_editor_resource_electrical", "resource" to resource.label.ifBlank { resource.id })
            }
            attributes.putUnlessReadAsAbsent(loaded, "FPGAPinIOStandard", electrical.ioStandard ?: "Default", "Default")
            attributes.putUnlessReadAsAbsent(loaded, "FPGAPinPullBehavior", electrical.pull.xml, FpgaPull.FLOAT.xml)
            attributes.putUnlessReadAsAbsent(loaded, "FPGAPinDriveStrength", electrical.driveMilliamps?.let { "$it mA" } ?: "Default", "Default")
            attributes.putUnlessReadAsAbsent(loaded, "ActivityLevel", if (electrical.activeLow) "Active low" else "Active high", "Active high")
        }
        fpgaBoardXmlElement(resource.type, attributes)
    }

    private fun validateDisplay(type: String, attributes: MutableMap<String, String>) {
        val ledArray = type.equals("LedArray", ignoreCase = true)
        if (!ledArray && !type.equals("SevenSegmentScanning", ignoreCase = true)) return
        val key = if (ledArray) "LedArrayInfo" else "ScanningSevenSegInfo"
        val values = attributes[key].orEmpty().split(',').map { it.trim() }
        val rows = values.getOrNull(0)?.toIntOrNull()
        val columns = values.getOrNull(1)?.toIntOrNull()
        val mode = values.getOrNull(2)
        val modes = if (ledArray) setOf("LedDefault", "LedRowScanning", "LedColumnScanning", "RgbDefault",
            "RgbRowScanning", "RgbColScanning") else setOf("SevenSegDecoded", "SevenSegScanningActiveLow", "SevenSegScanningActiveHi")
        requireLocalized(values.size == 3 && rows != null && rows in 1..128 && columns != null &&
            (columns in 1..128 || (!ledArray && columns == -1)) && mode in modes) {
            message("fpga_editor_invalid_field", "field" to message("fpga_editor_display_configuration"))
        }
        attributes[key] = values.joinToString(",")
    }
}

/**
 * Evolution reads a missing pull or activity attribute as "unknown", not as the value Mobile shows for it. When the
 * board or resource came from a file, keep such an attribute missing while the edited value is still what Mobile
 * reads for a missing attribute. Boards and resources created in the editor keep explicit values.
 */
private fun MutableMap<String, String>.putUnlessReadAsAbsent(loaded: Boolean, key: String, value: String, absentValue: String) {
    if (!loaded || key in this || value != absentValue) put(key, value)
}

private val FpgaPull.xml: String
    get() = when (this) {
        FpgaPull.FLOAT -> "Float"
        FpgaPull.UP -> "Pull Up"
        FpgaPull.DOWN -> "Pull Down"
    }

internal fun StringBuilder.fpgaBoardXmlElement(name: String, attributes: Map<String, String>) {
    startElement(name, attributes)
    append("/>\n")
}

private fun StringBuilder.startElement(name: String, attributes: Map<String, String>) {
    append('<').append(name)
    for ((key, value) in attributes) {
        require(key.isNotEmpty() && (key.first().isLetter() || key.first() == '_') &&
            key.all { it.isLetterOrDigit() || it in "_-.:" })
        append(' ').append(key).append("=\"")
        for (character in value) append(when (character) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\n' -> "&#10;"
            '\r' -> "&#13;"
            '\t' -> "&#9;"
            else -> character.toString()
        })
        append('"')
    }
}
