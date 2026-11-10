#!/usr/bin/env python3
"""Recheck compact documentation evidence; optionally replay local raw HTTP bodies."""
from html.parser import HTMLParser
from pathlib import Path
import argparse
import hashlib
import json
import plistlib
import re
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parent
GROUP = "NSExtensionFileProviderDocumentGroup"
ENUMERATION = "NSExtensionFileProviderSupportsEnumeration"
EXTENSION = "doc://com.apple.bundleresources/documentation/BundleResources/Information-Property-List/NSExtension"
RULE = (ROOT / "archive-attributes-sentence.txt").read_text().removesuffix("\n")
RULE_SHA = "df25e74304b388dc26c117ac475168f7113d8af0a2a15b680b81abbdb221b2f7"


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def load(name):
    return json.loads((ROOT / name).read_text())


class Text(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []
        self.skip = 0

    def handle_starttag(self, tag, attributes):
        if tag in ["script", "style"]:
            self.skip += 1
        if tag in ["h1", "h2", "h3", "h4", "p", "li", "tr", "div", "pre", "br"]:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in ["script", "style"]:
            self.skip = max(0, self.skip - 1)
        if tag in ["h1", "h2", "h3", "h4", "p", "li", "tr", "div", "pre", "br"]:
            self.parts.append("\n")

    def handle_data(self, text):
        if not self.skip:
            self.parts.append(text)


def normalized_html(raw):
    parser = Text()
    parser.feed(raw.decode())
    return "\n".join(re.sub(r"\s+", " ", line).strip()
                     for line in "".join(parser.parts).splitlines() if line.strip())


def docc_extract(file, url, raw):
    data = json.loads(raw)
    metadata = data["metadata"]
    identifiers = [identifier for section in data.get("topicSections", []) for identifier in section.get("identifiers", [])]
    return {"rawFile": file, "sourceURL": url, "externalID": metadata["externalID"], "title": metadata["title"],
            "platforms": metadata.get("platforms", []), "hierarchyPaths": data["hierarchy"]["paths"],
            "deprecationMarkers": {key: data[key] for key in ["deprecationSummary"] if key in data},
            "totalTopicIdentifierCount": len(identifiers),
            "fileProviderTopicIdentifiers": [identifier for identifier in identifiers
                                             if any(identifier.endswith("/" + key) for key in [GROUP, ENUMERATION])]}


def verify(raw_directory=None):
    manifest = load("manifest.json")
    for name, entry in manifest["files"].items():
        assert Path(name).name == name and not (ROOT / name).is_symlink()
        raw = (ROOT / name).read_bytes()
        assert len(raw) == entry["bytes"] and digest(raw) == entry["sha256"], name
    assert len(manifest["files"]) == 10

    pages = load("raw-pages-manifest.json")
    records = pages["files"]
    assert pages["dateCheckedUTC"] == "2026-11-05" and pages["rawBodiesCommitted"] is False
    assert len(records) == len({entry["file"] for entry in records}) == 10
    for record in records:
        assert record["status"] == 200 and record["startedUTC"].startswith("2026-11-05T07:")
        assert record["requestedURL"] == record["finalURL"]
        assert urlparse(record["requestedURL"]).hostname == "developer.apple.com"
        assert re.fullmatch("[0-9a-f]{64}", record["sha256"]) and record["bytes"] > 0
    by_file = {record["file"]: record for record in records}

    extracts = load("docc-extracts.json")
    assert set(extracts) == {"nsextension", "nsextensionattributes", GROUP.lower(), ENUMERATION.lower()}
    for key, name in [(GROUP.lower(), GROUP), (ENUMERATION.lower(), ENUMERATION)]:
        entry = extracts[key]
        assert entry["externalID"] == "plist:Information_Property_List.NSExtension." + name
        assert entry["title"] == name and entry["hierarchyPaths"][0][-1] == EXTENSION
        assert all("/NSExtensionAttributes" not in path for hierarchy in entry["hierarchyPaths"] for path in hierarchy)
        version = "8.0" if name == GROUP else "11.0"
        assert entry["platforms"] == [{"introducedAt": version, "name": "iOS"},
                                      {"introducedAt": version, "name": "iPadOS"},
                                      {"introducedAt": "1.0", "name": "visionOS"}]
        assert entry["deprecationMarkers"] == {} and entry["fileProviderTopicIdentifiers"] == []
    parent = extracts["nsextension"]
    attributes = extracts["nsextensionattributes"]
    assert parent["externalID"] == "plist:Information_Property_List.NSExtension"
    assert parent["hierarchyPaths"][0][-1].endswith("/Information-Property-List")
    assert parent["fileProviderTopicIdentifiers"] == [EXTENSION + "/" + GROUP, EXTENSION + "/" + ENUMERATION]
    assert attributes["externalID"] == "plist:Information_Property_List.NSExtension.NSExtensionAttributes"
    assert attributes["hierarchyPaths"][0][-1] == EXTENSION and attributes["fileProviderTopicIdentifiers"] == []
    assert attributes["totalTopicIdentifierCount"] == 40
    for parent_entry in [parent, attributes]:
        assert {platform["name"] for platform in parent_entry["platforms"]} == {"iOS", "iPadOS", "macOS", "visionOS"}
        assert parent_entry["deprecationMarkers"] == {}

    table = load("keypaths.json")
    assert table["documentedConflict"] is True and table["runtimeResolutionEstablished"] is False
    archive, guide, current = table["sources"]
    for key in [GROUP, ENUMERATION]:
        assert archive["keypaths"][key] == ["NSExtension", "NSExtensionAttributes", key]
        assert current["keypaths"][key] == ["NSExtension", key]
    assert archive["updated"] == "2018-06-04" and guide["updated"] == "2017-10-19"
    assert guide["keypaths"] == {GROUP: ["NSExtension", GROUP]}
    assert digest((ROOT / "archive-attributes-sentence.txt").read_bytes()) == RULE_SHA and len(RULE.split()) == 14
    declaration = load("guide-declaration.json")["NSExtension"]
    assert declaration[GROUP] == "group.com.example.sharedContainerURL" and "NSExtensionAttributes" not in declaration
    assert declaration["NSExtensionPointIdentifier"] == "com.apple.fileprovider-nonui"

    trees = load("local-trees.json")
    assert trees["internalNormalizedAttributesObserved"] is False
    assert trees["templateDefinitionPath"] == ["Definitions", "Info.plist:NSExtension"]
    for tree in [trees["templateGeneratedTree"]["NSExtension"], trees["fixtureSourceTree"], trees["installedTree"]]:
        assert tree["NSExtensionPointIdentifier"] == "com.apple.fileprovider-nonui"
        assert GROUP in tree and tree[ENUMERATION] is True and "NSExtensionAttributes" not in tree
    assert trees["fixtureSourceTree"][GROUP] == trees["installedTree"][GROUP] == "group.thalesmms.logisim.validation.revocationfixture"
    assert trees["placeholderEventZeroBasedIndex"] == 17
    assert GROUP in trees["placeholderEvent"]["eventMessage"] and "NSExtensionAttributes" not in trees["placeholderEvent"]["eventMessage"]
    observation = load("discovery-observation.json")
    assert [entry["sourceArrayIndex"] for entry in observation["events"]] == [961, 962, 963]
    assert len({(entry["processID"], entry["threadID"]) for entry in observation["events"]}) == 1
    assert "Query result" in observation["events"][0]["eventMessage"]
    assert "NSExtensionFileProviderDocumentGroup" in observation["events"][1]["eventMessage"]
    assert "Ignoring the extension" in observation["events"][1]["eventMessage"]
    assert "Failed to create descriptor" in observation["events"][2]["eventMessage"]

    probe = load("compatibility-probe.json")
    assert probe["status"] == "PLAN_ONLY" and probe["nativeExecution"] == "NOT_RUN"
    assert probe["priority"] == "DEPRIORITIZED" and probe["executionRecommended"] is False
    assert probe["compatibilityDemonstrated"] is False
    assert probe["sourcePatchProduced"] is False and probe["artifactBuilt"] is False
    assert probe["wholeArtifactByteIdentityExpected"] is False and probe["signatureResourceBytesChangeExpected"] is True
    assert probe["proposal"]["preserveDirectKeys"] == ["NSExtension." + GROUP, "NSExtension." + ENUMERATION]
    assert probe["proposal"]["addOnly"] == "NSExtension.NSExtensionAttributes." + GROUP
    assert probe["proposal"]["doNotMove"] == ENUMERATION
    assert probe["proposal"]["addedValue"] == "Same exact group identifier as the direct key"

    if raw_directory is not None:
        raw_directory = Path(raw_directory)
        bodies = {}
        for record in records:
            assert Path(record["file"]).name == record["file"]
            raw = (raw_directory / record["file"]).read_bytes()
            assert len(raw) == record["bytes"] and digest(raw) == record["sha256"], record["file"]
            bodies[record["file"]] = raw
        for entry in extracts.values():
            assert docc_extract(entry["rawFile"], entry["sourceURL"], bodies[entry["rawFile"]]) == entry
        archive_text = normalized_html(bodies["archive-app-extension-keys.html"])
        assert RULE in archive_text[archive_text.index("Document Picker and File Provider Extension Keys\n"):]
        guide_text = normalized_html(bodies["archive-document-provider.html"])
        group = guide_text.index("<key>" + GROUP + "</key>")
        start = guide_text.rfind("<key>NSExtension</key>", 0, group)
        end = guide_text.index("</dict>", group) + len("</dict>")
        parsed = plistlib.loads(("<plist version=\"1.0\"><dict>" + guide_text[start:end] + "</dict></plist>").encode())
        assert parsed == load("guide-declaration.json")
        for file, date in [("archive-app-extension-keys.html", "2018-06-04"), ("archive-document-provider.html", "2017-10-19")]:
            assert 'name="date" content="' + date + '"' in bodies[file].decode()
        for file, name in [("current-nsextension.md", "nsextension"), ("current-NSExtensionAttributes.md", "nsextensionattributes"),
                           ("current-" + GROUP + ".md", GROUP.lower()), ("current-" + ENUMERATION + ".md", ENUMERATION.lower())]:
            metadata = json.loads(re.search(r"<!--\s*(\{.*?\})\s*-->", bodies[file].decode(), re.S)[1])
            assert metadata["symbol"]["preciseIdentifier"] == extracts[name]["externalID"]
    return {"offlineDocumentationGate": "PASS", "httpResponsesRecorded": 10, "doccSchemas": 4,
            "documentedConflict": True, "rawReplay": "PASS" if raw_directory is not None else "NOT_REQUESTED",
            "nativeExecution": "NOT_RUN", "runtimeKeypathCauseEstablished": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-dir", type=Path, help="Optional retained HTTP directory; no network request is made")
    print(json.dumps(verify(parser.parse_args().raw_dir), indent=2))
