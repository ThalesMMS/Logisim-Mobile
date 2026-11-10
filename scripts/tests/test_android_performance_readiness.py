"""Offline contract checks using unchanged bytes from the archived failed attempt."""
import hashlib
import json
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from android_performance_readiness import (IMPORTED_CIRCUIT, QualificationError, ReadinessText, evolution_label, qualify_editor,
                                           qualify_import_report, qualify_independent16_model)

ARCHIVE = ROOT / "docs/performance-evidence/android-3a-input-idle16-001/raw-attempt.zip"
ARCHIVE_SHA256 = "d26cba82aed3662406ec760c32f0e146424550a43ceb5428b82ef568e7796c23"
PACKAGE = "thalesmms.logisim.mobile.e2e"


class AndroidPerformanceReadinessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive_bytes = ARCHIVE.read_bytes()
        if hashlib.sha256(cls.archive_bytes).hexdigest() != ARCHIVE_SHA256:
            raise AssertionError("failed-attempt archive bytes changed")
        with zipfile.ZipFile(ARCHIVE) as archive:
            cls.attempt = json.loads(archive.read("attempt.json"))
            cls.report = archive.read("import-report-1-ui.xml")
            cls.editor = archive.read("adb/0077-stdout")
            cls.source_xml = archive.read("source-independent-16.circ")
        cls.catalog = {}
        for path in sorted((ROOT / "localization/messages").glob("*.json")):
            cls.catalog.update(json.loads(path.read_bytes()))
        # The archived attempt predates #321, so it shows the source names.
        cls.text = ReadinessText.from_catalog(cls.catalog, "en", renamed=False)
        cls.imported_text = ReadinessText.from_catalog(cls.catalog, "en")

    @classmethod
    def tearDownClass(cls):
        if ARCHIVE.read_bytes() != cls.archive_bytes:
            raise AssertionError("offline checks modified the failed-attempt archive")

    def test_expected_report_uses_exact_archived_fixture_uuid(self):
        result = qualify_import_report(self.report, fixture_name=self.attempt["fixture_name"], text=self.text, package=PACKAGE)
        self.assertEqual({"screen": "expected_import_report", "fixture_name": "e2e-a7d7411b75f443fd84dfd616363c9fe0.circ",
                          "circuit": "gates-16", "source_version": "3.8.0"}, result)

    def test_archived_editor_and_separate_historical_input_match_offline_prerequisites(self):
        self.assertEqual({"screen": "paused_editor_and_supplied_model", "steps": 0, "circuit": "gates-16",
                          "components": 64, "pins": 48, "and_gates": 16, "wires": 16},
                         qualify_editor(self.editor, self.source_xml, text=self.text, package=PACKAGE, renamed=False))
        self.assertEqual("FAIL", self.attempt["status"])
        self.assertIs(False, self.attempt["sampling_started"])
        self.assertNotIn("canonical_before_sha256", self.attempt)
        self.assertNotIn("model_before", self.attempt)
        self.assertEqual("19198a15e4880baccd3d00391f257c766a8be0859daae1ca721d3d8ed7c12a31",
                         hashlib.sha256(self.source_xml).hexdigest())

    def test_wrong_uuid_and_another_visible_uuid_are_rejected(self):
        with self.assertRaisesRegex(QualificationError, "fixture UUID"):
            qualify_import_report(self.report, fixture_name="e2e-" + "0" * 32 + ".circ", text=self.text, package=PACKAGE)
        other = self.report.replace(b"Rule: version-defaults", b"Rule: e2e-00000000000000000000000000000000.circ", 1)
        with self.assertRaisesRegex(QualificationError, "another fixture UUID"):
            qualify_import_report(other, fixture_name=self.attempt["fixture_name"], text=self.text, package=PACKAGE)

    def test_error_dialog_is_rejected_before_report_or_editor_qualification(self):
        error_report = self.report.replace(b"Import report", b"Could not complete action", 1)
        with self.assertRaisesRegex(QualificationError, "error dialog"):
            qualify_import_report(error_report, fixture_name=self.attempt["fixture_name"], text=self.text, package=PACKAGE)
        error_editor = self.editor.replace(b"Steps 0", b"Got it", 1)
        with self.assertRaisesRegex(QualificationError, "error dialog"):
            qualify_editor(error_editor, self.source_xml, text=self.text, package=PACKAGE, renamed=False)

    def test_missing_hidden_or_duplicate_canvas_is_rejected(self):
        missing = self.editor.replace(b"Circuit area.", b"Other area.", 1)
        with self.assertRaisesRegex(QualificationError, "visible Canvas, found 0"):
            qualify_editor(missing, self.source_xml, text=self.text, package=PACKAGE, renamed=False)
        root = ET.fromstring(self.editor)
        canvas = next(node for node in root.iter("node") if node.get("content-desc", "").startswith("Circuit area."))
        canvas.set("visible-to-user", "false")
        with self.assertRaisesRegex(QualificationError, "visible Canvas, found 0"):
            qualify_editor(ET.tostring(root), self.source_xml, text=self.text, package=PACKAGE, renamed=False)
        canvas.set("visible-to-user", "true")
        root.append(ET.fromstring(ET.tostring(canvas)))
        with self.assertRaisesRegex(QualificationError, "visible Canvas, found 2"):
            qualify_editor(ET.tostring(root), self.source_xml, text=self.text, package=PACKAGE, renamed=False)

    def test_pause_and_nonzero_steps_block_the_editor(self):
        extra_pause = self.editor.replace(b'text="Step"', 'text="Ⅱ Pause"'.encode(), 1)
        with self.assertRaisesRegex(QualificationError, "Pause control is visible"):
            qualify_editor(extra_pause, self.source_xml, text=self.text, package=PACKAGE, renamed=False)
        paused = self.editor.replace("▶ Run".encode(), "Ⅱ Pause".encode())
        with self.assertRaises(QualificationError):
            qualify_editor(paused, self.source_xml, text=self.text, package=PACKAGE, renamed=False)
        with self.assertRaisesRegex(QualificationError, "Steps 0"):
            qualify_editor(self.editor.replace(b"Steps 0", b"Steps 1", 1), self.source_xml, text=self.text, package=PACKAGE, renamed=False)

    def test_wrong_wire_count_endpoint_and_gate_type_are_rejected(self):
        first_wire = b'<wire from="(100,100)" to="(140,100)"/>'
        with self.assertRaisesRegex(QualificationError, "64 components and 16 wires"):
            qualify_independent16_model(self.source_xml.replace(first_wire, b"", 1), renamed=False)
        with self.assertRaisesRegex(QualificationError, "wire endpoints"):
            qualify_independent16_model(self.source_xml.replace(first_wire, b'<wire from="(100,100)" to="(150,100)"/>', 1), renamed=False)
        with self.assertRaisesRegex(QualificationError, "only Wiring Pin and Gates AND"):
            qualify_independent16_model(self.source_xml.replace(b'name="AND Gate"', b'name="OR Gate"', 1), renamed=False)
        gate_prefix = b'<comp loc="(100,100)" name="AND Gate" lib="1">\n      <a name="facing" val="east"/>'
        with self.assertRaisesRegex(QualificationError, "component types"):
            qualify_independent16_model(self.source_xml.replace(gate_prefix, gate_prefix.replace(b'val="east"', b'val="north"'), 1), renamed=False)
        duplicated_wire = b'<wire from="(100,100)" to="(140,100)"/>'
        with self.assertRaisesRegex(QualificationError, "wire endpoints"):
            qualify_independent16_model(self.source_xml.replace(b'<wire from="(300,100)" to="(340,100)"/>', duplicated_wire, 1), renamed=False)

    def test_shape_allows_library_renumbering_coordinate_spaces_and_reversed_wires(self):
        changed = self.source_xml.replace(b'name="0"', b'name="wiring"').replace(b'lib="0"', b'lib="wiring"')
        changed = changed.replace(b'name="1"', b'name="gates"').replace(b'lib="1"', b'lib="gates"')
        changed = changed.replace(b'loc="(50,80)"', b'loc="( 50 , 80 )"', 1)
        changed = changed.replace(b'<wire from="(100,100)" to="(140,100)"/>', b'<wire from="( 140 , 100 )" to="( 100 , 100 )"/>', 1)
        self.assertEqual({"circuit": "gates-16", "components": 64, "pins": 48, "and_gates": 16, "wires": 16},
                         qualify_independent16_model(changed, renamed=False))

    def imported_model(self):
        """The source fixture with the names Mobile writes after opening it (#321)."""
        root = ET.fromstring(self.source_xml)
        for node in root.iter():
            if node.tag in ("main", "circuit") or (node.tag == "a" and node.get("name") == "label"):
                key = "val" if node.tag == "a" else "name"
                node.set(key, evolution_label(node.get(key)))
        return ET.tostring(root)

    def test_suffix_matches_mobile_and_evolution_rules(self):
        # EvolutionNamesTest: Mobile's stable suffix, and generateValidVHDLLabel's shape with Evolution's random one.
        self.assertEqual("Vis_o_a6e3837c", evolution_label("Visão → \u202bاختبار\u202c"))
        self.assertEqual("padded", evolution_label("  padded  "))
        self.assertRegex(evolution_label("9a!b"), r"^L_9aNOT_b_[0-9a-f]{8}$")
        self.assertEqual("gates_16_0da774a5", IMPORTED_CIRCUIT)
        self.assertEqual("a_0_dc896499", evolution_label("a-0"))

    def test_exported_model_uses_the_names_mobile_renames_on_open(self):
        imported = self.imported_model()
        self.assertEqual({"circuit": IMPORTED_CIRCUIT, "components": 64, "pins": 48, "and_gates": 16, "wires": 16},
                         qualify_independent16_model(imported))
        with self.assertRaisesRegex(QualificationError, "only the gates_16_0da774a5 circuit"):
            qualify_independent16_model(self.source_xml)
        with self.assertRaisesRegex(QualificationError, "component types, labels"):
            qualify_independent16_model(imported.replace(evolution_label("a-0").encode(), b"a-0", 1))
        self.assertEqual(IMPORTED_CIRCUIT, qualify_editor(self.editor, imported, text=self.imported_text, package=PACKAGE)["circuit"])

    def test_report_after_rename_needs_the_notice_and_the_new_circuit(self):
        notice = self.imported_text.rename_notice.encode()
        self.assertEqual('"gates-16" was renamed to "gates_16_0da774a5", as Logisim Evolution does when it opens the file.',
                         self.imported_text.rename_notice)
        renamed = self.report.replace(b"Circuit: gates-16", b"Circuit: " + IMPORTED_CIRCUIT.encode(), 1)
        renamed = renamed.replace(b'text="Rule: version-defaults"', b'text="' + notice.replace(b'"', b"&quot;") + b'"', 1)
        self.assertEqual({"screen": "expected_import_report", "fixture_name": self.attempt["fixture_name"],
                          "circuit": IMPORTED_CIRCUIT, "source_version": "3.8.0"},
                         qualify_import_report(renamed, fixture_name=self.attempt["fixture_name"], text=self.imported_text, package=PACKAGE))
        with self.assertRaisesRegex(QualificationError, "circuit rename notice"):
            qualify_import_report(self.report, fixture_name=self.attempt["fixture_name"], text=self.imported_text, package=PACKAGE)
        with self.assertRaisesRegex(QualificationError, "visible circuit summary"):
            qualify_import_report(renamed.replace(b"Circuit: " + IMPORTED_CIRCUIT.encode(), b"Circuit: gates-16"),
                                  fixture_name=self.attempt["fixture_name"], text=self.imported_text, package=PACKAGE)

    def test_localized_report_fields_must_match_one_locale_exactly(self):
        portuguese = ReadinessText.from_catalog(self.catalog, "pt")
        with self.assertRaisesRegex(QualificationError, "localized report title"):
            qualify_import_report(self.report, fixture_name=self.attempt["fixture_name"], text=portuguese, package=PACKAGE)
        for old, new in ((b"Source version: 3.8.0. Circuits: 1.", b"Source version: 3.8.0. Circuits: 2."),
                         (b"Circuit: gates-16", b"Circuit: gates-64")):
            with self.subTest(field=old), self.assertRaises(QualificationError):
                qualify_import_report(self.report.replace(old, new, 1), fixture_name=self.attempt["fixture_name"], text=self.text, package=PACKAGE)


if __name__ == "__main__":
    unittest.main()
