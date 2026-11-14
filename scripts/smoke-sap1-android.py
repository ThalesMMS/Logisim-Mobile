#!/usr/bin/env python3
"""Run the bundled SAP-1 through the installed Android UI on a test device.

This replaces the open project with the SAP-1 example and restarts the app.
"""
import os
from pathlib import Path
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
ADB = str(SDK / "platform-tools/adb")
SERIAL = os.environ.get("ANDROID_SERIAL")
if not SERIAL:
    devices = subprocess.check_output([ADB, "devices"], text=True)
    attached = [line.split()[0] for line in devices.splitlines()[1:] if line.endswith("\tdevice")]
    if len(attached) != 1:
        raise SystemExit("Connect one test device or set ANDROID_SERIAL.")
    SERIAL = attached[0]
OUTPUT = Path(__file__).resolve().parents[1] / "outputs/sap1-validation"
OUTPUT.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.check_output([ADB, "-s", SERIAL, *args], text=True)


def screen():
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        result = adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/logisim-sap1.xml")
        if "dumped to" in result:
            root = ET.fromstring(adb("shell", "cat", "/sdcard/logisim-sap1.xml"))
            ready = any(n.get("content-desc", "").startswith(("Importar ou exportar circuito", "Biblioteca de componentes", "Carregar exemplo SAP-1")) or
                        "<project" in n.get("text", "") or n.get("text") in
                        ("Arquivo não importado", "Armazenamento do projeto", "Propriedades do circuito") for n in root.iter("node"))
            if ready:
                return root
        time.sleep(1)
    raise AssertionError("Android did not expose a current UI hierarchy")


def node(root, *, description=None, text=None):
    for item in root.iter("node"):
        if description is not None and item.get("content-desc", "").startswith(description):
            return item
        if text is not None and item.get("text") == text:
            return item
    raise AssertionError(f"Visible control missing: {description or text}")


def tap(item):
    left, top, right, bottom = map(int, re.findall(r"\d+", item.get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(0.25)


def capture(name, root=None):
    root = root if root is not None else screen()
    with (OUTPUT / f"{name}.png").open("wb") as image:
        subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"], stdout=image, check=True)
    (OUTPUT / f"{name}-ui.xml").write_bytes(ET.tostring(root, encoding="utf-8"))


def display_digits(root):
    description = node(root, description="Área do circuito.").get("content-desc")
    return re.findall(r"Saída: ([01?E]{4})(?=,|\.)", description)[-2:]


def export_project():
    tap(node(screen(), description="Importar ou exportar circuito"))
    root = screen()
    xml = next((n.get("text") for n in root.iter("node")
                if n.get("class") == "android.widget.EditText" and "<project" in n.get("text", "")), None)
    assert xml is not None, "Export did not expose the project XML"
    project = ET.fromstring(xml)
    assert len(project.findall("circuit")) == 7
    assert len(project.findall("circuit/comp")) == 156
    assert len(project.findall("circuit/wire")) == 318
    tap(node(root, text="Fechar"))
    return xml


def run_to_eight():
    tap(node(screen(), description="Reiniciar simulação"))
    tap(node(screen(), description="Alternar entrada Reset: 0"))
    tap(node(screen(), description="Alternar entrada Reset: 1"))
    root = screen()
    # The bottom bar's counter, simulation_ticks: "Ticks {count}" in Portuguese too.
    counter = "Ticks 0"
    for attempt in range(4):
        run_button = node(root, text="▶ Executar")
        tap(run_button)
        # The same fixed toolbar button becomes Pause. Read the result only after
        # stopping updates, since UiAutomator requires an idle accessibility tree.
        # The selected rate includes simulation work, so inspect actual progress.
        time.sleep(20 if attempt == 0 else 10)
        tap(run_button)
        root = screen()
        counter = next((n.get("text") for n in root.iter("node")
                        if re.fullmatch(r"Ticks \d+", n.get("text", ""))), "Ticks 0")
        if int(counter.split()[-1]) >= 56:
            break
    assert int(counter.split()[-1]) >= 56, f"Insufficient half-cycles: {counter}"
    digits = display_digits(root)
    assert digits == ["0000", "1000"], f"Expected visible displays 08, got {digits}"
    assert "não estabilizou" not in " ".join(n.get("text", "") for n in root.iter("node"))
    return root


def check_landscape():
    auto = adb("shell", "settings", "get", "system", "accelerometer_rotation").strip()
    rotation = adb("shell", "settings", "get", "system", "user_rotation").strip()
    try:
        adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb("shell", "settings", "put", "system", "user_rotation", "1")
        time.sleep(2)
        root = screen()
        assert root.get("rotation") == "1", f"Expected landscape: {root.attrib}"
        assert display_digits(root) == ["0000", "1000"], "Rotation changed simulation state"
        percentages = [int(n.get("text")[:-1]) for n in root.iter("node")
                       if re.fullmatch(r"\d+%", n.get("text", ""))]
        # The label counts dp per circuit unit, as Evolution's 100% (C48): the former 4% of pixels is 1.5% on the
        # emulator's 2.625x screen, so anything above 1% is the same usable zoom.
        assert percentages and max(percentages) > 1, f"Unusable fitted zoom: {percentages}"
        compact = any(n.get("content-desc", "").startswith("Abrir circuitos e biblioteca")
                      for n in root.iter("node"))
        if compact:
            tap(node(root, description="Abrir circuitos e biblioteca"))
            library = screen()
            assert any(n.get("text") == "Projeto · 7 circuitos" for n in library.iter("node"))
            adb("shell", "input", "keyevent", "4")
            root = screen()
            tap(node(root, description="Abrir propriedades da seleção"))
            properties = screen()
            assert any(n.get("text") == "Propriedades do circuito" for n in properties.iter("node"))
            adb("shell", "input", "keyevent", "4")
            root = screen()
        else:
            texts = [n.get("text") for n in root.iter("node")]
            assert "Projeto · 7 circuitos" in texts and "Propriedades do circuito" in texts
        assert display_digits(root) == ["0000", "1000"]
        capture("android-sap1-landscape", root)
        (OUTPUT / "android-landscape-result.json").write_text(json.dumps({
            "rotation": 1, "projectCircuits": 7, "display": "08",
            "zoomPercent": max(percentages), "compactLayout": compact,
            "libraryReachable": True, "inspectorReachable": True,
        }, indent=2) + "\n")
        print("PASS: landscape keeps 08 and exposes the library and inspector at usable zoom")
    finally:
        adb("shell", "settings", "put", "system", "user_rotation", rotation)
        adb("shell", "settings", "put", "system", "accelerometer_rotation", auto)
        time.sleep(2)
        root = screen()
        capture("android-sap1", root)


adb("shell", "am", "start", "-n", "thalesmms.logisim.mobile/dev.logisim.mobile.MainActivity")
root = screen()
try:
    example = node(root, description="Carregar exemplo SAP-1")
except AssertionError:
    tap(node(root, description="Abrir circuitos e biblioteca"))
    example = node(screen(), description="Carregar exemplo SAP-1")
tap(example)
root = screen()
run_to_eight()
xml = export_project()
(OUTPUT / "android-export.circ").write_text(xml)
print("PASS: SAP-1 shows 08 through the Android controls; all seven circuits export")

adb("shell", "am", "force-stop", "thalesmms.logisim.mobile")
adb("shell", "am", "start", "-n", "thalesmms.logisim.mobile/dev.logisim.mobile.MainActivity")
time.sleep(0.5)
assert export_project() == xml, "Saved SAP-1 changed after process restart"
run_to_eight()
capture("android-sap1")
print("PASS: SAP-1 survives process restart and runs again to 08")
check_landscape()
