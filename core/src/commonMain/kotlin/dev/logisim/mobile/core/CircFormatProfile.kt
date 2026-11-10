package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

data class CircMigration(val rule: String, val circuit: String, val componentId: String?, val notice: LocalizedText) {
    constructor(rule: String, circuit: String, componentId: String?, description: String) :
        this(rule, circuit, componentId, LocalizedText.literal(description))
    val description: String get() = notice.resolve(AppLanguage.PORTUGUESE)
}

internal data class CircFormatProfile(val source: String, val major: Int, val minor: Int, val patch: Int, val holyCross: Boolean) {
    fun before(major: Int, minor: Int, patch: Int): Boolean = compareValuesBy(this, CircFormatProfile("", major, minor, patch, false),
        { it.major }, { it.minor }, { it.patch }) < 0
    fun atMost(major: Int, minor: Int, patch: Int): Boolean = before(major, minor, patch) ||
        this.major == major && this.minor == minor && this.patch == patch
    val originalLogisim: Boolean get() = !holyCross && atMost(2, 7, 1)
    val needsLegacyConversion: Boolean get() = before(2, 6, 3)

    fun library(description: String?, componentName: String): String? =
        if (description == "#Arithmetic" && before(4, 1, 0) && (componentName.startsWith("FP") || componentName == "IntToFP")) "#FPArithmetic" else description

    fun implicitAttributes(name: String, raw: Map<String, String>, mobileExport: Boolean = false): Map<String, String> = buildMap {
        if (name == "Pin") {
            if ("type" !in raw && "output" in raw) put("type", if (raw["output"].equals("true", true)) "output" else "input")
            if ("behavior" !in raw) when {
                raw["pull"].equals("up", true) -> put("behavior", "pullup")
                raw["pull"].equals("down", true) -> put("behavior", "pulldown")
                raw["tristate"].equals("true", true) -> put("behavior", "tristate")
            }
        }
        // Evolution's TextAttributes default to StdAttr.DEFAULT_LABEL_FONT (SansSerif bold 16); 2.7.1 used plain 12.
        if (name == "Text" && "font" !in raw) put("font", AnnotationLibrary.defaultTextFont(originalLogisim || holyCross))
        if ("appearance" !in raw && (isSequential(name) || name in memoryAppearances)) {
            put("appearance", if (holyCross || (originalLogisim && isSequential(name))) "classic" else "logisim_evolution")
        }
        if ("enable" !in raw) when (name) {
            "Multiplexer", "Demultiplexer" -> put("enable", atMost(3, 6, 1).toString())
            "Decoder" -> put("enable", (!before(2, 6, 4)).toString())
        }
        if (name == "Splitter" && "appear" !in raw && before(2, 6, 4)) put("appear", "legacy")
        if (name == "Splitter" && (originalLogisim || !mobileExport)) {
            val width = raw["incoming"]?.toIntOrNull() ?: 2
            val fanout = raw["fanout"]?.toIntOrNull() ?: 2
            // The desktop loaders try to assign each omitted bit to its own branch.
            // Bits without a matching branch retain the initial balanced distribution.
            for (bit in 0 until minOf(width, fanout, 64)) {
                if ("bit$bit" !in raw) put("bit$bit", bit.toString())
            }
        }
    }

    companion object {
        private val sequentialAppearances = setOf("D Flip-Flop", "T Flip-Flop", "J-K Flip-Flop", "S-R Flip-Flop",
            "Register", "Counter", "Random", "Shift Register")
        private val memoryAppearances = setOf("RAM", "ROM", "Dual RAM")
        fun isSequential(name: String): Boolean = name in sequentialAppearances
        fun parse(raw: String?): CircFormatProfile {
            val source = raw?.takeIf { it.isNotBlank() } ?: "5.1.0-dev"
            val match = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-+]?[A-Za-z][A-Za-z0-9.-]*)?$").matchEntire(source)
                ?: throw LocalizedArgumentException(message(Messages.CodecSourceVersionInvalid, "source" to source))
            return CircFormatProfile(source,
                match.groupValues[1].toIntOrNull() ?: throw LocalizedArgumentException(message(Messages.CodecSourceInvalid)),
                match.groupValues[2].toIntOrNull() ?: throw LocalizedArgumentException(message(Messages.CodecSourceInvalid)),
                match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: throw LocalizedArgumentException(message(Messages.CodecSourceInvalid)),
                source.endsWith("-HC", true))
        }
    }
}
