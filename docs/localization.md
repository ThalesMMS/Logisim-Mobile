# Contribute translations

App messages have stable keys. Document labels, circuit names, `.circ` format identifiers, and user-entered text remain literal project data.

## Add a message

1. Create or edit the file for the feature under `localization/messages/`.
2. Use an English key with lowercase letters and underscores. Keep the key when changing the text.
3. Include `en` and `pt`. English is the fallback language. Portuguese covers `pt`, `pt-BR`, and `pt-PT`.
4. Use named parameters in braces, such as `{name}`. Use the same parameter names in both translations.
5. Run `python3 scripts/generate-localization.py`.
6. Run `python3 scripts/generate-localization.py --check` and `python3 scripts/test-localization.py` before committing.

```json
{
  "open_circuit": {
    "en": "Open circuit {name}",
    "pt": "Abrir circuito {name}",
    "description": "Accessibility description for a circuit in the project tree."
  }
}
```

The generator produces `GeneratedMessages.kt`, `GeneratedTranslations.kt`, and Compose resource files under `composeApp/src/commonMain/composeResources/values*`. Edit the source catalogs to change these files. The Kotlin catalog and Compose resources share the same source and key names.

## Add a language from the Evolution reference

The other languages in revision `d58a61e0` use separate files under `localization/translations/`. Each file maps an existing key to the translated text. Use the plural categories listed in the generator's `REFERENCE_PLURALS` and keep the same named parameters as the English text. The initial menu labels were adapted from this Evolution revision's `src/main/resources/resources/logisim/strings/` files.

To reuse a label, check that the command has the same effect on desktop and mobile and record the source key in `localization/evolution-shared.json`. Run `python3 scripts/seed-evolution-translations.py --desktop /path/to/logisim-evolution` for a dry run and add `--apply` to fill catalog gaps. The script reads the pinned revision, requires matching English text in both catalogs, and preserves existing translations. For text with a single parameter, it replaces `%s` or `%1$s` with the name already used in Mobile. Missing or ambiguous values and values needing review stay pending. Run the generator after applying changes.

Incomplete catalogs are available for testing through an explicit `AppLanguage`. A missing preview key uses English text and plural rules. Preference selection and automatic language detection use published catalogs only. Add a language to `PUBLISHED_TRANSLATIONS` after completing the catalog, reviewing terminology, and checking Android and iOS with enlarged fonts, menus, diagnostics, and a screen reader. The generator rejects publishing an incomplete catalog.

Run `python3 scripts/generate-localization.py --check --require-reference-locales` to check coverage acceptance for all languages. The failure message reports the number of translated keys in each incomplete catalog.

## Use a message in code

In core code, retain the message identity and arguments until presentation.

```kotlin
val notice = message(Messages.A11yOpenCircuit, "name" to circuit.name)
```

In Compose UI, use `localizedString(notice)`. In accessibility lambdas or non-composable callbacks, capture `val strings = rememberLocalizer()` and use `strings(notice)`. To set a window's language, wrap its content in `AppLocaleProvider(language)`. This provider invalidates consumers when the language changes and keeps the UI layout direction LTR for Evolution languages. Components that draw text on the canvas can read `LocalAppLanguage.current` and resolve the same message.

The core runs independently of Compose, Android, and Foundation. Validation exceptions can use `LocalizedArgumentException`, whose `text` field preserves the key and arguments. The default exception message is English for logs and for compatibility with consumers of `Throwable.message`.

Pass complete arguments to the message so translated text stays together. Arguments are substituted once, so imported names containing braces or directional symbols remain literal data.

## Use plurals

A plural message contains `one` and `other` in both languages. The `{count}` parameter is populated from `quantity`.

```json
{
  "wire_count": {
    "en": { "one": "{count} wire", "other": "{count} wires" },
    "pt": { "one": "{count} fio", "other": "{count} fios" }
  }
}
```

Use `LocalizedText(Messages.WireCount, quantity = count)` in core or `localizedPlural(Messages.WireCount, count)` in the UI. The singular corresponds to `1`. Zero and other quantities use `other`.

## Validate a change

The generator rejects duplicate keys, empty translations, missing languages, mismatched parameters, and incompatible plural sets. The Kotlin test `LocalizedTextTest` covers fallback, language changes, plurals, long names, directional symbols, and parameters containing reserved characters.

Check screens in Portuguese and English with enlarged fonts. Verify that commands remain reachable and accessibility descriptions use the same messages. Preserve file names, user labels, and reserved `.circ` format words as literals.

## Storage notices

Storage records notices in `notices: List<LocalizedText>`. Failures expose a `notice`, and the session keeps the list of notices. The UI resolves them in the selected language. The legacy `diagnostics` and `message` getters still return Portuguese for compatibility. New consumers should use the structured notices. Resolving a message in another language leaves project files and revisions intact.

The editor accepts `App(language = AppLanguage.ENGLISH)` for host preferences and
preview checks. It reads the system locale by default. Its state keeps
`LocalizedText` values so changing that parameter updates existing statuses and
file errors. User labels, circuit names, XML attributes, component identifiers,
and native provider details remain literal data.

Tool integrations can supply `EditorTool.titleText` and call
`EditorToolContext.showNotice`. Native document adapters should construct
`DocumentOpenResult.Failure`, `DocumentWriteResult.Failure`, or
`DocumentShareResult.Failure` with a `LocalizedText` notice. The String constructors
preserve external provider text. The legacy String getters remain available for
callers migrating from the original contracts; presentation should use the typed
notice.

Run `python3 scripts/generate-localization.py --check --check-ui` when adding a
screen. The UI audit reports direct text literals in Compose `Text`, accessibility
descriptions, and editor status/error calls. Dynamic names, circuit data, symbols,
and protocol units remain literal. Both flags can also be used as a CI gate once
the host integrations are assembled. Catalog validation rejects duplicate JSON
properties, missing locales, mismatched parameters, and inconsistent plurals.
