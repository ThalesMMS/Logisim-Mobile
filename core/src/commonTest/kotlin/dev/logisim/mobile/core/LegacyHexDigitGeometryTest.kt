package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LegacyHexDigitGeometryTest {
    @Test
    fun legacyDecimalPointStaysWiredAfterRoundTrip() {
        val original = importProject(fixture("2.7.1"))
        val exported = CircCodec.writeProject(original)
        for (project in listOf(original, importProject(exported))) {
            val display = project.mainCircuit.components.single { it.kind == IoKinds.HexDigit }
            assertEquals(GridPoint(310, 300), decimalPoint(display))
            assertEquals(IoLampState(0x86), CircuitSimulator().settle(project).componentStates[display.id])
        }
    }

    @Test
    fun modernDisplayRetainsItsPortWhenSavedInALegacyProject() {
        val modern = importProject(fixture("5.1.0-dev"))
        val modernDisplay = modern.mainCircuit.components.single { it.kind == IoKinds.HexDigit }
        assertEquals(GridPoint(320, 300), decimalPoint(modernDisplay))

        val legacy = importProject(fixture("2.7.1"))
        val placed = ComponentCatalog.registry.create(IoKinds.HexDigit, "placed", GridPoint(600, 300))
        val mixed = legacy.copy(circuits = listOf(legacy.mainCircuit.copy(components = legacy.mainCircuit.components + placed)))
        val reloaded = importProject(CircCodec.writeProject(mixed))
        assertEquals(GridPoint(620, 300), decimalPoint(reloaded.mainCircuit.components.single { it.id == "placed" }))
        assertEquals(GridPoint(310, 300), decimalPoint(reloaded.mainCircuit.components.single { it.position == GridPoint(300, 300) }))
    }

    @Test
    fun originalCopyRewiresThePointWithoutMovingTheNativePort() {
        val project = importProject(modernDisplayInOriginalProject())
        val originalCopy = importProject(OriginalCircExport.write(project))
        val reloadedNative = importProject(CircCodec.writeProject(project))
        for ((copy, port) in listOf(originalCopy to GridPoint(310, 300), reloadedNative to GridPoint(320, 300))) {
            val display = copy.mainCircuit.components.single { it.kind == IoKinds.HexDigit }
            assertEquals(port, decimalPoint(display))
            assertEquals(IoLampState(0x86), CircuitSimulator().settle(copy).componentStates[display.id])
        }
    }

    @Test
    fun originalCopyReportsAConflictWhenTheLegacyPointWouldJoinAnUnrelatedTerminal() {
        val project = importProject(modernDisplayInOriginalProject().replace(
            "<comp lib=\"1\"",
            "<comp lib=\"0\" loc=\"(310,300)\" name=\"Constant\"><a name=\"value\" val=\"0x0\"/></comp><comp lib=\"1\"",
        ))
        val failure = assertFailsWith<LocalizedArgumentException> { OriginalCircExport.write(project) }
        assertEquals(Messages.OriginalCircExportHexPointConflict, failure.text.key)
    }

    @Test
    fun originalCopyRejectsAWireThatWouldActivateADisabledPoint() {
        val project = importProject(fixture("2.7.1").replace("name=\"Hex Digit Display\"/>",
            "name=\"Hex Digit Display\"><a name=\"decimalPoint\" val=\"false\"/></comp>"))
        val failure = assertFailsWith<LocalizedArgumentException> { OriginalCircExport.write(project) }
        assertEquals(Messages.OriginalCircExportHexPointConflict, failure.text.key)
    }

    private fun modernDisplayInOriginalProject() = fixture("2.7.1")
        .replace("310,", "320,")
        .replace("name=\"Hex Digit Display\"/>",
            "name=\"Hex Digit Display\"><a name=\"logisim-mobile-original-hex-digit\" val=\"false\"/></comp>")

    private fun decimalPoint(display: CircuitComponent): GridPoint =
        ComponentCatalog.registry.layout(display).ports.single { it.name == "decimalPoint" }.position

    private fun importProject(xml: String): CircuitProject =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project

    private fun fixture(source: String): String = """
        <project source="$source" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <lib desc="#I/O" name="1"/>
          <main name="main"/>
          <circuit name="main">
            <comp lib="0" loc="(100,260)" name="Constant"><a name="width" val="4"/><a name="value" val="0x1"/></comp>
            <comp lib="0" loc="(310,400)" name="Constant"><a name="value" val="0x1"/></comp>
            <comp lib="1" loc="(300,300)" name="Hex Digit Display"/>
            <wire from="(100,260)" to="(300,260)"/>
            <wire from="(300,260)" to="(300,300)"/>
            <wire from="(310,300)" to="(310,400)"/>
          </circuit>
        </project>
    """.trimIndent()
}
