import base64
from pathlib import Path
import tempfile
import threading
import unittest

from companion.api import CommandResult, JobContext, JobFailure, OperationRegistry
from companion.fpga import register
from companion.fpga_ise import build, build_cpld


class Basys2IseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.work = Path(temporary.name)
        self.inputs = self.work / "inputs"
        self.inputs.mkdir()
        (self.inputs / "source.v").write_text(
            "module logisim_fpga_top(input fpga_p0_b0, output fpga_p1_b0);\n"
            "assign fpga_p1_b0 = fpga_p0_b0; endmodule\n"
        )
        self.constraint = self.inputs / "pins.ucf"
        self.constraint.write_text(
            'NET "fpga_p0_b0" LOC = "P11" | IOSTANDARD = LVCMOS33;\n'
            'NET "fpga_p1_b0" LOC = "M5" | DRIVE = 8 | PULLUP;\n'
        )
        self.parameters = {"language": "verilog", "sources": ["source.v"]}
        self.stages = []
        self.context = JobContext(self.work, {p.name: p for p in self.inputs.iterdir()},
                                  {name: name for name in ("xst", "ngdbuild", "map", "par", "bitgen")},
                                  threading.Event(), emit=lambda stream, text: self.stages.append((stream, text)))

    def test_fixed_stages_return_only_the_generated_bitstream(self):
        self.assert_generated_bitstream(self.parameters, "basys2", "XC3S100E-CP132-4", [])

    def test_spartan_3e_starter_selects_its_fixed_part(self):
        self.constraint.write_text(
            'NET "fpga_p0_b0" LOC = "N17" | IOSTANDARD = LVTTL | PULLUP;\n'
            'NET "fpga_p1_b0" LOC = "F9" | IOSTANDARD = LVTTL | DRIVE = 8;\n'
        )
        profile = "digilent-spartan-3e-starter"
        self.assert_generated_bitstream({**self.parameters, "profile": profile}, profile, "XC3S500E-FG320-5", [])

    def test_gecko4led_preserves_double_letter_pin_rows_and_unused_pin_pull(self):
        self.constraint.write_text(
            'NET "fpga_p0_b0" LOC = "K1" | PULLUP;\n'
            'NET "fpga_p1_b0" LOC = "AC25";\n'
        )
        self.assert_generated_bitstream({**self.parameters, "profile": "gecko4led"}, "gecko4led",
                                        "XC3S5000-FG676-5", ["-g", "UnusedPin:PULLDOWN"])

    def assert_generated_bitstream(self, parameters, profile, part, unused_pin_args):
        commands = []

        def run(argv):
            commands.append(argv)
            if argv[0] == "xst":
                self.assertEqual(part, (self.work / argv[2]).read_text().split()[-1])
                self.assertEqual('verilog work "inputs/source.v"\n', (self.work / "build.prj").read_text())
                (self.work / "xst.log").write_text("XST fixture diagnostic")
                (self.work / "synthesized.ngc").write_bytes(b"ngc")
            if argv[0] == "ngdbuild":
                self.assertEqual(self.constraint, Path(argv[4]))
                (self.work / argv[-1]).write_bytes(b"ngd")
            if argv[0] == "map":
                (self.work / argv[4]).write_bytes(b"ncd")
                (self.work / "mapped.pcf").write_bytes(b"pcf")
            if argv[0] == "par":
                self.assertEqual("mapped.pcf", argv[-1])
                (self.work / argv[-2]).write_bytes(b"routed")
            if argv[0] == "bitgen":
                self.assertEqual(["-w", *unused_pin_args, "-g", "StartupClk:CCLK", "routed.ncd"], argv[1:-1])
                Path(argv[-1]).write_bytes(b"FIXTURE BIT")
            return CommandResult(0, "", "")

        self.context.run = run
        result = build(self.context, parameters)
        self.assertEqual(["xst", "ngdbuild", "map", "par", "bitgen"], [argv[0] for argv in commands])
        self.assertEqual(["synthesize", "translate", "map", "place-route", "pack"],
                         [text for stream, text in self.stages if stream == "stage"])
        self.assertIn(("xst-log", "XST fixture diagnostic"), self.stages)
        self.assertTrue(result["bitstreamGenerated"])
        self.assertEqual(profile, result["profile"])
        self.assertFalse(result["programmed"])
        self.assertFalse(result["hardwareVerified"])
        [artifact] = result["artifacts"]
        self.assertEqual("design.bit", artifact["name"])
        self.assertEqual(b"FIXTURE BIT", base64.b64decode(artifact["base64"]))

    def test_rejects_non_generated_constraints_before_launch(self):
        self.constraint.write_text(self.constraint.read_text() + 'CONFIG PROHIBIT = "P11";\n')
        self.context.run = lambda _argv: self.fail("Invalid input reached the tool")
        with self.assertRaises(JobFailure) as failure:
            build(self.context, self.parameters)
        self.assertEqual("invalid-fpga-constraint", failure.exception.code)
        self.assertEqual([], self.stages)

    def test_gecko4led_rejects_caller_supplied_bitgen_options(self):
        self.context.run = lambda _argv: self.fail("Unapproved arguments reached ISE")
        with self.assertRaises(JobFailure) as failure:
            build(self.context, {**self.parameters, "profile": "gecko4led", "bitgen": ["-g", "UnusedPin:PULLUP"]})
        self.assertEqual("invalid-fpga-parameter", failure.exception.code)

    def test_reptar_constraints_select_spartan6_and_keep_basys2_profile_separate(self):
        self.constraint.write_text(
            'NET "fpga_p0_b0" LOC = "W22" | IOSTANDARD = LVCMOS18;\n'
            'NET "fpga_p1_b0" LOC = "AB30" | IOSTANDARD = LVCMOS18;\n'
            'NET "fpga_p2_b0" LOC = "AK29" | IOSTANDARD = LVCMOS18;\n'
        )
        source = self.inputs / "source.vhd"
        source.write_text(
            "library ieee; use ieee.std_logic_1164.all;\n"
            "entity logisim_fpga_top is port (fpga_p0_b0: in std_logic; "
            "fpga_p1_b0: out std_logic; fpga_p2_b0: inout std_logic); end;\n"
            "architecture rtl of logisim_fpga_top is begin fpga_p1_b0 <= fpga_p0_b0; end;\n"
        )
        self.context.input_files[source.name] = source
        request = {"language": "vhdl", "sources": ["source.vhd"]}
        self.context.run = lambda _argv: self.fail("Unapproved constraints reached ISE")
        with self.assertRaises(JobFailure) as basys2:
            build(self.context, request)
        self.assertEqual("invalid-fpga-constraint", basys2.exception.code)
        with self.assertRaises(JobFailure) as wrong_part:
            build(self.context, {**request, "profile": "xc6slx9-tqg144-2"})
        self.assertEqual("invalid-fpga-parameter", wrong_part.exception.code)

        def xst(argv):
            self.assertEqual("xst", argv[0])
            self.assertIn("-p XC6SLX150T-FGG900-3", (self.work / argv[2]).read_text())
            self.assertEqual('vhdl work "inputs/source.vhd"\n', (self.work / "build.prj").read_text())
            return CommandResult(7, "", "stop after checking the XST request")

        self.context.run = xst
        with self.assertRaises(JobFailure) as reached_ise:
            build(self.context, {**request, "profile": "reptar-spartan-6"})
        self.assertEqual("fpga-stage-failed", reached_ise.exception.code)
        self.assertEqual("synthesize", reached_ise.exception.details["stage"])

    def test_stage_error_and_cancellation_stop_the_sequence(self):
        def fail(argv):
            self.assertEqual("xst", argv[0])
            (self.work / "xst.log").write_text("vendor log")
            return CommandResult(7, "", "vendor error")

        self.context.run = fail
        with self.assertRaises(JobFailure) as failure:
            build(self.context, self.parameters)
        self.assertEqual("fpga-stage-failed", failure.exception.code)
        self.assertEqual("synthesize", failure.exception.details["stage"])
        self.assertEqual(7, failure.exception.details["exitCode"])
        self.assertEqual("vendor error", failure.exception.details["stderr"])
        self.assertIn(("xst-log", "vendor log"), self.stages)
        self.context.cancelled.set()
        self.context.run = lambda _argv: self.fail("Cancelled job reached the tool")
        with self.assertRaises(JobFailure) as cancelled:
            build(self.context, self.parameters)
        self.assertEqual("cancelled", cancelled.exception.code)

    def test_profile_requires_tools_and_plan_confirmation(self):
        registry = OperationRegistry()
        register(registry)
        operation = registry.operations["fpga-ise-build"]
        self.assertTrue(operation.requires_confirmation)
        self.assertEqual(("xst", "ngdbuild", "map", "par", "bitgen"), operation.required_tools)
        self.assertNotIn("fpga-program", registry.operations)

    def test_microlabtle_runs_cpld_fitter_and_returns_only_the_jedec(self):
        self.constraint.write_text('NET "fpga_p0_b0" LOC = "P19";\nNET "fpga_p1_b0" LOC = "P5";\n')
        tools = ("xst", "ngdbuild", "cpldfit", "hprep6")
        registry = OperationRegistry()
        register(registry)
        cpld = registry.operations["fpga-ise-cpld-build"]
        self.assertTrue(cpld.requires_confirmation)
        self.assertEqual(tools, cpld.required_tools)
        with self.assertRaises(JobFailure) as denied:
            build_cpld(self.context, self.parameters)
        self.assertEqual("confirmation-required", denied.exception.code)
        context = JobContext(self.work, self.context.input_files, {tool: tool for tool in tools},
                             threading.Event(), user_confirmed=True,
                             emit=lambda stream, text: self.stages.append((stream, text)))
        commands = []
        payload = b"\x02FIXTURE JEDEC\x03"

        def run(argv):
            commands.append(argv)
            if argv[0] == "xst":
                self.assertIn("-p XC2C64-VQ44-5", (self.work / "build.xst").read_text())
                (self.work / "synthesized.ngc").write_bytes(b"ngc")
            elif argv[0] == "ngdbuild":
                self.assertEqual(["synthesized.ngc", "logisim.ngd"], argv[-2:])
                (self.work / "logisim.ngd").write_bytes(b"ngd")
            elif argv[0] == "cpldfit":
                self.assertEqual(["-p", "XC2C64-5-VQ44", "-intstyle", "ise", "-terminate", "float", "-loc", "on",
                                  "-log", "logisim_cpldfit.log", "logisim.ngd"], argv[1:])
                (self.work / "logisim.vm6").write_bytes(b"vm6")
            elif argv[0] == "hprep6":
                self.assertEqual(["-i", "logisim.vm6"], argv[1:])
                (self.work / "logisim.jed").write_bytes(payload)
            return CommandResult(0, "", "")

        context.run = run
        result = cpld.handler(context, self.parameters)
        self.assertEqual(list(tools), [argv[0] for argv in commands])
        self.assertEqual(["synthesize", "translate", "cpld-fit", "generate-jedec"],
                         [text for stream, text in self.stages if stream == "stage"])
        [artifact] = result["artifacts"]
        self.assertEqual("design.jed", artifact["name"])
        self.assertEqual(payload, base64.b64decode(artifact["base64"]))
        self.assertFalse(result["programmed"])
        self.assertFalse(result["hardwareVerified"])
