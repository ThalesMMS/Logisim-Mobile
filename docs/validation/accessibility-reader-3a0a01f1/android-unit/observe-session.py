#!/usr/bin/env python3
"""Observe one explicitly leased Android reader unit without injecting UI actions."""

import hashlib
import json
from pathlib import Path
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, "/Users/thales/GitHub/logisim-mobile-worktrees/android-reader-observer/scripts")
from android_ui import AndroidUi

output = Path(sys.argv[1]).resolve()
output.mkdir(parents=True, exist_ok=False)
ui = AndroidUi("/Users/thales/Library/Android/sdk/platform-tools/adb", "emulator-5556",
               "thalesmms.logisim.mobile.e2e", output, preserve_accessibility_services=True)
print("ARMED: observer only. No UI action, service enable or setting change has executed.", flush=True)
for index, line in enumerate(sys.stdin, 1):
    request = json.loads(line)
    name = request.get("name", f"step-{index}")
    if not name or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-" for c in name):
        raise ValueError("Use a unique lowercase capture name")
    record = {"index": index, "name": name, "operation": request["op"],
              "host_monotonic_seconds": time.monotonic()}
    try:
        if request["op"] == "screen":
            root = ui.screen()
            path = output / f"{index:03d}-{name}.xml"
            path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
            record["focused"] = [{k: node.get(k) for k in ["resource-id", "text", "content-desc", "state-desc", "bounds"]}
                                 for node in root.iter("node") if node.get("package") == ui.package
                                 and node.get("accessibility-focused") == "true"]
            record["root_attributes"] = dict(root.attrib)
            record["path"] = path.name
            record["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        elif request["op"] == "screenshot":
            result = ui._run(("exec-out", "screencap", "-p"), text=False)
            path = output / f"{index:03d}-{name}.png"
            path.write_bytes(result.stdout)
            record["path"] = path.name
            record["sha256"] = hashlib.sha256(result.stdout).hexdigest()
        elif request["op"] == "mark":
            record["details"] = request.get("details", {})
        elif request["op"] == "finish":
            record["note"] = "Observation session ended; restoration and lane return require independent evidence."
        else:
            raise ValueError("Only screen, screenshot, mark and finish are supported")
        record["status"] = "CAPTURED"
    except Exception as error:
        record["status"] = "FAIL"
        record["error"] = f"{type(error).__name__}: {error}"
    (output / f"{index:03d}-{name}.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps(record), flush=True)
    if request["op"] == "finish":
        break
