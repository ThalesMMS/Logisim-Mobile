package dev.logisim.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.localization.localizedString

/** A result the open tool reported to the editor status line, which the tool's sheet hides (C74). */
internal data class ToolSheetNotice(val toolId: String, val text: LocalizedText, val serial: Long)

/**
 * The notices a tool sheet's panels already show under the control that produced them (AccessibleEditorNotice), so
 * the sheet does not show and announce the same result a second time.
 */
internal class ToolSheetInlineNotices {
    private val shown = mutableStateMapOf<Any, LocalizedText>()
    operator fun contains(text: LocalizedText): Boolean = text in shown.values

    @Composable
    fun Register(notice: LocalizedText?) {
        DisposableEffect(this, notice) {
            val token = Any()
            if (notice != null) shown[token] = notice
            onDispose { shown.remove(token) }
        }
    }
}

internal val LocalToolSheetInlineNotices = staticCompositionLocalOf<ToolSheetInlineNotices?> { null }

/**
 * The open tool's latest reported result, for a dialog the tool opens over its sheet (such as the Memory editor's
 * exchange dialog), which hides the sheet's banner; null outside a tool sheet.
 */
internal val LocalToolSheetNotice = compositionLocalOf<ToolSheetNotice?> { null }

/**
 * Shows [notice] at the top of the tool sheet, where the status line behind the sheet cannot be seen, and announces
 * it politely. A notice the panel already shows inline is left to the panel, and stays with it once that line goes, so
 * it is never announced twice. It waits one frame so the panel's own line, set by the same tap, is registered first.
 */
@Composable
internal fun ToolSheetNoticeBanner(notice: ToolSheetNotice?, inline: ToolSheetInlineNotices, onDismiss: () -> Unit) {
    var pending by remember { mutableStateOf<ToolSheetNotice?>(null) }
    var shownInline by remember { mutableStateOf<ToolSheetNotice?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) withFrameNanos { }
        pending = notice
    }
    LaunchedEffect(notice, inline) {
        if (notice != null) snapshotFlow { notice.text in inline }.collect { if (it) shownInline = notice }
    }
    val shown = pending?.takeIf { it == notice && it != shownInline && it.text !in inline } ?: return
    val dismiss = localizedString("tool_sheet_notice_dismiss")
    Surface(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("editor-tool-sheet-notice"), shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A new node per report, so the same result reported again is announced again.
            key(shown.serial) {
                Text(localizedString(shown.text), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = dismiss }.testTag("editor-tool-sheet-notice-dismiss"),
                contentAlignment = Alignment.Center) {
                Text("×", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}
