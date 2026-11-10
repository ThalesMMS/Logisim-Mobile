#!/usr/bin/env python3
"""Validate message catalogs and generate shared Kotlin and Compose resources."""
import argparse
import json
import re
import sys
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
KEY = re.compile(r"[a-z][a-z0-9]*(?:_[a-z0-9]+)*")
PARAM = re.compile(r"\{([a-z][a-zA-Z0-9_]*)\}")
LANGUAGES = ("en", "pt")
REFERENCE_PLURALS = {
    "de": ("one", "other"), "el": ("one", "other"), "es": ("one", "many", "other"),
    "fr": ("one", "many", "other"), "it": ("one", "many", "other"), "ja": ("other",),
    "nl": ("one", "other"), "pl": ("one", "few", "many", "other"),
    "ru": ("one", "few", "many", "other"), "tr": ("one", "other"), "zh": ("other",),
}
# Every published catalog must cover all message keys.
PUBLISHED_TRANSLATIONS = ("de", "el", "es", "fr", "it", "ja", "nl", "pl", "ru", "tr", "zh")


def unique_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON property: {key}")
        result[key] = value
    return result


def load_messages():
    result = {}
    for file in sorted((ROOT / "localization/messages").glob("*.json")):
        document = json.loads(file.read_text(), object_pairs_hook=unique_json_object)
        for key, entry in document.items():
            if not KEY.fullmatch(key) or key in result:
                raise ValueError(f"Invalid or duplicate message key: {key} ({file.name})")
            if set(entry) - {"en", "pt", "description"}:
                raise ValueError(f"Unexpected properties: {key}")
            parameters = None
            variants = None
            for language in LANGUAGES:
                value = entry.get(language)
                if isinstance(value, str):
                    values = {"other": value}
                elif isinstance(value, dict) and set(value) == {"one", "other"}:
                    values = value
                else:
                    raise ValueError(f"{key}.{language}: provide text or one/other plurals")
                if any(not isinstance(text, str) or not text.strip() for text in values.values()):
                    raise ValueError(f"Empty message: {key}.{language}")
                found = [set(PARAM.findall(text)) for text in values.values()]
                if any(p != found[0] for p in found):
                    raise ValueError(f"Plural parameters differ: {key}.{language}")
                if parameters is not None and found[0] != parameters:
                    raise ValueError(f"Translation parameters differ: {key}")
                if variants is not None and set(values) != variants:
                    raise ValueError(f"Plural forms differ across languages: {key}")
                parameters, variants = found[0], set(values)
                for text in values.values():
                    remainder = PARAM.sub("", text)
                    if "{" in remainder or "}" in remainder:
                        raise ValueError(f"Unbalanced or unsupported placeholder: {key}.{language}")
            result[key] = entry
    if not result:
        raise ValueError("No messages found")
    return dict(sorted(result.items()))


def load_translations(messages):
    catalogs = {}
    for path in sorted((ROOT / "localization/translations").glob("*.json")):
        language = path.stem
        if language not in REFERENCE_PLURALS:
            raise ValueError(f"Unknown translation locale: {language}")
        catalog = json.loads(path.read_text(), object_pairs_hook=unique_json_object)
        if not isinstance(catalog, dict) or not catalog:
            raise ValueError(f"{language}: expected an object with translated messages")
        for key, translation in catalog.items():
            if key not in messages:
                raise ValueError(f"{key}.{language}: unknown message")
            source = messages[key]["en"]
            if isinstance(source, str) and isinstance(translation, str):
                expected = set(PARAM.findall(source))
                values = [translation]
            elif isinstance(source, dict) and isinstance(translation, dict):
                if set(translation) != set(REFERENCE_PLURALS[language]):
                    raise ValueError(f"{key}.{language}: expected plurals {REFERENCE_PLURALS[language]}")
                expected = set(PARAM.findall(source["other"]))
                values = translation.values()
            else:
                raise ValueError(f"{key}.{language}: translation must match the source message type")
            for value in values:
                if not isinstance(value, str) or not value.strip():
                    raise ValueError(f"Empty message: {key}.{language}")
                if set(PARAM.findall(value)) != expected:
                    raise ValueError(f"Translation parameters differ: {key}.{language}")
                remainder = PARAM.sub("", value)
                if "{" in remainder or "}" in remainder:
                    raise ValueError(f"Unbalanced or unsupported placeholder: {key}.{language}")
        catalogs[language] = dict(sorted(catalog.items()))
    return catalogs


def kotlin_string(value):
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def identifier(key):
    return "".join(part.capitalize() for part in key.split("_"))


def validate_references(messages):
    reference = re.compile(r'\b(?:LocalizedText|localizedString|localizedPlural|message)\(\s*"([a-z][a-z0-9_]*)"(?=\s*[,\)])')
    errors = []
    for module in ("core", "composeApp", "androidApp"):
        source = ROOT / module / "src"
        for path in source.glob("*Main/**/*.kt"):
            text = path.read_text()
            if "import dev.logisim.mobile.core.localization" not in text and "import dev.logisim.mobile.localization" not in text:
                continue
            for match in reference.finditer(text):
                if match.group(1) not in messages:
                    line = text.count("\n", 0, match.start()) + 1
                    errors.append(f"{path.relative_to(ROOT)}:{line}: unknown message {match.group(1)}")
    if errors:
        raise ValueError("\n".join(errors))



def kotlin_ui_literal(source, start):
    delimiter = '\"\"\"' if source.startswith('\"\"\"', start) else '\"'
    index = start + len(delimiter)
    static = []
    while index < len(source):
        if source.startswith(delimiter, index):
            return source[start + len(delimiter):index], "".join(static), index + len(delimiter)
        if delimiter == '\"' and source[index] == "\\":
            static.append(source[index:index + 2])
            index += 2
        elif source.startswith("${", index):
            index += 2
            depth = 1
            while index < len(source) and depth:
                if source[index] == '\"':
                    _, _, index = kotlin_ui_literal(source, index)
                else:
                    depth += (source[index] == '{') - (source[index] == '}')
                    index += 1
        elif source[index] == '$' and re.match(r'[A-Za-z_]', source[index + 1:index + 2]):
            index += len(re.match(r'\$[A-Za-z_]\w*', source[index:]).group(0))
        else:
            static.append(source[index])
            index += 1
    return source[start + len(delimiter):], "".join(static), index


def inline_ui_literals(source):
    # Interpolated values are data; inspect the surrounding UI copy, including Elvis fallbacks.
    literal = r'(?P<literal>")'
    calls = re.compile(r'\b(?:Text|reportStatus|reportError|showStatus)\(\s*(?:text\s*=\s*)?' + literal)
    descriptions = re.compile(r'\bcontentDescription\s*=\s*' + literal)
    fallbacks = re.compile(r'\b(?:Text|reportStatus|reportError|showStatus)\([^"()\n]*\?:\s*' + literal)
    technical = {"SAP-1", "TTL", "LED", "RGB", "RGBA", "ROM", "RAM", "ASCII", "UTF-8", "XML", "VHDL", "Verilog", "HDL", "FPGA", "PC", "SP", "IR", "CSR", "CPU", "UART", "GPIO", "JTAG", "VCC", "GND", "CLK", "ANSI", "IEC", "AND", "OR", "NOT", "XOR", "NAND", "NOR", "XNOR"}
    units = re.compile(r'\b(?:Hz|kHz|MHz|GHz|ns|μs|ms|s|mA|mV|V|A)\b')
    matches = []
    for pattern in (calls, descriptions, fallbacks):
        for match in pattern.finditer(source):
            value, static, _ = kotlin_ui_literal(source, match.start('literal'))
            if value in technical:
                continue
            static = units.sub('', static)
            if re.search(r'[^\W\d_]{2,}', static, re.UNICODE):
                matches.append((source.count("\n", 0, match.start()) + 1, value))
    return sorted(set(matches))


def validate_ui_literals():
    errors = []
    for module in ("composeApp", "androidApp"):
        for path in (ROOT / module / "src").glob("*Main/**/*.kt"):
            for line, value in inline_ui_literals(path.read_text()):
                errors.append(f"{path.relative_to(ROOT)}:{line}: move UI text to localization/messages: {value}")
    if errors:
        raise ValueError("\n".join(errors))

def generate_kotlin(messages):
    lines = [
        "// Generated by scripts/generate-localization.py. Edit localization/messages/*.json.",
        "package dev.logisim.mobile.core.localization", "", "object Messages {",
    ]
    for key in messages:
        lines.append(f"    const val {identifier(key)} = {kotlin_string(key)}")
    entries = list(messages.items())
    # Keep JVM initializer methods bounded as feature catalogs grow.
    chunks = [entries[start:start + 100] for start in range(0, len(entries), 100)]
    lines += ["}", "", "internal val appMessageCatalog: Map<String, MessageTemplate> = buildMap {"]
    for index in range(len(chunks)):
        lines.append(f"    putAll(messageCatalogPart{index}())")
    lines += ["}", ""]
    for index, chunk in enumerate(chunks):
        lines.append(f"private fun messageCatalogPart{index}(): Map<String, MessageTemplate> = mapOf(")
        for key, entry in chunk:
            values = []
            for language in LANGUAGES:
                value = entry[language]
                values.append(kotlin_forms(value))
            lines.append(f"    {kotlin_string(key)} to MessageTemplate(english = {values[0]}, portuguese = {values[1]}),")
        lines += [")", ""]
    return "\n".join(lines)


def kotlin_forms(value):
    forms = {"other": value} if isinstance(value, str) else value
    parameters = ", ".join(f"{form} = {kotlin_string(forms[form])}"
                           for form in ("one", "few", "many", "other") if form in forms)
    return f"MessageForms({parameters})"


def generate_translations(catalogs, published):
    languages = ", ".join(kotlin_string(language) for language in published)
    lines = [
        "// Generated by scripts/generate-localization.py. Edit localization/translations/*.json.",
        "package dev.logisim.mobile.core.localization", "",
        f"internal val publishedTranslationTags: Set<String> = setOf({languages})", "",
        "internal fun additionalTranslation(language: String, key: String): MessageForms? = when (language) {",
    ]
    for language in catalogs:
        lines.append(f"    {kotlin_string(language)} -> Translation{language.capitalize()}.messages[key]")
    lines += ["    else -> null", "}", ""]
    for language, entries in catalogs.items():
        name = f"Translation{language.capitalize()}"
        chunks = [list(entries.items())[start:start + 100] for start in range(0, len(entries), 100)]
        # Each language has a separate JVM constant pool. Its catalog is allocated on first use.
        lines += [f"private object {name} {{", "    val messages: Map<String, MessageForms> by lazy {", "        buildMap {"]
        for index in range(len(chunks)):
            lines.append(f"            putAll(part{index}())")
        lines += ["        }", "    }", ""]
        for index, chunk in enumerate(chunks):
            lines.append(f"    private fun part{index}(): Map<String, MessageForms> = mapOf(")
            for key, value in chunk:
                lines.append(f"        {kotlin_string(key)} to {kotlin_forms(value)},")
            lines += ["    )", ""]
        lines += ["}", ""]
    return "\n".join(lines)


def resource_text(value):
    # The custom formatter uses named placeholders. Keep them identical in XML and Kotlin.
    return escape(value).replace("'", "\\'").replace('"', '\\"')


def generate_xml(messages, language):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Generated by scripts/generate-localization.py. Edit localization/messages/*.json. -->',
             '<resources>']
    for key, entry in messages.items():
        value = entry[language]
        if isinstance(value, str):
            lines.append(f'    <string name="{key}">{resource_text(value)}</string>')
        else:
            lines.append(f'    <plurals name="{key}">')
            for form in ("one", "few", "many", "other"):
                if form in value:
                    lines.append(f'        <item quantity="{form}">{resource_text(value[form])}</item>')
            lines.append('    </plurals>')
    lines += ['</resources>', '']
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if generated files differ")
    parser.add_argument("--check-ui", action="store_true", help="Also reject inline UI copy")
    parser.add_argument("--require-reference-locales", action="store_true",
                        help="Fail unless every Evolution locale covers the full catalog")
    args = parser.parse_args()
    try:
        messages = load_messages()
        catalogs = load_translations(messages)
        required = REFERENCE_PLURALS if args.require_reference_locales else PUBLISHED_TRANSLATIONS
        incomplete = [f"{language} {len(catalogs.get(language, {}))}/{len(messages)}"
                      for language in required if len(catalogs.get(language, {})) != len(messages)]
        if incomplete:
            raise ValueError("Incomplete Evolution locales: " + ", ".join(incomplete))
        validate_references(messages)
        if args.check_ui:
            validate_ui_literals()
    except (OSError, ValueError) as error:
        parser.exit(1, f"Localization: {error}\n")
    files = {
        ROOT / 'core/src/commonMain/kotlin/dev/logisim/mobile/core/localization/GeneratedMessages.kt': generate_kotlin(messages),
        ROOT / 'core/src/commonMain/kotlin/dev/logisim/mobile/core/localization/GeneratedTranslations.kt': generate_translations(catalogs, PUBLISHED_TRANSLATIONS),
        ROOT / 'composeApp/src/commonMain/composeResources/values/strings.xml': generate_xml(messages, 'en'),
        ROOT / 'composeApp/src/commonMain/composeResources/values-pt/strings.xml': generate_xml(messages, 'pt'),
    }
    for language in PUBLISHED_TRANSLATIONS:
        translated = {key: {language: value} for key, value in catalogs[language].items()}
        files[ROOT / f'composeApp/src/commonMain/composeResources/values-{language}/strings.xml'] = generate_xml(translated, language)
    stale = []
    for path, contents in files.items():
        if args.check:
            if not path.exists() or path.read_text() != contents:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents)
    if stale:
        parser.exit(1, "Localization outputs are stale:\n" + "\n".join(stale) + "\n")
    print(f"Localization: {len(messages)} keys, {2 + len(PUBLISHED_TRANSLATIONS)} complete published locales, English fallback.")


if __name__ == '__main__':
    main()
