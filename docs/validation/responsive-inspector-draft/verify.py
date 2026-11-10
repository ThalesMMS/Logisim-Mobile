#!/usr/bin/env python3
"""Verify the portable host-test receipt without a compiler or device."""
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def main():
    for row in (ROOT / "SHA256SUMS").read_text().splitlines():
        expected, name = row.split("  ", 1)
        path = ROOT / name
        assert path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == expected, name
    receipt = json.loads((ROOT / "receipt.json").read_text())
    expected = {row["path"]: row for row in receipt["junitMembers"]}
    count = 0
    with tarfile.open(fileobj=io.BytesIO(gzip.decompress((ROOT / "junit-reports.tar.gz").read_bytes()))) as archive:
        assert set(archive.getnames()) == set(expected) and len(archive.getmembers()) == 91
        for member in archive.getmembers():
            assert member.isfile()
            data = archive.extractfile(member).read()
            record = expected[member.name]
            assert len(data) == record["bytes"] and hashlib.sha256(data).hexdigest() == record["sha256"]
            report = ET.fromstring(data)
            tests = report.findall("testcase")
            assert len(tests) == int(report.get("tests"))
            assert all(not case.findall("failure") and not case.findall("error") and not case.findall("skipped") for case in tests)
            assert all(int(report.get(key, 0)) == 0 for key in ("failures", "errors", "skipped"))
            count += len(tests)
    assert count == receipt["hostTests"]["tests"] == 543
    assert receipt["native"] == "NOT_RUN"
    print(json.dumps({"status": "HOST_RECEIPT_VERIFIED", "suites": 91, "tests": count, "native": "NOT_RUN"}))


if __name__ == "__main__":
    main()
