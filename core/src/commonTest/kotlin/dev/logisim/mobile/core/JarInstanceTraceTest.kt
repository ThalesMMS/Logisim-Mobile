package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JarInstanceTraceTest {
    private fun reply(sample: String) = CompanionJsonCodec.parseObject("""
        {"instances":[
            {"id":"/200,180/40,50","path":[[200,180]],"circuit":"inner","factory":"Gray Counter",
             "location":[40,50],"bounds":[10,35,30,30],
             "terminals":[{"position":[10,50],"direction":"input","width":1,"exclusive":false},
                          {"position":[40,50],"direction":"output","width":4,"exclusive":true}]}
        ],"frames":[$sample]}
    """.trimIndent())

    @Test
    fun buildsNativeLayoutAndReadsResolvedBitsInPortOrder() {
        val trace = reply("""{"/200,180/40,50":["1","10?E"]}""").readJarInstanceTrace(expectedFrames = 1)
        val instance = trace.instances.single()
        assertEquals(listOf(GridPoint(200, 180)), instance.path)
        assertEquals(ComponentBounds(10, 35, 40, 65), instance.layout.bounds)
        assertEquals(listOf(PortDirection.INPUT, PortDirection.OUTPUT), instance.layout.ports.map { it.direction })
        assertEquals(listOf(false, true), instance.exclusiveTerminals)
        assertEquals(listOf("1", "10?E"), trace.frames.single().getValue(instance.id).map { it.display })
    }

    @Test
    fun distinguishesReleasedAndDrivenTerminalValuesFromTheResolvedWire() {
        val observed = reply("""{"/200,180/40,50":["1","EEEE"]}""")
        fun withDrive(value: CompanionJson): CompanionJsonObject = observed.copy(fields = observed.fields + (
            "drives" to CompanionJsonArray(listOf(CompanionJsonObject(mapOf("/200,180/40,50" to
                CompanionJsonArray(listOf(CompanionJsonNull, value))))))))
        val released = withDrive(CompanionJsonString("Z")).readJarInstanceTrace(1, expectDriverTrace = true)
        val driven = withDrive(CompanionJsonString("10?E")).readJarInstanceTrace(1, expectDriverTrace = true)
        assertEquals("EEEE", released.frames.single().getValue("/200,180/40,50").last().display)
        assertEquals(listOf(null, JarTerminalDrive.Released), released.drives!!.single().getValue("/200,180/40,50"))
        val drive = assertIs<JarTerminalDrive.Driven>(driven.drives!!.single().getValue("/200,180/40,50").last())
        assertEquals("10?E", drive.value.display)
        assertFailsWith<IllegalArgumentException> { observed.readJarInstanceTrace(1, expectDriverTrace = true) }
        assertFailsWith<IllegalArgumentException> { withDrive(CompanionJsonNull).readJarInstanceTrace(1, expectDriverTrace = true) }
    }

    @Test
    fun rejectsDifferentInstanceIdentityOrTerminalWidth() {
        assertFailsWith<IllegalArgumentException> {
            reply("""{"/200,190/40,50":["1","1000"]}""").readJarInstanceTrace(expectedFrames = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            reply("""{"/200,180/40,50":["1","100"]}""").readJarInstanceTrace(expectedFrames = 1)
        }
    }
}
