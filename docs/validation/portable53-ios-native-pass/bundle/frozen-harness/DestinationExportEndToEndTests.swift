import XCTest
import UIKit

/// Destination controls must be checked against the current native Files tree during execution.
/// The test uses production controls, the system URL association, and files actually written by Files.
final class DestinationExportEndToEndTests: NativeUIHarness {
    private struct RunConfiguration: Decodable {
        let run_id: String
        let fixtures: [String: String]
        let titles: [String: String]
        let documents: String
    }

    private var run: RunConfiguration!
    private var stage = 0

    override func setUpWithError() throws {
        try super.setUpWithError()
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        run = try JSONDecoder().decode(RunConfiguration.self, from: Data(contentsOf: url))
        XCTAssertNotNil(run.run_id.range(of: "^[a-f0-9]{12}$", options: .regularExpression))
        let documents = URL(fileURLWithPath: run.documents, isDirectory: true).standardizedFileURL
        XCTAssertEqual(documents.lastPathComponent, run.run_id)
        XCTAssertEqual(documents.deletingLastPathComponent().lastPathComponent, "LogisimE2E")
        XCTAssertEqual(documents.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent, "Documents")
    }

    func testSaveAsChoosesARealFilesDestinationAndReopensTheExport() throws {
        let warm = try fixture("warm")
        let cold = try fixture("cold")
        let warmTitle = try XCTUnwrap(run.titles["warm"])
        let coldTitle = try XCTUnwrap(run.titles["cold"])
        let originalWarm = try Data(contentsOf: warm)
        let originalCold = try Data(contentsOf: cold)
        let filename = warmTitle + ".circ"
        let destination = URL(fileURLWithPath: run.documents, isDirectory: true).appendingPathComponent(filename)
        XCTAssertEqual(destination.lastPathComponent, filename, "The suggested name must be a filename, not a path")
        XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path),
                       "Use a fresh run. An existing destination cannot prove a new export")

        record("before-system-open-warm")
        XCUIDevice.shared.system.open(warm)
        try waitForProject(warmTitle)
        record("before-reading-warm-xml")
        let before = try observedProjectXML(attachmentName: "destination-before-export.circ")
        let expected = Data(before.utf8)

        try openFileMenu()
        try tapAppButton("action_save_as", step: "before-save-as")
        let picker = NativeFilesDestinationNavigator(harness: self,
            documentsDirectory: URL(fileURLWithPath: run.documents, isDirectory: true), runID: run.run_id)
        XCTAssertEqual(try picker.completeExport(expectedFilename: filename), destination)
        try waitForExport(destination, expected: expected)
        record("after-export-before-reading-editor-xml")
        XCTAssertEqual(try observedProjectXML(attachmentName: "destination-after-export.circ"), before,
                       "Completing native Save As changed the actual project")
        try assertSaveEnabledAndCloseMenu(stageName: "after-native-export")
        XCTAssertEqual(try Data(contentsOf: warm), originalWarm, "Save As overwrote its warm source document")
        XCTAssertEqual(try Data(contentsOf: cold), originalCold, "Save As changed the cold control fixture")

        record("before-system-open-cold-control")
        XCUIDevice.shared.system.open(cold)
        try waitForProject(coldTitle)
        record("before-reading-cold-control-xml")
        let coldXML = try observedProjectXML(attachmentName: "destination-cold-control.circ")
        XCTAssertNotEqual(coldXML, before, "The control open must actually replace the warm project")

        record("before-system-open-exported-warm")
        XCUIDevice.shared.system.open(destination)
        try waitForProject(warmTitle)
        record("before-reading-reopened-export-xml")
        XCTAssertEqual(try observedProjectXML(attachmentName: "destination-reopened-export.circ"), before,
                       "The system URL association did not reopen the bytes exported through Files")
        try assertSaveEnabledAndCloseMenu(stageName: "after-reopening-export")
        XCTAssertEqual(try Data(contentsOf: destination), expected, "Reopening changed the exported file")
        XCTAssertEqual(try Data(contentsOf: warm), originalWarm)
        XCTAssertEqual(try Data(contentsOf: cold), originalCold)
        record("completed-native-destination-roundtrip")
    }

    private func fixture(_ name: String) throws -> URL {
        let url = URL(fileURLWithPath: try XCTUnwrap(run.fixtures[name])).standardizedFileURL
        XCTAssertEqual(url.deletingLastPathComponent(), URL(fileURLWithPath: run.documents, isDirectory: true).standardizedFileURL)
        return url
    }

    private func record(_ name: String) {
        stage += 1
        let label = String(format: "%02d-%@", stage, name)
        screenshot(label)
        attach(app.debugDescription, name: label + "-tree")
    }

    private func exact(_ labels: [String]) -> NSPredicate {
        NSCompoundPredicate(orPredicateWithSubpredicates: labels.map { NSPredicate(format: "label == %@", $0) })
    }

    private func canonical(_ labels: [String]) -> NSPredicate {
        NSCompoundPredicate(orPredicateWithSubpredicates: labels.map {
            NSPredicate(format: "label == %@ OR label BEGINSWITH %@", $0, $0 + ", ")
        })
    }

    private func visible(_ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        guard frame.width > 0, frame.height > 0, frame.midX.isFinite, frame.midY.isFinite,
              app.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) else { return false }
        return element.isHittable
    }

    private func waitForUnique(_ description: String, timeout: TimeInterval = 15,
                               elements: @escaping () -> [XCUIElement]) throws -> XCUIElement {
        // Wait only for appearance. Multiple observed matches must fail, never become a first-match tap.
        let appeared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !elements().isEmpty }, object: app)
        let result = XCTWaiter.wait(for: [appeared], timeout: timeout)
        if result != .completed { record("missing-" + description) }
        XCTAssertEqual(result, .completed, "The native UI did not expose \(description)")
        let found = elements()
        if found.count != 1 { record("ambiguous-" + description) }
        XCTAssertEqual(found.count, 1, "Expected one visible \(description), found \(found.count)")
        return try XCTUnwrap(found.count == 1 ? found[0] : nil)
    }

    private func waitForExport(_ url: URL, expected: Data) throws {
        record("before-reading-native-destination-file")
        var observed: Data?
        let written = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            observed = try? Data(contentsOf: url)
            return observed == expected
        }, object: app)
        let result = XCTWaiter.wait(for: [written], timeout: 25)
        if let bytes = observed {
            let attachment = XCTAttachment(data: bytes, uniformTypeIdentifier: "org.logisim-evolution.circuit")
            attachment.name = "native-destination-" + url.lastPathComponent
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        XCTAssertEqual(result, .completed, "Files did not write the expected XML at \(url.path)")
        XCTAssertEqual(try Data(contentsOf: url), expected)
    }

    private func waitForProject(_ title: String) throws {
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        let report = identified("editor-import-report")
        let labels = [title]
        let deadline = Date().addingTimeInterval(20)
        while true {
            let appeared = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
                if report.exists {
                    return report.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", title))
                        .allElementsBoundByIndex.contains { $0.exists }
                }
                return self.matching(labels).allElementsBoundByIndex.contains(where: self.visible)
            }, object: app)
            let remaining = deadline.timeIntervalSinceNow
            XCTAssertGreaterThan(remaining, 0, "The requested document did not finish its observed import")
            XCTAssertEqual(XCTWaiter.wait(for: [appeared], timeout: max(0.1, remaining)), .completed)
            if report.exists {
                record("requested-project-import-report")
                try closeImportReportIfPresented()
                continue
            }
            break
        }
        record("opened-project-" + title)
    }

    private func tapAppButton(_ key: String, step: String, toolbar: String? = nil) throws {
        record(step)
        let labels = try values(key)
        _ = try reveal(labels, toolbar: toolbar)
        let control = try waitForUnique("app action \(key)") {
            self.app.buttons.matching(self.canonical(labels)).allElementsBoundByIndex.filter(self.visible)
        }
        XCTAssertTrue(control.isEnabled)
        record(step + "-ready-to-tap")
        control.tap()
    }

    private func openFileMenu() throws {
        try tapAppButton("a11y_project_files", step: "before-opening-file-menu", toolbar: "editor-project-toolbar")
        let labels = try values("project_files_title")
        _ = try waitForUnique("project file menu title") {
            self.app.staticTexts.matching(self.exact(labels)).allElementsBoundByIndex.filter(self.visible)
        }
    }

    private func assertSaveEnabledAndCloseMenu(stageName: String) throws {
        try openFileMenu()
        record(stageName + "-before-checking-save-reference")
        let labels = try values("action_save_file")
        let save = try waitForUnique("Save in file action") {
            self.app.buttons.matching(self.canonical(labels)).allElementsBoundByIndex.filter(self.visible)
        }
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: save)
        XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 15), .completed,
                       "The exported document must retain a writable external reference")
        XCTAssertTrue(save.isEnabled)
        try tapAppButton("action_close", step: stageName + "-before-closing-file-menu")
    }
}
