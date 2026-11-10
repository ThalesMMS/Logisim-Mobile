package dev.logisim.mobile

import dev.logisim.mobile.core.CircMigration
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.localization.LocalizedText

/** Presentation data follows a workspace replacement without changing the imported XML. */
data class EditorImportReport(
    val sourceVersion: String,
    val circuitCount: Int,
    val migrations: List<CircMigration>,
    val origin: LocalizedText? = null,
    val showOnOpen: Boolean = true,
) {
    companion object {
        fun from(result: CircProjectImportResult.Success, origin: LocalizedText? = null): EditorImportReport =
            EditorImportReport(result.project.sourceVersion, result.project.circuits.size, result.migrations.toList(), origin, result.migrations.isNotEmpty())
    }
}
