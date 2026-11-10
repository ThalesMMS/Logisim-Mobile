#!/usr/bin/env python3
"""Exercise the installed Android app through its visible controls."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
ADB = str(SDK / "platform-tools/adb")
SERIAL = os.environ.get("ANDROID_SERIAL")
if not SERIAL:
    attached = subprocess.check_output([ADB, "devices"], text=True)
    devices = [line.split()[0] for line in attached.splitlines()[1:] if line.endswith("\tdevice")]
    if len(devices) != 1:
        raise SystemExit("Conecte um dispositivo ou defina ANDROID_SERIAL para escolher um deles.")
    SERIAL = devices[0]


def adb(*args):
    return subprocess.check_output([ADB, "-s", SERIAL, *args], text=True)


def screen():
    adb("shell", "uiautomator", "dump", "/sdcard/logisim-mobile-smoke.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/logisim-mobile-smoke.xml"))


def node(root, *, description=None, text=None):
    for item in root.iter("node"):
        if description is not None and item.get("content-desc", "").startswith(description):
            return item
        if text is not None and item.get("text") == text:
            return item
    raise AssertionError(f"Visible control missing: {description or text}")


def optional_node(root, *, description=None, text=None):
    try:
        return node(root, description=description, text=text)
    except AssertionError:
        return None


def tap(item):
    left, top, right, bottom = map(int, re.findall(r"\d+", item.get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(0.25)


def library_control(description):
    """Find a library action in the tablet sidebar or the phone sheet."""
    root = screen()
    item = optional_node(root, description=description)
    if item is not None:
        return item
    opener = optional_node(root, description="Abrir circuitos e biblioteca")
    if opener is not None:
        tap(opener)
    for _ in range(10):
        root = screen()
        item = optional_node(root, description=description)
        if item is not None:
            return item
        pane = node(root, description="Biblioteca de componentes e circuitos")
        left, top, right, bottom = map(int, re.findall(r"\d+", pane.get("bounds")))
        x = (left + right) // 2
        # Keep the drag within the list, above the fixed examples footer.
        adb("shell", "input", "swipe", str(x), str(bottom - 110),
            str(x), str(top + 110), "350")
        time.sleep(0.2)
    raise AssertionError(f"Library control missing: {description}")


def output(expected):
    root = screen()
    node(root, text=f"Y = {expected}")
    return root


def exported_xml():
    tap(node(screen(), description="Importar ou exportar circuito"))
    root = screen()
    xml = next((n.get("text") for n in root.iter("node")
                if n.get("class") == "android.widget.EditText" and "<project" in n.get("text", "")), None)
    if xml is None:
        raise AssertionError("Export dialog did not expose a circuit document")
    document = ET.fromstring(xml)
    tap(node(root, text="Fechar"))
    return document


adb("shell", "am", "start", "-n", "thalesmms.logisim.mobile/dev.logisim.mobile.MainActivity")
root = screen()
tap(library_control("Carregar exemplo AND"))
root = output(0)
for name, expected in [("A", 0), ("B", 1), ("A", 0), ("B", 0)]:
    tap(node(root, description=f"Alternar entrada {name}:"))
    root = output(expected)
print("PASS: AND truth table through input controls")
initial_wire_count = len(exported_xml().findall(".//wire"))

tap(library_control("Inserir OR"))
canvas = node(screen(), description="Área do circuito.")
left, top, right, bottom = map(int, re.findall(r"\d+", canvas.get("bounds")))
adb("shell", "input", "tap", str((left + right) // 2), str(top + (bottom - top) // 5))
time.sleep(0.25)
document = exported_xml()
assert len(document.findall(".//comp[@name='OR Gate']")) == 1, "Placed gate missing from export"
assert len(document.findall(".//wire")) == initial_wire_count, "Placing a gate changed existing wires"
print("PASS: place a gate and export the edited document")

adb("shell", "am", "force-stop", "thalesmms.logisim.mobile")
adb("shell", "am", "start", "-n", "thalesmms.logisim.mobile/dev.logisim.mobile.MainActivity")
time.sleep(0.5)
document = exported_xml()
assert len(document.findall(".//comp[@name='OR Gate']")) == 1, "Edited document not restored after relaunch"
assert len(document.findall(".//wire")) == initial_wire_count, "Restoring the project changed wires"
print("PASS: document survives process restart")

tap(library_control("Carregar exemplo AND"))
tap(node(screen(), description="Desfazer"))
document = exported_xml()
assert len(document.findall(".//comp[@name='OR Gate']")) == 1, "Loading an example lost undo history"
assert len(document.findall(".//wire")) == initial_wire_count, "Undo did not restore wires"
tap(node(screen(), description="Refazer"))
document = exported_xml()
assert not document.findall(".//comp[@name='OR Gate']"), "Redo did not restore the example"
output(0)
print("PASS: undo and redo recover a replaced document")
