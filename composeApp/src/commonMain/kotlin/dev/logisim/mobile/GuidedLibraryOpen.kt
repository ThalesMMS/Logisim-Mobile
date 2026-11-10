package dev.logisim.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class GuidedLibraryOpen(val content: DocumentContent, val identity: Long, val revision: Int)

internal fun needsExternalLibraryFolder(result: CircProjectImportResult): Boolean =
    result is CircProjectImportResult.Failure && result.notices.any {
        it.key == Messages.CodecComponentUnsupported && it.arguments["value2"]?.startsWith("file#") == true
    }

@Composable
internal fun GuidedLibraryOpenDialog(
    request: GuidedLibraryOpen,
    directories: DirectoryAssetActions,
    opener: PortableProjectCopyOpener,
    registry: ComponentRegistry,
    isCurrent: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val strings = rememberLocalizer()
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(isCurrent)
    val dismiss by rememberUpdatedState(onDismiss)
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<LocalizedText?>(null) }

    fun showFolderPicker() {
        if (busy || !current()) return
        busy = true
        notice = null
        directories.open { result ->
            if (!scope.isActive || !current()) {
                (result as? DirectoryAssetOpenResult.Opened)?.grant?.release()
                return@open
            }
            when (result) {
                DirectoryAssetOpenResult.Cancelled -> busy = false
                is DirectoryAssetOpenResult.Failure -> {
                    busy = false
                    notice = result.notice
                }
                is DirectoryAssetOpenResult.Opened -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val prepared = try {
                        withContext(Dispatchers.IO) { preparePortableDirectoryForDocument(result.grant, request.content, registry) }
                    } finally {
                        result.grant.release()
                    }
                    if (!current()) return@launch
                    when (prepared) {
                        is PortableProjectReadResult.Failure -> {
                            busy = false
                            notice = LocalizedText.join(prepared.notices)
                        }
                        is PortableProjectReadResult.Ready -> opener.openCopy(prepared.copy, request.identity) { opened ->
                            when (opened) {
                                is PortableProjectCopyOpenResult.Opened -> dismiss()
                                is PortableProjectCopyOpenResult.Failure -> if (current()) {
                                    busy = false
                                    notice = opened.notice
                                }
                                PortableProjectCopyOpenResult.Cancelled -> if (current()) busy = false
                            }
                        }
                    }
                }
            }
        }
    }

    AppAlertDialog(
        modifier = Modifier.exportEditorTestTags(),
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(strings("portable_guided_title")) },
        text = {
            Column {
                Text(strings(message("portable_guided_description", "name" to request.content.reference.name)))
                notice?.let { Text(strings(it)) }
                if (busy) LinearProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(onClick = ::showFolderPicker, enabled = !busy) { Text(strings("portable_open_directory")) }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = !busy) { Text(strings(Messages.ActionCancel)) }
        },
    )
}
