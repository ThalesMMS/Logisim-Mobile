package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.localization.LocalizedText

/** Recorder closures survive project changes and ordinary editor recomposition. */
@Composable
fun rememberBuiltInEditorTools(
    registry: ComponentRegistry = ComponentCatalog.registry,
    documents: DocumentActions? = null,
    extensions: List<EditorTool> = emptyList(),
    additionalDefaults: List<EditorTool> = emptyList(),
    preferencesTool: EditorTool? = null,
    memoryFiles: MemoryEditorFileActions? = null,
    onNotice: (LocalizedText) -> Unit = {},
    onFailure: (LocalizedText) -> Unit = {},
    assets: DocumentAssetActions? = null,
): List<EditorTool> {
    val notice by rememberUpdatedState(onNotice)
    val failure by rememberUpdatedState(onFailure)
    val currentRegistry by rememberUpdatedState(registry)
    val traceFiles = assets?.traceFiles
    val trace = remember(traceFiles, assets) {
        traceEditorTool(files = traceFiles, onFailure = { failure(it) }, currentRegistry = { currentRegistry }, assets = assets)
    }
    val defaults = remember(registry, documents, memoryFiles, assets, trace) {
        createBuiltInEditorTools(registry, memoryFiles, documents, { notice(it) }, { failure(it) }, assets, trace)
    }
    return remember(defaults, additionalDefaults, preferencesTool, extensions) {
        mergeEditorTools(defaults + additionalDefaults + listOfNotNull(preferencesTool), extensions)
    }
}

internal fun createBuiltInEditorTools(
    registry: ComponentRegistry,
    memoryFiles: MemoryEditorFileActions? = null,
    documents: DocumentActions? = null,
    onNotice: (LocalizedText) -> Unit = {},
    onFailure: (LocalizedText) -> Unit = {},
    assets: DocumentAssetActions? = null,
    trace: EditorTool = traceEditorTool(registry, files = assets?.traceFiles, onFailure = onFailure, assets = assets),
): List<EditorTool> = listOf(
    analysisEditorTool(registry, assets = assets),
    trace,
    testVectorEditorTool(registry),
    memoryEditorTool(registry, memoryFiles),
    circuitAccessibilityEditorTool(registry),
    diagnosticsEditorTool(registry),
    hdlExportEditorTool(registry, assets = assets, onNotice = onNotice, onFailure = onFailure),
)

internal fun mergeEditorTools(defaults: List<EditorTool>, extensions: List<EditorTool>): List<EditorTool> {
    val uniqueExtensions = extensions.distinctBy { it.id }
    val overridden = uniqueExtensions.map { it.id }.toSet()
    return defaults.filterNot { it.id in overridden }.distinctBy { it.id } + uniqueExtensions
}
