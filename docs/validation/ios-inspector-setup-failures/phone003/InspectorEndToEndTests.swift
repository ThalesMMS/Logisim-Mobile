import Foundation
import CryptoKit
import XCTest
import UIKit

final class InspectorEndToEndTests: NativeUIHarness {
    func testInvalidWidthPreservesProjectAndValidWidthIsOneUndoAction() throws {
        orient(.portrait)
        let fixture = try loadInput()
        let label = fixture.label
        let baseline = try observedProjectXML(attachmentName: "inspector-before.circ")
        let importedHash = SHA256.hash(data: Data(baseline.utf8)).map { String(format: "%02x", $0) }.joined()
        attach("route=Files Import Copy, sourceSHA256=\(fixture.sourceSHA256), exportedUISHA256=\(importedHash)",
               name: "inspector-import-copy-ui-export")
        let original = try InspectorPinDocument.read(baseline)
        XCTAssertEqual(original.attributes["width"], "1")
        XCTAssertEqual(original.attributes["label"], label)
        XCTAssertEqual(original.mainCircuitName, fixture.circuit)
        XCTAssertEqual(original.circuitName, fixture.circuit)

        try openWidth(label: label)
        try replaceNumeric(using: .oneToInvalidZero)
        let invalid = try labels("inspector_value_invalid", arguments: ["name": "width", "raw": "0"])
        XCTAssertTrue(matching(invalid).firstMatch.waitForExistence(timeout: 5),
                      "The invalid field must identify width and the entered value")
        let blockedApply = try propertyApply()
        XCTAssertFalse(blockedApply.isEnabled, "An invalid width must not be submitted")
        checkFrame(blockedApply, name: "invalid-width-apply", avoidingKeyboard: true)
        screenshot("inspector-invalid-width-with-keyboard")
        attach(app.debugDescription, name: "inspector-invalid-width-native-tree")
        try tapKey("action_cancel")
        try dismissInspector()
        XCTAssertEqual(try observedProjectXML(attachmentName: "inspector-after-invalid.circ"), baseline,
                       "Editing and cancelling an invalid value must preserve the complete project")

        try openWidth(label: label)
        try replaceNumeric(using: .oneToFour)
        let apply = try propertyApply()
        XCTAssertTrue(apply.isEnabled)
        checkFrame(apply, name: "valid-width-apply", avoidingKeyboard: true)
        apply.tap()
        try dismissInspector()
        let changed = try InspectorPinDocument.read(observedProjectXML(attachmentName: "inspector-width4.circ"))
        let expected = try InspectorPinDocument.read(baseline, replacingWidth: "4")
        XCTAssertEqual(changed.canonicalProject, expected.canonicalProject,
                       "Only the selected pin's width may change across the complete project")

        try tapKey("action_undo", toolbar: "editor-project-toolbar")
        XCTAssertEqual(try observedProjectXML(attachmentName: "inspector-one-undo.circ"), baseline,
                       "One Undo must restore the complete project before the valid edit")
        attach("invalidWidth=0 rejected; validWidth=4; oneUndoWidth=1; appFrame=\(app.frame)",
               name: "inspector-observation")
    }

    private func loadInput() throws -> (label: String, circuit: String, sourceSHA256: String) {
        let resource = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        let run = try JSONDecoder().decode(InspectorRunConfiguration.self, from: Data(contentsOf: resource))
        let url = URL(fileURLWithPath: try XCTUnwrap(run.fixtures["inspector"])).standardizedFileURL
        let directory = URL(fileURLWithPath: run.documents, isDirectory: true).standardizedFileURL
        XCTAssertEqual(url.deletingLastPathComponent(), directory, "Open only this run's staged Inspector fixture")
        let circuit = try XCTUnwrap(run.titles["inspector"])
        let label = run.inspector.label
        let bytes = try Data(contentsOf: url)
        XCTAssertEqual(SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(), run.inspector.fixtureSHA256)
        let xml = try XCTUnwrap(String(data: bytes, encoding: .utf8))
        let fixture = try InspectorPinDocument.read(xml)
        XCTAssertEqual(fixture.mainCircuitName, circuit)
        XCTAssertEqual(fixture.circuitName, circuit)
        XCTAssertEqual(fixture.attributes, ["type": "input", "width": "1", "label": label])

        try tapKey("a11y_project_files", toolbar: "editor-project-toolbar")
        XCTAssertTrue(matching(try values("project_files_title")).firstMatch.waitForExistence(timeout: 5))
        try tapKey("action_import_copy")
        try NativeFilesPeerNavigator(harness: self, peerDisplayName: app.label, runID: run.run_id)
            .open(filename: url.lastPathComponent)
        try waitForImportedFixture(filename: url.lastPathComponent)
        XCTAssertTrue(matching([circuit]).firstMatch.waitForExistence(timeout: 10), "The staged circuit name must be visible")
        try searchInput(label: label)
        let names = try labels("a11y_explorer_select", arguments: ["name": label])
        let row = try reveal(names, verticalRegion: "a11y-circuit-list")
        let selectedRows = matching(names, within: identified("a11y-circuit-list")).allElementsBoundByIndex.filter {
            $0.exists && $0.identifier.hasPrefix("component:")
        }
        XCTAssertEqual(selectedRows.count, 1, "The actual imported fixture must expose exactly one uniquely labelled pin")
        XCTAssertTrue(row.identifier.hasPrefix("component:"))
        XCTAssertEqual(try Data(contentsOf: url), bytes, "Import Copy must preserve the staged source bytes")
        attach("route=Files Import Copy, fixture=\(url.path), circuit=\(circuit), label=\(label), nativeID=\(row.identifier)",
               name: "inspector-files-import-copy-fixture")
        try tapKey("action_close")
        return (label, circuit, run.inspector.fixtureSHA256)
    }

    private func waitForImportedFixture(filename: String) throws {
        let opened = try labels("status_file_opened", arguments: ["name": filename])
        let origins = try labels("import_report_origin", arguments: ["origin": filename])
        let report = identified("editor-import-report")
        func exactOrigin() -> Bool {
            report.descendants(matching: .staticText).matching(NSPredicate(format: "label IN %@", origins)).firstMatch.exists
        }
        func exactOpenedStatus() -> Bool {
            app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", opened)).firstMatch.exists
        }
        let deadline = Date().addingTimeInterval(20)
        while true {
            let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                report.exists ? exactOrigin() : exactOpenedStatus()
            }, object: app)
            let remaining = deadline.timeIntervalSinceNow
            guard remaining > 0, XCTWaiter.wait(for: [ready], timeout: remaining) == .completed else {
                screenshot("inspector-import-copy-source-missing")
                attach(app.debugDescription, name: "inspector-import-copy-source-missing-tree")
                XCTFail("Import Copy must expose the exact selected filename in its report or opened-file status")
                throw InspectorInputFailure.failed("The selected Inspector source was not confirmed")
            }
            if report.exists {
                guard exactOrigin() else { continue }
                screenshot("inspector-import-copy-report")
                attach(app.debugDescription, name: "inspector-import-copy-report-tree")
                guard report.exists && exactOrigin() else { continue }
                try tapKey("action_close")
                let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !report.exists }, object: app)
                XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 10), .completed,
                               "Close only the report associated with the selected Inspector filename")
                continue
            }
            XCTAssertTrue(exactOpenedStatus())
            break
        }
    }

    private func searchInput(label: String) throws {
        try tapKey("a11y_project_tools", toolbar: "editor-project-toolbar")
        try reveal(values("a11y_explorer_title"), verticalRegion: "editor-tool-menu").tap()
        try reveal(values("a11y_explorer_components"), verticalRegion: "a11y-circuit-list").tap()
        let search = identified("a11y-circuit-search")
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        try replacePresentedText(search, with: label)
    }

    private func openWidth(label: String) throws {
        try searchInput(label: label)
        try reveal(labels("a11y_explorer_edit", arguments: ["name": label]), verticalRegion: "a11y-circuit-list").tap()
        XCTAssertTrue(identified("editor-inspector-sheet").waitForExistence(timeout: 5))
        // Compose exports this tag as a sibling of the sheet body on iOS.
        // Resolve the presented control globally instead of assuming ancestry.
        let width = try labels("inspector_edit", nestedKey: "attribute_label_width")
        try reveal(width).tap()
        _ = try numericField("1")
    }

    private enum InspectorSingleDigitReplacementPolicy: String {
        case oneToInvalidZero = "0"
        case oneToFour = "4"
    }

    private func replaceNumeric(using policy: InspectorSingleDigitReplacementPolicy) throws {
        let value = policy.rawValue
        let field = try numericField("1")
        guard let initial = field.value as? String, Array(initial.utf8) == [0x31],
              [.textView, .textField].contains(field.elementType), field.isEnabled, field.isHittable else {
            try failSingleDigit("This Inspector-only policy requires one presented nonsecure field containing exactly ASCII 1.", field: field)
        }
        let role = field.elementType
        let identifier = field.identifier
        let label = field.label
        func requireFocusedIdentity(_ expected: String, stage: String) throws {
            let root = field.debugDescription.components(separatedBy: "\n").first ?? ""
            attach(root, name: "inspector-width-\(value)-\(stage)-root-attributes")
            // This SDK's public diagnostic format is an intentionally conservative
            // gate, not a supported keyboard-focus API. A changed format fails.
            guard field.exists, field.elementType == role, field.identifier == identifier, field.label == label,
                  field.value as? String == expected,
                  (root.hasPrefix("Attributes: TextView, ") || root.hasPrefix("Attributes: TextField, ")),
                  root.hasSuffix(", Keyboard Focused") else {
                try failSingleDigit("The same width field must expose its exact value and keyboard focus in its own root Attributes.", field: field)
            }
        }
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5),
                      "Enable the Simulator software keyboard for this focus/IME case")
        let keyboards = app.keyboards.allElementsBoundByIndex.filter { visibleControl($0) }
        guard keyboards.count == 1, let keyboard = keyboards.first else {
            try failSingleDigit("One visible software keyboard is required for the single Delete policy.", field: field)
        }
        checkFrame(field, name: "focused-width-before-\(value)", avoidingKeyboard: true)
        try requireFocusedIdentity("1", stage: "before-delete")
        let deletes = keyboard.keys.matching(NSPredicate(format: "label == %@", "delete")).allElementsBoundByIndex.filter {
            self.visibleControl($0) && $0.isEnabled && keyboard.frame.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY))
        }
        guard deletes.count == 1, let delete = deletes.first else {
            try failSingleDigit("One enabled native software-keyboard Key labelled delete must be visible.", field: field)
        }
        attach("fieldRole=\(role.rawValue), fieldID=\(identifier), keyboard=\(keyboard.frame), deleteID=\(delete.identifier), deleteFrame=\(delete.frame)",
               name: "inspector-width-\(value)-software-delete")
        delete.tap()
        let cleared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            field.exists && field.elementType == role && field.identifier == identifier && field.label == label &&
                field.value as? String == ""
        }, object: field)
        guard XCTWaiter.wait(for: [cleared], timeout: 5) == .completed else {
            try failSingleDigit("One software Delete must leave the complete field exactly empty; no replacement will be typed.", field: field)
        }
        try requireFocusedIdentity("", stage: "after-delete")
        field.typeText(value)
        XCTAssertEqual(field.value as? String, value)
        try requireFocusedIdentity(value, stage: "after-type")
        checkFrame(field, name: "focused-width-\(value)", avoidingKeyboard: true)
    }

    private func visibleControl(_ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        guard frame.minX.isFinite, frame.minY.isFinite, frame.width.isFinite, frame.height.isFinite,
              frame.width > 0, frame.height > 0,
              app.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) else { return false }
        return element.isHittable
    }

    private func failSingleDigit(_ message: String, field: XCUIElement) throws -> Never {
        screenshot("inspector-single-digit-failure")
        attach(field.debugDescription, name: "inspector-single-digit-failure-field")
        attach(app.debugDescription, name: "inspector-single-digit-failure-tree")
        XCTFail(message)
        throw InspectorInputFailure.failed(message)
    }

    private func propertyApply() throws -> XCUIElement {
        let names = try values("action_apply")
        func presented() -> [XCUIElement] {
            app.buttons.allElementsBoundByIndex.filter { element in
                element.exists && names.contains(where: { self.nativeLabel(element.label, matches: $0) }) &&
                    !element.frame.intersection(self.app.frame).isNull && (element.isHittable || !element.isEnabled)
            }
        }
        waitFor("One presented property Apply action") { presented().count == 1 }
        let candidates = presented()
        XCTAssertEqual(candidates.count, 1, "The property dialog must have one presented Apply action")
        return try XCTUnwrap(candidates.first)
    }

    private func numericField(_ value: String) throws -> XCUIElement {
        func presented() -> [XCUIElement] { fields.filter { $0.value as? String == value && $0.isHittable } }
        waitFor("One presented numeric field containing \(value)") { presented().count == 1 }
        let candidates = presented()
        XCTAssertEqual(candidates.count, 1, "Read one presented numeric property field with value \(value)")
        return try XCTUnwrap(candidates.first)
    }

    private var fields: [XCUIElement] {
        (app.textViews.allElementsBoundByIndex + app.textFields.allElementsBoundByIndex).filter { $0.exists }
    }

    private func dismissInspector() throws {
        XCTAssertTrue(identified("editor-inspector-sheet").exists)
        let handles = app.buttons.matching(identifier: "editor-inspector-drag-handle")
            .allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
        XCTAssertEqual(handles.count, 1)
        let handle = try XCTUnwrap(handles.first)
        let descriptions = try values("a11y_inspector_handle")
        XCTAssertTrue(descriptions.contains { nativeLabel(handle.label, matches: $0) })
        checkFrame(handle, name: "inspector-drag-handle")
        let frame = handle.frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: frame.midY - app.frame.minY))
            .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: app.frame.height - 5)))
        waitFor("Inspector dismissed") { !self.identified("editor-inspector-sheet").exists }
    }

    private func labels(_ key: String, arguments: [String: String] = [:], nestedKey: String? = nil) throws -> [String] {
        let translations = try XCTUnwrap(catalog[key])
        return try translations.compactMap { language, value in
            guard var text = value as? String else { return nil }
            for (name, value) in arguments { text = text.replacingOccurrences(of: "{\(name)}", with: value) }
            if let nestedKey {
                let translation = try XCTUnwrap(catalog[nestedKey]?[language] as? String)
                text = text.replacingOccurrences(of: "{name}", with: translation)
            }
            return text
        }
    }

    private func waitFor(_ name: String, condition: @escaping () -> Bool) {
        let expected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [expected], timeout: 10), .completed, name)
    }
}

private struct InspectorRunConfiguration: Decodable {
    let run_id: String
    let fixtures: [String: String]
    let titles: [String: String]
    let documents: String
    let inspector: InspectorFixture

    struct InspectorFixture: Decodable {
        let label: String
        let fixtureSHA256: String
    }
}

private enum InspectorInputFailure: Error { case failed(String) }

private final class InspectorPinDocument: NSObject, XMLParserDelegate {
    private var path: [String] = []
    private(set) var attributes: [String: String] = [:]
    private(set) var component: [String: String] = [:]
    private(set) var canonicalProject: [String] = []
    private(set) var mainCircuitName: String?
    private(set) var circuitName: String?
    private var replacingWidth: String?
    private var text = ""
    private var circuits = 0
    private var components = 0
    private var wires = 0

    static func read(_ xml: String, replacingWidth: String? = nil) throws -> InspectorPinDocument {
        let result = InspectorPinDocument()
        result.replacingWidth = replacingWidth
        let parser = XMLParser(data: Data(xml.utf8))
        parser.shouldResolveExternalEntities = false
        parser.delegate = result
        XCTAssertTrue(parser.parse(), "The UI export must be valid XML")
        XCTAssertEqual(result.circuits, 1)
        XCTAssertEqual(result.components, 1)
        XCTAssertEqual(result.wires, 0)
        XCTAssertEqual(result.component["name"], "Pin")
        XCTAssertEqual(result.component["loc"], "(100,100)")
        return result
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?,
                qualifiedName: String?, attributes: [String: String]) {
        flushText()
        var canonicalAttributes = attributes
        if path == ["project"] && name == "main" { mainCircuitName = attributes["name"] }
        if path == ["project"] && name == "circuit" { circuits += 1; circuitName = attributes["name"] }
        if path == ["project", "circuit"] && name == "comp" { components += 1; component = attributes }
        if path == ["project", "circuit"] && name == "wire" { wires += 1 }
        if path == ["project", "circuit", "comp"] && name == "a" {
            if let key = attributes["name"], let value = attributes["val"] {
                XCTAssertNil(self.attributes[key], "Duplicate exported property \(key)")
                self.attributes[key] = value
                if key == "width", let replacingWidth { canonicalAttributes["val"] = replacingWidth }
            } else { XCTFail("The pin has an invalid exported property") }
        }
        let values = canonicalAttributes.sorted { $0.key < $1.key }.map {
            String(reflecting: $0.key) + "=" + String(reflecting: $0.value)
        }.joined(separator: " ")
        canonicalProject.append("start " + String(reflecting: name) + " " + values)
        path.append(name)
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        flushText()
        canonicalProject.append("end " + String(reflecting: name))
        _ = path.popLast()
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ parser: XMLParser, foundCDATA data: Data) {
        guard let value = String(data: data, encoding: .utf8) else { XCTFail("Invalid UTF-8 CDATA"); return }
        text += value
    }

    func parser(_ parser: XMLParser, foundComment comment: String) {
        flushText()
        canonicalProject.append("comment " + String(reflecting: comment))
    }

    private func flushText() {
        if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            canonicalProject.append("text " + String(reflecting: text))
        }
        text = ""
    }
}
