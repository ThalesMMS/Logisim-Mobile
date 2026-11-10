#!/usr/bin/env python3
"""Run the builder against fake SDK tools and synthetic, never-executed Mach-O files."""
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
DRIVER = ROOT / "build-simulator-fixture.py"
GROUP = "group.thalesmms.logisim.validation.revocationfixture"
APP = "thalesmms.logisim.validation.revocationfixture"
FAKE_TOOL = r'''
import importlib.util, json, os, pathlib, plistlib, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
if name == "xcodegen": raise SystemExit(0)
if name == "xcodebuild":
    spec = importlib.util.spec_from_file_location("synthetic", os.environ["FIXTURE_TEST_HELPERS"])
    synthetic = importlib.util.module_from_spec(spec);spec.loader.exec_module(synthetic)
    derived = pathlib.Path(args[args.index("-derivedDataPath") + 1])
    output = derived / "Build/Products/Debug-iphonesimulator"
    app = output / "RevocationFixture.app"
    for bundle, identifier, executable in [
        (app, "thalesmms.logisim.validation.revocationfixture", "RevocationFixture"),
        (app / "PlugIns/RevocationProvider.appex", "thalesmms.logisim.validation.revocationfixture.provider", "RevocationProvider")]:
        bundle.mkdir(parents=True)
        (bundle / "Info.plist").write_bytes(plistlib.dumps({"CFBundleIdentifier":identifier,"CFBundleExecutable":executable}))
        group = "group.other" if os.environ.get("FIXTURE_TEST_WRONG_GROUP") else synthetic.GROUP
        value = {synthetic.MODULE.GROUP_KEY:[group]}
        xml, encoded = plistlib.dumps(value), synthetic.entitlement_der(value)
        (bundle / executable).write_bytes(synthetic.macho([
            ("__entitlements",xml),("__ents_der",encoded)]))
        packaged = derived / "Build/Intermediates.noindex" / executable
        packaged.mkdir(parents=True)
        (packaged / (bundle.name + "-Simulated.xcent")).write_bytes(xml)
        (packaged / (bundle.name + "-Simulated.xcent.der")).write_bytes(encoded)
    if os.environ.get("FIXTURE_TEST_MUTATE_HELPER"):
        stage = pathlib.Path(args[args.index("-project") + 1]).parent
        with (stage / "simulator_entitlements.py").open("a") as stream:stream.write("\nraise RuntimeError('MUTATED_VERIFIER_EXECUTED')\n")
    if os.environ.get("FIXTURE_TEST_MUTATE_LIVE_READER"):
        pathlib.Path(os.environ["FIXTURE_TEST_MUTATE_LIVE_READER"]).write_text("raise RuntimeError('LIVE_READER_EXECUTED')\n")
    if os.environ.get("FIXTURE_TEST_MUTATE_EXPORTED_READER"):
        (derived.parent / "artifact-reader.py").write_text("raise RuntimeError('MUTATED_READER_EXECUTED')\n")
    if os.environ.get("FIXTURE_TEST_PACKAGING_MISMATCH"):
        (packaged / (bundle.name + "-Simulated.xcent.der")).write_bytes(b"mismatch")
    print(json.dumps(args));raise SystemExit(0)
if name == "codesign":
    if "--force" in args or "--sign" in args:
        print("Forbidden post-build signing request");raise SystemExit(97)
    if "--entitlements" in args:
        value = json.loads(os.environ.get("FIXTURE_TEST_HOST_ENTITLEMENTS", "{}"))
        sys.stdout.buffer.write(plistlib.dumps(value));raise SystemExit(0)
    if "--display" in args:
        print("Signature=adhoc\nTeamIdentifier=not set");raise SystemExit(0)
    if "--verify" in args:raise SystemExit(0)
if name == "xcrun" and args == ["--sdk","iphonesimulator","--show-sdk-version"]:
    print("27.2");raise SystemExit(0)
if name == "lipo" and args[0] == "-archs":print("arm64");raise SystemExit(0)
print("Unexpected fake-tool request",name,args);raise SystemExit(98)
'''
COMMON = '''
from pathlib import Path
import hashlib,plistlib
def artifact(app, identifier):
    info=plistlib.loads((app/"Info.plist").read_bytes())
    return {"path":str(app),"bundle_id":identifier,"executable_sha256":hashlib.sha256((app/info["CFBundleExecutable"]).read_bytes()).hexdigest()}
'''


class XcodeSimulatorBuilderTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / "source"
        fixture = self.source / "scripts/ios_fileprovider_revocation"
        (fixture / "Config").mkdir(parents=True)
        for filename in ["build-simulator-fixture.py", "simulator_entitlements.py"]:
            (fixture / filename).write_bytes(DRIVER.with_name(filename).read_bytes())
        for kind in ["App", "Provider"]:
            (fixture / "Config" / (kind + ".entitlements")).write_bytes(plistlib.dumps({
                "com.apple.security.application-groups": [GROUP]}))
        (fixture / "verify-fixture.py").write_text('print("Synthetic configuration")\n')
        (self.source / "scripts/run-ios-accessibility.py").write_text(COMMON)
        git = shutil.which("git")
        self.assertIsNotNone(git)
        self.env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null")
        def command(*args):
            return subprocess.check_output([git,"-c","core.hooksPath=/dev/null","-c","commit.gpgsign=false",*args],
                                           cwd=self.source,env=self.env,stderr=subprocess.STDOUT,text=True).strip()
        command("-c","init.templateDir=","init","--quiet")
        command("add","scripts")
        command("-c","user.name=Fixture Test","-c","user.email=fixture@example.invalid", "commit","--quiet","-m","Add fake fixture")
        self.git_command = command
        self.pin = command("rev-parse","HEAD")
        guard = self.root / "fake-sdk"
        guard.mkdir()
        (guard / "python3").symlink_to(sys.executable)
        (guard / "git").symlink_to(git)
        for name in ["xcodegen","xcodebuild","codesign","xcrun","lipo"]:
            path=guard/name;path.write_text("#!"+sys.executable+"\n"+FAKE_TOOL);path.chmod(0o755)
        self.env.update(PATH=str(guard), FIXTURE_TEST_HELPERS=str(ROOT / "test_simulator_entitlements.py"))
        self.output = self.root / "retained"

    def run_builder(self):
        result = subprocess.run([sys.executable,str(DRIVER),"--source-root",str(self.source),"--expected-source",self.pin,
                                 "--output",str(self.output),"--lock-file",str(self.root/"fake.lock")],
                                env=self.env,capture_output=True,text=True,timeout=15)
        report=json.loads((self.output/"artifact.json").read_text())
        return result,report

    def test_build_uses_xcode_signing_without_a_post_signing_command(self):
        result,report=self.run_builder()
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        self.assertEqual("BUILD_AND_ADHOC_SIGN_PASS",report["status"])
        self.assertEqual("NOT_RUN",report["nativeExecution"])
        self.assertEqual("NOT_RUN",report["domainRegistration"])
        self.assertTrue(report["simulatorEntitlementReviewRequired"])
        build,= [row["arguments"] for row in report["commands"] if row["arguments"][0]=="xcodebuild"]
        for flag in ["CODE_SIGNING_ALLOWED=YES","CODE_SIGNING_REQUIRED=YES","CODE_SIGN_IDENTITY=-",
                     "CODE_SIGN_STYLE=Manual","DEVELOPMENT_TEAM="]:
            self.assertIn(flag,build)
        for row in report["commands"]:
            self.assertNotIn("--sign",row["arguments"])
            self.assertNotIn("--force",row["arguments"])
        for kind in ["App","Provider"]:
            self.assertEqual({},report["simulatorEntitlements"][kind]["codeSignatureEntitlements"])
            self.assertEqual({"com.apple.security.application-groups":[GROUP]},
                             report["simulatorEntitlements"][kind]["simulatedEntitlements"])
            self.assertEqual({},report["simulatorEntitlements"][kind]["generatedAdditionalKeys"])

    def test_generated_wrong_group_is_not_a_successful_build(self):
        self.env["FIXTURE_TEST_WRONG_GROUP"]="1"
        result,report=self.run_builder()
        self.assertEqual(1,result.returncode)
        self.assertEqual("FAIL",report["status"])
        self.assertIn("exact fixture App Group",report["error"]["message"])
        self.assertTrue(report["simulatorEntitlementReviewRequired"])
        self.assertEqual("NOT_RUN",report["nativeExecution"])


    def test_different_committed_driver_fails_before_any_build_command(self):
        driver = self.source / "scripts/ios_fileprovider_revocation/build-simulator-fixture.py"
        driver.write_bytes(driver.read_bytes() + b"\n# Different committed driver\n")
        self.git_command("add", "scripts")
        self.git_command("-c", "user.name=Fixture Test", "-c", "user.email=fixture@example.invalid",
                         "commit", "--quiet", "-m", "Change committed driver")
        self.pin = self.git_command("rev-parse", "HEAD")
        result, report = self.run_builder()
        self.assertEqual(1, result.returncode)
        self.assertEqual([], report["commands"])
        self.assertIn("invoked driver differs", report["error"]["message"])

    def test_staged_helper_changed_during_fake_build_is_not_executed(self):
        self.env["FIXTURE_TEST_MUTATE_HELPER"] = "1"
        result, report = self.run_builder()
        self.assertEqual(1, result.returncode)
        self.assertIn("verifier changed after export", report["error"]["message"])
        self.assertNotIn("MUTATED_VERIFIER_EXECUTED", report["error"]["message"])
        self.assertEqual("NOT_RUN", report["nativeExecution"])

    def test_packaging_input_mismatch_is_not_accepted(self):
        self.env["FIXTURE_TEST_PACKAGING_MISMATCH"] = "1"
        result, report = self.run_builder()
        self.assertEqual(1, result.returncode)
        self.assertIn("differs from its Xcode packaging input", report["error"]["message"])

    def test_host_signature_team_claim_is_rejected(self):
        self.env["FIXTURE_TEST_HOST_ENTITLEMENTS"] = json.dumps({"com.apple.developer.team-identifier": "TEAM123"})
        result, report = self.run_builder()
        self.assertEqual(1, result.returncode)
        self.assertIn("claim a Team identifier", report["error"]["message"])

    def test_mutated_live_artifact_reader_is_not_imported(self):
        self.env["FIXTURE_TEST_MUTATE_LIVE_READER"] = str(self.source / "scripts/run-ios-accessibility.py")
        result, report = self.run_builder()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("BUILD_AND_ADHOC_SIGN_PASS", report["status"])
        self.assertEqual(COMMON, (self.output / "artifact-reader.py").read_text())
        self.assertEqual("scripts/run-ios-accessibility.py", report["artifactReaderSourcePath"])

    def test_mutated_exported_artifact_reader_is_not_executed(self):
        self.env["FIXTURE_TEST_MUTATE_EXPORTED_READER"] = "1"
        result, report = self.run_builder()
        self.assertEqual(1, result.returncode)
        self.assertIn("artifact reader changed after export", report["error"]["message"])
        self.assertNotIn("MUTATED_READER_EXECUTED", report["error"]["message"])

    def test_host_signature_another_group_is_rejected(self):
        self.env["FIXTURE_TEST_HOST_ENTITLEMENTS"] = json.dumps({"com.apple.security.application-groups": ["group.other"]})
        result, report = self.run_builder()
        self.assertEqual(1, result.returncode)
        self.assertIn("claim another App Group", report["error"]["message"])


if __name__ == "__main__":
    unittest.main()
