import Foundation
import UIKit
import XCTest

/// A real Files grant followed by provider authentication denial. No kernel-token revocation is claimed.
final class ProviderAuthenticationEndToEndTests: NativeUIHarness {
    private struct Run: Decodable {
        struct Main: Decodable { let bundleID: String; let source: String }
        struct Fixture: Decodable { let bundleID: String; let providerBundleID: String; let source: String }
        let schemaVersion: Int
        let runID: String
        let receiptDirectory: String
        let simulatorUDID: String?
        let main: Main
        let fixture: Fixture
        let fileName: String
        let providerDisplayName: String
        let initialSHA256: String
        let inputLabel: String
    }

    private var run: Run!
    private var fixture: ProviderFixtureController!
    private var phase = "not-started"
    private var denyAttempted = false
    private var completed = false
    private var records: [[String: Any]] = []

    override func setUpWithError() throws {
        try super.setUpWithError()
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        run = try JSONDecoder().decode(Run.self, from: Data(contentsOf: url))
        XCTAssertEqual(run.schemaVersion, 1)
        XCTAssertEqual(run.main.bundleID, "thalesmms.logisim.mobile")
        XCTAssertEqual(run.fixture.bundleID, "thalesmms.logisim.validation.revocationfixture")
        XCTAssertEqual(run.fixture.providerBundleID, run.fixture.bundleID + ".provider")
        XCTAssertEqual(run.fileName, "revocation.circ")
        XCTAssertEqual(run.providerDisplayName, "Logisim Revocation Fixture")
        XCTAssertEqual(run.inputLabel, "REVOCATION_INPUT")
        XCTAssertEqual(run.initialSHA256, ProviderAuthenticationOracle.initialSHA256)
        XCTAssertNotNil(run.runID.range(of: "^[a-f0-9]{12}$", options: .regularExpression))
        XCTAssertNotNil(UUID(uuidString: try XCTUnwrap(run.simulatorUDID)))
        fixture = ProviderFixtureController(harness: self)
        attach(String(decoding: try Data(contentsOf: url), as: UTF8.self), name: "provider-auth-run")
    }

    override func tearDownWithError() throws {
        if denyAttempted, let fixture {
            app?.terminate()
            do {
                let cleanup = try fixture.allowAuthenticationCleanup()
                try ProviderAuthenticationOracle.validate(cleanup)
                XCTAssertTrue(cleanup.state.authenticationAllowed, "Cleanup must restore fixture authentication through its public button")
                record(snapshot: cleanup, name: "cleanup-authentication-allowed")
            } catch {
                completed = false
                XCTFail("Public Allow authentication cleanup failed: \(error)")
                records.append(["phase": "cleanup-failed", "error": String(describing: error)])
            }
        }
        fixture?.terminate()
        if let run {
            let receipt: [String: Any] = [
                "schemaVersion": 1, "runID": run.runID, "phase": phase,
                "status": completed ? "SCENARIO_COMPLETED_PENDING_RUNNER_POSTFLIGHT" : "INCOMPLETE_OR_FAILED",
                "mainSource": run.main.source, "fixtureSource": run.fixture.source,
                "simulatorUDID": run.simulatorUDID ?? "MISSING",
                "route": "Files Edit external; persisted reference after terminate/launch",
                "authorizationScope": "provider authentication denied after grant; kernel security token revocation NOT_TESTED",
                "cohort": "Main plus Provider integration; no longer UIKit-only control",
                "observations": records,
            ]
            do {
                let data = try JSONSerialization.data(withJSONObject: receipt, options: [.prettyPrinted, .sortedKeys])
                addData(data, name: "provider-auth-scenario.json", type: "public.json")
            } catch {
                XCTFail("The complete provider authentication receipt could not be attached: \(error)")
            }
        }
        try super.tearDownWithError()
    }

    func testAuthenticationDenialAfterGrantPreservesProject() throws {
        executionTimeAllowance = 1200
        orient(.portrait)
        phase = "initial-before-preparatory-eviction"
        let initial = try fixture.snapshot()
        try ProviderAuthenticationOracle.initial(initial)
        record(snapshot: initial, name: phase)
        // A prior partial run can leave the document materialized. The public
        // Evict item action makes this run's subsequent fetch a fresh prerequisite.
        app.terminate()
        XCTAssertEqual(app.state, .notRunning)
        let baseline = try fixture.evictForFreshFetch(main: app)
        try ProviderAuthenticationOracle.evictedForFreshFetch(after: initial, in: baseline)
        record(snapshot: baseline, name: "preparatory-eviction-completed-authentication-retained")

        phase = "baseline-before-files"
        app.launch()
        try waitForEditor()
        try openFiles()
        try tapKey("action_edit_external")
        // This is the new provider's public Files location, never a local-peer fallback.
        try ProviderFilesNavigator(harness: self).selectDocument(
            locationDisplayName: run.providerDisplayName, filename: run.fileName)
        try waitForOpenedFixture()
        let initialXML = try readXML(name: "provider-auth-opened.circ")
        try ProviderCircuitDocument.read(initialXML).requireFixture(facing: "east")
        try assertSaveAvailable()
        let fetched = try fixture.observe(timeout: 30) {
            (try? ProviderAuthenticationOracle.fetched(after: baseline, in: $0)) != nil
        }
        let fetchProof = try ProviderAuthenticationOracle.fetched(after: baseline, in: fetched)
        record(snapshot: fetched, name: "real-provider-fetch", proof: fetchProof)

        phase = "first-enum-edit-and-upload"
        app.activate()
        try waitForEditor()
        try setFacing("south")
        let savedXML = try readXML(name: "provider-auth-before-upload.circ")
        try ProviderCircuitDocument.read(savedXML).requireFixture(facing: "south")
        XCTAssertNotEqual(savedXML, initialXML, "The first enum edit must change the complete project")
        _ = try saveFile()
        try waitForMessage("status_file_saved", arguments: ["name": run.fileName])
        let uploaded = try fixture.observe(timeout: 30) {
            (try? ProviderAuthenticationOracle.uploaded(xml: savedXML, after: fetched, in: $0)) != nil
        }
        let uploadProof = try ProviderAuthenticationOracle.uploaded(xml: savedXML, after: fetched, in: uploaded)
        record(snapshot: uploaded, name: "real-provider-upload", proof: uploadProof)

        // No denial action is reachable until a genuine fetch and backend upload passed above.
        phase = "terminate-then-evict-then-deny"
        app.terminate()
        XCTAssertEqual(app.state, .notRunning)
        denyAttempted = true
        let denied = try fixture.evictThenDeny(main: app)
        try ProviderAuthenticationOracle.evictedAndDenied(after: uploaded, in: denied)
        record(snapshot: denied, name: "eviction-completed-before-authentication-denied")

        phase = "restore-persisted-reference"
        app.launch()
        try waitForEditor()
        let restoredXML = try readXML(name: "provider-auth-restored-reference.circ")
        XCTAssertEqual(restoredXML, savedXML, "A real process restart must restore exactly the uploaded project")
        try assertSaveAvailable()
        try setFacing("north")
        let localXML = try readXML(name: "provider-auth-local-before-denied-save.circ")
        let localDocument = try ProviderCircuitDocument.read(localXML)
        try localDocument.requireFixture(facing: "north")
        try localDocument.requireOnlyFacingChanged(from: ProviderCircuitDocument.read(savedXML))
        XCTAssertNotEqual(localXML, savedXML)

        // A launch-time denial is not evidence for this Save: start a separate request boundary here.
        let beforeDeniedSave = try fixture.snapshot()
        try ProviderAuthenticationOracle.validate(beforeDeniedSave, preserving: denied)
        try ProviderAuthenticationOracle.unchangedBackend(uploaded.state, beforeDeniedSave.state)
        XCTAssertFalse(beforeDeniedSave.state.authenticationAllowed)
        record(snapshot: beforeDeniedSave, name: "immediately-before-denied-save")
        app.activate()
        try waitForEditor()
        XCTAssertEqual(try readXML(name: "provider-auth-local-after-fixture-observation.circ"), localXML)

        phase = "save-must-report-contextual-denial"
        let saveInvokedAt = try saveFile()
        try expectContextualFailure()
        let errorObservedAt = Date()
        let time = ISO8601DateFormatter()
        time.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        records.append(["phase": "denied-save-action-window", "saveInvokedAt": time.string(from: saveInvokedAt),
                        "contextualErrorObservedAt": time.string(from: errorObservedAt),
                        "association": "request start and terminal timestamps within public Save/error window; no Main request instrumentation"])
        screenshot("provider-auth-contextual-error")
        attach(app.debugDescription, name: "provider-auth-contextual-error-tree")
        try tapKey("action_understood")
        try waitUntil("The contextual error must close") {
            !self.matching(try self.values("action_failed_title")).firstMatch.exists
        }
        let afterErrorXML = try readXML(name: "provider-auth-local-after-denied-save.circ")
        XCTAssertEqual(afterErrorXML, localXML, "Failed authorization must preserve the complete unsaved local XML byte for byte")
        let deniedSave = try fixture.observe(timeout: 30) {
            (try? ProviderAuthenticationOracle.deniedRequest(after: beforeDeniedSave,
                notBefore: saveInvokedAt, notAfter: errorObservedAt, in: $0)) != nil
        }
        let denialProof = try ProviderAuthenticationOracle.deniedRequest(after: beforeDeniedSave,
            notBefore: saveInvokedAt, notAfter: errorObservedAt, in: deniedSave)
        try ProviderAuthenticationOracle.unchangedBackend(uploaded.state, deniedSave.state)
        record(snapshot: deniedSave, name: "real-provider-denial-backend-preserved", proof: denialProof)
        phase = "all-denied-phase-oracles-passed-before-cleanup"
        completed = true
    }

    private func openFiles() throws {
        try openEditorFileMenu()
    }

    private func assertSaveAvailable() throws {
        try openFiles()
        let save = try uniqueVisible(try values("action_save_file"), roles: [.button])
        XCTAssertTrue(save.isEnabled, "Edit external must retain a writable document association")
        dismissMenus()
    }

    private func saveFile() throws -> Date {
        try openFiles()
        let save = try uniqueVisible(try values("action_save_file"), roles: [.button])
        XCTAssertTrue(save.isEnabled)
        let invokedAt = Date()
        save.tap()
        return invokedAt
    }

    private func setFacing(_ direction: String) throws {
        try tapKey("a11y_project_tools", toolbar: "editor-project-toolbar")
        try reveal(values("a11y_explorer_title"), verticalRegion: "editor-tool-menu").tap()
        let list = identified("a11y-circuit-list")
        XCTAssertTrue(list.waitForExistence(timeout: 10))
        try reveal(localized("a11y_explorer_components"), verticalRegion: "a11y-circuit-list").tap()
        let edit = try localized("a11y_explorer_edit", arguments: ["name": run.inputLabel])
        try reveal(edit, verticalRegion: "a11y-circuit-list").tap()
        XCTAssertTrue(identified("editor-inspector-sheet").waitForExistence(timeout: 10))
        let facing = try localized("inspector_edit", nestedKey: "name", nestedValue: "attribute_label_facing")
        try uniqueVisible(facing, roles: [.button, .other]).tap()
        try uniqueVisible(try values("direction_" + direction), roles: [.button, .radioButton, .other]).tap()
        try uniqueVisible(try values("action_apply"), roles: [.button]).tap()
        let handles = app.buttons.matching(identifier: "editor-inspector-drag-handle").allElementsBoundByIndex.filter { visible($0) }
        XCTAssertEqual(handles.count, 1, "Use the actual visible inspector handle, never the empty sheet tag's frame")
        let handle = try XCTUnwrap(handles.first)
        XCTAssertTrue(try values("a11y_inspector_handle").contains { nativeLabel(handle.label, matches: $0) })
        XCTAssertTrue(handle.isEnabled)
        handle.tap() // Main3a's expanded sheet explicitly exposes dismiss on this public Button.
        try waitUntil("Inspector handle must dismiss its sheet") { !self.identified("editor-inspector-sheet").exists }
    }

    private func readXML(name: String) throws -> String {
        try tapKey("a11y_import_export_project", toolbar: "editor-project-toolbar")
        try waitForMessage("project_xml_title")
        let edit = try uniqueVisible(try values("xml_edit"), roles: [.button])
        edit.tap() // Tiny fixture only. No Copy/receiver or alternate route is admitted.
        func fields() -> [XCUIElement] {
            (app.textViews.allElementsBoundByIndex + app.textFields.allElementsBoundByIndex).filter {
                guard $0.exists, let value = $0.value as? String else { return false }
                return value.contains("<project") && value.contains("</project>")
            }
        }
        try waitUntil("One complete editable XML value must be presented") { fields().count == 1 }
        let field = try XCTUnwrap(fields().first)
        let xml = try XCTUnwrap(field.value as? String)
        _ = try ProviderCircuitDocument.read(xml)
        addData(Data(xml.utf8), name: name, type: "public.xml")
        try tapKey("action_close")
        let titles = try values("project_xml_title")
        try waitUntil("Close the read-only XML observation before the next action") { !self.matching(titles).firstMatch.exists }
        return xml
    }

    private func waitForEditor() throws {
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        try waitUntil("The editor must finish restoring its workspace", timeout: 20) {
            self.identified("editor-import-report").exists || self.identified("editor-project-toolbar").exists ||
            self.identified("editor-command-toolbar").exists
        }
        try closeImportReportIfPresented()
    }

    private func waitForOpenedFixture() throws {
        // "File NAME opened.", or with its compatibility adjustments when the report stayed closed (C145).
        let opened = try fileOpenedStatus(run.fileName)
        try waitUntil("The requested provider file must complete importing", timeout: 30) {
            let report = self.identified("editor-import-report")
            if report.exists {
                let requested = report.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", self.run.fileName))
                return requested.firstMatch.exists
            }
            return opened.exists
        }
        try closeImportReportIfPresented()
        try waitUntil("Present the opened-file status for \(run.fileName)") { opened.exists }
    }

    private func expectContextualFailure() throws {
        try waitForMessage("action_failed_title", timeout: 30)
        let patterns = try localized("ios_document_access_failed", arguments: ["name": run.fileName])
        let prefixes = patterns.map { $0.components(separatedBy: "{reason}")[0] }
        try waitUntil("The error must identify this document and retain a native reason") {
            self.matching(prefixes, prefix: true).allElementsBoundByIndex.contains { element in
                element.exists && prefixes.contains { element.label.hasPrefix($0) && element.label.count > $0.count }
            }
        }
        XCTAssertFalse(matching(try values("error_file_conflict")).firstMatch.exists,
                       "A version conflict is not provider authentication denial")
    }

    private func localized(_ key: String, arguments: [String: String] = [:],
                           nestedKey: String? = nil, nestedValue: String? = nil) throws -> [String] {
        let translations = try XCTUnwrap(catalog[key])
        return try translations.compactMap { language, raw in
            guard var value = raw as? String else { return nil }
            for (key, replacement) in arguments { value = value.replacingOccurrences(of: "{\(key)}", with: replacement) }
            if let nestedKey, let nestedValue {
                let replacement = try XCTUnwrap(catalog[nestedValue]?[language] as? String)
                value = value.replacingOccurrences(of: "{\(nestedKey)}", with: replacement)
            }
            return value
        }
    }

    private func waitForMessage(_ key: String, arguments: [String: String] = [:], timeout: TimeInterval = 10) throws {
        let names = try localized(key, arguments: arguments)
        try waitUntil("Present canonical \(key)", timeout: timeout) { self.matching(names).firstMatch.exists }
    }

    private func visible(_ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        guard [frame.minX, frame.minY, frame.width, frame.height].allSatisfy({ $0.isFinite }),
              frame.width > 0, frame.height > 0,
              app.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) else { return false }
        return element.isHittable
    }

    private func uniqueVisible(_ names: [String], roles: [XCUIElement.ElementType]) throws -> XCUIElement {
        var found: [XCUIElement] = []
        try waitUntil("Exactly one visible native control for \(names)") {
            found = self.matching(names).allElementsBoundByIndex.filter { roles.contains($0.elementType) && self.visible($0) }
            if found.count > 1 { throw ProviderAuthenticationOracle.Failure(description: "Ambiguous visible controls for \(names): \(found.count)") }
            return found.count == 1
        }
        let result = try XCTUnwrap(found.first)
        XCTAssertTrue(result.isEnabled)
        return result
    }

    private func waitUntil(_ description: String, timeout: TimeInterval = 10, condition: @escaping () throws -> Bool) throws {
        var failure: Error?
        let predicate = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            do { return try condition() } catch { failure = error; return true }
        }, object: app)
        let result = XCTWaiter.wait(for: [predicate], timeout: timeout)
        if let failure { throw failure }
        guard result == .completed else {
            screenshot("provider-auth-condition-failed")
            attach(app.debugDescription, name: "provider-auth-condition-failed-tree")
            throw ProviderAuthenticationOracle.Failure(description: description)
        }
    }

    private func record(snapshot: ProviderFixtureSnapshot, name: String,
                        proof: ProviderAuthenticationOracle.RequestProof? = nil) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let bytes = try? encoder.encode(snapshot) { addData(bytes, name: name + ".json", type: "public.json") }
        var record: [String: Any] = ["phase": name, "lastSequence": snapshot.lastSequence,
            "backendSHA256": snapshot.state.backendSHA256, "backendBytes": snapshot.state.backendByteCount,
            "generation": snapshot.state.generation, "authenticationAllowed": snapshot.state.authenticationAllowed]
        if let proof, let bytes = try? encoder.encode(proof), let object = try? JSONSerialization.jsonObject(with: bytes) {
            record["requestProof"] = object
        }
        records.append(record)
    }

    private func addData(_ data: Data, name: String, type: String) {
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: type)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
