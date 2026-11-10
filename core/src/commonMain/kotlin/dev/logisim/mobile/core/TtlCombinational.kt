package dev.logisim.mobile.core

internal object TtlCombinational {
    val quadGatePins = mapOf(3 to listOf(1, 2), 6 to listOf(4, 5), 8 to listOf(9, 10), 11 to listOf(12, 13))
    val norGatePins = mapOf(1 to listOf(2, 3), 4 to listOf(5, 6), 10 to listOf(8, 9), 13 to listOf(11, 12))
    val hexGatePins = mapOf(2 to listOf(1), 4 to listOf(3), 6 to listOf(5), 8 to listOf(9), 10 to listOf(11), 12 to listOf(13))

    val supportedModels = setOf(
        "7400", "7402", "7404", "7405", "7408", "7410", "7411", "7413", "7414", "7418", "7419", "7420", "7421", "7424", "7427", "7430", "7432", "7434", "7436", "7438", "7442", "7443", "7444", "7447", "7451", "7454", "7458", "7464", "7485", "7486", "7487", "74125", "74132", "74138", "74139", "74148", "74151", "74153", "74157", "74158", "74181", "74182", "74240", "74241", "74244", "74245", "74257", "74266", "74283", "74365", "74381", "74541", "74640", "74688", "744075", "747266",
    )

    fun evaluate(model: String, pins: Map<Int, LogicValue>): TtlResult? {
        if (model !in supportedModels) return null
        val spec = TtlCatalog.spec(model)
        val indexed = spec.signalPins.map { pins.pin(it) }
        fun p(index: Int) = indexed[index]
        fun h(pin: Int) = pins.pin(pin) == LogicValue.HIGH
        fun l(pin: Int) = pins.pin(pin) == LogicValue.LOW
        val outputs = linkedMapOf<Int, LogicValue>()
        val delays = linkedMapOf<Int, Long>()
        fun set(pin: Int, value: LogicValue, delay: Long = 1) { outputs[pin] = value; delays[pin] = delay }
        fun port(index: Int, value: LogicValue, delay: Long = 1) = set(spec.signalPins[index], value, delay)
        fun and(vararg index: Int) = index.map(::p).reduce { a, b -> a ttlAnd b }
        fun or(vararg index: Int) = index.map(::p).reduce { a, b -> a ttlOr b }
        fun bits(vararg indices: Int) = indices.withIndex().sumOf { (bit, index) -> if (p(index) == LogicValue.HIGH) 1 shl bit else 0 }
        when (model) {
            "7400", "7408", "7424", "7432", "7436", "7438", "7486", "74132", "74266", "747266" -> for ((out, inputs) in quadGatePins) {
                val (first, second) = inputs
                val a = pins.pin(first)
                val b = pins.pin(second)
                val value = when (model) {
                    "7408" -> a ttlAnd b
                    "7432" -> a ttlOr b
                    "7436" -> (a ttlOr b).ttlNot()
                    "7486" -> a ttlXor b
                    "747266" -> (a ttlXor b).ttlNot()
                    "7438" -> if (!(a == LogicValue.HIGH && b == LogicValue.HIGH)) LogicValue.UNKNOWN else LogicValue.LOW
                    "74266" -> if ((a ttlXor b).ttlNot() == LogicValue.HIGH) LogicValue.UNKNOWN else LogicValue.LOW
                    else -> (a ttlAnd b).ttlNot()
                }
                set(out, value)
            }
            "7402" -> for ((out, inputs) in norGatePins) {
                val (first, second) = inputs
                set(out, (pins.pin(first) ttlOr pins.pin(second)).ttlNot())
            }
            "7404", "7414", "7419", "7434", "7405" -> for ((out, inputs) in hexGatePins) {
                val input = pins.pin(inputs.single())
                set(out, when (model) {
                    "7434" -> input
                    "7405" -> when (input) { LogicValue.HIGH -> LogicValue.LOW; LogicValue.LOW -> LogicValue.UNKNOWN; else -> LogicValue.ERROR }
                    else -> input.ttlNot()
                })
            }
            "7410", "7411", "7427" -> for (g in listOf(intArrayOf(5, 2, 3, 4), intArrayOf(10, 0, 1, 11), intArrayOf(6, 7, 8, 9))) {
                val value = if (model == "7427") or(g[1], g[2], g[3]) else and(g[1], g[2], g[3])
                port(g[0], if (model == "7411") value else value.ttlNot(), 2)
            }
            "7413", "7418", "7420", "7421" -> {
                val invert = model != "7421"
                val a = and(0, 1, 2, 3); val b = and(6, 7, 8, 9)
                port(4, if (invert) a.ttlNot() else a, 3)
                port(5, if (invert) b.ttlNot() else b, 4)
            }
            "7430" -> port(6, and(0, 1, 2, 3, 4, 5, 7, 8).ttlNot())
            "7442", "7443", "7444" -> {
                val inputs = listOf(p(13), p(12), p(11), p(10))
                if (inputs.any { it != LogicValue.LOW && it != LogicValue.HIGH }) {
                    for (i in 0..9) port(i, LogicValue.UNKNOWN)
                } else {
                    var code = bits(13, 12, 11, 10)
                    if (model == "7443") code -= 3
                    val gray = listOf(2, 6, 7, 5, 4, 12, 13, 15, 14, 10)
                    for (i in 0..9) port(i, (code != if (model == "7444") gray[i] else i).ttlValue())
                }
            }
            "7447" -> {
                val inputs = listOf(7, 1, 2, 6).map { pins.pin(it) }
                var code = if (LogicValue.UNKNOWN in inputs) -1 else inputs.withIndex().sumOf { (i, v) -> if (v == LogicValue.HIGH) 1 shl i else 0 }
                val blankZero = l(5) && code == 0
                if (l(4)) code = 15 else if (l(3)) code = 8 else if (blankZero) code = 15
                val masks = intArrayOf(0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7c,0x07,0x7f,0x67,0x58,0x4c,0x62,0x69,0x78,0x00)
                for ((bit, pin) in listOf(13, 12, 11, 10, 9, 15, 14).withIndex()) {
                    set(pin, if (code < 0) LogicValue.UNKNOWN else (masks[code] and (1 shl bit) == 0).ttlValue(), 3)
                }
                set(4, if (blankZero && !l(3)) LogicValue.LOW else LogicValue.UNKNOWN, 3)
            }
            "7451" -> { port(5, ((p(1) ttlAnd p(2)) ttlOr (p(3) ttlAnd p(4))).ttlNot(), 3); port(6, ((p(0) ttlAnd p(9)) ttlOr (p(7) ttlAnd p(8))).ttlNot(), 3) }
            "7454" -> port(5, ((p(0) ttlAnd p(8)) ttlOr ((p(1) ttlAnd p(2)) ttlOr ((p(3) ttlAnd p(4)) ttlOr (p(6) ttlAnd p(7))))).ttlNot(), 3)
            "7458" -> { port(5, (p(1) ttlAnd p(2)) ttlOr (p(3) ttlAnd p(4)), 5); port(6, and(0, 11, 10) ttlOr and(9, 8, 7), 5) }
            "7464" -> port(6, ((p(1) ttlAnd p(2)) ttlOr (and(3, 4, 5) ttlOr ((p(7) ttlAnd p(8)) ttlOr and(9, 10, 11, 0)))).ttlNot(), 7)
            "7485" -> {
                val a = bits(8, 10, 11, 13); val b = bits(7, 9, 12, 0)
                val gt: Boolean; val eq: Boolean; val lt: Boolean
                when {
                    a > b -> { gt=true; eq=false; lt=false }
                    a < b -> { gt=false; eq=false; lt=true }
                    p(2) == LogicValue.HIGH -> { gt=false; eq=true; lt=false }
                    p(1) == LogicValue.HIGH && p(3) == LogicValue.HIGH -> { gt=false; eq=false; lt=false }
                    p(1) == LogicValue.HIGH -> { gt=false; eq=false; lt=true }
                    p(3) == LogicValue.HIGH -> { gt=true; eq=false; lt=false }
                    else -> { gt=true; eq=false; lt=true }
                }
                port(4, gt.ttlValue()); port(5, eq.ttlValue()); port(6, lt.ttlValue())
            }
            "7487" -> for ((out, data) in listOf(2 to 1, 4 to 3, 6 to 7, 8 to 9)) port(out, when (p(5)) {
                LogicValue.HIGH -> p(0).ttlNot()
                LogicValue.LOW -> when (p(0)) { LogicValue.HIGH -> p(data); LogicValue.LOW -> p(data).ttlNot(); else -> LogicValue.ERROR }
                else -> LogicValue.ERROR
            })
            "74125" -> for ((out, data, enable) in listOf(Triple(2,1,0), Triple(5,4,3), Triple(6,7,8), Triple(9,10,11))) port(out, if (p(enable) == LogicValue.HIGH) LogicValue.UNKNOWN else p(data))
            "74138" -> {
                val enabled = h(6) && l(4) && l(5); val selected = (if(h(1))1 else 0)+(if(h(2))2 else 0)+(if(h(3))4 else 0)
                for ((i,pin) in listOf(15,14,13,12,11,10,9,7).withIndex()) set(pin, (!enabled || i != selected).ttlValue())
            }
            "74139" -> for ((enable, a, b, outs) in listOf(Decoder(1,2,3,listOf(4,5,6,7)), Decoder(15,14,13,listOf(12,11,10,9)))) {
                val selected=(if(h(a))1 else 0)+(if(h(b))2 else 0)
                for((i,pin) in outs.withIndex()) set(pin, (!l(enable)||i!=selected).ttlValue())
            }
            "74148" -> {
                val inputPins=listOf(10,11,12,13,1,2,3,4)
                val code=if(l(5)) inputPins.indexOfLast(::l) else -1
                for((bit,pin) in listOf(9,7,6).withIndex()) set(pin, (!(code>=0 && code and (1 shl bit)!=0)).ttlValue())
                set(14,(code<0).ttlValue()); set(15,(!(l(5)&&code<0)).ttlValue())
            }
            "74151" -> {
                val select=(if(h(11))1 else 0)+(if(h(10))2 else 0)+(if(h(9))4 else 0)
                val data=h(listOf(4,3,2,1,15,14,13,12)[select]); set(5,(!h(7)&&data).ttlValue());set(6,(h(7)||!data).ttlValue())
            }
            "74153" -> {
                val select=(if(h(14))1 else 0)+(if(h(2))2 else 0)
                set(7,(!h(1)&&h(listOf(6,5,4,3)[select])).ttlValue())
                set(9,(!h(15)&&h(listOf(10,11,12,13)[select])).ttlValue())
            }
            "74157", "74158" -> for((a,b,out) in listOf(Triple(2,3,4),Triple(5,6,7),Triple(11,10,9),Triple(14,13,12))) {
                val value=(!h(15) && h(if(h(1)) b else a)).ttlValue()
                set(out,if(model=="74158")value.ttlNot() else value)
            }
            "74181", "74182", "74381" -> return TtlPal.evaluate(model,pins)
            "74240", "74241", "74244" -> {
                val inv=model=="74240"
                for((out,dat) in listOf(16 to 1,14 to 3,12 to 5,10 to 7)) port(out,if(p(0)==LogicValue.HIGH)LogicValue.UNKNOWN else if(inv)p(dat).ttlNot() else p(dat))
                for((out,dat) in listOf(8 to 9,6 to 11,4 to 13,2 to 15)) port(out,if(p(17)==if(model=="74241")LogicValue.LOW else LogicValue.HIGH)LogicValue.UNKNOWN else if(inv)p(dat).ttlNot() else p(dat))
            }
            "74245", "74640" -> {
                val enabled=if(model=="74245") !h(19) else l(19)
                val direction=pins.pin(1)
                for(i in 2..9) { set(i,LogicValue.UNKNOWN);set(20-i,LogicValue.UNKNOWN) }
                if(enabled && (model=="74245" || direction==LogicValue.LOW || direction==LogicValue.HIGH)) {
                    for(i in 2..9) {
                        val out=if(direction==LogicValue.HIGH)20-i else i
                        val input=if(direction==LogicValue.HIGH)i else 20-i
                        set(out,if(model=="74640")pins.pin(input).ttlNot() else pins.pin(input))
                    }
                }
            }
            "74257" -> for((a,b,out) in listOf(Triple(2,3,4),Triple(5,6,7),Triple(11,10,9),Triple(14,13,12))) {
                val selected=select(pins.pin(1),pins.pin(a),pins.pin(b)); val enable=pins.pin(15)
                set(out,when {enable==LogicValue.HIGH->LogicValue.UNKNOWN;enable==LogicValue.LOW->selected;selected==LogicValue.UNKNOWN&&enable==LogicValue.UNKNOWN->LogicValue.UNKNOWN;enable==LogicValue.ERROR||selected==LogicValue.ERROR->LogicValue.ERROR;else->LogicValue.UNKNOWN})
            }
            "74283" -> {
                val sum=bits(4,2,12,10)+bits(5,1,13,9)+(if(p(6)==LogicValue.HIGH)1 else 0)
                for((bit,out)in listOf(3,0,11,8,7).withIndex())port(out,(sum and (1 shl bit)!=0).ttlValue())
            }
            "74365" -> for((input,out)in listOf(2 to 3,4 to 5,6 to 7,10 to 9,12 to 11,14 to 13)) set(out,if(l(1)&&l(15))pins.pin(input)else LogicValue.UNKNOWN)
            "74541" -> for(i in 2..9) set(20-i,if(l(1)&&l(19))pins.pin(i)else LogicValue.UNKNOWN)
            "74688" -> {
                val pairs=listOf(2 to 3,4 to 5,6 to 7,8 to 9,11 to 12,13 to 14,15 to 16,17 to 18)
                val matches=pairs.all{(a,b)->pins.pin(a)==pins.pin(b)&&(pins.pin(a)==LogicValue.LOW||pins.pin(a)==LogicValue.HIGH)}
                set(19,(!(l(1)&&matches)).ttlValue())
            }
            "744075" -> {set(6,(pins.pin(3) ttlOr pins.pin(4)) ttlOr pins.pin(5));set(9,(pins.pin(1) ttlOr pins.pin(2)) ttlOr pins.pin(8));set(10,(pins.pin(11) ttlOr pins.pin(12)) ttlOr pins.pin(13))}
        }
        return TtlResult(outputs, outputDelays=delays)
    }

    private data class Decoder(val enable:Int,val a:Int,val b:Int,val outputs:List<Int>)
    private fun select(select:LogicValue,a:LogicValue,b:LogicValue):LogicValue=when {
        select==LogicValue.LOW->a
        select==LogicValue.HIGH->b
        a==b->a
        select==LogicValue.ERROR||a==LogicValue.ERROR||b==LogicValue.ERROR->LogicValue.ERROR
        else->LogicValue.UNKNOWN
    }
}
