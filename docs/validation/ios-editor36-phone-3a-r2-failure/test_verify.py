"""Behavior checks against the preserved native XML and metadata."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("editor36_phone_verify", ROOT / "verify.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class ArchiveChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.receipt = json.loads((ROOT / "receipt.json").read_bytes())
        cls.index = json.loads((ROOT / "raw-members.json").read_bytes())
        cls.members = verify.read_archive(ROOT / "raw.tar.gz", cls.index, cls.receipt["rawArchive"])

    def reject_rehashed(self, change):
        """Rebuild every inventory layer before testing the semantic boundary."""
        receipt, members = copy.deepcopy(self.receipt), copy.deepcopy(self.members)
        change(receipt, members)
        for record in receipt["exports"]:
            raw = members["attachments/" + record["attachment"]]
            record.update(bytes=len(raw), sha256=verify.sha(raw))
        index = {name: {"bytes": len(data), "sha256": verify.sha(data)}
                 for name, data in members.items()}
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / "raw.tar.gz"
            with tarfile.open(p, "w:gz") as archive:
                for name, data in sorted(members.items()):
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    archive.addfile(info, io.BytesIO(data))
            receipt["rawArchive"].update(bytes=p.stat().st_size, sha256=verify.sha(p.read_bytes()),
                                         memberCount=len(members))
            reread = verify.read_archive(p, index, receipt["rawArchive"])
            with self.assertRaises((AssertionError, KeyError, ValueError)):
                verify.verify_evidence(receipt, reread)

    def xml_mutation(self, fn, *, export=2):
        def change(receipt, members):
            name = "attachments/" + receipt["exports"][export - 1]["attachment"]
            root = ET.fromstring(members[name])
            fn(root)
            members[name] = ET.tostring(root, encoding="utf-8", xml_declaration=True)
        return change

    def test_original_failure_is_verified_without_local_video_or_device(self):
        result = verify.verify_package(ROOT)
        self.assertEqual({"status": "FAIL_PRESERVED", "nativeTests": 1, "failures": 1,
                          "completedSteps": 16, "xmlExports": 6, "nextStep": 17}, result)

    def test_extra_wire_is_rejected_after_all_hashes_are_rebuilt(self):
        self.reject_rehashed(self.xml_mutation(
            lambda root: ET.SubElement(root.find("circuit"), "wire",
                                       {"from": "(100,100)", "to": "(170,100)"})))

    def test_wrong_facing_is_rejected_after_all_hashes_are_rebuilt(self):
        def mutate(root):
            component = root.findall("circuit/comp")[1]
            component.find("a[@name='facing']").set("val", "west")
        self.reject_rehashed(self.xml_mutation(mutate))

    def test_swapped_identity_is_rejected_across_undo_exports(self):
        def mutate(root):
            a, b = root.findall("circuit/comp")[:2]
            aa, bb = a.get("logisim-mobile-id"), b.get("logisim-mobile-id")
            a.set("logisim-mobile-id", bb)
            b.set("logisim-mobile-id", aa)
        self.reject_rehashed(self.xml_mutation(mutate, export=4))

    def test_consistently_swapped_xml_ids_conflict_with_native_tree(self):
        def change(receipt, members):
            for record in receipt["exports"][1:]:
                name = "attachments/" + record["attachment"]
                root = ET.fromstring(members[name])
                for component in root.findall("circuit/comp"):
                    old = component.get("logisim-mobile-id")
                    component.set("logisim-mobile-id", {"c1": "c2", "c2": "c1"}.get(old, old))
                members[name] = ET.tostring(root, encoding="utf-8", xml_declaration=True)
        self.reject_rehashed(change)

    def test_duplicate_component_is_rejected_after_rehash(self):
        self.reject_rehashed(self.xml_mutation(
            lambda root: root.find("circuit").append(copy.deepcopy(root.findall("circuit/comp")[0]))))

    def test_forged_pass_is_rejected_after_rehash(self):
        def change(receipt, members):
            receipt.update(status="PASS", nativeExecutionGate="NATIVE_PASS", exitCode=0, failureCount=0)
            r = json.loads(members["results.json"])
            r.update(status="PASS", qualification_gate="PARTIAL", native_execution_gate="NATIVE_PASS")
            r["execution"].update(status="PASS", exit_code=0)
            members["results.json"] = json.dumps(r).encode()
        self.reject_rehashed(change)

    def test_wrong_device_is_rejected_after_rehash(self):
        def change(receipt, members):
            receipt["device"]["udid"] = "B25F12A5-62D8-4E9E-964E-96519DB6FB69"
            for name in ["results.json", "preflight/preflight.json", "independent-postflight.json"]:
                r = json.loads(members[name])
                r["device"]["udid"] = receipt["device"]["udid"]
                members[name] = json.dumps(r).encode()
        self.reject_rehashed(change)

    def test_changed_raw_byte_fails_unchanged_inventory(self):
        members = dict(self.members)
        members["test.log"] += b"\nchanged\n"
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / "raw.tar.gz"
            with tarfile.open(p, "w:gz") as archive:
                for name, data in sorted(members.items()):
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    archive.addfile(info, io.BytesIO(data))
            record = dict(self.receipt["rawArchive"], bytes=p.stat().st_size,
                          sha256=verify.sha(p.read_bytes()))
            with self.assertRaises(AssertionError):
                verify.read_archive(p, self.index, record)

    def test_lost_step_note_is_rejected_after_rehash(self):
        def change(receipt, members):
            note = receipt["stepNotes"][10]
            members["attachments/" + note["attachment"]] = b"step=11, action=assertDocument, elapsed=0, status=failed"
        self.reject_rehashed(change)

    def test_changed_swift_source_cannot_be_relabelled_e835_after_rehash(self):
        def change(receipt, members):
            name = "harness/EditorEndToEndTests.swift"
            members[name] += b"\n// changed source\n"
            r = json.loads(members["results.json"])
            r["harness_sources"]["EditorEndToEndTests.swift"] = verify.sha(members[name])
            members["results.json"] = json.dumps(r).encode()
        self.reject_rehashed(change)

    def test_compiled_source_inventory_cannot_omit_a_prepared_test_after_rehash(self):
        def change(receipt, members):
            members["harness/project.yml"] = members["harness/project.yml"].replace(
                b"      - FocusEndToEndTests.swift\n", b"")
        self.reject_rehashed(change)

    def test_r1_source_comparison_cannot_forge_the_historical_hashes(self):
        comparison = json.loads((ROOT / "source-comparison.json").read_bytes())
        comparison["differingPreparedSources"][0]["R1"] = "a" * 64
        with self.assertRaises(AssertionError):
            verify.verify_source_comparison(comparison, self.members)

    def test_source_comparison_cannot_claim_an_isolated_ab(self):
        comparison = json.loads((ROOT / "source-comparison.json").read_bytes())
        comparison["scope"] = "Only one file changed; isolated A/B pass."
        with self.assertRaises(AssertionError):
            verify.verify_source_comparison(comparison, self.members)


if __name__ == "__main__":
    unittest.main()
