package dev.logisim.mobile

import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitExamples
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HelpContentTest {
    @Test
    fun everyHelpExampleOpensAndTheSap1ReferenceReachesEight() {
        assertEquals(CircuitExamples.all.map { it.id }.toSet(), HelpContent.examples.map { it.id }.toSet())
        for (entry in HelpContent.examples) {
            val circuit = entry.circuit ?: error(entry.id)
            assertIs<CircProjectImportResult.Success>(HelpContent.prepare(circuit), entry.id)
        }
        val sap1 = HelpContent.verify(CircuitExamples.find("sap1")!!)
        assertTrue(sap1.failures.isEmpty(), sap1.failures.joinToString())
        assertEquals("00001000", sap1.observations.single { it.action == "program-complete" }.values["Display"])
        assertEquals(56L, sap1.observations.single { it.action == "program-complete" }.ticks)
    }

    @Test
    fun unavailableComponentsPreventOpeningAndReferenceExecutionInTheSameRegistry() {
        val limited = ComponentRegistry(ComponentCatalog.definitions.filterNot { it.fileName == "AND Gate" })
        val example = CircuitExamples.find("and")!!
        assertIs<CircProjectImportResult.Failure>(HelpContent.prepare(example, limited))
        val failure = runCatching { HelpContent.verify(example, limited) }.exceptionOrNull()
        val notice = assertIs<LocalizedArgumentException>(failure).text
        assertTrue(notice.resolve(AppLanguage.ENGLISH).startsWith("Could not load example and"))
        assertTrue(notice.resolve(AppLanguage.PORTUGUESE).startsWith("Não foi possível carregar o exemplo and"))
        for (language in AppLanguage.entries) {
            val text = notice.resolve(language)
            assertTrue(!text.contains("LocalizedText(") && !text.contains("Failure("), text)
        }
    }

    @Test
    fun offlineGuidesExamplesAndSupportAreasTranslateAtPresentationTime() {
        val messages = HelpContent.guides.flatMap { listOf(it.title) + it.steps } +
            HelpContent.examples.flatMap { listOf(it.title, it.description, it.instructions) } +
            HelpContent.support.flatMap { listOf(it.title, it.description) }
        for (text in messages) for (language in AppLanguage.entries) {
            val rendered = text.resolve(language)
            assertTrue(rendered.isNotBlank() && rendered != text.key, "${text.key}: ${language.tag}")
            assertTrue(!rendered.contains(Regex("\\{[a-z][a-zA-Z0-9_]*}")), rendered)
        }
        assertTrue(HelpContent.support.flatMap { it.issues }.containsAll(listOf(20, 29, 35, 36, 53, 65)))
        assertTrue(HelpContent.parityUrl.endsWith("/docs/parity.md"))
    }

    @Test
    fun aSecondOpenCannotOvertakeAnImportInProgress() = runBlocking<Unit> {
        val opener = HelpExampleOpener()
        val first = CircuitExamples.find("and")!!
        val second = CircuitExamples.find("rom")!!
        val project = (HelpContent.prepare(first) as CircProjectImportResult.Success).project
        val loading = CompletableDeferred<Unit>()
        val accepted = mutableListOf<String>()
        val a = async(start = CoroutineStart.UNDISPATCHED) {
            opener.open(first, { project }, prepare = { loading.await(); HelpContent.prepare(it) },
                accept = { accepted += it.id; true })
        }
        assertEquals("and", opener.opening)
        val b = opener.open(second, { project }, prepare = { error("A second import must not start") },
            accept = { accepted += it.id; true })
        assertEquals(HelpExampleOpenResult.Busy, b)
        loading.complete(Unit)
        assertEquals(HelpExampleOpenResult.Requested, a.await())
        assertEquals(listOf("and"), accepted)
        assertEquals(null, opener.opening)
    }

    @Test
    fun projectChangeDuringImportKeepsTheNewProjectAndDoesNotInvokeTheHost() = runBlocking<Unit> {
        val opener = HelpExampleOpener()
        val example = CircuitExamples.find("and")!!
        val original = (HelpContent.prepare(example) as CircProjectImportResult.Success).project
        var project = original
        var accepted = false
        val result = opener.open(example, { project }, prepare = {
            project = original.copy(metadata = mapOf("new-document" to "keep"))
            HelpContent.prepare(it)
        }, accept = { accepted = true; true })
        assertEquals(HelpExampleOpenResult.ProjectChanged, result)
        assertTrue(!accepted)
        assertEquals("keep", project.metadata["new-document"])
        assertEquals(null, opener.opening)
    }

}
