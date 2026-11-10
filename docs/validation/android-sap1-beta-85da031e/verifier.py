#!/usr/bin/env python3
"""Verify the archived Android Beta SAP-1 receipt without a device or app code."""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


APP = "thalesmms.logisim.mobile.e2e"
SOURCE = "85da031e4ed11474c2e763976392729810ef142d"
PRODUCTION_SOURCE = "6cef05429cf66be23569aaebc3c72950cf3fe53a"
HARNESS = "8cf98577877f368af927ace72450b03d2fb5e512"
APK_HASHES = {
    APP: "67e81d691beb3830cf49325c81ccffca9b66f2bf8aa98fdc46c9ee7d1f9e5e2c",
    APP + ".test": "c439cef16d4f9ccced20189d12b22b731449b17c5417dbe25d65167ad7e3d882",
    "thalesmms.logisim.mobile.snapshot.e2e.test": "2f17d8af178460537e2e4106d8ea79063fa938b0fdebcae1710b9b0294fee387",
}
EDITOR_SHA = "d28e2473df36c503a0963c6b6720623a83c9e82bbd809d85df4decc4326fc27b"
SOURCE_HASHES = {
    "e2e-android.py": EDITOR_SHA,
    "android_ui.py": "d12f747df683593646938e93aafacee4f62a45fc42dcefde0174b68881fb1674",
    "android_editing_scenarios.py": "0b97319487924cf371eec29b8f0b9c7ec7a553230ea5ebd9149ff5d041556673",
    "user_scenario_contract.py": "858dc5e43c70a28898afaa16341b786b203696153cc33c2e43c8314a4572c9eb",
    "android_inspector_scenarios.py": "74e686a0448e4e6c29729f19cbf04c67922368bab65ebb69a7d238004582f40c",
    "android_io_showcase_scenarios.py": "4aa42d7f7d3bd95922cef3cf9609db9dc1d57d77a701619a5e90c71a59c983e8",
    "android_portable_scenarios.py": "6e5888e73ddb558d038f22c45b517e182a46a991f43d878427a65f724ee5c7b4",
    "android_visual_export34_scenarios.py": "aa2c353709fd35398f39a32592a4e8b1a2029a21e042702678b14b772a036763",
}
DOCUMENT_SHA = "259d61bc36d2ad2b837df948eefca5f55adca2b7b3f0fc31fd41bbd88a16e298"
PNG_SHA = "373aebbb080a768f9261c7038e16c9a44b7ceaf7e2d11106c81081d2bebc73d1"


class VerificationError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def digest(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def verify_inventory(base: Path) -> int:
    entries = {}
    for line in (base / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (raw/.+)", line)
        require(match is not None, "Malformed SHA256SUMS entry")
        checksum, name = match.groups()
        relative = Path(name)
        require(not relative.is_absolute() and ".." not in relative.parts, "Unsafe inventory path")
        require(name not in entries, "Duplicate inventory entry")
        entries[name] = checksum
    observed = {p.relative_to(base).as_posix() for p in (base / "raw").rglob("*") if p.is_file()}
    require(set(entries) == observed, "Inventory does not cover the complete raw directory")
    for name, checksum in entries.items():
        require(digest((base / name).read_bytes()) == checksum, "Inventory hash mismatch: " + name)
    return len(entries)


def verify_runner_source(source: str) -> None:
    """Check the executed assertion sequence, not the application's implementation."""
    module = ast.parse(source)
    owner = next(node for node in module.body if isinstance(node, ast.ClassDef) and node.name == "Scenarios")
    sequential = next(node for node in owner.body if isinstance(node, ast.FunctionDef) and node.name == "sequential")
    nodes = list(ast.walk(sequential))

    def expression(text: str) -> str:
        return ast.dump(ast.parse(text, mode="eval").body, include_attributes=False)

    def line_of_assert(text: str) -> int:
        matching = [node.lineno for node in nodes if isinstance(node, ast.Assert)
                    and ast.dump(node.test, include_attributes=False) == expression(text)]
        require(len(matching) == 1, "Missing or duplicate executor assertion: " + text)
        return matching[0]

    def line_of_call(text: str) -> int:
        matching = [node.lineno for node in nodes if isinstance(node, ast.Call)
                    and ast.dump(node, include_attributes=False) == expression(text)]
        require(len(matching) == 1, "Missing or duplicate executor call: " + text)
        return matching[0]

    run = line_of_call('self.action_with_icon("action_run")')
    pause = line_of_call('self.action_with_icon("action_pause")')
    advanced = line_of_assert("paused > 56")
    paused_sleep = line_of_call('time.sleep(self.contract["sap1"]["pauseObservationMilliseconds"] / 1000)')
    frozen = line_of_assert("self.simulation_steps() == paused")
    restored = line_of_assert("restored == xml")
    require(run < pause < advanced < paused_sleep < frozen < restored,
            "Executor Run/Pause/restart assertion order changed")


def verify(base: Path) -> dict:
    base = base.resolve()
    raw = base / "raw"
    raw_count = verify_inventory(base)

    def load(name: str):
        return json.loads((raw / name).read_text())

    manifest = load("manifest.json")
    require(manifest.get("package") == APP and manifest.get("serial") == "emulator-5556",
            "Wrong isolated application or device")
    require(manifest.get("scenario") == "sequential", "Wrong scenario")
    require(manifest.get("gitHead") == SOURCE and manifest.get("harnessGitHead") == HARNESS,
            "Wrong producer or executor source")
    require(manifest.get("deviceApi") == "35" and manifest.get("appLanguage") == "en",
            "Wrong API or application language")
    require(manifest.get("sourceChanges") == ["?? outputs-sequential-en-r1.log"],
            "Unexpected executor source changes")
    for stage in ("installedArtifactsBefore", "installedArtifactsAfter"):
        rows = manifest.get(stage, [])
        require(len(rows) == 3 and len({row.get("package") for row in rows}) == 3,
                "Incomplete or duplicated installed APK identity set")
        require({row.get("package"): row.get("sha256") for row in rows} == APK_HASHES,
                "Unexpected installed APK hash in " + stage)
    require(manifest.get("appSha256") == APK_HASHES[APP]
            and manifest.get("testSha256") == APK_HASHES[APP + ".test"]
            and manifest.get("snapshotSha256") == APK_HASHES["thalesmms.logisim.mobile.snapshot.e2e.test"],
            "Manifest APK identities disagree")
    identities = manifest["apkManifestIdentities"]
    require(identities["app"] == {"package": APP, "instrumentations": []}, "Unexpected editor manifest")
    for role, package, target in (("test", APP + ".test", APP),
            ("snapshot", "thalesmms.logisim.mobile.snapshot.e2e.test", "thalesmms.logisim.mobile.snapshot.e2e.test")):
        identity = identities[role]
        require(identity.get("package") == package and bool(identity.get("instrumentations")),
                "Unexpected " + role + " manifest")
        require(all(item.get("targetPackage") == target for item in identity["instrumentations"]),
                "Wrong instrumentation target")

    build = load("build-receipt.json")
    require(build.get("gitHead") == SOURCE and build.get("appProducerHead") == SOURCE
            and build.get("productionSourceIdenticalTo") == PRODUCTION_SOURCE
            and build.get("appSha256") == APK_HASHES[APP] and build.get("build") == "PASS",
            "Wrong editor build receipt")
    result = load("results.json")
    require(result == [{"scenario": "sequential-simulation-and-recovery", "status": "passed", "seconds": 245.35}],
            "The archived sequential run did not pass")
    for name, checksum in manifest["scenarioSources"].items():
        require(digest((raw / ("source-" + name)).read_bytes()) == checksum,
                "Retained executor source hash mismatch: " + name)
    source = (raw / "source-e2e-android.py").read_text()
    verify_runner_source(source)
    require(manifest["scenarioSources"] == SOURCE_HASHES, "Wrong pinned executor sources")

    contract_bytes = (raw / "runtime-contract.json").read_bytes()
    require(digest(contract_bytes) == manifest["scenarioContractSha256"], "Runtime contract hash mismatch")
    contract = json.loads(contract_bytes)["sap1"]
    expected_contract = {"circuitCount": 7, "componentCount": 156, "wireCount": 318,
                         "manualHalfCycles": 56, "assertCounterEvery": 8,
                         "displayNibbles": ["0000", "1000"], "runMilliseconds": 1500,
                         "pauseObservationMilliseconds": 2500}
    require(all(contract.get(key) == value for key, value in expected_contract.items()),
            "The literal SAP counter, output, or Pause contract changed")
    for count in range(0, 57, 8):
        observation = load(f"simulation-steps-{count}.json")
        require(observation.get("expected") == count and observation.get("timeoutSeconds") == 30
                and observation.get("status") == "passed", "Wrong recorded manual half-cycle checkpoint")
        counts = [item.get("count") for item in observation.get("observations", [])]
        require(bool(counts) and counts[-1] == count
                and all(type(value) is int and 0 <= value <= count for value in counts),
                "Manual half-cycle observation does not reach its checkpoint")

    original = (raw / "sequential-sap1.circ").read_bytes()
    restored = (raw / "sequential-restored.circ").read_bytes()
    require(original == restored, "Restarted document is not byte-identical to the export")
    for data in (original, restored):
        project = ET.fromstring(data)
        counts = (len(project.findall("circuit")), len(project.findall(".//comp")), len(project.findall(".//wire")))
        require(counts == (7, 156, 318), "Wrong SAP project structure")
        require(len(data) == 52746 and digest(data) == DOCUMENT_SHA, "Wrong complete SAP document")

    def inspect_ui(name: str, steps: int, outputs: list[str]) -> None:
        tree = ET.parse(raw / name).getroot()
        counters = [node for node in tree.iter("node") if node.get("text") == f"Steps {steps}"]
        require(len(counters) == 1 and counters[0].get("package") == APP, "Wrong visible UI step counter")
        descriptions = [node.get("content-desc", "") for node in tree.iter("node")
                        if node.get("content-desc", "").startswith("Circuit area.")]
        require(len(descriptions) == 1, "Ambiguous circuit output description")
        nibbles = re.findall(r"Output: ([01?E]{4})(?=,|\.)", descriptions[0])[-2:]
        require(nibbles == outputs, "Wrong visible SAP display nibbles")

    inspect_ui("sap1-at-half-cycle-56-ui.xml", 56, ["0000", "1000"])
    inspect_ui("sequential-simulation-and-recovery-ui.xml", 0, ["0000", "0000"])
    require(digest((raw / "sap1-at-half-cycle-56.png").read_bytes()) == PNG_SHA, "Wrong archived 08 screenshot")
    launch = (raw / "launch-02.txt").read_text()
    require(re.search(r"^Status: ok$", launch, re.MULTILINE) is not None
            and re.search(r"^LaunchState: COLD$", launch, re.MULTILINE) is not None
            and f"Activity: {APP}/dev.logisim.mobile.MainActivity" in launch,
            "Wrong cold launch receipt")
    log = (raw / "execution.log").read_text()
    require(all(f"SAP-1 manual half-cycles: {count}/56" in log for count in range(8, 57, 8))
            and "PASS: sequential-simulation-and-recovery" in log and "FAIL:" not in log,
            "Execution log does not record the complete passing sequence")
    return {"status": "PASS", "rawFiles": raw_count, "circuitCount": 7, "componentCount": 156,
            "wireCount": 318, "manualHalfCycles": 56, "displayNibbles": ["0000", "1000"],
            "documentSha256": DOCUMENT_SHA, "screenshotSha256": PNG_SHA,
            "pauseObservationMilliseconds": 2500, "pausedCounterSeparatelyRecorded": False,
            "pidContinuityQualified": False, "kernelStateRestorationQualified": False,
            "fullIssue35Qualified": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    print(json.dumps(verify(args.base), indent=2))
