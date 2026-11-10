import CryptoKit
import Foundation
import XCTest
import UIKit

/// Exercises the local Files provider of a separate fixture app. This is not grant revocation.
final class ExternalProviderEndToEndTests: NativeUIHarness {
    private struct Run: Decodable {
        let run_id: String
        let documents: String
    }

    private struct PeerEnvelope: Decodable {
        let externalPeer: Peer
    }

    private struct Peer: Decodable {
        let bundleID: String
        let displayName: String
        let documents: String
        let fixture: String
        let control: String
        let originalSHA256: String
    }

    private var run: Run!
    private var peer: Peer!
    private var original = Data()
    private var originalControl = Data()
    private var projectTitle = ""
    private var phase = "not-started"
    private var observations: [[String: Any]] = []

    override func setUpWithError() throws {
        try super.setUpWithError()
        let resource = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        run = try JSONDecoder().decode(Run.self, from: Data(contentsOf: resource))
        let documents = URL(fileURLWithPath: run.documents, isDirectory: true).standardizedFileURL
        XCTAssertNotNil(run.run_id.range(of: "^[a-f0-9]{12}$", options: .regularExpression))
        XCTAssertEqual(documents.lastPathComponent, run.run_id)
        XCTAssertEqual(documents.deletingLastPathComponent().lastPathComponent, "LogisimE2E")
        XCTAssertEqual(documents.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent, "Documents")
        let sidecar = documents.appendingPathComponent("external-peer.json")
        let sidecarData = try Data(contentsOf: sidecar)
        peer = try JSONDecoder().decode(PeerEnvelope.self, from: sidecarData).externalPeer
        XCTAssertEqual(peer.bundleID, "thalesmms.logisim.validation.clipboardreceiver")
        XCTAssertFalse(peer.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        let peerDocuments = URL(fileURLWithPath: peer.documents, isDirectory: true).standardizedFileURL
        XCTAssertEqual(peerDocuments.lastPathComponent, run.run_id)
        XCTAssertEqual(peerDocuments.deletingLastPathComponent().lastPathComponent, "LogisimE2E")
        XCTAssertEqual(peerDocuments.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent, "Documents")
        let mainContainer = documents.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let peerContainer = peerDocuments.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        XCTAssertNotEqual(mainContainer.resolvingSymlinksInPath(), peerContainer.resolvingSymlinksInPath(),
                          "The peer fixture must be outside the production app container")
        XCTAssertEqual(fixtureURL.deletingLastPathComponent(), peerDocuments)
        XCTAssertEqual(controlURL.deletingLastPathComponent(), peerDocuments)
        XCTAssertEqual(fixtureURL.lastPathComponent, "peer.circ")
        XCTAssertEqual(controlURL.lastPathComponent, "control.circ")
        for url in [peerDocuments, fixtureURL, controlURL] {
            XCTAssertFalse(try url.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink ?? false,
                           "Only this run's regular peer fixtures may be read or changed")
        }
        original = try Data(contentsOf: fixtureURL)
        originalControl = try Data(contentsOf: controlURL)
        XCTAssertEqual(originalControl, original, "The untouched control starts with exactly the peer input bytes")
        XCTAssertNotNil(peer.originalSHA256.range(of: "^[a-f0-9]{64}$", options: .regularExpression))
        XCTAssertEqual(peerSHA(original), peer.originalSHA256)
        let fixture = try PeerProject.read(try XCTUnwrap(String(data: original, encoding: .utf8)))
        projectTitle = try XCTUnwrap(fixture.main)
        XCTAssertEqual(projectTitle, "IOS-PEER-" + run.run_id)
        XCTAssertEqual(fixture.inputLabel, "E2E_IN")
        attach(String(decoding: sidecarData, as: UTF8.self), name: "external-peer-configuration")
        attachFile(original, name: "external-peer-original.circ")
    }

    override func tearDownWithError() throws {
        attach("phase=\(phase); scope=separate-app-local-Files; grantRevocation=NOT_TESTED", name: "external-peer-last-phase")
        if let peer {
            if let bytes = try? Data(contentsOf: URL(fileURLWithPath: peer.fixture)) {
                attachFile(bytes, name: "external-peer-final.circ")
            }
            if let bytes = try? Data(contentsOf: URL(fileURLWithPath: peer.control)) {
                attachFile(bytes, name: "external-peer-control-final.circ")
            }
        }
        try super.tearDownWithError()
    }

    func testPeerDocumentCopyEditRestartAndConflict() throws {
        orient(.portrait)
        record("before-native-import-copy")
        try choosePeer(modeKey: "action_import_copy")
        try assertSaveEnabled(false)
        let baseline = try observedProjectXML(attachmentName: "peer-copy-baseline.circ")
        XCTAssertEqual(try PeerProject.read(baseline).inputLabel, "E2E_IN")
        assertOriginalsUnchanged()

        let copiedLabel = "PEER_COPY_" + run.run_id
        try editInputLabel(from: "E2E_IN", to: copiedLabel)
        let copied = try observedProjectXML(attachmentName: "peer-copy-edited.circ")
        try assertOnlyInputLabelChanged(before: baseline, after: copied, to: copiedLabel)
        try assertSaveEnabled(false)
        assertOriginalsUnchanged()
        observe("copy-edited", xml: copied, source: original)

        record("before-native-edit-in-place")
        try choosePeer(modeKey: "action_edit_external")
        try assertSaveEnabled(true)
        XCTAssertEqual(try observedProjectXML(attachmentName: "peer-external-baseline.circ"), baseline,
                       "Edit external must load the peer source, replacing the independently edited copy")
        assertOriginalsUnchanged()

        let editedLabel = "PEER_EDIT_" + run.run_id
        try editInputLabel(from: "E2E_IN", to: editedLabel)
        let edited = try observedProjectXML(attachmentName: "peer-external-edited.circ")
        try assertOnlyInputLabelChanged(before: baseline, after: edited, to: editedLabel)
        try savePeer(expectedXML: edited)
        XCTAssertEqual(try Data(contentsOf: controlURL), originalControl)
        observe("external-saved", xml: edited, source: Data(edited.utf8))

        record("before-process-restart-without-open-url")
        app.terminate()
        XCTAssertEqual(app.state, .notRunning)
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        try waitForProject()
        XCTAssertEqual(try observedProjectXML(attachmentName: "peer-restored-after-restart.circ"), edited,
                       "Restart must restore the saved project without a picker or system Open With")
        try assertSaveEnabled(true)
        let restartedLabel = "PEER_RESTART_" + run.run_id
        try editInputLabel(from: editedLabel, to: restartedLabel)
        let restarted = try observedProjectXML(attachmentName: "peer-restarted-edited.circ")
        try assertOnlyInputLabelChanged(before: edited, after: restarted, to: restartedLabel)
        try savePeer(expectedXML: restarted)
        XCTAssertEqual(try Data(contentsOf: controlURL), originalControl)
        observe("bookmark-saved-after-restart", xml: restarted, source: Data(restarted.utf8))

        record("before-external-revision-conflict")
        var externalRevision = Data(restarted.utf8)
        externalRevision.append(Data("\n<!-- Peer provider revision \(run.run_id): preserve exactly -->\n".utf8))
        try externalRevision.write(to: fixtureURL, options: .atomic)
        XCTAssertEqual(try Data(contentsOf: fixtureURL), externalRevision)
        try openFileMenu()
        try tapKey("action_save_file")
        try expectMessage("error_file_conflict")
        record("external-revision-conflict-presented")
        XCTAssertEqual(try Data(contentsOf: fixtureURL), externalRevision, "A stale Save overwrote the peer provider revision")
        try tapKey("action_understood")
        XCTAssertEqual(try observedProjectXML(attachmentName: "peer-local-after-conflict.circ"), restarted,
                       "The provider conflict changed the exact local project XML")
        XCTAssertEqual(try Data(contentsOf: fixtureURL), externalRevision)
        XCTAssertEqual(try Data(contentsOf: controlURL), originalControl)
        checkFrame(try canvas(), name: "peer-canvas-after-conflict")
        observe("conflict-preserved", xml: restarted, source: externalRevision)
        attachFile(externalRevision, name: "peer-conflicting-provider-revision.circ")
        record("completed-local-peer-provider-scenario")
        try writeReceipt()
    }

    private var fixtureURL: URL { URL(fileURLWithPath: peer.fixture).standardizedFileURL }
    private var controlURL: URL { URL(fileURLWithPath: peer.control).standardizedFileURL }

    private func choosePeer(modeKey: String) throws {
        try openFileMenu()
        try tapKey(modeKey)
        try NativeFilesPeerNavigator(harness: self, peerDisplayName: peer.displayName, runID: run.run_id)
            .open(filename: fixtureURL.lastPathComponent)
        try waitForProject()
    }

    private func waitForProject() throws {
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        let deadline = Date().addingTimeInterval(20)
        while true {
            let report = identified("editor-import-report")
            let imported = report.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS %@", projectTitle))
            let presented = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
                report.exists ? imported.firstMatch.exists : self.matching([self.projectTitle]).firstMatch.exists
            }, object: app)
            let remaining = deadline.timeIntervalSinceNow
            XCTAssertGreaterThan(remaining, 0, "The peer document must finish importing")
            XCTAssertEqual(XCTWaiter.wait(for: [presented], timeout: max(0.1, remaining)), .completed)
            if report.exists { try closeImportReportIfPresented(); continue }
            break
        }
    }

    private func openFileMenu() throws {
        try tapKey("a11y_project_files", toolbar: "editor-project-toolbar")
        XCTAssertTrue(matching(try values("project_files_title")).firstMatch.waitForExistence(timeout: 5))
    }

    private func assertSaveEnabled(_ enabled: Bool) throws {
        try openFileMenu()
        let save = try fileSaveButton()
        waitFor("Save File enabled=\(enabled)") { save.exists && save.isEnabled == enabled }
        XCTAssertEqual(save.isEnabled, enabled, "Copy and Edit external must retain distinct write associations")
        try tapKey("action_close")
    }

    private func savePeer(expectedXML: String) throws {
        record("before-saving-peer")
        let before = try Data(contentsOf: fixtureURL)
        let expected = Data(expectedXML.utf8)
        XCTAssertNotEqual(before, expected, "The bookmark write must change actual source bytes")
        try openFileMenu()
        let save = try fileSaveButton()
        XCTAssertTrue(save.isEnabled)
        save.tap()
        try expectMessage("status_file_saved", filename: fixtureURL.lastPathComponent)
        waitFor("Peer source contains the complete saved XML") { (try? Data(contentsOf: self.fixtureURL)) == expected }
        XCTAssertEqual(try Data(contentsOf: fixtureURL), expected)
    }

    private func fileSaveButton() throws -> XCUIElement {
        let names = try values("action_save_file")
        func candidates() -> [XCUIElement] {
            app.buttons.allElementsBoundByIndex.filter { button in
                guard button.exists, names.contains(where: { self.nativeLabel(button.label, matches: $0) }) else { return false }
                let frame = button.frame
                return frame.width > 0 && frame.height > 0 && self.app.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) &&
                    (button.isHittable || !button.isEnabled)
            }
        }
        waitFor("One presented Save File action, including its disabled copy state") { candidates().count == 1 }
        XCTAssertEqual(candidates().count, 1)
        return try XCTUnwrap(candidates().first)
    }

    private func editInputLabel(from old: String, to new: String) throws {
        record("before-label-edit-" + new)
        if !identified("a11y-circuit-search").exists {
            try tapKey("a11y_project_tools", toolbar: "editor-project-toolbar")
            try reveal(values("a11y_explorer_title"), verticalRegion: "editor-tool-menu").tap()
        }
        try reveal(values("a11y_explorer_components"), verticalRegion: "a11y-circuit-list").tap()
        let search = identified("a11y-circuit-search")
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        try replacePresentedText(search, with: old)
        finishKeyboardEntry()
        try reveal(labels("a11y_explorer_edit", arguments: ["name": old]), verticalRegion: "a11y-circuit-list").tap()
        XCTAssertTrue(identified("editor-inspector-sheet").waitForExistence(timeout: 5))
        let field = try editableField("property_label")
        XCTAssertEqual(field.value as? String, old)
        try replacePresentedText(field, with: new)
        finishKeyboardEntry()
        try tapKey("action_apply")
        let handles = app.buttons.matching(identifier: "editor-inspector-drag-handle")
            .allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
        XCTAssertEqual(handles.count, 1, "Use the actually presented inspector handle")
        let handle = try XCTUnwrap(handles.first)
        checkFrame(handle, name: "peer-inspector-handle")
        let frame = handle.frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: frame.midY - app.frame.minY))
            .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: app.frame.height - 5)))
        waitFor("Inspector closes after the real label edit") { !self.identified("editor-inspector-sheet").exists }
    }

    private func editableField(_ key: String) throws -> XCUIElement {
        let names = Set(try values(key))
        func candidates() -> [XCUIElement] {
            (app.textViews.allElementsBoundByIndex + app.textFields.allElementsBoundByIndex)
                .filter { $0.exists && $0.isHittable && names.contains($0.label) }
        }
        waitFor("One presented \(key) field") { candidates().count == 1 }
        XCTAssertEqual(candidates().count, 1)
        return try XCTUnwrap(candidates().first)
    }

    private func finishKeyboardEntry() {
        for name in ["Done", "Hide keyboard", "Concluído", "Ocultar teclado", "Return", "Retorno"] {
            let button = app.keyboards.buttons[name]
            if button.exists && button.isHittable { button.tap(); return }
        }
    }

    private func assertOnlyInputLabelChanged(before: String, after: String, to label: String) throws {
        XCTAssertNotEqual(after, before)
        let expected = try PeerProject.read(before, replacingInputLabelWith: label)
        let actual = try PeerProject.read(after)
        XCTAssertEqual(actual.inputLabel, label)
        XCTAssertEqual(actual.canonical, expected.canonical, "Only the selected input label may change in the complete project")
    }

    private func assertOriginalsUnchanged() {
        XCTAssertEqual(try Data(contentsOf: fixtureURL), original, "Import copy must preserve the external original")
        XCTAssertEqual(try Data(contentsOf: controlURL), originalControl)
    }

    private func expectMessage(_ key: String, filename: String? = nil) throws {
        XCTAssertTrue(matching(try labels(key, arguments: filename.map { ["name": $0] } ?? [:]))
            .firstMatch.waitForExistence(timeout: 20), "The result must report \(key)")
    }

    private func labels(_ key: String, arguments: [String: String] = [:]) throws -> [String] {
        try values(key).map { value in
            arguments.reduce(value) { text, argument in text.replacingOccurrences(of: "{\(argument.key)}", with: argument.value) }
        }
    }

    private func waitFor(_ description: String, condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: app)
        let result = XCTWaiter.wait(for: [expectation], timeout: 15)
        if result != .completed { record("failed-" + description) }
        XCTAssertEqual(result, .completed, description)
    }

    private func record(_ name: String) {
        phase = name
        screenshot("peer-" + name)
        attach(app.debugDescription, name: "peer-" + name + "-tree")
    }

    private func observe(_ name: String, xml: String, source: Data) {
        observations.append(["stage": name, "xmlSha256": peerSHA(Data(xml.utf8)),
                             "sourceSha256": peerSHA(source), "sourceBytes": source.count])
    }

    private func attachFile(_ bytes: Data, name: String) {
        let attachment = XCTAttachment(data: bytes, uniformTypeIdentifier: "org.logisim-evolution.circuit")
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func writeReceipt() throws {
        let receipt: [String: Any] = ["schemaVersion": 1, "platform": "ios", "executedViaUi": true,
            "result": "PASSED", "runID": run.run_id, "scope": "separate-app-local-Files",
            "grantRevocation": "NOT_TESTED", "peerBundleID": peer.bundleID, "peerDisplayName": peer.displayName,
            "peerFixture": fixtureURL.path, "peerControl": controlURL.path,
            "originalSha256": peer.originalSHA256, "controlSha256": peerSHA(originalControl), "observations": observations]
        let data = try JSONSerialization.data(withJSONObject: receipt, options: [.prettyPrinted, .sortedKeys])
        let destination = URL(fileURLWithPath: run.documents, isDirectory: true).appendingPathComponent("external-peer-receipt.json")
        try data.write(to: destination, options: .withoutOverwriting)
        attach(String(decoding: data, as: UTF8.self), name: "external-peer-receipt")
    }
}

/// Literal XML comparison, independent of the production codec and model.
private final class PeerProject: NSObject, XMLParserDelegate {
    private var path: [String] = []
    private var selectedInput = false
    private var replacement: String?
    private var text = ""
    private var circuits = 0
    private var components = 0
    private var wires = 0
    private var inputLabels = 0
    private(set) var main: String?
    private(set) var inputLabel: String?
    private(set) var canonical: [String] = []

    static func read(_ xml: String, replacingInputLabelWith label: String? = nil) throws -> PeerProject {
        let result = PeerProject()
        result.replacement = label
        let parser = XMLParser(data: Data(xml.utf8))
        parser.shouldResolveExternalEntities = false
        parser.delegate = result
        XCTAssertTrue(parser.parse(), "The observed project must be complete XML")
        XCTAssertEqual(result.circuits, 1)
        XCTAssertEqual(result.components, 3)
        XCTAssertEqual(result.wires, 2)
        XCTAssertEqual(result.inputLabels, 1, "The shared fixture must identify one input pin at (100,100)")
        return result
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?,
                qualifiedName: String?, attributes: [String: String]) {
        flushText()
        var values = attributes
        if path == ["project"], name == "main" { main = attributes["name"] }
        if path == ["project"], name == "circuit" { circuits += 1 }
        if path == ["project", "circuit"], name == "wire" { wires += 1 }
        if path == ["project", "circuit"], name == "comp" {
            components += 1
            selectedInput = attributes["name"] == "Pin" && attributes["loc"] == "(100,100)"
        }
        if selectedInput, path == ["project", "circuit", "comp"], name == "a", attributes["name"] == "label" {
            inputLabels += 1
            inputLabel = attributes["val"]
            if let replacement { values["val"] = replacement }
        }
        canonical.append("start " + name + " " + values.sorted { $0.key < $1.key }
            .map { String(reflecting: $0.key) + "=" + String(reflecting: $0.value) }.joined(separator: " "))
        path.append(name)
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        flushText()
        canonical.append("end " + name)
        if path == ["project", "circuit", "comp"] { selectedInput = false }
        _ = path.popLast()
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }
    func parser(_ parser: XMLParser, foundCDATA data: Data) { text += String(decoding: data, as: UTF8.self) }
    func parser(_ parser: XMLParser, foundComment comment: String) {
        flushText()
        canonical.append("comment " + String(reflecting: comment))
    }

    private func flushText() {
        if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { canonical.append("text " + String(reflecting: text)) }
        text = ""
    }
}

private func peerSHA(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
