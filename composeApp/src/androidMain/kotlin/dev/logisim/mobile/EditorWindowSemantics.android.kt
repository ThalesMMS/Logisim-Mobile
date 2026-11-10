package dev.logisim.mobile

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.exportEditorTestTags(): Modifier = semantics { testTagsAsResourceId = true }

internal actual val editorMenusTakeFocus: Boolean = true
