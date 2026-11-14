package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.ProjectCircuitMerge
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.WorkspaceProjectInfo
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.localizedLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Edited times, tab labels, the merge plan and the import report as the user reads them. */
class WorkspaceFilesPresentationTest {
    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    @Test fun relativeTimesReadNaturallyInEnglishAndPortuguese() {
        fun en(ago: Long) = relativeTimeText(now, now - ago).resolve(AppLanguage.ENGLISH)
        fun pt(ago: Long) = relativeTimeText(now, now - ago).resolve(AppLanguage.PORTUGUESE)
        assertEquals("just now", en(10_000)); assertEquals("agora mesmo", pt(10_000))
        assertEquals("1 minute ago", en(minute)); assertEquals("há 1 minuto", pt(minute))
        assertEquals("5 minutes ago", en(5 * minute)); assertEquals("há 5 minutos", pt(5 * minute))
        assertEquals("3 hours ago", en(3 * 60 * minute)); assertEquals("há 3 horas", pt(3 * 60 * minute))
        assertEquals("3 days ago", en(3 * day)); assertEquals("há 3 dias", pt(3 * day))
        assertEquals("2 months ago", en(65 * day)); assertEquals("há 2 meses", pt(65 * day))
        assertEquals("1 year ago", en(400 * day)); assertEquals("há 1 ano", pt(400 * day))
        assertEquals("just now", en(-5 * minute), "Another device's clock ahead of this one")
    }

    private fun project(id: String, name: String, created: Long = now, updated: Long = now) =
        WorkspaceProjectInfo(WorkspaceProjectId(id), name, 7, created, updated)

    @Test fun rowsSayWhenAProjectWasEditedAndShowTheIdOnlyForSharedNames() {
        val row = project("3fa9c0d1ffff", "Adder", updated = now - 3 * day)
        assertEquals("Edited 3 days ago", projectEditedText(row, now, sharesName = false).resolve(AppLanguage.ENGLISH))
        assertEquals("Editado há 3 dias · 3fa9c0d1", projectEditedText(row, now, sharesName = true).resolve(AppLanguage.PORTUGUESE))
        assertFalse(projectEditedText(row, now, sharesName = false).resolve(AppLanguage.ENGLISH).contains("7"), "No revision counter")
    }

    @Test fun tabsWithTheSameNameAreToldApart() {
        val projects = listOf(project("a", "main", created = now - 3 * day), project("b", "main", created = now - 5 * minute),
            project("c", "Adder"))
        val labels = workspaceTabLabels(projects, emptyMap(), now).mapValues { it.value.resolve(AppLanguage.ENGLISH) }
        assertEquals("Adder", labels.getValue(WorkspaceProjectId("c")))
        assertEquals("main · created 3 days ago", labels.getValue(WorkspaceProjectId("a")))
        assertEquals("main · created 5 minutes ago", labels.getValue(WorkspaceProjectId("b")))
        val byFile = workspaceTabLabels(projects, mapOf(WorkspaceProjectId("a") to "main.circ", WorkspaceProjectId("b") to "lab-2.circ"), now)
        assertEquals("main · lab-2.circ", byFile.getValue(WorkspaceProjectId("b")).resolve(AppLanguage.ENGLISH))
        val twins = workspaceTabLabels(listOf(project("aaaaaa1", "main"), project("bbbbbb2", "main")), emptyMap(), now)
            .values.map { it.resolve(AppLanguage.ENGLISH) }
        assertNotEquals(twins[0], twins[1], "Same name and age fall back to the ID")
    }

    private val destination = """<project source="3.8.0"><main name="main"/><circuit name="main"/></project>"""
    private val library = """
        <project source="3.8.0"><main name="main"/>
          <circuit name="main"><comp name="helper" loc="(50,50)"/></circuit>
          <circuit name="helper"/>
          <circuit name="extra"/>
        </project>
    """.trimIndent()

    @Test fun theMergePlanListsDependenciesAndRenamesWithoutChangingTheMerge() {
        val target = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(destination)).project
        val source = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(library)).project
        val plan = ProjectCircuitMerge.plan(target, source, setOf("main"))
        assertEquals(listOf("helper", "main"), plan.included)
        assertEquals(setOf("helper"), plan.dependencies)
        assertEquals(mapOf("main" to "main_2"), plan.renames)
        assertEquals(listOf("helper"), ProjectCircuitMerge.uses(source, "main"))
        assertEquals(ProjectCircuitMerge.copySelected(target, source, setOf("main")), ProjectCircuitMerge.apply(target, source, plan))
        assertEquals(listOf("main", "helper", "main_2"),
            ProjectCircuitMerge.apply(target, source, plan).circuits.map { it.name })
    }

    @Test fun theMergeSummaryAgreesWithItsCount() {
        assertEquals("1 circuit will be copied (selected: 1, dependencies: 0)", projectMergeSummary(1, 0).resolve(AppLanguage.ENGLISH))
        assertEquals("1 circuito será copiado (selecionados: 1, dependências: 0)", projectMergeSummary(1, 0).resolve(AppLanguage.PORTUGUESE))
        assertEquals("3 circuits will be copied (selected: 1, dependencies: 2)", projectMergeSummary(3, 2).resolve(AppLanguage.ENGLISH))
        assertEquals("3 circuitos serão copiados (selecionados: 1, dependências: 2)", projectMergeSummary(3, 2).resolve(AppLanguage.PORTUGUESE))
    }

    private val legacy = """<project source="2.7.1" version="1.0"><lib desc="#Wiring" name="0"/><lib desc="#Gates" name="1"/>
        <main name="main"/><circuit name="main">
        <comp lib="1" loc="(200,120)" name="AND Gate"><a name="label" val="G1"/></comp>
        <comp lib="1" loc="(300,120)" name="NOT Gate"/>
        </circuit></project>"""

    @Test fun theImportReportNamesComponentsAndAttributesInTheAppLanguage() {
        val result = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(legacy))
        val report = EditorImportReport.from(result)
        val groups = importReportGroups(report, result.project)
        val and = groups.single { group -> group.components.any { "G1" in it.resolve(AppLanguage.ENGLISH) } }
        val component = and.components.single().resolve(AppLanguage.ENGLISH)
        val id = result.project.mainCircuit.components.single { it.label == "G1" }.id
        assertTrue(component.startsWith("G1 (") && component.endsWith("at (200, 120)"), component)
        assertFalse(id in component, "No component ID")
        assertEquals(listOf(id), and.componentIds)
        val notice = and.notice.resolve(AppLanguage.ENGLISH)
        val raw = result.migrations.first { it.componentId == id }.notice.arguments["value2"].orEmpty()
        assertTrue(raw.isNotEmpty())
        assertFalse(notice.contains(raw), "Attribute XML names are shown by label: $notice")
        // The heading names the component by its kind, as the line below it does, not by the codec's XML name.
        val kind = result.project.mainCircuit.components.single { it.id == id }.kind
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.PORTUGUESE)) {
            val heading = and.notice.resolve(language)
            assertFalse("AND Gate" in heading, heading)
            assertTrue(heading.startsWith(kind.localizedLabel().resolve(language)), heading)
        }
        for (rule in result.migrations.map { it.rule }.toSet() + setOf("version-defaults", "floating-point-library",
            "evolution-label-renamed", "evolution-circuit-renamed", "legacy-project-converted", "simulation-noise-preserved")) {
            val title = importReportRuleTitle(rule)
            assertNotEquals(title.key, title.resolve(AppLanguage.ENGLISH), "$rule has a localized title")
            assertNotEquals("import_report_rule_other", title.key, "$rule is a known rule")
        }
    }

    @Test fun aGroupNamesAtMostFiveComponents() {
        val gates = (0 until 7).joinToString("") { """<comp lib="1" loc="(${100 + it * 60},100)" name="NOT Gate"/>""" }
        val xml = """<project source="2.7.1" version="1.0"><lib desc="#Gates" name="1"/><main name="main"/><circuit name="main">$gates</circuit></project>"""
        val result = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml))
        val group = importReportGroups(EditorImportReport.from(result), result.project).single { it.componentIds.size == 7 }
        assertEquals(IMPORT_REPORT_COMPONENT_LIMIT, group.components.size)
        assertEquals(2, group.more)
    }
}
