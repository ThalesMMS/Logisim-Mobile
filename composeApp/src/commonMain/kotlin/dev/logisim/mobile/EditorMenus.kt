package dev.logisim.mobile

import dev.logisim.mobile.localization.Localizer

/**
 * The menu that lists each project tool, following Evolution's menu bar: File holds Export drawing (Export Image and
 * Print) and portable projects, Simulate the signal recording (Logging), test vectors, the assembly viewer and the
 * run-time tools, FPGA the board, HDL, companion and Tcl tools, Help the examples, and Project the rest, including tools
 * that extensions add. The tool ids and the "project-tool-<id>" item ids stay the same wherever a tool is listed.
 * Preferences has its own File item and is not listed here.
 */
internal fun editorToolMenu(toolId: String): String? = when (toolId) {
    "preferences" -> null
    "visual-export", "portable-projects" -> "file"
    "trace", "test-vectors", ASSEMBLY_VIEWER_ID, "soc-debugger", "memory-editor", "diagnostics" -> "simulate"
    "fpga", "hdl-export", "hdl-import", "hdl-simulation", "companion-pairing", "tcl", "external-io", jarCompanionToolId -> "fpga"
    "help-examples", "legal-notices" -> "help"
    else -> "project"
}

/**
 * Evolution's Project menu circuit commands for the circuit in view (MenuProject: Add Circuit…, Move Circuit Up/Down,
 * Set As Main Circuit, Remove Circuit…), so they are one menu away and Find Action lists them. Rename stays in the
 * circuit list, as Evolution renames through the circuit's attributes.
 */
internal fun circuitCommandActions(
    state: EditorState,
    strings: Localizer,
    writable: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
): List<EditorToolbarAction> {
    val circuits = state.project.circuits
    val index = circuits.indexOfFirst { it.id == state.document.id }
    val current = circuits.getOrNull(index)
    val isMain = current?.name == state.project.mainCircuitName
    return listOf(
        EditorToolbarAction("add-circuit", strings("project_add_circuit"), enabled = writable, onClick = onAdd),
        EditorToolbarAction("move-circuit-up", strings("project_move_circuit_up"), enabled = writable && index > 0) {
            current?.let { state.moveCircuit(it.id, -1) }
        },
        EditorToolbarAction("move-circuit-down", strings("project_move_circuit_down"),
            enabled = writable && index >= 0 && index < circuits.lastIndex) { current?.let { state.moveCircuit(it.id, 1) } },
        EditorToolbarAction("set-main-circuit", strings("project_set_main_circuit"), enabled = writable && current != null && !isMain) {
            current?.let { state.setMainCircuit(it.id) }
        },
        // Enabled for the main and used circuits too: the dialog explains why they stay (#C31).
        EditorToolbarAction("remove-circuit", strings("project_remove_circuit"), enabled = writable && current != null) {
            current?.let { onRemove(it.id) }
        },
    )
}

/**
 * File > Open example: every bundled example under one heading, by its localized title, opened as the Help and
 * examples gallery opens it. SAP-1 and AND keep their item ids and descriptions.
 */
internal fun exampleMenuActions(strings: Localizer, enabled: Boolean, onOpen: (HelpExample) -> Unit): List<EditorToolbarAction> =
    listOf(EditorToolbarAction("file-examples", strings("menu_open_example"), separator = true, onClick = {})) +
        HelpContent.examples.map { example ->
            val title = strings(example.title)
            val description = when (example.id) {
                "sap1" -> strings("a11y_load_sap1")
                "and" -> strings("a11y_load_and")
                else -> strings("a11y_load_example", "name" to example.title)
            }
            EditorToolbarAction("example-${example.id}", title, description, enabled = enabled) { onOpen(example) }
        }
