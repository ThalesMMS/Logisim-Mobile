package dev.logisim.mobile

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.WorkspaceRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController
import kotlin.native.ref.WeakReference

@OptIn(ExperimentalForeignApi::class)
fun MainViewController(): UIViewController = IosDocumentSession("default").makeViewController()

/** A SwiftUI window keeps its own session so incoming URLs reach that window's editor. */
@OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
class IosDocumentSession(private val sessionId: String) {
    private val slot = ControllerSlot()
    private val presenter: () -> UIViewController? = slot.let { controllerSlot ->
        { controllerSlot.reference?.get() }
    }
    private val documents = IosDocumentActions(presenter,
        referenceKey = "workspace.external-document.v1.$sessionId")
    private val assets = IosDocumentAssetActions(presenter)
    private val directoryAssets = IosDirectoryAssetActions(presenter)
    private var controller: UIViewController? = null

    fun makeViewController(): UIViewController = controller ?: createViewController(documents, assets, directoryAssets, sessionId).also {
        controller = it
        slot.reference = WeakReference(it)
    }

    fun openURL(url: NSURL) { documents.openURL(url) }

    private class ControllerSlot { var reference: WeakReference<UIViewController>? = null }
}

@OptIn(ExperimentalForeignApi::class)
private fun createViewController(
    documents: IosDocumentActions,
    assets: DocumentAssetActions,
    directoryAssets: DirectoryAssetActions,
    windowId: String,
): UIViewController {
    val defaults = NSUserDefaults.standardUserDefaults
    val storage = IosCatalogStorage()
    val preferences = IosPreferencesStorage()
    return ComposeUIViewController(configure = {
        onFocusBehavior = OnFocusBehavior.DoNothing
    }) {
        var restored by remember { mutableStateOf<LegacyBootstrap?>(null) }
        LaunchedEffect(storage, documents) {
            val legacy = withContext(Dispatchers.Default) {
                WorkspaceRepository(storage,
                    validate = { CircCodec.readProject(it) is CircProjectImportResult.Success },
                ).load(defaults.stringForKey("workspace.circuit"))
            }
            documents.bindWorkspace(legacy.xml)
            restored = LegacyBootstrap(legacy.xml, documents.currentReference)
        }
        restored?.let { legacy ->
            CatalogWorkspaceHost(
                storage = storage,
                legacyXml = legacy.xml,
                documents = documents,
                windowId = windowId,
                legacyExternalReference = legacy.reference,
                onExternalWorkspaceSaved = documents::workspaceSaved,
                preferencesStorage = preferences,
                assets = assets,
                directoryAssets = directoryAssets,
            )
        } ?: MaterialTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

private data class LegacyBootstrap(val xml: String?, val reference: DocumentReference?)
