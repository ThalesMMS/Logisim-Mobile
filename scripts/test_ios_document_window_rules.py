#!/usr/bin/env python3
"""Run Foundation window-scope checks explicitly, without XCTest or a simulator."""

from pathlib import Path
import shutil
import subprocess
import tempfile


SWIFT_CHECKS = r'''
import Foundation
import CoreGraphics

var checks = 0
var failures = 0
func expect(_ name: String, _ condition: @autoclosure () -> Bool) {
    checks += 1
    if !condition() { failures += 1; print("FAIL: \(name)") }
}

let filename = "io-showcase-0CC34CEE-65CC-4467-8BDC-30F865D372C8.circ"
let opened = "Opened file \(filename)."
let origin = "Origin: \(filename)"
let anchor = try DocumentWindowAnchor(filename: filename, openedLabels: [opened], originLabels: [origin])
let screen = CGRect(x: 0, y: 0, width: 834, height: 1210)
func window(_ labels: [String], exists: Bool = true, isWindow: Bool = true,
            frame: CGRect = screen, applicationFrame: CGRect = screen,
            childCount: Int = 1) -> DocumentWindowObservation {
    DocumentWindowObservation(exists: exists, isWindow: isWindow, frame: frame,
                              applicationFrame: applicationFrame, labels: labels, childCount: childCount)
}
func rejectsAnchor(_ filename: String, openedLabels: [String]? = nil, originLabels: [String]? = nil) -> Bool {
    do {
        _ = try DocumentWindowAnchor(filename: filename, openedLabels: openedLabels ?? ["Opened \(filename)"],
                                     originLabels: originLabels ?? ["Origin \(filename)"])
        return false
    } catch { return true }
}

expect("exact opened label", anchor.matches(label: opened))
expect("exact origin label", anchor.matches(label: origin))
expect("basename retained", anchor.filename == filename)
for bad in ["", filename, "prefix " + opened, opened + " suffix", " " + opened, opened + " ",
            opened.replacingOccurrences(of: "0CC34CEE", with: "1CC34CEE"), "Opened file io-showcase.circ."] {
    expect("reject nonexact label \(bad)", !anchor.matches(label: bad))
}

let unrelated = window(["Opened file another.circ."])
let valid = window([opened])
expect("unique later Window", DocumentWindowRules.uniqueIndex(anchor: anchor, observations: [unrelated, valid]) == 1)
expect("order has no meaning", DocumentWindowRules.uniqueIndex(anchor: anchor, observations: [valid, unrelated]) == 0)
expect("origin can bind", DocumentWindowRules.uniqueIndex(anchor: anchor, observations: [window([origin])]) == 0)
expect("origin-only admission rejects transient opened status", DocumentWindowRules.uniqueIndex(anchor: anchor,
    observations: [valid], requiringOrigin: true) == nil)
expect("origin-only admission accepts exact report origin", DocumentWindowRules.uniqueIndex(anchor: anchor,
    observations: [window([origin])], requiringOrigin: true) == 0)
expect("origin-only admission does not prefer a report over another matching Window", DocumentWindowRules.uniqueIndex(anchor: anchor,
    observations: [valid, window([origin])], requiringOrigin: true) == nil)
expect("both labels in one Window meet origin-only admission", DocumentWindowRules.uniqueIndex(anchor: anchor,
    observations: [window([opened, origin])], requiringOrigin: true) == 0)
expect("two labels in one Window remain unique", DocumentWindowRules.uniqueIndex(anchor: anchor, observations: [window([opened, origin])]) == 0)
expect("duplicate qualified Windows are ambiguous", DocumentWindowRules.uniqueIndex(anchor: anchor, observations: [valid, valid]) == nil)
expect("origin and opened in distinct Windows are ambiguous", DocumentWindowRules.uniqueIndex(anchor: anchor, observations: [window([origin]), valid]) == nil)
expect("no Windows", DocumentWindowRules.uniqueIndex(anchor: anchor, observations: []) == nil)
expect("no nonce", DocumentWindowRules.uniqueIndex(anchor: anchor, observations: [unrelated]) == nil)
for label in [opened + " suffix", "prefix " + origin, filename, origin.replacingOccurrences(of: "0CC34CEE", with: "1CC34CEE")] {
    expect("nonexact Window cannot bind", DocumentWindowRules.uniqueIndex(anchor: anchor, observations: [window([label])]) == nil)
}

let invalidWindows: [(String, DocumentWindowObservation)] = [
    ("missing", window([opened], exists: false)),
    ("wrong role", window([opened], isWindow: false)),
    ("empty AX tree", window([opened], childCount: 0)),
    ("negative child count", window([opened], childCount: -1)),
    ("zero frame", window([opened], frame: .zero)),
    ("negative width", window([opened], frame: CGRect(x: 0, y: 0, width: -1, height: 100))),
    ("null frame", window([opened], frame: .null)),
    ("infinite frame", window([opened], frame: .infinite)),
    ("NaN frame", window([opened], frame: CGRect(x: CGFloat.nan, y: 0, width: 100, height: 100))),
    ("overflowing frame", window([opened], frame: CGRect(x: CGFloat.greatestFiniteMagnitude, y: 0, width: CGFloat.greatestFiniteMagnitude, height: 100))),
    ("offscreen", window([opened], frame: CGRect(x: 900, y: 0, width: 100, height: 100))),
    ("touches screen edge only", window([opened], frame: CGRect(x: 834, y: 0, width: 100, height: 100))),
    ("zero application frame", window([opened], applicationFrame: .zero)),
    ("NaN application frame", window([opened], applicationFrame: CGRect(x: 0, y: 0, width: CGFloat.nan, height: 1210))),
    ("infinite application frame", window([opened], applicationFrame: .infinite))
]
for (name, observation) in invalidWindows {
    expect("not live: \(name)", !DocumentWindowRules.isLiveWindow(observation))
    expect("cannot bind: \(name)", DocumentWindowRules.uniqueIndex(anchor: anchor, observations: [observation]) == nil)
    expect("invalid observation cannot hide unique valid Window: \(name)",
           DocumentWindowRules.uniqueIndex(anchor: anchor, observations: [observation, valid]) == 1)
}
expect("positive partial intersection", DocumentWindowRules.isLiveWindow(window([opened], frame: CGRect(x: 820, y: 1100, width: 100, height: 100))))

let closedCases = [
    ("missing Window and editor", false, false, false, false),
    ("missing Window cannot close", false, true, false, false),
    ("missing editor cannot close", true, false, false, false),
    ("empty AX with modal", false, false, true, false),
    ("stale editor with missing Window", false, true, true, false),
    ("modal without editor", true, false, true, false),
    ("editor behind modal is not closed", true, true, true, false),
    ("live editor after modal closes", true, true, false, true)
]
for (name, live, editor, modal, expected) in closedCases {
    expect(name, DocumentWindowRules.canObserveEditorWithoutModal(windowLive: live,
                                                                  editorPresent: editor, modalPresent: modal) == expected)
    expect("verified origin return: \(name)", DocumentWindowRules.canCompleteReturn(windowLive: live,
        originVerified: true, editorPresent: editor, modalPresent: modal) == expected)
    expect("unverified origin cannot complete return: \(name)",
           !DocumentWindowRules.canCompleteReturn(windowLive: live, originVerified: false,
                                                  editorPresent: editor, modalPresent: modal))
}

for bad in ["", "io-showcase.circ", "/tmp/" + filename, "../" + filename, "folder\\" + filename,
            "x0CC34CEE-65CC-4467-8BDC-30F865D372C8.circ", filename + "\n",
            "io-showcase-0CC34CEE-65CC-4467-8BDC-30F865D372CZ.circ"] {
    expect("invalid filename \(bad)", rejectsAnchor(bad))
}
expect("empty opened list", rejectsAnchor(filename, openedLabels: []))
expect("empty origin list", rejectsAnchor(filename, originLabels: []))
expect("blank opened label", rejectsAnchor(filename, openedLabels: [" "]))
expect("one empty label is not discarded", rejectsAnchor(filename, originLabels: [origin, ""]))
expect("labels must refer to filename", rejectsAnchor(filename, openedLabels: ["Opened another.circ."]))
let bilingual = try DocumentWindowAnchor(filename: filename, openedLabels: [opened, "Arquivo aberto: \(filename)."],
                                          originLabels: [origin, "Origem: \(filename)"])
expect("exact localized label", bilingual.matches(label: "Origem: \(filename)"))

print("\(checks - failures)/\(checks) Foundation window checks passed")
if failures > 0 { exit(1) }
'''


def main():
    compiler = shutil.which("swiftc")
    if compiler is None:
        raise SystemExit("swiftc is required for this explicit Foundation check")
    source = Path(__file__).with_name("ios_native_ui") / "DocumentWindowRules.swift"
    with tempfile.TemporaryDirectory(prefix="logisim-document-window-rules-") as directory:
        scratch = Path(directory)
        test_source = scratch / "main.swift"
        test_source.write_text(SWIFT_CHECKS)
        executable = scratch / "document-window-rules"
        subprocess.run([compiler, "-module-cache-path", str(scratch / "module-cache"),
                        str(source), str(test_source), "-o", str(executable)], check=True)
        subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    main()
