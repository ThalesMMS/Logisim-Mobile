package dev.logisim.mobile

import dev.logisim.mobile.core.AttributeKey
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.Direction
import dev.logisim.mobile.core.localization.*

enum class PropertyInputKind { BOOLEAN, DIRECTION, INTEGER, DECIMAL, TEXT }

data class PropertyEditorField(
    val name: String,
    val kind: PropertyInputKind,
    val value: String?,
    val readOnly: Boolean = false,
) {
    val mixed: Boolean get() = value == null
}

sealed interface PropertyEditResult {
    data class Valid(val updates: Map<String, String>) : PropertyEditResult
    data class Invalid(val notice: LocalizedText) : PropertyEditResult {
        constructor(message: String) : this(LocalizedText.literal(message))
        val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
    }
}

object PropertyEditorModel {
    /**
     * The attribute table of [components]. With [darkTheme], a Text color shows as the canvas draws it, as
     * TextColorAttribute.toDisplayString does; [validate] stores the inverse of the typed color (#340).
     */
    fun fields(registry: ComponentRegistry, components: List<CircuitComponent>, darkTheme: Boolean): List<PropertyEditorField> =
        fields(registry, components).map { field ->
            if (darkTheme && field.isThemeTextColor(components)) field.copy(value = field.value?.let { ThemeTextColor.display(it, true) }) else field
        }

    private fun PropertyEditorField.isThemeTextColor(components: List<CircuitComponent>) =
        name == TEXT_COLOR && !readOnly && components.all { it.kind == ComponentKind.TEXT }

    private const val TEXT_COLOR = "color"

    fun fields(registry: ComponentRegistry, components: List<CircuitComponent>): List<PropertyEditorField> {
        if (components.isEmpty()) return emptyList()
        val definitions = components.map { registry.definition(it.kind) }
        val firstKeys = definitions.first()?.schema?.keys.orEmpty()
        val editable = firstKeys.filter { key ->
            key.name != "label" && definitions.all { definition ->
                definition?.schema?.keys?.any { it.name == key.name && inputKind(it) == inputKind(key) } == true
            }
        }
        val editableNames = editable.map { it.name }.toSet()
        val known = editable.map { key ->
            val values = components.mapIndexed { index, component ->
                component.attributes[key.name] ?: definitions[index]!!.schema.defaults.getValue(key.name)
            }
            PropertyEditorField(key.name, inputKind(key), values.distinct().singleOrNull())
        }
        val remainingNames = components.flatMap { it.attributes.keys }.distinct().sorted()
            .filter { it != "label" && it !in editableNames }
        return known + remainingNames.map { name ->
            val values = components.map { it.attributes[name] }
            PropertyEditorField(name, PropertyInputKind.TEXT, values.distinct().singleOrNull(), readOnly = true)
        }
    }

    fun validate(
        registry: ComponentRegistry,
        components: List<CircuitComponent>,
        name: String,
        typed: String,
        darkTheme: Boolean = false,
    ): PropertyEditResult {
        if (components.isEmpty()) return PropertyEditResult.Invalid(message(Messages.InspectorSelectRequired))
        val field = fields(registry, components).firstOrNull { it.name == name }
        if (field == null || field.readOnly) return PropertyEditResult.Invalid(message(Messages.InspectorNotEditable, "name" to name))
        val raw = if (darkTheme && field.isThemeTextColor(components)) ThemeTextColor.store(typed, field.value, true) else typed
        val key = registry.requireDefinition(components.first().kind).schema.keys.first { it.name == name }
        val value = try { normalize(key, raw) } catch (_: IllegalArgumentException) { null }
            ?: return PropertyEditResult.Invalid(message(Messages.InspectorValueInvalid, "name" to name, "raw" to raw))
        return try {
            components.forEach { registry.reconfigure(it, mapOf(name to value)) }
            PropertyEditResult.Valid(mapOf(name to value))
        } catch (error: IllegalArgumentException) {
            PropertyEditResult.Invalid(error.localizedDescription(message(Messages.InspectorValueInvalidShort, "name" to name)))
        }
    }

    fun unregisteredKinds(registry: ComponentRegistry, components: List<CircuitComponent>): List<String> =
        components.filter { registry.definition(it.kind) == null }.map { it.kind.name }.distinct()

    private fun inputKind(key: AttributeKey<*>): PropertyInputKind = when (key.default) {
        is Boolean -> PropertyInputKind.BOOLEAN
        is Direction -> PropertyInputKind.DIRECTION
        is Int, is Long -> PropertyInputKind.INTEGER
        is Float, is Double -> PropertyInputKind.DECIMAL
        else -> PropertyInputKind.TEXT
    }

    private fun <T> normalize(key: AttributeKey<T>, raw: String): String? {
        val parsed = key.parse(raw) ?: return null
        return if (key.validate(parsed)) key.format(parsed) else null
    }
}

internal fun PropertyEditorField.localizedValue(): LocalizedText = when {
    value == null -> message(Messages.InspectorMixedValues)
    kind == PropertyInputKind.BOOLEAN && value == "true" -> message(Messages.InspectorEnabled)
    kind == PropertyInputKind.BOOLEAN && value == "false" -> message(Messages.InspectorDisabled)
    kind == PropertyInputKind.DIRECTION -> when (value) {
        "east" -> message(Messages.DirectionEast)
        "north" -> message(Messages.DirectionNorth)
        "west" -> message(Messages.DirectionWest)
        "south" -> message(Messages.DirectionSouth)
        else -> LocalizedText.literal(value)
    }
    else -> LocalizedText.literal(value)
}
