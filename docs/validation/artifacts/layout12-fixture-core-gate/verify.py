"""Verify the Root-produced gate archive without compiling or executing Java."""

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def verify():
    archive = json.loads((ROOT / "archive.json").read_text())
    for item in archive["files"]:
        content = (ROOT / item["path"]).read_bytes()
        assert len(content) == item["bytes"] and hashlib.sha256(content).hexdigest() == item["sha256"], item["path"]
    receipt = json.loads((ROOT / "receipt.json").read_text())
    assert receipt["status"] == "PASS" and receipt["native"] == "NOT_RUN"
    assert hashlib.sha256((ROOT / "Layout12FixtureGateFixed.java").read_bytes()).hexdigest() == receipt["source_sha256"]
    assert receipt["fixture_sha256"] == "b6e68a8d6f2060ef1baa3436f8f8e7becbb5c3b9926b732bda939207f1e63893"
    assert "AssertionError: roundtrip components/wires" in (ROOT / "run.log").read_text()
    assert (ROOT / "run-fixed.log").read_text() == "PASS: one circuit, two embedded 1x1 PNGs with exact bounds and license, Reset INPUT, component/wire roundtrip\n"
    addition = json.loads((ROOT / "classpath-postrun-archive.json").read_text())
    content = (ROOT / addition["path"]).read_bytes()
    assert len(content) == addition["bytes"] and hashlib.sha256(content).hexdigest() == addition["sha256"]
    provenance = json.loads(content)
    assert provenance["timing"] == "Post-run read only, not a before/during hash claim"
    assert len(provenance["files"]) == len({item["path"] for item in provenance["files"]}) == 967
    digest = hashlib.sha256()
    for item in provenance["files"]:
        digest.update((item["path"] + "\0" + item["sha256"] + "\n").encode())
    assert digest.hexdigest() == provenance["classes_tree_sha256"] == "de08aa193cab1dd0758ab32f9a9ab8b6046cdadde636e259172bf70d6d86ea94"
    assert provenance["stdlib_sha256"] == receipt["stdlib_sha256"]
    assert provenance["compile_argv"][1:3] == ["-cp", provenance["classpath"]]
    assert provenance["source_changes_since_root_gate"] == []
    later = json.loads((ROOT / "classpath-postrun-verification.json").read_text())
    assert later["core_files_verified"] == 967 and later["classes_tree_sha256"] == digest.hexdigest()
    assert not later["recompiled"] and not later["reran_gate"] and later["native"] == "NOT_RUN"
    return {"archive": "PASS", "files": len(archive["files"]), "reportedJvmGate": "PASS", "native": "NOT_RUN",
            "postrunCoreFiles": len(provenance["files"]),
            "verificationScope": "Archive integrity including explicitly post-run classpath provenance; no JVM gate rerun or before/during hash claim."}


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
