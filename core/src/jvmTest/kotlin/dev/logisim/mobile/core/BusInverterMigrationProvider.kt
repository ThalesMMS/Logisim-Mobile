// SPDX-License-Identifier: GPL-3.0-only
package dev.logisim.mobile.core

/** Port of the independently built org.example.InverterLibrary 2.7.1 fixture. */
object BusInverterMigrationProvider {
    private val kind = ComponentKind("ExampleBusInverter")
    private const val fileName = "Bus Inverter"
    private const val library = "#org.example.bus-inverter"

    val provider = StaticMobileExtension(
        MobileExtensionManifest(
            id = "org.example.bus-inverter",
            version = "1.0.0",
            displayName = fileName,
            publisher = "Logisim Mobile contributors",
            licenseId = "GPL-3.0-only",
            licenseNotice = """
                Copyright (c) 2026 Logisim Mobile contributors.

                This program is free software: you can redistribute it and/or modify
                it under the terms of the GNU General Public License as published by
                the Free Software Foundation, version 3 of the License.

                This program is distributed in the hope that it will be useful,
                but WITHOUT ANY WARRANTY; without even the implied warranty of
                MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
                GNU General Public License for more details.

                You should have received a copy of the GNU General Public License
                along with this program. If not, see https://www.gnu.org/licenses/.
            """.trimIndent(),
            capabilities = setOf(ExtensionCapability.PURE_LOGIC),
            components = listOf(ExtensionComponentSpec(kind.name, fileName, library)),
            legacyJarClasses = setOf("org.example.InverterLibrary"),
        ),
        listOf(ComponentDefinition(
            kind = kind,
            fileName = fileName,
            library = library,
            layout = { component, _ ->
                val (x, y) = component.position
                ComponentLayout(
                    listOf(
                        ComponentTerminal("in", GridPoint(x - 20, y), PortDirection.INPUT, 4),
                        ComponentTerminal("out", component.position, PortDirection.OUTPUT, 4),
                    ),
                    ComponentBounds(x - 20, y - 10, x, y + 10),
                )
            },
            behavior = object : ComponentBehavior {
                override fun evaluate(context: ComponentEvaluationContext) = ComponentEvaluation(
                    mapOf("out" to context.inputs.getValue("in").inverted()),
                    delay = 1,
                )
            },
            supportsRotation = false,
            isCombinational = true,
        )),
    )
}
