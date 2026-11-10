#!/usr/bin/env python3
"""Check independent Files-provider fixtures using temporary containers and fake simctl."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


SPEC = importlib.util.spec_from_file_location("ios_external_peer_runner", Path(__file__).with_name("run-ios-e2e.py"))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
SIMULATOR = "20A4D87D-A2A6-46BD-A889-C4888CE1E152"
RECEIVER_ID = "thalesmms.logisim.validation.clipboardreceiver"
RUN_ID = "f0e1d2c3b4a5"
PEER_TITLE = "IOS_PEER_f0e1d2c3b4a5"
ORIGINAL_SHA256 = "7ae88b3eda4c7124124655f954b9233e09aeeb69a6f1f8cf95cf7e370a5e141f"


class IosExternalPeerTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        previous = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, previous)
        self.entered_native_process = patch.object(
            subprocess, "run", side_effect=AssertionError("A unit test attempted to start a native process")
        )
        self.entered_native_process.start()
        self.addCleanup(self.entered_native_process.stop)
        self.primary = self.root / "primary-container"
        self.documents = self.primary / "Documents/LogisimE2E" / RUN_ID
        self.documents.mkdir(parents=True)
        self.peer_container = self.root / "receiver-container"
        self.peer_container.mkdir()
        self.receiver = self.root / "ClipboardReceiver.app"
        self.receiver.mkdir()
        self.info = {
            "CFBundleIdentifier": RECEIVER_ID,
            "CFBundleDisplayName": "Clipboard Receiver",
            "CFBundleName": "ClipboardReceiver",
            "UIFileSharingEnabled": True,
            "LSSupportsOpeningDocumentsInPlace": True,
        }
        self.write_info()
        self.commands = []
        self.container_result = str(self.peer_container)

        def command(arguments):
            self.commands.append(list(arguments))
            self.assertEqual(
                ["xcrun", "simctl", "get_app_container", SIMULATOR, RECEIVER_ID, "data"], list(arguments)
            )
            return self.container_result

        self.common = SimpleNamespace(run=command)

    def write_info(self):
        with (self.receiver / "Info.plist").open("wb") as stream:
            plistlib.dump(self.info, stream)

    def stage(self, **overrides):
        arguments = {
            "common": self.common,
            "simulator": SIMULATOR,
            "receiver": self.receiver,
            "primary_container": self.primary,
            "documents": self.documents,
            "run_id": RUN_ID,
        }
        arguments.update(overrides)
        return runner.stage_external_peer(**arguments)

    @property
    def peer_documents(self):
        return self.peer_container / "Documents/LogisimE2E" / RUN_ID

    @property
    def sidecar(self):
        return self.documents / "external-peer.json"

    def test_stages_a_distinct_provider_with_literal_provenance_and_fixture_hash(self):
        metadata = self.stage()
        expected = {
            "bundleID": RECEIVER_ID,
            "displayName": "Clipboard Receiver",
            "documents": str(self.peer_documents),
            "fixture": str(self.peer_documents / "peer.circ"),
            "control": str(self.peer_documents / "control.circ"),
            "originalSHA256": ORIGINAL_SHA256,
        }
        self.assertEqual(expected, metadata)
        self.assertEqual({"externalPeer": expected}, json.loads(self.sidecar.read_text()))
        fixture = Path(metadata["fixture"])
        control = Path(metadata["control"])
        original = fixture.read_bytes()
        self.assertEqual(1489, len(original))
        self.assertEqual(ORIGINAL_SHA256, hashlib.sha256(original).hexdigest())
        self.assertEqual(runner.fixture_xml(PEER_TITLE, tools=True), original)
        self.assertEqual(original, control.read_bytes())
        project = ET.fromstring(original)
        self.assertEqual(PEER_TITLE, project.find("main").attrib["name"])
        self.assertEqual([PEER_TITLE], [c.attrib["name"] for c in project.findall("circuit")])
        self.assertEqual(3, len(project.findall("circuit/comp")))
        self.assertEqual(2, len(project.findall("circuit/wire")))
        self.assertEqual(["E2E_IN", "E2E_NOT", "E2E_OUT"], [
            value.attrib["val"] for value in project.findall("circuit/comp/a") if value.attrib["name"] == "label"
        ])
        self.assertFalse(fixture.is_relative_to(self.primary))
        self.assertFalse(self.primary.is_relative_to(self.peer_container))
        self.assertEqual(1, len(self.commands))

    def test_editing_the_peer_preserves_the_control_and_original_provenance(self):
        metadata = self.stage()
        fixture = Path(metadata["fixture"])
        control = Path(metadata["control"])
        source = control.read_bytes()
        fixture.write_bytes(b"<?xml version='1.0'?><project><main name='Changed'/></project>\n")
        self.assertNotEqual(source, fixture.read_bytes())
        self.assertEqual(source, control.read_bytes(), "The control must not be a hard link to the editable fixture")
        self.assertEqual(ORIGINAL_SHA256, hashlib.sha256(control.read_bytes()).hexdigest())
        self.assertEqual({"externalPeer": metadata}, json.loads(self.sidecar.read_text()))
        self.assertEqual(ORIGINAL_SHA256, metadata["originalSHA256"])

    def test_provider_display_name_comes_from_the_built_app_info(self):
        self.info["CFBundleDisplayName"] = "Independent Files Provider"
        self.write_info()
        self.assertEqual("Independent Files Provider", self.stage()["displayName"])

    def test_built_bundle_name_is_used_when_the_optional_display_name_is_empty(self):
        self.info["CFBundleDisplayName"] = ""
        self.info["CFBundleName"] = "Renamed Peer"
        self.write_info()
        self.assertEqual("Renamed Peer", self.stage()["displayName"])

    def test_relative_container_output_is_rejected_without_staging(self):
        self.container_result = "relative-receiver-container"
        with self.assertRaises(ValueError):
            self.stage()
        self.assertFalse((self.root / self.container_result).exists())
        self.assertFalse(self.sidecar.exists())

    def test_missing_and_non_directory_containers_are_rejected(self):
        missing = self.root / "missing-receiver"
        plain_file = self.root / "not-a-container"
        plain_file.write_bytes(b"not a directory")
        for path in [missing, plain_file]:
            with self.subTest(container=path):
                self.container_result = str(path)
                with self.assertRaises(ValueError):
                    self.stage()
        self.assertFalse(missing.exists())
        self.assertEqual(b"not a directory", plain_file.read_bytes())
        self.assertFalse(self.sidecar.exists())

    def test_primary_container_and_its_descendants_are_not_an_external_peer(self):
        nested = self.primary / "nested-container"
        nested.mkdir()
        for container in [self.primary, nested, self.documents]:
            with self.subTest(container=container):
                self.container_result = str(container)
                with self.assertRaises(ValueError):
                    self.stage()
        self.assertFalse(self.sidecar.exists())

    def test_receiver_ancestor_of_the_primary_container_is_rejected(self):
        self.container_result = str(self.root)
        with self.assertRaises(ValueError):
            self.stage()
        self.assertFalse(self.sidecar.exists())

    def test_a_container_symlink_cannot_disguise_overlap_with_the_primary(self):
        alias = self.root / "primary-alias"
        alias.symlink_to(self.primary, target_is_directory=True)
        self.container_result = str(alias)
        with self.assertRaises(ValueError):
            self.stage()
        self.assertFalse(self.sidecar.exists())

    def test_documents_symlink_cannot_stage_files_outside_the_receiver(self):
        outside = self.root / "outside-documents"
        outside.mkdir()
        (self.peer_container / "Documents").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.stage()
        self.assertEqual([], list(outside.iterdir()))
        self.assertFalse(self.sidecar.exists())

    def test_nested_staging_symlink_cannot_escape_the_receiver(self):
        outside = self.root / "outside-staging"
        outside.mkdir()
        documents = self.peer_container / "Documents"
        documents.mkdir()
        (documents / "LogisimE2E").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.stage()
        self.assertEqual([], list(outside.iterdir()))
        self.assertFalse(self.sidecar.exists())

    def test_primary_documents_symlink_cannot_relocate_evidence_outside_its_container(self):
        moved = self.root / "moved-primary-documents"
        primary_documents = self.primary / "Documents"
        primary_documents.rename(moved)
        primary_documents.symlink_to(moved, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.stage()
        self.assertFalse(self.sidecar.exists())
        self.assertFalse(self.peer_documents.exists())

    def test_reused_run_directory_preserves_existing_fixture_bytes(self):
        self.peer_documents.mkdir(parents=True)
        fixture = self.peer_documents / "peer.circ"
        control = self.peer_documents / "control.circ"
        fixture.write_bytes(b"existing peer")
        control.write_bytes(b"existing control")
        with self.assertRaises(ValueError):
            self.stage()
        self.assertEqual(b"existing peer", fixture.read_bytes())
        self.assertEqual(b"existing control", control.read_bytes())
        self.assertFalse(self.sidecar.exists())

    def test_existing_sidecar_is_not_overwritten(self):
        self.sidecar.write_bytes(b"existing source evidence\n")
        with self.assertRaises(ValueError):
            self.stage()
        self.assertEqual(b"existing source evidence\n", self.sidecar.read_bytes())
        self.assertFalse(self.peer_documents.exists())

    def test_invalid_run_id_cannot_create_or_escape_the_staging_directory(self):
        for value in ["", "../escape", "/escape", "f0e1d2c3b4a5/child", "F0E1D2C3B4A5", "f0e1d2c3b4a", "not-a-run-id"]:
            with self.subTest(run_id=value):
                with self.assertRaises(ValueError):
                    self.stage(run_id=value)
        self.assertFalse((self.peer_container / "Documents").exists())
        self.assertFalse(self.sidecar.exists())

    def test_info_must_identify_the_fixed_receiver_bundle(self):
        self.info["CFBundleIdentifier"] = "thalesmms.logisim.mobile"
        self.write_info()
        with self.assertRaises(ValueError):
            self.stage()
        self.assertFalse(self.sidecar.exists())

    def test_both_files_provider_flags_must_be_boolean_true(self):
        for flag in ["UIFileSharingEnabled", "LSSupportsOpeningDocumentsInPlace"]:
            for value in [False, "YES", 1, None]:
                with self.subTest(flag=flag, value=value):
                    self.info[flag] = value
                    if value is None:
                        self.info.pop(flag)
                    self.write_info()
                    with self.assertRaises(ValueError):
                        self.stage()
            self.info[flag] = True
        self.assertFalse(self.sidecar.exists())

    def test_built_info_requires_a_real_display_name(self):
        self.info["CFBundleName"] = ""
        for name in ["", "   "]:
            with self.subTest(display_name=name):
                self.info["CFBundleDisplayName"] = name
                self.write_info()
                with self.assertRaises(ValueError):
                    self.stage()
        self.assertFalse(self.sidecar.exists())

    def test_unexpanded_build_setting_is_not_a_real_files_provider_name(self):
        self.info["CFBundleDisplayName"] = "$(PRODUCT_NAME)"
        self.write_info()
        with self.assertRaises(ValueError):
            self.stage()
        self.assertFalse(self.sidecar.exists())

    def test_missing_and_invalid_built_plists_are_rejected(self):
        info = self.receiver / "Info.plist"
        info.unlink()
        with self.assertRaises(FileNotFoundError):
            self.stage()
        info.write_bytes(b"invalid plist data")
        with self.assertRaises(plistlib.InvalidFileException):
            self.stage()
        self.assertFalse(self.sidecar.exists())


if __name__ == "__main__":
    unittest.main()
