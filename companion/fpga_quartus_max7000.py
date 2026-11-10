"""Part and JTAG checks for the approved Quartus MAX 7000S boards."""
import re
import struct

from companion.api import JobFailure


MAX7000_DEVICES = {
    "max-7000-epm7128s": ("EPM7128SLC84-7", 0x071280DD),
    "max-7000-epm7256s": ("EPM7256SQC208-7", 0x072560DD),
}
MAX7000_BOARDS = {
    "EPM2525_CONSOLE": MAX7000_DEVICES["max-7000-epm7128s"],
    "EPM2525_FULL": MAX7000_DEVICES["max-7000-epm7128s"],
    "SLO_MAX7000S": MAX7000_DEVICES["max-7000-epm7256s"],
}
_PACKET = struct.Struct("<HI")


def validate_pof(path, part):
    # POF uses little-endian tag/size packets. Tag 2 carries the NUL-terminated part.
    with path.open("rb") as file:
        data = file.read(4 * 1024 * 1024 + 1)
    if not 12 <= len(data) <= 4 * 1024 * 1024 or data[:4] != b"POF\0":
        raise JobFailure("invalid-bitstream", "The MAX 7000S POF header or size is invalid.")
    count = int.from_bytes(data[8:12], "little")
    position = 12
    part_packet = None
    payload_found = False
    trailer_found = False
    if count > (len(data) - position) // _PACKET.size:
        raise JobFailure("invalid-bitstream", "The MAX 7000S POF packet count is invalid.")
    for index in range(count):
        if position + _PACKET.size > len(data):
            break
        tag, size = _PACKET.unpack_from(data, position)
        position += _PACKET.size
        end = position + size
        if end > len(data):
            break
        if tag == 2:
            if part_packet is not None or not 2 <= size <= 64:
                raise JobFailure("invalid-bitstream", "The MAX 7000S POF part packet is invalid.")
            part_packet = data[position:end]
        elif tag == 0x11:
            payload_found = size > 12
        elif tag == 8:
            trailer_found = size == 2 and index == count - 1
        position = end
    if position != len(data) or part_packet is None or not payload_found or not trailer_found:
        raise JobFailure("invalid-bitstream", "The MAX 7000S POF is missing valid part, payload or trailer data.")
    if part_packet.upper() != part.encode("ascii") + b"\0":
        raise JobFailure("invalid-bitstream", f"The POF does not match the approved device {part}.")


def verify_chain(ctx, target, idcode):
    from companion.fpga import _execute

    command = [ctx.tool("quartus_pgm"), "-c", target.cable, "--auto"]
    result = _execute(ctx, "verify-quartus-cpld-jtag", command)
    lines = result.stdout.splitlines()
    cables = [(index, match[1].strip()) for index, line in enumerate(lines)
              if (match := re.fullmatch(r"\s*[1-9][0-9]*\)\s+(.+)", line))]
    devices = []
    if len(cables) == 1 and cables[0][1] == target.cable:
        for line in lines[cables[0][0] + 1:]:
            if re.match(r"\s*(?:Info|Warning|Error)(?:\s+\([0-9]+\))?:", line):
                break
            if not line.strip():
                continue
            match = re.fullmatch(r"\s*([0-9a-fA-F]{8})(?:\s+.+)?", line)
            if not match:
                raise JobFailure("fpga-target-changed", "Quartus did not report a readable JTAG chain.")
            # The most significant nibble is the silicon revision, not the part ID.
            devices.append(int(match[1], 16) & 0x0FFFFFFF)
    if target.chain_position >= len(devices) or devices[target.chain_position] != idcode:
        raise JobFailure("fpga-target-changed", "The approved MAX 7000S device was not found at its JTAG position.")
