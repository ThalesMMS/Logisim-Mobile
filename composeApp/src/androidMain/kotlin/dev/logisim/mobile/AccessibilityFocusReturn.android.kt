package dev.logisim.mobile

import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.delay

@Composable
internal actual fun rememberAccessibilityFocusReturn(): AccessibilityFocusReturn {
    val view = LocalView.current
    return remember(view) { AccessibilityFocusReturn { tag -> moveAccessibilityFocus(view, tag) } }
}

private const val WINDOW_FOCUS_TIMEOUT_MS = 2_000L
private const val SETTLE_MS = 1_500L
private const val POLL_MS = 100L
private const val MAX_REQUESTS = 4

private suspend fun moveAccessibilityFocus(view: View, tag: String): Boolean {
    val manager = view.context.getSystemService(AccessibilityManager::class.java) ?: return false
    if (!manager.isEnabled || !manager.isTouchExplorationEnabled) return false
    var waited = 0L
    while (!view.hasWindowFocus() && waited < WINDOW_FOCUS_TIMEOUT_MS) { delay(POLL_MS); waited += POLL_MS }
    if (!view.isAttachedToWindow) return false
    // The screen reader moves its focus to the window's first node once it handles the window
    // change, which can come after the first request. Request again while that focus is lost.
    var requests = 0
    var settled = 0L
    var focused = false
    while (settled < SETTLE_MS) {
        focused = isAccessibilityFocused(view, tag)
        if (!focused && requests < MAX_REQUESTS) { requestAccessibilityFocus(view, tag); requests++ }
        delay(POLL_MS)
        settled += POLL_MS
    }
    return focused || isAccessibilityFocused(view, tag)
}

private fun taggedNodeId(view: View, tag: String): Int? {
    val owner = (view as? RootForTest)?.semanticsOwner ?: return null
    fun find(node: SemanticsNode): SemanticsNode? =
        if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) node
        else node.children.firstNotNullOfOrNull(::find)
    return find(owner.rootSemanticsNode)?.id
}

private fun requestAccessibilityFocus(view: View, tag: String) {
    val id = taggedNodeId(view, tag) ?: return
    view.accessibilityNodeProvider?.performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
}

private fun isAccessibilityFocused(view: View, tag: String): Boolean {
    val id = taggedNodeId(view, tag) ?: return false
    return view.accessibilityNodeProvider?.createAccessibilityNodeInfo(id)?.isAccessibilityFocused == true
}
