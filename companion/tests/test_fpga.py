import base64
import json
import os
from pathlib import Path
import shutil
from subprocess import CompletedProcess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

from companion.api import JobContext, JobFailure, OperationRegistry
from companion.fpga import FpgaProgramTarget, program, register, synthesize


YOSYS = shutil.which("yosys") or "/opt/homebrew/bin/yosys"
NEXTPNR_ICE40 = shutil.which("nextpnr-ice40") or "/opt/homebrew/bin/nextpnr-ice40"
ICEPACK = shutil.which("icepack") or "/opt/homebrew/bin/icepack"
OSS_SUITE = Path(__file__).resolve().parents[2] / "outputs/toolchains/oss-cad-suite-fpga/oss-cad-suite/bin"
NEXTPNR_ECP5 = os.environ.get("NEXTPNR_ECP5") or shutil.which("nextpnr-ecp5") or str(OSS_SUITE / "nextpnr-ecp5")
ECPPACK = os.environ.get("ECPPACK") or shutil.which("ecppack") or str(OSS_SUITE / "ecppack")
GHDL = os.environ.get("GHDL") or shutil.which("ghdl") or str(OSS_SUITE / "ghdl")
HDL = "module user_top(input a, input b, output y); assign y = a & b; endmodule\n"


class FpgaHandlerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name)
        self.source = self.path / "design.v"
        self.source.write_text(HDL)
        self.events = []

    def context(self, tools=None, confirmed=False):
        return JobContext(self.path, {path.name: path for path in self.path.iterdir() if path.is_file()},
                          tools if tools is not None else {"yosys": YOSYS}, threading.Event(),
                          emit=lambda stream, text: self.events.append((stream, text)),
                          user_confirmed=confirmed)

    @staticmethod
    def parameters(**changes):
        return dict(flow="ecp5", part="85k", package="CABGA381", top="user_top",
                    sources=["design.v"], language="verilog", goal="synthesis", **changes)

    @unittest.skipUnless(Path(YOSYS).is_file(), "Yosys is not installed")
    def test_real_yosys_synthesizes_both_open_architectures(self):
        for flow, part, package, lut in [("ecp5", "85k", "CABGA381", "LUT4"),
                                         ("ice40", "hx1k", "tq144", "SB_LUT4")]:
            with self.subTest(flow=flow):
                parameters = self.parameters()
                parameters.update(flow=flow, part=part, package=package)
                result = synthesize(self.context(), parameters)
                self.assertTrue(result["synthesized"])
                self.assertFalse(result["placedAndRouted"])
                self.assertFalse(result["bitstreamGenerated"])
                self.assertFalse(result["programmed"])
                self.assertFalse(result["hardwareVerified"])
                artifact = result["artifacts"][0]
                netlist = json.loads(base64.b64decode(artifact["base64"]))
                module = netlist["modules"]["user_top"]
                self.assertEqual({"a", "b", "y"}, set(module["ports"]))
                self.assertEqual({lut}, {cell["type"] for cell in module["cells"].values()})
                self.assertIn(("stage", "synthesize"), self.events)

    @unittest.skipUnless(all(Path(path).is_file() for path in [YOSYS, NEXTPNR_ICE40, ICEPACK]),
                         "The open iCE40 toolchain is not installed")
    def test_real_ice40_place_route_and_bitstream_generation_without_hardware(self):
        (self.path / "pins.pcf").write_text("set_io a 21\nset_io b 22\nset_io y 23\n")
        parameters = self.parameters()
        parameters.update(flow="ice40", part="hx1k", package="tq144", goal="bitstream", constraint="pins.pcf")
        result = synthesize(self.context(tools={"yosys": YOSYS, "nextpnr-ice40": NEXTPNR_ICE40,
                                                "icepack": ICEPACK}), parameters)
        self.assertTrue(result["synthesized"])
        self.assertTrue(result["placedAndRouted"])
        self.assertTrue(result["bitstreamGenerated"])
        self.assertFalse(result["programmed"])
        self.assertFalse(result["hardwareVerified"])
        self.assertEqual(["synthesize", "place-route", "pack"],
                         [value for stream, value in self.events if stream == "stage"])
        artifacts = {artifact["name"]: base64.b64decode(artifact["base64"]) for artifact in result["artifacts"]}
        self.assertEqual({"synthesized.json", "routed.asc", "design.bin"}, set(artifacts))
        self.assertIn(b".device 1k", artifacts["routed.asc"])
        self.assertGreater(len(artifacts["design.bin"]), 1000)

    def test_ecp5_packs_with_the_configuration_clock_independent_of_the_route_clock(self):
        (self.path / "pins.lpf").write_text('LOCATE COMP "a" SITE "C3";\n')
        parameters = self.parameters()
        parameters.update(goal="bitstream", constraint="pins.lpf", frequency_mhz=50)
        artifacts = {
            "yosys": ("synthesized.json", b'{"modules": {"user_top": {}}}'),
            "nextpnr-ecp5": ("routed.config", b".device LFE5U-85F\n"),
            "ecppack": ("design.bit", b"fixture bitstream"),
        }
        context = self.context(tools={name: name for name in artifacts})

        def run_stage(arguments):
            name, content = artifacts[arguments[0]]
            context.output_path(name).write_bytes(content)
            return CompletedProcess(arguments, 0, stdout="", stderr="")

        with patch.object(context, "run", side_effect=run_stage) as run:
            result = synthesize(context, parameters)
        self.assertTrue(result["bitstreamGenerated"])
        self.assertEqual(["--freq", "50"], run.call_args_list[1].args[0][-2:])
        self.assertEqual(
            ["ecppack", "--compress", "--freq", "62.0", "--input", str(context.output_path("routed.config")),
             "--bit", str(context.output_path("design.bit"))],
            run.call_args_list[2].args[0],
        )

    @unittest.skipUnless(all(Path(path).is_file() for path in [YOSYS, NEXTPNR_ECP5, ECPPACK]),
                         "The open ECP5 toolchain is not installed")
    def test_real_ecp5_gecko_device_place_route_and_bitstream_generation_without_hardware(self):
        (self.path / "pins.lpf").write_text('LOCATE COMP "a" SITE "C3";\nLOCATE COMP "b" SITE "C4";\n'
                                            'LOCATE COMP "y" SITE "D5";\n'
                                            'IOBUF PORT "a" IO_TYPE=LVCMOS33;\n'
                                            'IOBUF PORT "b" IO_TYPE=LVCMOS33;\n'
                                            'IOBUF PORT "y" IO_TYPE=LVCMOS33;\n')
        parameters = self.parameters()
        parameters.update(goal="bitstream", constraint="pins.lpf")
        result = synthesize(self.context(tools={"yosys": YOSYS, "nextpnr-ecp5": NEXTPNR_ECP5,
                                                "ecppack": ECPPACK}), parameters)
        self.assertTrue(result["synthesized"])
        self.assertTrue(result["placedAndRouted"])
        self.assertTrue(result["bitstreamGenerated"])
        self.assertFalse(result["programmed"])
        self.assertFalse(result["hardwareVerified"])
        artifacts = {artifact["name"]: base64.b64decode(artifact["base64"]) for artifact in result["artifacts"]}
        self.assertEqual({"synthesized.json", "routed.config", "design.bit"}, set(artifacts))
        self.assertIn(b".device LFE5U-85F", artifacts["routed.config"])
        self.assertGreater(len(artifacts["design.bit"]), 1000)

    @unittest.skipUnless(Path(YOSYS).is_file(), "Yosys is not installed")
    def test_real_hdl_error_contains_stage_and_compiler_diagnostic(self):
        self.source.write_text("module user_top( syntax error ! endmodule")
        with self.assertRaises(JobFailure) as failure:
            synthesize(self.context(), self.parameters())
        self.assertEqual("fpga-stage-failed", failure.exception.code)
        self.assertEqual("synthesize", failure.exception.details["stage"])
        self.assertIn("syntax error", failure.exception.details["stderr"])

    @unittest.skipUnless(all(Path(path).is_file() for path in [YOSYS, GHDL]), "GHDL synthesis is not installed")
    def test_real_vhdl_translation_and_synthesis(self):
        (self.path / "design.vhd").write_text("""library ieee; use ieee.std_logic_1164.all;
entity user_top is port(a,b: in std_logic; y: out std_logic); end entity;
architecture rtl of user_top is begin y <= a and b; end architecture;
""")
        parameters = self.parameters()
        parameters.update(sources=["design.vhd"], language="vhdl")
        result = synthesize(self.context(tools={"yosys": YOSYS, "ghdl": GHDL}), parameters)
        self.assertTrue(result["synthesized"])
        self.assertFalse(result["programmed"])
        self.assertEqual(["translate-vhdl", "synthesize"], [value for stream, value in self.events if stream == "stage"])
        netlist = json.loads(base64.b64decode(result["artifacts"][0]["base64"]))
        self.assertEqual({"a", "b", "y"}, set(netlist["modules"]["user_top"]["ports"]))

    def test_invalid_parameters_never_run_the_tool(self):
        for changes, code in [({"sources": [{}]}, "invalid-fpga-sources"),
                              ({"sources": ["design.v", "design.v"]}, "invalid-fpga-sources"),
                              ({"sources": ["../design.v"]}, "invalid-name"),
                              ({"top": "user_top; shell touch pwned"}, "invalid-fpga-parameter"),
                              ({"part": "85k --run evil.py"}, "invalid-fpga-parameter"),
                              ({"frequency_mhz": True}, "invalid-fpga-parameter"),
                              ({"command": "echo injected"}, "invalid-fpga-parameter")]:
            with self.subTest(changes=changes):
                parameters = self.parameters()
                parameters.update(changes)
                with self.assertRaises(JobFailure) as failure:
                    synthesize(self.context(tools={}), parameters)
                self.assertEqual(code, failure.exception.code)
                self.assertEqual([], self.events)

    def test_sources_cannot_read_host_files(self):
        for directive in ['`include "/etc/passwd"', '$readmemh("/etc/passwd", mem);',
                          '$fopen("/tmp/out");', '$system("true");']:
            self.source.write_text("module user_top; " + directive + " endmodule")
            with self.subTest(directive=directive), self.assertRaises(JobFailure) as failure:
                synthesize(self.context(tools={}), self.parameters())
            self.assertEqual("hdl-external-file", failure.exception.code)

    def test_bitstream_goal_requires_place_route_and_pack_tools(self):
        parameters = self.parameters()
        parameters.update(goal="bitstream", constraint="pins.lpf")
        with self.assertRaises(JobFailure) as failure:
            synthesize(self.context(), parameters)
        self.assertEqual("missing-tool", failure.exception.code)
        self.assertIn("nextpnr-ecp5", failure.exception.message)
        self.assertEqual([], self.events)

    def test_cancellation_interrupts_synthesis_process(self):
        fake = self.path / "slow-yosys"
        fake.write_text(f"#!{sys.executable}\nimport time\ntime.sleep(60)\n")
        fake.chmod(0o755)
        context = self.context(tools={"yosys": str(fake)})
        timer = threading.Timer(0.05, context.cancelled.set)
        timer.start()
        self.addCleanup(timer.cancel)
        with self.assertRaises(JobFailure) as failure:
            synthesize(context, self.parameters())
        self.assertEqual("cancelled", failure.exception.code)
        self.assertFalse(context.output_path("synthesized.json").exists())

    def test_program_operation_requires_host_target_and_server_confirmation(self):
        bitstream = self.path / "design.bit"
        bitstream.write_bytes(b"TEST FIXTURE ONLY")
        targets = {"bench": FpgaProgramTarget("ulx3s", "fixture-cable-serial")}
        with self.assertRaises(JobFailure) as failure:
            program(self.context(tools={}), {"target": "bench", "bitstream": "design.bit"}, targets)
        self.assertEqual("confirmation-required", failure.exception.code)
        with self.assertRaises(JobFailure) as failure:
            program(self.context(tools={}, confirmed=True), {"target": "unconfigured", "bitstream": "design.bit"}, targets)
        self.assertEqual("unknown-fpga-target", failure.exception.code)

    def test_program_uses_exact_host_selected_cable_with_fake_programmer_only(self):
        bitstream = self.path / "design.bit"
        bitstream.write_bytes(b"TEST FIXTURE ONLY")
        fake = self.path / "fixture-programmer"
        fake.write_text(f"#!{sys.executable}\nimport json, pathlib, sys\npathlib.Path('argv.json').write_text(json.dumps(sys.argv[1:]))\n")
        fake.chmod(0o755)
        context = self.context(tools={"openFPGALoader": str(fake)}, confirmed=True)
        target = FpgaProgramTarget("ecp5_evn", "fixture-cable-serial", "openfpgaloader", "ft2232", 2, allow_flash=True)
        result = program(context, {"target": "bench", "bitstream": "design.bit", "flash": True,
                                   "profile": target.fingerprint}, {"bench": target})
        self.assertEqual(["--cable", "ft2232", "--ftdi-serial", "fixture-cable-serial",
                          "--index-chain", "2", "--write-flash", str(bitstream)],
                         json.loads((self.path / "argv.json").read_text()))
        self.assertTrue(result["programmerReportedSuccess"])
        self.assertFalse(result["hardwareVerified"])
        self.assertTrue(result["flash"])

    def test_flash_without_explicit_host_approval_cannot_launch_programmer(self):
        targets = {"bench": FpgaProgramTarget("ulx3s", "fixture-cable-serial")}
        with self.assertRaises(JobFailure) as failure:
            program(self.context(tools={}, confirmed=True), {"target": "bench", "bitstream": "design.bit", "flash": True}, targets)
        self.assertEqual("fpga-flash-not-allowed", failure.exception.code)

    def test_programming_is_not_advertised_without_host_configuration(self):
        registry = OperationRegistry()
        register(registry)
        self.assertEqual(
            {"fpga-synthesize", "fpga-vivado-build", "fpga-ise-build", "fpga-ise-cpld-build", "fpga-quartus-build"},
            set(registry.operations),
        )
        configured = OperationRegistry()
        register(configured, {"bench": FpgaProgramTarget("ulx3s", "fixture-cable-serial")})
        self.assertTrue(configured.operations["fpga-program"].requires_confirmation)


if __name__ == "__main__":
    unittest.main()
