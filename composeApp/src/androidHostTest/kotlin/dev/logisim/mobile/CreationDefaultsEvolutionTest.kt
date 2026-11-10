package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #313: the editor builds the issue's scenario (new project, D flip-flop, Pin, new circuit, save) with each
 * creation-defaults profile. fixtures/creation-defaults holds the saved files; scripts/oracle/creation-defaults/run.sh
 * builds the same scenario in Evolution d58a61e0, opens both files there and records what it loads in
 * evolution-load.tsv. Set LOGISIM_REGENERATE_FIXTURES=1 to rewrite the Mobile files.
 */
class CreationDefaultsEvolutionTest {
    private val directory = listOf(File("fixtures/creation-defaults"), File("../fixtures/creation-defaults")).first(File::isDirectory)

    private fun saved(profile: String): String {
        if (profile == "empty") {
            // #339: Preferences > New projects > Empty template, then a new circuit, as Template.createEmpty() gives Evolution.
            val templates = ProjectTemplateStore(MemoryStorage(), ComponentCatalog.registry)
            templates.select(ProjectTemplateType.EMPTY)
            assertEquals(ProjectTemplateType.EMPTY, templates.type())
            val state = EditorState(null)
            state.newEmptyDocument()
            assertTrue(state.createCircuit("sub"))
            return state.saveXml()
        }
        val (appearance, newIo, fixed, removeUnused) = profile.split("-")
        val state = EditorState(null)
        state.creationDefaults = EditorPreferences(
            defaultAppearance = EditorDefaultAppearance.entries.single { it.value == appearance },
            newInputOutputShapes = newIo.toBooleanStrict(), namedCircuitBoxesFixedSize = fixed.toBooleanStrict(),
            removeUnusedLibraries = removeUnused.toBooleanStrict(),
        ).creationDefaults
        state.newDocument()
        for ((kind, point) in listOf(ComponentKind.D_FLIP_FLOP to GridPoint(300, 200), ComponentKind.INPUT to GridPoint(100, 200))) {
            assertTrue(state.insertComponent(kind, point), "$kind")
        }
        assertTrue(state.createCircuit("sub"))
        return state.saveXml()
    }

    @Test
    fun savedProjectsMatchTheFilesEvolutionLoadedLikeItsOwn() {
        if (System.getenv("LOGISIM_REGENERATE_FIXTURES") == "1") PROFILES.forEach { File(directory, "mobile-$it.circ").writeText(saved(it)) }
        val oracle = File(directory, "evolution-load.tsv").readLines().map { it.split('\t') }
        val profiles = oracle.filter { it[0] == "profile" }.map { it[1] }
        assertEquals(PROFILES, profiles)
        for (profile in profiles) {
            val file = File(directory, "mobile-$profile.circ")
            assertEquals(file.readText(), saved(profile), profile)
            val results = oracle.filter { it[0] == "result" && it[1] == profile }.associate { it[2] to it[3] }
            // #324: the template parts match; #323: Evolution's repairFloatLibrary no longer leaves a second #FPArithmetic.
            assertEquals(mapOf("circuits-and-components-match" to "true", "template-matches" to "true",
                "evolution-resave-unchanged" to "true"), results, profile)
            assertTrue(oracle.none { it[0] == "difference" && it[1] == profile }, profile)
        }
    }

    /** #344: the toolbar the editor shows for each saved project is the one Evolution loads from it; the empty template has none. */
    @Test
    fun displayedToolbarsMatchTheToolbarsEvolutionLoaded() {
        val oracle = File(directory, "evolution-load.tsv").readLines().map { it.split('\t') }
        for (profile in PROFILES) {
            val evolution = oracle.filter { it[0] == "evolution" && it[1] == profile && it[2] == "toolbar" }.map { it[4] }
            val state = EditorState(File(directory, "mobile-$profile.circ").readText())
            val shown = projectToolbarEntries(state.project, state.componentRegistry).map { (item, _) ->
                if (item.isSeparator) "separator" else item.name
            }
            assertEquals(evolution, shown, profile)
            assertEquals(profile == "empty", shown.isEmpty(), profile)
        }
    }

    private class MemoryStorage : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        override fun <T> withExclusiveAccess(action: () -> T): T = action()
        override fun listNames(): List<String> = files.keys.toList()
        override fun read(name: String): String? = files[name]
        override fun writeAtomically(name: String, contents: String) { files[name] = contents }
    }

    private companion object {
        val PROFILES = listOf(
            "classic-false-false-true",
            "evolution-true-true-false",
            "logisim_evolution-true-true-true",
            "empty",
        )
    }
}
