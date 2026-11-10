package dev.logisim.mobile.core

import kotlin.math.ceil

/** State transitions use one-based package pins, matching the desktop TTL definitions. */
object TtlSequential {
    val supportedModels: Set<String> = setOf(
        "7474", "7476", "7493", "74107", "74123", "74161", "74163", "74164", "74165", "74166",
        "74173", "74175", "74190", "74192", "74193", "74194", "74259", "74273", "74299",
        "74374", "74377", "74390", "74393", "74594", "74595", "74670", "744017", "744060", "744094",
    )

    fun evaluate(
        model: String,
        pins: Map<Int, LogicValue>,
        state: TtlState,
        attributes: Map<String, String> = emptyMap(),
        elapsedTimeMillis: Long = 0,
    ): TtlResult? {
        if (model !in supportedModels) return null
        val c = Scope(pins, state, attributes)
        when (model) {
            "7474" -> c.dualD()
            "7476" -> c.dualJk()
            "74107" -> c.dualJkWithReset()
            "7493" -> c.rippleFour()
            "74123" -> c.monostable(elapsedTimeMillis)
            "74161", "74163" -> c.synchronousCounter(model == "74163")
            "74164" -> c.serialEight()
            "74165", "74166" -> c.parallelSerial(model == "74166")
            "74173" -> c.quadThreeState()
            "74175" -> c.quadD()
            "74190" -> c.decadeUpDown()
            "74192", "74193" -> c.upDownCounter(if (model == "74192") 9 else 15)
            "74194", "74299" -> c.universalShift(model == "74299")
            "74259" -> c.addressableLatch()
            "74273", "74377", "74374" -> c.octalD(model)
            "74390" -> c.dualDecade()
            "74393" -> c.dualBinary()
            "74594", "74595" -> c.shiftAndStore(model == "74595")
            "74670" -> c.registerFile()
            "744017" -> c.decodedDecade()
            "744060" -> c.binaryFourteen()
            "744094" -> c.serialStrobe()
        }
        return TtlResult(c.outputs.toMap(), TtlState(c.data.toMap(), c.clocks.toMap()), c.delay, c.wakeup)
    }

    private class Scope(
        val pins: Map<Int, LogicValue>,
        previous: TtlState,
        val attributes: Map<String, String>,
    ) {
        val data = previous.data.toMutableMap()
        val clocks = previous.clocks.toMutableMap()
        val outputs = linkedMapOf<Int, LogicValue>()
        var delay = 1L
        var wakeup: Long? = null

        fun p(pin: Int) = pins.pin(pin)
        fun high(pin: Int) = p(pin) == LogicValue.HIGH
        fun low(pin: Int) = p(pin) == LogicValue.LOW
        fun word(key: String, width: Int): LogicVector = data[key] ?: if (
            attributes["MemStartUnknown"] == "true" || attributes["memoryStartupUnknown"] == "true"
        ) LogicVector.unknown(width) else LogicVector.low(width)
        fun bit(key: String) = word(key, 1).bit(0)
        fun save(key: String, value: LogicValue) { data[key] = LogicVector.fromBit(value) }
        fun save(key: String, bits: List<LogicValue>) { data[key] = LogicVector.fromBits(bits) }
        fun edge(pin: Int, key: String = pin.toString(), falling: Boolean = false): Boolean {
            val old = clocks[key] ?: LogicValue.LOW
            val now = p(pin)
            clocks[key] = now
            return if (falling) old == LogicValue.HIGH && now == LogicValue.LOW
            else old == LogicValue.LOW && now == LogicValue.HIGH
        }
        fun out(pins: List<Int>, value: LogicVector) { pins.forEachIndexed { i, pin -> outputs[pin] = value.bit(i) } }
        fun read(pins: List<Int>) = LogicVector.fromBits(pins.map(::p))
        fun oldLong(value: LogicVector): Long = value.toULongOrNull()?.toLong() ?: -1L
        fun oldLong(pin: Int): Long = if (high(pin)) 1 else if (low(pin)) 0 else -1
        fun increment(value: LogicVector, limit: Int): LogicVector = when {
            value.errorMask != 0uL -> LogicVector.error(value.width)
            !value.isFullyDefined -> LogicVector.unknown(value.width)
            value.value > limit.toULong() -> LogicVector.unknown(value.width)
            else -> LogicVector.of(value.width, (value.value + 1uL) % (limit.toULong() + 1uL))
        }
        fun dualD() {
            delay = 8
            for ((half, mapping) in listOf(listOf(1, 2, 3, 4, 5, 6), listOf(13, 12, 11, 10, 9, 8)).withIndex()) {
                val clear = mapping[0]; val input = mapping[1]; val clock = mapping[2]
                val preset = mapping[3]; val q = mapping[4]; val nq = mapping[5]
                val triggered = edge(clock)
                val next = when {
                    low(clear) && low(preset) -> LogicValue.UNKNOWN
                    low(clear) -> LogicValue.LOW
                    low(preset) -> LogicValue.HIGH
                    triggered -> p(input)
                    else -> bit("q$half")
                }
                save("q$half", next)
                outputs[q] = next
                outputs[nq] = next.ttlNot()
            }
        }
        fun dualJk() {
            delay = 8
            for ((half, m) in listOf(listOf(1, 2, 3, 4, 15, 14, 13), listOf(5, 6, 7, 9, 12, 11, 10)).withIndex()) {
                val triggered = edge(m[0], falling = true)
                val old = bit("q$half")
                val next = when {
                    low(m[1]) && low(m[2]) -> LogicValue.UNKNOWN
                    low(m[1]) -> LogicValue.HIGH
                    low(m[2]) -> LogicValue.LOW
                    triggered && high(m[3]) && high(m[4]) -> old.ttlNot()
                    triggered && high(m[3]) && low(m[4]) -> LogicValue.HIGH
                    triggered && low(m[3]) && high(m[4]) -> LogicValue.LOW
                    else -> old
                }
                save("q$half", next)
                outputs[m[5]] = next
                outputs[m[6]] = next.ttlNot()
            }
        }
        fun dualJkWithReset() {
            delay = 8
            for ((half, m) in listOf(listOf(1, 4, 12, 13, 3, 2), listOf(8, 11, 9, 10, 5, 6)).withIndex()) {
                val triggered = edge(m[2], falling = true)
                val old = bit("q$half")
                val next = when {
                    low(m[3]) -> LogicValue.LOW
                    p(m[3]) == LogicValue.ERROR -> LogicValue.ERROR
                    !high(m[3]) -> LogicValue.UNKNOWN
                    !triggered -> old
                    else -> {
                        val choices = levels(p(m[0])).flatMap { j -> levels(p(m[1])).map { k ->
                            when { j && k -> complement(old); j -> LogicValue.HIGH; k -> LogicValue.LOW; else -> old }
                        } }.distinct()
                        if (choices.size == 1) choices.single() else if (
                            p(m[0]) == LogicValue.ERROR || p(m[1]) == LogicValue.ERROR
                        ) LogicValue.ERROR else LogicValue.UNKNOWN
                    }
                }
                save("q$half", next)
                outputs[m[4]] = next
                outputs[m[5]] = complement(next)
            }
        }
        fun rippleFour() {
            delay = 4
            val a = edge(14, falling = true)
            val b = edge(1, falling = true)
            val bits = word("q", 4).bits().toMutableList()
            if (high(2) && high(3)) bits.indices.forEach { bits[it] = LogicValue.LOW }
            else {
                if (a) bits[0] = bits[0].ttlNot()
                if (b) {
                    var carry = LogicValue.HIGH
                    for (i in 1..3) {
                        val old = bits[i]
                        bits[i] = old ttlXor carry
                        carry = old ttlAnd carry
                    }
                }
            }
            save("q", bits)
            out(listOf(12, 9, 8, 11), data.getValue("q"))
        }
        fun synchronousCounter(synchronousClear: Boolean) {
            val clocked = edge(2)
            var counter = oldLong(word("q", 4))
            if ((!synchronousClear || clocked) && low(1)) counter = 0
            else if (clocked) {
                if (low(9)) counter = listOf(3, 4, 5, 6).mapIndexed { i, pin -> oldLong(pin) shl i }.sum()
                else if ((p(7) ttlAnd p(10)) == LogicValue.HIGH) counter = (counter + 1).let { if (it > 15) 0 else it }
            }
            val value = LogicVector.of(4, counter.toULong())
            data["q"] = value
            out(listOf(14, 13, 12, 11), value)
            outputs[15] = value.bits().fold(p(10)) { a, b -> a ttlAnd b }
        }
        fun serialEight() {
            delay = 4
            val clocked = edge(8)
            val old = word("q", 8)
            val next = when {
                low(9) -> LogicVector.low(8)
                // Evolution shifts the values read back from the QA..QG ports, not its stored word.
                clocked -> LogicVector.fromBits(listOf((high(1) && high(2)).ttlValue()) +
                    listOf(3, 4, 5, 6, 10, 11, 12).mapIndexed { index, pin -> data["drive:$pin"]?.bit(0) ?: old.bits()[index] })
                else -> old
            }
            data["q"] = next
            out(listOf(3, 4, 5, 6, 10, 11, 12, 13), next)
        }
        fun parallelSerial(synchronous: Boolean) {
            delay = 4
            val clocked = edge(if (synchronous) 7 else 2)
            val old = word("q", 8)
            val load = if (synchronous) 15 else 1
            val inhibited = if (synchronous) high(6) else !low(15)
            val loadPins = if (synchronous) listOf(14, 12, 11, 10, 5, 4, 3, 2) else listOf(6, 5, 4, 3, 14, 13, 12, 11)
            val next = when {
                synchronous && low(9) -> LogicVector.low(8)
                !synchronous && low(load) -> read(loadPins)
                clocked && !inhibited && low(load) -> read(loadPins)
                clocked && !inhibited && (!synchronous || high(load)) -> LogicVector.fromBits(old.bits().drop(1) + p(if (synchronous) 1 else 10))
                else -> old
            }
            data["q"] = next
            outputs[if (synchronous) 13 else 9] = next.bit(0)
            if (!synchronous) outputs[7] = next.bit(0).ttlNot()
        }
        fun quadThreeState() {
            var value = word("q", 4)
            if (high(15)) value = LogicVector.low(4)
            else if (low(9) && low(10) && edge(7)) value = read(listOf(11, 12, 13, 14))
            data["q"] = value
            out(listOf(6, 5, 4, 3), if (low(1) && low(2)) value else LogicVector.unknown(4))
        }
        fun quadD() {
            delay = 8
            val clocked = edge(9)
            val value = when { low(1) -> LogicVector.low(4); clocked -> read(listOf(4, 5, 12, 13)); else -> word("q", 4) }
            data["q"] = value
            out(listOf(2, 7, 10, 15), value)
            out(listOf(3, 6, 11, 14), value.inverted())
        }
        fun decadeUpDown() {
            val clocked = edge(14)
            val old = word("q", 4)
            val sources = listOf(p(11), p(4), p(5), p(15), p(1), p(10), p(9)) + old.bits()
            val next = resolveChoices(4, sources) { code ->
                val current = (code shr 7) and 15
                when {
                    code and 1 == 0 -> (code shr 3) and 15
                    clocked && code and 2 == 0 -> if (code and 4 != 0) countDown[current] else countUp[current]
                    else -> current
                }
            }
            data["q"] = next
            out(listOf(3, 2, 6, 7), next)
            val flags = resolveChoices(2, listOf(p(4), p(5), p(14)) + next.bits()) { code ->
                val current = (code shr 3) and 15
                val terminal = if (code and 2 != 0) current == 0 else current and 9 == 9
                val ripple = !(terminal && code and 1 == 0 && code and 4 == 0)
                (if (terminal) 1 else 0) or (if (ripple) 2 else 0)
            }
            out(listOf(12, 13), flags)
        }
        fun upDownCounter(max: Int) {
            delay = 4
            val oldDown = clocks["down"] ?: LogicValue.LOW
            val oldUp = clocks["up"] ?: LogicValue.LOW
            val down = p(4)
            val up = p(5)
            clocks["down"] = down
            clocks["up"] = up
            val downRise = oldDown == LogicValue.LOW && down == LogicValue.HIGH
            val upRise = oldUp == LogicValue.LOW && up == LogicValue.HIGH
            val downFall = oldDown == LogicValue.HIGH && down == LogicValue.LOW
            val upFall = oldUp == LogicValue.HIGH && up == LogicValue.LOW
            val downHigh = oldDown == LogicValue.HIGH && down == LogicValue.HIGH
            val upHigh = oldUp == LogicValue.HIGH && up == LogicValue.HIGH
            var count = oldLong(word("q", 4))
            var carry = LogicValue.HIGH
            var borrow = LogicValue.HIGH
            when {
                high(14) -> count = 0
                low(11) -> {
                    count = listOf(15, 1, 10, 9).mapIndexed { i, pin -> oldLong(pin) shl i }.sum()
                    if (count > max) count = 0
                }
                downRise && upHigh -> { count--; if (count < 0) count = max.toLong() }
                upRise && downHigh -> { count++; if (count > max) count = 0 }
                upFall && downHigh -> if (count == max.toLong()) carry = LogicValue.LOW
                downFall && upHigh -> if (count == 0L) borrow = LogicValue.LOW
                else -> { carry = bitOr("carry", LogicValue.HIGH); borrow = bitOr("borrow", LogicValue.HIGH) }
            }
            data["q"] = LogicVector.of(4, count.toULong())
            save("carry", carry)
            save("borrow", borrow)
            out(listOf(3, 2, 6, 7), data.getValue("q"))
            outputs[12] = carry
            outputs[13] = borrow
        }
        fun universalShift(octal: Boolean) {
            val width = if (octal) 8 else 4
            val clear = if (octal) 9 else 1
            val clock = if (octal) 12 else 11
            val select0 = if (octal) 1 else 9
            val select1 = if (octal) 19 else 10
            val serialRight = if (octal) 11 else 2
            val serialLeft = if (octal) 18 else 7
            val parallel = if (octal) listOf(16, 4, 15, 5, 14, 6, 13, 7) else listOf(6, 5, 4, 3)
            var value = word("q", width)
            if (low(clear)) value = LogicVector.low(width)
            else if (edge(clock)) {
                val mode = (if (high(select1)) 2 else 0) + if (high(select0)) 1 else 0
                value = when (mode) {
                    1 -> LogicVector.fromBits(value.bits().drop(1) + p(serialRight))
                    2 -> LogicVector.fromBits(listOf(p(serialLeft)) + value.bits().dropLast(1))
                    3 -> read(parallel)
                    else -> value
                }
            }
            data["q"] = value
            if (octal) {
                outputs[8] = value.bit(7)
                outputs[17] = value.bit(0)
                val enabled = low(2) && low(3) && (low(select0) || low(select1))
                out(parallel, if (enabled) value else LogicVector.unknown(width))
            } else out(listOf(12, 13, 14, 15), value)
        }
        fun addressableLatch() {
            var value = word("q", 8)
            if (low(15) && high(14)) value = LogicVector.low(8)
            else if (low(14) && (low(15) || high(15))) {
                value = LogicVector.fromBits((0 until 8).map { index ->
                    val idle = if (low(15)) LogicValue.LOW else value.bit(index)
                    val address = listOf(p(1), p(2), p(3))
                    val excluded = address.withIndex().any { (i, bit) ->
                        if (index and (1 shl i) != 0) bit == LogicValue.LOW else bit == LogicValue.HIGH
                    }
                    when {
                        excluded -> idle
                        address.all { it == LogicValue.LOW || it == LogicValue.HIGH } -> p(13)
                        else -> choice(p(13), idle)
                    }
                })
            }
            data["q"] = value
            out(listOf(4, 5, 6, 7, 9, 10, 11, 12), value)
        }
        fun octalD(model: String) {
            delay = if (model == "74374") 1 else 8
            val clocked = edge(11)
            var value = word("q", 8)
            when {
                model == "74273" && low(1) -> value = LogicVector.low(8)
                clocked && (model != "74377" || low(1)) -> value = read(listOf(3, 4, 7, 8, 13, 14, 17, 18))
            }
            data["q"] = value
            val visible = if (model == "74374" && !low(1)) LogicVector.unknown(8) else value
            out(listOf(2, 5, 6, 9, 12, 15, 16, 19), visible)
        }
        fun dualDecade() {
            delay = 4
            for ((half, m) in listOf(listOf(1, 4, 2, 3, 5, 6, 7), listOf(15, 12, 14, 13, 11, 10, 9)).withIndex()) {
                val a = edge(m[0], falling = true)
                val b = edge(m[1], falling = true)
                val bits = word("q$half", 4).bits().toMutableList()
                if (high(m[2])) bits.indices.forEach { bits[it] = LogicValue.LOW }
                else {
                    if (a) bits[0] = complement(bits[0])
                    if (b) {
                        val upper = LogicVector.fromBits(bits.drop(1))
                        val next = increment(upper, 4)
                        (1..3).forEach { bits[it] = next.bit(it - 1) }
                    }
                }
                save("q$half", bits)
                out(m.drop(3), data.getValue("q$half"))
            }
        }
        fun dualBinary() {
            delay = 4
            for ((half, m) in listOf(listOf(1, 2, 3, 4, 5, 6), listOf(13, 12, 11, 10, 9, 8)).withIndex()) {
                val clocked = edge(m[0], falling = true)
                val old = word("q$half", 4)
                val next = when { high(m[1]) -> LogicVector.low(4); clocked -> increment(old, 15); else -> old }
                data["q$half"] = next
                out(m.drop(2), next)
            }
        }
        fun shiftAndStore(tristate: Boolean) {
            val shiftEdge = edge(11)
            val storeEdge = edge(12)
            val old = word("shift", 8)
            val cleared = low(10)
            val shift = when { cleared -> LogicVector.low(8); shiftEdge -> LogicVector.fromBits(listOf(p(14)) + old.bits().take(7)); else -> old }
            var stored = word("store", 8)
            if (!tristate && low(13)) stored = LogicVector.low(8)
            else if (storeEdge) stored = if (shiftEdge && !cleared) old else shift
            data["shift"] = shift
            data["store"] = stored
            out(listOf(15, 1, 2, 3, 4, 5, 6, 7), if (!tristate || low(13)) stored else LogicVector.unknown(8))
            outputs[9] = shift.bit(7)
        }
        fun registerFile() {
            val write = (if (high(13)) 2 else 0) + if (high(14)) 1 else 0
            val readAddress = (if (high(4)) 2 else 0) + if (high(5)) 1 else 0
            if (low(12)) data["r$write"] = read(listOf(15, 1, 2, 3))
            val value = if (low(11)) word("r$readAddress", 4) else LogicVector.unknown(4)
            out(listOf(10, 9, 7, 6), value)
        }
        fun decodedDecade() {
            delay = 4
            val rise = edge(14)
            val fall = edge(13, falling = true)
            val old = word("q", 4)
            val value = when {
                high(15) -> LogicVector.low(4)
                rise && low(13) || fall && high(14) -> increment(old, 9)
                else -> old
            }
            data["q"] = value
            val invalid = if (value.errorMask != 0uL) LogicValue.ERROR else LogicValue.UNKNOWN
            val code = value.toULongOrNull()?.toInt()
            listOf(3, 2, 4, 7, 10, 1, 5, 6, 9, 11).forEachIndexed { i, pin ->
                outputs[pin] = if (code == null || code > 9) invalid else (code == i).ttlValue()
            }
            outputs[12] = if (code == null || code > 9) invalid else (code < 5).ttlValue()
        }
        fun binaryFourteen() {
            delay = 4
            val clocked = edge(11, falling = true)
            val old = word("q", 14)
            val value = when { high(12) -> LogicVector.low(14); clocked -> increment(old, 16383); else -> old }
            data["q"] = value
            listOf(1, 2, 3, 4, 5, 6, 7, 13, 14, 15).zip(listOf(11, 12, 13, 5, 4, 6, 3, 8, 7, 9)).forEach {
                (pin, bit) -> outputs[pin] = value.bit(bit)
            }
        }
        fun serialStrobe() {
            val oldClock = clocks["3"] ?: LogicValue.LOW
            val now = p(3)
            clocks["3"] = now
            var shift = word("shift", 8)
            if (oldClock == LogicValue.LOW && now == LogicValue.HIGH)
                shift = LogicVector.fromBits(listOf(p(2)) + shift.bits().take(7))
            if (oldClock == LogicValue.HIGH && now == LogicValue.LOW) save("serial2", shift.bit(7))
            data["shift"] = shift
            val stored = if (high(1)) shift else word("store", 8)
            data["store"] = stored
            out(listOf(4, 5, 6, 7, 14, 13, 12, 11), if (high(15)) stored else LogicVector.unknown(8))
            outputs[9] = shift.bit(7)
            outputs[10] = bit("serial2")
        }
        fun monostable(now: Long) {
            delay = 4
            for ((half, m) in listOf(listOf(1, 2, 3, 13, 4), listOf(9, 10, 11, 5, 12)).withIndex()) {
                val key = "mono$half"
                val a = p(m[0]); val b = p(m[1]); val reset = p(m[2])
                val oldA = clocks["${key}a"] ?: LogicValue.UNKNOWN
                val oldB = clocks["${key}b"] ?: LogicValue.UNKNOWN
                val oldReset = clocks["${key}r"] ?: LogicValue.UNKNOWN
                var active = bit(key) == LogicValue.HIGH
                var end = data["${key}end"]?.toULongOrNull()?.toLong() ?: 0L
                if (active && now >= end) active = false
                val triggered = (oldB == LogicValue.LOW && b == LogicValue.HIGH && a == LogicValue.LOW) ||
                    (oldA == LogicValue.HIGH && a == LogicValue.LOW && b == LogicValue.HIGH) ||
                    (oldReset == LogicValue.LOW && reset == LogicValue.HIGH && a == LogicValue.LOW && b == LogicValue.HIGH)
                if (reset == LogicValue.LOW) active = false
                else if (reset == LogicValue.HIGH && triggered) {
                    val section = half + 1
                    val resistance = attributes["${section}Rext"]?.toIntOrNull() ?: 10
                    val capacitance = attributes["${section}Cext"]?.toIntOrNull() ?: 100_000
                    val widthMillis = ceil(0.45 * resistance.coerceIn(2, 1000) * capacitance.coerceIn(10_000, 1_000_000_000) / 1_000_000).toLong().coerceAtLeast(1)
                    end = now + widthMillis
                    active = true
                }
                clocks["${key}a"] = a; clocks["${key}b"] = b; clocks["${key}r"] = reset
                save(key, active.ttlValue())
                data["${key}end"] = LogicVector.of(64, end.toULong())
                outputs[m[3]] = active.ttlValue()
                outputs[m[4]] = (!active).ttlValue()
                if (active) wakeup = minOf(wakeup ?: end, end)
            }
        }
        private fun bitOr(key: String, fallback: LogicValue) = data[key]?.bit(0) ?: fallback
    }

    private val countUp = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 9, 4, 9, 0, 9, 0)
    private val countDown = intArrayOf(9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 3, 4, 5, 6)
    private fun LogicVector.bits() = (0 until width).map(::bit)
    private fun complement(value: LogicValue): LogicValue = when (value) {
        LogicValue.HIGH -> LogicValue.LOW
        LogicValue.LOW -> LogicValue.HIGH
        else -> value
    }
    private fun levels(value: LogicValue): List<Boolean> = when (value) {
        LogicValue.HIGH -> listOf(true)
        LogicValue.LOW -> listOf(false)
        else -> listOf(false, true)
    }
    private fun choice(a: LogicValue, b: LogicValue): LogicValue = when {
        a == b -> a
        a == LogicValue.ERROR || b == LogicValue.ERROR -> LogicValue.ERROR
        a in listOf(LogicValue.HIGH, LogicValue.LOW) && b in listOf(LogicValue.HIGH, LogicValue.LOW) -> LogicValue.ERROR
        else -> LogicValue.UNKNOWN
    }
    private fun resolveChoices(width: Int, sources: List<LogicValue>, evaluate: (Int) -> Int): LogicVector {
        var possibilities = listOf(0)
        sources.forEachIndexed { index, value -> possibilities = possibilities.flatMap { code ->
            levels(value).map { high -> if (high) code or (1 shl index) else code }
        } }
        val values = possibilities.map(evaluate)
        return LogicVector.fromBits((0 until width).map { bit ->
            val first = values.first() and (1 shl bit)
            if (values.all { it and (1 shl bit) == first }) (first != 0).ttlValue()
            else if (LogicValue.ERROR in sources) LogicValue.ERROR else LogicValue.UNKNOWN
        })
    }
}
