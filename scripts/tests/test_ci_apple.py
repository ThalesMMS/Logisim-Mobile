"""Check architecture routing at the verification command boundary."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "verify.sh"
SIMULATOR_UUID = "11111111-1111-4111-8111-111111111111"


class AppleVerificationRoutingTest(unittest.TestCase):
    def run_verifier(self, target, host_arch, simulator_ready=True):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "scripts").mkdir()
        shutil.copy2(SCRIPT, root / "scripts/verify.sh")
        binary = root / "bin"
        binary.mkdir()
        log = root / "commands.log"
        tools = {
            "uname": f'#!/bin/sh\nif [ "$1" = -m ]; then echo {host_arch}; else echo Darwin; fi\n',
            "python3": ('#!/bin/sh\nprintf "python3 %s\\n" "$*" >> "$COMMAND_LOG"\n'
                        'if [ "$1" = scripts/ci-boot-ios-simulator.py ]; then\n'
                        + (f'echo {SIMULATOR_UUID}\n' if simulator_ready else 'exit 1\n') + 'fi\n'),
            "xcodegen": '#!/bin/sh\nprintf "xcodegen %s\\n" "$*" >> "$COMMAND_LOG"\n',
            "xcodebuild": '#!/bin/sh\nprintf "xcodebuild %s\\n" "$*" >> "$COMMAND_LOG"\n',
        }
        for name, body in tools.items():
            path = binary / name
            path.write_text(body)
            path.chmod(0o755)
        gradle = root / "gradlew"
        gradle.write_text('#!/bin/sh\nprintf "gradle %s\\n" "$*" >> "$COMMAND_LOG"\n')
        gradle.chmod(0o755)
        env = os.environ.copy()
        env.update(PATH=f"{binary}:/usr/bin:/bin", JAVA_HOME="/unused-test-jdk", COMMAND_LOG=str(log))
        result = subprocess.run(["/bin/sh", str(root / "scripts/verify.sh"), target],
                                env=env, capture_output=True, text=True)
        return result, log.read_text() if log.exists() else ""

    def test_intel_executes_macos_native_and_cross_builds_ios_arm64(self):
        result, commands = self.run_verifier("ios-intel", "x86_64")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("gradle -PenableMacosX64Ci=true :core:macosX64Test :composeApp:linkDebugFrameworkIosSimulatorArm64", commands)
        self.assertNotIn(":core:iosSimulatorArm64Test", commands)
        self.assertNotIn(":composeApp:iosSimulatorArm64Test", commands)
        self.assertNotIn("ci-boot-ios-simulator.py", commands)
        self.assertIn("xcodebuild -project iosApp/LogisimMobile.xcodeproj", commands)
        self.assertIn("ARCHS=arm64 build", commands)

    def test_apple_silicon_keeps_the_ios_runtime_path(self):
        result, commands = self.run_verifier("ios", "arm64")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"gradle -PiosTestDevice={SIMULATOR_UUID} :core:iosSimulatorArm64Test :composeApp:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosSimulatorArm64", commands)
        self.assertIn("python3 scripts/ci-boot-ios-simulator.py --diagnostics outputs/ci/ios-simulator.json", commands)
        self.assertLess(commands.index("ci-boot-ios-simulator.py"), commands.index("gradle "))
        self.assertNotIn("macosX64", commands)

    def test_failed_simulator_preparation_stops_before_gradle(self):
        result, commands = self.run_verifier("ios", "arm64", simulator_ready=False)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("gradle ", commands)
        self.assertNotIn("xcodebuild ", commands)

    def test_intel_mode_rejects_an_arm_host_before_native_tasks(self):
        result, commands = self.run_verifier("ios-intel", "arm64")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("host macOS x86_64", result.stderr)
        self.assertNotIn("gradle ", commands)

    def test_ios_runtime_mode_rejects_an_intel_host_instead_of_skipping(self):
        result, commands = self.run_verifier("ios", "x86_64")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Apple Silicon", result.stderr)
        self.assertNotIn("gradle ", commands)


if __name__ == "__main__":
    unittest.main()
