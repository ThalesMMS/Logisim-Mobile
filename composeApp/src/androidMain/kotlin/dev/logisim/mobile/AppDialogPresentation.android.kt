package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

@Composable
internal actual fun Modifier.appDialogViewport(onDismissRequest: () -> Unit, properties: DialogProperties): Modifier = this

internal actual fun appDialogProperties(properties: DialogProperties): DialogProperties = properties

internal actual fun editorSheetDialogProperties() = DialogProperties(
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
    scrimAlpha = 0f,
)
