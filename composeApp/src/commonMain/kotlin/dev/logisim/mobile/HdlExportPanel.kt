package dev.logisim.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.HdlDiagnosticSeverity
import dev.logisim.mobile.core.FpgaGenerationOptions
import dev.logisim.mobile.core.HdlExporter
import dev.logisim.mobile.core.HdlLanguage
import dev.logisim.mobile.core.HdlSourceFile
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.localizedString
import dev.logisim.mobile.localization.rememberLocalizer

fun hdlExportEditorTool(
    registry: ComponentRegistry = ComponentCatalog.registry,
    onSaveSource: ((HdlSourceFile) -> Unit)? = null,
    onShareSource: ((HdlSourceFile) -> Unit)? = null,
    assets: DocumentAssetActions? = null,
    onNotice: ((LocalizedText) -> Unit)? = null,
    onFailure: ((LocalizedText) -> Unit)? = null,
): EditorTool = EditorTool("hdl-export", "HDL", requiresProjectGraph = true) { context ->
    val notice = onNotice ?: context.showNotice
    val failure = onFailure ?: context.showNotice
    HdlExportPanel(context, registry,
        onSaveSource ?: assets?.let { actions -> { source -> saveHdlSource(actions, source, notice, failure, context.projectExports) } },
        onShareSource ?: assets?.let { actions -> { source -> shareHdlSource(actions, source, notice, failure, context.projectExports) } })
}

@Composable
fun HdlExportPanel(
    context: EditorToolContext,
    registry: ComponentRegistry = ComponentCatalog.registry,
    onSaveSource: ((HdlSourceFile) -> Unit)? = null,
    onShareSource: ((HdlSourceFile) -> Unit)? = null,
) {
    val strings = rememberLocalizer()
    if (!context.projectExports.access.graphAvailable) {
        Text(strings("project_graph_unavailable"))
        return
    }
    // The language is Evolution's HdlType preference; choosing one here changes it, and None generates nothing.
    val access = LocalEditorPreferences.current
    var localHdlType by remember { mutableStateOf(EditorHdlType.VHDL) }
    val hdlType = access?.preferences?.hdlType ?: localHdlType
    fun choose(type: EditorHdlType) = access?.let { it.onChange(it.preferences.copy(hdlType = type)) } ?: run { localHdlType = type }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<ToolNotice?>(null) }
    val options = access?.preferences?.fpga ?: FpgaGenerationOptions()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Display names (VHDL, Verilog, None) with radio semantics; the choice still writes the shared hdlType preference.
        ChoiceRow(strings("preferences_fpga_hdl_type"), EditorHdlType.entries, hdlType,
            optionLabel = { option -> if (option == EditorHdlType.NONE) strings("preferences_fpga_hdl_none") else option.key },
            optionTag = { "hdl-export-language-${it.key.lowercase()}" }) { choose(it); copied = null }
        val language = hdlType.language
        if (language == null) {
            Text(strings("hdl_select_language"))
        } else {
            val result = remember(context.project, language, registry, options) { HdlExporter.export(context.project, language, registry, options) }
            val file = result.files.firstOrNull { it.path == selectedPath } ?: result.files.firstOrNull()
            Text(strings("hdl_binary_domain"))
            Text(strings("hdl_top_module", "name" to result.topLevelName), fontFamily = FontFamily.Monospace)
            result.diagnostics.forEach { diagnostic ->
                TextButton(onClick = {
                    context.project.circuits.firstOrNull { it.id == diagnostic.circuitId }?.let { context.selectCircuit(it.name) }
                    diagnostic.componentId?.let(context.selectComponent)
                }) {
                    Text("${diagnostic.code}: ${localizedString(diagnostic.notice)}",
                        color = if (diagnostic.severity == HdlDiagnosticSeverity.ERROR) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface)
                }
            }
            if (result.successful) {
                Text(strings("hdl_save_dependencies"))
                ChoiceDropdown(strings("hdl_export_file"), result.files, file, optionLabel = { it.path },
                    modifier = Modifier.fillMaxWidth(), tag = "hdl-export-file") { selectedPath = it.path; copied = null }
                if (file != null) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = {
                            context.exportProjectGraph {
                                clipboard.setText(AnnotatedString(file.content))
                                context.showNotice(message("hdl_source_copied"))
                                copied = message("hdl_source_copied").asResult()
                            }
                        }) { Text(strings("hdl_copy")) }
                        onSaveSource?.let { save -> TextButton(onClick = { context.exportProjectGraph { save(file) } }) { Text(strings("hdl_save_file")) } }
                        onShareSource?.let { share -> TextButton(onClick = { context.exportProjectGraph { share(file) } }) { Text(strings("hdl_share_file")) } }
                    }
                    ToolNoticeText(copied, Modifier.testTag("hdl-export-notice"))
                    SelectionContainer {
                        Text(file.content, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                                .horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()))
                    }
                }
            } else Text(strings("hdl_fix_errors"))
        }
    }
}
