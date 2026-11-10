import hashlib
from pathlib import Path
import struct
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock
import xml.etree.ElementTree as ET

from companion.api import CommandResult, JobContext, JobFailure
from companion.fpga_quartus_flash import program_quartus_flash


def cyclone_iv_sof(part):
    records = ((1, b"Quartus fixture\x00"), (2, part.encode("ascii") + b"\x00"), (17, b"fixture data"))
    return b"SOF\x00\x00\x00\x00\x00" + struct.pack("<I", len(records)) + b"".join(
        struct.pack("<HI", tag, len(data)) + data for tag, data in records
    )


class QuartusFlashTest(unittest.TestCase):
    cable = "USB-Blaster [1-2]"
    chain = (
        'Info (213045): Using programming cable "USB-Blaster [1-2]"\n'
        "1) USB-Blaster [1-2]\n"
        "  020F20DD   EP3C16/EP4CE15\n"
        "Info: Quartus Prime Programmer was successful\n"
    )

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name).resolve()
        self.pgm = str(root / "quartus" / "bin" / "quartus_pgm")
        self.cpf = str(root / "quartus" / "bin" / "quartus_cpf")
        self.loader = root / "quartus" / "common" / "devinfo" / "programmer" / "sfl_ep4ce15.sof"
        self.loader.parent.mkdir(parents=True)
        self.loader.write_bytes(b"host serial flash loader")
        self.bitstream = root / "job" / "inputs" / "reviewed.sof"
        self.bitstream.parent.mkdir(parents=True)
        self.bitstream.write_bytes(cyclone_iv_sof("EP4CE15F23C8"))
        self.logs = []
        self.ctx = JobContext(
            root / "job", {self.bitstream.name: self.bitstream},
            {"quartus_pgm": self.pgm, "quartus_cpf": self.cpf}, threading.Event(),
            user_confirmed=True, emit=lambda stream, text: self.logs.append((stream, text)),
        )
        self.ctx.run = Mock(side_effect=self.run_tool)
        self.target = SimpleNamespace(
            board="GECKO4_EDUCATION", transport="quartus", cable=self.cable, chain_position=0, allow_flash=True,
        )
        self.cof = self.ctx.output_path("flash.cof")
        self.jic = self.ctx.output_path("flash.jic")

    def run_tool(self, argv):
        if argv == [self.cpf, "-c", str(self.cof)]:
            self.jic.write_bytes(b"converted JIC")
        if argv == [self.pgm, "-c", self.cable, "--auto"]:
            return CommandResult(0, self.chain, "")
        return CommandResult(0, "", "")

    def test_converts_reviewed_sof_then_programs_and_verifies_approved_flash(self):
        hashes = program_quartus_flash(self.ctx, self.target, self.bitstream)
        self.assertEqual(
            ["convert-quartus-jic", "verify-quartus-jtag", "load-quartus-flash-bridge", "program-quartus-jic"],
            [text for stream, text in self.logs if stream == "stage"],
        )
        self.assertEqual(
            [[self.cpf, "-c", str(self.cof)], [self.pgm, "-c", self.cable, "--auto"],
             [self.pgm, "-c", self.cable, "-m", "jtag", "-o", "P;outputs/sfl_ep4ce15.sof@1"],
             [self.pgm, "-c", self.cable, "-m", "jtag", "-o", "PV;outputs/flash.jic@1"]],
            [call.args[0] for call in self.ctx.run.call_args_list],
        )
        root = ET.parse(self.cof).getroot()
        self.assertEqual("EPCQ16", root.findtext("eprom_name"))
        self.assertEqual("EP4CE15", root.findtext("flash_loader_device"))
        self.assertEqual("7", root.findtext("mode"))
        self.assertEqual(str(self.bitstream), root.findtext("sof_data/bit0/sof_filename"))
        self.assertEqual(str(self.jic), root.findtext("output_filename"))
        self.assertEqual("0", root.findtext("advanced_options/ignore_epcs_id_check"))
        self.assertEqual(self.loader.read_bytes(), self.ctx.output_path("sfl_ep4ce15.sof").read_bytes())
        for name, path in (
            ("sourceSof", self.bitstream), ("conversionCof", self.cof), ("bridgeSof", self.loader), ("flashJic", self.jic),
        ):
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(digest, hashes[name])
            self.assertIn(("artifact", f"{name} SHA-256 {digest}"), self.logs)

    def test_unapproved_target_and_missing_bridge_never_run_a_tool(self):
        self.ctx.user_confirmed = False
        with self.assertRaises(JobFailure) as failure:
            program_quartus_flash(self.ctx, self.target, self.bitstream)
        self.assertEqual("confirmation-required", failure.exception.code)
        self.ctx.user_confirmed = True
        for overrides in (
            {"allow_flash": False}, {"board": "DE0_CV"}, {"cable": "1"}, {"chain_position": 1},
        ):
            target = SimpleNamespace(**(vars(self.target) | overrides))
            with self.subTest(overrides=overrides), self.assertRaises(JobFailure) as failure:
                program_quartus_flash(self.ctx, target, self.bitstream)
            self.assertEqual("fpga-flash-not-allowed", failure.exception.code)
        self.loader.unlink()
        with self.assertRaises(JobFailure) as failure:
            program_quartus_flash(self.ctx, self.target, self.bitstream)
        self.assertEqual("fpga-flash-loader-unavailable", failure.exception.code)
        self.ctx.run.assert_not_called()

    def test_wrong_or_ambiguous_chain_stops_before_loading_the_bridge(self):
        for chain in (
            "1) USB-Blaster [1-3]\n  020F20DD EP4CE15\n",
            "1) USB-Blaster [1-2]\n  020F70DD different-device\n",
            "1) USB-Blaster [1-2]\n  020F20DD EP4CE15\n  020F20DD EP4CE15\n",
            "1) USB-Blaster [1-2]\n  020F20DD EP4CE15\n  UNKNOWN\n",
            "1) USB-Blaster [1-2]\n  020F20DD EP4CE15\n2) USB-Blaster [1-3]\n",
        ):
            with self.subTest(chain=chain):
                self.chain = chain
                self.ctx.run.reset_mock()
                with self.assertRaises(JobFailure) as failure:
                    program_quartus_flash(self.ctx, self.target, self.bitstream)
                self.assertEqual("fpga-target-changed", failure.exception.code)
                self.assertEqual(
                    [[self.cpf, "-c", str(self.cof)], [self.pgm, "-c", self.cable, "--auto"]],
                    [call.args[0] for call in self.ctx.run.call_args_list],
                )

    def test_converter_missing_output_stops_before_opening_jtag(self):
        self.ctx.run.side_effect = None
        self.ctx.run.return_value = CommandResult(0, "", "")
        with self.assertRaises(JobFailure) as failure:
            program_quartus_flash(self.ctx, self.target, self.bitstream)
        self.assertEqual("fpga-missing-artifact", failure.exception.code)
        self.ctx.run.assert_called_once_with([self.cpf, "-c", str(self.cof)])

    def test_wrong_or_malformed_sof_stops_before_invoking_tools(self):
        valid = self.bitstream.read_bytes()
        conflicting_part = b"EP4CE30F23C8\x00"
        duplicate = valid[:8] + struct.pack("<I", 4) + valid[12:] + struct.pack("<HI", 2, len(conflicting_part))
        for contents in (
            cyclone_iv_sof("EP4CE30F23C8"), valid[:-1], valid + b"trailing bytes",
            duplicate + conflicting_part, b"EP4CE15F23C8\x00",
        ):
            self.bitstream.write_bytes(contents)
            with self.subTest(contents=contents), self.assertRaises(JobFailure) as failure:
                program_quartus_flash(self.ctx, self.target, self.bitstream)
            self.assertEqual("invalid-bitstream", failure.exception.code)
        self.ctx.run.assert_not_called()

    def test_programmer_verify_failure_is_reported(self):
        def run_tool(argv):
            if argv[-1] == "PV;outputs/flash.jic@1":
                return CommandResult(1, "", "JIC verification failed")
            return self.run_tool(argv)

        self.ctx.run.side_effect = run_tool
        with self.assertRaises(JobFailure) as failure:
            program_quartus_flash(self.ctx, self.target, self.bitstream)
        self.assertEqual("fpga-stage-failed", failure.exception.code)
        self.assertEqual("program-quartus-jic", failure.exception.details["stage"])
        self.assertEqual("JIC verification failed", failure.exception.details["stderr"])
