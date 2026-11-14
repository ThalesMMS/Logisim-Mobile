package dev.logisim.mobile

import dev.logisim.mobile.core.CompanionJsonCodec
import dev.logisim.mobile.core.CompanionJsonObject
import dev.logisim.mobile.core.CompanionJsonString
import dev.logisim.mobile.core.ExtensionDigest
import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.WorkspaceCatalogStorage

internal data class FpgaExternalBoard(val name: String, val xml: String)

internal data class FpgaBoardCatalog(
    val external: Map<String, FpgaExternalBoard> = emptyMap(),
    val selected: String? = null,
)

/** External board XML and the selected key share one atomic, app-owned file. */
class FpgaBoardCatalogStore(private val storage: WorkspaceCatalogStorage? = null) {
    private var memory = FpgaBoardCatalog()

    internal fun load(): FpgaBoardCatalog =
        storage?.withExclusiveAccess { read() } ?: memory

    /**
     * FpgaBoards(): when the saved SelectedBoard names no available board (or none was chosen), the first of the
     * [builtIn] boards becomes the selection and is saved, so the FPGA panel starts with ALCHITRY_AU_IO like Evolution.
     */
    internal fun load(builtIn: List<String>): FpgaBoardCatalog = update { it.withValidSelection(builtIn) }

    internal fun select(id: String): FpgaBoardCatalog = update { catalog ->
        check(id.startsWith(BUILTIN_PREFIX) || id in catalog.external)
        catalog.copy(selected = id)
    }

    internal fun add(board: FpgaBoardDefinition): FpgaBoardCatalog {
        val xml = checkNotNull(board.originalXml)
        val id = "external:" + ExtensionDigest.sha256(xml.encodeToByteArray())
        return update { catalog ->
            catalog.copy(external = catalog.external + (id to FpgaExternalBoard(board.name, xml)), selected = id)
        }
    }

    /** Removing the selected board selects the first built-in board, as FpgaBoards.actionPerformed does. */
    internal fun remove(id: String, builtIn: List<String> = emptyList()): FpgaBoardCatalog = update { catalog ->
        catalog.copy(external = catalog.external - id, selected = catalog.selected?.takeUnless { it == id }).withValidSelection(builtIn)
    }

    /**
     * Undoes [remove]: puts [entry] back under [id] at its former [index] in the catalog and selects [previousSelected]
     * again when it still exists. Written atomically like every other change.
     */
    internal fun restore(id: String, entry: FpgaExternalBoard, previousSelected: String?, index: Int = Int.MAX_VALUE): FpgaBoardCatalog =
        update { catalog ->
            val entries = catalog.external.entries.filter { it.key != id }.map { it.key to it.value }.toMutableList()
            entries.add(index.coerceIn(0, entries.size), id to entry)
            val external = linkedMapOf(*entries.toTypedArray())
            val selected = previousSelected?.takeIf { it in external || it.startsWith(BUILTIN_PREFIX) } ?: catalog.selected
            catalog.copy(external = external, selected = selected)
        }

    private fun FpgaBoardCatalog.withValidSelection(builtIn: List<String>): FpgaBoardCatalog {
        val valid = selected?.let { id -> id in external || id.removePrefix(BUILTIN_PREFIX).let { it != id && it in builtIn } } == true
        return if (valid || builtIn.isEmpty()) this else copy(selected = BUILTIN_PREFIX + builtIn.first())
    }

    private fun update(change: (FpgaBoardCatalog) -> FpgaBoardCatalog): FpgaBoardCatalog =
        storage?.withExclusiveAccess {
            val before = read()
            change(before).also { after ->
                if (before != after) storage.writeAtomically(FILE, encode(after))
            }
        } ?: change(memory).also { memory = it }

    private fun read(): FpgaBoardCatalog {
        val raw = storage?.read(FILE) ?: return FpgaBoardCatalog()
        val root = CompanionJsonCodec.parseObject(raw)
        check(root.string("version") == "1")
        val external = root.obj("external").fields.mapValues { (_, value) ->
            val entry = value as CompanionJsonObject
            FpgaExternalBoard(entry.string("name"), entry.string("xml"))
        }
        return FpgaBoardCatalog(external, root.string("selected").ifEmpty { null })
    }

    private fun encode(catalog: FpgaBoardCatalog): String = CompanionJsonCodec.write(CompanionJsonObject(mapOf(
        "version" to CompanionJsonString("1"),
        "selected" to CompanionJsonString(catalog.selected.orEmpty()),
        "external" to CompanionJsonObject(catalog.external.mapValues { (_, board) ->
            CompanionJsonObject(mapOf("name" to CompanionJsonString(board.name), "xml" to CompanionJsonString(board.xml)))
        }),
    )))

    internal companion object {
        const val BUILTIN_PREFIX = "builtin:"
        private const val FILE = "fpga-board-catalog.json"
    }
}
