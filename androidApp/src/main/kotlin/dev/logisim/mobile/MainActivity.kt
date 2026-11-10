package dev.logisim.mobile

import android.os.Bundle
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var documents: AndroidDocumentActions
    private lateinit var assets: AndroidDocumentAssetActions
    private lateinit var directoryAssets: AndroidDirectoryAssetActions
    private var recoveredAssetResult by mutableStateOf<AssetActionResult?>(null)
    private var externalIntentConsumed = false
    private var documentSession = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        documentSession = savedInstanceState?.getString("document-session") ?: UUID.randomUUID().toString()
        documents = AndroidDocumentActions(this, documentSession)
        assets = AndroidDocumentAssetActions(this, documentSession) { result ->
            if (result is AssetActionResult.Failure) recoveredAssetResult = result
        }
        directoryAssets = AndroidDirectoryAssetActions(this) { result ->
            if (result is DirectoryAssetOpenResult.Failure) {
                recoveredAssetResult = AssetActionResult.Failure(result.notice)
            }
        }
        externalIntentConsumed = savedInstanceState?.getBoolean("external-document-consumed") == true
        if (!externalIntentConsumed) externalIntentConsumed = documents.handleIntent(intent)
        setContent {
            AndroidWorkspaceApp(this, documents = documents, assets = assets, directoryAssets = directoryAssets,
                windowId = documentSession,
                recoveredAssetResult = recoveredAssetResult,
                dismissRecoveredAssetResult = { recoveredAssetResult = null })
        }
    }

    // The system light/dark switch arrives here (configChanges uiMode), like Evolution's SystemThemeWatcher: Compose
    // reads the new configuration, so open sheets and dialogs survive instead of the activity being recreated.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalIntentConsumed = documents.handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("external-document-consumed", externalIntentConsumed)
        outState.putString("document-session", documentSession)
        super.onSaveInstanceState(outState)
    }
}
