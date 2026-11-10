"""Semantic controls refresh evidence hashes before expecting rejection."""
import base64
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import xml.etree.ElementTree as ET

from verifier import verify

BASE = Path(__file__).resolve().parent


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def rehash(base):
    path = base / "inventory.json"
    data = read(path)
    for name in data:
        p = base / name
        if p.is_file():
            payload = p.read_bytes()
            data[name] = {"bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}
    write(path, data)


class KeyboardReceiptTests(unittest.TestCase):
    def reject(self, change):
        with tempfile.TemporaryDirectory(prefix="keyboard-r2-negative-") as directory:
            base = Path(directory) / "proof"
            shutil.copytree(BASE, base, ignore=shutil.ignore_patterns("__pycache__"))
            change(base)
            rehash(base)
            with self.assertRaises((AssertionError, KeyError, ValueError, FileNotFoundError)):
                verify(base)

    def observations(self, base, change):
        path = base / "raw/io-showcase-observations.json"
        data = read(path)
        change(data)
        write(path, data)

    def test_retained_case_passes(self):
        result = verify(BASE)
        self.assertEqual(result["sdkComplete"], 26)
        self.assertEqual(result["formFeedReveals"], 1)
        self.assertFalse(result["fullIssue44Qualified"])

    def test_missing_backspace_probe_is_not_a_pass(self):
        self.reject(lambda b: self.observations(b, lambda d: d["automatedObservations"].pop(7)))

    def test_wrong_backspace_data_is_rejected(self):
        def change(base):
            def mutation(d):
                d["automatedObservations"][7]["observedDescription"] = d["automatedObservations"][7]["observedDescription"].replace("OUT_KEYBOARD_DATA: 0001000", "OUT_KEYBOARD_DATA: 0000000")
            self.observations(base, mutation)
        self.reject(change)

    def test_second_horizontal_navigation_is_rejected(self):
        self.reject(lambda b: self.observations(b, lambda d: d["automatedObservations"].insert(10, dict(d["automatedObservations"][9]))))

    def test_tap_before_reveal_is_rejected(self):
        def change(base):
            def mutation(d):
                records = d["automatedObservations"]
                records[9], records[10] = records[10], records[9]
            self.observations(base, mutation)
        self.reject(change)

    def test_touch_point_outside_row_is_rejected(self):
        self.reject(lambda b: self.observations(b, lambda d: d["automatedObservations"][6].update(point=[656, 1300])))

    def test_missing_new_tree_is_rejected(self):
        self.reject(lambda b: self.observations(b, lambda d: d["automatedObservations"][10].update(tree=d["automatedObservations"][9]["tree"])))

    def test_disabled_owner_in_rehashed_tree_is_rejected(self):
        def change(base):
            path = base / "raw/io-keyboard-io_ascii_backspace-tap.xml"
            tree = ET.parse(path)
            owner = next(n for n in tree.getroot().iter("node") if n.get("bounds") == "[455,1122][858,1248]" and n.get("clickable") == "true")
            owner.set("enabled", "false")
            tree.write(path, encoding="utf-8", xml_declaration=True)
        self.reject(change)

    def test_extra_component_attribute_is_rejected(self):
        def change(base):
            for name in ("io-keyboard-capacity.circ", "last-document.circ"):
                path = base / "raw" / name
                tree = ET.parse(path)
                ET.SubElement(tree.find(".//comp"), "a", {"name": "unexpected", "val": "true"})
                tree.write(path, encoding="utf-8", xml_declaration=True)
        self.reject(change)

    def test_missing_wire_is_rejected(self):
        def change(base):
            for name in ("io-keyboard-capacity.circ", "last-document.circ"):
                path = base / "raw" / name
                tree = ET.parse(path)
                circuit = tree.find("circuit")
                circuit.remove(circuit.find("wire"))
                tree.write(path, encoding="utf-8", xml_declaration=True)
        self.reject(change)

    def test_sdk_nonce_mismatch_is_rejected(self):
        def change(base):
            path = next((base / "raw").glob("focus-snapshot-*.json"))
            data = read(path)
            data["nonce"] = "0" * 32
            write(path, data)
        self.reject(change)

    def test_sdk_process_change_is_rejected(self):
        def change(base):
            path = next((base / "raw").glob("focus-snapshot-*.json"))
            data = read(path)
            data["appPidAfter"] = "99999"
            write(path, data)
        self.reject(change)

    def test_frozen_source_change_is_rejected(self):
        def change(base):
            path = base / "raw/source-android_ui.py"
            path.write_text(path.read_text() + "\n# changed\n")
        self.reject(change)

    def test_abcd_absent_from_consistent_sdk_payloads_is_rejected(self):
        def change(base):
            for path in (base / "raw").glob("focus-snapshot-*.json"):
                data = read(path)
                xml_path = base / "raw" / data["xml"]
                tree = ET.parse(xml_path)
                changed = False
                for node in tree.getroot().iter("node"):
                    if node.get("class", "").endswith("EditText") and node.get("text") == "ABCD":
                        node.set("text", "ABC")
                        changed = True
                if changed:
                    tree.write(xml_path, encoding="utf-8", xml_declaration=True)
                    old = data["bundle"]["snapshotXmlBase64"]
                    new = base64.b64encode(xml_path.read_bytes()).decode()
                    data["bundle"]["snapshotXmlBase64"] = new
                    stdout = base / "raw" / data["stdout"]
                    stdout.write_text(stdout.read_text().replace("snapshotXmlBase64=" + old, "snapshotXmlBase64=" + new))
                    write(path, data)
        self.reject(change)

    def swap_roles(self, base, value):
        for path in (base / "raw").glob("focus-snapshot-*.json"):
            data = read(path)
            xml_path = base / "raw" / data["xml"]
            tree = ET.parse(xml_path)
            fields = [n for n in tree.getroot().iter("node") if n.get("class", "").endswith("EditText")]
            keyboard = next((n for n in fields if n.get("text") == value and
                             any(c.get("text", "").startswith("IO_KEYBOARD · ") for c in n.iter("node"))), None)
            label = next((n for n in fields if n.get("text") == "IO_KEYBOARD" and
                          any(c.get("text") == "Label" for c in n.iter("node"))), None)
            if keyboard is not None and label is not None:
                keyboard.set("text", value[:-1])
                label.set("text", value)
                tree.write(xml_path, encoding="utf-8", xml_declaration=True)
                old = data["bundle"]["snapshotXmlBase64"]
                new = base64.b64encode(xml_path.read_bytes()).decode()
                data["bundle"]["snapshotXmlBase64"] = new
                stdout = base / "raw" / data["stdout"]
                stdout.write_text(stdout.read_text().replace("snapshotXmlBase64=" + old, "snapshotXmlBase64=" + new))
                write(path, data)

    def test_ab_in_component_label_is_not_keyboard_input(self):
        self.reject(lambda b: self.swap_roles(b, "AB"))

    def test_abcd_in_component_label_is_not_keyboard_capacity(self):
        self.reject(lambda b: self.swap_roles(b, "ABCD"))

    def test_missing_receipt_is_rejected(self):
        self.reject(lambda b: (b / "receipt.json").unlink())

    def test_full_issue_claim_is_rejected(self):
        self.reject(lambda b: write(b / "receipt.json", {"status": "PASS", "qualification": "full-issue44"}))

    def test_wrong_snapshot_pin_even_with_rehash_is_rejected(self):
        def change(base):
            path = base / "oracle-snapshot/manifest.json"
            data = read(path)
            data["sourcePin"] = "0" * 40
            write(path, data)
        self.reject(change)

    def test_modified_snapshot_even_with_rehash_is_rejected(self):
        def change(base):
            path = base / "oracle-snapshot/scripts/io_showcase_contract.py"
            path.write_text(path.read_text() + "\n# changed\n")
        self.reject(change)

    def test_missing_snapshot_is_rejected(self):
        self.reject(lambda b: (b / "oracle-snapshot/examples/io-showcase.circ").unlink())

    def test_external_snapshot_symlink_is_rejected(self):
        def change(base):
            path = base / "oracle-snapshot/examples/io-showcase.circ"
            external = base.parent / "outside.circ"
            path.rename(external)
            path.symlink_to(external)
        self.reject(change)

    def test_changed_after_apk_is_rejected(self):
        def change(base):
            path = base / "raw/post-run-readback-cleanup.json"
            data = read(path)
            data["installedArtifactsAfter"][0]["sha256"] = "0" * 64
            write(path, data)
        self.reject(change)

    def test_incomplete_tty_description_is_rejected(self):
        def change(base):
            path = base / "raw/io-tty-afterFirst-ui.xml"
            tree = ET.parse(path)
            for node in tree.getroot().iter("node"):
                if node.get("content-desc", "").startswith("Circuit area."):
                    node.set("content-desc", node.get("content-desc").replace("OUT_LED_LEVEL: 0, ", ""))
            tree.write(path, encoding="utf-8", xml_declaration=True)
        self.reject(change)


if __name__ == "__main__":
    unittest.main()
