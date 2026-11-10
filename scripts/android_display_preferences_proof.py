#!/usr/bin/env python3
"""Capture on-screen evidence for #310, #311, #312 and #316 in the isolated .e2e Android app.

Requires the e2e app and its androidTest APK installed on a reserved emulator (see docs/android-e2e.md) with an
English system locale. Each step stores a screenshot and the UI hierarchy; results.json records the observations.

  python3 scripts/android_display_preferences_proof.py --serial emulator-5558 --output outputs/display-proof
"""
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "thalesmms.logisim.mobile.e2e"
EVOLUTION_SAVED = (ROOT / "fixtures/pla/evolution-saved.txt").read_bytes()
PLA_PROJECT = """<?xml version="1.0" encoding="UTF-8"?>
<project source="3.8.0" version="1.0"><lib desc="#Gates" name="0"/><main name="PLA proof"/>
<circuit name="PLA proof"><comp lib="0" loc="(200,150)" name="PLA"><a name="in_width" val="4"/><a name="out_width" val="3"/><a name="label" val="decoder"/><a name="table" val="1x0x 101 # first match wins&#10;1xxx 010&#10;0011 111 # spaced comment&#10;x1x0 001&#10;"/></comp></circuit></project>
"""
# #311: two incompatible-width nets (4/1, and 2/3/2 whose common width 2 highlights the 3-bit end), a 1-bit net with a junction to poke and a floating probe.
WIDTH_PROJECT = """<?xml version="1.0" encoding="UTF-8"?>
<project source="3.8.0" version="1.0"><lib desc="#Wiring" name="0"/><main name="Width proof"/>
<circuit name="Width proof">
<wire from="(100,100)" to="(200,100)"/><wire from="(100,180)" to="(140,180)"/><wire from="(140,180)" to="(200,180)"/>
<wire from="(100,260)" to="(160,260)"/><wire from="(160,260)" to="(220,260)"/><wire from="(160,260)" to="(160,320)"/>
<wire from="(260,340)" to="(300,340)"/>
<comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="w4"><a name="width" val="4"/></comp>
<comp lib="0" loc="(200,100)" name="Pin" logisim-mobile-id="w1"><a name="type" val="output"/><a name="facing" val="west"/></comp>
<comp lib="0" loc="(100,180)" name="Pin" logisim-mobile-id="w2"><a name="width" val="2"/></comp>
<comp lib="0" loc="(200,180)" name="Pin" logisim-mobile-id="w3"><a name="type" val="output"/><a name="facing" val="west"/><a name="width" val="3"/></comp>
<wire from="(140,180)" to="(140,220)"/><wire from="(140,220)" to="(200,220)"/>
<comp lib="0" loc="(200,220)" name="Pin" logisim-mobile-id="w2b"><a name="type" val="output"/><a name="facing" val="west"/><a name="width" val="2"/></comp>
<comp lib="0" loc="(100,260)" name="Pin" logisim-mobile-id="p"><a name="label" val="P"/></comp>
<comp lib="0" loc="(220,260)" name="Pin" logisim-mobile-id="q"><a name="type" val="output"/><a name="facing" val="west"/><a name="label" val="Q"/></comp>
<comp lib="0" loc="(160,320)" name="Probe" logisim-mobile-id="probe-driven"/>
<comp lib="0" loc="(300,340)" name="Probe" logisim-mobile-id="probe-floating"><a name="label" val="F"/></comp>
</circuit></project>
"""
# #311: Y = A·B + C, so the minimized K-map has two groups.
LOGIC_PROJECT = """<?xml version="1.0" encoding="UTF-8"?>
<project source="3.8.0" version="1.0"><lib desc="#Wiring" name="0"/><lib desc="#Gates" name="1"/><main name="Logic proof"/>
<circuit name="Logic proof">
<wire from="(100,100)" to="(140,100)"/><wire from="(100,140)" to="(140,140)"/><wire from="(190,120)" to="(240,120)"/>
<wire from="(100,200)" to="(220,200)"/><wire from="(220,200)" to="(220,160)"/><wire from="(220,160)" to="(240,160)"/>
<wire from="(290,140)" to="(340,140)"/>
<comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="a"><a name="label" val="A"/></comp>
<comp lib="0" loc="(100,140)" name="Pin" logisim-mobile-id="b"><a name="label" val="B"/></comp>
<comp lib="0" loc="(100,200)" name="Pin" logisim-mobile-id="c"><a name="label" val="C"/></comp>
<comp lib="1" loc="(190,120)" name="AND Gate" logisim-mobile-id="and"/>
<comp lib="1" loc="(290,140)" name="OR Gate" logisim-mobile-id="or"/>
<comp lib="0" loc="(340,140)" name="Pin" logisim-mobile-id="y"><a name="type" val="output"/><a name="facing" val="west"/><a name="label" val="Y"/></comp>
</circuit></project>
"""
WIDTH_ERROR_RGB = "#0000FF"


class Device:
    def __init__(self, adb, serial, output):
        self.base = [adb, "-s", serial]
        self.output = output
        self.step = 0
        self.results = []

    def sh(self, *args, binary=False, stdin=None, timeout=60):
        result = subprocess.run(self.base + list(args), input=stdin, capture_output=True, timeout=timeout,
                                text=not binary and stdin is None)
        return result.stdout

    def dump(self):
        for _ in range(6):
            self.sh("shell", "uiautomator", "dump", "/sdcard/proof-ui.xml")
            raw = self.sh("exec-out", "cat", "/sdcard/proof-ui.xml")
            try:
                return ET.fromstring(raw)
            except ET.ParseError:
                time.sleep(0.5)
        raise AssertionError("uiautomator did not return a hierarchy")

    def find(self, *, text=None, desc=None, rid=None, root=None):
        root = root if root is not None else self.dump()
        return [n for n in root.iter("node")
                if (text is None or n.get("text") == text) and (desc is None or n.get("content-desc") == desc)
                and (rid is None or n.get("resource-id") == rid)]

    def one(self, timeout=15, **selector):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            found = self.find(**selector)
            if found:
                return found[0]
            time.sleep(0.4)
        self.capture("missing-" + "-".join(str(v) for v in selector.values()))
        raise AssertionError(f"Missing control {selector}")

    def tool(self, description):
        """A project without <toolbar> has no tools, as in Evolution (#344); the editor stays in Poke, which also selects."""
        found = self.find(desc=description)
        if found:
            self.tap(found[0])

    @staticmethod
    def center(node):
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        return (left + right) // 2, (top + bottom) // 2

    def tap(self, node=None, xy=None, **selector):
        x, y = xy or self.center(node if node is not None else self.one(**selector))
        self.sh("shell", "input", "tap", str(x), str(y))
        time.sleep(0.9)

    def back(self):
        self.sh("shell", "input", "keyevent", "4")
        time.sleep(0.9)

    def capture(self, name, **observed):
        self.step += 1
        stem = f"{self.step:02d}-{name}"
        png = self.sh("exec-out", "screencap", "-p", binary=True)
        (self.output / (stem + ".png")).write_bytes(png)
        root = self.dump()
        (self.output / (stem + ".xml")).write_bytes(ET.tostring(root, encoding="utf-8"))
        self.results.append({"step": stem, "screenshotSha256": hashlib.sha256(png).hexdigest(), **observed})
        (self.output / "results.json").write_text(json.dumps(self.results, indent=2) + "\n")
        return root

    def preferences(self):
        raw = self.sh("shell", "run-as", PACKAGE, "cat", "shared_prefs/editor-preferences.xml")
        text = (ET.fromstring(raw).findtext("string[@name='serialized']") or "") if raw.strip() else ""
        # EditorPreferencesCodec percent-encodes values (for example "?" is stored as %3F).
        from urllib.parse import unquote
        return {key: unquote(value) for key, value in (line.split("=", 1) for line in text.splitlines() if "=" in line)}

    def scroll_to(self, tries=25, **selector):
        for _ in range(tries):
            found = self.find(**selector)
            if found:
                y = self.center(found[0])[1]
                if 700 < y < 2000:
                    return found[0]
                swipe = ("540", "1900", "540", "1300") if y >= 2000 else ("540", "1100", "540", "1600")
            else:
                swipe = ("540", "1900", "540", "1100")
            self.sh("shell", "input", "swipe", *swipe, "400")
            time.sleep(0.6)
        raise AssertionError(f"Could not scroll to {selector}")

    def open_menu(self, menu, item):
        self.tap(rid="editor-project-toolbar-overflow")
        self.tap(text=menu)
        self.tap(text=item)

    def open_preferences(self):
        self.open_menu("File", "Preferences…")
        self.one(desc=None, rid="preferences-theme")

    def close_import_report(self):
        for node in self.find(text="Close"):
            self.tap(node)

    def mode(self, night):
        self.sh("shell", "cmd", "uimode", "night", "yes" if night else "no")
        time.sleep(3)

    def pid(self):
        return self.sh("shell", "pidof", PACKAGE).strip()


def theme_310(device):
    device.mode(False)
    device.sh("shell", "am", "start", "-W", "-n", f"{PACKAGE}/dev.logisim.mobile.MainActivity")
    time.sleep(3)
    device.close_import_report()
    pid = device.pid()
    device.open_preferences()
    rows = lambda root: [n.get("content-desc") for n in root.iter("node") if n.get("content-desc", "").startswith(("Theme:", "Editor theme:"))]
    light = device.capture("310-light-preferences-open")
    device.mode(True)
    dark = device.capture("310-dark-same-sheet", pid=device.pid())
    device.tap(rid="preferences-editor-theme")
    device.tap(text="Monokai")
    after = device.capture("310-dark-editor-theme-monokai", preferences=device.preferences())
    assert device.pid() == pid, "The system theme switch recreated the app process"
    assert "Editor theme: Monokai" in rows(after) and "Editor theme: Default" in rows(light), (rows(light), rows(after))
    assert device.find(rid="preferences-theme", root=dark), "The open preferences sheet must survive the switch"
    device.back()
    device.open_menu("Project", "Import Verilog / BLIF")
    fields = [n for n in device.dump().iter("node") if n.get("class", "").endswith("EditText")]
    device.tap(fields[2])
    device.sh("shell", "input text " + shlex.quote("module%sinv(input%sa,%soutput%sy);%s//%sinverter%sassign%sy%s=%s~a;%sendmodule"))
    time.sleep(1)
    hide = device.find(text="Hide keyboard")
    if hide:
        device.tap(hide[0])
    device.capture("310-hdl-editor-dark-monokai")
    device.mode(False)
    device.capture("310-hdl-editor-light-default", pid=device.pid())
    device.tap(text="Close")


def and_gate(device):
    """The AND gate of the bundled AND example; its imported ID differs between example revisions."""
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        gates = [n for n in device.dump().iter("node") if n.get("resource-id", "").startswith("canvas-component:")
                 and (n.get("content-desc") == "AND" or n.get("content-desc", "").startswith("AND ("))]
        if len(gates) == 1:
            return gates[0].get("resource-id")
        time.sleep(0.4)
    device.capture("missing-and-gate")
    raise AssertionError("Expected exactly one AND gate on the canvas")


def options_312(device):
    device.tool("Edit Tool")
    gate = and_gate(device)
    device.tap(rid=gate)
    device.capture("312-halo-on")
    x, y = device.center(device.one(rid=gate))
    device.sh("shell", "input", "motionevent", "DOWN", str(x), str(y))
    time.sleep(1.2)
    device.sh("shell", "input", "motionevent", "UP", str(x), str(y))
    time.sleep(1.5)
    root = device.capture("312-long-press-menu")
    tip = device.find(rid="canvas-component-tip", root=root)
    device.results[-1]["longPressTipShown"] = bool(tip)
    if tip:
        device.back()
    for toggle, label in (("preferences-attribute-halo", "halo"),):
        device.open_preferences()
        device.tap(device.scroll_to(rid=toggle))
        device.back()
        device.tap(rid=gate)
        device.capture(f"312-{label}-off", preferences=device.preferences())
        device.open_preferences()
        device.tap(device.scroll_to(rid=toggle))
        device.back()
    for choice, value in (("Bigger dot", "dot-bigger"), ("Small dot", "dot-small")):
        device.open_preferences()
        device.tap(device.scroll_to(rid="preferences-pin-appearance"))
        device.tap(text=choice)
        device.back()
        prefs = device.preferences()
        assert prefs.get("pinAppearance") == value, prefs
        device.capture(f"312-pins-{value}", pinAppearance=prefs.get("pinAppearance"))
    device.open_menu("Project", "Combinational analysis")
    device.tap(text="Read circuit")
    device.tap(text="Karnaugh")
    device.tap(rid="kmap-style-numbered")
    device.capture("312-kmap-numbered", kmapLinedStyle=device.preferences().get("kmapLinedStyle"))
    device.tap(rid="kmap-style-lined")
    device.one(rid="kmap-lined")
    device.capture("312-kmap-lined", kmapLinedStyle=device.preferences().get("kmapLinedStyle"))
    device.tap(rid="editor-tool-close")
    device.open_menu("Project", "Symbol appearance")
    grid = device.one(rid="appearance-show-grid")
    if grid.get("checked") != "true":
        device.tap(grid)
    device.capture("312-appearance-grid-on", appearanceShowGrid=device.preferences().get("appearanceShowGrid"))
    device.tap(rid="appearance-show-grid")
    device.capture("312-appearance-grid-off", appearanceShowGrid=device.preferences().get("appearanceShowGrid"))
    device.tap(rid="appearance-show-grid")
    device.tap(rid="editor-tool-close")


def open_fixture(device, xml):
    test = PACKAGE + ".test"
    result = device.sh("shell", "am", "broadcast", "-n", f"{test}/dev.logisim.mobile.documents.DocumentFixtureBootstrapReceiver")
    assert "result=-1" in result, result
    name = "e2e-" + uuid.uuid4().hex + ".circ"
    device.sh("shell", f"run-as {test} sh -c " + shlex.quote("cat > files/document-fixtures/" + name), stdin=xml.encode())
    device.sh("shell", "am", "start", "-W", "-f", "0x18000000", "-n", f"{test}/dev.logisim.mobile.documents.DocumentFixtureLauncherActivity",
              "--es", "action", "view", "--es", "fixtureUri", f"content://{test}.documents/document/{name}")
    device.tap(text="Open fixture")
    time.sleep(2)
    device.close_import_report()


def descriptions(root, prefix):
    return [n.get("content-desc") for n in root.iter("node") if n.get("resource-id", "").startswith(prefix)]


def pixel(png, x, y):
    """RGB of one pixel of an 8-bit RGB or RGBA PNG (screencap, simctl), without third-party decoders."""
    import struct
    import zlib
    width = struct.unpack(">I", png[16:20])[0]
    bpp = {2: 3, 6: 4}[png[25]]
    assert png[24] == 8, "8-bit PNG expected"
    pos, data = 8, b""
    while pos < len(png):
        length, kind = struct.unpack(">I4s", png[pos:pos + 8])
        if kind == b"IDAT":
            data += png[pos + 8:pos + 8 + length]
        pos += 12 + length
    raw, stride, prev = zlib.decompress(data), width * bpp, bytearray(width * bpp)
    for row in range(y + 1):
        kind, line = raw[row * (stride + 1)], bytearray(raw[row * (stride + 1) + 1:(row + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b, c = prev[i], prev[i - bpp] if i >= bpp else 0
            if kind == 1: line[i] = (line[i] + a) & 255
            elif kind == 2: line[i] = (line[i] + b) & 255
            elif kind == 3: line[i] = (line[i] + (a + b) // 2) & 255
            elif kind == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
    return "#%02X%02X%02X" % tuple(prev[x * bpp:x * bpp + 3])


def colors_311(device):
    device.mode(False)
    open_fixture(device, WIDTH_PROJECT)
    message = device.one(rid="editor-width-error")
    assert message.get("text") == "Incompatible widths (2)", message.get("text")
    probes = lambda root: {n.get("resource-id"): n.get("content-desc") or n.get("text") for n in root.iter("node")
                           if n.get("resource-id", "").startswith("canvas-component:probe")}
    device.capture("311-width-error-default", message=message.get("text"), probes=probes(device.dump()))
    # Wire Poke: tap the P-Q net halfway between P and the junction; its whole bundle takes the stroke colour.
    (px, py), (qx, _) = device.center(device.one(rid="canvas-component:p")), device.center(device.one(rid="canvas-component:q"))
    scale = (qx - px) / 140  # P's body centre is at x=90, Q's at x=230
    device.tool("Simulate")
    device.tap(xy=(int(px + 40 * scale), py))
    device.capture("311-wire-poke-stroke", tap=[int(px + 40 * scale), py])
    device.back()
    # Value character: the floating probe shows UNKNOWN_CHAR; choose "?" (SymbolChooser's second option).
    device.open_preferences()
    device.tap(device.scroll_to(rid="preferences-unknown-char"))
    device.tap(text="?")
    after = device.preferences()
    assert after.get("valueChar.unknown") == "?", after
    device.capture("311-unknown-char-preference", unknownChar=after.get("valueChar.unknown"))
    device.back()
    # Width error colour: Canvas colors → role → hex value → Apply.
    device.open_preferences()
    device.tap(device.scroll_to(text="Canvas colors"))
    device.tap(device.one(desc="Color to change: Canvas background"))
    for _ in range(12):
        found = device.find(text="Width error color")
        if found:
            device.tap(found[0])
            break
        device.sh("shell", "input", "swipe", "540", "1700", "540", "900", "500")
        time.sleep(0.6)
    else:
        raise AssertionError("Width error color is not listed")
    field = [n for n in device.dump().iter("node") if n.get("class", "").endswith("EditText")][0]
    device.tap(field)
    device.sh("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(8):
        device.sh("shell", "input", "keyevent", "KEYCODE_DEL")
    device.sh("shell", "input", "text", shlex.quote(WIDTH_ERROR_RGB))
    time.sleep(0.6)
    device.capture("311-color-dialog-width-error")
    device.tap(text="Apply")
    saved = device.preferences()
    assert saved.get("color.widthError") == WIDTH_ERROR_RGB, saved
    device.tap(text="Close")
    device.back()
    device.capture("311-changed-before-restart", probes=probes(device.dump()), widthError=saved.get("color.widthError"))
    # Restart: a new process must load the saved character and colour.
    before = device.pid()
    device.sh("shell", "am", "force-stop", PACKAGE)
    device.sh("shell", "am", "force-stop", PACKAGE + ".test")
    time.sleep(1)
    open_fixture(device, WIDTH_PROJECT)
    device.one(rid="editor-width-error")
    device.capture("311-after-restart", pidBefore=before, pidAfter=device.pid(), probes=probes(device.dump()),
                              preferences={k: v for k, v in device.preferences().items() if k in ("valueChar.unknown", "color.widthError")})
    assert device.pid() != before and device.results[-1]["preferences"] == {"valueChar.unknown": "?", "color.widthError": WIDTH_ERROR_RGB}
    # Restore defaults so the analysis steps use Evolution's colours, then check the restored width colour on screen.
    device.open_preferences()
    device.tap(device.scroll_to(text="Canvas colors"))
    device.tap(text="Restore canvas colors")
    device.tap(text="Close")
    device.tap(device.scroll_to(rid="preferences-unknown-char"))
    device.tap(text="U")
    device.back()
    restored = device.preferences()
    assert "color.widthError" not in restored and restored.get("valueChar.unknown") == "U", restored
    device.capture("311-restored-defaults", probes=probes(device.dump()))

    open_fixture(device, LOGIC_PROJECT)
    device.open_menu("Project", "Combinational analysis")
    device.tap(text="Read circuit")
    device.tap(text="Minimize")
    device.tap(text="Karnaugh")
    device.tap(rid="kmap-style-numbered")
    groups = device.one(rid="kmap-cells").get("content-desc")
    assert groups == "Karnaugh map groups: 2", groups
    device.capture("311-kmap-groups", groups=groups)
    device.tap(text="Table")
    device.tap(text="Select block")
    device.tap(rid="analysis-cell-0-1")
    device.tap(rid="analysis-cell-2-3")
    device.capture("311-table-cursor-selection")
    device.tap(rid="editor-tool-close")


def pla_316(device):
    for name, content in (("pla-5x2.txt", "# Logisim PLA program table\n10x1x 11 # alpha\nx0000 01\n"),
                          ("pla-4x3.txt", "# Logisim PLA program table\n0000 111 # imported\n1111 000\n")):
        device.sh("shell", f"cat > /sdcard/Download/{name}", stdin=content.encode())
    device.sh("shell", "rm", "-f", "/sdcard/Download/decoder.txt")
    test = PACKAGE + ".test"
    result = device.sh("shell", "am", "broadcast", "-n", f"{test}/dev.logisim.mobile.documents.DocumentFixtureBootstrapReceiver")
    assert "result=-1" in result, result
    name = "e2e-" + uuid.uuid4().hex + ".circ"
    device.sh("shell", f"run-as {test} sh -c " + shlex.quote("cat > files/document-fixtures/" + name), stdin=PLA_PROJECT.encode())
    device.sh("shell", "am", "start", "-W", "-f", "0x18000000", "-n", f"{test}/dev.logisim.mobile.documents.DocumentFixtureLauncherActivity",
              "--es", "action", "view", "--es", "fixtureUri", f"content://{test}.documents/document/{name}")
    device.tap(text="Open fixture")
    time.sleep(2)
    device.close_import_report()
    device.tool("Edit Tool")
    device.tap(rid="canvas-component:import-1")
    device.tap(rid="tool-properties")
    device.tap(desc="Edit property Truth table")
    device.capture("316-table-editor")
    device.tap(text="Export")
    device.one(text="decoder.txt")
    device.capture("316-native-save-picker")
    device.tap(text="SAVE")
    time.sleep(1.5)
    exported = device.sh("exec-out", "cat", "/sdcard/Download/decoder.txt", binary=True)
    (device.output / "exported-decoder.txt").write_bytes(exported)
    assert exported == EVOLUTION_SAVED, "The exported PLA file differs from Evolution's"
    device.capture("316-after-export", exportedSha256=hashlib.sha256(exported).hexdigest(), matchesEvolutionSaved=True)
    device.tap(text="Import")
    device.one(text="pla-5x2.txt")
    device.capture("316-native-open-picker")
    device.tap(text="pla-5x2.txt")
    message = "File contains unexpected input width, expected 4 but found 5"
    device.one(text=message)
    root = device.capture("316-width-mismatch", message=message)
    assert any("1x0x 101" in n.get("text", "") for n in root.iter("node")), "A mismatching file replaced the table"
    device.tap(text="Import")
    device.tap(device.one(text="pla-4x3.txt"))
    device.one(text="0000 111 # imported\n1111 000\n")
    device.capture("316-imported")
    device.tap(device.find(text="Apply")[-1])
    device.capture("316-applied")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default=str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
    parser.add_argument("--only", choices=("310", "311", "312", "316"), action="append")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    device = Device(args.adb, args.serial, args.output)
    manifest = {"serial": args.serial, "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "sourceChanges": subprocess.check_output(["git", "status", "--short"], cwd=ROOT, text=True).splitlines(),
                "fingerprint": device.sh("shell", "getprop", "ro.build.fingerprint").strip(),
                "appVersion": re.findall(r"versionName=\S+|lastUpdateTime=[^\n]+", device.sh("shell", "dumpsys", "package", PACKAGE))[:2]}
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    selected = args.only or ["310", "311", "312", "316"]
    for key, scenario in (("310", theme_310), ("311", colors_311), ("312", options_312), ("316", pla_316)):
        if key in selected:
            scenario(device)
            print(f"PASS: {key}", flush=True)
    print(f"Evidence: {args.output}")


if __name__ == "__main__":
    main()
