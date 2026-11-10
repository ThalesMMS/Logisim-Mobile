package dev.logisim.mobile.core

/** Default named circuit boxes from Logisim-evolution's DefaultEvolutionAppearance and
 * DefaultHolyCrossAppearance (GPL-3.0). Sizes use their file-compatible font metrics. */
internal object NamedSymbolAppearance {
    fun build(circuit: CircuitDocument): SymbolAppearance? {
        val style = if ("circuitnamedbox" in circuit.attributes) "logisim_evolution" else circuit.attributes["appearance"]
        if (style != "logisim_evolution" && style != "evolution") return null
        val evolution = style == "logisim_evolution"
        val fixed = evolution && circuit.attributes["circuitnamedboxfixedsize"].equals("true", ignoreCase = true)
        val pinOrder = compareBy<CircuitComponent>({ it.position.y }, { it.position.x })
        val inputs = circuit.components.filter { it.kind == ComponentKind.INPUT }.sortedWith(pinOrder)
        val outputs = circuit.components.filter { it.kind == ComponentKind.OUTPUT }.sortedWith(pinOrder)
        val pinCount = maxOf(inputs.size, outputs.size)
        val spacing = if (evolution) 20 else 10
        val firstPinY = if (evolution) 10 else 30
        val width = if (evolution) {
            val textWidth = if (fixed) 200 else maxOf(
                (inputs.maxOfOrNull { it.label.length } ?: 0) * 8 + (outputs.maxOfOrNull { it.label.length } ?: 0) * 8 + 35,
                circuit.name.length * 8 + 15,
            )
            textWidth / 10 * 10 + 20
        } else {
            val left = inputs.maxOfOrNull { fpgaLabelWidth(it.label) } ?: 0
            val right = outputs.maxOfOrNull { fpgaLabelWidth(it.label) } ?: 0
            maxOf(100, (left + right + 25 + 9) / 10 * 10)
        }
        val height = if (evolution) 20 + maxOf(10, pinCount * spacing) else maxOf(40, 35 + pinCount * spacing)
        val anchor = SymbolAnchor(GridPoint(if (outputs.isNotEmpty()) width else 0, if (pinCount == 0) 0 else firstPinY))
        val ports = mutableListOf<SymbolPort>()
        val shapes = mutableListOf<SymbolObject.Shape>()
        for ((side, pins) in listOf(inputs, outputs).withIndex()) {
            val left = side == 0
            val x = if (left) 0 else width
            for ((index, pin) in pins.withIndex()) {
                val y = firstPinY + index * spacing
                ports += SymbolPort(pin.id, GridPoint(x, y))
                val clock = evolution && left && clockLabel.matches(pin.label)
                if (evolution) {
                    val stubHeight = if (pin.width > 1) 4 else 3
                    shapes += rect("stub-$side-$index", if (left) x else x - 10, y - stubHeight / 2, 10, stubHeight, filled = true)
                }
                if (clock) shapes += SymbolObject.Shape("clock-$index", "polyline", mapOf(
                    "points" to "11,${y - 4} 18,$y 11,${y + 4}", "fill" to "none", "stroke-width" to "2",
                ))
                if (pin.label.isNotEmpty()) {
                    val inset = if (evolution) 15 else 5
                    val labelX = if (left) x + inset + (if (clock) 8 else 0) else x - inset
                    val pinLabel = shorten(pin.label, if (clock) 11 else 12, fixed)
                    shapes += label("label-$side-$index", pinLabel, labelX, if (evolution) y + 4 else y,
                        if (evolution) 12 else 10, if (left) "start" else "end",
                        if (evolution) "alphabetic" else "middle", evolution)
                }
            }
        }
        if (evolution) {
            shapes += rect("title-band", 10, height - 20, width - 20, 20, filled = true)
            shapes += rect("body", 10, 0, width - 20, height)
            shapes += label("title", shorten(circuit.name, 23, fixed), width / 2, height - 6, 14, "middle", "alphabetic", true, title = true)
        } else {
            shapes.add(0, rect("body", 0, 0, width, height))
            shapes += label("title", circuit.name, width / 2, 5, 12, "middle", "top", false)
        }
        return SymbolAppearance(anchor, ports, shapes)
    }

    private fun rect(id: String, x: Int, y: Int, width: Int, height: Int, filled: Boolean = false) =
        SymbolObject.Shape(id, "rect", mapOf(
            "x" to x.toString(), "y" to y.toString(), "width" to width.toString(), "height" to height.toString(),
            "fill" to if (filled) "#000000" else "none", "stroke" to if (filled) "none" else "#000000",
            "stroke-width" to if (filled) "1" else "2",
        ))

    private fun label(id: String, text: String, x: Int, y: Int, size: Int, align: String, baseline: String, mono: Boolean, title: Boolean = false) =
        SymbolObject.Shape(id, "text", mapOf(
            "x" to x.toString(), "y" to y.toString(), "font-size" to size.toString(), "text-anchor" to align,
            "dominant-baseline" to baseline, "font-family" to if (mono) "Monospaced" else "SansSerif",
            "font-weight" to if (title) "bold" else "normal", "fill" to if (title) "#ffffff" else "#000000",
        ), text)

    private fun shorten(label: String, limit: Int, fixed: Boolean): String =
        if (fixed && label.length > limit) label.take(limit - 3) + "..." else label

    private fun fpgaLabelWidth(label: String): Int =
        label.sumOf { fpgaAsciiWidths.getOrElse(it - ' ') { 8 } }

    private val clockLabel = Regex(".*(clk|clock).*", RegexOption.IGNORE_CASE)
    private val fpgaAsciiWidths = intArrayOf(
        3, 4, 5, 8, 6, 10, 9, 3, // Space to '
        4, 4, 5, 8, 3, 4, 3, 3, // ( to /
        6, 6, 6, 6, 6, 6, 6, 6, // 0 to 7
        6, 6, 3, 3, 8, 8, 8, 5, // 8 to ?
        11, 7, 7, 8, 8, 7, 6, 8, // @ to G
        8, 3, 3, 7, 6, 9, 8, 8, // H to O
        7, 8, 7, 7, 5, 8, 7, 9, // P to W
        6, 7, 6, 4, 3, 4, 8, 5, // X to _
        5, 6, 6, 5, 6, 6, 4, 6, // Grave accent to g
        6, 2, 2, 5, 2, 10, 6, 6, // h to o
        6, 6, 4, 5, 4, 6, 6, 8, // p to w
        6, 6, 5, 6, 3, 6, 8, // x to ~
    )
}
