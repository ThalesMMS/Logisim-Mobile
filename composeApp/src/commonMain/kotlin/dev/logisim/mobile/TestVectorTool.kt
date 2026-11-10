package dev.logisim.mobile

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer
import dev.logisim.mobile.core.localization.message
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry

fun testVectorEditorTool(registry: ComponentRegistry = ComponentCatalog.registry): EditorTool = EditorTool("test-vectors", "Test vectors", titleText = message(Messages.VectorTitle), requiresProjectGraph = true, content = { context ->
    val localize = rememberLocalizer()
    var exported by remember { mutableStateOf<String?>(null) }
    TestVectorPanel(context.project, onExport = { exported = it }, registry = registry)
    exported?.let { text ->
        @Suppress("DEPRECATION")
        val clipboard = LocalClipboardManager.current
        AppAlertDialog(onDismissRequest = { exported = null }, title = { Text(localize(Messages.VectorExportTitle)) },
            text = { OutlinedTextField(text, onValueChange = {}, readOnly = true, minLines = 5, maxLines = 12) },
            confirmButton = { TextButton(onClick = { context.exportProjectGraph { clipboard.setText(AnnotatedString(text)); context.showNotice(message(Messages.VectorCopied)) } }) { Text(localize(Messages.TraceCopy)) } },
            dismissButton = { TextButton(onClick = { exported = null }) { Text(localize(Messages.TraceClose)) } })
    }
})
