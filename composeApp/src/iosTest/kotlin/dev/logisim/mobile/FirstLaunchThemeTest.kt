package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.WorkspaceCatalogRepository
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.WorkspaceMutationResult
import dev.logisim.mobile.core.localization.AppLanguage
import platform.Foundation.NSRecursiveLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class FirstLaunchThemeTest {
    @Test
    fun firstLaunchOffersAllThemesAndRemembersTheConfirmedChoice() = runComposeUiTest {
        val preferences = MemoryPreferences()
        var workspace by mutableStateOf(MemoryWorkspace())
        setContent {
            key(workspace) {
                CatalogWorkspaceHost(storage = workspace, language = AppLanguage.ENGLISH, preferencesStorage = preferences)
            }
        }

        waitForTag("first-launch-theme")
        onNodeWithTag("first-launch-theme-light").assertIsDisplayed()
        onNodeWithTag("first-launch-theme-dark").assertIsDisplayed()
        onNodeWithTag("first-launch-theme-system").assertIsDisplayed().assertIsSelected()
        onNodeWithTag("editor-canvas").assertDoesNotExist()

        onNodeWithTag("first-launch-theme-dark").performClick().assertIsSelected()
        runOnIdle { assertNull(preferences.load(), "Previewing a theme does not confirm it") }
        onNodeWithTag("first-launch-theme-continue").performClick()

        waitForTag("editor-canvas")
        onNodeWithTag("first-launch-theme").assertDoesNotExist()
        runOnIdle {
            assertEquals(EditorThemeMode.DARK, EditorPreferencesRepository(preferences).load().preferences.themeMode)
            // Remount with the same preferences and an empty catalog to isolate the saved choice.
            workspace = MemoryWorkspace()
        }
        waitForTag("editor-canvas")
        onNodeWithTag("first-launch-theme").assertDoesNotExist()
    }

    @Test
    fun anExistingWorkspaceWithoutPreferencesOpensTheEditorDirectly() = runComposeUiTest {
        val preferences = MemoryPreferences()
        val workspace = MemoryWorkspace()
        val repository = WorkspaceCatalogRepository(workspace,
            validate = { CircCodec.readProject(it) is CircProjectImportResult.Success })
        assertIs<WorkspaceMutationResult.Success>(repository.create("Existing project",
            """<project source="3.8.0" version="1.0"><circuit name="main"/></project>"""))

        setContent {
            CatalogWorkspaceHost(storage = workspace, language = AppLanguage.ENGLISH, preferencesStorage = preferences)
        }

        waitForTag("editor-canvas")
        onNodeWithTag("first-launch-theme").assertDoesNotExist()
        runOnIdle { assertNull(preferences.load()) }
    }

    private fun ComposeUiTest.waitForTag(tag: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private class MemoryPreferences : PreferencesStorage {
        private var serialized: String? = null

        override fun load(): String? = serialized
        override fun save(serialized: String) {
            this.serialized = serialized
        }
    }

    private class MemoryWorkspace : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        private val lock = NSRecursiveLock()

        override fun <T> withExclusiveAccess(action: () -> T): T {
            lock.lock()
            return try {
                action()
            } finally {
                lock.unlock()
            }
        }

        override fun listNames(): List<String> = withExclusiveAccess { files.keys.toList() }
        override fun read(name: String): String? = withExclusiveAccess { files[name] }
        override fun writeAtomically(name: String, contents: String) = withExclusiveAccess {
            files[name] = contents
        }
    }
}
