package dev.logisim.mobile

import dev.logisim.mobile.core.FpgaBoardCodec
import dev.logisim.mobile.core.FpgaBoardImportResult
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class FpgaBoardCatalogStoreTest {
    @Test
    fun externalBoardsAndSelectionSurviveReopening() {
        val storage = Storage()
        val catalog = FpgaBoardCatalogStore(storage)
        val builtIn = "builtin:BASYS3"
        catalog.select(builtIn)
        assertEquals(builtIn, FpgaBoardCatalogStore(storage).load().selected)

        val xml = """<Workshop><BoardInformation><FPGAInformation Vendor="OPENFPGA" Family="ice40" Part="HX1K" Package="TQ144" Speedgrade="1"/></BoardInformation><IOComponents><LED FPGAPinName="22"/></IOComponents></Workshop>"""
        val imported = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(xml))
        val external = checkNotNull(catalog.add(imported.board).selected)
        assertEquals(external, catalog.add(imported.board).selected)

        val reopened = FpgaBoardCatalogStore(storage)
        assertEquals(external, reopened.load().selected)
        val board = reopened.load().external.getValue(external)
        assertEquals("Workshop", board.name)
        assertEquals(xml, board.xml)
        assertEquals(1, reopened.load().external.size)

        reopened.remove(external)
        val removed = FpgaBoardCatalogStore(storage).load()
        assertNull(removed.selected)
        assertEquals(emptyMap(), removed.external)
    }

    @Test
    fun failedWritesLeaveTheSavedSelectionIntact() {
        val storage = Storage()
        val catalog = FpgaBoardCatalogStore(storage)
        catalog.select("builtin:BASYS3")
        storage.failWrites = true
        assertFailsWith<IllegalStateException> { catalog.select("builtin:BASYS2") }
        assertEquals("builtin:BASYS3", FpgaBoardCatalogStore(storage).load().selected)
    }

    private class Storage : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        var failWrites = false

        override fun <T> withExclusiveAccess(action: () -> T): T = action()
        override fun listNames(): List<String> = files.keys.toList()
        override fun read(name: String): String? = files[name]
        override fun writeAtomically(name: String, contents: String) {
            check(!failWrites)
            files[name] = contents
        }
    }
}
