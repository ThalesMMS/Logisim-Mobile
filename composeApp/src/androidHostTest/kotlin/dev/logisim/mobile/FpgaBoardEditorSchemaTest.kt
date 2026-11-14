package dev.logisim.mobile

import dev.logisim.mobile.core.FpgaBoardCodec
import dev.logisim.mobile.core.FpgaBoardImportResult
import dev.logisim.mobile.core.FpgaBoardPicture
import dev.logisim.mobile.core.FpgaBoardWriter
import dev.logisim.mobile.core.FpgaPinDirection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * W2-05: every built-in board opens in the editor with no live problem (the editor's checks never flag a board the
 * writer accepts), its per-pin fields join back to the same pin sets, and its clock frequency keeps its hertz.
 */
class FpgaBoardEditorSchemaTest {
    private val directory = listOf(File("composeApp/src/commonMain/composeResources/files/fpga-boards"),
        File("src/commonMain/composeResources/files/fpga-boards")).first(File::isDirectory)

    @Test
    fun builtInBoardsValidateCleanlyAndKeepTheirPinSets() {
        val boards = directory.listFiles { file -> file.extension == "xml" }.orEmpty().sortedBy { it.name }
        assertTrue(boards.size >= 31)
        var perPin = 0
        var freeLists = 0
        for (file in boards) {
            val xml = file.readText()
            val board = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(xml), file.name).board
            val picture = checkNotNull(FpgaBoardPicture.read(xml)) { file.name }
            val draft = FpgaBoardEditorDraft.from(board)
            assertEquals(emptyList(), draft.validate(picture.width, picture.height).map { "${it.resourceIndex} ${it.field} ${it.text.resolve()}" },
                file.name)
            // The frequency is shown in the largest whole unit and converts back to the same hertz.
            assertEquals(board.clock?.frequencyHz, draft.frequencyHz, file.name)
            for ((index, resource) in board.resources.withIndex()) {
                val edited = draft.resources[index]
                for ((field, direction) in listOf(FpgaResourceField.INPUT to FpgaPinDirection.INPUT,
                    FpgaResourceField.OUTPUT to FpgaPinDirection.OUTPUT, FpgaResourceField.INOUT to FpgaPinDirection.INOUT)) {
                    val original = resource.pins.filter { it.direction == direction }.map { it.location }
                    when (val layout = edited.pinLayout(field)) {
                        is FpgaPinLayout.PerPin -> {
                            perPin++
                            // Retyping every per-pin field gives back the attribute exactly.
                            val retyped = (0 until layout.count).fold(edited.copy(fields = edited.fields - field)) { next, pin ->
                                next.withPin(field, pin, layout.count, original.getOrElse(pin) { "" })
                            }
                            assertEquals(original, retyped.locations(field), "${file.name} ${resource.type} $field")
                            assertEquals(original.joinToString(","), retyped.fields[field].orEmpty().trimEnd(','), "${file.name} $field")
                        }
                        is FpgaPinLayout.FreeList -> {
                            freeLists++
                            assertEquals(original, edited.locations(field), "${file.name} ${resource.type} $field")
                        }
                        FpgaPinLayout.Hidden -> assertTrue(original.isEmpty(), "${file.name} ${resource.type} $field")
                    }
                }
            }
            val written = assertIs<FpgaBoardImportResult.Success>(FpgaBoardWriter.write(draft.board(), picture), file.name).board
            assertEquals(board.clock?.frequencyHz, written.clock?.frequencyHz, file.name)
            assertEquals(board.resources.map { it.pins.map { pin -> pin.location to pin.direction } },
                written.resources.map { it.pins.map { pin -> pin.location to pin.direction } }, file.name)
        }
        // Every built-in resource fits its type; custom boards that do not are covered in FpgaWave2ModelTest.
        assertTrue(perPin > 1000 && freeLists == 0, "per-pin $perPin, free lists $freeLists")
    }
}
