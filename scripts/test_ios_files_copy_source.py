#!/usr/bin/env python3
"""Exercise the actual Foundation Files source producer without XCTest or a simulator."""
from pathlib import Path
import shutil
import subprocess
import tempfile

CHECKS = r'''
import Foundation
import CryptoKit
var checks = 0
var failures = 0
func expect(_ name: String, _ value: @autoclosure () -> Bool) {
    checks += 1
    if !value() { failures += 1; print("FAIL: \(name)") }
}
func rejects(_ action: () throws -> Void) -> Bool {
    do { try action(); return false } catch { return true }
}
let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true).resolvingSymlinksInPath()
let fixtureData = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))
let hash = SHA256.hash(data: fixtureData).map { String(format: "%02x", $0) }.joined()
expect("approved fixture bytes", hash == "a2595e0ec949702685cf0465485e430b0491b1d8b31daf3350abe5f3697b55b9")
let runID = "aabbccddeeff"
let documents = root.appendingPathComponent("receiver/Documents/LogisimE2E/" + runID, isDirectory: true)
try FileManager.default.createDirectory(at: documents, withIntermediateDirectories: true)
let fixture = documents.appendingPathComponent("io-showcase-source.circ")
try fixtureData.write(to: fixture)
let config: [String: Any] = ["route": "files-copy", "peerDisplayName": "Clipboard Receiver", "runID": runID,
    "documents": documents.path, "fixture": fixture.path, "fixtureSHA256": hash]
let source = try FilesCopySource(configuration: config)
let name = "io-showcase-55AE3E74-59E7-4A4B-ACFD-B1C4AB7EACA4.circ"
let staged = try source.stage(fixtureData, filename: name)
expect("staged inside normalized owned run", staged.deletingLastPathComponent() == source.documents)
let stagedBytes = try Data(contentsOf: staged)
expect("staged exact actual fixture", stagedBytes == fixtureData)
try source.verify(staged)
let sourceBytes = try Data(contentsOf: fixture)
expect("source remains exact", sourceBytes == fixtureData)
expect("existing source is never overwritten", rejects { _ = try source.stage(fixtureData, filename: name) })
let preservedBytes = try Data(contentsOf: staged)
expect("existing staged bytes preserved", preservedBytes == fixtureData)
let second = "io-showcase-55AE3E74-59E7-4A4B-ACFD-B1C4AB7EACA5.circ"
expect("changed input rejected", rejects { _ = try source.stage(Data("different".utf8), filename: second) })
expect("changed input creates no file", !FileManager.default.fileExists(atPath: documents.appendingPathComponent(second).path))
for bad in ["../" + name, "/tmp/" + name, "folder\\" + name, name + "\n", name + "\r", "no-nonce.circ", name + ".txt"] {
    expect("invalid filename: \(bad)", rejects { _ = try source.stage(fixtureData, filename: bad) })
}
for (key, bad) in [("route", "system-open"), ("peerDisplayName", " "), ("runID", "../" + runID),
                   ("runID", "AABBCCDDEEFF"), ("documents", "relative/Documents/LogisimE2E/" + runID),
                   ("documents", documents.deletingLastPathComponent().path),
                   ("documents", documents.path + "/../" + runID),
                   ("fixture", root.appendingPathComponent("outside.circ").path),
                   ("fixtureSHA256", String(repeating: "0", count: 64)), ("fixtureSHA256", "invalid")] {
    var changed = config; changed[key] = bad
    expect("invalid configuration \(key)=\(bad)", rejects { _ = try FilesCopySource(configuration: changed) })
}
var absent = config; absent.removeValue(forKey: "fixtureSHA256")
expect("missing hash rejected", rejects { _ = try FilesCopySource(configuration: absent) })
let linked = documents.appendingPathComponent("linked.circ")
try FileManager.default.createSymbolicLink(at: linked, withDestinationURL: fixture)
var linkedConfig = config; linkedConfig["fixture"] = linked.path
expect("symlink fixture rejected", rejects { _ = try FilesCopySource(configuration: linkedConfig) })
let externalFixture = root.appendingPathComponent("same-bytes-outside.circ")
try fixtureData.write(to: externalFixture)
try FileManager.default.removeItem(at: fixture)
try FileManager.default.createSymbolicLink(at: fixture, withDestinationURL: externalFixture)
expect("fixture symlink introduced after init rejected at stage", rejects { _ = try source.stage(fixtureData, filename: second) })
expect("fixture symlink introduced after init rejected at verify", rejects { try source.verify(staged) })
try FileManager.default.removeItem(at: fixture)
try fixtureData.write(to: fixture)
try Data("tampered".utf8).write(to: staged)
expect("changed selected source rejected", rejects { try source.verify(staged) })
try fixtureData.write(to: staged)
try Data("tampered".utf8).write(to: fixture)
expect("changed base source rejected at return", rejects { try source.verify(staged) })
expect("changed base source rejected at stage", rejects { _ = try source.stage(fixtureData, filename: second) })
try fixtureData.write(to: fixture)
let moved = documents.deletingLastPathComponent().appendingPathComponent(runID + "-original", isDirectory: true)
try FileManager.default.moveItem(at: documents, to: moved)
let outside = root.appendingPathComponent("outside", isDirectory: true)
try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
try fixtureData.write(to: outside.appendingPathComponent("io-showcase-source.circ"))
try FileManager.default.createSymbolicLink(at: documents, withDestinationURL: outside)
expect("run folder replacement rejected", rejects { _ = try source.stage(fixtureData, filename: second) })
expect("nothing written through replaced folder", !FileManager.default.fileExists(atPath: outside.appendingPathComponent(second).path))
expect("symlink run folder configuration rejected", rejects { _ = try FilesCopySource(configuration: config) })
print("\(checks - failures)/\(checks) Files source checks passed")
if failures > 0 { exit(1) }
'''


def main():
    root = Path(__file__).resolve().parents[1]
    compiler = shutil.which("swiftc")
    if not compiler:
        raise SystemExit("swiftc is required")
    with tempfile.TemporaryDirectory(prefix="logisim-files-copy-source-") as temporary:
        work = Path(temporary).resolve()
        (work / "main.swift").write_text(CHECKS)
        subprocess.run([compiler, "-module-cache-path", str(work / "ModuleCache"),
                        str(root / "scripts/ios_native_ui/FilesCopySource.swift"), str(work / "main.swift"),
                        "-o", str(work / "checks")], check=True)
        subprocess.run([str(work / "checks"), str(work / "fixture-run"),
                        str(root / "examples/io-showcase.circ")], check=True)


if __name__ == "__main__":
    main()
