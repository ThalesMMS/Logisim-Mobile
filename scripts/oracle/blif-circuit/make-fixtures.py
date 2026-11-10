#!/usr/bin/env python3
"""Writes the BLIF circuit corpus: one .circ and one .ops.tsv per case, plus index.txt.

Each circuit holds one BLIFCircuit with inline content and one Pin per port, placed on
Evolution's port grid (inputs at x=0, outputs at x=140, from y=40 in steps of 10).
Inputs change with a fixed seed and include unknown (x) and error (E) bits.
Self-oscillating sources (for example `.subckt NOT A=y Y=y`) are left out: Evolution's oracle stops
at its step limit while the Mobile trace rejects oscillation, so the two tools cannot compare them.
"""
from pathlib import Path
import random
import re
import sys
from xml.sax.saxutils import escape, quoteattr

CASES = {
    "gates": """.model gates
.inputs a b
.outputs and or xor nand nor nxor andnot ornot
.subckt AND A=a B=b Y=and
.subckt OR A=a B=b Y=or
.subckt XOR A=a B=b Y=xor
.subckt NAND A=a B=b Y=nand
.subckt NOR A=a B=b Y=nor
.subckt NXOR A=a B=b Y=nxor
.gate ANDNOT A=a B=b Y=andnot
.gate ORNOT A=a B=b Y=ornot
.end""",
    "buffers": """.model buffers
.inputs a
.outputs buf not conn one zero undef
.names $false
.names $true
1
.names $undef
.subckt BUF A=a Y=buf
.subckt NOT A=a Y=not
.conn a conn
.subckt BUF A=$true Y=one
.subckt BUF A=$false Y=zero
.subckt BUF A=$undef Y=undef
.end""",
    "mux": """.model mux
.inputs a b s
.outputs y
.subckt MUX A=a B=b S=s Y=y
.end""",
    "tristate": """.model desktop_bus2
.inputs a[0] a[1] b[0] b[1] en_a[0] en_a[1] en_b[0] en_b[1]
.outputs y[0] y[1]
.names $undef
.subckt TRIS A=a[0] B=en_a[0] Y=left0
.subckt TRISI A=b[0] B=en_b[0] Y=right0
.gate BUS A=left0 B=right0 Y=y[0]
.subckt TRIS A=a[1] B=en_a[1] Y=left1
.subckt TRISI A=b[1] B=en_b[1] Y=right1
.subckt BUS A=left1 B=$undef Y=left1_resolved
.gate BUS A=left1_resolved B=right1 Y=y[1]
.end""",
    "multidriver": """.model multidriver
.inputs a b c en
.outputs y z
.subckt TRIS A=a B=en Y=y
.subckt TRISI A=b B=en Y=y
.subckt AND A=a B=c Y=z
.subckt OR A=b B=c Y=z
.subckt BUF A=c Y=z
.end""",
    "pulls": """.model pulls
.inputs a en
.outputs up down
.subckt TRIS A=a B=en Y=up
.subckt TRIS A=a B=en Y=down
.subckt PULLUP Y=up
.subckt PULLDOWN Y=down
.end""",
    "counter": """.model counter
.inputs clk
.outputs q[0] q[1]
.subckt NOT A=q[0] Y=d0
.subckt XOR A=q[0] B=q[1] Y=d1
.subckt DFF C=clk D=d0 Q=q[0]
.subckt DFF C=clk D=d1 Q=q[1]
.end""",
    "latch": """.model latch
.inputs d e
.outputs q
.subckt DLATCH D=d E=e Q=q
.end""",
    "dffsr": """.model dffsr
.inputs c d s r
.outputs q
.subckt DFFSR C=c D=d Q=q S=s R=r
.end""",
    "ports": """.model ports
.inputs z bus[3] a bus[0] w[0] both
.outputs r[1] r[0] q[0] both
.subckt AND A=z B=bus[3] Y=r[1]
.subckt XOR A=a B=bus[0] Y=r[0]
.subckt NOT A=w[0] Y=q[0]
.subckt NOT A=z Y=both
.end""",
    "srlatch": """.model srlatch
.inputs s r
.outputs q qb
.subckt NAND A=s B=qb Y=q
.subckt NAND A=r B=q Y=qb
.end""",
    "wide": """.model wide
.inputs a[0] a[1] a[2] a[3] a[4] a[5] a[6] a[7] b[0] b[7]
.outputs y[0] y[1] y[2] y[3] y[4] y[5] y[6] y[7]
.subckt AND A=a[0] B=b[0] Y=y[0]
.subckt OR A=a[1] B=b[7] Y=y[1]
.subckt XOR A=a[2] B=a[3] Y=y[2]
.subckt BUF A=a[4] Y=y[3]
.subckt NOT A=a[5] Y=y[4]
.subckt BUF A=b[1] Y=y[5]
.subckt BUF A=a[6] Y=y[6]
.end""",
    "template": None,
    # Evolution rejects truth-table .names and keeps its template; Mobile simulates the template as well.
    "invalid": """.model invalid
.inputs a b
.outputs y
.names a b y
11 1
.end""",
}


def ports(source):
    """Evolution's BlifParser.parseNameSet for the subset used above."""
    sets = {".inputs": set(), ".outputs": set()}
    for line in source.split("\n"):
        words = line.strip().split(" ")
        if words[0] in sets:
            sets[words[0]].update(words[1:])
    sets[".inputs"] -= sets[".outputs"]
    result = {}
    for key, names in sets.items():
        scalars, buses = [], {}
        for name in sorted(names):
            match = re.fullmatch(r"(.*)\[([0-9]+)\]", name)
            if match:
                buses[match[1]] = max(buses.get(match[1], 0), int(match[2]))
            else:
                scalars.append((name, 1))
        result[key] = scalars + [(f"{p}[0]", 1) if last == 0 else (p, last + 1) for p, last in sorted(buses.items())]
    return result[".inputs"], result[".outputs"]


TEMPLATE_PORTS = ([("button", 1), ("reset", 1)], [("light_out", 1)])


def pin(ident, x, y, width, output):
    return (f'    <comp loc="({x},{y})" logisim-mobile-id="{ident}" name="Pin" lib="1">\n'
            f'      <a name="facing" val="{"west" if output else "east"}"/>\n'
            f'      <a name="type" val="{"output" if output else "input"}"/>\n'
            f'      <a name="width" val="{width}"/>\n'
            f'      <a name="appearance" val="classic"/>\n'
            f'      <a name="label" val="{ident}"/>\n'
            f'    </comp>\n')


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: make-fixtures.py output-directory")
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for case, source in CASES.items():
        inputs, outputs = TEMPLATE_PORTS if source is None or ".names a b y" in source else ports(source)
        content = "" if source is None else f'      <a name="content">{escape(source)}</a>\n'
        comps = [f'    <comp loc="(400,400)" logisim-mobile-id="c" name="BLIFCircuit" lib="0">\n{content}'
                 f'      <a name="label" val="blif"/>\n    </comp>\n']
        comps += [pin(f"in{i}", 400, 440 + 10 * i, w, False) for i, (_, w) in enumerate(inputs)]
        comps += [pin(f"out{i}", 540, 440 + 10 * i, w, True) for i, (_, w) in enumerate(outputs)]
        (out / f"blif_circuit__{case}.circ").write_text(
            '<?xml version="1.0" encoding="UTF-8"?>\n<project source="3.8.0" version="1.0">\n'
            '  <lib desc="#HDL-IP" name="0"/>\n  <lib desc="#Wiring" name="1"/>\n  <main name="main"/>\n'
            '  <circuit name="main" id="main">\n' + "".join(comps) + "  </circuit>\n</project>\n")
        rng = random.Random(case)
        ops = [f"P\tout{i}\t540\t{440 + 10 * i}\t{w}" for i, (_, w) in enumerate(outputs)]
        ops += [f"I\tin{i}\t{'0' * w}" for i, (_, w) in enumerate(inputs)] + ["Q"]
        for step in range(24):
            if step % 8 == 0:
                ops.append(f"A\tact{step // 8}")
            i = rng.randrange(len(inputs))
            w = inputs[i][1]
            # Mostly defined values, so sequential cases see clean edges; unknown and error bits as well.
            bits = "".join(rng.choice("0011xE" if step % 3 == 2 else "01") for _ in range(w))
            ops += [f"I\tin{i}\t{bits}", "D"]
        (out / f"blif_circuit__{case}.ops.tsv").write_text("\n".join(ops) + "\n")
    (out / "index.txt").write_text("".join(f"blif_circuit__{case}\n" for case in CASES))


if __name__ == "__main__":
    main()
