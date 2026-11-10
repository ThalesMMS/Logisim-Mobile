"""Quartus JIC programming for the GECKO4 Education EPCQ16 profiles."""
from dataclasses import dataclass
import hashlib
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET

from companion.api import JobFailure


FLASH_DEVICE = "EPCQ16"
FLASH_TOOL = "quartus_cpf"
_MAX_PROGRAM_BYTES = 4 * 1024 * 1024
_SOF_RECORD = struct.Struct("<HI")


@dataclass(frozen=True)
class QuartusFlashProfile:
    part: str
    idcode: str

    @property
    def loader_part(self):
        return self.part.partition("F")[0]


_PROFILES = {
    "GECKO4_EDUCATION": QuartusFlashProfile("EP4CE15F23C8", "020F20DD"),
    "GECKO4_EDUCATION_EPFL_EDITION": QuartusFlashProfile("EP4CE30F23C8", "020F40DD"),
}


def flash_profile(target):
    if (
        target.allow_flash and target.transport == "quartus"
        and type(target.chain_position) is int and target.chain_position == 0
        and isinstance(target.cable, str)
        and re.fullmatch(r"USB-Blaster(?:II)? \[[A-Za-z0-9._:/-]+\]", target.cable) is not None
    ):
        return _PROFILES.get(target.board)
    return None


def _sof_part(data):
    # Cyclone IV SOF stores a record count at offset 8 and a NUL-terminated part in record type 2.
    offset = 12
    if len(data) < offset or data[:4] != b"SOF\x00":
        return None
    count = int.from_bytes(data[8:offset], "little")
    if count > (len(data) - offset) // _SOF_RECORD.size:
        return None
    part = None
    for _ in range(count):
        if offset + _SOF_RECORD.size > len(data):
            return None
        tag, size = _SOF_RECORD.unpack_from(data, offset)
        offset += _SOF_RECORD.size
        end = offset + size
        if end > len(data):
            return None
        if tag == 2:
            if part is not None or not 2 <= size <= 32:
                return None
            part = data[offset:end]
        offset = end
    return part if offset == len(data) else None


def _write_cof(cof, bitstream, jic, loader_part):
    root = ET.Element("cof")
    for name, value in (
        ("eprom_name", FLASH_DEVICE), ("flash_loader_device", loader_part),
        ("output_filename", str(jic)), ("n_pages", "1"), ("width", "1"), ("mode", "7"),
    ):
        ET.SubElement(root, name).text = value
    sof_data = ET.SubElement(root, "sof_data")
    ET.SubElement(sof_data, "user_name").text = "Page_0"
    ET.SubElement(sof_data, "page_flags").text = "1"
    ET.SubElement(ET.SubElement(sof_data, "bit0"), "sof_filename").text = str(bitstream)
    for name, value in (
        ("version", "10"), ("create_cvp_file", "0"), ("create_hps_iocsr", "0"),
        ("auto_create_rpd", "0"), ("rpd_little_endian", "1"),
    ):
        ET.SubElement(root, name).text = value
    ET.SubElement(ET.SubElement(root, "options"), "map_file").text = "0"
    advanced = ET.SubElement(root, "advanced_options")
    for name, value in (
        ("ignore_epcs_id_check", "0"), ("ignore_condone_check", "0"),
        ("plc_adjustment", "0"), ("post_chain_bitstream_pad_bytes", "-1"),
        ("post_device_bitstream_pad_bytes", "-1"), ("bitslice_pre_padding", "1"),
    ):
        ET.SubElement(advanced, name).text = value
    ET.ElementTree(root).write(cof, encoding="us-ascii", xml_declaration=True)


def program_quartus_flash(ctx, target, bitstream):
    # The shared dispatcher validates the request and binds confirmation to its input bytes.
    from companion.fpga import _execute

    if not ctx.user_confirmed:
        raise JobFailure("confirmation-required", "Review a matching Quartus flash plan before submitting.")
    profile = flash_profile(target)
    if profile is None:
        raise JobFailure("fpga-flash-not-allowed", "Flash requires the approved GECKO4 Education Quartus target.")
    if bitstream.suffix.lower() != ".sof" or not 0 < bitstream.stat().st_size <= _MAX_PROGRAM_BYTES:
        raise JobFailure("invalid-bitstream", "Provide a nonempty SOF up to 4 MiB.")
    bitstream_bytes = bitstream.read_bytes()
    if _sof_part(bitstream_bytes) != (profile.part + "\x00").encode("ascii"):
        raise JobFailure("invalid-bitstream", f"The SOF must identify {profile.part} as its only target device.")
    ctx.check_cancelled()
    pgm = ctx.tool("quartus_pgm")
    cpf = ctx.tool(FLASH_TOOL)
    loader_name = f"sfl_{profile.loader_part.lower()}.sof"
    loader = Path(pgm).parent.parent / "common" / "devinfo" / "programmer" / loader_name
    missing_loader = f"Install the Quartus {profile.loader_part} serial flash loader."
    if not loader.is_file():
        raise JobFailure("fpga-flash-loader-unavailable", missing_loader)
    try:
        with loader.open("rb") as source:
            loader_bytes = source.read(_MAX_PROGRAM_BYTES + 1)
    except OSError as error:
        raise JobFailure("fpga-flash-loader-unavailable", missing_loader) from error
    if not 0 < len(loader_bytes) <= _MAX_PROGRAM_BYTES:
        raise JobFailure("fpga-flash-loader-unavailable",
                         f"The Quartus {profile.loader_part} serial flash loader has an invalid size.")
    staged_loader = ctx.output_path(loader_name)
    staged_loader.write_bytes(loader_bytes)
    cof = ctx.output_path("flash.cof")
    jic = ctx.output_path("flash.jic")
    _write_cof(cof, bitstream, jic, profile.loader_part)
    hashes = {name: hashlib.sha256(data).hexdigest() for name, data in (
        ("sourceSof", bitstream_bytes), ("conversionCof", cof.read_bytes()), ("bridgeSof", loader_bytes),
    )}
    _execute(ctx, "convert-quartus-jic", [cpf, "-c", str(cof)])
    if jic.is_symlink() or not jic.is_file() or not 0 < jic.stat().st_size <= _MAX_PROGRAM_BYTES:
        raise JobFailure("fpga-missing-artifact", "Quartus did not produce a usable JIC.")
    hashes["flashJic"] = hashlib.sha256(jic.read_bytes()).hexdigest()
    for name, digest in hashes.items():
        ctx.emit("artifact", f"{name} SHA-256 {digest}")

    cable_command = [pgm, "-c", target.cable]
    chain = _execute(ctx, "verify-quartus-jtag", [*cable_command, "--auto"])
    lines = chain.stdout.splitlines()
    cables = [(index, match[1].strip()) for index, line in enumerate(lines)
              if (match := re.fullmatch(r"\s*[1-9][0-9]*\)\s+(.+)", line))]
    devices = []
    if len(cables) == 1 and cables[0][1] == target.cable:
        for line in lines[cables[0][0] + 1:]:
            if re.match(r"\s*(?:Info|Warning|Error)(?:\s+\([0-9]+\))?:", line):
                break
            if line.strip():
                devices.append(line.strip())
    if len(devices) != 1 or re.fullmatch(rf"{profile.idcode}(?:\s+.+)?", devices[0], re.IGNORECASE) is None:
        raise JobFailure("fpga-target-changed", "The selected USB-Blaster must report one matching FPGA at position one.")

    program = [*cable_command, "-m", "jtag", "-o"]
    loader_path = staged_loader.relative_to(ctx.working_directory).as_posix()
    jic_path = jic.relative_to(ctx.working_directory).as_posix()
    _execute(ctx, "load-quartus-flash-bridge", [*program, f"P;{loader_path}@1"])
    _execute(ctx, "program-quartus-jic", [*program, f"PV;{jic_path}@1"])
    return hashes
