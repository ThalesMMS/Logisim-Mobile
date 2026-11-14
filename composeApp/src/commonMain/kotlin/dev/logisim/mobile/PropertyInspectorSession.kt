package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.SocComponents
import dev.logisim.mobile.core.localization.message

internal data class PropertyInspectorContext(
    val circuit: CircuitAccessibilityContext,
    val components: List<CircuitComponent>,
    /** The circuit's SoC buses, which a selected SoC component chooses from (C13). */
    val socBuses: List<CircuitComponent> = emptyList(),
)

internal fun EditorState.propertyInspectorContext() = PropertyInspectorContext(
    accessibilityContext(), document.components.filter { it.id in selectedIds },
    document.components.filter { it.kind == SocComponents.BUS },
)

@Stable
internal class PropertyValueEdit(val field: PropertyEditorField) {
    // The current value starts selected, so typing replaces it instead of appending to it.
    var value by mutableStateOf(field.value.orEmpty().let { TextFieldValue(it, TextRange(0, it.length)) })

    /**
     * Whether the user entered something. A field with different values across the selection starts empty;
     * applying that untouched empty text would overwrite every component's value.
     */
    val changed: Boolean get() = value.text != this@PropertyValueEdit.field.value.orEmpty()
}

internal class PropertyInspectorSession(
    private val owner: EditorState,
    val context: PropertyInspectorContext,
    private val darkTheme: Boolean = false,
    /** Offers Undo beside the status line after an edit of several components (C7). */
    private val undoNotice: MultiEditUndoNotice? = null,
) {
    private val registry = owner.componentRegistry
    private val selectedLabels = context.components.map { it.label }.distinct()
    var label by mutableStateOf(TextFieldValue(selectedLabels.singleOrNull().orEmpty()))

    /** Whether the selection's labels differ, so the field starts empty with a "Different labels" hint. */
    val mixedLabels: Boolean get() = selectedLabels.size > 1

    /**
     * Whether every selected component has Evolution's label attribute. Splitters, constants, power, Text and others
     * have none: a label typed there would be lost in Evolution (C9).
     */
    val labelled: Boolean = context.components.isNotEmpty() && context.components.all { component ->
        registry.definition(component.kind)?.schema?.keys?.any { it.name == "label" } != false
    }

    /** Whether the label field differs from the selection's label, so Apply has something to apply. */
    val labelChanged: Boolean get() =
        if (selectedLabels.size == 1) label.text != selectedLabels.single() else label.text.isNotEmpty()
    var editing by mutableStateOf<PropertyValueEdit?>(null)
        private set
    private var active = true

    /** Set when a replacement session for the same components took over the label draft. */
    private var handedOff = false

    /** The label text this session last applied, which a later commit does not apply (or report) again. */
    private var appliedLabel: String? = null

    /** [EditorState.historyTravels] when the session opened: an Undo or Redo since then drops the label draft. */
    private val historyTravels = owner.historyTravels
    val focus = InspectorFocusState { editing == null }

    /**
     * Takes over the label draft of [previous] when it edited the same components of the same circuit, as when moving
     * the selection or editing another of its properties recreates the session (C9). Otherwise [previous] keeps it
     * and applies it when it is retired.
     */
    fun adoptDraft(previous: PropertyInspectorSession?) {
        if (previous == null || previous === this || !previous.active || previous.owner !== owner ||
            previous.historyTravels != owner.historyTravels || previous.context.circuit != context.circuit ||
            previous.context.components.map { it.id } != context.components.map { it.id }) return
        if (previous.labelChanged || previous.label.text == label.text) label = previous.label
        previous.focus.focusedPresentation?.let { focus.focusChanged(it, true) }
        previous.handedOff = true
    }

    fun open(field: PropertyEditorField, otherFocus: InspectorFocusState? = null) {
        if (!active || field.readOnly) return
        otherFocus?.clear()
        editing = PropertyValueEdit(field)
        focus.clear()
    }

    fun cancel(edit: PropertyValueEdit) {
        if (editing === edit) editing = null
    }

    fun validation(edit: PropertyValueEdit): PropertyEditResult = validation(edit, edit.value.text)

    /** Validates [text] for [edit]'s field; it reads no snapshot state, so a background thread may call it. */
    fun validation(edit: PropertyValueEdit, text: String): PropertyEditResult =
        PropertyEditorModel.validate(registry, context.components, edit.field.name, text, darkTheme, context.socBuses)

    /**
     * How many components "Clear for all N" empties when [edit]'s field differs across several of them, or null when
     * it is not offered. Only plain text, whose empty value Evolution reads back as empty, may be cleared: memory
     * contents, PLA tables, HDL sources and images are EXTERNAL and an empty one is no valid value there (Evolution's
     * Rom.ContentsAttribute.parse("") gives null), nor are colors, fonts or options (C7).
     */
    fun clearForAllCount(edit: PropertyValueEdit): Int? {
        val field = edit.field
        if (!field.mixed || field.kind != PropertyInputKind.TEXT || context.components.size < 2) return null
        if (validation(edit, "") !is PropertyEditResult.Valid) return null
        return context.components.count { component ->
            PropertyEditorModel.fields(registry, listOf(component), darkTheme).firstOrNull { it.name == field.name }
                ?.value.orEmpty().isNotEmpty()
        }.takeIf { it > 0 }
    }

    /** Whether [edit]'s field differs across several components and may be emptied for all of them at once. */
    fun canClearForAll(edit: PropertyValueEdit): Boolean = clearForAllCount(edit) != null

    /** How many labels "Clear for all N" empties: the selected components whose label is not empty yet. */
    val labelClearCount: Int get() = context.components.count { it.label.isNotEmpty() }

    fun isCurrent(editor: EditorState, current: PropertyInspectorSession): Boolean =
        active && this === current && owner === editor && registry === editor.componentRegistry &&
            context == editor.propertyInspectorContext() && editor.documentWritable && editor.interactionsEnabled

    fun canRequestLabelFocus(request: InspectorFocusRequest): Boolean =
        isCurrent(owner, this) && editing == null && focus.focusRequest == request

    fun labelFocusChanged(editor: EditorState, current: PropertyInspectorSession,
                          host: InspectorPresentation, focused: Boolean, otherFocus: InspectorFocusState?) {
        if (!isCurrent(editor, current)) return
        if (focused) otherFocus?.clear()
        focus.focusChanged(host, focused)
    }

    fun applyLabel(editor: EditorState, current: PropertyInspectorSession): Boolean {
        if (editing != null || !labelled || !isCurrent(editor, current)) return false
        // An untouched field changes nothing: for a mixed selection it is empty, and applying it would erase every
        // label (C7). Emptying them takes [clearLabels].
        if (!labelChanged) return false
        recording(editor) {
            if (!editor.updateSelectedLabel(label.text)) {
                // Evolution puts the previous label back when it refuses one (#321); the status line says why.
                label = TextFieldValue(selectedLabels.singleOrNull().orEmpty())
            }
        }
        return true
    }

    /** Empties the labels of a selection whose labels differ: the explicit "Clear for all N" action (C7). */
    fun clearLabels(editor: EditorState, current: PropertyInspectorSession): Boolean {
        if (editing != null || !labelled || !mixedLabels || !isCurrent(editor, current)) return false
        label = TextFieldValue("")
        return recording(editor) { editor.updateSelectedLabel("") }
    }

    /**
     * Applies a typed label that was not applied yet to the components it was typed for, as Evolution's attribute
     * table stops editing when the focus leaves it: on focus loss, when the sheet closes and before the selection
     * changes (C9, C155). The status line says why when the label is refused. Returns whether it applied one.
     * A draft typed before an Undo or Redo is dropped with a notice: it is not in Evolution's history, and applying it
     * after the undo would put it back on top of the restored state and clear Redo.
     */
    fun commitLabelDraft(): Boolean {
        if (!active || handedOff || editing != null || !labelled || !labelChanged || label.text == appliedLabel) return false
        val editor = owner
        val text = label.text
        if (editor.historyTravels != historyTravels || registry !== editor.componentRegistry ||
            editor.accessibilityContext() != context.circuit ||
            !editor.documentWritable || !editor.interactionsEnabled) {
            // An Undo or Redo came first, or its components are in a circuit or document the editor has left (the
            // circuit switch itself applies the draft first, see [bindPendingCommit]): say that the typed label was
            // not applied (C9, C155).
            appliedLabel = text
            editor.reportStatus(message("inspector_label_draft_discarded", "label" to text))
            return false
        }
        val ids = context.components.map { it.id }.toSet()
        if (editor.document.components.none { it.id in ids }) return false
        return recording(editor) { editor.updateLabels(ids, text) }.also { if (it) appliedLabel = text }
    }

    /**
     * Lets [owner] apply the label draft before it switches to another circuit (the Circuits tab, a subcircuit, the
     * docked inspector on a tablet, where the focus stays in the field), so the draft reaches the components it was
     * typed for (C9). Returns the action that undoes the binding.
     */
    fun bindPendingCommit(): () -> Unit {
        val commit: () -> Unit = { commitLabelDraft() }
        owner.commitPendingEdit = commit
        return { if (owner.commitPendingEdit === commit) owner.commitPendingEdit = null }
    }

    fun applyValue(editor: EditorState, current: PropertyInspectorSession, edit: PropertyValueEdit): Boolean {
        if (editing !== edit || !isCurrent(editor, current)) return false
        if (edit.field.mixed && !edit.changed) return false
        val result = validation(edit) as? PropertyEditResult.Valid ?: return false
        recording(editor) { editor.updateSelectedAttributes(result.updates) }
        editing = null
        return true
    }

    /**
     * Empties [edit]'s field for every selected component: the dialog's explicit "Clear for all N" action (C7).
     * Returns whether it changed them; when the editor refuses, the dialog stays open and the status line says why.
     */
    fun clearValue(editor: EditorState, current: PropertyInspectorSession, edit: PropertyValueEdit): Boolean {
        if (editing !== edit || !isCurrent(editor, current) || !canClearForAll(edit)) return false
        val result = validation(edit, "") as? PropertyEditResult.Valid ?: return false
        val before = editor.undoHistory.firstOrNull()?.id
        recording(editor) { editor.updateSelectedAttributes(result.updates) }
        if (editor.undoHistory.firstOrNull()?.id.let { it == null || it == before }) return false
        editing = null
        return true
    }

    /**
     * Applies one of [field]'s options, chosen from the inspector's menu or switch, as one undoable edit, like
     * choosing in Evolution's attribute combo box. Returns false when the selection or the value is no longer valid.
     */
    fun applyOption(editor: EditorState, current: PropertyInspectorSession, field: PropertyEditorField, value: String): Boolean {
        if (editing != null || field.readOnly || !isCurrent(editor, current)) return false
        if (value == field.value) return true
        val result = PropertyEditorModel.validate(registry, context.components, field.name, value, darkTheme, context.socBuses)
            as? PropertyEditResult.Valid ?: return false
        recording(editor) { editor.updateSelectedAttributes(result.updates) }
        return true
    }

    /**
     * Runs an edit of the session's components. When it changed several, the status line says how many and Undo is
     * offered beside it (C7).
     */
    private fun <T> recording(editor: EditorState, edit: () -> T): T {
        val before = editor.undoHistory.firstOrNull()?.id
        val ids = context.components.map { it.id }.toSet()
        val previous = editor.document.components.filter { it.id in ids }.associateBy { it.id }
        return edit().also {
            if (editor.undoHistory.firstOrNull()?.id.let { it == null || it == before }) return@also
            // The components the edit actually changed, not every selected one: pins already facing west are not
            // counted when West is chosen for all of them.
            val count = editor.document.components.count { it.id in ids && previous[it.id] != it }
            if (count > 1) {
                editor.reportStatus(MultiEditUndoNotice.status(count))
                undoNotice?.record(editor, count, before)
            }
        }
    }

    fun close() {
        active = false
        editing = null
        focus.close()
    }

    /** Closes a session the inspector no longer shows, applying its label draft first unless a successor took it. */
    fun retire() {
        commitLabelDraft()
        close()
    }
}

@Composable
internal fun rememberPropertyInspectorSession(editor: EditorState, undoNotice: MultiEditUndoNotice? = null): PropertyInspectorSession {
    val context = editor.propertyInspectorContext()
    val darkTheme = LocalEditorDarkTheme.current
    val latest = remember(editor) { arrayOfNulls<PropertyInspectorSession>(1) }
    val session = remember(editor, editor.componentRegistry, context, darkTheme, undoNotice) {
        PropertyInspectorSession(editor, context, darkTheme, undoNotice).also { it.adoptDraft(latest[0]); latest[0] = it }
    }
    // Retiring runs after composition, where changing the document is allowed.
    DisposableEffect(session) {
        val unbind = session.bindPendingCommit()
        onDispose { unbind(); session.retire() }
    }
    return session
}
