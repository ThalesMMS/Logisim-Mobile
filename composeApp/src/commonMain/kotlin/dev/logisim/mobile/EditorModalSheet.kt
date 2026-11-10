package dev.logisim.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.logisim.mobile.localization.localizedString
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    sheetGesturesEnabled: Boolean = true,
    showDragHandle: Boolean = sheetGesturesEnabled,
    inspector: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scaffold = rememberBottomSheetScaffoldState(bottomSheetState = state)
    val scope = rememberCoroutineScope()
    val onDismiss by rememberUpdatedState(onDismissRequest)
    val closeLabel = localizedString("a11y_sheet_close")
    val pane = localizedString("a11y_sheet_pane")
    val scrimColor = BottomSheetDefaults.ScrimColor
    val scrimAlpha by animateFloatAsState(if (state.targetValue == SheetValue.Hidden) 0f else 1f)
    var dismissalDelivered by remember { mutableStateOf(false) }
    val scrollGuard = remember { EditorSheetScrollGuard() }

    fun finishDismissal() {
        if (dismissalDelivered) return
        dismissalDelivered = true
        onDismiss()
    }

    fun dismiss() {
        if (state.currentValue == SheetValue.Hidden) finishDismissal()
        else scope.launch {
            state.hide()
            if (!state.isVisible) finishDismissal()
        }
    }
    val dismissSheet by rememberUpdatedState(::dismiss)

    LaunchedEffect(state) {
        snapshotFlow { state.hasExpandedState }.first { it }
        state.show()
    }
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }.dropWhile { it == SheetValue.Hidden }.first { it == SheetValue.Hidden }
        finishDismissal()
    }

    Dialog(onDismissRequest = ::dismiss, properties = editorSheetDialogProperties()) {
        BottomSheetScaffold(
            modifier = Modifier.fillMaxSize().imePadding().clipToBounds().exportEditorTestTags()
                .semantics { isTraversalGroup = true },
            scaffoldState = scaffold,
            sheetPeekHeight = 0.dp,
            sheetContainerColor = containerColor,
            sheetTonalElevation = BottomSheetDefaults.Elevation,
            sheetShadowElevation = 0.dp,
            sheetDragHandle = null,
            sheetSwipeEnabled = sheetGesturesEnabled && state.isVisible,
            containerColor = Color.Transparent,
            sheetContent = {
                val topOffset = if (state.hasExpandedState) state.requireOffset().toInt().coerceAtLeast(0) else 0
                Column(modifier.fillMaxWidth().semantics { paneTitle = pane; traversalIndex = 0f }
                    .nestedScroll(scrollGuard)
                    .pointerInput(scrollGuard) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                scrollGuard.pointerDown = event.changes.any { it.pressed }
                            }
                        }
                    }
                    .consumeWindowInsets(WindowInsets(top = topOffset))
                    .windowInsetsPadding(BottomSheetDefaults.windowInsets)
                    // The default sheet insets are vertical only; in landscape a side cutout or
                    // navigation bar would cover the start of every row (#239, layout12).
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                    // A short landscape window with the soft keyboard open, or with a large font, leaves
                    // too little room for the focused row or the Explorer search under a 48 dp handle;
                    // Close, Back and the scrim still dismiss (#239, 568 x 320 dp at 200%).
                    val density = LocalDensity.current
                    val shortWindow = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } < 480.dp
                    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
                    val largeFont = density.fontScale > 1.3f
                    if (showDragHandle && !(shortWindow && (keyboardOpen || largeFont))) EditorSheetDragHandle(inspector, ::dismiss)
                    content()
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(scrimColor.copy(alpha = scrimColor.alpha * scrimAlpha.coerceIn(0f, 1f)))
                .testTag("editor-sheet-scrim")
                .pointerInput(Unit) { detectTapGestures { dismissSheet() } }
                .semantics {
                    contentDescription = closeLabel
                    traversalIndex = 1f
                    role = Role.Button
                    onClick { dismissSheet(); true }
                })
        }
    }
}

internal expect fun editorSheetDialogProperties(): DialogProperties

/**
 * Keeps scrolls that no finger is driving from dragging the sheet. Compose dispatches
 * accessibility scroll actions (TalkBack swipes past the edge of a list) and mouse wheels as
 * [NestedScrollSource.UserInput], so the sheet's own connection would move by the leftover delta
 * and, with no fling to settle it, stay between anchors. A later anchor change, such as the IME
 * opening, then snapped it to Hidden and closed the sheet (#309).
 */
internal class EditorSheetScrollGuard : NestedScrollConnection {
    var pointerDown = false

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && !pointerDown) available else Offset.Zero
}
