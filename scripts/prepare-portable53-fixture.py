#!/usr/bin/env python3
"""Create the deterministic portable-project fixture; no build, device, or application actions."""
import argparse
from io import BytesIO
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
fixture = ROOT / "fixtures/portable-projects"
manifest = b'<logisim type="bundle" version="1"><meta><tool name="Portable53 qualification" version="1"/></meta><project><files><file main="true">main.circ</file></files></project></logisim>'
files = {"manifest.xml": manifest}
files.update({str(path.relative_to(fixture / "sources")): path.read_bytes()
              for path in sorted((fixture / "sources").rglob("*.circ"))})
files["readme53.txt"] = (fixture / "opaque/readme53.txt").read_bytes()
files["library/unreferenced53.circ"] = (fixture / "opaque/unreferenced53.circ").read_bytes()
stream = BytesIO()
with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_STORED) as archive:
    for name, content in sorted(files.items()):
        entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_STORED
        entry.external_attr = 0o100644 << 16
        archive.writestr(entry, content)
output = fixture / "portable53.lsebdl"
if args.check:
    if not output.exists() or output.read_bytes() != stream.getvalue():
        parser.exit(1, "Portable53 fixture is stale\n")
else:
    output.write_bytes(stream.getvalue())
expected_path = fixture / "expected.json"
if expected_path.exists():
    expected = json.loads(expected_path.read_text())
    expected_hash = hashlib.sha256(stream.getvalue()).hexdigest()
    opaque_hashes = {name: hashlib.sha256(files[name]).hexdigest() for name in ("readme53.txt", "library/unreferenced53.circ")}
    if args.check:
        if expected["bundleSha256"] != expected_hash or expected["opaqueBundleEntries"] != opaque_hashes:
            parser.exit(1, "Portable53 expected hashes are stale\n")
    else:
        expected["bundleSha256"] = expected_hash
        expected["opaqueBundleEntries"] = opaque_hashes
        expected_path.write_text(json.dumps(expected, indent=2, ensure_ascii=False) + "\n")
print(output)
