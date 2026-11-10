package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.systemAppLanguage

internal fun androidDocumentText(key: String, vararg arguments: Pair<String, Any?>): String =
    message(key, *arguments).resolve(systemAppLanguage())

internal fun androidDocumentNotice(key: String, vararg arguments: Pair<String, Any?>): LocalizedText =
    message(key, *arguments)
