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


def reports(name, records, expected):
    records = {row["path"]: row for row in records}
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    with tarfile.open(fileobj=io.BytesIO(gzip.decompress((ROOT / name).read_bytes()))) as archive:
        assert set(archive.getnames()) == set(records)
        assert len(archive.getmembers()) == expected["suites"]
        for member in archive.getmembers():
            assert member.isfile()
            data = archive.extractfile(member).read()
            record = records[member.name]
            assert len(data) == record["bytes"]
            assert hashlib.sha256(data).hexdigest() == record["sha256"]
            report = ET.fromstring(data)
            assert len(report.findall("testcase")) == int(report.get("tests"))
            for key in totals:
                totals[key] += int(report.get(key, 0))
    assert totals == {key: expected[key] for key in totals}


def main():
    for row in (ROOT / "SHA256SUMS").read_text().splitlines():
        expected, name = row.split("  ", 1)
        assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == expected, name
    receipt = json.loads((ROOT / "receipt.json").read_text())
    reports("junit-reports.tar.gz", receipt["junitMembers"], receipt["hostTests"])
    reports("first-junit.tar.gz", receipt["firstJunitMembers"], receipt["firstFocusedTests"])
    assert receipt["hostTests"] == dict(suites=93, tests=552, failures=2, errors=0, skipped=0)
    assert receipt["fullOutcome"] == "FAILED"
    names = {"KeyboardInspectorRuntimeTest", "KeyboardInspectorSessionTest", "KeyboardInputCallbackTest",
             "KeyboardDraftRuntimeTest", "PropertyInspectorRuntimeTest", "PropertyInspectorSessionTest"}
    scoped = []
    with tarfile.open(fileobj=io.BytesIO(gzip.decompress((ROOT / "junit-reports.tar.gz").read_bytes()))) as archive:
        for member in archive.getmembers():
            report = ET.fromstring(archive.extractfile(member).read())
            if report.get("name").split(".")[-1] in names:
                scoped.append(report)
    assert len(scoped) == 6
    assert sum(int(report.get("tests")) for report in scoped) == 41
    assert all(int(report.get(key, 0)) == 0 for report in scoped for key in ("failures", "errors", "skipped"))
    assert receipt["native"] == "NOT_RUN"
    assert receipt["keyboardInputDraftUnchangedFromBase"]
    print(json.dumps(dict(status="HOST_RESULTS_VERIFIED", fullOutcome="FAILED", **receipt["hostTests"], scopeTestsPassed=41, native="NOT_RUN")))


if __name__ == "__main__":
    main()
