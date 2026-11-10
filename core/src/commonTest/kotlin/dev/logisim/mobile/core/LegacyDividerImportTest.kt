package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LegacyDividerImportTest {
    @Test fun importedDividerRetainsItsArithmeticWhenReopened() {
        val original = read(fixture("2.7.1"))
        val modern = read(fixture("5.1.0-dev"))
        for ((project, expected) in listOf(
            original to (0x55555555uL to 1uL),
            modern to (0xaaaaaaaauL to 2uL),
        )) {
            assertEquals(expected, divide(project))
            for (destinationVersion in listOf("2.7.1", "5.1.0-dev")) {
                val exported = CircCodec.writeProject(project.copy(sourceVersion = destinationVersion))
                assertEquals(expected, divide(read(exported)))
            }
        }
    }

    private fun read(xml: String): CircuitProject =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project

    private fun divide(project: CircuitProject): Pair<ULong?, ULong?> {
        val ports = CircuitSimulator().settle(project).portVectors
        return ports.getValue(GridPoint(500, 300)).toULongOrNull() to
            ports.getValue(GridPoint(380, 420)).toULongOrNull()
    }

    private fun fixture(source: String) = """
        <project source="$source" version="1.0">
          <lib name="0" desc="#Wiring"/><lib name="1" desc="#Arithmetic"/>
          <main name="main"/><circuit name="main">
            <comp loc="(400,300)" name="Divider" lib="1"><a name="width" val="32"/></comp>
            <comp loc="(200,290)" name="Constant" lib="0"><a name="width" val="32"/><a name="value" val="0x0"/></comp>
            <comp loc="(200,310)" name="Constant" lib="0"><a name="width" val="32"/><a name="value" val="0x3"/></comp>
            <comp loc="(380,200)" name="Constant" lib="0"><a name="width" val="32"/><a name="facing" val="south"/><a name="value" val="0x80000000"/></comp>
            <comp loc="(500,300)" name="Pin" lib="0"><a name="width" val="32"/><a name="facing" val="west"/><a name="output" val="true"/></comp>
            <comp loc="(380,420)" name="Pin" lib="0"><a name="width" val="32"/><a name="facing" val="north"/><a name="output" val="true"/></comp>
            <wire from="(200,290)" to="(360,290)"/><wire from="(200,310)" to="(360,310)"/>
            <wire from="(380,200)" to="(380,280)"/><wire from="(400,300)" to="(500,300)"/>
            <wire from="(380,320)" to="(380,420)"/>
          </circuit>
        </project>
    """.trimIndent()
}
