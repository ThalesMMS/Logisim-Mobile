#!/usr/bin/env python3
"""Exercise driver interruption with isolated Git, locks, and disposable Python process groups."""

import fcntl
import json
import os
from pathlib import Path
import plistlib
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest


DRIVER = Path(__file__).with_name("build-simulator-fixture.py")
GROUP = "group.thalesmms.logisim.validation.revocationfixture"
VERIFY = r'''
import json, os, pathlib, signal, subprocess, sys, time
root = pathlib.Path(os.environ["FIXTURE_TEST_STATE"])
child_code = r"""
import json, os, pathlib, signal, sys, time
ready, acknowledgment = map(pathlib.Path, sys.argv[1:])
signal.signal(signal.SIGTERM, signal.SIG_IGN)
signal.signal(signal.SIGINT, signal.SIG_IGN)
signal.signal(signal.SIGUSR1, lambda *_: acknowledgment.write_text(str(os.getpid())))
ready.write_text(json.dumps({"pid": os.getpid(), "group": os.getpgrp()}))
while True: time.sleep(0.02)
"""
children = []
for index in range(2):
    ready = root / ("child-" + str(index) + ".json")
    acknowledgment = root / ("ack-" + str(index))
    child = subprocess.Popen([sys.executable, "-c", child_code, str(ready), str(acknowledgment)],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    children.append((child, ready, acknowledgment))
while not all(ready.exists() for _, ready, _ in children): time.sleep(0.02)
(root / "verify-ready.json").write_text(json.dumps({
    "leader": os.getpid(), "group": os.getpgrp(),
    "children": [dict(json.loads(ready.read_text()), acknowledgment=str(ack)) for _, ready, ack in children],
}))
while True: time.sleep(0.02)
'''
COMPETITOR = r'''
import fcntl, json, os, pathlib, signal, sys, time
lock, ready, result, fixture = map(pathlib.Path, sys.argv[1:])
ready.write_text("waiting")
with lock.open("a") as stream:
    fcntl.flock(stream, fcntl.LOCK_EX)
    children = json.loads(fixture.read_text())["children"]
    for child in children:
        try: os.kill(child["pid"], signal.SIGUSR1)
        except ProcessLookupError: pass
    deadline = time.monotonic() + 0.5
    while time.monotonic() < deadline:
        if all(pathlib.Path(child["acknowledgment"]).exists() for child in children): break
        time.sleep(0.01)
    result.write_text(json.dumps({"respondedAfterLockAcquired": [
        child["pid"] for child in children
        if pathlib.Path(child["acknowledgment"]).exists()
    ]}))
'''


class BuildSimulatorFixtureInterruptionTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.processes = []
        self.fixture_group = None
        self.addCleanup(self.cleanup_processes)
        self.source = self.root / "source"
        fixture = self.source / "scripts/ios_fileprovider_revocation"
        (fixture / "Config").mkdir(parents=True)
        for filename in ["build-simulator-fixture.py", "simulator_entitlements.py"]:
            (fixture / filename).write_bytes(DRIVER.with_name(filename).read_bytes())
        (self.source / "scripts/run-ios-accessibility.py").write_text("def artifact(app, identifier): return {}\n")
        for name in ("App", "Provider"):
            with (fixture / "Config" / (name + ".entitlements")).open("wb") as stream:
                plistlib.dump({"com.apple.security.application-groups": [GROUP]}, stream)
        (fixture / "verify-fixture.py").write_text(VERIFY)
        git = shutil.which("git")
        self.assertIsNotNone(git)
        git_env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null")

        def git_command(*arguments):
            return subprocess.check_output([git, "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false",
                                            *arguments], cwd=self.source, env=git_env, text=True,
                                           stderr=subprocess.STDOUT).strip()

        git_command("-c", "init.templateDir=", "init", "--quiet")
        git_command("add", "scripts")
        git_command("-c", "user.name=Fixture Test", "-c", "user.email=fixture@example.invalid",
                    "commit", "--quiet", "-m", "Add isolated fake verification fixture")
        self.pin = git_command("rev-parse", "HEAD")
        self.state = self.root / "process-state"
        self.state.mkdir()
        self.output = self.root / "evidence"
        self.lock = self.root / "isolated-build.lock"
        guard = self.root / "guard-bin"
        guard.mkdir()
        (guard / "python3").symlink_to(sys.executable)
        (guard / "git").symlink_to(git)
        self.unexpected = self.root / "unexpected-native-command"
        for tool in ("xcodegen", "xcodebuild", "codesign", "xcrun", "lipo"):
            trap = guard / tool
            trap.write_text("#!" + sys.executable + "\nimport os, pathlib, sys\n"
                            "pathlib.Path(os.environ['FIXTURE_TEST_UNEXPECTED']).write_text(sys.argv[0])\n"
                            "raise SystemExit(97)\n")
            trap.chmod(0o755)
        self.env = dict(git_env, PATH=str(guard), FIXTURE_TEST_STATE=str(self.state),
                        FIXTURE_TEST_UNEXPECTED=str(self.unexpected))

    def start(self, command, **options):
        process = subprocess.Popen(command, start_new_session=True, **options)
        self.processes.append(process)
        return process

    def start_driver(self):
        self.assertTrue(DRIVER.is_file(), "The versioned driver must exist before these tests run")
        return self.start([sys.executable, str(DRIVER), "--source-root", str(self.source),
                           "--expected-source", self.pin, "--output", str(self.output),
                           "--lock-file", str(self.lock)], env=self.env, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, text=True)

    def wait_for(self, condition):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                value = condition()
                if value:
                    return value
            except (FileNotFoundError, json.JSONDecodeError):
                pass
            time.sleep(0.02)
        self.fail("The isolated fake driver did not reach the expected synchronization point")

    def manifest(self):
        return json.loads((self.output / "artifact.json").read_text())

    def assert_interrupted(self, process):
        stdout, stderr = process.communicate(timeout=20)
        self.assertEqual(130, process.returncode, stderr)
        self.assertEqual(str(self.output / "artifact.json"), stdout.strip())
        report = self.manifest()
        self.assertEqual("INTERRUPTED", report["status"])
        self.assertEqual("BuildInterrupted", report["error"]["type"])
        self.assertEqual("NOT_RUN", report["nativeExecution"])
        self.assertEqual("NOT_RUN", report["domainRegistration"])
        self.assertFalse(self.unexpected.exists(), "No Xcode, Simulator, signing, or SDK tool may run")
        return report

    def test_sigint_while_waiting_for_an_isolated_lock_records_no_commands(self):
        with self.lock.open("a") as held:
            fcntl.flock(held, fcntl.LOCK_EX)
            process = self.start_driver()
            self.wait_for(lambda: self.manifest().get("lockRequested"))
            os.kill(process.pid, signal.SIGINT)
            report = self.assert_interrupted(process)
            self.assertEqual([], report["commands"])
            self.assertNotIn("lockAcquired", report)
            self.assertFalse((self.state / "verify-ready.json").exists())

    def test_sigterm_stops_its_entire_command_group_before_releasing_the_lock(self):
        control = self.start([sys.executable, "-c", "import time; time.sleep(60)"],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        driver = self.start_driver()
        ready = self.state / "verify-ready.json"
        fixture = self.wait_for(lambda: json.loads(ready.read_text()))
        self.fixture_group = fixture["group"]
        self.assertEqual(fixture["leader"], self.fixture_group)
        self.assertNotEqual(os.getpgrp(), self.fixture_group)
        self.assertNotEqual(driver.pid, self.fixture_group)
        self.assertEqual([self.fixture_group, self.fixture_group], [c["group"] for c in fixture["children"]])
        competitor_ready = self.root / "competitor-ready"
        acquired = self.root / "competitor-result.json"
        competitor = self.start([sys.executable, "-c", COMPETITOR, str(self.lock), str(competitor_ready),
                                 str(acquired), str(ready)], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        self.wait_for(competitor_ready.exists)
        self.assertFalse(acquired.exists(), "The driver must still own the isolated lock")
        os.kill(driver.pid, signal.SIGTERM)
        report = self.assert_interrupted(driver)
        competitor.communicate(timeout=10)
        self.assertEqual(0, competitor.returncode)
        self.assertIsNone(control.poll(), "Interruption must preserve the unrelated control process")
        self.assertEqual(1, len(report["commands"]))
        self.assertEqual(["python3", "verify-fixture.py"], report["commands"][0]["arguments"])
        self.assertEqual(signal.SIGTERM, report["commands"][0]["interruptedSignal"])
        self.assertEqual([], json.loads(acquired.read_text())["respondedAfterLockAcquired"],
                         "A command-group child was still executing after the build lock became available")

    def cleanup_processes(self):
        if self.fixture_group is not None:
            try:
                os.killpg(self.fixture_group, signal.SIGKILL)
            except ProcessLookupError:
                pass
        for process in reversed(self.processes):
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            process.communicate(timeout=5)


if __name__ == "__main__":
    unittest.main()
