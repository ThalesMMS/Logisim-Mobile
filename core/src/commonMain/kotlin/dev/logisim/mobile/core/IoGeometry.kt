/*
 * Adapted from Logisim-evolution I/O components.
 * Copyright by the Logisim-evolution developers.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.logisim.mobile.core

object IoGeometry {
    fun layout(component: CircuitComponent, attributes: AttributeValues): ComponentLayout {
        fun port(name: String, x: Int, y: Int, direction: PortDirection, width: Int = 1) =
            ComponentTerminal(name, GridPoint(component.position.x + x, component.position.y + y), direction, width)
        fun bounds(x: Int, y: Int, width: Int, height: Int) = ComponentBounds(
            component.position.x + x, component.position.y + y,
            component.position.x + x + width, component.position.y + y + height)
        fun rotatedBounds(x: Int, y: Int, width: Int, height: Int, from: Direction, to: Direction): ComponentBounds {
            val points = listOf(GridPoint(x, y), GridPoint(x + width, y), GridPoint(x, y + height), GridPoint(x + width, y + height))
                .map { rotate(it, from, to) }
            return bounds(points.minOf { it.x }, points.minOf { it.y },
                points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
        }
        return when (component.kind) {
            IoKinds.Button -> ComponentLayout(listOf(port("out", 0, 0, PortDirection.OUTPUT)),
                rotatedBounds(-20, -10, 20, 20, Direction.EAST, attributes[IoAttributes.Facing]))
            IoKinds.DipSwitch -> {
                val count = attributes[IoAttributes.Switches]
                val facing = attributes[IoAttributes.DipFacing]
                val ports = (0 until count).map { index ->
                    val location = when (facing) {
                        Direction.NORTH -> GridPoint(10 * (index + 1), 0)
                        Direction.SOUTH -> GridPoint(-10 * (count - index), 0)
                        Direction.EAST -> GridPoint(0, 10 * (index + 1))
                        Direction.WEST -> GridPoint(0, -10 * (index + 1))
                    }
                    port("switch-$index", location.x, location.y, PortDirection.OUTPUT)
                }
                ComponentLayout(ports, rotatedBounds(0, 0, 10 * (count + 1), 40, Direction.NORTH, facing))
            }
            IoKinds.Joystick -> {
                val coordinates = when (attributes[IoAttributes.Facing]) {
                    Direction.NORTH -> listOf(GridPoint(-20, -10), GridPoint(-10, -10))
                    Direction.SOUTH -> listOf(GridPoint(-20, 20), GridPoint(-10, 20))
                    Direction.WEST -> listOf(GridPoint(-30, 0), GridPoint(-30, 10))
                    Direction.EAST -> listOf(GridPoint(0, 0), GridPoint(0, 10))
                }
                val width = attributes[IoAttributes.JoystickBits]
                ComponentLayout(coordinates.mapIndexed { index, at -> port(if (index == 0) "x" else "y", at.x, at.y, PortDirection.OUTPUT, width) },
                    bounds(-30, -10, 30, 30))
            }
            IoKinds.Keyboard -> ComponentLayout(listOf(
                port("clear", 20, 10, PortDirection.INPUT), port("clock", 0, 0, PortDirection.INPUT),
                port("readEnable", 10, 10, PortDirection.INPUT), port("available", 130, 10, PortDirection.OUTPUT),
                port("data", 140, 10, PortDirection.OUTPUT, 7)), bounds(0, -15, 145, 25))
            IoKinds.Led -> ComponentLayout(listOf(port("in", 0, 0, PortDirection.INPUT)),
                rotatedBounds(0, -10, 20, 20, Direction.WEST, attributes[IoAttributes.LedFacing]))
            IoKinds.RgbLed -> {
                val coordinates = when (attributes[IoAttributes.LedFacing]) {
                    Direction.NORTH -> listOf(GridPoint(0, 0), GridPoint(10, 10), GridPoint(-10, 10))
                    Direction.SOUTH -> listOf(GridPoint(0, 0), GridPoint(-10, -10), GridPoint(10, -10))
                    Direction.EAST -> listOf(GridPoint(0, 0), GridPoint(-10, 10), GridPoint(-10, -10))
                    Direction.WEST -> listOf(GridPoint(0, 0), GridPoint(10, -10), GridPoint(10, 10))
                }
                ComponentLayout(coordinates.mapIndexed { index, at -> port(listOf("red", "green", "blue")[index], at.x, at.y, PortDirection.INPUT) },
                    rotatedBounds(0, -10, 20, 20, Direction.WEST, attributes[IoAttributes.LedFacing]))
            }
            IoKinds.HexDigit -> ComponentLayout(buildList {
                add(port("data", 0, 0, PortDirection.INPUT, 4))
                if (attributes[IoAttributes.DecimalPoint]) {
                    val offset = if (component.isOriginalHexDigit) 10 else 20
                    add(port("decimalPoint", offset, 0, PortDirection.INPUT))
                }
            }, bounds(-15, -60, 40, 60))
            IoKinds.SevenSegment -> {
                val count = if (attributes[IoAttributes.DecimalPoint]) 8 else 7
                val ports = if (attributes[IoAttributes.SegmentInput] == "bus") {
                    val at = when (attributes[IoAttributes.BusSide]) {
                        Direction.NORTH -> GridPoint(10, 0)
                        Direction.SOUTH -> GridPoint(10, 60)
                        Direction.WEST -> GridPoint(-10, 30)
                        Direction.EAST -> GridPoint(40, 30)
                    }
                    listOf(port("segments", at.x, at.y, PortDirection.INPUT, count))
                } else {
                    val coordinates = listOf(GridPoint(20, 0), GridPoint(30, 0), GridPoint(20, 60), GridPoint(10, 60),
                        GridPoint(0, 60), GridPoint(10, 0), GridPoint(0, 0), GridPoint(30, 60))
                    coordinates.take(count).mapIndexed { index, at -> port("segment-$index", at.x, at.y, PortDirection.INPUT) }
                }
                ComponentLayout(ports, bounds(-5, 0, 40, 60))
            }
            IoKinds.Tty -> {
                val width = maxOf(30, 12 + attributes[IoAttributes.TtyColumns] * 7)
                val height = maxOf(30, 12 + attributes[IoAttributes.TtyRows] * 15)
                ComponentLayout(listOf(port("clear", 20, 10, PortDirection.INPUT), port("clock", 0, 0, PortDirection.INPUT),
                    port("writeEnable", 10, 10, PortDirection.INPUT), port("data", 0, -10, PortDirection.INPUT, 7)), bounds(0, 10 - height, width, height))
            }
            IoKinds.MatrixKeypad -> {
                val large = attributes[IoAttributes.KeypadSize] == "large"
                val edge = if (large) 60 else 30
                ComponentLayout((0..3).map { port("row-$it", -edge + (it + 1) * 10, edge, PortDirection.INPUT) } +
                    (0..3).map { port("column-$it", edge, -edge + (it + 1) * 10, PortDirection.OUTPUT) },
                    bounds(-edge, -edge, 2 * edge, 2 * edge))
            }
            IoKinds.DotMatrix, IoKinds.LedBar -> {
                val bar = component.kind == IoKinds.LedBar
                val rows = if (bar) 1 else attributes[IoAttributes.MatrixRows]
                val columns = if (bar) attributes[IoAttributes.BarColumns] else attributes[IoAttributes.MatrixColumns]
                val scaleY = if (bar) attributes[IoAttributes.BarHeight] else 1
                val mode = if (bar) attributes[IoAttributes.BarInput] else attributes[IoAttributes.MatrixInput]
                val facing = if (bar) attributes[IoAttributes.Facing] else Direction.EAST
                val bottomLeft = attributes[IoAttributes.SelectLocation] == "bl"
                val rawPorts = when (mode) {
                    "column" -> (0 until columns).map { Triple("column-$it", GridPoint(10 * it, if (bottomLeft) 0 else -rows * 10 * scaleY), rows) }
                    "row" -> (0 until rows).map { Triple("row-$it", GridPoint(if (bottomLeft) 0 else columns * 10, 10 * it), columns) }
                    else -> if (rows <= 1) listOf(Triple("columns", GridPoint(0, 0), columns), Triple("rows", GridPoint(columns * 10, 0), rows))
                    else listOf(Triple("columns", GridPoint(if (bottomLeft) 0 else columns * 10, 0), columns),
                        Triple("rows", GridPoint(if (bottomLeft) 0 else columns * 10, 10), rows))
                }
                val ports = rawPorts.map { (name, at, width) -> rotate(at, Direction.EAST, facing).let { port(name, it.x, it.y, PortDirection.INPUT, width) } }
                val origin = when (mode) {
                    "column" -> GridPoint(-5, -10 * scaleY * rows)
                    "row" -> GridPoint(0, -5 * scaleY)
                    else -> GridPoint(0, if (rows == 1) -5 * scaleY else -5 * scaleY * rows + 5)
                }
                ComponentLayout(ports, rotatedBounds(origin.x, origin.y, columns * 10, rows * 10 * scaleY, Direction.EAST, facing))
            }
            else -> error("Unknown I/O component: ${component.kind.name}")
        }
    }

    fun rotate(point: GridPoint, from: Direction, to: Direction): GridPoint {
        fun turns(direction: Direction) = when (direction) {
            Direction.EAST -> 0
            Direction.SOUTH -> 1
            Direction.WEST -> 2
            Direction.NORTH -> 3
        }
        return when ((turns(to) - turns(from) + 4) % 4) {
            1 -> GridPoint(-point.y, point.x)
            2 -> GridPoint(-point.x, -point.y)
            3 -> GridPoint(point.y, -point.x)
            else -> point
        }
    }
}
