package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimulationProjectOptionsTest {
    @Test
    fun missingOptionsUseTheMobileDefaults() {
        assertEquals(SimulationProjectOptions(256, false, GateUndefinedPolicy.IGNORE, 4.0, true),
            SimulationProjectOptions.read(project()))
        assertEquals(mapOf("propagationLimit" to "256", "memoryStartupUnknown" to "false",
            "gateUndefined" to "ignore", "tickFrequencyHz" to "4.0", "autoPropagating" to "true"),
            SimulationProjectOptions().asMap())
    }

    @Test
    fun desktopProfilesUseDesktopLimitUnlessTheProjectSetsIt() {
        fun read(source: String, options: String): Int {
            val xml = """<project source="$source" version="1.0">
                <options>$options</options><circuit name="main"/>
            </project>"""
            val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
            return SimulationProjectOptions.read(project).propagationLimit
        }

        for (source in listOf("2.7.1", "5.1.0dev")) {
            assertEquals(1000, read(source, ""), source)
            assertEquals(37, read(source, """<a name="simlimit" val="37"/>"""), source)
        }
        assertEquals(256, read("5.1.0-dev", ""))
    }

    @Test
    fun readsDesktopOptionNamesTextValuesAndCircuitFrequency() {
        val original = project(listOf("""<options><a name="simlimit">1000</a><a name="gateUndefined" val="error"/></options>"""))
            .copy(circuits = listOf(CircuitDocument("main", attributes = mapOf("simulationFrequency" to "8.0"))))
        assertEquals(SimulationProjectOptions(1000, false, GateUndefinedPolicy.ERROR, 8.0, true),
            SimulationProjectOptions.read(original))
        val inherited = original.copy(circuits = listOf(CircuitDocument("main", attributes = mapOf("simulationFrequency" to "-1.0"))))
        assertEquals(4.0, SimulationProjectOptions.read(inherited).tickFrequencyHz)
    }

    @Test
    fun readsMobileFieldsAndExplicitFrequencyOverridesTheDesktopFallback() {
        val options = """<options>
            <a name="propagationLimit" val="700"/>
            <a name="memoryStartupUnknown" val="true"/>
            <a name="gateUndefined" val="ignore"/>
            <a name="tickFrequencyHz" val="12.5"/>
            <a name="autoPropagating" val="false"/>
        </options>"""
        val original = project(listOf(options)).copy(circuits = listOf(
            CircuitDocument("main", attributes = mapOf("simulationFrequency" to "64.0")),
        ))
        assertEquals(SimulationProjectOptions(700, true, GateUndefinedPolicy.IGNORE, 12.5, false),
            SimulationProjectOptions.read(original))
    }

    @Test
    fun rejectsInvalidLimitsFrequenciesPoliciesAndBooleanValues() {
        for (limit in listOf(0, -1)) assertFailsWith<IllegalArgumentException> { SimulationProjectOptions(propagationLimit = limit) }
        for (frequency in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { SimulationProjectOptions(tickFrequencyHz = frequency) }
        }
        for ((name, value) in listOf("simlimit" to "0", "simlimit" to "2147483648", "tickFrequencyHz" to "NaN",
            "tickFrequencyHz" to "Infinity", "gateUndefined" to "random", "autoPropagating" to "yes",
            "memoryStartupUnknown" to "1")) {
            assertFailsWith<IllegalArgumentException> {
                SimulationProjectOptions.read(project(listOf("""<options><a name="$name" val="$value"/></options>""")))
            }
        }
    }

    @Test
    fun applyingPreservesRawUnknownContentAndUpdatesOnlyOwnedAttributeValues() {
        val xml = """<options future="one &amp; two">
  <!-- <a name='simlimit' val='999'/> -->
  before <a name = 'simlimit' note=" val='decoy' " val = '512'><unknown key="&lt;"/></a> after
  <a name="future" val="keep"><nested>text &amp; more</nested></a>
  <futureChild enabled="true"/>
</options>"""
        val original = project(listOf("<futureProject key=\"unchanged\"/>", xml)).copy(
            metadata = mapOf("author" to "Ada", "version" to "1.0"), sourceVersion = "5.1.0",
            libraries = mapOf("7" to "#Wiring"), mainXml = "<main name=\"main\" custom=\"keep\"/>",
        )
        val applied = SimulationProjectOptions(propagationLimit = 900, tickFrequencyHz = 12.5).applyTo(original)
        val changedXml = applied.extraXml[1]
        assertTrue(changedXml.startsWith("<options future=\"one &amp; two\">"))
        assertTrue("<!-- <a name='simlimit' val='999'/> -->" in changedXml)
        assertTrue("before <a name = 'simlimit' note=\" val='decoy' \" val = '900'><unknown key=\"&lt;\"/></a> after" in changedXml)
        assertTrue("<a name=\"future\" val=\"keep\"><nested>text &amp; more</nested></a>" in changedXml)
        assertTrue("<futureChild enabled=\"true\"/>" in changedXml)
        assertEquals(original.extraXml[0], applied.extraXml[0])
        assertEquals(original.metadata, applied.metadata)
        assertEquals(original.sourceVersion, applied.sourceVersion)
        assertEquals(original.libraries, applied.libraries)
        assertEquals(original.mainXml, applied.mainXml)
        assertEquals("12.5", applied.mainCircuit.attributes["simulationFrequency"])
        assertEquals(SimulationProjectOptions(propagationLimit = 900, tickFrequencyHz = 12.5), SimulationProjectOptions.read(applied))
    }

    @Test
    fun appliesAllOptionsToSelfClosingRootAndPreservesOtherCircuitAttributes() {
        val child = CircuitDocument("child", attributes = mapOf("simulationFrequency" to "32.0", "custom" to "child"))
        val original = project(listOf("<options future='true'/>")).copy(circuits = listOf(
            CircuitDocument("main", attributes = mapOf("custom" to "main")), child,
        ))
        val options = SimulationProjectOptions(120, true, GateUndefinedPolicy.ERROR, 2.0, false)
        val applied = options.applyTo(original)
        assertTrue(applied.extraXml.single().startsWith("<options future='true'>"))
        assertEquals(options, SimulationProjectOptions.read(applied))
        assertEquals(mapOf("custom" to "main", "simulationFrequency" to "2.0"), applied.mainCircuit.attributes)
        assertEquals(child, applied.circuit("child"))
        assertEquals(applied, options.applyTo(applied))
    }

    @Test
    fun repeatedKnownOptionsAcrossBlocksAreUpdatedWithoutDiscardingUnknownBlocks() {
        val original = project(listOf(
            "<options one='1'><a name='simlimit' val='10'/><first/></options>",
            "<options two='2'><a name='propagationLimit' val='20'/><second/></options>",
        ))
        val options = SimulationProjectOptions(propagationLimit = 30)
        val applied = options.applyTo(original)
        assertTrue("<options one='1'>" in applied.extraXml[0])
        assertTrue("<a name='simlimit' val='30'/>" in applied.extraXml[0])
        assertTrue("<first/>" in applied.extraXml[0])
        assertEquals("<options two='2'><a name='propagationLimit' val='30'/><second/></options>", applied.extraXml[1])
        assertEquals(options, SimulationProjectOptions.read(applied))
    }

    @Test
    fun exportedProjectRoundTripsOptionsAndUnrelatedMetadata() {
        val original = project(listOf("<options future=\"keep\"><a name=\"simrand\" val=\"0\"/><futureChild/></options>"))
            .copy(metadata = mapOf("author" to "Ada", "version" to "1.0"))
        for (policy in GateUndefinedPolicy.entries) {
            val options = SimulationProjectOptions(1500, true, policy, 16.0, false)
            val applied = options.applyTo(original)
            val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(applied))).project
            assertEquals(options, SimulationProjectOptions.read(imported))
            assertEquals(original.metadata, imported.metadata)
            assertTrue("<futureChild/>" in imported.extraXml.single())
            assertTrue("<a name=\"simrand\" val=\"0\"/>" in imported.extraXml.single())
        }
    }

    @Test
    fun preservedDesktopFrequencyMetadataDoesNotCreateDuplicateAttributes() {
        val xml = """<project source="3.8.0" version="1.0"><circuit name="main">
            <a name="simulationFrequency" val="8.0" future="keep"><futureChild/></a>
            <a name="custom" val="untouched"/>
        </circuit></project>"""
        val original = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        assertEquals(8.0, SimulationProjectOptions.read(original).tickFrequencyHz)
        val options = SimulationProjectOptions(tickFrequencyHz = 12.5)
        val applied = options.applyTo(original)
        assertTrue("simulationFrequency" !in applied.mainCircuit.attributes)
        assertEquals("untouched", applied.mainCircuit.attributes["custom"])
        assertEquals("""<a name="simulationFrequency" val="12.5" future="keep"><futureChild/></a>""",
            applied.mainCircuit.extraXml.single())
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(applied))).project
        assertEquals(options, SimulationProjectOptions.read(imported))
        assertEquals(applied.mainCircuit.extraXml, imported.mainCircuit.extraXml)
    }

    @Test
    fun applyingOtherOptionsKeepsTheDesktopFrequencyAbsent() {
        val original = project()
        val limited = SimulationProjectOptions.read(original).copy(propagationLimit = 500).applyTo(original)
        assertEquals(null, limited.mainCircuit.projectAttribute("simulationFrequency"))
        assertEquals(500, SimulationProjectOptions.read(limited).propagationLimit)
        val faster = SimulationProjectOptions.read(limited).copy(tickFrequencyHz = 8.0).applyTo(limited)
        assertEquals("8.0", faster.mainCircuit.projectAttribute("simulationFrequency"))
        val stored = SimulationProjectOptions.read(faster).copy(propagationLimit = 600).applyTo(faster)
        assertEquals("8.0", stored.mainCircuit.projectAttribute("simulationFrequency"))
    }

    private fun project(extraXml: List<String> = emptyList()) =
        CircuitProject(listOf(CircuitDocument("main")), extraXml = extraXml)
}
