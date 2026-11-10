"""Check Files presentation ordering without Android processes or devices."""

import ast
from contextlib import ExitStack
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("files_presentation_runner", SCRIPTS / "e2e-android.py")
RUNNER = importlib.util.module_from_spec(spec)
spec.loader.exec_module(RUNNER)
SCENARIO_TREE = ast.parse((SCRIPTS / "e2e-android.py").read_text())
PACKAGE = "thalesmms.logisim.mobile.e2e"
EDITOR = ET.fromstring(f'<hierarchy><node package="{PACKAGE}" bounds="[0,0][1080,2400]">'
                       '<node resource-id="editor-canvas" bounds="[0,100][1080,2300]"/></node></hierarchy>')
PICKER = ET.parse(SCRIPTS.parent / "fixtures/native-documentsui/roots-open-google-api35.xml").getroot()
CHOOSER = ET.parse(SCRIPTS.parent / "docs/validation/artifacts/android-export34-final-002/share-tree.xml").getroot()
XML = '<project>' + ''.join(f'<circuit name="{n}"/>' for n in range(7)) + '</project>'


class Clock:
    def __init__(self):
        self.now = 0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class Ui:
    _rectangle = staticmethod(RUNNER.AndroidUi._rectangle)
    find = staticmethod(RUNNER.AndroidUi.find)
    click = RUNNER.AndroidUi.click

    def __init__(self, output, *roots):
        self.package = PACKAGE
        self.output = output
        self.roots = list(roots)
        self.current = roots[0]
        self.events = []
        self.adb_path = "FORBIDDEN_ADB"
        self.serial = "FAKE_SERIAL"

    def screen(self, *, timeout=45):
        self.current = self.roots.pop(0) if self.roots else self.current
        self.events.append(("screen", next(self.current.iter("node")).get("package")))
        return self.current

    def key(self, key):
        owner = next(self.current.iter("node")).get("package")
        if owner not in {"com.google.android.documentsui", "com.android.documentsui", "com.android.intentresolver"}:
            raise AssertionError("Back was requested before the native surface")
        self.events.append(("key", key, owner))

    def adb(self, *arguments):
        if arguments != ("shell", "run-as", PACKAGE + ".test", "rm", "-f", "files/share-result.json"):
            raise AssertionError("Unexpected fake Android command")
        return ""

    def tap(self, node):
        self.events.append(("tap", node.get("text")))


def nested_case(name, scenarios):
    owner = next(n for n in SCENARIO_TREE.body if isinstance(n, ast.ClassDef) and n.name == "Scenarios")
    files = next(n for n in owner.body if isinstance(n, ast.FunctionDef) and n.name == "files")
    case = next(n for n in files.body if isinstance(n, ast.FunctionDef) and n.name == name)
    namespace = {"self": scenarios, "time": RUNNER.time, "json": json, "hashlib": hashlib, "subprocess": subprocess}
    exec(compile(ast.fix_missing_locations(ast.Module(body=[case], type_ignores=[])), "actual-Files-case", "exec"), namespace)
    return namespace[name]


class NativePresentationTests(unittest.TestCase):
    def setUp(self):
        guards = ExitStack()
        self.addCleanup(guards.close)
        for name in ("Popen", "run", "call", "check_call", "check_output"):
            guards.enter_context(patch.object(subprocess, name, side_effect=AssertionError("No Android processes allowed")))
        self.output = Path(guards.enter_context(tempfile.TemporaryDirectory()))
        self.clock = Clock()
        guards.enter_context(patch.object(RUNNER.time, "monotonic", self.clock.monotonic))
        guards.enter_context(patch.object(RUNNER.time, "sleep", self.clock.sleep))

    def scenarios(self, *roots):
        scenarios = object.__new__(RUNNER.Scenarios)
        scenarios.ui = Ui(self.output, *roots)
        scenarios.export_xml = lambda name: (XML, ET.fromstring(XML))
        scenarios.click_key = lambda *args, **kwargs: scenarios.ui.events.append(("request", args[0]))
        return scenarios

    def check_cancel(self, case, surface, evidence, native, *, share=False):
        scenarios = self.scenarios(EDITOR, native)
        action = nested_case(case, scenarios)
        action(cancel=True) if share else action()
        events = scenarios.ui.events
        self.assertEqual([("screen", PACKAGE), ("screen", next(native.iter("node")).get("package"))],
                         [event for event in events if event[0] == "screen"])
        self.assertEqual([("key", 4, next(native.iter("node")).get("package"))],
                         [event for event in events if event[0] == "key"])
        tree = self.output / (evidence + ".xml")
        metadata = json.loads((self.output / (evidence + ".json")).read_text())
        self.assertEqual(ET.tostring(native), ET.tostring(ET.fromstring(tree.read_bytes())))
        self.assertEqual(hashlib.sha256(tree.read_bytes()).hexdigest(), metadata["treeSha256"])
        self.assertEqual(surface, metadata["surface"])
        self.assertEqual(1, metadata["topLevelWindowCount"])
        self.assertFalse(metadata["activityIdentityObserved"])
        self.assertTrue(metadata["markers"])
        self.assertTrue(all(n["package"] == metadata["package"] for n in metadata["markers"]))

    def test_cancel_open_waits_for_the_picker_and_retains_its_tree(self):
        self.check_cancel("cancellation", "documents", "cancel-open-native-presented", PICKER)

    def test_cancel_export_waits_for_the_picker_and_retains_its_tree(self):
        self.check_cancel("cancel_export", "documents", "cancel-export-native-presented", PICKER)

    def test_cancel_share_waits_for_the_real_resolver_and_retains_its_tree(self):
        self.check_cancel("share", "share", "cancel-share-native-presented", CHOOSER, share=True)

    def test_editor_only_never_receives_back_or_qualifies_cancellation(self):
        scenarios = self.scenarios(EDITOR)
        with self.assertRaisesRegex(AssertionError, "native documents surface was not observed"):
            nested_case("cancellation", scenarios)()
        self.assertEqual([], [event for event in scenarios.ui.events if event[0] == "key"])
        self.assertGreaterEqual(self.clock.now, 30)
        self.assertEqual([], list(self.output.glob("cancel-open-native-presented.*")))

    def test_wrong_package_cannot_supply_a_native_marker(self):
        spoof = ET.fromstring(ET.tostring(PICKER))
        for node in spoof.iter("node"):
            node.set("package", PACKAGE)
        scenarios = self.scenarios(spoof)
        with self.assertRaisesRegex(AssertionError, "not observed"):
            scenarios.wait_native_file_surface("documents", "spoof")
        self.assertEqual([], list(self.output.iterdir()))

    def test_native_owner_without_its_canonical_marker_does_not_satisfy_readiness(self):
        missing = ET.fromstring('<hierarchy><node package="com.android.intentresolver" bounds="[0,0][1080,2400]"/></hierarchy>')
        scenarios = self.scenarios(missing)
        with self.assertRaisesRegex(AssertionError, "not observed"):
            scenarios.wait_native_file_surface("share", "missing")

    def test_multiple_top_level_windows_are_not_attributed_to_the_first(self):
        ambiguous = ET.fromstring(ET.tostring(CHOOSER))
        ET.SubElement(ambiguous, "node", {"package": "com.google.android.inputmethod.latin", "bounds": "[0,1700][1080,2400]"})
        scenarios = self.scenarios(ambiguous)
        with self.assertRaisesRegex(AssertionError, "not observed"):
            scenarios.wait_native_file_surface("share", "ambiguous")

    def test_positive_share_uses_the_existing_poll_when_the_receiver_arrives_later(self):
        scenarios = self.scenarios(EDITOR, CHOOSER)
        received = {"readable": True, "circuits": 7, "bytes": len(XML.encode()),
                    "sha256": hashlib.sha256(XML.encode()).hexdigest(), "uri": f"content://{PACKAGE}.files/circuits/share.circ"}
        with patch.object(subprocess, "run", return_value=SimpleNamespace(stdout=json.dumps(received).encode())):
            nested_case("share", scenarios)()
        self.assertEqual([("tap", "Document test receiver: " + PACKAGE + ".test")],
                         [event for event in scenarios.ui.events if event[0] == "tap"])
        self.assertEqual([], [event for event in scenarios.ui.events if event[0] == "key"])
        self.assertEqual(received, json.loads((self.output / "share-receiver.json").read_text()))

    def test_the_nine_case_names_and_order_are_preserved(self):
        owner = next(n for n in SCENARIO_TREE.body if isinstance(n, ast.ClassDef) and n.name == "Scenarios")
        files = next(n for n in owner.body if isinstance(n, ast.FunctionDef) and n.name == "files")
        names = [node.value.args[0].value for node in files.body if isinstance(node, ast.Expr)
                 and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Attribute)
                 and node.value.func.attr == "run"]
        self.assertEqual(["incoming-share-imports-a-copy", "saf-local-import-preserves-source",
                          "cancel-open-preserves-project", "cancel-export-preserves-project",
                          "cancel-share-preserves-project", "external-receiver-reads-share-grant",
                          "invalid-and-incompatible-files-preserve-project", "save-as-local-document",
                          "recover-create-document-after-process-death"], names)


if __name__ == "__main__":
    unittest.main()
