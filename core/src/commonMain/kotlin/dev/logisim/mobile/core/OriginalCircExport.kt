package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** Writes a compatibility copy of a project that already uses the original Logisim format. */
object OriginalCircExport {
    fun supports(project: CircuitProject): Boolean = CircFormatProfile.parse(project.sourceVersion).originalLogisim

    @kotlin.jvm.JvmOverloads
    fun write(project: CircuitProject, registry: ComponentRegistry = ComponentCatalog.registry): String {
        requireLocalized(supports(project)) { message(Messages.OriginalCircExportSourceRequired) }
        val circuits = project.circuits.map { circuit ->
            validateMultipliers(circuit, registry)
            val displays = circuit.components.filter { it.kind == IoKinds.HexDigit }
            if (displays.isEmpty()) return@map circuit

            val editor = CircuitEditor(circuit, registry)
            for (display in displays.filterNot { it.isOriginalHexDigit }) {
                editor.updateComponent(display.id, ::asOriginal)
                requireLocalized(editor.document.components.first { it.id == display.id }.isOriginalHexDigit) {
                    pointConflict(circuit, display)
                }
            }
            validateDisabledPoints(editor.document, registry)
            editor.document
        }
        return CircCodec.writeProject(project.copy(circuits = circuits), registry)
    }

    private fun validateMultipliers(circuit: CircuitDocument, registry: ComponentRegistry) {
        val multiplierKind = ComponentKind(ArithmeticOperation.MULTIPLIER.name)
        for (component in circuit.components.filter { it.kind == multiplierKind }) {
            val width = registry.attributes(component)[CommonAttributes.Width]
            // Logisim 2.7.1 sign-extends both 32-bit inputs and the carry.
            requireLocalized(width != 32 || component.multiplierMode(width) == "twosComplement") {
                message(Messages.OriginalCircExportMultiplierIncompatible,
                    "circuit" to circuit.name, "x" to component.position.x, "y" to component.position.y)
            }
        }
    }

    private fun asOriginal(display: CircuitComponent) = display.copy(
        attributes = display.attributes + (ORIGINAL_HEX_DIGIT_ATTRIBUTE to "true"),
        implicitAttributes = display.implicitAttributes + (ORIGINAL_HEX_DIGIT_ATTRIBUTE to "true"),
    )

    private fun validateDisabledPoints(circuit: CircuitDocument, registry: ComponentRegistry) {
        val disabled = circuit.components.filter {
            it.kind == IoKinds.HexDigit && !registry.attributes(it)[IoAttributes.DecimalPoint]
        }
        if (disabled.isEmpty()) return
        val terminals = circuit.components.flatMap { it.layout(registry).ports }.map { it.position }.toSet()
        for (display in disabled) {
            // The desktop always has this input, even if the Mobile display hides it.
            val withPoint = display.copy(attributes = display.attributes + (IoAttributes.DecimalPoint.name to "true"))
            val point = withPoint.layout(registry).ports.single { it.name == "decimalPoint" }.position
            requireLocalized(point !in terminals && circuit.wires.none { it.contains(point) }) {
                pointConflict(circuit, display)
            }
        }
    }

    private fun pointConflict(circuit: CircuitDocument, display: CircuitComponent) =
        message(Messages.OriginalCircExportHexPointConflict,
            "circuit" to circuit.name, "x" to display.position.x, "y" to display.position.y)
}
