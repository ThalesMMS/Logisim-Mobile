#!/usr/bin/env python3
"""Check diagnostic encoding and prove the fixture's mutation body is unchanged from 601."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
BASE = "601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd"
PREFIX = "scripts/ios_fileprovider_revocation/"


def baseline(relative):
    return subprocess.check_output(["git", "show", BASE + ":" + PREFIX + relative], cwd=REPO)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


def verify_source():
    old_files = subprocess.check_output(
        ["git", "ls-tree", "-r", "--name-only", BASE, "--", PREFIX], cwd=REPO, text=True).splitlines()
    protected = {}
    for file in old_files:
        relative = file.removeprefix(PREFIX)
        if relative == "Provider/RevocationFileProvider.swift":
            continue
        old = baseline(relative)
        assert (ROOT / relative).read_bytes() == old, relative
        protected[relative] = sha(old)
    provider = (ROOT / "Provider/RevocationFileProvider.swift").read_text()
    old = baseline("Provider/RevocationFileProvider.swift").decode()
    mutation_body = "{ beginCommit in\n"
    end = "\n    func deleteItem("
    assert section(provider, mutation_body, end) == section(old, mutation_body, end), "Mutation body changed"
    assert section(provider, "    func deleteItem(", "    private func perform<Value>") == section(
        old, "    func deleteItem(", "    private func perform<Value>"), "Delete/activeStore changed"
    assert provider[:provider.index("    func modifyItem(")] == old[:old.index("    func modifyItem(")]
    assert provider[provider.index("    private func record("):] == old[old.index("    private func record("):]
    expected_start = section(old, "    private func perform<Value>", "    private func record(")
    actual_start = section(provider, "    private func perform<Value>", "    private func record(")
    expected_start = expected_start.replace(
        "identifier: NSFileProviderItemIdentifier,\n",
        "identifier: NSFileProviderItemIdentifier,\n                                details requestDetails: [String: String] = [:],\n", 1)
    expected_start = expected_start.replace('let details = ["requestID": requestID]',
                                            'let details = requestDetails.merging(["requestID": requestID]) { _, new in new }', 1)
    assert actual_start == expected_start, "perform changed beyond event details"
    item = (ROOT / "Provider/RevocationProviderItem.swift").read_text()
    assert "var contentPolicy: NSFileProviderContentPolicy { .downloadLazilyAndEvictOnRemoteUpdate }" in item
    assert "fixtureDeclaredContentPolicyRaw: NSFileProviderContentPolicy.downloadLazilyAndEvictOnRemoteUpdate.rawValue" in provider
    return {"baseline": BASE, "protectedFilesByteIdentical": protected,
            "mutationBodyByteIdentical": True, "fixturePolicyUnchanged": "downloadLazilyAndEvictOnRemoteUpdate",
            "sourceProof": "PASS", "nativeExecution": "NOT_RUN"}


def verify_sdk():
    sdk = Path(subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"], text=True).strip())
    header = sdk / "System/Library/Frameworks/FileProvider.framework/Headers/NSFileProviderItem.h"
    text = header.read_text()
    fields = re.findall(r"NSFileProviderItem(Contents|Filename|ParentItemIdentifier|LastUsedDate|TagData|FavoriteRank|CreationDate|ContentModificationDate|FileSystemFlags|ExtendedAttributes|TypeAndCreator)\s*(?:FILEPROVIDER_API_AVAILABILITY_V4_0_IOS\s*)?= 1 << (\d+)", text)
    assert len(fields) == 11 and [int(bit) for _, bit in fields] == list(range(11)), fields
    helper = (ROOT / "Provider/RevocationModifyDiagnostics.swift").read_text()
    for name, bit in fields:
        swift_name = name[0].lower() + name[1:]
        assert f'(1 << {bit}, "{swift_name}")' in helper
    return {"sdkHeader": str(header), "sdkHeaderSHA256": sha(header.read_bytes()), "fieldSchema": fields}


def main():
    report = verify_source()
    report.update(verify_sdk())
    with tempfile.TemporaryDirectory(prefix="logisim-modify-diagnostics-") as temporary:
        executable = Path(temporary) / "checks"
        subprocess.run(["xcrun", "swiftc", "-warnings-as-errors",
                        str(ROOT / "Provider/RevocationModifyDiagnostics.swift"),
                        str(ROOT / "ModifyDiagnosticsChecks.swift"), "-o", str(executable)], check=True, timeout=60)
        subprocess.run([str(executable)], check=True, timeout=30)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
