import Foundation
import XCTest

/// A document window is admitted by an exact public filename anchor, then bound to its AX element.
final class DocumentWindowScope {
    enum Failure: Error { case ambiguousWindows, missingWindow, wrongNonce, missingModal, ambiguousControl, modalNotClosed }
    let anchor: DocumentWindowAnchor
    let window: XCUIElement
    private let bindingID = UUID().uuidString
    private var recordNumber = 0

    private init(anchor: DocumentWindowAnchor, window: XCUIElement) {
        self.anchor = anchor
        self.window = window
    }

    static func observation(of window: XCUIElement, in app: XCUIApplication) -> DocumentWindowObservation {
        guard window.exists, let snapshot = try? window.snapshot() else {
            return DocumentWindowObservation(exists: false, isWindow: false, frame: .zero,
                applicationFrame: app.frame, labels: [], childCount: 0)
        }
        func labels(_ node: XCUIElementSnapshot) -> [String] {
            [node.label] + node.children.flatMap(labels)
        }
        return DocumentWindowObservation(exists: true, isWindow: snapshot.elementType == .window,
            frame: snapshot.frame, applicationFrame: app.frame, labels: labels(snapshot), childCount: snapshot.children.count)
    }

    static func resolve(in app: XCUIApplication, anchor: DocumentWindowAnchor) throws -> DocumentWindowScope? {
        // Unlike an ordinal query, these references retain the resulting accessibility elements.
        let windows = app.windows.allElementsBoundByAccessibilityElement
        let observations = windows.map { observation(of: $0, in: app) }
        let matches = observations.filter {
            DocumentWindowRules.isLiveWindow($0) && $0.labels.contains(where: anchor.matches)
        }
        if matches.count > 1 { throw Failure.ambiguousWindows }
        guard let index = DocumentWindowRules.uniqueIndex(anchor: anchor, observations: observations) else { return nil }
        return DocumentWindowScope(anchor: anchor, window: windows[index])
    }

    func isLive(in app: XCUIApplication) -> Bool {
        DocumentWindowRules.isLiveWindow(Self.observation(of: window, in: app))
    }

    func requireLive(in harness: NativeUIHarness, expectedFilename: String, stage: String) throws {
        guard anchor.filename == expectedFilename else {
            record(in: harness, stage: stage + "-wrong-nonce")
            XCTFail("The retained window binding belongs to another document nonce")
            throw Failure.wrongNonce
        }
        guard isLive(in: harness.app) else {
            record(in: harness, stage: stage + "-missing-window")
            XCTFail("The nonce-bound accessibility window must retain a nonempty visible window tree")
            throw Failure.missingWindow
        }
    }

    func matching(_ labels: [String], type: XCUIElement.ElementType = .any) -> [XCUIElement] {
        let predicate = NSCompoundPredicate(orPredicateWithSubpredicates: labels.map {
            NSPredicate(format: "label == %@ OR label BEGINSWITH %@", $0, $0 + ", ")
        })
        return window.descendants(matching: type).matching(predicate).allElementsBoundByAccessibilityElement
            .filter { $0.exists }
    }

    func requireModal(in harness: NativeUIHarness, titleLabels: [String], stage: String) throws {
        try requireLive(in: harness, expectedFilename: anchor.filename, stage: stage)
        let bodies = window.descendants(matching: .any).matching(identifier: "editor-xml-body")
            .allElementsBoundByAccessibilityElement.filter { $0.exists }
        let titles = matching(titleLabels, type: .staticText)
        guard bodies.count == 1, titles.count == 1,
              bodies[0].frame.width > 0, bodies[0].frame.height > 0,
              window.frame.contains(CGPoint(x: bodies[0].frame.midX, y: bodies[0].frame.midY)) else {
            record(in: harness, stage: stage + "-missing-owned-modal")
            XCTFail("The XML modal must be uniquely presented inside the retained nonce window; no global window transfer is allowed")
            throw Failure.missingModal
        }
    }

    func uniqueClose(in harness: NativeUIHarness, labels: [String], titleLabels: [String]) throws -> XCUIElement {
        try requireModal(in: harness, titleLabels: titleLabels, stage: "xml-return")
        let controls = matching(labels, type: .button).filter {
            let frame = $0.frame
            return $0.isEnabled && frame.width > 0 && frame.height > 0 &&
                frame.midX.isFinite && frame.midY.isFinite &&
                window.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) && $0.isHittable
        }
        guard controls.count == 1 else {
            record(in: harness, stage: "xml-close-not-unique")
            XCTFail("The retained document window must contain exactly one reachable Close button")
            throw Failure.ambiguousControl
        }
        return controls[0]
    }

    private func editorObservation(in app: XCUIApplication, titleLabels: [String]) -> (live: Bool, editor: Bool, modal: Bool) {
        let observation = Self.observation(of: window, in: app)
        let live = DocumentWindowRules.isLiveWindow(observation)
        guard live else { return (false, false, true) }
        let markers = window.descendants(matching: .any).matching(identifier: "editor-canvas")
            .allElementsBoundByAccessibilityElement.filter { $0.exists }
        let toolbars = ["editor-project-toolbar", "editor-command-toolbar"].contains {
            window.descendants(matching: .any).matching(identifier: $0).count == 1
        }
        let modal = !matching(titleLabels, type: .staticText).isEmpty ||
            ["editor-xml-body", "editor-import-report"].contains {
                window.descendants(matching: .any).matching(identifier: $0).count > 0
            }
        return (live, markers.count == 1 && toolbars, modal)
    }

    func observesEditorWithoutModal(in app: XCUIApplication, titleLabels: [String]) -> Bool {
        let observed = editorObservation(in: app, titleLabels: titleLabels)
        return DocumentWindowRules.canObserveEditorWithoutModal(windowLive: observed.live,
            editorPresent: observed.editor, modalPresent: observed.modal)
    }

    /// Status text changes during normal editing. Reopen the existing report to read the persistent source.
    func revalidateOriginAfterXmlReturn(in harness: NativeUIHarness, xmlTitleLabels: [String]) throws {
        try requireLive(in: harness, expectedFilename: anchor.filename, stage: "xml-return-before-origin-report")
        try harness.tapKey("import_report_title", toolbar: "editor-project-toolbar")
        let reports = window.descendants(matching: .any).matching(identifier: "editor-import-report")
        let presented = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            reports.allElementsBoundByAccessibilityElement.filter { $0.exists }.count == 1
        }, object: harness.app)
        guard XCTWaiter.wait(for: [presented], timeout: 10) == .completed else {
            record(in: harness, stage: "xml-return-origin-report-missing")
            XCTFail("Reopen the persistent import report in the same nonce-bound window")
            throw Failure.missingModal
        }
        let liveReports = reports.allElementsBoundByAccessibilityElement.filter { $0.exists }
        guard liveReports.count == 1 else {
            record(in: harness, stage: "xml-return-origin-report-no-longer-unique")
            XCTFail("The same window must retain exactly one import report after admission")
            throw Failure.missingModal
        }
        let report = liveReports[0]
        func exactOriginCount() -> Int {
            report.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", anchor.originLabels))
                .allElementsBoundByAccessibilityElement.filter {
                    let frame = $0.frame
                    return $0.exists && frame.width > 0 && frame.height > 0 &&
                        report.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) &&
                        window.frame.contains(CGPoint(x: frame.midX, y: frame.midY))
                }.count
        }
        let originVerified = exactOriginCount() == 1
        guard originVerified else {
            record(in: harness, stage: "xml-return-origin-report-wrong-nonce")
            XCTFail("The persistent report must expose exactly one canonical origin for the current filename nonce")
            throw Failure.wrongNonce
        }
        let controls = matching(try harness.values("action_close"), type: .button).filter {
            $0.exists && $0.isEnabled && $0.isHittable && $0.frame.width > 0 && $0.frame.height > 0 &&
                window.frame.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY))
        }
        guard controls.count == 1 else {
            record(in: harness, stage: "xml-return-origin-report-close-not-unique")
            XCTFail("The persistent origin report must have one reachable Close button in its window")
            throw Failure.ambiguousControl
        }
        let close = controls[0]
        let frame = close.frame
        record(in: harness, stage: "xml-return-origin-report-before-close", control: close)
        try requireLive(in: harness, expectedFilename: anchor.filename, stage: "xml-return-origin-report-recheck")
        guard report.exists, exactOriginCount() == 1, close.exists, close.isHittable, close.frame == frame else {
            record(in: harness, stage: "xml-return-origin-report-changed", control: close)
            XCTFail("The same report, exact origin, and admitted Close frame must remain before its single tap")
            throw Failure.wrongNonce
        }
        close.tap()
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let observed = self.editorObservation(in: harness.app, titleLabels: xmlTitleLabels)
            return DocumentWindowRules.canCompleteReturn(windowLive: observed.live, originVerified: originVerified,
                editorPresent: observed.editor, modalPresent: observed.modal)
        }, object: harness.app)
        guard XCTWaiter.wait(for: [closed], timeout: 10) == .completed else {
            record(in: harness, stage: "xml-return-origin-report-not-closed")
            XCTFail("Advance only after exact persistent origin verification, a positive editor, and both modals closed")
            throw Failure.modalNotClosed
        }
        record(in: harness, stage: "xml-return-origin-verified-editor")
    }

    func record(in harness: NativeUIHarness, stage: String, control: XCUIElement? = nil) {
        recordNumber += 1
        let observation = Self.observation(of: window, in: harness.app)
        var metadata: [String: Any] = ["scope": "nonce-bound-public-XCUI-window", "stage": stage,
            "filename": anchor.filename, "bindingID": bindingID,
            "unixSeconds": Date().timeIntervalSince1970,
            "systemUptimeSeconds": ProcessInfo.processInfo.systemUptime,
            "binding": "allElementsBoundByAccessibilityElement", "windowIdentifier": observation.exists ? window.identifier : "",
            "windowFrame": NSCoder.string(for: observation.frame), "windowLive": DocumentWindowRules.isLiveWindow(observation),
            "childCount": observation.childCount,
            "exactNonceAnchorVisibleNow": observation.labels.contains(where: anchor.matches),
            "nonceRevalidation": observation.labels.contains(where: anchor.matches) ? "exact-anchor-visible" : "retained-AX-window-binding; anchor-not-exposed",
            "qualification": "Window continuity and target admission only; no event delivery inferred."]
        if let control, control.exists {
            let frame = control.frame
            metadata["control"] = ["type": control.elementType.rawValue, "identifier": control.identifier,
                "label": control.label, "frame": NSCoder.string(for: frame),
                "enabled": control.isEnabled, "hittable": control.isHittable,
                "frameCenterCandidateOnly": ["x": frame.midX, "y": frame.midY]]
        }
        let name = "document-window-\(recordNumber)-\(stage)"
        if let data = try? JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys]),
           let text = String(data: data, encoding: .utf8) { harness.attach(text, name: name) }
        if observation.exists { harness.attach(window.debugDescription, name: name + "-window-tree") }
        harness.attach(harness.app.debugDescription, name: name + "-app-tree")
        harness.screenshot(name)
    }
}
