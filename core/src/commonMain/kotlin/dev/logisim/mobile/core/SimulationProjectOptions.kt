package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

enum class GateUndefinedPolicy(val xmlValue: String) {
    IGNORE("ignore"), ERROR("error"),
}

/** Mobile defaults remain stable when a project does not specify simulation settings. */
data class SimulationProjectOptions(
    val propagationLimit: Int = 256,
    val memoryStartupUnknown: Boolean = false,
    val gateUndefined: GateUndefinedPolicy = GateUndefinedPolicy.IGNORE,
    val tickFrequencyHz: Double = 4.0,
    val autoPropagating: Boolean = true,
) {
    init {
        requireLocalized(propagationLimit > 0) { message(Messages.SimulationOptionsErrorPositiveLimit) }
        requireLocalized(tickFrequencyHz.isFinite() && tickFrequencyHz > 0) { message(Messages.SimulationOptionsErrorPositiveFrequency) }
    }

    fun asMap(): Map<String, String> = linkedMapOf(
        "propagationLimit" to propagationLimit.toString(),
        "memoryStartupUnknown" to memoryStartupUnknown.toString(),
        "gateUndefined" to gateUndefined.xmlValue,
        "tickFrequencyHz" to tickFrequencyHz.toString(),
        "autoPropagating" to autoPropagating.toString(),
    )

    /**
     * Updates owned option values inside their original XML. Unknown attributes, children, text,
     * comments, and unrelated project metadata stay intact. The desktop's names are used where
     * available; its per-circuit frequency is mirrored on the main circuit for interoperability.
     */
    fun applyTo(project: CircuitProject): CircuitProject {
        val values = linkedMapOf(
            "simlimit" to propagationLimit.toString(),
            "memoryStartupUnknown" to memoryStartupUnknown.toString(),
            "gateUndefined" to gateUndefined.xmlValue,
            "tickFrequencyHz" to tickFrequencyHz.toString(),
            "autoPropagating" to autoPropagating.toString(),
        )
        val optionElements = project.extraXml.map { CircXmlParser(it).parse() }
        val firstOptions = optionElements.indexOfFirst { it.name == "options" }
        val present = optionElements.filter { it.name == "options" }.flatMap { it.children }
            .filter { it.name == "a" }.mapNotNull { it.attributes["name"] }.toSet()
        val missing = values.filterKeys { it !in present }
        val extraXml = project.extraXml.mapIndexed { index, xml ->
            val element = optionElements[index]
            if (element.name != "options") xml
            else updateOptionsXml(element, values, if (index == firstOptions) missing else emptyMap())
        }.toMutableList()
        if (firstOptions < 0) extraXml += buildString {
            append("<options>")
            values.forEach { (name, value) -> append("\n    <a name=\"").append(name).append("\" val=\"").append(value).append("\"/>") }
            append("\n  </options>")
        }
        // Evolution keeps the frequency on the main circuit. Mirror it only when the project already has one or the
        // frequency changes; otherwise an unrelated option would impose Mobile's default rate on the desktop.
        val mirrorFrequency = project.circuit(project.mainCircuitName)?.projectAttribute("simulationFrequency") != null ||
            runCatching { read(project).tickFrequencyHz }.getOrNull() != tickFrequencyHz
        return project.copy(
            extraXml = extraXml,
            circuits = project.circuits.map { circuit ->
                if (mirrorFrequency && circuit.name == project.mainCircuitName) {
                    circuit.withDesktopAttribute("simulationFrequency", tickFrequencyHz.toString())
                } else circuit
            },
        )
    }

    companion object {
        fun read(project: CircuitProject): SimulationProjectOptions {
            val values = linkedMapOf<String, String>()
            for ((name, value) in optionValues(project)) {
                val normalized = if (name == "simlimit") "propagationLimit" else name
                if (normalized in ownedNames) values[normalized] = value
            }
            val defaults = SimulationProjectOptions()
            val limit = values["propagationLimit"]?.let { value ->
                requireNotNullLocalized(value.toIntOrNull()?.takeIf { it > 0 }) { message(Messages.SimulationOptionsErrorValue, "name" to "propagationLimit", "value" to value) }
            } ?: defaultPropagationLimit(project, defaults.propagationLimit)
            val frequency = values["tickFrequencyHz"]?.let { value ->
                requireNotNullLocalized(value.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }) { message(Messages.SimulationOptionsErrorValue, "name" to "tickFrequencyHz", "value" to value) }
            } ?: project.mainCircuit.projectAttribute("simulationFrequency")?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0 } ?: defaults.tickFrequencyHz
            val gatePolicy = values["gateUndefined"]?.let { value ->
                requireNotNullLocalized(GateUndefinedPolicy.entries.firstOrNull { it.xmlValue == value }) { message(Messages.SimulationOptionsErrorValue, "name" to "gateUndefined", "value" to value) }
            } ?: defaults.gateUndefined
            return SimulationProjectOptions(limit,
                readBoolean(values, "memoryStartupUnknown", defaults.memoryStartupUnknown), gatePolicy, frequency,
                readBoolean(values, "autoPropagating", defaults.autoPropagating))
        }

        internal fun randomDelay(project: CircuitProject): Int =
            optionValues(project).lastOrNull { (name) -> name == "simrand" }?.second?.toIntOrNull() ?: 0

        private fun optionValues(project: CircuitProject): Sequence<Pair<String, String>> = sequence {
            for (xml in project.extraXml) {
                val element = CircXmlParser(xml).parse()
                if (element.name != "options") continue
                for (option in element.children) {
                    if (option.name != "a") continue
                    val name = option.attributes["name"] ?: continue
                    yield(name to (option.attributes["val"] ?: option.text.trim()))
                }
            }
        }

        private fun defaultPropagationLimit(project: CircuitProject, mobileLimit: Int): Int {
            val profile = CircFormatProfile.parse(project.sourceVersion)
            // The audited Evolution desktop uses 5.1.0dev. Mobile writes 5.1.0-dev.
            return if (profile.originalLogisim || profile.source == "5.1.0dev") 1000 else mobileLimit
        }

        private val ownedNames = setOf("propagationLimit", "memoryStartupUnknown", "gateUndefined", "tickFrequencyHz", "autoPropagating")

        private fun readBoolean(values: Map<String, String>, name: String, default: Boolean): Boolean =
            values[name]?.let { value -> requireNotNullLocalized(value.toBooleanStrictOrNull()) { message(Messages.SimulationOptionsErrorValue, "name" to name, "value" to value) } } ?: default
    }
}

internal fun CircuitDocument.projectAttribute(name: String): String? =
    attributes[name] ?: extraXml.map { CircXmlParser(it).parse() }
        .lastOrNull { it.name == "a" && it.attributes["name"] == name }
        ?.let { it.attributes["val"] ?: it.text.trim() }

internal fun CircuitDocument.withDesktopAttribute(name: String, value: String): CircuitDocument {
    var preserved = false
    val extraXml = extraXml.map { xml ->
        val element = CircXmlParser(xml).parse()
        if (element.name == "a" && element.attributes["name"] == name) {
            preserved = true
            replaceOptionValue(xml, value)
        } else xml
    }
    return copy(
        attributes = if (preserved) attributes - name else attributes + (name to value),
        extraXml = extraXml,
    )
}

private fun updateOptionsXml(
    element: CircXmlElement,
    values: Map<String, String>,
    missing: Map<String, String>,
): String {
    val original = element.rawXml
    val openingEnd = xmlOpeningEnd(original)
    if (original.substring(0, openingEnd).trimEnd().endsWith('/')) {
        val slash = original.lastIndexOf('/', openingEnd)
        return buildString {
            append(original.substring(0, slash)).append('>')
            missing.forEach { (name, value) -> append("\n    <a name=\"").append(name).append("\" val=\"").append(value).append("\"/>") }
            append("\n  </options>")
        }
    }
    var cursor = openingEnd + 1
    return buildString {
        append(original.substring(0, cursor))
        for (child in element.children) {
            val start = nextXmlChildStart(original, cursor)
            append(original.substring(cursor, start))
            val name = child.attributes["name"]
            val value = if (name == "propagationLimit") values["simlimit"] else name?.let(values::get)
            append(if (child.name == "a" && value != null) replaceOptionValue(child.rawXml, value) else child.rawXml)
            cursor = start + child.rawXml.length
        }
        val close = original.lastIndexOf("</")
        append(original.substring(cursor, close))
        missing.forEach { (name, value) -> append("\n    <a name=\"").append(name).append("\" val=\"").append(value).append("\"/>") }
        if (missing.isNotEmpty()) append("\n  ")
        append(original.substring(close))
    }
}

/** The XML has already been validated by CircXmlParser; skip comments between direct children. */
private fun nextXmlChildStart(xml: String, from: Int): Int {
    var position = xml.indexOf('<', from)
    while (xml.startsWith("<!--", position)) position = xml.indexOf('<', xml.indexOf("-->", position) + 3)
    return position
}

private fun replaceOptionValue(xml: String, value: String): String {
    val end = xmlOpeningEnd(xml)
    val content = optionValueRange(xml, end)
    if (content != null) return xml.replaceRange(content, value)
    val slash = xml.substring(0, end).trimEnd().endsWith('/')
    val insertAt = if (slash) xml.lastIndexOf('/', end) else end
    return xml.substring(0, insertAt) + " val=\"$value\"" + xml.substring(insertAt)
}

private fun optionValueRange(xml: String, openingEnd: Int): IntRange? {
    var position = 1
    while (position < openingEnd && !xml[position].isWhitespace() && xml[position] != '/') position++
    while (position < openingEnd) {
        while (position < openingEnd && xml[position].isWhitespace()) position++
        if (position == openingEnd || xml[position] == '/') return null
        val nameStart = position
        while (position < openingEnd && !xml[position].isWhitespace() && xml[position] != '=') position++
        val name = xml.substring(nameStart, position)
        while (xml[position] != '=') position++
        position++
        while (xml[position].isWhitespace()) position++
        val quote = xml[position++]
        val start = position
        while (xml[position] != quote) position++
        if (name == "val") return start until position
        position++
    }
    return null
}

private fun xmlOpeningEnd(xml: String): Int {
    var quote: Char? = null
    for (index in xml.indices) {
        val character = xml[index]
        if (quote == null) {
            if (character == '\'' || character == '"') quote = character
            else if (character == '>') return index
        } else if (character == quote) quote = null
    }
    throw LocalizedArgumentException(message(Messages.SimulationOptionsErrorXmlOpening))
}
