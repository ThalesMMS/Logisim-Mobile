package dev.logisim.mobile.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceCatalogFileTest {
    @Test fun processTerminationBeforeCatalogCommitKeepsConfirmedDocument() = withDirectory { directory ->
        val repository = repository(directory)
        val first = assertIs<WorkspaceMutationResult.Success>(repository.create("Project", xml("confirmed"))).document
        val process = child(directory, first.info.id.value, "crash", "unconfirmed")
        finish(process, directory)
        assertEquals(29, process.exitValue(), File(directory, "child-unconfirmed.log").readText())
        val opened = assertIs<WorkspaceOpenResult.Success>(repository(directory).open(first.info.id)).document
        assertEquals(1L, opened.info.revision)
        assertEquals(xml("confirmed"), opened.xml)
        val next = assertIs<WorkspaceMutationResult.Success>(repository.save(first.info.id, 1, xml("next"))).document
        assertEquals(3L, next.info.revision)
    }

    @Test fun twoProcessesSavingOneRevisionProduceOneWinnerAndOneConflict() = withDirectory { directory ->
        val first = assertIs<WorkspaceMutationResult.Success>(repository(directory).create("Project", xml("initial"))).document
        val a = child(directory, first.info.id.value, "race", "a")
        val b = child(directory, first.info.id.value, "race", "b")
        File(directory, "go").writeText("start")
        finish(a, directory); finish(b, directory)
        assertEquals(0, a.exitValue(), File(directory, "child-a.log").readText())
        assertEquals(0, b.exitValue(), File(directory, "child-b.log").readText())
        val results = listOf(File(directory, "result-a").readText(), File(directory, "result-b").readText()).sorted()
        assertEquals(listOf("Conflict", "Success"), results)
        val opened = assertIs<WorkspaceOpenResult.Success>(repository(directory).open(first.info.id)).document
        assertEquals(2L, opened.info.revision)
        assertTrue(opened.xml == xml("a") || opened.xml == xml("b"))
    }

    @Test fun unicodeNamesAndActualFileCorruptionRecoverAcrossInstances() = withDirectory { directory ->
        val first = assertIs<WorkspaceMutationResult.Success>(repository(directory).create("Instruções 💡", xml("one"))).document
        repository(directory).save(first.info.id, 1, xml("two"))
        File(directory, "project-${first.info.id.value}-2.workspace").writeText("corrupted")
        val opened = assertIs<WorkspaceOpenResult.Success>(repository(directory).open(first.info.id)).document
        assertEquals("Instruções 💡", opened.info.name)
        assertEquals(xml("one"), opened.xml)
        assertEquals(1L, opened.contentRevision)
    }

    private fun child(directory: File, id: String, mode: String, value: String): Process {
        val classpath = listOf(WorkspaceCatalogProbe::class.java, WorkspaceCatalogRepository::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-cp", classpath,
            WorkspaceCatalogProbe::class.java.name, directory.path, id, mode, value)
            .redirectErrorStream(true).redirectOutput(File(directory, "child-$value.log")).start()
    }
    private fun finish(process: Process, directory: File) {
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        assertTrue(finished, directory.listFiles().orEmpty().filter { it.name.startsWith("child-") }
            .joinToString("\n") { "${it.name}: ${it.readText()}" })
    }
    private fun repository(directory: File) = WorkspaceCatalogRepository(FileWorkspaceCatalogStorage(directory),
        validate = { it.startsWith("<project>") && it.endsWith("</project>") }, clock = { 100L })
    private fun xml(value: String) = "<project>$value</project>"
    private fun withDirectory(action: (File) -> Unit) {
        val directory = Files.createTempDirectory("logisim-catalog-test").toFile()
        try { action(directory) } finally { directory.deleteRecursively() }
    }
}

object WorkspaceCatalogProbe {
    @JvmStatic fun main(args: Array<String>) {
        val directory = File(args[0]); val id = WorkspaceProjectId(args[1]); val mode = args[2]; val value = args[3]
        val files = FileWorkspaceCatalogStorage(directory)
        val storage = object : WorkspaceCatalogStorage by files {
            override fun writeAtomically(name: String, contents: String) {
                if (mode == "crash" && name == WorkspaceCatalogRepository.CATALOG_FILE) Runtime.getRuntime().halt(29)
                files.writeAtomically(name, contents)
            }
        }
        if (mode == "race") {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!File(directory, "go").isFile && System.nanoTime() < deadline) Thread.sleep(5)
            check(File(directory, "go").isFile)
        }
        val repository = WorkspaceCatalogRepository(storage,
            validate = { it.startsWith("<project>") && it.endsWith("</project>") }, clock = { 200L })
        val result = repository.save(id, 1, "<project>$value</project>")
        File(directory, "result-$value").writeText(when (result) {
            is WorkspaceMutationResult.Success -> "Success"
            is WorkspaceMutationResult.Conflict -> "Conflict"
            else -> error("Unexpected result $result")
        })
    }
}
