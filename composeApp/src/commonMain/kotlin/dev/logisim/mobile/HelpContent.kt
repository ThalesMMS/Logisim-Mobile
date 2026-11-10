package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitExample
import dev.logisim.mobile.core.CircuitExamples
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ExampleResult
import dev.logisim.mobile.core.ExampleRunner
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

internal data class HelpGuide(
    val id: String,
    val title: LocalizedText,
    val steps: List<LocalizedText>,
    val toolId: String? = null,
)

internal data class HelpExample(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val instructions: LocalizedText,
) {
    val circuit: CircuitExample? get() = CircuitExamples.find(id)
}

internal data class HelpSupportArea(
    val title: LocalizedText,
    val description: LocalizedText,
    val issues: List<Int>,
)

internal object HelpContent {
    const val repository = AppIdentity.repository
    const val parityUrl = "$repository/blob/main/docs/parity.md"
    const val userGuideUrl = "$repository/blob/main/docs/user-guide.md"

    val guides = listOf(
        guide("editing", 3),
        guide("connections", 3, "accessibility"),
        guide("simulation", 4),
        guide("diagnostics", 3, "diagnostics"),
        guide("saving", 4),
        guide("navigation", 3),
    )
    val examples = listOf("and", "d-flip-flop", "bus-adder", "hierarchy", "rom", "sap1", "bus-conflict").map { id ->
        val key = id.replace('-', '_')
        HelpExample(id, message("help_example_${key}_title"), message("help_example_${key}_description"),
            message("help_example_${key}_instructions"))
    }
    val support = listOf(
        area("editing", listOf(8, 9, 10, 21, 22, 23, 24, 27, 54)),
        area("simulation", listOf(5, 19, 29, 38, 39, 47, 51, 52)),
        area("components", listOf(31, 32, 33, 40, 41, 42, 43, 44, 45, 46, 60, 61, 64)),
        area("documents", listOf(7, 11, 20, 25, 26, 34, 50, 53, 56)),
        area("companion", listOf(18, 37, 49, 57, 58, 59, 65)),
        area("validation", listOf(3, 12, 13, 14, 28, 35, 36, 48, 62, 63)),
    )

    fun prepare(example: CircuitExample, registry: ComponentRegistry = ComponentCatalog.registry): CircProjectImportResult =
        CircCodec.readProject(example.xml, registry)

    fun verify(example: CircuitExample, registry: ComponentRegistry = ComponentCatalog.registry): ExampleResult =
        ExampleRunner(registry = registry).run(example)

    private fun guide(id: String, stepCount: Int, toolId: String? = null) = HelpGuide(
        id, message("help_guide_${id}_title"), (1..stepCount).map { message("help_guide_${id}_step_$it") }, toolId,
    )

    private fun area(id: String, issues: List<Int>) = HelpSupportArea(
        message("help_support_${id}_title"), message("help_support_${id}_description"), issues,
    )
}
