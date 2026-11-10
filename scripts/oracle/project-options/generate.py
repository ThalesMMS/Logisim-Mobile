#!/usr/bin/env python3
"""Write the project-option fixtures compared with Logisim Evolution (#331).

Each fixture is an Evolution 5.1.0dev project. Variants differ only in the
<options> block, so a difference between variants comes from the option.
"""
import sys
from pathlib import Path

HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
"""


def options(values):
    if values is None:
        return ""
    body = "".join(f'    <a name="{k}" val="{v}"/>\n' for k, v in values.items())
    return f"  <options>\n{body}  </options>\n"


def pin_in(x, y, label):
    return f'    <comp lib="0" loc="({x},{y})" name="Pin">\n      <a name="label" val="{label}"/>\n    </comp>\n'


def pin_out(x, y, label):
    return (f'    <comp lib="0" loc="({x},{y})" name="Pin">\n      <a name="facing" val="west"/>\n'
            f'      <a name="output" val="true"/>\n      <a name="label" val="{label}"/>\n    </comp>\n')


def comp(lib, x, y, name, **attrs):
    body = "".join(f'      <a name="{k}" val="{v}"/>\n' for k, v in attrs.items())
    return f'    <comp lib="{lib}" loc="({x},{y})" name="{name}">\n{body}    </comp>\n'


def wire(a, b):
    return f'    <wire from="({a[0]},{a[1]})" to="({b[0]},{b[1]})"/>\n'


def project(circuit, opts):
    return HEADER + options(opts) + '  <circuit name="main">\n' + circuit + "  </circuit>\n</project>\n"


def gate_undefined():
    """Gates with unconnected ports, floating wire stubs, and fully driven baselines."""
    parts = []
    y = 100

    def row():
        nonlocal y
        current = y
        y += 100
        return current

    def two_input(gate, first, second, out):
        r = row()
        parts.append(comp(1, 300, r, gate, size="50", inputs="2"))
        for offset, kind in ((-20, first), (20, second)):
            port = (250, r + offset)
            if kind == "open":
                continue
            if kind == "stub":
                parts.append(wire((230, r + offset), port))
            else:
                parts.append(pin_in(200, r + offset, kind))
                parts.append(wire((200, r + offset), port))
        parts.append(pin_out(400, r, out))
        parts.append(wire((300, r), (400, r)))

    two_input("AND Gate", "a0", "open", "y0_and_open")
    two_input("OR Gate", "a1", "stub", "y1_or_stub")
    two_input("AND Gate", "a2", "b2", "y2_and_driven")
    two_input("XOR Gate", "a3", "stub", "y3_xor_stub")
    two_input("NAND Gate", "open", "open", "y4_nand_none")
    r = row()  # NOT on a floating stub
    parts.append(comp(1, 300, r, "NOT Gate"))
    parts.append(wire((250, r), (270, r)))
    parts.append(pin_out(400, r, "y5_not_stub"))
    parts.append(wire((300, r), (400, r)))
    r = row()  # NOT driven
    parts.append(comp(1, 300, r, "NOT Gate"))
    parts.append(pin_in(200, r, "a6"))
    parts.append(wire((200, r), (270, r)))
    parts.append(pin_out(400, r, "y6_not_driven"))
    parts.append(wire((300, r), (400, r)))
    r = row()  # Buffer on a floating stub
    parts.append(comp(1, 300, r, "Buffer"))
    parts.append(wire((250, r), (280, r)))
    parts.append(pin_out(400, r, "y7_buffer_stub"))
    parts.append(wire((300, r), (400, r)))
    r = row()  # Controlled buffer, control open
    parts.append(comp(1, 300, r, "Controlled Buffer"))
    parts.append(pin_in(200, r, "a8"))
    parts.append(wire((200, r), (280, r)))
    parts.append(pin_out(400, r, "y8_cbuf_open"))
    parts.append(wire((300, r), (400, r)))
    r = row()  # Controlled buffer, control driven (right-handed control sits below the body when facing east)
    parts.append(comp(1, 300, r, "Controlled Buffer"))
    parts.append(pin_in(200, r, "a9"))
    parts.append(wire((200, r), (280, r)))
    parts.append(pin_in(200, r + 40, "c9"))
    parts.append(wire((200, r + 40), (290, r + 40)))
    parts.append(wire((290, r + 40), (290, r + 10)))
    parts.append(pin_out(400, r, "y9_cbuf_driven"))
    parts.append(wire((300, r), (400, r)))
    return "".join(parts)


def buffer_chain(length):
    """One input through `length` buffers, 30 units apart, joined by 10-unit wires."""
    parts = [pin_in(100, 100, "a"), wire((100, 100), (130, 100))]
    xs = [150 + 30 * index for index in range(length)]
    for index, x in enumerate(xs):
        parts.append(comp(1, x, 100, "Buffer"))
        if index + 1 < length:
            parts.append(wire((x, 100), (x + 10, 100)))
    parts += [wire((xs[-1], 100), (xs[-1] + 60, 100)), pin_out(xs[-1] + 60, 100, "y")]
    return "".join(parts)


def ring_with_halt():
    """A clock into a NOR gate whose output feeds its other input, plus an undriven `halt` output.

    The first high clock defines the loop (NOR(1, U) = 0); when the clock falls, the loop toggles on
    every step, so the temporal run stops on the propagation limit.
    """
    return (comp(0, 200, 80, "Clock") + wire((200, 80), (250, 80))
            + comp(1, 300, 100, "NOR Gate", size="50", inputs="2")
            + wire((300, 100), (320, 100)) + wire((320, 100), (320, 160)) + wire((320, 160), (230, 160))
            + wire((230, 160), (230, 120)) + wire((230, 120), (250, 120))
            + wire((320, 100), (400, 100)) + pin_out(400, 100, "y")
            + pin_out(400, 300, "halt") + wire((380, 300), (400, 300)))


def order_independent():
    """Two-level network whose settled value does not depend on event order."""
    parts = [pin_in(100, 80, "a"), pin_in(100, 120, "b"), pin_in(100, 320, "c")]
    parts.append(comp(1, 300, 100, "XOR Gate", size="50", inputs="2"))
    parts += [wire((100, 80), (250, 80)), wire((100, 120), (250, 120))]
    parts.append(comp(1, 300, 300, "AND Gate", size="50", inputs="2"))
    parts += [wire((300, 100), (320, 100)), wire((320, 100), (320, 200)), wire((320, 200), (230, 200)),
              wire((230, 200), (230, 280)), wire((230, 280), (250, 280)), wire((100, 320), (250, 320))]
    parts.append(comp(1, 500, 300, "OR Gate", size="50", inputs="2"))
    parts += [wire((300, 300), (450, 300)), wire((450, 300), (450, 280)), wire((320, 100), (420, 100)),
              wire((420, 100), (420, 320)), wire((420, 320), (450, 320))]
    parts += [pin_out(600, 300, "y"), wire((500, 300), (600, 300)), pin_out(600, 100, "x"), wire((420, 100), (600, 100))]
    return "".join(parts)


def race_latch(buffered=True):
    """Equal-delay race: clock -> NOT and clock -> Buffer meet at an AND gate.

    With deterministic delays both paths arrive together and the AND output never pulses.
    A one-step skew (Evolution's random delay noise) lets a pulse set a NOR latch whose
    output is the `halt` pin, so the halt tick depends on event order.
    """
    w = [((100, 100), (150, 100)), ((150, 100), (150, 60)), ((150, 60), (200, 60)),
         ((150, 100), (150, 140)), ((150, 140), (210, 140)),
         ((230, 60), (260, 60)), ((260, 60), (260, 80)), ((260, 80), (280, 80)),
         ((230, 140), (260, 140)), ((260, 140), (260, 120)), ((260, 120), (280, 120)),
         ((330, 100), (350, 100)), ((350, 100), (350, 80)), ((350, 80), (380, 80)),
         ((430, 100), (450, 100)), ((450, 100), (450, 150)), ((450, 150), (360, 150)),
         ((360, 150), (360, 220)), ((360, 220), (380, 220)),
         ((430, 200), (470, 200)), ((470, 200), (470, 250)), ((470, 250), (340, 250)),
         ((340, 250), (340, 120)), ((340, 120), (380, 120)), ((470, 200), (520, 200))]
    # Without the buffer the direct path is one gate delay shorter: a deterministic one-step pulse.
    middle = comp(1, 230, 140, "Buffer") if buffered else wire((210, 140), (230, 140))
    return (comp(0, 100, 100, "Clock") + comp(1, 230, 60, "NOT Gate") + middle
            + comp(1, 330, 100, "AND Gate", size="50", inputs="2")
            + comp(1, 430, 100, "NOR Gate", size="50", inputs="2")
            + comp(1, 430, 200, "NOR Gate", size="50", inputs="2")
            + "".join(wire(a, b) for a, b in w) + pin_out(520, 200, "halt"))


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    fixtures = {}
    for policy in ("ignore", "error"):
        fixtures[f"gate-undefined-{policy}"] = project(gate_undefined(), {"gateUndefined": policy, "simlimit": "1000", "simrand": "0"})
    fixtures["gate-undefined-default"] = project(gate_undefined(), None)
    chain = 12
    for limit in (2, 11, 12, 13, 14, 15, 1000):
        fixtures[f"simlimit-chain{chain}-{limit}"] = project(buffer_chain(chain), {"gateUndefined": "ignore", "simlimit": str(limit), "simrand": "0"})
    fixtures[f"simlimit-chain{chain}-default"] = project(buffer_chain(chain), None)
    for limit in (3, 4, 5, 6, 1000):
        fixtures[f"simlimit-ring-halt-{limit}"] = project(ring_with_halt(), {"simlimit": str(limit)})
    for rand in (0, 32):
        fixtures[f"simrand-network-{rand}"] = project(order_independent(), {"simlimit": "1000", "simrand": str(rand)})
        fixtures[f"simrand-chain{chain}-{rand}"] = project(buffer_chain(chain), {"simlimit": "1000", "simrand": str(rand)})
    race = {}
    for rand in (0, 32):
        race[f"simrand-race-{rand}"] = project(race_latch(), {"simlimit": "1000", "simrand": str(rand)})
    fixtures["pulse-latch-halt-0"] = project(race_latch(buffered=False), {"simlimit": "1000", "simrand": "0"})
    (out / "race").mkdir(exist_ok=True)
    for name, text in race.items():
        (out / "race" / f"{name}.circ").write_text(text)
    for name, text in fixtures.items():
        (out / f"{name}.circ").write_text(text)
    print("\n".join(sorted(fixtures)))


if __name__ == "__main__":
    main()
