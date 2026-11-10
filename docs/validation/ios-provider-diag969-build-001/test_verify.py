#!/usr/bin/env python3
"""Portable archive checks and mutations that must invalidate the evidence.

Set VERIFY_HOST_REPLAY=1 to include the explicit host Swift replay. The default
suite uses Python only and never calls a build, signing, SDK, or device tool.
"""
from __future__ import annotations

from contextlib import contextmanager
from copy import deepcopy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("portable_verify", ROOT / "verify.py")
assert SPEC is not None and SPEC.loader is not None
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def inventory(root: Path) -> None:
    files = {}
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.name == "inventory.json" or "__pycache__" in path.parts:
            continue
        data = path.read_bytes()
        files[path.relative_to(root).as_posix()] = {"bytes": len(data), "sha256": digest(data)}
    write_json(root / "inventory.json", {"schemaVersion": 1, "files": files})


@contextmanager
def relocated_archive():
    with tempfile.TemporaryDirectory(prefix="diag969-portable-test-") as temporary:
        root = Path(temporary) / "relocated evidence"
        shutil.copytree(ROOT, root, ignore=shutil.ignore_patterns("__pycache__", "inventory.json"))
        inventory(root)
        yield root


def manifests(root: Path):
    return read_json(root / "producer969/artifact.json"), read_json(root / "reference601/artifact.json")


class ArchiveVerificationTests(unittest.TestCase):
    def test_archive_passes_after_relocation_without_external_commands(self):
        with relocated_archive() as root:
            with mock.patch.object(subprocess, "run", side_effect=AssertionError("external command")), \
                    mock.patch.object(subprocess, "check_output", side_effect=AssertionError("external command")), \
                    mock.patch.object(subprocess, "Popen", side_effect=AssertionError("external command")):
                self.assertEqual(VERIFY.verify_archive(root)["status"], "PASS")

    def test_default_cli_runs_from_unrelated_working_directory(self):
        with relocated_archive() as root, tempfile.TemporaryDirectory(prefix="diag969-unrelated-cwd-") as cwd:
            result = subprocess.run([sys.executable, str(root / "verify.py")], cwd=cwd,
                                    text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(json.loads(result.stdout)["status"], "PASS")

    def test_missing_inventory_entry_is_rejected(self):
        with relocated_archive() as root:
            value = read_json(root / "inventory.json")
            value["files"].pop("sdk-review/spec.json")
            write_json(root / "inventory.json", value)
            with self.assertRaises(VERIFY.VerificationError):
                VERIFY.verify_inventory(root)

    def test_inventory_traversal_is_rejected(self):
        with relocated_archive() as root:
            value = read_json(root / "inventory.json")
            value["files"]["../external-file"] = {"bytes": 0, "sha256": digest(b"")}
            write_json(root / "inventory.json", value)
            with self.assertRaises(VERIFY.VerificationError):
                VERIFY.verify_inventory(root)

    def test_inventory_payload_tamper_is_rejected(self):
        with relocated_archive() as root:
            with (root / "sdk-review/review.md").open("ab") as handle:
                handle.write(b"\nchanged\n")
            with self.assertRaises(VERIFY.VerificationError):
                VERIFY.verify_inventory(root)

    def test_native_claim_is_rejected_even_after_inventory_rehash(self):
        with relocated_archive() as root:
            manifest, _ = manifests(root)
            manifest["nativeExecution"] = "PASS"
            write_json(root / "producer969/artifact.json", manifest)
            inventory(root)
            VERIFY.verify_inventory(root)
            with self.assertRaises(VERIFY.VerificationError):
                VERIFY.verify_claims(manifest)
            with self.assertRaises(VERIFY.VerificationError):
                VERIFY.verify_archive(root)

    def test_manifest_anchor_survives_inventory_rehash(self):
        with relocated_archive() as root:
            manifest, _ = manifests(root)
            manifest["sourceRoot"] = "/different/historical/source"
            write_json(root / "producer969/artifact.json", manifest)
            inventory(root)
            VERIFY.verify_inventory(root)
            with self.assertRaisesRegex(VERIFY.VerificationError, "Literal manifest SHA256 pin"):
                VERIFY.verify_archive(root)

    def test_rehashed_protected_source_change_is_rejected(self):
        with relocated_archive() as root:
            manifest, reference = manifests(root)
            relative = "Shared/FixtureStore.swift"
            path = root / "producer969/fixture-source" / relative
            path.write_bytes(path.read_bytes() + b"\n// altered protected source\n")
            manifest["sourceFiles"][relative] = digest(path.read_bytes())
            inventory(root)
            with self.assertRaises(VERIFY.VerificationError):
                VERIFY.verify_source_proof(root, manifest, reference)

    def test_raw_byte_proof_rejects_crlf_in_mutation_body(self):
        with relocated_archive() as root:
            manifest, reference = manifests(root)
            relative = "Provider/RevocationFileProvider.swift"
            path = root / "producer969/fixture-source" / relative
            original = path.read_bytes()
            marker = b"{ beginCommit in\n"
            self.assertEqual(original.count(marker), 1)
            path.write_bytes(original.replace(marker, b"{ beginCommit in\r\n", 1))
            manifest["sourceFiles"][relative] = digest(path.read_bytes())
            inventory(root)
            with self.assertRaises(VERIFY.VerificationError):
                VERIFY.verify_source_proof(root, manifest, reference)

    def test_generated_and_retained_section_mismatch_is_rejected(self):
        with relocated_archive() as root:
            manifest, reference = manifests(root)
            item = manifest["simulatorEntitlements"]["App"]["packagingInputs"]["__entitlements"]
            path = root / "producer969/retained" / item["path"]
            original = path.read_bytes()
            self.assertIn(b"P4VGX44GD8", original)
            changed = original.replace(b"P4VGX44GD8", b"Q4VGX44GD8", 1)
            path.write_bytes(changed)
            item.update(bytes=len(changed), sha256=digest(changed))
            inventory(root)
            with self.assertRaises(VERIFY.VerificationError):
                VERIFY.verify_entitlements(root, manifest, reference)

    def test_der_semantics_are_checked_after_matching_pair_rehash(self):
        with relocated_archive() as root:
            manifest, reference = manifests(root)
            entitlement = manifest["simulatorEntitlements"]["Provider"]
            main = next(image for image in entitlement["images"] if image["image"] == "main")
            section = main["sections"]["__ents_der"]
            generated = entitlement["packagingInputs"]["__ents_der"]
            data = (root / "producer969/retained" / section["path"]).read_bytes()
            self.assertIn(b"P4VGX44GD8", data)
            changed = data.replace(b"P4VGX44GD8", b"Q4VGX44GD8", 1)
            for item in (section, generated):
                (root / "producer969/retained" / item["path"]).write_bytes(changed)
                item.update(bytes=len(changed), sha256=digest(changed))
            reference["simulatorEntitlements"]["Provider"]["images"][0]["sections"]["__ents_der"]["sha256"] = digest(changed)
            inventory(root)
            with self.assertRaisesRegex(VERIFY.VerificationError, "XML/DER semantic entitlement mismatch"):
                VERIFY.verify_entitlements(root, manifest, reference)

    def test_sdk_option_availability_change_is_rejected(self):
        with relocated_archive() as root:
            value = read_json(root / "sdk-review/spec.json")
            value["optionBits"][1]["iOSAvailability"] = "16.0+"
            write_json(root / "sdk-review/spec.json", value)
            inventory(root)
            with self.assertRaises(VERIFY.VerificationError):
                VERIFY.verify_sdk_review(root)


class BundleIndexTests(unittest.TestCase):
    def reject_index_mutation(self, mutate):
        with relocated_archive() as root:
            value = read_json(root / "bundle-index.json")
            mutate(value)
            write_json(root / "bundle-index.json", value)
            inventory(root)
            manifest, reference = manifests(root)
            with self.assertRaises(VERIFY.VerificationError):
                VERIFY.verify_bundle_index(root, manifest, reference)

    def test_incomplete_file_index_is_rejected(self):
        self.reject_index_mutation(lambda value: value["artifacts"]["DIAG969"]["bundles"]["app"]["files"].pop())

    def test_duplicate_relative_path_is_rejected(self):
        def mutate(value):
            files = value["artifacts"]["DIAG969"]["bundles"]["provider"]["files"]
            files.append(deepcopy(files[0]))
        self.reject_index_mutation(mutate)

    def test_index_path_traversal_is_rejected(self):
        def mutate(value):
            value["artifacts"]["DIAG969"]["bundles"]["app"]["files"][0]["relativePath"] = "../Info.plist"
        self.reject_index_mutation(mutate)

    def test_bundle_tree_hash_is_recomputed(self):
        def mutate(value):
            value["artifacts"]["DIAG969"]["bundles"]["app"]["bundleTreeSHA256"] = "0" * 64
        self.reject_index_mutation(mutate)

    def test_executable_hash_is_tied_to_file_index(self):
        def mutate(value):
            value["artifacts"]["DIAG969"]["bundles"]["provider"]["executable"]["sha256"] = "0" * 64
        self.reject_index_mutation(mutate)

    def test_preview_sections_cannot_be_reported_as_main_only(self):
        def mutate(value):
            value["summary"]["mainOnlyAcrossAllBundleMachO"] = True
        self.reject_index_mutation(mutate)


class MaskDecodingTests(unittest.TestCase):
    def test_empty_mask(self):
        self.assertEqual(VERIFY.decode_fields(0), {"rawDecimal": "0", "names": [], "unknownDecimal": "0"})

    def test_high_unknown_bit_is_not_discarded(self):
        raw = (1 << 63) | (1 << 10) | 1
        self.assertEqual(VERIFY.decode_fields(raw), {
            "rawDecimal": str(raw), "names": ["contents", "typeAndCreator"],
            "unknownDecimal": str(1 << 63),
        })

    def test_all_uint64_bits_preserve_unknown_mask(self):
        value = VERIFY.decode_fields((1 << 64) - 1)
        self.assertEqual(value["unknownDecimal"], str(((1 << 64) - 1) ^ 2047))
        self.assertEqual(len(value["names"]), 11)

    def test_invalid_raw_masks_are_rejected(self):
        for raw in (True, -1, 1 << 64, "1", 1.0, None):
            with self.subTest(raw=raw), self.assertRaises(VERIFY.VerificationError):
                VERIFY.decode_fields(raw)


@unittest.skipUnless(os.environ.get("VERIFY_HOST_REPLAY") == "1" and shutil.which("swiftc"),
                     "explicit host replay requires VERIFY_HOST_REPLAY=1 and host swiftc")
class OptionalHostReplayTests(unittest.TestCase):
    def test_host_replay_after_relocation(self):
        with relocated_archive() as root, tempfile.TemporaryDirectory(prefix="diag969-host-cwd-") as cwd:
            result = subprocess.run([sys.executable, str(root / "verify.py"), "--host-replay"],
                                    cwd=cwd, text=True, capture_output=True, timeout=180)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report["status"], "PASS")
            self.assertIn("79", result.stdout)


if __name__ == "__main__":
    unittest.main()
