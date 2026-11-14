package dev.logisim.mobile

import dev.logisim.mobile.core.FpgaBoardCodec
import dev.logisim.mobile.core.FpgaBoardImportResult
import dev.logisim.mobile.core.FpgaBoardPicture
import dev.logisim.mobile.core.FpgaBoardWriter
import dev.logisim.mobile.core.scannedDisplay
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every built-in board opened in the editor and saved unchanged keeps the electrical and display attributes the
 * choice menus edit, and every stored value is one the menus offer (or is kept as a "from file" entry).
 */
class FpgaBoardEditorRoundTripTest {
    private val directory = listOf(File("composeApp/src/commonMain/composeResources/files/fpga-boards"),
        File("src/commonMain/composeResources/files/fpga-boards")).first(File::isDirectory)

    @Test
    fun builtInBoardsRoundTripThroughTheEditorDraft() {
        val boards = directory.listFiles { file -> file.extension == "xml" }.orEmpty().sortedBy { it.name }
        assertTrue(boards.size >= 31, "Built-in boards: ${boards.size}")
        var displays = 0
        for (file in boards) {
            val xml = file.readText()
            val board = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(xml), file.name).board
            val draft = FpgaBoardEditorDraft.from(board)
            val written = assertIs<FpgaBoardImportResult.Success>(FpgaBoardWriter.write(draft.board(), FpgaBoardPicture.read(xml)),
                file.name).board
            assertEquals(board.clock?.electrical, written.clock?.electrical, file.name)
            assertEquals(board.resources.size, written.resources.size, file.name)
            for ((before, after) in board.resources.zip(written.resources)) {
                val name = "${file.name} ${before.id} ${before.type}"
                assertEquals(before.pins.map { it.electrical }, after.pins.map { it.electrical }, name)
                for (key in listOf("rotation", "LedArrayInfo", "ScanningSevenSegInfo", "FPGAPinIOStandard", "FPGAPinDriveStrength",
                    "FPGAPinPullBehavior", "ActivityLevel")) {
                    assertEquals(before.attributes[key], after.attributes[key], "$name $key")
                }
                assertEquals(before.scannedDisplay()?.channelCount, after.scannedDisplay()?.channelCount, name)
                val resource = FpgaResourceEditorDraft.from(before)
                // The menus show every stored value as one of their options.
                for ((field, options) in listOf(
                    FpgaResourceField.STANDARD to FpgaBoardChoices.ioStandard(resource.fields.getValue(FpgaResourceField.STANDARD)),
                    FpgaResourceField.DRIVE to FpgaBoardChoices.drive(resource.fields.getValue(FpgaResourceField.DRIVE)),
                    FpgaResourceField.ROTATION to FpgaBoardChoices.rotation(before.type, resource.fields.getValue(FpgaResourceField.ROTATION)),
                )) {
                    val value = resource.fields.getValue(field)
                    assertTrue(options.any { it.value == value && !it.fromFile }, "$name $field $value")
                }
                if (FpgaResourceEditorDraft.displayAttribute(before.type) != null) {
                    displays++
                    val raw = resource.fields.getValue(FpgaResourceField.DISPLAY)
                    assertEquals(raw, FpgaDisplaySettings.parse(raw).encoded, name)
                    assertEquals(before.pins.size, resource.requiredDisplayPins, name)
                }
                if (resource.fields.getValue(FpgaResourceField.ROTATION).isNotEmpty()) assertTrue(resource.showsRotation, name)
                if (resource.fields.getValue(FpgaResourceField.DRIVE).isNotEmpty()) assertTrue(resource.showsDrive, name)
            }
        }
        assertEquals(7, displays)
    }
}
