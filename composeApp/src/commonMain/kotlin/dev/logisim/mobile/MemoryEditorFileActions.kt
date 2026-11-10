package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.MemoryImageFormat
import dev.logisim.mobile.core.MemoryImageImportResult

/** Binary image operations are independent of the active .circ file association. */
interface MemoryEditorFileActions {
    fun importImage(component: CircuitComponent, format: MemoryImageFormat, onResult: (MemoryImageImportResult?) -> Unit)
    fun exportImage(component: CircuitComponent, format: MemoryImageFormat, contents: ByteArray)
}
