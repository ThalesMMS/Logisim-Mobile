"""Issue 34. Drive the actual editor in the isolated Android E2E variant.

Call run_visual_export34 from the Android scenarios runner after installation. The
build receipt must identify the source gitHead and appSha256 of the compiled APK.
Native UI selectors target the runner's English Android system; editor labels use
the app's localization catalog. No share recipient or print destination is chosen.
"""
import hashlib
import json
from pathlib import Path
import re
import runpy
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
import zlib


SCRIPT_DIR = Path(__file__).resolve().parent


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def sha(content):
    return hashlib.sha256(content).hexdigest()


def wait_for(probe, description, timeout=35):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = probe()
        if value is not None:
            return value
        time.sleep(0.15)
    raise AssertionError(f"Timed out waiting for {description}")


def receipt_for(ui, build_path):
    manifest = json.loads((ui.output / "manifest.json").read_bytes())
    source = Path(build_path).read_bytes()
    build = json.loads(source)
    require(re.fullmatch(r"[0-9a-f]{40}", build["gitHead"]) and build["gitHead"] == manifest["gitHead"],
            "Build and E2E manifest must identify the same source commit")
    digest = build["appSha256"]
    require(re.fullmatch(r"[0-9a-f]{64}", digest) and digest == manifest["appSha256"],
            "Build receipt and runner refer to different APKs")
    installed = [item for item in manifest["installedArtifactsBefore"] if item["package"] == ui.package]
    require(ui.package == manifest["package"] and ui.package.endswith(".e2e") and len(installed) == 1
            and installed[0]["sha256"] == digest, "Expected the verified installed editor APK")
    require(ui.serial == manifest["serial"], "Manifest device differs from scenario device")
    (ui.output / "export34-build-receipt.json").write_bytes(source)
    return {"gitHead": build["gitHead"], "appSha256": digest, "installedEditor": installed[0],
            "serial": ui.serial, "buildReceiptSha256": sha(source), "scenarioSha256": sha(Path(__file__).read_bytes())}


def editor_activity(ui):
    active = ui.adb("shell", "dumpsys", "activity", "activities")
    component = ui.package + "/dev.logisim.mobile.MainActivity"
    require(any(component in line and ("ResumedActivity" in line or "topResumedActivity" in line)
                for line in active.splitlines()), "Foreground activity is not the installed MainActivity editor")
    return component


def open_tool(s):
    s.click_key("a11y_project_tools", description=True)
    s.click_key("visual_export_title", reveal=True)
    s.wait_key("visual_export_heading")


def hide_keyboard(ui):
    if not re.search(r"mInputShown=true|isInputViewShown=true", ui.adb("shell", "dumpsys", "input_method")):
        return
    ui.key(4)
    # The export sheet slides down after the keyboard closes; a control read during that
    # motion is tapped at a stale position. Continue once two reads agree on every bound.
    deadline = time.monotonic() + 10
    previous = None
    while time.monotonic() < deadline:
        layout = tuple(node.get("bounds") for node in ui.screen().iter("node"))
        if layout == previous:
            return
        previous = layout
        time.sleep(0.2)
    raise AssertionError("The export sheet kept moving after the keyboard closed")


def edit_field(s, key, value, original):
    # Compose can merge the floating label into the field, or expose only its
    # current editable value. The initial values of these three fields differ.
    def identify(root):
        fields = [n for n in root.iter("node") if n.get("class", "").endswith("EditText")]
        labels = set(s.catalog[key].values())
        named = [f for f in fields if any(n.get("text") in labels or n.get("content-desc") in labels
                                          for n in f.iter("node"))]
        matches = named or [f for f in fields if f.get("text") == original]
        require(len(matches) <= 1, f"Ambiguous editable field {key}")
        return matches[0] if matches else None

    field = identify(s.ui.screen())
    if field is None:
        label = s.reveal_key(key)
        field = identify(s.ui.screen())
        if field is None:
            l, t, r, b = s.ui._bounds(label)
            fields = [n for n in s.ui.screen().iter("node") if n.get("class", "").endswith("EditText")
                      and contains(s.ui._bounds(n), (l + r) // 2, (t + b) // 2)]
            require(len(fields) == 1, f"Cannot identify the editable field {key}")
            field = fields[0]
    if field.get("text") != str(value):
        s.ui.input_text(field, str(value))
        hide_keyboard(s.ui)


def contains(bounds, x, y):
    left, top, right, bottom = bounds
    return left <= x < right and top <= y < bottom


def toggle(s, key, enabled):
    s.reveal_key(key)
    def current():
        root = s.ui.screen()
        label = s.find_key(key, root=root)
        l, t, r, b = s.ui._bounds(label)
        matches = [n for n in root.iter("node") if n.get("checkable") == "true"
                   and contains(s.ui._bounds(n), (l + r) // 2, (t + b) // 2)]
        require(len(matches) == 1, f"Missing checkable control for {key}")
        return matches[0]
    node = current()
    if (node.get("checked") == "true") != enabled:
        s.ui.tap(node)
        require((current().get("checked") == "true") == enabled, f"{key} did not change")


def native_documents_field(ui):
    return next((n for n in ui.screen().iter("node") if n.get("class") == "android.widget.EditText"
                 and "documentsui" in n.get("package", "")), None)


def save_to_downloads(s, name):
    ui = s.ui
    s.click_key("visual_export_save", reveal=True)
    wait_for(lambda: native_documents_field(ui), "DocumentsUI Create Document")
    s.choose_downloads()
    root = ui.screen()
    fields = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"
              and "documentsui" in n.get("package", "")]
    require(len(fields) == 1, "Expected DocumentsUI's Create Document filename field")
    ui.input_text(fields[0], name)
    hide_keyboard(ui)
    ui.click(resource_id="android:id/button1")
    s.wait_key("visual_export_saved")
    # The fresh random filename prevents observing data from an earlier run.
    data = ui.adb("exec-out", "cat", "/sdcard/Download/" + name, text=False)
    require(data, "SAF produced no bytes")
    path = ui.output / name
    path.write_bytes(data)
    return path


def samples(decoded, spec, tolerance):
    width, height, channels, pixels = decoded
    require((width, height) == (spec["width"], spec["height"]), f"Unexpected raster {width} x {height}")
    measured = []
    for sample in spec["samples"]:
        offset = (sample["y"] * width + sample["x"]) * channels
        rgba = pixels[offset:offset + channels]
        r, g, b = rgba[:3]
        argb = bytes([rgba[3] if channels == 4 else 255, r, g, b])
        expected = bytes.fromhex(sample["argb"])
        require(all(abs(a - e) <= tolerance for a, e in zip(argb, expected)), f"Unexpected pixel at {sample}: {argb.hex()}")
        measured.append({"x": sample["x"], "y": sample["y"], "argb": argb.hex()})
    return {"width": width, "height": height, "samples": measured}


def ink_coverage(decoded, spec):
    """Observe each fixture member inside its literal, independent pixel ROI."""
    width, height, channels, pixels = decoded
    require((width, height) == (spec["width"], spec["height"]), f"Unexpected raster {width} x {height}")
    ink = spec["ink"]
    background = ink["backgroundRGB"]
    measured = []
    for region in ink["regions"]:
        left, top, right, bottom = region["pixelBounds"]
        require(0 <= left < right <= width and 0 <= top < bottom <= height,
                f"Ink region outside raster: {region['id']}")
        count = 0
        for y in range(top, bottom):
            for x in range(left, right):
                offset = (y * width + x) * channels
                if any(abs(pixels[offset + channel] - background[channel]) > ink["channelDifference"]
                       for channel in range(3)):
                    count += 1
        require(count >= region["minimumInkPixels"],
                f"Missing rendered fixture member {region['id']}: {count} ink pixels, expected at least {region['minimumInkPixels']}")
        measured.append({"id": region["id"], "kind": region["kind"], "worldBounds": region["worldBounds"],
                         "pixelBounds": region["pixelBounds"], "inkPixels": count,
                         "minimumInkPixels": region["minimumInkPixels"]})
    return {"backgroundRGB": background, "channelDifference": ink["channelDifference"], "regions": measured}


def rendered_page_colors(decoded, bounds):
    width, height, channels, pixels = decoded
    left, top, right, bottom = bounds
    require(0 <= left < right <= width and 0 <= top < bottom <= height, "Preview page outside screenshot")
    red = green = total = 0
    for y in range(top, bottom, 2):
        for x in range(left, right, 2):
            p = (y * width + x) * channels
            r, g, b = pixels[p:p + 3]
            red += r > 195 and g < 70 and b < 70
            green += 80 <= r <= 175 and g > 210 and 80 <= b <= 175
            total += 1
    return {"redFraction": red / total, "greenFraction": green / total}


def native_surface(ui, packages):
    root = ui.screen()
    if any(n.get("package") in packages for n in root.iter("node")):
        return root
    return None


def share_and_cancel(s, name):
    ui = s.ui
    s.click_key("visual_export_share", reveal=True)
    def chooser():
        root = native_surface(ui, {"android", "com.android.intentresolver"})
        if root is None:
            return None
        state = ui.adb("shell", "dumpsys", "activity", "activities")
        if any("ChooserActivity" in line and ("ResumedActivity" in line or "topResumedActivity" in line)
               for line in state.splitlines()):
            return root
        return None
    root = wait_for(chooser, "Android ACTION_SEND chooser")
    proof = ui.capture("export34-share-sheet", root)
    data = staged_pdf(ui, "shared-circuits", name)
    ui.key(4)
    s.wait_key("action_close")
    return data, {"chooser": proof, "outcome": "cancelled-at-chooser", "editorActivity": editor_activity(ui)}


def staged_pdf(ui, directory, name):
    cache = "cache/" + directory
    matches = ui.adb("shell", "run-as", ui.package, "find", cache, "-name", name).splitlines()
    require(len(matches) == 1 and re.fullmatch(re.escape(cache) + r"/[0-9a-f-]{36}/" + re.escape(name), matches[0]),
            f"No unique exported PDF in {directory}")
    return ui.adb("exec-out", "run-as", ui.package, "cat", matches[0], text=False)



def print_and_cancel(s, decoder, name, expected_bytes):
    ui = s.ui
    s.click_key("visual_export_print", reveal=True)
    def preview():
        root = native_surface(ui, {"com.android.printspooler"})
        if root is None:
            return None
        pages = [n for n in root.iter("node") if n.get("resource-id") == "com.android.printspooler:id/preview_page"
                 and n.get("content-desc", "").lower() == "page 1 of 1"]
        if len(pages) != 1 or any(n.get("class", "").endswith("ProgressBar") for n in root.iter("node")):
            return None
        proof = ui.capture("export34-print-preview", root)
        colors = rendered_page_colors(decoder(proof["screenshot"]), ui._bounds(pages[0]))
        # The 128 x 88 print-layout raster covers about 2% of a Letter page; its red and green
        # bands are each at least 0.2% of the page once the spooler has rendered it.
        if min(colors.values()) < 0.002:
            return None
        return {"preview": proof, "page": pages[0].get("content-desc"), **colors}
    result = wait_for(preview, "page 1 of 1 with the exported red and green annotation", timeout=50)
    printed = staged_pdf(ui, "print-assets", name)
    require(printed == expected_bytes, "Print adapter did not receive the verified selected PDF")
    result["stagedSha256"] = sha(printed)
    ui.key(4)
    s.wait_key("action_close")
    result.update(outcome="cancelled-at-preview", editorActivity=editor_activity(ui))
    return result


def inspect_pdf(path, selected, tolerance):
    """Use Poppler for page count and verify pixels of the application's RGB image."""
    data = path.read_bytes()
    spec = selected["pdf"]
    require(data.startswith(spec["signature"].encode()) and data.rstrip().endswith(b"%%EOF"), "Invalid PDF envelope")
    info = subprocess.run(["pdfinfo", str(path)], capture_output=True, text=True, timeout=15, check=True).stdout
    rows = re.search(r"^Pages:\s+(\d+)\s*$", info, re.MULTILINE)
    require(rows and int(rows[1]) == spec["pages"], "Unexpected PDF page count")
    image_streams = [m for m in re.finditer(rb"<<([^<>]+)>>\s*stream\r?\n", data)
                     if re.search(rb"/Subtype\s+/Image\b", m[1]) and b"/DeviceRGB" in m[1]]
    def dimensions(found):
        width, height = (re.search(rb"/" + name + rb"\s+(\d+)\b", found[1]) for name in (b"Width", b"Height"))
        return width and height and (int(width[1]), int(height[1]))
    # The print layout also draws its page header as an image.
    drawings = [m for m in image_streams if dimensions(m) == (spec["rasterWidth"], spec["rasterHeight"])]
    require(len(drawings) == 1, "Expected exactly one RGB raster of the selection in the selected PDF")
    stream = drawings[0]
    header = stream[1]
    require(b"/FlateDecode" in header and re.search(rb"/BitsPerComponent\s+8\b", header), "Unsupported PDF raster encoding")
    def integer(field):
        match = re.search(rb"/" + field + rb"\s+(\d+)\b", header)
        require(match is not None, f"Missing raster field {field}")
        return int(match[1])
    width, height, length = integer(b"Width"), integer(b"Height"), integer(b"Length")
    require((width, height) == (spec["rasterWidth"], spec["rasterHeight"]), "Wrong selected PDF raster dimensions")
    decoder = zlib.decompressobj()
    pixels = decoder.decompress(data[stream.end():stream.end() + length], width * height * 3 + 1)
    require(decoder.eof and not decoder.unused_data and len(pixels) == width * height * 3, "Invalid PDF image stream")
    expected = {"width": width, "height": height, "samples": spec["samples"]} if "samples" in spec else selected["png"]
    return {"pages": spec["pages"], "raster": samples((width, height, 3, pixels), expected, tolerance)}


def configure_panel(s, selection, name, settings, circuit_name):
    open_tool(s)
    if selection:
        s.ui.click(text="PDF")
    edit_field(s, "visual_export_filename", name, circuit_name)
    edit_field(s, "visual_export_scale", settings["scale"], "1")
    # PDF is the paged print layout since #343, which has no margin field.
    if not selection:
        edit_field(s, "visual_export_margin", settings["margin"], "10")
    toggle(s, "visual_export_selection", selection)
    toggle(s, "visual_export_transparent", settings["transparent"])
    s.ui.capture("export34-selection-settings" if selection else "export34-whole-settings")


def preserved_project(before, after):
    initial = ET.canonicalize(before, with_comments=True)
    final = ET.canonicalize(after, with_comments=True)
    require(initial == final, "Editor project XML changed after visual export, share, or print preview")
    return {"beforeXmlSha256": sha(before.encode()), "afterXmlSha256": sha(after.encode()),
            "canonicalXmlSha256": sha(final.encode())}


def run_visual_export34(scenarios, *, fixture_dir=None, build_receipt=None):
    """Return the verified per-run evidence. Raise on any missing prerequisite."""
    from android_editing_scenarios import _Editing
    s, ui = scenarios, scenarios.ui
    directory = Path(fixture_dir) if fixture_dir else SCRIPT_DIR.parent / "fixtures" / "visual-export"
    contract_bytes = (directory / "contract.json").read_bytes()
    contract = json.loads(contract_bytes)
    require(contract["version"] == 1 and contract["fixture"] == "qualification.circ", "Unsupported fixture contract")
    source = (directory / contract["fixture"]).read_bytes()
    require(sha(source) == contract["sha256"], "Qualification fixture digest mismatch")
    proof = receipt_for(ui, build_receipt or ui.output / "build-receipt.json")
    proof.update(contractSha256=sha(contract_bytes), fixtureSha256=sha(source))
    tolerance = contract["tolerance"]["maximumChannelDifference"]
    decoder = runpy.run_path(str(SCRIPT_DIR / "test-android-assets.py"))["decode_android_png"]
    # Only the permission-granting fixture provider is external; the editor must
    # open the document and expose its model through its own Copy XML action.
    s.incoming(s.fixture(source))
    proof["editorActivity"] = editor_activity(ui)
    editor = _Editing(s)
    original_xml, project = editor.export("export34-opened-document")
    circuits = project.findall("circuit")
    main = [c for c in circuits if c.get("name") == contract["mainCircuit"]]
    require(len(circuits) == contract["circuitCount"] and len(main) == 1, "Wrong editor project")
    require(len(main[0].findall("comp")) == contract["mainComponentCount"]
            and len(main[0].findall("wire")) == contract["wireCount"], "Large fixture did not fully import")
    labels = {a.get("val") for a in main[0].findall("comp/a[@name='label']")}
    require({contract["imageLabel"], contract["textLabel"], contract["farLabel"]} <= labels, "Missing qualification annotations")
    base = "export34-" + uuid.uuid4().hex[:12]
    configure_panel(s, False, base, contract["whole"], contract["mainCircuit"])
    png = save_to_downloads(s, base + ".png")
    decoded = decoder(png)
    proof["png"] = {"savedPath": str(png), "sha256": sha(png.read_bytes()),
                    **samples(decoded, contract["whole"]["png"], tolerance),
                    "ink": ink_coverage(decoded, contract["whole"]["png"])}
    s.click_key("action_close")
    editor.open_explorer()
    editor.search(contract["selection"]["componentLabel"])
    name = editor.component_name()
    require(contract["selection"]["componentLabel"] in name, f"Wrong search result: {name}")
    editor.click("a11y_explorer_select", name=name, description=True, scroll=True)
    editor.close_explorer()
    configure_panel(s, True, base, contract["selected"], contract["mainCircuit"])
    pdf = save_to_downloads(s, base + ".pdf")
    pdf_bytes = pdf.read_bytes()
    proof["pdf"] = {"savedPath": str(pdf), "sha256": sha(pdf_bytes),
                    **inspect_pdf(pdf, contract["selected"], tolerance)}
    shared, proof["share"] = share_and_cancel(s, pdf.name)
    require(shared == pdf_bytes, "The staged share PDF differs from the verified SAF file")
    proof["share"]["stagedSha256"] = sha(shared)
    proof["print"] = print_and_cancel(s, decoder, pdf.name, pdf_bytes)
    s.click_key("action_close")
    final_xml, _ = editor.export("export34-final-document")
    proof["projectPreservation"] = preserved_project(original_xml, final_xml)
    proof["finalEditorActivity"] = editor_activity(ui)
    (ui.output / "export34-result.json").write_text(json.dumps(proof, indent=2) + "\n")
    return proof
