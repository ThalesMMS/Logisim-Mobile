package dev.logisim.mobile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.delay

/** How long Undo stays beside "Simulation reset." in the status line, before the accessibility timing settings. */
internal const val ResetUndoMillis = 6_000L

/**
 * Undo beside the status line's "Simulation reset." for a few seconds after Reset (C69), in the bottom bar's own row
 * so it never covers the simulation controls. As Material snackbars do, it stays for the time the accessibility
 * settings recommend for a control and is announced when it appears. It disappears on its own, or as soon as anything
 * changes the simulation.
 */
@Composable
internal fun SimulationResetUndoAction(state: EditorState) {
    val undoable = state.undoableReset ?: return
    val strings = rememberLocalizer()
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(undoable, accessibility) {
        delay(accessibility?.calculateRecommendedTimeoutMillis(ResetUndoMillis, containsIcons = false, containsText = true,
            containsControls = true) ?: ResetUndoMillis)
        if (state.undoableReset === undoable) state.dismissResetUndo()
    }
    val description = strings("a11y_simulation_reset_undo")
    TextButton(onClick = { state.undoReset() }, contentPadding = PaddingValues(horizontal = 10.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag("simulation-reset-undo").semantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        }) {
        Text(strings(Messages.ActionUndo), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
