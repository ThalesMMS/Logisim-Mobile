package dev.logisim.mobile

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

/**
 * Keeps an [EditorModalSheet] open while a panel in it holds unapplied changes (X3). Back, the scrim, a swipe, the
 * drag handle and the tool sheet's Close ask first: apply (when one draft can), discard, or keep editing.
 */
internal class EditorSheetDismissGuard {
    internal class Draft(val dirty: () -> Boolean, val apply: () -> (() -> Unit)?)

    private val drafts = mutableStateListOf<Draft>()
    internal var confirming by mutableStateOf(false)
    private var released = false
    internal var dismiss: () -> Unit = {}

    internal fun add(draft: Draft) { drafts += draft }
    internal fun remove(draft: Draft) { drafts -= draft }

    private val dirtyDrafts: List<Draft> get() = drafts.filter { it.dirty() }

    /** Whether the sheet may close now; otherwise the confirmation opens and the sheet stays. */
    fun allowsDismissal(): Boolean {
        if (released || dirtyDrafts.isEmpty()) return true
        confirming = true
        return false
    }

    /** The single dirty draft's Apply, when exactly one panel can apply what it holds. */
    internal val apply: (() -> Unit)? get() = dirtyDrafts.singleOrNull()?.apply?.invoke()

    /** Closes the sheet without asking again. */
    fun release() {
        confirming = false
        released = true
        dismiss()
    }
}

internal val LocalEditorSheetDismissGuard = staticCompositionLocalOf<EditorSheetDismissGuard?> { null }

/**
 * Registers a panel's unapplied [dirty] state with the enclosing sheet; [onApply] commits it from the confirmation.
 * Outside a sheet it does nothing.
 */
@Composable
internal fun EditorSheetDraft(dirty: Boolean, onApply: (() -> Unit)? = null) {
    val guard = LocalEditorSheetDismissGuard.current ?: return
    val currentDirty by rememberUpdatedState(dirty)
    val currentApply by rememberUpdatedState(onApply)
    DisposableEffect(guard) {
        val draft = EditorSheetDismissGuard.Draft({ currentDirty }, { currentApply })
        guard.add(draft)
        onDispose { guard.remove(draft) }
    }
}

/** The confirmation an [EditorSheetDismissGuard] shows when a dismissal would discard a draft. */
@Composable
internal fun EditorSheetDiscardDialog(guard: EditorSheetDismissGuard) {
    if (!guard.confirming) return
    val strings = rememberLocalizer()
    val apply = guard.apply
    AppAlertDialog(modifier = Modifier.testTag("sheet-discard-dialog"), onDismissRequest = { guard.confirming = false },
        title = { Text(strings("sheet_discard_title")) },
        text = { Text(strings("sheet_discard_text")) },
        confirmButton = {
            if (apply != null) TextButton(onClick = { apply(); guard.release() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("sheet-discard-apply")) { Text(strings(Messages.ActionApply)) }
            TextButton(onClick = { guard.release() }, modifier = Modifier.heightIn(min = 48.dp).testTag("sheet-discard-confirm")) {
                Text(strings("confirm_discard"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { guard.confirming = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("sheet-discard-keep")) {
                Text(strings("sheet_keep_editing"))
            }
        })
}
