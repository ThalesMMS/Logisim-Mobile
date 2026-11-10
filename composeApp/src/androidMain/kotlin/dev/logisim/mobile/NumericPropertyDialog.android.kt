package dev.logisim.mobile

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal actual fun NumericPropertyDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    field: @Composable (Modifier) -> Unit,
    validationMessage: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    val dismiss = rememberUpdatedState(onDismissRequest)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            // The dialog owns the whole viewport so it can measure around the IME.
            Spacer(Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures { dismiss.value() }
            })
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                val compact = maxHeight < 200.dp && maxWidth >= 360.dp
                Surface(
                    modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                    shape = AlertDialogDefaults.shape,
                    color = AlertDialogDefaults.containerColor,
                    contentColor = AlertDialogDefaults.textContentColor,
                    tonalElevation = AlertDialogDefaults.TonalElevation,
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(if (compact) 8.dp else 24.dp)) {
                        ProvideTextStyle(if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall) {
                            title()
                        }
                        // Reflow the same children to keep the field focused when the keyboard opens or the window rotates.
                        Layout(
                            modifier = Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 16.dp),
                            content = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    field(Modifier.fillMaxWidth())
                                    validationMessage()
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    dismissButton()
                                    confirmButton()
                                }
                            },
                        ) { (input, actions), constraints ->
                            val width = constraints.maxWidth
                            val gap = if (compact) 8.dp.roundToPx() else 24.dp.roundToPx()
                            val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                            val actionWidth = if (compact) width * 2 / 3 else width
                            val buttons = actions.measure(childConstraints.copy(maxWidth = actionWidth))
                            val fieldWidth = if (compact) width - buttons.width - gap else width
                            val value = input.measure(childConstraints.copy(minWidth = fieldWidth, maxWidth = fieldWidth))
                            val height = if (compact) maxOf(value.height, buttons.height) else value.height + gap + buttons.height
                            layout(width, height) {
                                value.placeRelative(0, 0)
                                buttons.placeRelative(width - buttons.width, if (compact) 0 else value.height + gap)
                            }
                        }
                    }
                }
            }
        }
    }
}
