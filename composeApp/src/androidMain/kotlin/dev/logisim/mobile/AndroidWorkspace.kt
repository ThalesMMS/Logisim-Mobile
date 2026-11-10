package dev.logisim.mobile

import android.content.Context
import android.util.AtomicFile
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.WorkspaceRepository
import dev.logisim.mobile.core.WorkspaceLoadResult
import dev.logisim.mobile.core.WorkspaceStorage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.systemAppLanguage
import dev.logisim.mobile.localization.AppLocaleProvider
import dev.logisim.mobile.localization.localizedString
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AndroidWorkspaceApp(
    context: Context,
    documents: DocumentActions? = null,
    assets: DocumentAssetActions? = null,
    directoryAssets: DirectoryAssetActions? = null,
    windowId: String = "main",
    recoveredAssetResult: AssetActionResult? = null,
    dismissRecoveredAssetResult: () -> Unit = {},
) {
    val application = context.applicationContext
    val directory = remember(application) { File(application.filesDir, "workspace") }
    val storage = remember(directory) { AndroidCatalogStorage(directory) }
    val preferences = remember(application) { AndroidPreferencesStorage(application) }
    val locale = runCatching { EditorPreferencesCodec.read(preferences.load()).preferences.locale }
        .getOrDefault(EditorLocale.SYSTEM)
    val noticeLanguage = locale.language ?: systemAppLanguage()
    val pending by produceState<WorkspaceLoadResult?>(null, storage) {
        value = withContext(Dispatchers.IO) {
            try {
                storage.withExclusiveAccess {
                    val repository = WorkspaceRepository(AndroidLegacyWorkspaceStorage(directory)) {
                        CircCodec.readProject(it) is CircProjectImportResult.Success
                    }
                    repository.load(application.getSharedPreferences("workspace", Context.MODE_PRIVATE)
                        .getString("circuit", null))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                WorkspaceLoadResult(null, notices = listOf(message("android_workspace_open_failed",
                    "reason" to error.localizedDescription(message("error_storage_generic")))))
            }
        }
    }
    val initial = pending
    if (initial == null) {
        AppLocaleProvider(noticeLanguage) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(localizedString("android_workspace_loading"), Modifier.padding(16.dp))
                }
            }
        }
        return
    }
    var legacyNotices by remember(initial) { mutableStateOf(initial.notices) }
    AppLocaleProvider(noticeLanguage) {
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            CatalogWorkspaceHost(storage = storage, legacyXml = initial.xml, documents = documents,
                assets = assets, directoryAssets = directoryAssets, windowId = windowId,
                language = systemAppLanguage(), preferencesStorage = preferences)
            val notices = legacyNotices + listOfNotNull((recoveredAssetResult as? AssetActionResult.Failure)?.notice)
            if (notices.isNotEmpty()) {
                val dismiss = { legacyNotices = emptyList(); dismissRecoveredAssetResult() }
                AppAlertDialog(onDismissRequest = dismiss,
                    title = { Text(localizedString(Messages.ProjectFilesTitle)) },
                    text = { Text(notices.joinToString("\n") { it.resolve(noticeLanguage) }) },
                    confirmButton = { TextButton(onClick = dismiss) { Text(localizedString(Messages.ActionOk)) } })
            }
        }
    }
}

private class AndroidLegacyWorkspaceStorage(private val directory: File) : WorkspaceStorage {
    override fun read(name: String): String? = try {
        AtomicFile(File(directory, name)).readFully().toString(Charsets.UTF_8)
    } catch (_: FileNotFoundException) {
        null
    }

    override fun writeAtomically(name: String, contents: String) {
        checkLocalized(directory.isDirectory || directory.mkdirs()) { message(Messages.ErrorStorageDirectory) }
        val file = AtomicFile(File(directory, name))
        val stream = file.startWrite()
        try {
            stream.write(contents.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
