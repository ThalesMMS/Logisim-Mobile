package dev.logisim.mobile.core

/**
 * The Poke tool on an input Pin, as Evolution's `Pin.PinPoker` (d58a61e0): a tap toggles the bit (radix 2)
 * or advances the hexadecimal/octal digit under the pointer; a tri-state pin cycles U → 0 → 1 → U.
 * Decimal and float radixes have no bit under the pointer ([bitAt] returns -1): Evolution opens a value editor.
 */
object PinPoke {
    /** Bits per digit for the pin radix; 0 when the radix has no per-digit poke (decimal, float). */
    fun digitBits(radix: String): Int = when (radix) {
        "16" -> 4
        "8" -> 3
        "2" -> 1
        else -> 0
    }

    /** Pin behaviour as resolved from the raw attributes (2.7.1 `tristate`/`pull` or Evolution `behavior`). */
    fun behavior(raw: Map<String, String>): String = raw["behavior"] ?: when {
        raw["pull"] == "up" -> "pullup"
        raw["pull"] == "down" -> "pulldown"
        raw["tristate"] == "true" -> "tristate"
        else -> "simple"
    }

    /**
     * The poke target of a tap at ([x], [y]) on an input [pin]: the bit index, -1 when no bit or digit is under
     * the pointer, or null when the radix is decimal or float and Evolution opens its value editor instead.
     */
    fun target(pin: CircuitComponent, x: Int, y: Int, registry: ComponentRegistry = ComponentCatalog.registry): Int? {
        val attributes = registry.attributes(pin)
        val radix = attributes.string("radix") ?: "2"
        if (pin.width > 1 && digitBits(radix) == 0) return null
        return bitAt(pin.bounds(registry), x, y, pin.width, radix,
            Direction.parse(attributes.string("facing") ?: "east") ?: Direction.EAST, attributes.string("appearance") == "NewPins")
    }

    /** `PinPoker.getBit`: the lowest bit of the bit/digit under ([x], [y]), or -1 outside or for decimal/float. */
    fun bitAt(bounds: ComponentBounds, x: Int, y: Int, width: Int, radix: String, facing: Direction, newAppearance: Boolean): Int {
        val r = digitBits(if (width == 1) "2" else radix)
        if (r == 0) return -1
        if (width <= r) return 0
        val boundsWidth = bounds.right - bounds.left
        val boundsHeight = bounds.bottom - bounds.top
        val column: Int
        val row: Int
        if (newAppearance) {
            val distance = if (r == 1) 10 else DIGIT_WIDTH
            column = when (facing) {
                Direction.EAST, Direction.WEST -> {
                    val offset = if (facing == Direction.EAST) 20 else 10
                    (bounds.left + boundsWidth - x - offset) / distance
                }
                Direction.NORTH -> (y - bounds.top - 20) / distance
                Direction.SOUTH -> (bounds.top + boundsHeight - y - 20) / distance
            }
            row = when (facing) {
                Direction.EAST, Direction.WEST -> (bounds.top + boundsHeight - y) / 20
                Direction.NORTH -> (bounds.left + boundsWidth - x) / 20
                Direction.SOUTH -> (x - bounds.left) / 20
            }
        } else {
            column = (bounds.left + boundsWidth - x - (if (r == 1) 0 else 4)) / (if (r == 1) 10 else 8)
            row = (bounds.top + boundsHeight - y - 2) / 14
        }
        val bit = if (r == 1) 8 * row + column else column * r
        return if (bit < 0 || bit >= width) -1 else bit
    }

    /** `PinPoker.handleBitPress` with no typed character: the poke of [bit] on the pin's intended value. */
    fun poke(current: LogicVector, bit: Int, radix: String, tristate: Boolean): LogicVector {
        val width = current.width
        require(bit in 0 until width)
        var r = digitBits(if (width == 1) "2" else radix).coerceAtLeast(1)
        if (bit + r > width) r = width - bit
        // Evolution normalizes the intended value first: tri-state keeps U (E becomes U); others turn E and U into 1.
        val values = Array(width) { index ->
            when (val value = current.bit(index)) {
                LogicValue.HIGH, LogicValue.LOW -> value
                else -> if (tristate) LogicValue.UNKNOWN else LogicValue.HIGH
            }
        }
        val digit = bit until bit + r
        val ones = digit.none { values[it] == LogicValue.LOW }
        val defined = digit.all { values[it] == LogicValue.HIGH || values[it] == LogicValue.LOW }
        when {
            !defined || (ones && !tristate) -> digit.forEach { values[it] = LogicValue.LOW }
            ones && tristate -> digit.forEach { values[it] = LogicValue.UNKNOWN }
            else -> {
                var carry = 1
                for (index in digit) {
                    val sum = (if (values[index] == LogicValue.HIGH) 1 else 0) + carry
                    values[index] = if (sum % 2 == 1) LogicValue.HIGH else LogicValue.LOW
                    carry = sum / 2
                }
            }
        }
        return LogicVector.fromBits(values.toList())
    }

    private const val DIGIT_WIDTH = 8
}
