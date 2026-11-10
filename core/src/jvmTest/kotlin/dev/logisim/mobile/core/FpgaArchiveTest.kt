package dev.logisim.mobile.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FpgaArchiveTest {
    private val board = FpgaBoardDefinition("Export", FpgaDevice(FpgaVendor.OPEN_FPGA, "ice40", "hx1k", "tq144", "1"),
        null, emptyList())
    private fun plan(files: List<FpgaSourceFile>) = FpgaBuildPlan(board, FpgaFlow.OPEN_ICE40, "logisim_fpga_top", files,
        emptyList(), emptyMap(), emptyList())

    @Test fun exportsAnOrdinaryDeterministicZipWithOriginalPathsAndUtf8Contents() {
        val files = listOf(FpgaSourceFile("hierarchy/user_top.v", "// Sinal de saída\nmodule user_top; endmodule\n"),
            FpgaSourceFile("pins.pcf", "set_io result 21\n"))
        val bytes = plan(files).archive()
        assertContentEquals(bytes, plan(files.reversed()).archive())
        val extracted = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                extracted[entry.name] = zip.readBytes().decodeToString()
            }
        }
        assertEquals(files.associate { it.path to it.content }, extracted)
    }

    @Test fun rejectsTraversalAndNamesThatCollideOnCaseInsensitiveFilesystems() {
        assertFailsWith<IllegalArgumentException> { plan(listOf(FpgaSourceFile("../out.v", "x"))).archive() }
        assertFailsWith<IllegalArgumentException> {
            plan(listOf(FpgaSourceFile("main.v", "x"), FpgaSourceFile("MAIN.v", "y"))).archive()
        }
    }

    @Test fun reptarSizedCompanionBitstreamBytesArePreservedWithoutTextDecoding() {
        val bitstream = ByteArray(4_238_708) { it.toByte() }
        val bytes = fpgaArchive(mapOf("design.bit" to bitstream))
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            assertEquals("design.bit", zip.nextEntry.name)
            assertContentEquals(bitstream, zip.readBytes())
        }
    }

    @Test fun vendorBitstreamAtTheResponseLimitCanBeExported() {
        val bitstream = ByteArray(5 * 1024 * 1024) { index -> (index % 251).toByte() }
        val bytes = fpgaArchive(mapOf("design.sof" to bitstream))
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            assertEquals("design.sof", zip.nextEntry.name)
            assertContentEquals(bitstream, zip.readBytes())
        }
        assertFailsWith<IllegalArgumentException> {
            fpgaArchive(mapOf("design.sof" to ByteArray(bitstream.size + 1)))
        }
    }
}
