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
spec = importlib.util.spec_from_file_location("editor36_r4_phone_verify", ROOT / "verify.py")
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

    def test_selected_case_pass_preserves_wrapper_partial_without_local_files(self):
        result = verify.verify_package(ROOT)
        self.assertEqual({"status": "SELECTED_CASE_PASS", "wrapperQualification": "PARTIAL",
                          "nativeTests": 1, "failures": 0, "completedSteps": 62, "xmlExports": 20,
                          "zoomPercent": {"53": 172, "55": 215, "57": 172, "60": 400},
                          "outputs": ["1", "0", "1"], "headerObservations": 53, "headerSkips": 15,
                          "headerTapRequests": 0, "headerScrollRequests": 4}, result)

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

    def test_consistently_swapped_xml_ids_conflict_with_observed_identity(self):
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

    def test_wrapper_partial_cannot_be_relabelled_all_suite_pass_after_rehash(self):
        def change(receipt, members):
            receipt["wrapperQualification"] = "PASS"
            result = json.loads(members["results.json"])
            result["qualification_gate"] = "PASS"
            members["results.json"] = json.dumps(result).encode()
        self.reject_rehashed(change)

    def test_wrong_device_is_rejected_after_rehash(self):
        def change(receipt, members):
            receipt["device"]["udid"] = "B25F12A5-62D8-4E9E-964E-96519DB6FB69"
            for name in ["results.json", "preflight/preflight.json", "postflight/preflight.json"]:
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

    def test_changed_swift_source_cannot_be_relabelled_f25_after_rehash(self):
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


    def header_mutation(self, predicate, key, value):
        def change(receipt, members):
            draft = json.loads(members["draft-inventory.json"])
            rows = [row for group in draft["headerByInvocation"].values() for row in group]
            row = next(row for row in rows if predicate(row["fields"]))
            row["fields"][key] = value
            row["payload"] = "; ".join(f"{key}={value}" for key, value in row["fields"].items())
            members["attachments/" + row["attachment"]] = row["payload"].encode()
            members["draft-inventory.json"] = json.dumps(draft).encode()
            for i, first in enumerate(receipt["firstClippedSearchRecovery"]):
                if first["attachment"] == row["attachment"]:
                    receipt["firstClippedSearchRecovery"][i] = copy.deepcopy(row)
        return change

    def test_public_selected_true_cannot_be_rewritten_false_after_rehash(self):
        self.reject_rehashed(self.header_mutation(lambda f: f["target"] == "components" and f.get("selected") == "true", "selected", "false"))

    def test_existing_last_search_cannot_be_rewritten_as_absent_after_rehash(self):
        self.reject_rehashed(self.header_mutation(lambda f: f.get("label") == "", "count", "0"))

    def test_unobserved_selection_is_not_false_after_rehash(self):
        self.reject_rehashed(self.header_mutation(lambda f: f["target"] == "search", "selected", "false"))

    def test_unqueried_hittable_is_not_false_after_rehash(self):
        self.reject_rehashed(self.header_mutation(lambda f: f.get("hittable") == "unqueried", "hittable", "false"))

    def test_header_tap_request_cannot_be_forged_after_rehash(self):
        self.reject_rehashed(self.header_mutation(lambda f: "tapRequests" in f, "tapRequests", "1"))

    def test_scroll_aggregate_cannot_erase_observed_requests_after_rehash(self):
        def change(receipt, members):
            receipt["headerEvidence"]["headerDecisionCounts"]["short-backward-scroll"] = 0
        self.reject_rehashed(change)

    def test_header_transcription_must_match_original_attachment_after_rehash(self):
        def change(receipt, members):
            draft = json.loads(members["draft-inventory.json"])
            next(iter(draft["headerByInvocation"].values()))[0]["fields"]["count"] = "9"
            members["draft-inventory.json"] = json.dumps(draft).encode()
        self.reject_rehashed(change)

    def test_zoom_relation_is_checked_after_rehash_and_metadata_update(self):
        def change(receipt, members):
            members["test.log"] = members["test.log"].replace(b'Fit circuit, 215%', b'Fit circuit, 172%')
            receipt["runtimeEvidence"]["zoomPercent"]["55"] = 172
        self.reject_rehashed(change)

    def test_output_value_is_checked_in_its_own_step_after_rehash(self):
        def change(receipt, members):
            log = members["test.log"].decode()
            start, end = log.index('contract step 21:'), log.index('contract step 22:')
            log = log[:start] + log[start:end].replace('E2E_OUT: 0.', 'E2E_OUT: 1.') + log[end:]
            members["test.log"] = log.encode()
            receipt["runtimeEvidence"]["outputs"]["21"] = "1"
        self.reject_rehashed(change)

    def test_distinct_native_caption_must_match_xml_copy_identity_after_rehash(self):
        def change(receipt, members):
            caption = receipt["runtimeEvidence"]["distinctCaptions"]["E2E_IN"]
            caption["payload"] = caption["payload"].replace('c4', 'c1')
            members["attachments/" + caption["attachment"]] = caption["payload"].encode()
        self.reject_rehashed(change)

    def test_duplicate_wire_in_wired_checkpoint_is_rejected_after_rehash(self):
        self.reject_rehashed(self.xml_mutation(
            lambda root: root.find("circuit").append(copy.deepcopy(root.find("circuit/wire"))), export=7))


if __name__ == "__main__":
    unittest.main()
