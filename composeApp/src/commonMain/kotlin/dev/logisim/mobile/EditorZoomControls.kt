package dev.logisim.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** The zoom range the editor offers, in percent: 100% is one dp per circuit unit. */
internal const val ZoomMinPercent = 5f
internal const val ZoomMaxPercent = MaxZoomPerDp * 100f

/** The zoom of [scale] pixels per unit on a screen of [density] pixels per dp, in percent. */
internal fun zoomPercentOf(scale: Float, density: Float): Float = scale / density * 100f

/**
 * The zoom at [position] (0 to 1) of the logarithmic zoom track: [ZoomMinPercent] at 0, [ZoomMaxPercent] at 1, so each
 * doubling takes the same length and 50%, 100% and 200% sit near the middle instead of in its first tenth.
 */
internal fun zoomSliderPercent(position: Float): Float =
    ZoomMinPercent * (ZoomMaxPercent / ZoomMinPercent).pow(position.coerceIn(0f, 1f))

/** Where [percent] sits on the logarithmic zoom track, clamped to it. */
internal fun zoomSliderPosition(percent: Float): Float =
    if (percent.isNaN() || percent <= ZoomMinPercent) 0f
    else (ln(percent / ZoomMinPercent) / ln(ZoomMaxPercent / ZoomMinPercent)).coerceIn(0f, 1f)

/** Zoom steps of the View menu and the popover, as Evolution's header buttons (1.25 per step here). */
internal const val ZoomStep = 1.25f

/**
 * The status bar zoom: the percentage opens Evolution's zoom popover (UnifiedHeaderBar.showZoomMenu), with a slider
 * that zooms while it moves, Zoom in and out, 100%, Fit, Fit selection, 50% and 200%. Custom… opens the exact entry,
 * so the soft keyboard opens only once the user chooses to type. [stepButtons] adds − and + beside it.
 */
@Composable
internal fun EditorZoomControls(
    state: EditorState,
    hasSelection: Boolean,
    stepButtons: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCustom: () -> Unit,
) {
    val strings = rememberLocalizer()
    val percent = state.zoomPercent.roundToInt()
    val percentText = strings("zoom_preset_percent", "percent" to percent)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (stepButtons) ChromeButton(EditorToolbarAction("zoom-out", "−", strings(Messages.A11yZoomOut), icon = EditorIcon.ZOOM_OUT) {
            state.zoomBy(1f / ZoomStep)
        }, size = 32.dp)
        Box {
            ChromeButton(EditorToolbarAction("zoom-exact", percentText, strings(Messages.A11yZoomExact, "percent" to percent)) {
                onExpandedChange(true)
            }, size = 32.dp)
            DropdownMenu(expanded, onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = editorMenusTakeFocus), containerColor = chromeMenuContainerColor(),
                shape = RoundedCornerShape(8.dp), modifier = Modifier.exportEditorTestTags().testTag("editor-zoom-menu")) {
                val sliderLabel = strings(Messages.ZoomSlider)
                Row(Modifier.widthIn(min = 240.dp).padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = zoomSliderPosition(state.zoomPercent),
                        onValueChange = { state.zoomToPercent(zoomSliderPercent(it)) },
                        modifier = Modifier.weight(1f).heightIn(min = ChromeTouchTarget).testTag("editor-zoom-menu-slider").semantics {
                            contentDescription = sliderLabel
                            // The track position is not the zoom; read the percentage instead.
                            stateDescription = percentText
                        },
                    )
                    Text(percentText, fontSize = 13.sp, textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(min = 52.dp).padding(start = 8.dp).clearAndSetSemantics {})
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = ChromeColors.Divider)
                ChromeMenuItems(listOf(
                    EditorToolbarAction("zoom-popover-in", strings(Messages.ActionZoomIn), strings(Messages.A11yZoomIn),
                        icon = EditorIcon.ZOOM_IN) { state.zoomBy(ZoomStep) },
                    EditorToolbarAction("zoom-popover-out", strings(Messages.ActionZoomOut), strings(Messages.A11yZoomOut),
                        icon = EditorIcon.ZOOM_OUT) { state.zoomBy(1f / ZoomStep) },
                    EditorToolbarAction("zoom-popover-100", strings("zoom_reset")) { state.zoomToPercent(100f) },
                    EditorToolbarAction("zoom-popover-fit", strings(Messages.ActionFit), strings(Messages.A11yFitCircuit),
                        icon = EditorIcon.FIT, onClick = state::fitViewport),
                    EditorToolbarAction("zoom-popover-fit-selection", strings(Messages.ActionFitSelection),
                        strings(Messages.A11yFitSelection), enabled = hasSelection, onClick = state::fitSelected),
                    EditorToolbarAction("zoom-popover-50", strings("zoom_preset_percent", "percent" to 50)) { state.zoomToPercent(50f) },
                    EditorToolbarAction("zoom-popover-200", strings("zoom_preset_percent", "percent" to 200)) { state.zoomToPercent(200f) },
                    EditorToolbarAction("zoom-popover-separator", "", separator = true) {},
                    EditorToolbarAction("zoom-popover-custom", strings("zoom_custom"), onClick = onCustom),
                )) { onExpandedChange(false) }
            }
        }
        if (stepButtons) ChromeButton(EditorToolbarAction("zoom-in", "+", strings(Messages.A11yZoomIn), icon = EditorIcon.ZOOM_IN) {
            state.zoomBy(ZoomStep)
        }, size = 32.dp)
    }
}
