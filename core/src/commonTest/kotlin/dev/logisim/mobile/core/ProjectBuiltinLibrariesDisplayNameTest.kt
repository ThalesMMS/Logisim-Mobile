package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedFailure
import dev.logisim.mobile.core.localization.appMessageCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** C138: every built-in library has a localized display name; the descriptor in the .circ is untouched. */
class ProjectBuiltinLibrariesDisplayNameTest {
    /** MobileExtensions' set of recognized built-in libraries, the 14 libraries of Evolution's builtin set. */
    private val builtins = listOf("#Base", "#Gates", "#Wiring", "#Plexers", "#Arithmetic", "#FPArithmetic", "#Memory", "#I/O",
        "#TTL", "#HDL-IP", "#TCL", "#BFH-Praktika", "#Input/Output-Extra", "#Soc")

    @Test
    fun everyBuiltinLibraryMapsToACatalogMessage() {
        val libraries = (ProjectBuiltinLibraries.available(ComponentCatalog.registry) + builtins).distinct()
        assertEquals(builtins.toSet(), libraries.toSet(), "The registry offers exactly the built-in libraries")
        for (library in libraries) {
            val name = ProjectBuiltinLibraries.displayName(library)
            assertTrue(name.key in appMessageCatalog, "$library has no catalog message (${name.key})")
            for (language in listOf(AppLanguage.ENGLISH, AppLanguage.PORTUGUESE)) {
                assertTrue(name.resolve(language).isNotBlank(), "$library in $language")
            }
        }
    }

    @Test
    fun displayNamesFollowEvolutionAndTranslate() {
        fun name(library: String, language: AppLanguage = AppLanguage.ENGLISH) = ProjectBuiltinLibraries.displayName(library).resolve(language)
        assertEquals("Seletores", name("#Plexers", AppLanguage.PORTUGUESE))
        assertNotEquals("Plexers", name("#Plexers", AppLanguage.PORTUGUESE))
        assertEquals("Floating-point arithmetic", name("#FPArithmetic"))
        assertEquals("BFH mega functions", name("#BFH-Praktika"))
        assertEquals("System on a chip", name("#Soc"))
        assertEquals("HDL-IP", name("#HDL-IP"))
        assertEquals("TCL", name("#TCL"))
        // Another library (an extension's or a JAR's) keeps its descriptor as its name.
        assertEquals("GrayCode", name("#GrayCode"))
        assertEquals("jar#lib.jar#Library", name("jar#lib.jar#Library"))
    }

    @Test
    fun theInUseRefusalNamesTheLibraryByItsDisplayName() {
        val registry = ComponentCatalog.registry
        val project = CircuitProject(listOf(CircuitDocument(components = listOf(registry.create(ComponentKind.AND, "and", GridPoint(100, 100))))),
            libraries = mapOf("0" to "#Gates"))
        val failure = assertFailsWith<Exception> { ProjectBuiltinLibraries.remove(project, "#Gates", registry) }
        val text = (failure as LocalizedFailure).text
        assertEquals("active_library_in_use", text.key)
        assertTrue(text.resolve(AppLanguage.PORTUGUESE).contains("Portas"), text.resolve(AppLanguage.PORTUGUESE))
        // The descriptor written to the file stays the library's identity.
        assertEquals(project, ProjectBuiltinLibraries.remove(project, "#Plexers", registry))
    }
}
