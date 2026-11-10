import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "oracle-replay-receipt.py"
SPEC = importlib.util.spec_from_file_location("oracle_replay_receipt", SCRIPT)
replay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(replay)


RUNNER = '''
import argparse
import json
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument("--desktop-jar", required=True)
parser.add_argument("--skip-build", action="store_true", required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
assert (root / "core/build/libs/core-jvm.jar").read_bytes() == b"core fixture"
assert Path(args.desktop_jar).read_bytes() == b"desktop fixture"
args.output.mkdir(exist_ok=True)
if Path(__file__).name == "verify-corpus.py":
    (args.output / "summary.json").write_text(json.dumps({
        "examples": [{"id": "fixture", "records": 3, "differences": []}],
        "records": 3, "differences": 0}))
print("synthetic oracle passed")
'''


class OracleReplayReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.root = self.directory / "repository"
        self.root.mkdir()
        self.git("init", "-q")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "user.name", "Receipt fixture")
        self.core = self.directory / "core.jar"
        self.desktop = self.directory / "desktop.jar"
        self.stdlib = self.directory / "stdlib.jar"
        self.core.write_bytes(b"core fixture")
        self.desktop.write_bytes(b"desktop fixture")
        self.stdlib.write_bytes(b"stdlib fixture")
        self.write("core/src/Engine.kt", "class Engine\n")
        self.write("build.gradle.kts", 'kotlin("multiplatform") version "2.4.21"\n')
        self.write("examples/catalog.json", json.dumps({"desktopRevision": "b" * 40, "examples": []}))
        self.write("examples/corpus/reference/provenance.json", json.dumps({
            "desktopRevision": "b" * 40, "desktopJarSha256": replay.sha(self.desktop)}))
        self.write("examples/corpus/temporal/provenance.json", json.dumps({
            "desktop_revision": "b" * 40, "desktop_jar_sha256": replay.sha(self.desktop)}))
        self.write("scripts/verify-corpus.py", textwrap.dedent(RUNNER))
        self.write("scripts/verify-temporal-oracle.py", textwrap.dedent(RUNNER))
        self.commit()

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True).strip()

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "Fixture")
        self.revision = self.git("rev-parse", "HEAD")

    def arguments(self, **overrides):
        values = dict(source_root=self.root, mobile_revision=self.revision, desktop_revision="b" * 40,
                      core_jar=self.core, desktop_jar=self.desktop, core_sha256=replay.sha(self.core),
                      desktop_sha256=replay.sha(self.desktop), kotlin_stdlib_jar=self.stdlib,
                      output=self.directory / "receipt")
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_runs_both_oracles_with_copied_jars_and_skip_build_and_preserves_inputs(self):
        receipt = replay.replay(self.arguments())
        self.assertEqual("passed", receipt["status"])
        self.assertEqual(["corpus", "temporal"], [item["name"] for item in receipt["commands"]])
        self.assertEqual([0, 0], [item["exitCode"] for item in receipt["commands"]])
        self.assertEqual(3, receipt["commands"][0]["summary"]["data"]["records"])
        for command in receipt["commands"]:
            self.assertIn("--skip-build", command["argv"])
            self.assertNotIn("gradlew", " ".join(command["argv"]))
            self.assertIn("synthetic oracle passed", Path(command["stdout"]).read_text())
        for key in ("core", "desktop", "kotlinStdlib"):
            jar = receipt["jars"][key]
            self.assertNotEqual(jar["original"], jar["copy"])
            self.assertEqual(jar["expectedSha256"], jar["sha256After"])
            self.assertEqual(jar["expectedSha256"], jar["copySha256After"])
        self.assertFalse((self.root / "core/build").exists())
        self.assertEqual(self.git("rev-parse", "HEAD^{tree}"), receipt["sourceBefore"]["tree"])
        self.assertEqual("", self.git("status", "--porcelain"))
        self.assertEqual(receipt, json.loads((self.directory / "receipt/receipt.json").read_text()))

    def test_bad_jar_hash_prevents_oracle_execution_and_writes_failure_receipt(self):
        receipt = replay.replay(self.arguments(core_sha256="0" * 64))
        self.assertEqual("preflight_failed", receipt["status"])
        self.assertEqual([], receipt["commands"])
        self.assertIn("Unexpected JAR hash", receipt["error"])
        self.assertFalse((self.directory / "receipt/corpus").exists())

    def test_requires_complete_revisions(self):
        receipt = replay.replay(self.arguments(mobile_revision=self.revision[:12]))
        self.assertEqual("preflight_failed", receipt["status"])
        self.assertIn("full lowercase 40-character", receipt["error"])
        self.assertEqual([], receipt["commands"])

    def test_rejects_source_changes_against_declared_revision(self):
        self.write("core/src/Engine.kt", "class ChangedEngine\n")
        receipt = replay.replay(self.arguments())
        self.assertEqual("preflight_failed", receipt["status"])
        self.assertIn("core/src/Engine.kt", receipt["error"])
        self.assertEqual([], receipt["commands"])

    def test_allows_unrelated_changes_and_newer_head_with_identical_oracle_sources(self):
        revision = self.revision
        self.write("docs/review.txt", "Additional UI evidence\n")
        self.commit()
        self.write("docs/review.txt", "Uncommitted UI evidence\n")
        receipt = replay.replay(self.arguments(mobile_revision=revision))
        self.assertEqual("passed", receipt["status"])
        self.assertEqual(self.revision, receipt["sourceBefore"]["head"])
        self.assertEqual(revision, receipt["sourceBefore"]["revision"])
        self.assertEqual(["docs/review.txt"], receipt["sourceBefore"]["otherTrackedChanges"])

    def test_rejects_recorded_desktop_jar_and_revision_mismatch_before_execution(self):
        self.write("examples/corpus/reference/provenance.json", json.dumps({
            "desktopRevision": "b" * 40, "desktopJarSha256": "0" * 64}))
        self.commit()
        receipt = replay.replay(self.arguments())
        self.assertEqual("preflight_failed", receipt["status"])
        self.assertIn("Desktop JAR provenance mismatch", receipt["error"])
        self.assertEqual([], receipt["commands"])
        second = replay.replay(self.arguments(desktop_revision="c" * 40, output=self.directory / "other"))
        self.assertIn("Desktop revision mismatch", second["error"])

    def test_runner_failure_is_recorded_and_other_oracle_still_runs(self):
        self.write("scripts/verify-corpus.py", "raise SystemExit(7)\n")
        self.commit()
        receipt = replay.replay(self.arguments())
        self.assertEqual("failed", receipt["status"])
        self.assertEqual([7, 0], [item["exitCode"] for item in receipt["commands"]])
        self.assertEqual(receipt["jars"]["core"]["sha256Before"], receipt["jars"]["core"]["sha256After"])

    def test_invalid_summary_preserves_executed_command_and_fails_receipt(self):
        self.write("scripts/verify-corpus.py", textwrap.dedent(RUNNER) +
                   '\n(args.output / "summary.json").write_text("invalid json")\n')
        self.commit()
        receipt = replay.replay(self.arguments())
        self.assertEqual("failed", receipt["status"])
        self.assertEqual([0, 0], [item["exitCode"] for item in receipt["commands"]])
        self.assertIn("summaryParseError", receipt["commands"][0])
        self.assertTrue(Path(receipt["commands"][0]["stdout"]).is_file())
        self.assertEqual("temporal", receipt["commands"][1]["name"])

    def test_rejects_mutation_of_archived_fixture_or_reference_by_runner(self):
        self.write("scripts/verify-corpus.py", textwrap.dedent(RUNNER) +
                   '\n(root / "examples/catalog.json").write_text("{}")\n')
        self.commit()
        receipt = replay.replay(self.arguments())
        self.assertEqual("failed", receipt["status"])
        self.assertIn("changed during replay", receipt["error"])

    def test_rejects_original_or_copied_jar_mutation_and_records_final_hashes(self):
        self.write("scripts/verify-temporal-oracle.py", textwrap.dedent(RUNNER) +
                   f'\nPath({str(self.desktop)!r}).write_bytes(b"changed desktop")\n' +
                   '\n(root / "core/build/libs/core-jvm.jar").write_bytes(b"changed core")\n')
        self.commit()
        receipt = replay.replay(self.arguments())
        self.assertEqual("failed", receipt["status"])
        self.assertEqual([0, 0], [item["exitCode"] for item in receipt["commands"]])
        self.assertEqual(2, len(receipt["finalizationErrors"]))
        self.assertNotEqual(receipt["jars"]["core"]["expectedSha256"], receipt["jars"]["core"]["copySha256After"])
        self.assertNotEqual(receipt["jars"]["desktop"]["expectedSha256"], receipt["jars"]["desktop"]["sha256After"])

    def test_output_directory_cannot_overwrite_an_existing_receipt(self):
        arguments = self.arguments()
        arguments.output.mkdir()
        sentinel = arguments.output / "receipt.json"
        sentinel.write_text("preserved")
        with self.assertRaisesRegex(ValueError, "must not exist"):
            replay.replay(arguments)
        self.assertEqual("preserved", sentinel.read_text())


if __name__ == "__main__":
    unittest.main()
