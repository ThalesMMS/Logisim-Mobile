#!/usr/bin/env python3
"""Check common source boundaries and basic source formatting without dependencies."""

import argparse
import re
from pathlib import Path


def check(root: Path) -> list[str]:
    errors: list[str] = []
    roots = [root / name for name in ("core", "composeApp", "androidApp", "iosApp", "scripts", ".github")]
    files = {path for folder in roots if folder.exists() for path in folder.rglob("*")
             if path.is_file() and path.suffix in {".kt", ".kts", ".swift", ".sh", ".py", ".yml", ".yaml"}
             and not {"build", ".gradle", "deriveddata"}.intersection(part.lower() for part in path.relative_to(root).parts)
             and not any(part.endswith(".xcodeproj") for part in path.parts)}
    files.update(root.glob("*.kts"))
    forbidden = re.compile(r"^\s*import\s+(?:java\.|javax\.|android\.|platform\.|kotlinx\.cinterop\.)")
    for path in sorted(files):
        relative = path.relative_to(root)
        text = path.read_text(encoding="utf-8")
        if text and not text.endswith("\n"):
            errors.append(f"{relative}: missing final newline")
        for number, line in enumerate(text.splitlines(), 1):
            if line.rstrip() != line:
                errors.append(f"{relative}:{number}: trailing whitespace")
            if "commonMain" in relative.parts and path.suffix == ".kt" and forbidden.match(line):
                errors.append(f"{relative}:{number}: platform import in commonMain: {line.strip()}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    errors = check(parser.parse_args().root.resolve())
    if errors:
        print("\n".join(errors))
        return 1
    print("Common source boundaries and source formatting passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
