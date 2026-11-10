#!/usr/bin/env python3
"""Deterministic memory stimuli shared by the two independent runtimes."""
import sys


def emit(kind, width, appearance, bus, mode, variant):
    attrs = dict(addrWidth="4", dataWidth=str(width), appearance=appearance)
    if kind != "ROM":
        attrs.update(enables=mode, databus=bus, trigger=variant[0], clearpin="true")
        if mode == "byte":
            attrs.update(asyncread=str(variant[1]).lower(), readbehav=variant[2])
            if width > 8 and variant[0] in ("rising", "falling"):
                attrs["byteenables"] = "byteEnables"
        else:
            attrs.update(line=variant[1], misaligned="false")
    else:
        attrs.update(line=variant[1], misaligned="false")
    identity = f"{kind}-{width}-{appearance}-{bus}-{mode}-{'-'.join(map(str, variant))}"
    print("@", identity, kind, ";".join(f"{k}={v}" for k, v in attrs.items()), sep="\t")
    lines = {"single": 1, "dual": 2, "quad": 4, "octo": 8}.get(attrs.get("line"), 1)
    for step in range(16):
        inputs = {}
        for port in range(2 if kind == "DualRAM" else 1):
            invalid_port = 1 if kind == "DualRAM" and step == 6 else 0
            inputs[f"a{port}"] = "E" if step == 5 and port == invalid_port else "U" if step == 6 and port == invalid_port else f"{((step // 4) * lines + port) % 16:x}"
            inputs[f"clk{port}"] = str(step % 2)
            inputs[f"we{port}"] = str(int(step not in (4, 7, 9)))
            inputs[f"oe{port}"] = str(int(step != 10))
            for line in range(lines):
                inputs[f"d{port}_{line}"] = "U" if step == 8 else f"{(0xabcde + step * 0x21 + line + port * 7) & ((1 << width) - 1):x}"
                inputs[f"le{port}_{line}"] = str((line + step) % 2)
            for byte in range((width + 7) // 8):
                inputs[f"be{port}_{byte}"] = str((byte + step) % 2)
        inputs["clear"] = str(int(step == 12))
        print("S", ";".join(f"{k}={v}" for k, v in inputs.items()), sep="\t")


for kind in ("ROM", "RAM", "DualRAM"):
    for width in (1, 8, 20, 64):
        for appearance in ("classic", "evolution", "logisim_evolution"):
            buses = ("bibus",) if kind == "ROM" else ("bidir", "bibus", "bibusControlled")
            for bus in buses:
                if kind != "ROM":
                    for variant in (("rising", False, "raw"), ("falling", False, "war"),
                                    ("rising", True, "raw"), ("high", False, "raw"), ("low", False, "raw")):
                        emit(kind, width, appearance, bus, "byte", variant)
                for trigger, line in (("rising", "single"), ("falling", "dual"), ("high", "quad"), ("low", "octo")):
                    emit(kind, width, appearance, bus, "line", (trigger, line))
