import Foundation
import CryptoKit
import XCTest
import UIKit

final class InspectorEndToEndTests: NativeUIHarness {
    func testNarrowInspectorMovesInlineOnWideTablet() throws {
        orient(.portrait)
        let sheet = identified("editor-inspector-sheet")
        let inline = identified("editor-inspector-inline")
        // A window at least 760 points wide docks the inspector even in portrait (editorLayoutIsWide, #321),
        // so no sheet exists to move. This device proves the docked side; iPad mini portrait proves the move.
        if app.frame.width >= 760 {
            XCTAssertTrue(inline.waitForExistence(timeout: 5), "A wide window must dock its one inspector")
            XCTAssertFalse(sheet.exists, "A wide window must not present the narrow sheet")
            orient(.landscapeLeft)
            let docked = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !sheet.exists && inline.exists }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [docked], timeout: 8), .completed,
                           "Rotation must keep one inline inspector and no sheet")
            attach("appFrame=\(app.frame), branch=docked-at-760", name: "inspector-narrow-branch")
            return
        }
        try tapKey("a11y_open_properties", toolbar: "editor-command-toolbar")
        XCTAssertTrue(sheet.waitForExistence(timeout: 5))

        orient(.landscapeLeft)
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !sheet.exists && inline.exists
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [moved], timeout: 8), .completed,
                       "The wide layout must expose one inline inspector and dismiss the narrow sheet")
    }

    func testSignalsSheetMovesInlineOnTallPhone() throws {
        orient(.landscapeLeft)
        try tapKey("signals_heading", toolbar: "editor-command-toolbar")
        let sheet = identified("editor-signal-sheet")
        let panel = identified("editor-signal-panel")
        XCTAssertTrue(sheet.waitForExistence(timeout: 5))
        XCTAssertTrue(panel.exists)

        orient(.portrait)
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !sheet.exists && panel.exists
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [moved], timeout: 8), .completed,
                       "The taller layout must move Signals inline and dismiss its sheet")
    }

    /// The reported flaw: a label direction was retyped as text ("north" -> "west"). It is now chosen from a menu
    /// of Evolution's display names, the current value is marked, and the choice is applied at once.
    func testLabelLocationIsChosenFromAMenu() throws {
        orient(.portrait)
        // The open project's first input pin (the AND example's A, or any project's unlabelled Input).
        let inputs = app.descendants(matching: .any).matching(NSPredicate(format:
            "identifier BEGINSWITH 'canvas-component:' AND (label == 'A' OR label BEGINSWITH 'Input' OR label BEGINSWITH 'Entrada')"))
        let pin = inputs.firstMatch
        XCTAssertTrue(pin.waitForExistence(timeout: 10), "The project must show an input pin")
        // The Edit tool, wherever the current toolbar layout shows it.
        try XCTUnwrap(visibleMatch(values("action_edit_tool")), "The Edit tool must be visible").tap()
        pin.tap()
        try tapKey("a11y_open_properties", toolbar: "editor-command-toolbar")
        let row = identified("inspector-row-labelloc")
        for _ in 0..<4 where !row.isHittable { identified("editor-inspector-sheet").swipeUp() }
        XCTAssertTrue(row.waitForExistence(timeout: 5) && row.isHittable, "The Label location row must be reachable")
        screenshot("labelloc-1-inspector")
        attach(row.debugDescription, name: "labelloc-row-accessibility")
        let shownBefore = row.value as? String
        row.tap()
        let locations = ["center", "north", "south", "east", "west"]
        let options = locations.map { identified("inspector-option-labelloc-\($0)") }
        XCTAssertTrue(options[1].waitForExistence(timeout: 5), "The menu must offer the label locations")
        XCTAssertTrue(options.allSatisfy { $0.exists }, "Center, North, South, East and West must all be offered")
        let marked = locations.indices.filter { options[$0].isSelected }
        XCTAssertEqual(marked.count, 1, "Exactly the current location must be marked")
        let current = locations[try XCTUnwrap(marked.first)]
        let target = current == "north" ? "west" : "north"
        screenshot("labelloc-2-menu")
        attach("current=\(current) target=\(target)", name: "labelloc-choice")
        identified("inspector-option-labelloc-\(target)").tap()
        let applied = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            !self.identified("inspector-option-labelloc-center").exists
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [applied], timeout: 5), .completed, "Choosing must close the menu")
        let after = identified("inspector-row-labelloc")
        XCTAssertNotEqual(after.value as? String, shownBefore, "The row must show the chosen location")
        screenshot("labelloc-3-chosen")
        attach(after.debugDescription, name: "labelloc-row-after")
        try dismissInspector()
        XCTAssertTrue(try observedProjectXML(attachmentName: "labelloc-chosen.circ").contains("<a name=\"labelloc\" val=\"\(target)\"/>"),
                      "The file must store the serialized value \(target)")
    }

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
        // The message names the attribute as the table shows it ("Data bits") and its range (W3-01).
        let invalid = try labels("inspector_value_invalid_range", arguments: ["raw": "0", "min": "1", "max": "64"],
                                 nestedKey: "attribute_label_width")
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
        XCTAssertTrue(identified("editor-file-menu").waitForExistence(timeout: 5))
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
        try closeExplorer()
        return (label, circuit, run.inspector.fixtureSHA256)
    }

    private func closeExplorer() throws {
        func fail(_ message: String) throws -> Never {
            screenshot("inspector-explorer-close-failure")
            attach(app.debugDescription, name: "inspector-explorer-close-failure-tree")
            XCTFail(message)
            throw InspectorInputFailure.failed(message)
        }
        let sheet = identified("editor-tool-sheet")
        let search = identified("a11y-circuit-search")
        let titles = try values("a11y_explorer_title")
        let title = app.staticTexts.matching(NSPredicate(format: "label IN %@", titles)).firstMatch
        guard sheet.waitForExistence(timeout: 5), search.exists,
              identified("a11y-circuit-list").exists, title.exists,
              title.frame.width > 0, title.frame.height > 0, app.frame.intersects(title.frame) else {
            try fail("The presented tool must be the Circuit Explorer before its header is used.")
        }
        func header(_ identifier: String, key: String) throws -> XCUIElement {
            let controls = app.buttons.matching(identifier: identifier).allElementsBoundByIndex.filter { $0.exists }
            guard controls.count == 1, let control = controls.first else {
                try fail("The Explorer header must expose one Button tagged \(identifier).")
            }
            let frame = control.frame
            let names = try values(key)
            guard names.contains(control.label), frame.minX.isFinite, frame.minY.isFinite,
                  frame.width.isFinite, frame.height.isFinite, frame.width >= 48, frame.height >= 48,
                  app.frame.contains(frame), control.isEnabled, control.isHittable else {
                try fail("The tagged Explorer header must have its exact canonical action and a reachable 48-point frame.")
            }
            attach("tag=\(identifier), label=\(control.label), frame=\(frame)", name: "inspector-explorer-\(identifier)")
            return control
        }
        func occludingInputFrames() -> [CGRect] {
            var controls = app.keyboards.allElementsBoundByIndex
            for identifier in ["inputView", "SystemInputAssistantView"] {
                controls += app.descendants(matching: .any).matching(identifier: identifier).allElementsBoundByIndex
            }
            return controls.compactMap { control in
                guard control.exists else { return nil }
                let frame = control.frame
                guard frame.minX.isFinite, frame.minY.isFinite, frame.width.isFinite, frame.height.isFinite,
                      frame.width > 0, frame.height > 0 else { return nil }
                let visible = frame.intersection(app.frame)
                return visible.isNull || visible.isEmpty ? nil : visible
            }
        }
        attach("before=\(occludingInputFrames())", name: "inspector-explorer-before-hide-input")
        if app.buttons.matching(identifier: "editor-tool-hide-keyboard").firstMatch.exists {
            try header("editor-tool-hide-keyboard", key: "action_hide_keyboard").tap()
        }
        let close = identified("editor-tool-close")
        let hidden = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            close.exists && occludingInputFrames().isEmpty
        }, object: app)
        guard XCTWaiter.wait(for: [hidden], timeout: 10) == .completed else {
            try fail("Hide keyboard must remove observed input occlusion and reveal the Explorer Close header.")
        }
        attach("after=\(occludingInputFrames())", name: "inspector-explorer-after-hide-input")
        try header("editor-tool-close", key: "action_close").tap()
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !sheet.exists && !search.exists
        }, object: app)
        guard XCTWaiter.wait(for: [dismissed], timeout: 10) == .completed else {
            try fail("The Explorer header Close must dismiss both its sheet and search control.")
        }
    }

    private func waitForImportedFixture(filename: String) throws {
        let opened = try fileOpenedStatus(filename, within: app)
        let origins = try labels("import_report_origin", arguments: ["origin": filename])
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
        XCTAssertNotNil(presentedInspector(), "Edit must present the inspector, as a sheet or docked")
        // Compose exports this tag as a sibling of the sheet body on iOS.
        // Resolve the presented control globally instead of assuming ancestry.
        let width = try labels("inspector_edit", nestedKey: "attribute_label_width")
        try revealInInspector(width).tap()
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
        // The Keyboard node exists while it is still below the screen; judge it once it has risen.
        func risenKeyboards() -> [XCUIElement] { app.keyboards.allElementsBoundByIndex.filter { visibleControl($0) } }
        let risen = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in risenKeyboards().count == 1 }, object: app)
        _ = XCTWaiter.wait(for: [risen], timeout: 5)
        let keyboards = risenKeyboards()
        guard keyboards.count == 1, let keyboard = keyboards.first else {
            try failSingleDigit("One visible software keyboard is required for the single Delete policy.", field: field)
        }
        checkFrame(field, name: "focused-width-before-\(value)", avoidingKeyboard: true)
        try requireFocusedIdentity("1", stage: "before-delete")
        let deletes = keyboard.keys.matching(NSPredicate(format: "label IN %@", ["delete", "Delete"])).allElementsBoundByIndex.filter {
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
        if !identified("editor-inspector-sheet").exists {
            // The docked inspector stays open beside the canvas (#321); there is nothing to dismiss.
            XCTAssertTrue(identified("editor-inspector-inline").exists, "The inspector must stay presented, as a sheet or docked")
            return
        }
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
