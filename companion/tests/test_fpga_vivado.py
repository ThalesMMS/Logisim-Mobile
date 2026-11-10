import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest

from companion.api import JobContext, JobFailure
from companion.fpga_vivado import build
from companion.server import CompanionService, Configuration, default_registry


class VivadoBuildTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        input_dir = self.root / "inputs"
        input_dir.mkdir()
        self.source = input_dir / "source-0000.v"
        self.source.write_text("module logisim_fpga_top(input fpga_p0_b0, output fpga_p1_b0); "
                               "assign fpga_p1_b0 = fpga_p0_b0; endmodule\n")
        self.xdc = input_dir / "pins.xdc"
        self.xdc.write_text("set_property PACKAGE_PIN V17 [get_ports {fpga_p0_b0}]\n"
                            "set_property IOSTANDARD LVCMOS33 [get_ports {fpga_p0_b0}]\n"
                            "set_property PACKAGE_PIN U16 [get_ports {fpga_p1_b0}]\n"
                            "set_property IOSTANDARD LVCMOS33 [get_ports {fpga_p1_b0}]\n"
                            "create_clock -period 1.0E-3 [get_ports {fpga_p0_b0}]\n")
        self.parameters = {"part": "xc7a35tcpg236-1", "top": "logisim_fpga_top", "language": "verilog",
                           "sources": [self.source.name], "constraint": self.xdc.name}
        self.tool = self.root / "vivado-fixture"
        self.logs = []

    def fixture(self, code):
        self.tool.write_text(f"#!{sys.executable}\n" + code)
        self.tool.chmod(0o755)

    def context(self, confirmed=True):
        return JobContext(self.root, {self.source.name: self.source, self.xdc.name: self.xdc},
                          {"vivado": str(self.tool)}, threading.Event(),
                          user_confirmed=confirmed, emit=lambda kind, text: self.logs.append((kind, text)))

    def test_reviewed_basys3_job_runs_only_host_generated_tcl_and_returns_verified_files(self):
        self.fixture(
            "import json, sys\n"
            "from pathlib import Path\n"
            "Path('argv.json').write_text(json.dumps(sys.argv[1:]))\n"
            "Path('outputs/design.bit').write_bytes(b'fixture-bitstream')\n"
            "Path('outputs/timing.txt').write_text('fixture timing')\n"
            "print('fixture synthesis output')\n")
        result = build(self.context(), self.parameters)
        self.assertEqual(["-mode", "batch", "-nojournal", "-nolog", "-source", "generated-vivado.tcl"],
                         json.loads((self.root / "argv.json").read_text()))
        script = (self.root / "generated-vivado.tcl").read_text()
        self.assertIn("read_verilog -sv {inputs/source-0000.v}", script)
        self.assertIn("synth_design -top logisim_fpga_top -part xc7a35tcpg236-1", script)
        self.assertIn("write_bitstream -force {outputs/design.bit}", script)
        self.assertEqual(self.xdc.read_bytes(), (self.root / "validated-pins.xdc").read_bytes())
        self.assertIn(("stdout", "fixture synthesis output\n"), self.logs)
        self.assertTrue(result["bitstreamGenerated"])
        self.assertFalse(result["programmed"])
        self.assertFalse(result["hardwareVerified"])
        self.assertEqual(["design.bit", "timing.txt"], [item["name"] for item in result["artifacts"]])

    def test_rejects_unconfirmed_different_part_and_tcl_injection_before_execution(self):
        for context, parameters, expected in (
            (self.context(confirmed=False), self.parameters, "confirmation-required"),
            (self.context(), {**self.parameters, "part": "xc7a100tcsg324-1"}, "invalid-fpga-parameter"),
            (self.context(), {**self.parameters, "part": "xc7a15tftg256-1"}, "invalid-fpga-parameter"),
            (self.context(), {**self.parameters, "part": "xc7z010clg400-2"}, "invalid-fpga-parameter"),
        ):
            with self.subTest(expected=expected), self.assertRaises(JobFailure) as failure:
                build(context, parameters)
            self.assertEqual(expected, failure.exception.code)
        for line in ("set_property PACKAGE_PIN V17 [get_ports {fpga_p0_b0}]; exec touch unexpected",
                     "set_property IOSTANDARD [exec touch unexpected] [get_ports {fpga_p0_b0}]",
                     "set_property PACKAGE_PIN N14 [get_ports {fpga_scan_clock}]; exec touch unexpected",
                     "set_property PACKAGE_PIN V17 [get_ports {fpga_p0_b0}]\\",
                     "set_property DRIVE 12 [get_ports {fpga_p0_b0}]"):
            with self.subTest(line=line), self.assertRaises(JobFailure) as failure:
                self.xdc.write_text(line + "\n")
                build(self.context(), self.parameters)
            self.assertEqual("invalid-fpga-constraint", failure.exception.code)
        self.assertFalse((self.root / "generated-vivado.tcl").exists())

    def test_alchitry_scan_pins_are_accepted_without_relaxing_the_tcl_syntax(self):
        self.fixture("from pathlib import Path\n"
                     "Path('outputs/design.bit').write_bytes(b'fixture-bitstream')\n"
                     "Path('outputs/timing.txt').write_text('fixture timing')\n")
        self.xdc.write_text("set_property PACKAGE_PIN N14 [get_ports {fpga_scan_clock}]\n"
                            "create_clock -period 10.0 [get_ports {fpga_scan_clock}]\n"
                            "set_property PACKAGE_PIN T5 [get_ports {fpga_scan_0_p0}]\n"
                            "set_property IOSTANDARD LVCMOS33 [get_ports {fpga_scan_0_p0}]\n")
        result = build(self.context(), {**self.parameters, "part": "xc7a35tftg256-1"})
        self.assertEqual("xc7a35tftg256-1", result["part"])
        self.assertEqual(self.xdc.read_bytes(), (self.root / "validated-pins.xdc").read_bytes())
        script = (self.root / "generated-vivado.tcl").read_text()
        self.assertIn("synth_design -top logisim_fpga_top -part xc7a35tftg256-1", script)

    def test_cmod_and_zybo_builds_keep_the_reviewed_part_and_constraints(self):
        self.fixture("from pathlib import Path\n"
                     "Path('outputs/design.bit').write_bytes(b'fixture-bitstream')\n"
                     "Path('outputs/timing.txt').write_text('fixture timing')\n")
        for part, input_pin, output_pin in (
            ("xc7a15tcpg236-1", "A18", "A17"),
            ("xc7z010clg400-1", "T16", "D18"),
        ):
            with self.subTest(part=part):
                self.xdc.write_text(f"set_property PACKAGE_PIN {input_pin} [get_ports {{fpga_p0_b0}}]\n"
                                    "set_property IOSTANDARD LVCMOS33 [get_ports {fpga_p0_b0}]\n"
                                    f"set_property PACKAGE_PIN {output_pin} [get_ports {{fpga_p1_b0}}]\n"
                                    "set_property IOSTANDARD LVCMOS33 [get_ports {fpga_p1_b0}]\n")
                parameters = {**self.parameters, "part": part}
                with self.assertRaises(JobFailure) as failure:
                    build(self.context(confirmed=False), parameters)
                self.assertEqual("confirmation-required", failure.exception.code)
                result = build(self.context(), parameters)
                self.assertEqual(part, result["part"])
                self.assertFalse(result["programmed"])
                self.assertFalse(result["hardwareVerified"])
                self.assertEqual(["design.bit", "timing.txt"], [item["name"] for item in result["artifacts"]])
                self.assertEqual(self.xdc.read_bytes(), (self.root / "validated-pins.xdc").read_bytes())
                script = (self.root / "generated-vivado.tcl").read_text()
                self.assertIn(f"synth_design -top logisim_fpga_top -part {part}", script)

    def test_vendor_exit_status_is_exposed_with_stage_and_log(self):
        self.fixture("import sys\nprint('fixture Vivado diagnostic', file=sys.stderr)\nsys.exit(9)\n")
        with self.assertRaises(JobFailure) as failure:
            build(self.context(), self.parameters)
        self.assertEqual("fpga-stage-failed", failure.exception.code)
        self.assertEqual("vivado-build", failure.exception.details["stage"])
        self.assertEqual(9, failure.exception.details["exitCode"])
        self.assertIn("fixture Vivado diagnostic", failure.exception.details["stderr"])

    def test_cancellation_stops_the_running_build_before_artifacts(self):
        self.fixture("from pathlib import Path\nimport time\n"
                     "Path('started').touch()\ntime.sleep(30)\n"
                     "Path('outputs/design.bit').write_bytes(b'unexpected')\n")
        context = self.context()

        def cancel_on_start():
            deadline = time.monotonic() + 3
            while not (self.root / "started").is_file() and time.monotonic() < deadline:
                time.sleep(0.01)
            context.cancelled.set()

        canceller = threading.Thread(target=cancel_on_start)
        canceller.start()
        try:
            with self.assertRaises(JobFailure) as failure:
                build(context, self.parameters)
        finally:
            canceller.join()
        self.assertEqual("cancelled", failure.exception.code)
        self.assertTrue((self.root / "started").is_file())
        self.assertFalse((self.root / "outputs/design.bit").exists())

    def test_host_limits_and_vivado_capability_are_reported(self):
        self.fixture("raise SystemExit('test executable must not run')\n")
        configuration = Configuration("t" * 32, {"fpga-vivado-build"},
                                      tool_paths={"vivado": str(self.tool)}, timeout_seconds=300,
                                      max_output_bytes=5 * 1024 * 1024, memory_limit_bytes=8 * 1024**3)
        service = CompanionService(configuration, default_registry())
        self.addCleanup(service.close)
        capabilities = service.capabilities()
        operation = next(item for item in capabilities["operations"] if item["name"] == "fpga-vivado-build")
        self.assertTrue(operation["allowed"] and operation["available"] and operation["requiresConfirmation"])
        self.assertEqual(8 * 1024**3, capabilities["limits"]["processMemoryBytes"])
        self.assertEqual(300, capabilities["limits"]["timeoutSeconds"])
        self.assertEqual(5 * 1024 * 1024, capabilities["limits"]["outputBytes"])
