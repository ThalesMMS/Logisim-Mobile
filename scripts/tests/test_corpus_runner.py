import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("corpus", Path(__file__).resolve().parents[1] / "verify-corpus.py")
corpus = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(corpus)


class CorpusRunnerTest(unittest.TestCase):
    def test_missing_extra_and_changed_records_are_all_reported(self):
        expected = {"V\ta\tQ": "1", "G\tmain\tPin\t0,0": "0,0:1:2"}
        actual = {"V\ta\tQ": "0", "V\tb\tQ": "1"}
        self.assertEqual([
            {"key": "G\tmain\tPin\t0,0", "desktop": "0,0:1:2", "mobile": None},
            {"key": "V\ta\tQ", "desktop": "1", "mobile": "0"},
            {"key": "V\tb\tQ", "desktop": None, "mobile": "1"},
        ], corpus.differences(expected, actual))

    def test_duplicate_or_empty_records_cannot_pass(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            corpus.records("V\ta\tQ\t1\nV\ta\tQ\t0\n")
        with self.assertRaisesRegex(ValueError, "no corpus records"):
            corpus.records("M\trevision\tabc\n")

    def test_startup_flushes_before_observed_actions(self):
        example = {"probes": [{"id": "Q", "x": 10, "y": 20, "width": 1}],
                   "startup": [{"name": "reset", "inputs": {"CLR": "1"}}],
                   "actions": [{"name": "edge", "inputs": {"D": "1", "CLK": "1"}, "ticks": 2}]}
        self.assertEqual("P\tQ\t10\t20\t1\nA\treset\t0\nI\tCLR\t1\nF\t0\nA\tedge\t2\nI\tD\t1\nI\tCLK\t1\nF\t1\n",
                         corpus.operations(example))

    def test_original_reopen_failures_keep_summary_and_fail_verification(self):
        original_records = "M\trevision\t2.7.1\nV\tread\tQ\t1\n"
        changed_records = "M\trevision\t2.7.1\nV\tread\tQ\t0\n"
        mismatch = {"differences": [{"key": "V\tread\tQ", "desktop": "1", "mobile": "0"}]}
        cases = (
            (changed_records, mismatch),
            (RuntimeError("Original oracle could not open export"), {"error": "Original oracle could not open export"}),
            (subprocess.TimeoutExpired("original-oracle", 30), {"error": "Command 'original-oracle' timed out after 30 seconds"}),
            ("", {"error": "Runner produced no corpus records"}),
            ("V\tread\tQ\t1\n", {"error": "Original reopen reported revision None, expected '2.7.1'"}),
        )

        for outcome, expected in cases:
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                output = root / "output"
                output.mkdir()
                fixtures = root / "fixtures"
                fixtures.mkdir()
                (fixtures / "example.circ").touch()
                (fixtures / "example.ops.tsv").touch()
                (fixtures / "example.original.tsv").write_text(original_records)
                jar = root / "original.jar"
                jar.write_bytes(b"stub oracle")
                empty_hash = hashlib.sha256(b"").hexdigest()
                (fixtures / "provenance.json").write_text(json.dumps({
                    "release": "2.7.1",
                    "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                    "fixtures": {"example.circ": {
                        "circuit_sha256": empty_hash,
                        "operations_sha256": empty_hash,
                        "records": 1,
                    }},
                }))

                def run_oracle(command, **_kwargs):
                    if "dev.logisim.mobile.core.CircuitCorpusCli" in command:
                        return original_records + "D\tversion-defaults\n"
                    if command[0] == "javac":
                        return ""
                    if Path(command[-2]) != output / "example" / "mobile.circ":
                        return original_records
                    if isinstance(outcome, Exception):
                        raise outcome
                    return outcome

                args = SimpleNamespace(output=output, update_reference=False)
                stdout = io.StringIO()
                with (patch.object(corpus, "ORIGINAL_FIXTURES", fixtures),
                      patch.object(corpus, "run", side_effect=run_oracle),
                      redirect_stdout(stdout),
                      self.assertRaisesRegex(SystemExit, "reopen mobile exports")):
                    corpus.verify_original(args, jar, root / "mobile.jar", root / "stdlib.jar")

                summary, = json.loads((output / "summary.json").read_text())
                self.assertEqual({"mobile": [], "mobile-reopened": []}, summary["mobile"])
                self.assertEqual(expected, summary["original_export_compatibility"])
                self.assertNotIn("PASS", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
