"""Host-only board/cable configuration. Never load this from a mobile job input."""
import json
from pathlib import Path
import re

from companion.fpga import FpgaProgramTarget


def _unique_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError(f"Duplicate FPGA configuration field: {name}")
        result[name] = value
    return result


def load_program_targets(path) -> dict[str, FpgaProgramTarget]:
    """Read an explicitly selected local config; it contains identities, not commands."""
    with Path(path).open("rb") as source:
        data = source.read(65_537)
    if len(data) > 65_536:
        raise ValueError("The FPGA target configuration exceeds 64 KiB.")
    try:
        document = json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=_unique_object)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise ValueError("The FPGA target configuration must be a UTF-8 JSON object.") from error
    if not isinstance(document, dict) or set(document) != {"version", "targets"} or type(document["version"]) is not int or document["version"] != 1:
        raise ValueError("Use FPGA target configuration version 1 with only version and targets fields.")
    values = document["targets"]
    if not isinstance(values, dict) or len(values) > 32:
        raise ValueError("Configure at most 32 named physical FPGA targets.")
    targets = {}
    identities = set()
    for name, target in values.items():
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", name):
            raise ValueError("FPGA target names must be plain identifiers.")
        fields = {"board", "serial", "transport", "cable", "chainPosition", "device", "allowFlash", "flashChainPosition", "sofPart"}
        if not isinstance(target, dict) or "board" not in target or set(target) - fields or None in target.values():
            raise ValueError(f"Invalid FPGA programming target {name}.")
        physical = FpgaProgramTarget(target["board"], target.get("serial"), target.get("transport"),
                                     target.get("cable"), target.get("chainPosition"), target.get("device"),
                                     allow_flash=target.get("allowFlash", False),
                                     flash_chain_position=target.get("flashChainPosition"), sof_part=target.get("sofPart"))
        identity = (physical.tool, physical.cable, physical.serial, physical.chain_position)
        if identity in identities:
            raise ValueError("Each configured FPGA target must identify a distinct board and cable.")
        identities.add(identity)
        targets[name] = physical
    return targets
