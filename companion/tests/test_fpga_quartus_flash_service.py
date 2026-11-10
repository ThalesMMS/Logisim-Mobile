import base64
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from companion.api import JobFailure
from companion.fpga import FpgaProgramTarget
from companion.server import CompanionService, Configuration, default_registry
from companion.tests.test_fpga_quartus_flash import cyclone_iv_sof


class QuartusFlashServiceTest(unittest.TestCase):
    cable = "USB-Blaster [1-2]"
    board = "GECKO4_EDUCATION"
    part = "EP4CE15F23C8"
    loader_part = "EP4CE15"
    idcode = "020F20DD"
    other_board = "GECKO4_EDUCATION_EPFL_EDITION"
    other_part = "EP4CE30F23C8"
    other_idcode = "020F40DD"

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.install = Path(temporary.name) / "quartus"
        (self.install / "bin").mkdir(parents=True)
        self.payload = cyclone_iv_sof(self.part)
        self.loader = self.install / "common" / "devinfo" / "programmer" / f"sfl_{self.loader_part.lower()}.sof"
        self.loader.parent.mkdir(parents=True)
        self.loader.write_bytes(b"host bridge")
        self.chain = self.install / "chain.txt"
        self.chain.write_text(
            'Info (213045): Using programming cable "USB-Blaster [1-2]"\n'
            '1) USB-Blaster [1-2]\n'
            f'  {self.idcode}   {self.loader_part}\n'
        )
        self.pgm = self.tool("quartus_pgm", f"""
from pathlib import Path
import sys
assert sys.argv[1:3] == ['-c', 'USB-Blaster [1-2]'], sys.argv
if sys.argv[3:] == ['--auto']:
    print(Path({str(self.chain)!r}).read_text(), end='')
else:
    assert sys.argv[3:6] == ['-m', 'jtag', '-o'], sys.argv
    operation = sys.argv[6]
    expected = {{
        'P;outputs/{self.loader.name}@1': b'host bridge',
        'PV;outputs/flash.jic@1': b'converted JIC',
        'P;inputs/design.sof@1': {self.payload!r},
    }}
    source = operation.split(';')[1].split('@')[0]
    assert Path(source).read_bytes() == expected[operation]
    print(operation)
""")
        self.cpf = self.tool("quartus_cpf", f"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
assert sys.argv[1] == '-c'
root = ET.parse(sys.argv[2]).getroot()
assert root.findtext('eprom_name') == 'EPCQ16'
assert root.findtext('flash_loader_device') == {self.loader_part!r}
assert root.findtext('advanced_options/ignore_epcs_id_check') == '0'
assert Path(root.findtext('sof_data/bit0/sof_filename')).read_bytes() == {self.payload!r}
Path(root.findtext('output_filename')).write_bytes(b'converted JIC')
""")
        self.target = FpgaProgramTarget(self.board, transport="quartus", cable=self.cable,
                                        chain_position=0, allow_flash=True)

    def tool(self, name, code):
        path = self.install / "bin" / name
        path.write_text(f"#!{sys.executable}\n" + code)
        path.chmod(0o755)
        return str(path)

    def service(self, include_converter=True):
        tools = {"quartus_pgm": self.pgm}
        if include_converter:
            tools["quartus_cpf"] = self.cpf
        with patch("companion.server.shutil.which", return_value=None):
            service = CompanionService(Configuration("fixture-token-" * 4, {"fpga-program"}, tool_paths=tools),
                                       default_registry(program_targets={"gecko": self.target}))
        self.addCleanup(service.close)
        return service

    def request(self, flash, payload=None, name="design.sof"):
        data = self.payload if payload is None else payload
        return {
            "operation": "fpga-program",
            "parameters": {"target": "gecko", "profile": self.target.fingerprint, "bitstream": name, "flash": flash},
            "inputs": [{"name": name, "sha256": hashlib.sha256(data).hexdigest(),
                        "base64": base64.b64encode(data).decode()}],
        }

    def execute(self, service, request):
        plan = service.plan(request)
        job_id = service.submit({**request, "planToken": plan["planToken"]})["jobId"]
        service._jobs[job_id]["future"].result(timeout=5)
        return service.snapshot(job_id)

    def test_only_the_fixed_board_cable_and_chain_can_enable_flash(self):
        for board, cable, position in (
            ("DE0_CV", self.cable, 0), (self.board, "USB-Blaster", 0),
            (self.board, "1", 0), (self.board, self.cable, 1),
        ):
            with self.subTest(board=board, cable=cable, position=position), self.assertRaises(ValueError):
                FpgaProgramTarget(board, transport="quartus", cable=cable, chain_position=position, allow_flash=True)
        self.assertNotEqual(self.target.fingerprint, FpgaProgramTarget(
            self.board, transport="quartus", cable="USB-Blaster [1-3]", chain_position=0, allow_flash=True,
        ).fingerprint)

    def test_approved_sof_is_converted_then_programmed_on_the_selected_chain(self):
        service = self.service()
        capability = next(item for item in service.capabilities()["operations"] if item["name"] == "fpga-program")
        self.assertEqual(["gecko"], capability["flashProgramTargets"])
        profile = capability["programTargetProfiles"]["gecko"]
        self.assertEqual((self.board, self.cable, 0, "EPCQ16", self.target.fingerprint),
                         (profile["board"], profile["cable"], profile["chainPosition"], profile["flashDevice"], profile["fingerprint"]))
        self.assertNotIn("flashTool", profile)
        request = self.request(True)
        plan = service.plan(request)
        for unreviewed in (self.request(False), self.request(True, b"different input")):
            with self.assertRaises(JobFailure) as failure:
                service.submit({**unreviewed, "planToken": plan["planToken"]})
            self.assertEqual("confirmation-required", failure.exception.code)
        with self.assertRaises(JobFailure) as failure:
            service.plan(self.request(True, name="external.jic"))
        self.assertEqual("invalid-bitstream", failure.exception.code)
        # Reuse the same approved plan to prove rejected submissions did not consume it.
        job_id = service.submit({**request, "planToken": plan["planToken"]})["jobId"]
        service._jobs[job_id]["future"].result(timeout=5)
        complete = service.snapshot(job_id)
        self.assertEqual("succeeded", complete["state"], complete)
        self.assertEqual(
            ["convert-quartus-jic", "verify-quartus-jtag", "load-quartus-flash-bridge", "program-quartus-jic"],
            [entry["text"] for entry in complete["logs"] if entry["stream"] == "stage"],
        )
        hashes = complete["result"]["artifactHashes"]
        self.assertEqual(hashlib.sha256(self.payload).hexdigest(), hashes["sourceSof"])
        self.assertEqual(hashlib.sha256(b"converted JIC").hexdigest(), hashes["flashJic"])
        self.assertEqual(hashlib.sha256(b"host bridge").hexdigest(), hashes["bridgeSof"])
        self.assertRegex(hashes["conversionCof"], "[0-9a-f]{64}")
        self.assertFalse(complete["result"]["hardwareVerified"])

    def test_rejects_other_variant_profile_sof_and_jtag_id(self):
        service = self.service()
        request = self.request(True)
        other = FpgaProgramTarget(self.other_board, transport="quartus", cable=self.cable,
                                  chain_position=0, allow_flash=True)
        self.assertNotEqual(other.fingerprint, self.target.fingerprint)
        with self.assertRaises(JobFailure) as failure:
            service.plan({**request, "parameters": {**request["parameters"], "profile": other.fingerprint}})
        self.assertEqual("fpga-target-changed", failure.exception.code)

        wrong_sof = self.execute(service, self.request(True, cyclone_iv_sof(self.other_part)))
        self.assertEqual("invalid-bitstream", wrong_sof["error"]["code"])
        self.assertFalse(any(entry["stream"] == "stage" for entry in wrong_sof["logs"]))

        self.chain.write_text(f"1) {self.cable}\n  {self.other_idcode}\n")
        wrong_chain = self.execute(service, request)
        self.assertEqual("fpga-target-changed", wrong_chain["error"]["code"])
        self.assertEqual(["convert-quartus-jic", "verify-quartus-jtag"],
                         [entry["text"] for entry in wrong_chain["logs"] if entry["stream"] == "stage"])

    def test_without_converter_volatile_sof_stays_available(self):
        service = self.service(include_converter=False)
        capability = next(item for item in service.capabilities()["operations"] if item["name"] == "fpga-program")
        self.assertTrue(capability["available"])
        self.assertEqual([], capability["flashProgramTargets"])
        with self.assertRaises(JobFailure) as failure:
            service.plan(self.request(True))
        self.assertEqual("missing-tool", failure.exception.code)
        complete = self.execute(service, self.request(False))
        self.assertEqual("succeeded", complete["state"], complete)
        self.assertEqual(["program-quartus"],
                         [entry["text"] for entry in complete["logs"] if entry["stream"] == "stage"])


class QuartusFlashEpflServiceTest(QuartusFlashServiceTest):
    board = "GECKO4_EDUCATION_EPFL_EDITION"
    part = "EP4CE30F23C8"
    loader_part = "EP4CE30"
    idcode = "020F40DD"
    other_board = "GECKO4_EDUCATION"
    other_part = "EP4CE15F23C8"
    other_idcode = "020F20DD"
