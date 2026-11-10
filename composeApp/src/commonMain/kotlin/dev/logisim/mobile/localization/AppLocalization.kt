package dev.logisim.mobile.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.systemAppLanguage

val LocalAppLanguage = staticCompositionLocalOf { systemAppLanguage() }

@Composable
fun AppLocaleProvider(language: AppLanguage, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalLayoutDirection provides LayoutDirection.Ltr,
        content = content,
    )
}

@Composable
fun localizedString(text: LocalizedText): String = text.resolve(LocalAppLanguage.current)

@Composable
fun localizedString(key: String, vararg arguments: Pair<String, Any?>): String =
    localizedString(message(key, *arguments))

@Composable
fun localizedPlural(key: String, quantity: Long, vararg arguments: Pair<String, Any?>): String =
    localizedString(LocalizedText(key, arguments.associate { it.first to it.second.toString() }, quantity))

class Localizer(val language: AppLanguage) {
    operator fun invoke(key: String, vararg arguments: Pair<String, Any?>): String =
        message(key, *arguments).resolve(language)

    operator fun invoke(text: LocalizedText): String = text.resolve(language)

    fun plural(key: String, quantity: Long, vararg arguments: Pair<String, Any?>): String =
        LocalizedText(key, arguments.associate { it.first to it.second.toString() }, quantity).resolve(language)
}

@Composable
fun rememberLocalizer(): Localizer {
    val language = LocalAppLanguage.current
    return remember(language) { Localizer(language) }
}
