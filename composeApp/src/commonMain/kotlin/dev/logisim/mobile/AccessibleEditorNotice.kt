package dev.logisim.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.localization.localizedString

/**
 * The host supplies action results and diagnostics here, never clock ticks or signal samples. The notice sits under
 * the control that produced it and scrolls into view when it changes after this call is first composed, so a call
 * site stays composed with a null notice rather than appearing with it. Terminal results are announced politely; progress
 * such as "Synthesizing…" is shown but neither announced nor scrolled to, so TalkBack is not flooded and the panel does
 * not jump. Failures use the error color.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccessibleEditorNotice(
    notice: LocalizedText?,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    announce: Boolean = true,
    selectable: Boolean = false,
) {
    val requester = remember { BringIntoViewRequester() }
    // The value present when this call site is composed was already there: a sheet reopening on a notice the tool kept,
    // or a lazy-list item scrolling back into view with its result. Only a later change is revealed, so neither
    // case scrolls the panel. Callers keep this composed while the notice is null, so a first result still counts.
    val reveal = remember { NoticeRevealGate() }
    LaunchedEffect(notice, isError, announce) {
        if (!reveal.changedSinceMount()) return@LaunchedEffect
        // Only results are revealed: progress lines change often and must not pull the panel away from the user.
        if (notice == null || !announce) return@LaunchedEffect
        // Wait for the new line to be placed before asking the enclosing scroll container to reveal it.
        withFrameNanos { }
        requester.bringIntoView()
    }
    // Inside a tool sheet, the sheet's own banner leaves this result to the line under its control (C74).
    LocalToolSheetInlineNotices.current?.Register(notice)
    if (notice == null) return
    val text = localizedString(notice)
    val content = @Composable {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else LocalContentColor.current,
            modifier = modifier.bringIntoViewRequester(requester)
                .semantics { if (announce) liveRegion = LiveRegionMode.Polite },
        )
    }
    if (selectable) SelectionContainer { content() } else content()
}

/** Skips the first run of the reveal effect, which only reports what was on screen when the notice was composed. */
private class NoticeRevealGate {
    private var mounted = false
    fun changedSinceMount(): Boolean = mounted.also { mounted = true }
}

internal enum class NoticeKind { PROGRESS, RESULT, ERROR }

/** A tool's own result line with its severity, kept separate from the editor status bar behind the sheet. */
internal data class ToolNotice(val text: LocalizedText, val kind: NoticeKind = NoticeKind.RESULT)

internal fun LocalizedText.asResult() = ToolNotice(this, NoticeKind.RESULT)
internal fun LocalizedText.asError() = ToolNotice(this, NoticeKind.ERROR)
internal fun LocalizedText.asProgress() = ToolNotice(this, NoticeKind.PROGRESS)

@Composable
internal fun ToolNoticeText(notice: ToolNotice?, modifier: Modifier = Modifier, selectable: Boolean = false) =
    AccessibleEditorNotice(notice?.text, modifier, isError = notice?.kind == NoticeKind.ERROR,
        announce = notice?.kind != NoticeKind.PROGRESS, selectable = selectable)
