package dev.logisim.mobile.core.localization

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.math.absoluteValue

/** English is the fallback for an unsupported device or requested language. */
enum class AppLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"),
    PORTUGUESE("pt", "Português"),
    GERMAN("de", "Deutsch"),
    GREEK("el", "Ελληνικά"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    ITALIAN("it", "Italiano"),
    JAPANESE("ja", "日本語"),
    DUTCH("nl", "Nederlands"),
    POLISH("pl", "Polski"),
    RUSSIAN("ru", "Русский"),
    TURKISH("tr", "Türkçe"),
    CHINESE("zh", "中文");

    val isAvailable: Boolean get() = this == ENGLISH || this == PORTUGUESE || tag in publishedTranslationTags

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            val languageTag = tag?.substringBefore('-')?.substringBefore('_')?.lowercase()
            return entries.firstOrNull { it.tag == languageTag && it.isAvailable } ?: ENGLISH
        }
    }
}

/** A message identity and its data, independent of the screen and current language. */
data class LocalizedText(
    val key: String,
    val arguments: Map<String, String> = emptyMap(),
    val quantity: Long? = null,
    val fallback: String? = null,
    val localizedArguments: Map<String, LocalizedText> = emptyMap(),
    private val joinedParts: List<LocalizedText>? = null,
    private val joinSeparator: String = "\n",
) {
    fun resolve(language: AppLanguage = AppLanguage.ENGLISH): String {
        joinedParts?.let { return it.joinToString(joinSeparator) { part -> part.resolve(language) } }
        val message = appMessageCatalog[key] ?: return fallback ?: key
        val translated = additionalTranslation(language.tag, key)
        val template = when {
            language == AppLanguage.PORTUGUESE -> message.portuguese.resolve(language, quantity)
            translated != null -> translated.resolve(language, quantity)
            else -> message.english.resolve(AppLanguage.ENGLISH, quantity)
        }
        val supplied = arguments + localizedArguments.mapValues { it.value.resolve(language) }
        val values = if (quantity == null) supplied else supplied + ("count" to quantity.toString())
        return formatMessage(template, values)
    }

    companion object {
        fun literal(value: String): LocalizedText = LocalizedText("", fallback = value)

        fun join(parts: List<LocalizedText>, separator: String = "\n"): LocalizedText =
            LocalizedText("", joinedParts = parts.toList(), joinSeparator = separator)
    }
}

internal data class MessageForms(
    val other: String,
    val one: String? = null,
    val few: String? = null,
    val many: String? = null,
) {
    fun resolve(language: AppLanguage, quantity: Long?): String {
        if (quantity == null || one == null) return other
        // The editor and diagnostics pass integer quantities. Modulo is taken before absoluteValue
        // so even Long.MIN_VALUE is safe.
        val lastDigit = (quantity % 10).absoluteValue
        val lastTwoDigits = (quantity % 100).absoluteValue
        val isOne = quantity == 1L || quantity == -1L
        val isMillionMultiple = quantity != 0L && quantity % 1_000_000 == 0L
        return when (language) {
            AppLanguage.FRENCH -> when {
                quantity == 0L || isOne -> one
                isMillionMultiple -> many ?: other
                else -> other
            }
            AppLanguage.SPANISH, AppLanguage.ITALIAN -> when {
                isOne -> one
                isMillionMultiple -> many ?: other
                else -> other
            }
            AppLanguage.POLISH -> when {
                isOne -> one
                lastDigit in 2L..4L && lastTwoDigits !in 12L..14L -> few ?: other
                else -> many ?: other
            }
            AppLanguage.RUSSIAN -> when {
                lastDigit == 1L && lastTwoDigits != 11L -> one
                lastDigit in 2L..4L && lastTwoDigits !in 12L..14L -> few ?: other
                else -> many ?: other
            }
            AppLanguage.ENGLISH, AppLanguage.PORTUGUESE -> if (quantity == 1L) one else other
            else -> if (isOne) one else other
        }
    }
}
internal data class MessageTemplate(val english: MessageForms, val portuguese: MessageForms)

/** Substitute once so imported names containing braces are kept as user data. */
fun formatMessage(template: String, arguments: Map<String, String>): String =
    Regex("\\{([a-z][a-zA-Z0-9_]*)\\}").replace(template) { match ->
        arguments[match.groupValues[1]] ?: match.value
    }

fun message(key: String, vararg arguments: Pair<String, Any?>): LocalizedText =
    LocalizedText(
        key,
        arguments.filter { it.second !is LocalizedText }.associate { it.first to it.second.toString() },
        localizedArguments = arguments.mapNotNull { (name, value) -> (value as? LocalizedText)?.let { name to it } }.toMap(),
    )

interface LocalizedFailure { val text: LocalizedText }

open class LocalizedArgumentException(override val text: LocalizedText) : IllegalArgumentException(text.resolve()), LocalizedFailure
open class LocalizedStateException(override val text: LocalizedText) : IllegalStateException(text.resolve()), LocalizedFailure

@OptIn(ExperimentalContracts::class)
inline fun requireLocalized(value: Boolean, lazyMessage: () -> LocalizedText) {
    contract { returns() implies value }
    if (!value) throw LocalizedArgumentException(lazyMessage())
}

@OptIn(ExperimentalContracts::class)
inline fun <T : Any> requireNotNullLocalized(value: T?, lazyMessage: () -> LocalizedText): T {
    contract { returns() implies (value != null) }
    if (value == null) throw LocalizedArgumentException(lazyMessage())
    return value
}

@OptIn(ExperimentalContracts::class)
inline fun checkLocalized(value: Boolean, lazyMessage: () -> LocalizedText) {
    contract { returns() implies value }
    if (!value) throw LocalizedStateException(lazyMessage())
}

fun Throwable.localizedDescription(fallback: LocalizedText): LocalizedText = when (this) {
    is LocalizedFailure -> text
    else -> message?.let(LocalizedText::literal) ?: fallback
}
