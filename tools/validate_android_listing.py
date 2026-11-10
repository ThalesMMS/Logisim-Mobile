"""Validate the Android listing draft against app identity and listing text limits."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from .generate_app_identity import ROOT, read_identity


def validate_listing(data: dict, root: Path, *, require_reviewed: bool = False) -> list[str]:
    if not isinstance(data, dict):
        raise ValueError("Listing must be a JSON object")
    for field in ("identity", "listings", "ownerDecisions", "materials", "privacyReview", "licenseReview"):
        if not isinstance(data.get(field), dict):
            raise ValueError(f"Listing {field} must be a JSON object")
    identity = read_identity(root / "app.properties")
    expected = {"application": identity.name, "packageName": identity.android_id,
                "version": identity.version, "build": identity.build}
    if data.get("schemaVersion") != 1 or data.get("identity") != expected:
        raise ValueError("Listing schema or identity differs from app.properties")
    if data.get("reviewStatus") not in ("draft", "reviewed"):
        raise ValueError("Listing reviewStatus must be draft or reviewed")
    listings = data.get("listings", {})
    if not listings or data.get("defaultLanguageCandidate") not in listings:
        raise ValueError("The candidate default language needs a localized listing")
    for language, listing in listings.items():
        if not isinstance(listing, dict):
            raise ValueError(f"{language} listing must be a JSON object")
        for field, limit in (("title", 30), ("shortDescription", 80), ("fullDescription", 4000)):
            text = listing.get(field)
            if not isinstance(text, str) or not text.strip() or len(text) > limit:
                raise ValueError(f"{language}.{field} must contain 1 to {limit} characters")
        if not (root / listing["releaseNotesFile"]).is_file():
            raise ValueError(f"{language} release notes file is missing")
    decisions = data["ownerDecisions"]
    required = {"channel", "developerAccount", "supportEmail", "privacyPolicyUrl", "targetAudience",
                "pricing", "contentRating", "dataSafetyDeclaration"}
    if decisions.keys() != required:
        raise ValueError("Listing ownerDecisions fields are missing or unknown")
    if any(value is not None and (not isinstance(value, str) or not value.strip()) for value in decisions.values()):
        raise ValueError("Listing decisions must be nonempty text or null while unresolved")
    pending = [f"ownerDecisions.{name}" for name, value in decisions.items() if value is None]
    pending += [f"materials.{name}" for name in ("phoneScreenshots", "tabletScreenshots", "featureGraphic")
                if not data["materials"].get(name)]
    pending += [name for name in ("privacyReview", "licenseReview") if data[name].get("status") != "reviewed"]
    if data["reviewStatus"] != "reviewed":
        pending.append("reviewStatus")
    if require_reviewed and pending:
        raise ValueError("Listing review is incomplete: " + ", ".join(pending))
    return pending


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, default=ROOT / "docs/releases/android-listing-draft.json")
    parser.add_argument("--require-reviewed", action="store_true", help="Reject unresolved listing decisions; does not authorize distribution")
    options = parser.parse_args()
    try:
        pending = validate_listing(json.loads(options.file.read_text()), ROOT, require_reviewed=options.require_reviewed)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(error, file=sys.stderr)
        return 2
    print(f"Android listing valid; {len(pending)} unresolved review fields")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
