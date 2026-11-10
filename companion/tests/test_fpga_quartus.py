import base64
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest

from companion.api import JobContext, JobFailure
from companion.fpga_quartus import build
from companion.server import default_registry


class QuartusBuildTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name)
        self.inputs = self.path / "inputs"
        self.inputs.mkdir()
        self.files = {
            "source-0000.v": "module logisim_fpga_top(input fpga_p0_b0, output fpga_p1_b0);"
                             "assign fpga_p1_b0 = fpga_p0_b0; endmodule\n",
            "pins.qsf": 'set_location_assignment PIN_U13 -to fpga_p0_b0\n'
                        'set_location_assignment PIN_W2 -to fpga_p1_b0\n'
                        'set_global_assignment -name RESERVE_ALL_UNUSED_PINS "AS INPUT TRI-STATED"\n',
            "timing.sdc": "derive_clock_uncertainty\n",
        }
        for name, content in self.files.items():
            (self.inputs / name).write_text(content)
        self.tool = self.path / "fixture-quartus"
        self.parameters = {"language": "verilog", "sources": ["source-0000.v"]}
        self.events = []
        self.cancelled = threading.Event()

    def context(self, confirmed=True, configure=True):
        return JobContext(self.path, {name: self.inputs / name for name in self.files},
                          {"quartus_sh": str(self.tool)} if configure else {}, self.cancelled,
                          timeout_seconds=3, user_confirmed=confirmed,
                          emit=lambda stream, value: self.events.append((stream, value)))

    def executable(self, script):
        self.tool.write_text(f"#!{sys.executable}\n" + script)
        self.tool.chmod(0o755)

    def test_approved_fixed_quartus_command_returns_only_the_fixture_sof(self):
        self.executable("""
import pathlib, sys
assert sys.argv[1:] == ['--flow', 'compile', 'build']
assert 'PROJECT_REVISION = "build"' in pathlib.Path('build.qpf').read_text()
settings = pathlib.Path('build.qsf').read_text()
assert 'DEVICE 5CEBA4F23C7' in settings
assert 'TOP_LEVEL_ENTITY logisim_fpga_top' in settings
assert 'SYSTEMVERILOG_FILE {inputs/source-0000.v}' in settings
assert 'PIN_U13 -to fpga_p0_b0' in settings and 'PIN_W2 -to fpga_p1_b0' in settings
assert pathlib.Path('timing.sdc').read_text() == 'derive_clock_uncertainty\\n'
output = pathlib.Path('quartus-output')
output.mkdir()
(output / 'build.sof').write_bytes(b'fixture-sof')
print('fixture tool log')
""")
        operation = default_registry().operations["fpga-quartus-build"]
        self.assertTrue(operation.requires_confirmation)
        self.assertEqual(("quartus_sh",), operation.required_tools)
        result = build(self.context(), self.parameters)
        self.assertTrue(result["bitstreamGenerated"])
        self.assertFalse(result["programmed"])
        self.assertFalse(result["hardwareVerified"])
        [artifact] = result["artifacts"]
        self.assertEqual("design.sof", artifact["name"])
        self.assertEqual(b"fixture-sof", base64.b64decode(artifact["base64"]))
        self.assertIn(("stage", "quartus-compile"), self.events)
        self.assertIn(("stdout", "fixture tool log\n"), self.events)

    def test_catalog_fpga_targets_select_fixed_qsf_family_and_device(self):
        self.executable("""
import pathlib, sys
assert sys.argv[1:] == ['--flow', 'compile', 'build']
output = pathlib.Path('quartus-output')
output.mkdir(exist_ok=True)
(output / 'build.sof').write_bytes(b'fixture-catalog-sof')
""")
        for target, family, part in [
            ("ep1c12q240c8", "Cyclone", "EP1C12Q240C8"),
            ("ep4ce22f17c7", "Cyclone IV E", "EP4CE22F17C7"),
            ("10m08sau169c8g", "MAX 10", "10M08SAU169C8G"),
            ("5cseba6u23i7", "Cyclone V", "5CSEBA6U23I7"),
        ]:
            with self.subTest(target=target):
                result = build(self.context(), {**self.parameters, "target": target})
                [artifact] = result["artifacts"]
                self.assertEqual("design.sof", artifact["name"])
                self.assertEqual(b"fixture-catalog-sof", base64.b64decode(artifact["base64"]))
                settings = (self.path / "build.qsf").read_text().splitlines()
                self.assertIn(f'set_global_assignment -name FAMILY "{family}"', settings)
                self.assertIn(f"set_global_assignment -name DEVICE {part}", settings)

    def test_max_v_target_selects_the_device_and_returns_the_pof(self):
        self.executable("""
import pathlib, sys
assert sys.argv[1:] == ['--flow', 'compile', 'build']
settings = pathlib.Path('build.qsf').read_text()
assert 'FAMILY "MAX V"' in settings
assert 'DEVICE 5M570ZF256C5' in settings
output = pathlib.Path('quartus-output')
output.mkdir()
(output / 'build.pof').write_bytes(b'fixture-max-v-pof')
(output / 'build.sof').write_bytes(b'unrelated-sof')
""")
        result = build(self.context(), {**self.parameters, "target": "max-v-5m570z"})
        self.assertTrue(result["bitstreamGenerated"])
        self.assertFalse(result["programmed"])
        [artifact] = result["artifacts"]
        self.assertEqual("design.pof", artifact["name"])
        self.assertEqual(b"fixture-max-v-pof", base64.b64decode(artifact["base64"]))

    def test_unlisted_device_is_rejected_without_launching_quartus(self):
        with self.assertRaises(JobFailure) as failure:
            build(self.context(configure=False), {**self.parameters, "target": "5M2210ZF256C5"})
        self.assertEqual("unsupported-fpga-device", failure.exception.code)
        self.assertEqual([], self.events)

    def test_rejects_tcl_and_conflicting_assignments_before_executing(self):
        for name, extra in [
            ("pins.qsf", 'source /tmp/untrusted.tcl\n'),
            ("pins.qsf", 'set_location_assignment PIN_U13 -to fpga_p2_b0\n'),
            ("timing.sdc", 'create_clock -name C -period 20 [exec bad]\n'),
        ]:
            with self.subTest(name=name, extra=extra):
                path = self.inputs / name
                path.write_text(extra + self.files[name])
                try:
                    with self.assertRaises(JobFailure) as failure:
                        build(self.context(configure=False), self.parameters)
                    self.assertEqual("invalid-fpga-constraint", failure.exception.code)
                    self.assertEqual([], self.events)
                finally:
                    path.write_text(self.files[name])

    def test_confirmation_and_installed_tool_are_required(self):
        for expected, context in [
            ("confirmation-required", self.context(confirmed=False)),
            ("missing-tool", self.context(configure=False)),
        ]:
            with self.subTest(expected=expected):
                with self.assertRaises(JobFailure) as failure:
                    build(context, self.parameters)
                self.assertEqual(expected, failure.exception.code)

    def test_stage_failure_and_missing_sof_are_diagnosed(self):
        for expected, script in [
            ("fpga-stage-failed", "import sys\nprint('Fitter failed', file=sys.stderr)\nsys.exit(8)\n"),
            ("fpga-missing-artifact", "pass\n"),
        ]:
            with self.subTest(expected=expected):
                self.executable(script)
                with self.assertRaises(JobFailure) as failure:
                    build(self.context(), self.parameters)
                self.assertEqual(expected, failure.exception.code)
                if expected == "fpga-stage-failed":
                    self.assertEqual("quartus-compile", failure.exception.details["stage"])
                    self.assertEqual(8, failure.exception.details["exitCode"])
                    self.assertIn("Fitter failed", failure.exception.details["stderr"])

    def test_cancellation_interrupts_the_active_quartus_process(self):
        self.executable("import pathlib, time\npathlib.Path('started').touch()\ntime.sleep(60)\n")

        def cancel_after_launch():
            deadline = time.monotonic() + 2
            while not (self.path / "started").exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.cancelled.set()

        cancel = threading.Thread(target=cancel_after_launch)
        cancel.start()
        self.addCleanup(cancel.join)
        with self.assertRaises(JobFailure) as failure:
            build(self.context(), self.parameters)
        self.assertTrue((self.path / "started").exists())
        self.assertEqual("cancelled", failure.exception.code)
