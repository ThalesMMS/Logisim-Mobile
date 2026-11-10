package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceRepositoryTest {
    private class MemoryStorage : WorkspaceStorage {
        val files = mutableMapOf<String, String>()
        var failOnWrite: String? = null
        override fun read(name: String): String? = files[name]
        override fun writeAtomically(name: String, contents: String) {
            check(name != failOnWrite) { "Gravação interrompida" }
            files[name] = contents
        }
    }

    private fun repository(storage: MemoryStorage) = WorkspaceRepository(storage) {
        it.startsWith("<project>") && it.endsWith("</project>")
    }

    @Test fun reopensUnicodeDocumentAndKeepsStableRevisionForRepeatedSave() {
        val storage = MemoryStorage()
        val repository = repository(storage)
        val xml = "<project>Circuito de instruções \uD83D\uDCA1</project>"
        assertEquals(WorkspaceSaveResult.Success(1), repository.save(xml))
        assertEquals(WorkspaceSaveResult.Success(1), repository.save(xml))
        val reopened = repository(storage).load()
        assertEquals(xml, reopened.xml)
        assertEquals(1L, reopened.revision)
        assertEquals(WorkspaceLoadSource.CURRENT, reopened.source)
        assertTrue(reopened.diagnostics.isEmpty())
    }

    @Test fun interruptedCurrentReplacementLeavesThePreviousDocumentOpenable() {
        val storage = MemoryStorage()
        val repository = repository(storage)
        repository.save("<project>Primeiro</project>")
        storage.failOnWrite = WorkspaceRepository.CURRENT_FILE
        assertIs<WorkspaceSaveResult.Failure>(repository.save("<project>Segundo</project>"))
        val reopened = repository(storage).load()
        assertEquals("<project>Primeiro</project>", reopened.xml)
        assertEquals(1L, reopened.revision)
    }

    @Test fun corruptedCurrentRecoversBackupAndRetainsCorruptedFileBeforeNextSave() {
        val storage = MemoryStorage()
        val repository = repository(storage)
        repository.save("<project>Primeiro</project>")
        repository.save("<project>Segundo</project>")
        val corrupted = storage.files.getValue(WorkspaceRepository.CURRENT_FILE).dropLast(8)
        storage.files[WorkspaceRepository.CURRENT_FILE] = corrupted
        val recovered = repository(storage).load()
        assertEquals("<project>Primeiro</project>", recovered.xml)
        assertEquals(WorkspaceLoadSource.BACKUP, recovered.source)
        assertTrue(recovered.diagnostics.isNotEmpty())
        assertEquals(WorkspaceSaveResult.Success(2), repository.save("<project>Terceiro</project>"))
        assertEquals(corrupted, storage.files[WorkspaceRepository.RECOVERED_FILE])
        assertEquals("<project>Terceiro</project>", repository(storage).load().xml)
    }

    @Test fun refusesToOverwriteAnUnreadableWorkspaceWithoutAValidBackup() {
        val storage = MemoryStorage()
        storage.files[WorkspaceRepository.CURRENT_FILE] = "LOGISIM-WORKSPACE\n2\nfuture document"
        val repository = repository(storage)
        val before = storage.files.toMap()
        val loaded = repository.load()
        assertNull(loaded.xml)
        assertTrue(loaded.diagnostics.isNotEmpty())
        assertIs<WorkspaceSaveResult.Failure>(repository.save("<project>Novo</project>"))
        assertEquals(before, storage.files)
    }

    @Test fun changedBytesWithTheSameLengthAreRejected() {
        val storage = MemoryStorage()
        val repository = repository(storage)
        repository.save("<project>Primeiro</project>")
        repository.save("<project>Segundo</project>")
        storage.files[WorkspaceRepository.CURRENT_FILE] = storage.files
            .getValue(WorkspaceRepository.CURRENT_FILE).replace("Segundo", "Alterou")
        val recovered = repository(storage).load()
        assertEquals("<project>Primeiro</project>", recovered.xml)
        assertEquals(WorkspaceLoadSource.BACKUP, recovered.source)
    }

    @Test fun migratesLegacyDocumentWithoutRemovingTheCallersOriginal() {
        val storage = MemoryStorage()
        val legacy = "<project>Legado</project>"
        val loaded = repository(storage).load(legacy)
        assertEquals(legacy, loaded.xml)
        assertEquals(WorkspaceLoadSource.LEGACY, loaded.source)
        assertNotNull(storage.files[WorkspaceRepository.CURRENT_FILE])
        assertEquals(legacy, repository(storage).load().xml)
    }

    @Test fun invalidDocumentNeverReplacesLastValidRevision() {
        val storage = MemoryStorage()
        val repository = repository(storage)
        repository.save("<project>Válido</project>")
        val before = storage.files.toMap()
        assertIs<WorkspaceSaveResult.Failure>(repository.save("<project>incompleto"))
        assertEquals(before, storage.files)
        assertEquals("<project>Válido</project>", repository.load().xml)
    }

    @Test fun savesAndReopensEveryCircuitUsingTheProjectCodec() {
        val storage = MemoryStorage()
        val xml = """
            <project source="2.7.1" version="1.0">
              <lib name="0" desc="#Wiring"/>
              <main name="main"/>
              <circuit name="main">
                <comp name="child" loc="(100,100)"/>
              </circuit>
              <circuit name="child">
                <comp lib="0" name="Pin" loc="(30,30)">
                  <a name="width" val="8"/>
                  <a name="label" val="Entrada"/>
                </comp>
              </circuit>
            </project>
        """.trimIndent()
        fun openRepository() = WorkspaceRepository(storage) {
            CircCodec.readProject(it) is CircProjectImportResult.Success
        }

        assertIs<CircImportResult.Failure>(CircCodec.read(xml))
        assertEquals(WorkspaceSaveResult.Success(1), openRepository().save(xml))
        val reopened = openRepository().load()
        assertEquals(xml, reopened.xml)
        assertEquals(WorkspaceLoadSource.CURRENT, reopened.source)
        assertTrue(reopened.diagnostics.isEmpty())
        val project = assertIs<CircProjectImportResult.Success>(
            CircCodec.readProject(assertNotNull(reopened.xml)),
        ).project
        assertEquals(listOf("main", "child"), project.circuits.map { it.name })
        assertEquals("main", project.mainCircuitName)
        assertEquals("child", project.mainCircuit.components.single().circuitName)
        assertEquals(8, project.circuits.last().components.single().width)
    }
}
