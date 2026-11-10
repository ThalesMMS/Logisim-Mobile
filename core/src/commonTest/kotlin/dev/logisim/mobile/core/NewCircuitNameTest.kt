package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewCircuitNameTest {
    @Test
    fun newCircuitUsesTheDesktopNameThatReopensUnchanged() {
        val document = CircuitDocument()
        // Logisim Evolution names a new circuit "main" and replaces any name outside its
        // identifier syntax with a random suffix when it reopens the file.
        assertEquals("main", document.name)
        assertTrue(Regex("[A-Za-z][A-Za-z0-9_]*").matches(document.name))
        val xml = CircCodec.writeProject(CircuitProject(listOf(document)))
        assertTrue("<main name=\"main\"/>" in xml, xml)
        assertTrue("<circuit name=\"main\" " in xml, xml)
    }
}
