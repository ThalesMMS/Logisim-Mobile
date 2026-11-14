import XCTest
import UIKit

/// Exercises the shipping URL handler and native controllers. No editor test hook is used.
final class FilesEndToEndTests: NativeUIHarness {
    private var run: [String: Any] = [:]

    override func setUpWithError() throws {
        try super.setUpWithError()
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        run = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    private func fixture(_ name: String) throws -> URL {
        let paths = try XCTUnwrap(run["fixtures"] as? [String: String])
        return URL(fileURLWithPath: try XCTUnwrap(paths[name]))
    }

    private func title(_ name: String) throws -> String {
        try XCTUnwrap((run["titles"] as? [String: String])?[name])
    }

    private func expectDocument(_ name: String, timeout: TimeInterval = 20) throws {
        let title = try title(name)
        let visibleTitle = matching([title]).firstMatch
        let report = identified("editor-import-report")
        let reportedCircuit = report.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", title))
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            let presented = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                report.exists ? reportedCircuit.firstMatch.exists : visibleTitle.exists
            }, object: app)
            let remaining = deadline.timeIntervalSinceNow
            XCTAssertGreaterThan(remaining, 0, "The requested circuit did not become visible")
            XCTAssertEqual(XCTWaiter.wait(for: [presented], timeout: max(remaining, 0.1)), .completed,
                           "The requested circuit must become visible after the system open")
            if report.exists {
                try closeImportReportIfPresented()
                continue
            }
            XCTAssertTrue(visibleTitle.exists)
            break
        }
        screenshot("document-\(name)")
        attach(app.debugDescription, name: "document-\(name)-tree")
    }

    private func expectMessage(_ key: String, filename: String? = nil) throws {
        let labels = try values(key).map { value in
            filename.map { value.replacingOccurrences(of: "{name}", with: $0) } ?? value
        }
        XCTAssertTrue(matching(labels).firstMatch.waitForExistence(timeout: 20), "Missing result for \(key)")
    }

    private func openFileMenu() throws {
        try openEditorFileMenu()
    }

    private func closeError() throws {
        try tapKey("action_understood")
    }

    private func cancelNativeController(sharing: Bool) throws {
        // Labels are taken from the shared catalog; native UIKit uses these same localized actions.
        let labels = try values("action_cancel") + values("action_close")
        let predicate = NSCompoundPredicate(orPredicateWithSubpredicates: labels.map { NSPredicate(format: "label == %@", $0) })
        let cancel = app.buttons.matching(predicate).firstMatch
        let picker = identified("Browse View (Picker)")
        let activity = identified("ActivityListView")
        let outside = identified("PopoverDismissRegion")
        let phone = UIDevice.current.userInterfaceIdiom == .phone
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            if phone && sharing { return activity.exists && outside.exists }
            return (cancel.exists && cancel.isHittable) || (phone && picker.exists)
        }, object: app)
        _ = XCTWaiter.wait(for: [ready], timeout: 15)
        if phone && sharing {
            let window = app.frame
            let sheet = activity.frame.intersection(window)
            guard activity.exists, outside.exists, !sheet.isEmpty, sheet.minY > window.minY + 44 else {
                XCTFail("The share sheet must leave a visible dismiss region above it")
                return
            }
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
                dx: window.width / 2, dy: (sheet.minY - window.minY) / 2)).tap()
        } else if cancel.exists && cancel.isHittable {
            screenshot("native-controller-before-cancel")
            attach(app.debugDescription, name: "native-controller-before-cancel-tree")
            cancel.tap()
        } else if phone && picker.exists {
            let window = app.frame
            let sheet = picker.frame.intersection(window)
            guard !sheet.isEmpty, sheet.minY > window.minY, sheet.height > 64 else {
                XCTFail("The Files picker must have a visible sheet top to swipe from")
                return
            }
            let origin = app.coordinate(withNormalizedOffset: .zero)
            let x = sheet.midX - window.minX
            let start = origin.withOffset(CGVector(dx: x, dy: sheet.minY - window.minY + 8))
            let end = origin.withOffset(CGVector(dx: x, dy: sheet.midY - window.minY))
            start.press(forDuration: 0.1, thenDragTo: end)
        } else {
            XCTAssertTrue(outside.waitForExistence(timeout: 8), "Native controller exposes no cancel action")
            XCTAssertTrue(UIDevice.current.userInterfaceIdiom == .pad,
                          "A phone sheet must expose an explicit native cancel action")
            screenshot("native-popover-before-cancel")
            attach(app.debugDescription, name: "native-popover-before-cancel-tree")
            // UIKit's dismiss region extends beyond the screen. Anchor the touch to the
            // observed application frame instead of normalizing the oversized region.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.03, dy: 0.97)).tap()
        }
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !outside.exists && !activity.exists && !cancel.exists && !picker.exists
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 10), .completed,
                       "The native controller must actually disappear; background canvas existence is insufficient")
        XCTAssertTrue(identified("editor-canvas").exists)
    }

    func testColdAndWarmOpenWithUsesTheSystemDocumentAssociation() throws {
        app.terminate()
        XCTAssertEqual(app.state, .notRunning)
        XCUIDevice.shared.system.open(try fixture("cold"))
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        try expectDocument("cold")
        XCTAssertEqual(app.state, .runningForeground)
        XCUIDevice.shared.system.open(try fixture("warm"))
        try expectDocument("warm")
        attach("Cold open began with notRunning; warm open began with runningForeground.", name: "open-with-states")
    }

    func testExternalSaveReopensAndPreservesAConflictingRevision() throws {
        let url = try fixture("external")
        XCUIDevice.shared.system.open(url)
        try expectDocument("external")
        try openFileMenu()
        let save = try reveal(values("action_save_file"))
        XCTAssertTrue(save.isEnabled, "System Open With must retain an external document reference")
        save.tap()
        try expectMessage("ios_file_saved_on_device", filename: url.lastPathComponent)
        let saved = try Data(contentsOf: url)
        add(XCTAttachment(data: saved, uniformTypeIdentifier: "org.logisim-evolution.circuit"))
        app.terminate()
        app.launch()
        try expectDocument("external")
        var changed = saved
        changed.append(Data("\n<!-- E2E external revision: preserve me -->\n".utf8))
        try changed.write(to: url, options: .atomic)
        try openFileMenu()
        try tapKey("action_save_file")
        try expectMessage("error_file_conflict")
        XCTAssertEqual(try Data(contentsOf: url), changed, "A stale save overwrote the external document")
        screenshot("external-conflict")
        let file = XCTAttachment(data: try Data(contentsOf: url), uniformTypeIdentifier: "org.logisim-evolution.circuit")
        file.name = "external-document-after-conflict"
        file.lifetime = .keepAlways
        add(file)
        try closeError()
        try expectDocument("external")
    }

    func testMissingExternalDocumentReportsFailureAndPreservesProject() throws {
        let source = try fixture("external")
        let documents = URL(fileURLWithPath: try XCTUnwrap(run["documents"] as? String), isDirectory: true)
        let missing = documents.appendingPathComponent("missing-external.circ")
        XCTAssertEqual(source.deletingLastPathComponent().standardizedFileURL, documents.standardizedFileURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: missing.path), "Each run owns a fresh missing-file fixture")
        let original = try Data(contentsOf: source)
        try original.write(to: missing, options: .withoutOverwriting)
        let input = XCTAttachment(data: original, uniformTypeIdentifier: "org.logisim-evolution.circuit")
        input.name = "missing-external-original.circ"
        input.lifetime = .keepAlways
        add(input)

        // This is a local missing-file test, not a revoked file-provider grant.
        let runID = try XCTUnwrap(run["run_id"] as? String)
        XCTAssertEqual(documents.lastPathComponent, runID)
        try openFileMenu()
        try tapKey("action_edit_external")
        try NativeFilesPeerNavigator(harness: self, peerDisplayName: app.label, runID: runID)
            .open(filename: missing.lastPathComponent)
        try waitForMissingFixtureOrigin(filename: missing.lastPathComponent)
        try expectDocument("external")
        try openFileMenu()
        let associatedSave = try reveal(values("action_save_file"))
        XCTAssertTrue(associatedSave.isEnabled, "Files Edit external must retain the selected document reference before deletion")
        dismissMenus()
        XCTAssertEqual(try Data(contentsOf: missing), original, "The picker setup must preserve the selected source bytes")
        XCTAssertEqual(try Data(contentsOf: source), original, "The picker setup must preserve the untouched control fixture")
        attach("route=Files Edit external\nfilename=\(missing.lastPathComponent)\nfixture=\(missing.path)\nexternalAssociation=Save File enabled before deletion",
               name: "missing-external-picker-setup")
        let before = try observedProjectXML(attachmentName: "before-missing-external.circ")
        try FileManager.default.removeItem(at: missing)
        XCTAssertFalse(FileManager.default.fileExists(atPath: missing.path))
        try openFileMenu()
        let save = try reveal(values("action_save_file"))
        XCTAssertTrue(save.isEnabled, "The external association must exist before the missing-file write is attempted")
        save.tap()

        try expectMessage("action_failed_title")
        XCTAssertTrue(matching(try values("action_understood")).firstMatch.waitForExistence(timeout: 10))
        let canonicalFailures = try values("ios_provider_unavailable") + values("ios_access_expired") + values("ios_provider_no_destination")
        let failurePredicates = canonicalFailures.map { NSPredicate(format: "label == %@", $0) } + [
            NSPredicate(format: "label CONTAINS %@", missing.lastPathComponent),
            NSPredicate(format: "label CONTAINS %@", missing.deletingPathExtension().lastPathComponent),
        ]
        let failure = app.descendants(matching: .any)
            .matching(NSCompoundPredicate(orPredicateWithSubpredicates: failurePredicates)).firstMatch
        XCTAssertTrue(failure.waitForExistence(timeout: 10),
                      "The error must identify the missing file or a canonical provider/access failure; an unrelated alert is insufficient")
        XCTAssertFalse(matching(try values("error_file_conflict")).firstMatch.exists,
                       "A missing document is an access failure, not a revision conflict")
        XCTAssertFalse(FileManager.default.fileExists(atPath: missing.path), "Failed Save must not recreate the removed document")
        screenshot("missing-external-error")
        attach(app.debugDescription, name: "missing-external-error-tree")
        try closeError()
        try expectDocument("external")
        XCTAssertEqual(try observedProjectXML(attachmentName: "after-missing-external.circ"), before,
                       "A failed external save changed the actual local project XML")
        XCTAssertFalse(FileManager.default.fileExists(atPath: missing.path))
        XCTAssertEqual(try Data(contentsOf: source), original, "The test must leave its source fixture intact")
        checkFrame(try canvas(), name: "canvas-after-missing-external")
        try openFileMenu()
        dismissMenus()
        XCTAssertTrue(identified("editor-canvas").exists, "The editor must remain usable after acknowledging the failure")
    }

    private func waitForMissingFixtureOrigin(filename: String) throws {
        let opened = try fileOpenedStatus(filename, within: app)
        let origins = try values("import_report_origin").map { $0.replacingOccurrences(of: "{origin}", with: filename) }
        let report = identified("editor-import-report")
        func exactOrigin() -> Bool {
            report.descendants(matching: .staticText).matching(NSPredicate(format: "label IN %@", origins)).firstMatch.exists
        }
        func exactOpenedStatus() -> Bool { opened.exists }
        let deadline = Date().addingTimeInterval(20)
        while true {
            let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                report.exists ? exactOrigin() : exactOpenedStatus()
            }, object: app)
            let remaining = deadline.timeIntervalSinceNow
            guard remaining > 0, XCTWaiter.wait(for: [ready], timeout: remaining) == .completed else {
                screenshot("missing-external-picker-origin-missing")
                attach(app.debugDescription, name: "missing-external-picker-origin-missing-tree")
                XCTFail("Edit external must expose the exact selected filename in its import origin or opened-file status")
                throw HarnessFailure.missingControl
            }
            if report.exists {
                guard exactOrigin() else { continue }
                screenshot("missing-external-picker-import-report")
                attach(app.debugDescription, name: "missing-external-picker-import-report-tree")
                guard report.exists && exactOrigin() else { continue }
                try tapKey("action_close")
                let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !report.exists }, object: app)
                XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 10), .completed,
                               "Close only the import report for the selected missing-file fixture")
                continue
            }
            XCTAssertTrue(exactOpenedStatus())
            break
        }
    }

    func testIncompatibleImportPreservesTheCurrentProject() throws {
        XCUIDevice.shared.system.open(try fixture("warm"))
        try expectDocument("warm")
        let before = try observedProjectXML(attachmentName: "before-incompatible.circ")
        XCUIDevice.shared.system.open(try fixture("incompatible"))
        let notice = matching(try values("action_understood")).firstMatch
        XCTAssertTrue(notice.waitForExistence(timeout: 20), "An incompatible document must report an error")
        screenshot("incompatible-document-error")
        try closeError()
        try expectDocument("warm")
        XCTAssertEqual(try observedProjectXML(attachmentName: "after-incompatible.circ"), before,
                       "Incompatible import changed the actual circuit export")
    }

    func testCancellingImportExportAndSharingPreservesTheProject() throws {
        XCUIDevice.shared.system.open(try fixture("warm"))
        try expectDocument("warm")
        let before = try observedProjectXML(attachmentName: "before-native-cancellations.circ")
        for key in ["action_import_copy", "action_save_as", "action_share"] {
            try openFileMenu()
            try tapKey(key)
            screenshot("native-\(key)")
            try cancelNativeController(sharing: key == "action_share")
            try expectDocument("warm")
            XCTAssertEqual(try observedProjectXML(attachmentName: "after-\(key)-cancel.circ"), before,
                           "Cancellation changed the actual circuit export")
        }
    }
}
