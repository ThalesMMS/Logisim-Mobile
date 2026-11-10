package dev.logisim.mobile.core.localization

import dev.logisim.mobile.core.WorkspaceCatalogRepository
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.WorkspaceMutationResult
import dev.logisim.mobile.core.WorkspaceRepository
import dev.logisim.mobile.core.WorkspaceSaveResult
import dev.logisim.mobile.core.WorkspaceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceLocalizationTest {
    private class Storage : WorkspaceCatalogStorage {
        val files = mutableMapOf<String, String>()
        override fun read(name: String): String? = files[name]
        override fun writeAtomically(name: String, contents: String) { files[name] = contents }
        override fun listNames(): List<String> = files.keys.toList()
        override fun <T> withExclusiveAccess(action: () -> T): T = action()
    }

    @Test
    fun invalidProjectNameKeepsItsMessageIdentityAndCanChangeLanguage() {
        val repository = WorkspaceCatalogRepository(Storage(), { true }, { 0 }, { "project" })
        val failure = assertIs<WorkspaceMutationResult.Failure>(repository.create("", "<project/>"))
        assertEquals(Messages.WorkspaceErrorAccess, failure.notice.key)
        assertEquals(Messages.WorkspaceErrorName, failure.notice.localizedArguments.getValue("reason").key)
        assertTrue(failure.notice.resolve(AppLanguage.ENGLISH).contains("The name must contain 1 to 200 characters"))
        assertTrue(failure.notice.resolve(AppLanguage.PORTUGUESE).contains("O nome deve ter de 1 a 200 caracteres"))
    }

    @Test
    fun checkpointValidationExposesALocalizedNoticeWithoutLosingLegacyText() {
        val result = WorkspaceRepository(Storage()) { false }.save("invalid")
        val failure = assertIs<WorkspaceSaveResult.Failure>(result)
        assertEquals(Messages.ErrorStorageInvalid, failure.notice.key)
        assertEquals("O projeto não foi salvo porque o documento é inválido. A revisão anterior foi preservada.", failure.message)
        assertEquals("The project was not saved because the document is invalid. The previous revision was preserved.",
            failure.notice.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun conflictingWindowsKeepALocalizableMessageInSessionState() {
        val storage = Storage()
        val repository = WorkspaceCatalogRepository(storage, { true }, { 0 }, { "project" })
        val document = assertIs<WorkspaceMutationResult.Success>(repository.create("CPU", "<project/>"))
        val first = WorkspaceSession(repository, {})
        val second = WorkspaceSession(repository, {})
        assertTrue(first.open(document.document.info.id))
        assertTrue(second.open(document.document.info.id))
        first.edit("<project>first</project>")
        second.edit("<project>second</project>")
        assertIs<WorkspaceMutationResult.Success>(first.flush())
        assertIs<WorkspaceMutationResult.Conflict>(second.flush())
        val notice = second.state.notices.single()
        assertEquals(Messages.WorkspaceConflictSaved, notice.key)
        assertEquals("Another window saved a revision. Reload or save your edits as a copy.", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Outra janela salvou uma revisão. Recarregue ou salve sua edição como cópia.", notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun resolvingRecoveryNoticesDoesNotModifyStoredDocuments() {
        val storage = Storage()
        val repository = WorkspaceCatalogRepository(storage, { true }, { 0 }, { "project" })
        assertIs<WorkspaceMutationResult.Success>(repository.create("CPU → RAM", "<project>unchanged</project>"))
        storage.files[WorkspaceCatalogRepository.CATALOG_FILE] = "broken"
        val recovered = repository.list()
        val before = storage.files.toMap()
        val notice = recovered.notices.single()
        assertEquals(Messages.WorkspaceCatalogRecovered, notice.key)
        assertTrue(notice.resolve(AppLanguage.ENGLISH).startsWith("The previous catalog"))
        assertTrue(notice.resolve(AppLanguage.PORTUGUESE).startsWith("O catálogo anterior"))
        assertEquals(before, storage.files)
    }
}
