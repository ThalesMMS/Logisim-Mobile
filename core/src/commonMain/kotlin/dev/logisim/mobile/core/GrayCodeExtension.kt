package dev.logisim.mobile.core

/** Static port of Carl Burch's Logisim 2.7.1 Gray Code Incrementer example. */
object GrayCodeExtension {
    val incrementerKind = ComponentKind("GrayCodeIncrementer")
    private val width = AttributeKey.int("width", 4, 1..32)

    val provider = StaticMobileExtension(
        MobileExtensionManifest(
            id = "dev.logisim.gray",
            version = "1.0.0",
            displayName = "Gray Code Incrementer",
            publisher = "Logisim Mobile contributors",
            licenseId = "GPL-3.0-only",
            licenseNotice = """
                Gray Code Incrementer from the Logisim 2.7.1 Java library example.
                Copyright (c) 2010, Carl Burch.
                Kotlin port copyright (c) 2026, Logisim Mobile contributors.

                The original example is licensed under GNU GPL version 2 or later.
                This port is distributed under GNU GPL version 3.

                This program is free software: you can redistribute it and/or modify
                it under the terms of the GNU General Public License as published by
                the Free Software Foundation, version 3 of the License.

                This program is distributed in the hope that it will be useful,
                but WITHOUT ANY WARRANTY; without even the implied warranty of
                MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
                GNU General Public License for more details.

                You should have received a copy of the GNU General Public License
                along with this program. If not, see https://www.gnu.org/licenses/.
                Original source: https://www.cburch.com/logisim/
            """.trimIndent(),
            capabilities = setOf(ExtensionCapability.PURE_LOGIC),
            components = listOf(ExtensionComponentSpec(incrementerKind.name, "Gray Code Incrementer", "#dev.logisim.gray")),
            legacyJarClasses = setOf("com.cburch.gray.Components"),
        ),
        listOf(ComponentDefinition(
            kind = incrementerKind,
            fileName = "Gray Code Incrementer",
            library = "#dev.logisim.gray",
            defaultAttributes = mapOf("width" to "4"),
            schema = AttributeSchema(listOf(width)),
            layout = { component, attributes ->
                ComponentLayout(listOf(
                    ComponentTerminal("in", GridPoint(component.position.x - 30, component.position.y), PortDirection.INPUT, attributes[width]),
                    ComponentTerminal("out", component.position, PortDirection.OUTPUT, attributes[width]),
                ), ComponentBounds(component.position.x - 30, component.position.y - 15, component.position.x, component.position.y + 15))
            },
            behavior = object : ComponentBehavior {
                override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                    val bits = context.attributes[width]
                    val input = context.inputs["in"] ?: LogicVector.unknown(bits)
                    val output = if (!input.isFullyDefined) LogicVector.error(bits) else {
                        val value = input.value
                        val next = if (value.countOneBits() % 2 == 0) value xor 1uL else {
                            val nextBit = ((value xor (value and (value - 1uL))) shl 1) and ((1uL shl bits) - 1uL)
                            if (nextBit == 0uL) 0uL else value xor nextBit
                        }
                        LogicVector.of(bits, next)
                    }
                    return ComponentEvaluation(mapOf("out" to output), delay = bits + 1L)
                }
            },
            supportsRotation = false,
            isCombinational = true,
        )),
    )
}
