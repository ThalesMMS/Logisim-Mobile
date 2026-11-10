import Foundation
import XCTest

/// Explicit Files Import Copy setup. It never opens a file URL or changes the route after a failure.
struct NativeFilesCopySetup {
    enum Failure: Error { case ambiguousEditor, queryRoot, missingControl }
    let source: FilesCopySource

    static func read(from run: [String: Any]) throws -> NativeFilesCopySetup? {
        guard let value = run["ioSetup"] else { return nil }
        guard let configuration = value as? [String: Any] else { throw FilesCopySource.Failure.configuration }
        return NativeFilesCopySetup(source: try FilesCopySource(configuration: configuration))
    }

    /// The bootstrap has no fixture identity yet. Require one public editor window before any action.
    static func uniqueEditorWindow(in harness: NativeUIHarness) throws -> XCUIElement {
        let candidates = harness.app.windows.allElementsBoundByAccessibilityElement.filter { window in
            guard DocumentWindowRules.isLiveWindow(DocumentWindowScope.observation(of: window, in: harness.app)) else { return false }
            let counts = ["editor-project-toolbar", "editor-command-toolbar", "editor-import-report"].map {
                window.descendants(matching: .any).matching(identifier: $0).allElementsBoundByAccessibilityElement
                    .filter { $0.exists }.count
            }
            let canvases = window.descendants(matching: .any).matching(identifier: "editor-canvas")
                .allElementsBoundByAccessibilityElement.filter { $0.exists }.count
            return counts.allSatisfy { $0 <= 1 } && canvases <= 1 && counts.contains(1)
        }
        guard candidates.count == 1 else {
            harness.attach(harness.app.debugDescription, name: "io-files-copy-bootstrap-ambiguous-tree")
            harness.screenshot("io-files-copy-bootstrap-ambiguous")
            XCTFail("Files Import Copy requires one public editor window before setup; no first-window choice is allowed")
            throw Failure.ambiguousEditor
        }
        let window = candidates[0]
        harness.attach("route=files-copy; candidates=1; frame=\(window.frame); bootstrap only, no fixture identity or hit ownership inferred",
                       name: "io-files-copy-bootstrap-window")
        return window
    }

    func select(_ staged: URL, in harness: NativeUIHarness, editorWindow: XCUIElement) throws {
        guard harness.nativeQueryRoot === editorWindow,
              DocumentWindowRules.isLiveWindow(DocumentWindowScope.observation(of: editorWindow, in: harness.app)) else {
            XCTFail("Files setup actions must remain scoped to the admitted bootstrap editor window")
            throw Failure.queryRoot
        }
        try source.verify(staged)
        func uniqueButton(_ key: String, toolbar: String? = nil) throws -> XCUIElement {
            let labels = try harness.values(key)
            _ = try harness.reveal(labels, toolbar: toolbar)
            let controls = harness.matching(labels).allElementsBoundByAccessibilityElement.filter {
                $0.exists && $0.elementType == .button && $0.isEnabled && $0.isHittable &&
                    $0.frame.width > 0 && $0.frame.height > 0 &&
                    editorWindow.frame.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY))
            }
            guard controls.count == 1 else {
                harness.attach(editorWindow.debugDescription, name: "io-files-copy-\(key)-not-unique")
                harness.screenshot("io-files-copy-\(key)-not-unique")
                XCTFail("Files setup needs exactly one reachable canonical \(key) button in its editor window")
                throw Failure.missingControl
            }
            return controls[0]
        }
        try uniqueButton("a11y_project_files", toolbar: "editor-project-toolbar").tap()
        guard harness.identified("editor-file-menu").waitForExistence(timeout: 5) else {
            harness.attach(editorWindow.debugDescription, name: "io-files-copy-menu-missing-tree")
            XCTFail("Present the project Files menu before choosing Import Copy")
            throw Failure.missingControl
        }
        try uniqueButton("action_import_copy").tap()
        try NativeFilesPeerNavigator(harness: harness, peerDisplayName: source.peerDisplayName, runID: source.runID)
            .open(filename: staged.lastPathComponent)
        try source.verify(staged)
        let metadata: [String: Any] = ["route": "files-copy", "filename": staged.lastPathComponent,
            "peerDisplayName": source.peerDisplayName, "runID": source.runID,
            "sourceSHA256": source.fixtureSHA256, "sourcePreserved": true,
            "qualification": "Observed Files selection and unchanged source bytes only. Require exact origin and unique document window before I/O assertions."]
        let data = try JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys])
        harness.attach(try XCTUnwrap(String(data: data, encoding: .utf8)), name: "io-files-copy-selected-source")
    }
}
