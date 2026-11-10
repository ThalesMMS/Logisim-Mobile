package dev.logisim.mobile.core

data class JarObservedInstance(
    val id: String,
    val path: List<GridPoint>,
    val circuit: String,
    val factory: String,
    val location: GridPoint,
    val layout: ComponentLayout,
    val exclusiveTerminals: List<Boolean>,
)

sealed interface JarTerminalDrive {
    data object Released : JarTerminalDrive
    data class Driven(val value: LogicVector) : JarTerminalDrive
}

data class JarInstanceTrace(
    val instances: List<JarObservedInstance>,
    /** Values are resolved at the wire after each approved frame, in terminal index order. */
    val frames: List<Map<String, List<LogicVector>>>,
    /** Actual drives in terminal order. Null entries are input-only ports. */
    val drives: List<Map<String, List<JarTerminalDrive?>>>? = null,
)

fun CompanionJsonObject.readJarInstanceTrace(expectedFrames: Int, expectDriverTrace: Boolean = false): JarInstanceTrace {
    require(expectedFrames in 1..256)
    val instances = array("instances").also { require(it.size in 1..128) }.map { raw ->
        val item = raw.traceObject()
        val id = item.string("id")
        val path = item.array("path").also { require(it.size <= 24) }.map { it.tracePoint() }
        val circuit = item.string("circuit")
        val factory = item.string("factory")
        val location = item.fields.getValue("location").tracePoint()
        require(circuit.isNotBlank() && factory.isNotBlank())
        require(id == (path + location).joinToString("") { "/${it.x},${it.y}" })
        val (x, y, width, height) = item.fields.getValue("bounds").traceCoordinates(4)
        require(width >= 0 && height >= 0)
        val right = (x.toLong() + width).traceInt()
        val bottom = (y.toLong() + height).traceInt()
        val bounds = ComponentBounds(x, y, right, bottom)
        val terminals = item.array("terminals").mapIndexed { index, rawTerminal ->
            val terminal = rawTerminal.traceObject()
            val direction = when (terminal.string("direction")) {
                "input" -> PortDirection.INPUT
                "output" -> PortDirection.OUTPUT
                "inout" -> PortDirection.INOUT
                else -> throw IllegalArgumentException("Invalid JAR terminal direction.")
            }
            val bits = terminal.long("width").also { require(it in 1..64) }.toInt()
            ComponentTerminal("p$index", terminal.fields.getValue("position").tracePoint(), direction, bits) to
                terminal.boolean("exclusive")
        }
        JarObservedInstance(id, path, circuit, factory, location, ComponentLayout(terminals.map { it.first }, bounds),
            terminals.map { it.second })
    }
    val byId = instances.associateBy { it.id }
    require(byId.size == instances.size)
    require(instances.sumOf { it.layout.ports.size } * expectedFrames <= 4096)
    val frames = array("frames").also { require(it.size == expectedFrames) }.map { raw ->
        val frame = raw.traceObject()
        require(frame.fields.keys == byId.keys)
        instances.associate { item ->
            val values = frame.array(item.id)
            require(values.size == item.layout.ports.size)
            item.id to values.zip(item.layout.ports) { value, terminal ->
                value.readJarSignal(terminal.width)
            }
        }
    }
    require(("drives" in fields) == expectDriverTrace)
    val drives = if (expectDriverTrace) array("drives").also { require(it.size == expectedFrames) }.map { raw ->
        val frame = raw.traceObject()
        require(frame.fields.keys == byId.keys)
        instances.associate { item ->
            val values = frame.array(item.id)
            require(values.size == item.layout.ports.size)
            item.id to values.zip(item.layout.ports) { value, terminal ->
                when {
                    terminal.direction == PortDirection.INPUT -> {
                        require(value == CompanionJsonNull)
                        null
                    }
                    value == CompanionJsonString("Z") -> JarTerminalDrive.Released
                    else -> JarTerminalDrive.Driven(value.readJarSignal(terminal.width))
                }
            }
        }
    } else null
    return JarInstanceTrace(instances, frames, drives)
}

private fun CompanionJson.traceObject() = this as? CompanionJsonObject
    ?: throw IllegalArgumentException("Expected a JAR trace object.")

private fun CompanionJson.tracePoint(): GridPoint {
    val (x, y) = traceCoordinates(2)
    return GridPoint(x, y)
}

private fun CompanionJson.traceCoordinates(count: Int): List<Int> {
    val values = (this as? CompanionJsonArray)?.values
    require(values != null && values.size == count)
    return values.map { (it as? CompanionJsonNumber)?.value?.toLongOrNull()?.traceInt()
        ?: throw IllegalArgumentException("Invalid JAR trace coordinate.") }
}

private fun Long.traceInt(): Int {
    require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return toInt()
}


internal fun CompanionJson.readJarSignal(width: Int): LogicVector {
    val bits = (this as? CompanionJsonString)?.value
        ?: throw IllegalArgumentException("Invalid JAR signal sample.")
    require(bits.length == width)
    return LogicVector.fromBits(bits.reversed().map {
        when (it) {
            '0' -> LogicValue.LOW
            '1' -> LogicValue.HIGH
            '?' -> LogicValue.UNKNOWN
            'E' -> LogicValue.ERROR
            else -> throw IllegalArgumentException("Invalid JAR signal value.")
        }
    })
}
