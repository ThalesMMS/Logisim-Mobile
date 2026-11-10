package dev.logisim.mobile

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit

/** Circuit labels share the circuit's coordinate system; editor panels retain system font scaling. */
internal fun Density.worldTextSize(size: Float): TextUnit = size.toSp()
