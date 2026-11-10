import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("parity_matrix", ROOT / "scripts/parity_matrix.py")
pm = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pm)

DIFFERENCES = [
    {"id": "desk", "evidence": "sweep", "fixtures": "foo__bad", "results": ["DESKTOP_ERROR"], "treatment": "excluded", "reason": "r"},
    {"id": "clock", "evidence": "sweep", "fixtures": "foo__clock", "results": ["VALUE"], "treatment": "accepted", "reason": "r"},
    {"id": "known", "evidence": "sweep", "fixtures": "foo__known", "results": ["VALUE"], "treatment": "open", "reason": "r"},
]


def row(fixture, *results, suite="default"):
    return {"evidence": "sweep", "suite": suite, "fixture": fixture, "results": list(results), "mobile_revision": "r1"}


class JudgeKindTest(unittest.TestCase):
    def test_all_same_is_equal(self):
        self.assertEqual("igual", pm.judge_kind([row("foo", "SAME", "SAME")], DIFFERENCES)["status"])

    def test_excluded_rows_do_not_count_as_comparison(self):
        verdict = pm.judge_kind([row("foo", "SAME", "SAME"), row("foo__bad", "DESKTOP_ERROR", "DESKTOP_ERROR")], DIFFERENCES)
        self.assertEqual("igual", verdict["status"])
        self.assertEqual(1, verdict["counts"]["compared"])
        only_excluded = pm.judge_kind([row("foo__bad", "DESKTOP_ERROR", "DESKTOP_ERROR")], DIFFERENCES)
        self.assertEqual("nao_comparado", only_excluded["status"])

    def test_accepted_difference_is_explained(self):
        verdict = pm.judge_kind([row("foo", "SAME", "SAME"), row("foo__clock", "VALUE", "VALUE")], DIFFERENCES)
        self.assertEqual("diferenca", verdict["status"])
        self.assertEqual(["clock"], verdict["differences"])

    def test_open_or_unknown_difference_diverges(self):
        self.assertEqual("divergente", pm.judge_kind([row("foo__known", "VALUE", "VALUE")], DIFFERENCES)["status"])
        verdict = pm.judge_kind([row("foo__other", "SAME", "TIME")], DIFFERENCES)
        self.assertEqual("divergente", verdict["status"])
        self.assertEqual(["foo__other"], verdict["unexplained"])

    def test_difference_only_covers_its_results_and_suites(self):
        scoped = [{**DIFFERENCES[0], "suites": ["negative"]}]
        self.assertEqual("divergente", pm.judge_kind([row("foo__bad", "DESKTOP_ERROR", "DESKTOP_ERROR")], scoped)["status"])
        self.assertEqual("divergente", pm.judge_kind([row("foo__bad", "VALUE", "VALUE")], DIFFERENCES)["status"])


def git(root, *args):
    subprocess.run(["git", *args], cwd=root, check=True, capture_output=True)


class BuildTest(unittest.TestCase):
    """Builds a miniature repository to check presence, evidence and re-verification rules."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        files = {
            "core/Gates.kt": "object GatesLibrary\n",
            "core/Wiring.kt": "object WiringLibrary\n",
            "app/Editor.kt": "class Editor\n",
            "evidence/sweep.tsv": "and\tSAME\tSAME\nand__w_2\tSAME\tSAME\nor\tSAME\tVALUE\n",
            "evidence/run.md": "ran\n",
        }
        for path, text in files.items():
            (self.root / path).parent.mkdir(parents=True, exist_ok=True)
            (self.root / path).write_text(text)
        git(self.root, "init", "-q")
        git(self.root, "-c", "user.email=t@t", "-c", "user.name=t", "add", ".")
        git(self.root, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "base")
        self.base = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.root, text=True).strip()
        receipt = {"evolution": {"revision": "evo", "jar_sha256": "x"}, "suites": [
            {"id": "default", "artifact": "evidence/sweep.tsv", "modes": ["settle", "step"], "command": "run.sh",
             "mobile_revision": self.base, "artifact_sha256": pm.sha256(self.root / "evidence/sweep.tsv")}]}
        reference = {"product": "Logisim Evolution", "revision": "evo",
                     "libraries": [{"key": "Gates/And", "library": "Gates", "component_id": "AND Gate"},
                                   {"key": "Gates/Or", "library": "Gates", "component_id": "OR Gate"},
                                   {"key": "Wiring/Probe", "library": "Wiring", "component_id": "Probe"},
                                   {"key": "Base/PokeTool", "library": "Base", "component_id": "Poke Tool"}],
                     "menus": [{"key": "MenuEdit/undo"}, {"key": "MenuEdit/redo"}, {"key": "MenuFile/quit"}],
                     "preferences": [], "project_options": [], "formats": [], "cli": [],
                     "integrations": [{"key": "integration/fpga-program"}]}
        registry = {"definitions": [
            {"kind": "AND", "library": "#Gates", "fileName": "AND Gate", "metadataOwners": ["x.GatesLibrary", "x.ComponentDefinition"]},
            {"kind": "OR", "library": "#Gates", "fileName": "OR Gate", "metadataOwners": ["x.GatesLibrary"]},
            {"kind": "PROBE", "library": "#Wiring", "fileName": "Probe", "metadataOwners": ["x.WiringLibrary"]}],
            "registrySources": [{"path": "core/Gates.kt"}, {"path": "core/Wiring.kt"}]}
        self.matrix = {
            "reference": "parity/reference.json",
            "areas": [{"id": "editor", "title": "Editor"}, {"id": "other", "title": "Outros"}],
            "area_rules": [["MenuEdit/", "editor"], ["", "other"]],
            "evidence": {"sweep": {"kind": "oracle", "receipt": "evidence/receipt.json"},
                         "ui": {"kind": "scenario", "platform": "android", "title": "UI", "mobile_revision": self.base,
                                "command": "e2e", "record": "evidence/run.md"}},
            "differences": [{"id": "or-step", "evidence": "sweep", "fixtures": "or", "results": ["SAME", "VALUE"],
                             "treatment": "accepted", "reason": "r"}],
            "out_of_scope": [{"keys": ["integration/fpga-program"], "reason": "placas físicas"}],
            "rows": {"Base/PokeTool": {"mobile": ["app/Editor.kt"], "issues": [27]},
                     "MenuEdit/undo": {"mobile": ["app/Editor.kt"], "evidence": ["ui"], "result": "igual",
                                       "watch": ["app/Editor.kt"]},
                     "MenuEdit/redo": {"mobile": ["app/Editor.kt"]}},
            "extra_rows": [],
        }
        (self.root / "parity/registry").mkdir(parents=True)
        (self.root / "evidence/receipt.json").write_text(json.dumps(receipt))
        (self.root / "parity/reference.json").write_text(json.dumps(reference))
        (self.root / "parity/registry/mobile.json").write_text(json.dumps(registry))
        self.write_matrix()

    def tearDown(self):
        self.tmp.cleanup()

    def write_matrix(self):
        (self.root / "parity/matrix.json").write_text(json.dumps(self.matrix))

    def statuses(self, data):
        return {row["key"]: row["status"] for row in data["libraries"] + data["product"]}

    def test_statuses_follow_evidence_not_files(self):
        data = pm.build(self.root)
        self.assertEqual({"Gates/And": "igual", "Gates/Or": "diferenca", "Wiring/Probe": "nao_comparado",
                          "Base/PokeTool": "nao_comparado", "MenuEdit/undo": "igual", "MenuEdit/redo": "nao_comparado",
                          "MenuFile/quit": "ausente", "integration/fpga-program": "fora_de_escopo"},
                         self.statuses(data))
        overall = data["summary"]["overall"]
        self.assertEqual(7, overall["total"] - overall["fora_de_escopo"])
        self.assertEqual(round(100 * 3 / 7, 1), overall["comparado_pct"])
        self.assertEqual(0, overall["reverificar"])

    def test_change_in_own_component_requests_reverification_without_erasing_proof(self):
        (self.root / "core/Gates.kt").write_text("object GatesLibrary { val changed = 1 }\n")
        (self.root / "core/Other.kt").write_text("shared\n")
        git(self.root, "-c", "user.email=t@t", "-c", "user.name=t", "add", ".")
        git(self.root, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "change gates")
        data = pm.build(self.root)
        rows = {row["key"]: row for row in data["libraries"] + data["product"]}
        self.assertEqual("igual", rows["Gates/And"]["status"])
        self.assertTrue(rows["Gates/And"]["reverify"][0].endswith("change gates"))
        self.assertNotIn("reverify", rows["MenuEdit/undo"])
        first = pm.render(data)
        (self.root / "core/Gates.kt").write_text("object GatesLibrary { val changed = 2 }\n")
        git(self.root, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qam", "again")
        self.assertEqual(first, pm.render(pm.build(self.root)), "later commits keep the matrix unchanged")

    def test_declared_evidence_requires_result_and_watch(self):
        self.matrix["rows"]["MenuEdit/redo"] = {"mobile": ["app/Editor.kt"], "evidence": ["ui"], "result": "igual"}
        self.write_matrix()
        with self.assertRaisesRegex(pm.MatrixError, "watch"):
            pm.build(self.root)
        self.matrix["rows"]["MenuEdit/redo"] = {"mobile": ["app/Editor.kt"], "evidence": ["ui"], "result": "diferenca",
                                                "watch": ["app/Editor.kt"]}
        self.write_matrix()
        with self.assertRaisesRegex(pm.MatrixError, "document the difference"):
            pm.build(self.root)

    def test_sweep_evidence_cannot_be_overridden_and_artifacts_are_hash_checked(self):
        self.matrix["rows"]["Gates/And"] = {"evidence": ["ui"], "result": "igual", "watch": ["core/Gates.kt"]}
        self.write_matrix()
        with self.assertRaisesRegex(pm.MatrixError, "override"):
            pm.build(self.root)
        del self.matrix["rows"]["Gates/And"]
        self.write_matrix()
        (self.root / "evidence/sweep.tsv").write_text("and\tSAME\tSAME\n")
        with self.assertRaisesRegex(pm.MatrixError, "SHA-256"):
            pm.build(self.root)

    def test_unknown_keys_and_fixtures_are_rejected(self):
        self.matrix["rows"]["MenuEdit/nothing"] = {"mobile": ["app/Editor.kt"]}
        self.write_matrix()
        with self.assertRaisesRegex(pm.MatrixError, "unknown keys"):
            pm.build(self.root)


class RepositoryMatrixTest(unittest.TestCase):
    def test_generated_files_are_current(self):
        markdown, data = pm.render(pm.build(ROOT))
        self.assertEqual(markdown, (ROOT / pm.OUTPUT_MD).read_text(encoding="utf-8"),
                         "Run python3 scripts/parity_matrix.py")
        self.assertEqual(data, (ROOT / pm.OUTPUT_JSON).read_text(encoding="utf-8"))

    def test_reference_inventory_has_the_205_library_entries(self):
        reference = json.loads((ROOT / "parity/evolution-d58a61e0.json").read_text())
        self.assertEqual(205, len(reference["libraries"]))
        self.assertEqual("d58a61e0c290361722b43df5b1a6b86c1f7c7f0d", reference["revision"])


if __name__ == "__main__":
    unittest.main()
