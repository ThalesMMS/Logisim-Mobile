#!/usr/bin/env python3
"""Fill missing catalog entries from a reviewed mapping to pinned Evolution resources."""

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = "src/main/resources/resources/logisim"
PROPERTY = re.compile(r"^\s*([\w.]+)\s*=\s*(\S.*?)\s*$")
MOBILE_ARGUMENT = re.compile(r"\{[a-z][a-zA-Z0-9_]*\}")
EVOLUTION_ARGUMENT = re.compile(r"%(?:1\$)?s")
LOCAL_SCRIPT = {
    "el": re.compile("[Α-Ωα-ωΆΈΉΊΌΎΏάέήίόύώΐΰϊϋ]"),
    "ja": re.compile("[ぁ-ゖァ-ヺ一-鿿]"),
    "ru": re.compile("[А-Яа-яЁё]"),
    "zh": re.compile("[一-鿿]"),
}


def git_read(desktop, revision, path):
    data = subprocess.check_output(["git", "-C", str(desktop), "show", f"{revision}:{path}"])
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        # PropertyResourceBundle uses ISO-8859-1 if UTF-8 decoding fails.
        return data.decode("latin-1")


def read_bundle(desktop, revision, bundle, language=""):
    suffix = f"_{language}" if language else ""
    resource = f"{RESOURCES}/strings/{bundle}/{bundle}{suffix}.properties"
    properties = {}
    continued = False
    for line in git_read(desktop, revision, resource).splitlines():
        continuation = (len(line) - len(line.rstrip("\\"))) % 2 == 1
        if not continued and not continuation and (pair := PROPERTY.fullmatch(line)):
            properties[pair[1]] = pair[2]
        continued = continuation
    return properties


def adapt_label(value, argument):
    if not value:
        return None
    if argument:
        text, count = EVOLUTION_ARGUMENT.subn(argument, value)
        if count != 1:
            return None
        remainder = text.replace(argument, "", 1)
    else:
        text = remainder = value
    return text if not any(character in remainder for character in "\\%{}\r\n") else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--desktop", type=Path, default=ROOT.parent / "logisim-evolution")
    parser.add_argument("--apply", action="store_true", help="Fill gaps in localization/translations")
    args = parser.parse_args()

    manifest = json.loads((ROOT / "localization/evolution-shared.json").read_text())
    revision = manifest["revision"]
    messages = {}
    for path in sorted((ROOT / "localization/messages").glob("*.json")):
        messages.update(json.loads(path.read_text()))
    languages = git_read(args.desktop, revision, f"{RESOURCES}/settings.properties")
    languages = languages.partition("=")[2].split()
    languages = sorted(set(languages) - {"en", "pt"})
    if not languages:
        parser.error("Evolution settings did not declare additional locales")

    sources = {key: tuple(value.split(".")) for key, value in manifest["messages"].items()}
    bundles = sorted({bundle for bundle, _ in sources.values()})
    english = {bundle: read_bundle(args.desktop, revision, bundle) for bundle in bundles}
    arguments = {}
    for key, (bundle, property_key) in sources.items():
        mobile = messages.get(key, {}).get("en")
        desktop = english[bundle].get(property_key)
        parameters = MOBILE_ARGUMENT.findall(mobile) if isinstance(mobile, str) else []
        argument = parameters[0] if len(parameters) == 1 else None
        adapted = adapt_label(desktop, argument)
        if adapted is None or not isinstance(mobile, str) or mobile.casefold() != adapted.casefold():
            parser.error(f"{key} no longer matches {bundle}.{property_key}: {mobile!r} / {desktop!r}")
        arguments[key] = argument

    print(f"{len(sources)} reviewed entries at Evolution {revision[:8]}")
    review_required = manifest["review_required"]
    for language in languages:
        path = ROOT / f"localization/translations/{language}.json"
        translations = json.loads(path.read_text()) if path.exists() else {}
        localized = {bundle: read_bundle(args.desktop, revision, bundle, language) for bundle in bundles}
        counts = Counter()
        for key, (bundle, property_key) in sources.items():
            if key in translations:
                counts["existing"] += 1
                continue
            value = adapt_label(localized[bundle].get(property_key), arguments[key])
            source = messages[key]["en"]
            needs_review = key in review_required.get(language, ())
            script = LOCAL_SCRIPT.get(language)
            if (needs_review or value is None or value.casefold() == source.casefold()
                    or (script and not script.search(value))):
                # An untranslated, escaped or absent property still needs a human decision.
                counts["unresolved"] += 1
                continue
            translations[key] = value
            counts["added"] += 1
        if args.apply and counts["added"]:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(translations, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
        print(f"{language}: {counts['added']} {'added' if args.apply else 'available'}, "
              f"{counts['existing']} preserved, {counts['unresolved']} unresolved")


if __name__ == "__main__":
    main()
