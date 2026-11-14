package dev.logisim.mobile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.delay

/**
 * The last inspector edit that changed several components at once (C7): the status line says how many, and Undo
 * stays beside it while that edit is still the latest one in the history.
 */
@Stable
internal class MultiEditUndoNotice {
    private class Pending(val owner: EditorState, val actionId: Long, val count: Int)

    private var pending by mutableStateOf<Pending?>(null)

    /** Records the edit that made [editor]'s latest history entry, if it changed [count] > 1 components. */
    fun record(editor: EditorState, count: Int, previousActionId: Long?) {
        val latest = editor.undoHistory.firstOrNull()?.id ?: return
        if (count < 2 || latest == previousActionId) return
        pending = Pending(editor, latest, count)
    }

    /** How many components the undoable edit changed, while it is still the latest edit of [editor]. */
    fun undoableCount(editor: EditorState): Int? = pending?.takeIf {
        it.owner === editor && editor.undoHistory.firstOrNull()?.id == it.actionId && editor.documentWritable && editor.interactionsEnabled
    }?.count

    /** The identity the UI keys its timeout on. */
    val token: Any? get() = pending

    fun dismiss() { pending = null }

    fun undo(editor: EditorState): Boolean {
        val edit = pending ?: return false
        if (undoableCount(editor) == null) { pending = null; return false }
        pending = null
        editor.travelHistory(EditorHistoryDirection.UNDO, edit.actionId)
        return true
    }

    companion object {
        /** "Changed N components.", the status line after an edit of several components. */
        fun status(count: Int) = LocalizedText("inspector_multi_edit_status", mapOf("count" to count.toString()), count.toLong())
    }
}

/**
 * Undo beside the status line's "Changed N components." for a few seconds, like the Undo after Reset: it stays for the
 * time the accessibility settings recommend for a control and is announced when it appears. With [timed] false (in the
 * open inspector sheet, which covers the status line) it stays until the next edit; the status line's timeout then
 * starts only once the sheet has closed and the Undo there can be seen and reached.
 */
@Composable
internal fun MultiEditUndoAction(state: EditorState, notice: MultiEditUndoNotice, timed: Boolean = true) {
    val count = notice.undoableCount(state) ?: return
    val token = notice.token
    val strings = rememberLocalizer()
    val accessibility = LocalAccessibilityManager.current
    if (timed) LaunchedEffect(token, accessibility) {
        delay(accessibility?.calculateRecommendedTimeoutMillis(ResetUndoMillis, containsIcons = false, containsText = true,
            containsControls = true) ?: ResetUndoMillis)
        if (notice.token === token) notice.dismiss()
    }
    val description = strings.plural("inspector_multi_edit_undo", count.toLong(), "count" to count)
    TextButton(onClick = { notice.undo(state) }, contentPadding = PaddingValues(horizontal = 10.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag("inspector-multi-edit-undo").semantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        }) {
        Text(strings(Messages.ActionUndo), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * "Changed N components." with its Undo inside the open inspector sheet, where a phone makes multi-component edits and
 * the scrim hides the status line (C7). It stays while the sheet is open.
 */
@Composable
internal fun MultiEditUndoBanner(state: EditorState, notice: MultiEditUndoNotice, modifier: Modifier = Modifier) {
    val count = notice.undoableCount(state) ?: return
    val strings = rememberLocalizer()
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(strings(MultiEditUndoNotice.status(count)), style = MaterialTheme.typography.bodySmall, color = ChromeColors.Muted,
            modifier = Modifier.weight(1f))
        MultiEditUndoAction(state, notice, timed = false)
    }
}
