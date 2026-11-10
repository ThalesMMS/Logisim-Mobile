package dev.logisim.mobile.core

data class TtlPin(val number: Int, val name: String, val position: GridPoint, val direction: PortDirection)

object TtlGeometry {
    fun pins(model: String, position: GridPoint, facing: String = "east", powerPins: Boolean = false): List<TtlPin> {
        val spec = TtlCatalog.spec(model)
        val signalOrder = spec.signalPins
        val visible = signalOrder + if (powerPins) listOf(spec.gndPin, spec.vccPin) else emptyList()
        return visible.map { number ->
            val lower = number <= spec.pinCount / 2
            val x = if (lower) (number - 1) * 20 + 10 else (spec.pinCount - number) * 20 + 10
            val y = if (lower) spec.height - 30 else -30
            val local = rotate(x, y, facing)
            val bus299 = model == "74299" && number in setOf(4, 5, 6, 7, 13, 14, 15, 16)
            TtlPin(number, when (number) {
                spec.gndPin -> "GND"
                spec.vccPin -> "VCC"
                else -> spec.pinNames[number] ?: number.toString()
            }, GridPoint(position.x + local.x, position.y + local.y), when {
                number in spec.inoutPins || bus299 -> PortDirection.INOUT
                number in spec.outputPins -> PortDirection.OUTPUT
                else -> PortDirection.INPUT
            })
        }
    }

    fun bounds(model: String, position: GridPoint, facing: String = "east"): ComponentBounds {
        val spec=TtlCatalog.spec(model)
        val points=listOf(rotate(0,-30,facing),rotate(spec.pinCount*10,-30,facing),
            rotate(0,spec.height-30,facing),rotate(spec.pinCount*10,spec.height-30,facing))
        return ComponentBounds(position.x+points.minOf{it.x},position.y+points.minOf{it.y},
            position.x+points.maxOf{it.x},position.y+points.maxOf{it.y})
    }

    private fun rotate(x:Int,y:Int,facing:String):GridPoint=when(facing) {
        "east"->GridPoint(x,y)
        "west"->GridPoint(-x,-y)
        "north"->GridPoint(y,-x)
        "south"->GridPoint(-y,x)
        else->throw IllegalArgumentException("Direção TTL inválida: $facing")
    }
}
