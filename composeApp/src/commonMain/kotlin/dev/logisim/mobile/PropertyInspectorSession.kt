package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.CircuitComponent

internal data class PropertyInspectorContext(
    val circuit: CircuitAccessibilityContext,
    val components: List<CircuitComponent>,
)

internal fun EditorState.propertyInspectorContext() = PropertyInspectorContext(
    accessibilityContext(), document.components.filter { it.id in selectedIds },
)

@Stable
internal class PropertyValueEdit(val field: PropertyEditorField) {
    var value by mutableStateOf(TextFieldValue(field.value.orEmpty()))
}

internal class PropertyInspectorSession(
    private val owner: EditorState,
    val context: PropertyInspectorContext,
    private val darkTheme: Boolean = false,
) {
    private val registry = owner.componentRegistry
    var label by mutableStateOf(TextFieldValue(context.components.map { it.label }.distinct().singleOrNull().orEmpty()))
    var editing by mutableStateOf<PropertyValueEdit?>(null)
        private set
    private var active = true
    val focus = InspectorFocusState { editing == null }

    fun open(field: PropertyEditorField, otherFocus: InspectorFocusState? = null) {
        if (!active || field.readOnly) return
        otherFocus?.clear()
        editing = PropertyValueEdit(field)
        focus.clear()
    }

    fun cancel(edit: PropertyValueEdit) {
        if (editing === edit) editing = null
    }

    fun validation(edit: PropertyValueEdit): PropertyEditResult =
        PropertyEditorModel.validate(registry, context.components, edit.field.name, edit.value.text, darkTheme)

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
        if (editing != null || !isCurrent(editor, current)) return false
        if (!editor.updateSelectedLabel(label.text)) {
            // Evolution puts the previous label back when it refuses one (#321); the status line says why.
            label = TextFieldValue(context.components.map { it.label }.distinct().singleOrNull().orEmpty())
        }
        return true
    }

    fun applyValue(editor: EditorState, current: PropertyInspectorSession, edit: PropertyValueEdit): Boolean {
        if (editing !== edit || !isCurrent(editor, current)) return false
        val result = validation(edit) as? PropertyEditResult.Valid ?: return false
        editor.updateSelectedAttributes(result.updates)
        editing = null
        return true
    }

    fun close() {
        active = false
        editing = null
        focus.close()
    }
}

@Composable
internal fun rememberPropertyInspectorSession(editor: EditorState): PropertyInspectorSession {
    val context = editor.propertyInspectorContext()
    val darkTheme = LocalEditorDarkTheme.current
    val session = remember(editor, editor.componentRegistry, context, darkTheme) { PropertyInspectorSession(editor, context, darkTheme) }
    DisposableEffect(session) { onDispose { session.close() } }
    return session
}
