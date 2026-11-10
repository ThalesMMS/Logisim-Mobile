#!/usr/bin/env python3
"""Offline qualification-tool checks. This script never contacts an app or device."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

from portable_receipt_contract import validate_ui_exports

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("portable_scenarios", ROOT / "scripts/android_portable_scenarios.py")
portable = importlib.util.module_from_spec(spec)
spec.loader.exec_module(portable)


class FakeUi:
    package = "qualification.offline.e2e"
    serial = "OFFLINE_NO_DEVICE"

    def __init__(self, output, operations):
        self.output = output
        self.operations = operations

    def adb(self, *args, **_options):
        if args[-1] == "files":
            return "\n".join(self.operations)
        return json.dumps(self.operations[args[-1].removeprefix("files/")])


class PublishingUi:
    def __init__(self, output, digests, metadata=None):
        self.output = output
        self.digests = digests
        self.metadata = metadata or ["is_download=1, is_pending=0"]
        self.calls = []

    def adb(self, *arguments):
        self.calls.append(arguments)
        if arguments[:2] == ("shell", "sha256sum"):
            digest = next(value for suffix, value in self.digests.items() if arguments[-1].endswith("/" + suffix))
            return digest + "  " + arguments[-1] + "\n"
        if arguments[:3] == ("shell", "am", "broadcast"):
            return "Broadcast completed: result=0\n"
        if len(arguments) == 2 and arguments[0] == "shell" and arguments[1].startswith("content query "):
            return self.metadata.pop(0) if len(self.metadata) > 1 else self.metadata[0]
        raise AssertionError(f"Unexpected fixture operation: {arguments}")


class OfflinePortablePreparationTest(unittest.TestCase):
    def test_portable_mode_runs_only_the_requested_existing_group(self):
        for mode, expected in (("bundle", ["portable-bundle-storage-roundtrip"]),
                               ("folder", ["portable-folder-storage-roundtrip"]),
                               ("all", ["portable-bundle-storage-roundtrip", "portable-folder-storage-roundtrip"])):
            with self.subTest(mode=mode):
                scenarios = type("Scenarios", (), {"ui": type("Ui", (), {"package": "qualification.e2e"})(),
                    "catalog": {key: {} for key in ("portable_title", "portable_open_bundle", "portable_open_directory", "portable_export", "portable_saved")},
                    "results": [], "run": lambda self, name, action: self.results.append(name)})()
                portable.run_portable(scenarios, mode=mode)
                self.assertEqual(expected, scenarios.results)

    def test_fixture_publication_waits_for_pending_downloads_to_become_visible(self):
        fixture = ROOT / "fixtures/portable-projects/portable53.lsebdl"
        digest = hashlib.sha256(fixture.read_bytes()).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            ui = PublishingUi(Path(directory), {"portable53.lsebdl": digest},
                              ["is_download=0, is_pending=1", "is_download=1, is_pending=0"])
            case = portable._Portable(type("Scenarios", (), {"ui": ui})())
            case.publish_staged_files("bundle", [("portable53.lsebdl", fixture)])
            report = json.loads((Path(directory) / "portable-bundle-source-publication.json").read_text())
            self.assertEqual([True], [entry["published"] for entry in report["files"]])
            self.assertEqual(digest, report["files"][0]["sha256"])
            self.assertEqual(2, sum(args[0] == "shell" and args[1].startswith("content query ") for args in ui.calls if len(args) == 2))
            self.assertEqual(1, sum(args[:3] == ("shell", "am", "broadcast") for args in ui.calls))

    def test_wrong_staged_bytes_or_unpublished_file_cannot_enter_the_native_picker(self):
        fixture = ROOT / "fixtures/portable-projects/portable53.lsebdl"
        with tempfile.TemporaryDirectory() as directory:
            ui = PublishingUi(Path(directory), {"portable53.lsebdl": "0" * 64})
            case = portable._Portable(type("Scenarios", (), {"ui": ui})())
            with self.assertRaisesRegex(AssertionError, "bytes differ"):
                case.publish_staged_files("bundle", [("portable53.lsebdl", fixture)])
            self.assertEqual(1, len(ui.calls))
            ui = PublishingUi(Path(directory), {"portable53.lsebdl": hashlib.sha256(fixture.read_bytes()).hexdigest()},
                              ["is_download=0, is_pending=1"])
            case = portable._Portable(type("Scenarios", (), {"ui": ui})())
            from unittest.mock import patch
            ticks = iter(range(100))
            with patch.object(portable.time, "monotonic", side_effect=lambda: next(ticks)), \
                    patch.object(portable.time, "sleep"), self.assertRaisesRegex(AssertionError, "not published"):
                case.publish_staged_files("bundle", [("portable53.lsebdl", fixture)])
            report = json.loads((Path(directory) / "portable-bundle-source-publication.json").read_text())
            self.assertFalse(report["files"][0]["published"])

    def test_all_transitive_folder_sources_are_published_before_the_folder_picker(self):
        pairs = [("sources/" + name, ROOT / "fixtures/portable-projects/sources" / name) for name in portable.SOURCE_PATHS]
        digests = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in pairs}
        with tempfile.TemporaryDirectory() as directory:
            ui = PublishingUi(Path(directory), digests)
            case = portable._Portable(type("Scenarios", (), {"ui": ui})())
            case.publish_staged_files("folder", pairs)
            report = json.loads((Path(directory) / "portable-folder-source-publication.json").read_text())
            self.assertEqual(["sources/main.circ", "sources/library/bridge.circ", "sources/library/cell.circ"],
                             [entry["relativePath"] for entry in report["files"]])
            self.assertTrue(all(entry["published"] for entry in report["files"]))
            self.assertEqual(3, sum(args[:3] == ("shell", "am", "broadcast") for args in ui.calls))

    def test_fixture_graph_hashes_and_modern_appearance_ports(self):
        fixture = ROOT / "fixtures/portable-projects"
        expected = json.loads((fixture / "expected.json").read_text())
        raw = (fixture / "portable53.lsebdl").read_bytes()
        self.assertEqual(expected["bundleSha256"], hashlib.sha256(raw).hexdigest())
        summary = portable._bundle(raw)
        self.assertEqual({"Top53", "Bridge53", "Cell53"}, set(summary["sourceOwners"]))
        self.assertEqual(expected["opaqueBundleEntries"], summary["opaque"])
        with zipfile.ZipFile(fixture / "portable53.lsebdl") as archive:
            projects = [ET.fromstring(archive.read(name)) for name in ("main.circ", "library/bridge.circ", "library/cell.circ")]
        self.assertEqual(11, sum(len(project.findall("circuit/comp")) for project in projects))
        self.assertEqual(7, sum(len(project.findall("circuit/wire")) for project in projects))
        ports = [port for project in projects for port in project.findall("circuit/appear/circ-port")]
        self.assertEqual(4, len(ports))
        self.assertEqual({"in", "out"}, {port.attrib["dir"] for port in ports})
        subprocess.run([str(ROOT / "scripts/prepare-portable53-fixture.py"), "--check"], check=True, stdout=subprocess.PIPE)

    def test_real_create_metadata_shape_proves_uuid_and_rejects_ambiguity(self):
        operation = {"phase": "picker", "mime": "application/x-logisim-bundle", "name": "Top53.lsebdl",
                     "payload": "00000000-0000-0000-0000-000000000001"}
        session = "12345678-1234-1234-1234-123456789abc"
        with tempfile.TemporaryDirectory() as directory:
            ui = FakeUi(Path(directory), {f"asset-{session}-create.json": operation})
            scenarios = type("Scenarios", (), {"ui": ui})()
            case = portable._Portable(scenarios)
            self.assertEqual(session, case.observe_export_window("offline"))
            self.assertNotEqual("main", case.observed_window_id)
            ui.operations[f"asset-another-session-create.json"] = operation
            with self.assertRaises(portable.PortableScenarioHandoff):
                case.observe_export_window("ambiguous")
            ui.operations.clear()
            with self.assertRaises(portable.PortableScenarioHandoff):
                case.observe_export_window("missing")

    def test_utf16_envelope_and_required_native_folder_confirmation(self):
        body = "4:A🧩B"
        data = body.encode("utf-8")
        raw = f"LOGISIM-LINK\n1\n{len(data)}-{portable._fnv(data)}\n\n{body}".encode("utf-8")
        self.assertEqual(["A🧩B"], list(portable._tokens(raw, "LINK", "1", link=True)))
        with tempfile.TemporaryDirectory() as directory:
            scenarios = type("Scenarios", (), {"ui": FakeUi(Path(directory), {})})()
            with self.assertRaises(portable.PortableScenarioHandoff):
                portable._Portable(scenarios).folder()

    def test_both_export_paths_hashes_and_restart_stages_belong_to_the_same_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "after.lsebdl"
            peer = root / "before.lsebdl"
            artifact.write_bytes(b"offline after export")
            peer.write_bytes(b"offline before export")
            (root / "capture.txt").write_text("offline receipt schema test, not native evidence")
            receipt = {"platform": "android", "executedViaUi": True, "status": "PASSED", "appArtifactSha256": "a" * 64,
                       "evidence": ["capture.txt"], "exports": [
                           {"path": artifact.name, "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(), "afterRestart": True},
                           {"path": peer.name, "sha256": hashlib.sha256(peer.read_bytes()).hexdigest(), "afterRestart": False}]}
            receipt_path = root / "ui.json"
            def validate():
                receipt_path.write_text(json.dumps(receipt))
                return validate_ui_exports(receipt_path, artifact, peer, ROOT / "fixtures")
            _, checked = validate()
            self.assertEqual(["artifact", "peer"], [entry["role"] for entry in checked])
            self.assertEqual([True, False], [entry["afterRestart"] for entry in checked])
            self.assertEqual([str(artifact.resolve()), str(peer.resolve())], [entry["path"] for entry in checked])
            good = receipt["exports"][1].copy()
            receipt["exports"] = receipt["exports"][:1]
            with self.assertRaisesRegex(ValueError, "peer export path"):
                validate()
            receipt["exports"].append(good.copy())
            receipt["exports"][1]["sha256"] = "0" * 64
            with self.assertRaisesRegex(ValueError, "peer export hash"):
                validate()
            receipt["exports"][1] = dict(good, afterRestart=True)
            with self.assertRaisesRegex(ValueError, "opposite"):
                validate()
            receipt["exports"][1] = good
            receipt["exports"].append(good.copy())
            with self.assertRaisesRegex(ValueError, "exactly once"):
                validate()
            receipt["exports"].pop()
            with self.assertRaisesRegex(ValueError, "distinct"):
                validate_ui_exports(receipt_path, artifact, artifact, ROOT / "fixtures")
            with self.assertRaisesRegex(ValueError, "checked-in input fixtures"):
                validate_ui_exports(receipt_path, artifact, ROOT / "fixtures/portable-projects/portable53.lsebdl", ROOT / "fixtures")
            alias = root / "aliased-export.lsebdl"
            os.link(artifact, alias)
            with self.assertRaisesRegex(ValueError, "not aliases"):
                validate_ui_exports(receipt_path, artifact, alias, ROOT / "fixtures")

    def test_new_copy_cannot_reuse_any_nonactive_project_or_drop_prior_entries(self):
        previous = {"project": {"id": "current"}, "catalogProjectIds": ["old-copy", "current"]}
        reused = {"project": {"id": "old-copy"}, "catalogProjectIds": ["old-copy", "current"]}
        self.assertNotEqual(previous["project"]["id"], reused["project"]["id"])
        with self.assertRaisesRegex(AssertionError, "reused an existing catalog"):
            portable._assert_fresh_copy(previous, reused)
        fresh = {"project": {"id": "fresh"}, "catalogProjectIds": ["old-copy", "current", "fresh"]}
        portable._assert_fresh_copy(previous, fresh)
        with self.assertRaisesRegex(AssertionError, "reused an existing catalog"):
            portable._assert_fresh_copy(fresh, reused)
        dropped = {"project": {"id": "another"}, "catalogProjectIds": ["fresh", "another"]}
        with self.assertRaisesRegex(AssertionError, "removed an earlier catalog"):
            portable._assert_fresh_copy(fresh, dropped)

    def wait_case(self, snapshots, *, owner=None, observed=None, delay=0):
        case = object.__new__(portable._Portable)
        case.s = type('Scenarios', (), {'portable_window_id': owner})()
        case.observed_window_id = observed
        clock = [0.0]
        calls = []
        states = iter(snapshots)
        last = [None]

        def snapshot(name, *, timeout):
            calls.append(timeout)
            clock[0] += delay
            last[0] = next(states, last[0])
            return last[0]

        case.snapshot = snapshot
        self.enterContext(patch.object(portable.time, 'monotonic', side_effect=lambda: clock[0]))
        self.enterContext(patch.object(portable.time, 'sleep', side_effect=lambda seconds: clock.__setitem__(0, clock[0] + seconds)))
        return case, clock, calls

    def test_fresh_catalog_waits_for_the_durable_window_link_before_returning(self):
        previous = {'project': {'id': 'old'}, 'catalogProjectIds': ['old']}
        pending = {'project': {'id': 'fresh'}, 'catalogProjectIds': ['old', 'fresh'], 'windows': {'session': 'old'}}
        committed = {**pending, 'windows': {'session': 'fresh'}}
        case, clock, calls = self.wait_case([pending, committed])
        self.assertIs(committed, case.wait_snapshot('imported', previous=previous))
        self.assertEqual(2, len(calls))
        self.assertLess(calls[1], calls[0])
        self.assertLess(clock[0], 30)

    def test_stale_window_link_never_completes_or_renews_the_commit_deadline(self):
        previous = {'project': {'id': 'old'}, 'catalogProjectIds': ['old']}
        pending = {'project': {'id': 'fresh'}, 'catalogProjectIds': ['old', 'fresh'], 'windows': {'session': 'old'}}
        case, clock, calls = self.wait_case([pending])
        with self.assertRaisesRegex(AssertionError, 'within 30s.*No durable window link'):
            case.wait_snapshot('imported', previous=previous)
        self.assertAlmostEqual(30, clock[0])
        self.assertGreater(len(calls), 1)
        self.assertTrue(all(right < left for left, right in zip(calls, calls[1:])))

    def test_known_session_must_commit_even_when_another_window_already_points_to_the_copy(self):
        previous = {'project': {'id': 'old'}, 'catalogProjectIds': ['old']}
        pending = {'project': {'id': 'fresh'}, 'catalogProjectIds': ['old', 'fresh'],
                   'windows': {'actual-session': 'old', 'another-session': 'fresh'}}
        for identity_source in ('owner', 'observed'):
            with self.subTest(identity_source=identity_source):
                case, _, _ = self.wait_case([pending], **{identity_source: 'actual-session'})
                with self.assertRaisesRegex(AssertionError, 'within 30s.*actual window link'):
                    case.wait_snapshot('imported', previous=previous)

    def test_late_snapshot_cannot_complete_after_the_same_commit_deadline(self):
        previous = {'project': {'id': 'old'}, 'catalogProjectIds': ['old']}
        committed = {'project': {'id': 'fresh'}, 'catalogProjectIds': ['old', 'fresh'], 'windows': {'session': 'fresh'}}
        case, _, calls = self.wait_case([committed], delay=30.1)
        with self.assertRaisesRegex(AssertionError, 'within 30s.*returned after'):
            case.wait_snapshot('imported', previous=previous)
        self.assertEqual([30], calls)

    def test_preparation_cannot_be_mislabelled_as_an_app_export(self):
        command = ["python3", str(ROOT / "scripts/verify-portable53.py"), "--core-jar", "not-used.jar",
                   "--desktop-jar", "not-used.jar", "--artifact", str(ROOT / "fixtures/portable-projects/portable53.lsebdl"),
                   "--origin", "app-export", "--output", "/tmp/portable53-invalid-proof-must-not-exist.json"]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("requires --ui-receipt", result.stderr)
        receipt = json.loads((ROOT / "fixtures/portable-projects/ui-receipt.template.json").read_text())
        self.assertEqual("NOT_RUN", receipt["status"])
        self.assertFalse(receipt["executedViaUi"])


if __name__ == "__main__":
    unittest.main()
