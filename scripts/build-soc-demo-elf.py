#!/usr/bin/env python3
"""Rebuild the two ELF32 demos used by the desktop program oracle."""
from pathlib import Path
import struct

PROGRAMS = {
    "rv32": (243, [0x00000093, 0x00100113, 0x00600193, 0x002080B3, 0x00110113,
                    0xFE314CE3, 0x00001237, 0x00122023, 0x10102023, 0x10002283]),
    "nios2": (113, [0x00800004, 0x00C00044, 0x01000184, 0x10C5883A, 0x18C00044,
                     0x193FFD16, 0x01440004, 0x28800015, 0x00804015, 0x01804017]),
}
root = Path(__file__).resolve().parent.parent / "fixtures" / "soc"
for name, (machine, words) in PROGRAMS.items():
    data = struct.pack("<" + "I" * len(words), *words)
    ident = b"\x7fELF\x01\x01\x01" + bytes(9)
    header = ident + struct.pack("<HHIIIIIHHHHHH", 2, machine, 1, 0, 52, 0, 0, 52, 32, 1, 0, 0, 0)
    segment = struct.pack("<IIIIIIII", 1, 84, 0, 0, len(data), 0x104, 7, 4)
    (root / f"sum-{name}.elf").write_bytes(header + segment + data)
