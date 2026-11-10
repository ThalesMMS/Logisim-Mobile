package dev.logisim.mobile.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CircBundleInteroperabilityTest {
    @Test
    fun readsJavaDeflatedEntriesAndDataDescriptors() {
        val random = Random(573)
        repeat(40) { index ->
            val body = if (index % 2 == 0) "data".repeat(index * 300) else buildString {
                repeat(index * 200) { append(('a'.code + random.nextInt(26)).toChar()) }
            }
            val files = CircBundleCodec.create(CircuitProject(listOf(CircuitDocument("main")))).files +
                ("library/test.circ" to body.encodeToByteArray())
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.setLevel(index % 10)
                for ((name, bytes) in files) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val bundle = assertIs<CircBundleImportResult.Success>(CircBundleCodec.read(output.toByteArray()), "case $index").bundle
            for ((name, expected) in files) assertContentEquals(expected, bundle.files.getValue(name), "$index/$name")
        }
    }

    @Test
    fun standardJavaZipReaderOpensMobileArchive() {
        val bundle = CircBundleCodec.create(CircuitProject(listOf(CircuitDocument("main"))))
        val archive = CircBundleCodec.write(bundle)
        val read = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                read[entry.name] = zip.readBytes()
            }
        }
        assertEquals(bundle.files.keys, read.keys)
        for ((name, expected) in bundle.files) assertContentEquals(expected, read.getValue(name))
    }

    @Test
    fun rejectsTraversalAndDuplicateNormalizedNamesFromExternalZip() {
        for (bad in listOf("../escape.circ", "/tmp/escape.circ", "C:\\escape.circ", "library/deep/escape.circ")) {
            assertIs<CircBundleImportResult.Failure>(CircBundleCodec.read(zip(mapOf(bad to "invalid"))))
        }
        assertIs<CircBundleImportResult.Failure>(CircBundleCodec.read(zip(linkedMapOf(
            "library/test.circ" to "first", "library\\test.circ" to "second"
        ))))
        assertIs<CircBundleImportResult.Failure>(CircBundleCodec.read(zip(linkedMapOf(
            "library/test.circ" to "first", "library/TEST.circ" to "second"
        ))))
    }

    @Test
    fun rejectsCorruptedDataDescriptor() {
        val archive = zip(mapOf("main.circ" to "<project/>"))
        val descriptor = (0 until archive.size - 16).first { index ->
            archive[index] == 0x50.toByte() && archive[index + 1] == 0x4b.toByte() &&
                archive[index + 2] == 7.toByte() && archive[index + 3] == 8.toByte()
        }
        archive[descriptor + 4] = (archive[descriptor + 4].toInt() xor 1).toByte()
        assertIs<CircBundleImportResult.Failure>(CircBundleCodec.read(archive))
    }

    private fun zip(files: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
