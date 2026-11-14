package dev.logisim.mobile

import dev.logisim.mobile.core.CircMigration
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedAttributeName
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message

/**
 * Presentation data follows a workspace replacement without changing the imported XML. [showOnOpen] opens the report
 * by itself; [announceOnOpen] instead says in the status line how many adjustments there were, with Details (C145).
 */
data class EditorImportReport(
    val sourceVersion: String,
    val circuitCount: Int,
    val migrations: List<CircMigration>,
    val origin: LocalizedText? = null,
    val showOnOpen: Boolean = true,
    val announceOnOpen: Boolean = false,
) {
    /** Every adjustment only records how the file was read: the circuit behaves as it did where it was saved. */
    val informational: Boolean get() = migrations.isNotEmpty() && migrations.all { it.rule in InformationalImportRules }

    /** The report once presented: neither the dialog nor the status line comes back by itself. */
    fun consumed(): EditorImportReport = copy(showOnOpen = false, announceOnOpen = false)

    companion object {
        fun from(result: CircProjectImportResult.Success, origin: LocalizedText? = null): EditorImportReport {
            val report = EditorImportReport(result.project.sourceVersion, result.project.circuits.size, result.migrations.toList(), origin)
            return report.copy(showOnOpen = report.migrations.isNotEmpty() && !report.informational, announceOnOpen = report.informational)
        }
    }
}

/**
 * Rules that keep what the file meant: the defaults of the version that saved it, and the random delay it asked for.
 * Relocated libraries, converted or renamed content and any rule this build does not know change what the user
 * sees or may need action, so they still open the report.
 */
internal val InformationalImportRules = setOf("version-defaults", "simulation-noise-preserved")

internal const val ImportReportStatusKey = "import_report_status"
internal const val ImportReportFileStatusKey = "import_report_status_file"
internal const val ImportReportImportedStatusKey = "import_report_status_imported"
internal val ImportReportStatusKeys = setOf(ImportReportStatusKey, ImportReportFileStatusKey, ImportReportImportedStatusKey)

/**
 * "Opened with N compatibility adjustments", "Opened NAME with N ..." for a file, or "Project imported with N ..." for
 * text [imported] from the Project XML dialog, which is not a file.
 */
internal fun importReportStatus(report: EditorImportReport, name: LocalizedText? = null, imported: Boolean = false): LocalizedText {
    val count = report.migrations.size.toLong()
    return when {
        imported -> LocalizedText(ImportReportImportedStatusKey, quantity = count)
        name == null -> LocalizedText(ImportReportStatusKey, quantity = count)
        else -> LocalizedText(ImportReportFileStatusKey, quantity = count, localizedArguments = mapOf("name" to name))
    }
}

/** A file's name is a literal; the Project XML dialog's origin is a catalog title, which names no file (C145). */
internal val LocalizedText.namesAFile: Boolean get() = key.isEmpty() && fallback != null

/** How many components a report group names before "+N more". */
internal const val IMPORT_REPORT_COMPONENT_LIMIT = 5

/**
 * One group of the import report as the user reads it: what changed (the notice, with attribute names in the app
 * language), in which circuit, and the components it touched by label, kind and position instead of internal IDs.
 * [rule] stays available for tags and tests, never as the visible content.
 */
internal data class ImportReportGroup(
    val rule: String,
    val title: LocalizedText,
    val notice: LocalizedText,
    val circuit: String,
    val components: List<LocalizedText>,
    val more: Int,
    val componentIds: List<String>,
    val repeated: Int,
)

private val reportRules = setOf("version-defaults", "floating-point-library", "evolution-label-renamed",
    "evolution-circuit-renamed", "legacy-project-converted", "simulation-noise-preserved")

/** The localized title of a migration rule; a rule this build does not know reads as a compatibility adjustment. */
internal fun importReportRuleTitle(rule: String): LocalizedText =
    if (rule in reportRules) message("import_report_rule_" + rule.replace('-', '_')) else message("import_report_rule_other")

internal fun importReportGroups(report: EditorImportReport, project: CircuitProject?): List<ImportReportGroup> =
    report.migrations.groupBy { Triple(it.rule, it.circuit, it.notice) }.values.map { group ->
        val first = group.first()
        val ids = group.mapNotNull { it.componentId }.distinct()
        ImportReportGroup(first.rule, importReportRuleTitle(first.rule), importReportNotice(first, project), first.circuit,
            ids.take(IMPORT_REPORT_COMPONENT_LIMIT).map { importReportComponent(project, first.circuit, it) },
            (ids.size - IMPORT_REPORT_COMPONENT_LIMIT).coerceAtLeast(0),
            ids.filter { id -> project?.circuit(first.circuit)?.components?.any { it.id == id } == true }, group.size)
    }

/**
 * The codec names the component ("AND Gate") and the attributes a version default set by their XML names; the report
 * shows the component's kind and the attributes' labels in the app language, as the component lines below it do.
 */
private fun importReportNotice(migration: CircMigration, project: CircuitProject?): LocalizedText {
    val notice = localizedComponentName(migration, project)
    if (notice.key != Messages.CodecMigrationImplicitAttributes) return notice
    val names = notice.arguments["value2"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return notice
    return notice.copy(arguments = notice.arguments - "value2",
        localizedArguments = notice.localizedArguments + ("value2" to LocalizedText.join(names.map(::localizedAttributeName), ", ")))
}

/** The notice with its "name" argument as the component's localized kind, when the component is still in the project. */
private fun localizedComponentName(migration: CircMigration, project: CircuitProject?): LocalizedText {
    val notice = migration.notice
    if ("name" !in notice.arguments) return notice
    val id = migration.componentId ?: return notice
    val component = project?.circuit(migration.circuit)?.components?.firstOrNull { it.id == id } ?: return notice
    if (component.kind == ComponentKind.SUBCIRCUIT) return notice
    return notice.copy(arguments = notice.arguments - "name",
        localizedArguments = notice.localizedArguments + ("name" to component.kind.localizedLabel()))
}

/** "G1 (AND gate) at (200, 120)", "NOT gate at (300, 120)", resolved against the project as it is now. */
internal fun importReportComponent(project: CircuitProject?, circuit: String, id: String): LocalizedText {
    val component = project?.circuit(circuit)?.components?.firstOrNull { it.id == id }
        ?: return message("import_report_component_missing")
    val kind = if (component.kind == ComponentKind.SUBCIRCUIT) component.circuitName?.let(LocalizedText::literal) ?: component.kind.localizedLabel()
        else component.kind.localizedLabel()
    val x = component.position.x
    val y = component.position.y
    return if (component.label.isNotBlank()) message("import_report_component_labeled", "label" to component.label, "kind" to kind, "x" to x, "y" to y)
        else message("import_report_component", "name" to kind, "x" to x, "y" to y)
}
