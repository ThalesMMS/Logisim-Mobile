import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "prepare-source-release.py"


class SourceReleaseTest(unittest.TestCase):
    def make_repository(self, root):
        (root / "scripts").mkdir()
        shutil.copy2(SCRIPT, root / "scripts" / SCRIPT.name)
        (root / "README.md").write_text("Exact source revision\n")
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        subprocess.run(["git", "-C", str(root), "config", "user.name", "Test"], check=True)
        subprocess.run(["git", "-C", str(root), "config", "user.email", "test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(root), "add", "README.md", "scripts"], check=True)
        subprocess.run(["git", "-C", str(root), "commit", "-qm", "Fixture"], check=True)

    def test_archive_contains_the_exact_commit_without_untracked_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_repository(root)
            (root / "private-untracked.txt").write_text("Excluded\n")
            output = root / "release"
            subprocess.run([sys.executable, str(root / "scripts" / SCRIPT.name), "--output", str(output)],
                           check=True, capture_output=True, text=True)
            commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
            manifest = json.loads(next(output.glob("source-manifest-*.json")).read_text())
            self.assertEqual(commit, manifest["sourceCommit"])
            archive = output / manifest["sourceArchive"]
            self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), manifest["sourceArchiveSha256"])
            with tarfile.open(archive) as tar:
                files = [entry.name for entry in tar.getmembers() if entry.isfile()]
                self.assertEqual([f"logisim-mobile-{commit[:12]}/README.md",
                                  f"logisim-mobile-{commit[:12]}/scripts/{SCRIPT.name}"], files)

    def test_tracked_changes_prevent_an_ambiguous_source_archive(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_repository(root)
            (root / "README.md").write_text("Uncommitted change\n")
            result = subprocess.run([sys.executable, str(root / "scripts" / SCRIPT.name), "--output", str(root / "release")],
                                    capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
            self.assertIn("Commit tracked changes", result.stderr)
            self.assertFalse((root / "release").exists())


if __name__ == "__main__":
    unittest.main()
