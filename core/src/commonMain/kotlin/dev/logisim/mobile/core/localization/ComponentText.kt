package dev.logisim.mobile.core.localization

import dev.logisim.mobile.core.ComponentKind

/** File-format identifiers remain stable while the UI label follows the chosen language. */
fun ComponentKind.localizedLabel(): LocalizedText =
    LocalizedText("component_${name.lowercase()}", fallback = name)
