import Foundation
import XCTest
import UIKit

final class InspectorEndToEndTests: NativeUIHarness {
    func testInvalidWidthPreservesProjectAndValidWidthIsOneUndoAction() throws {
        orient(.portrait)
        let label = "INSPECTOR_" + String(UUID().uuidString.prefix(8))
        try loadInput(label: label)
        let baseline = try observedProjectXML(attachmentName: "inspector-before.circ")
        let original = try InspectorPinDocument.read(baseline)
        XCTAssertEqual(original.attributes["width"], "1")
        XCTAssertEqual(original.attributes["label"], label)

        try openWidth(label: label)
        try replaceNumeric("1", with: "0")
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
        try replaceNumeric("1", with: "4")
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

    private func loadInput(label: String) throws {
        if visibleMatch(try values("a11y_new_circuit")) == nil {
            try tapKey("a11y_open_library", toolbar: "editor-command-toolbar")
        }
        try tapKey("a11y_new_circuit")
        try tapKey("a11y_import_export_project", toolbar: "editor-project-toolbar")
        try tapKey("xml_edit")
        let field = try namedField("project_xml_label")
        let xml = """
        <project source="5.1.0-dev" version="1.0"><lib desc="#Wiring" name="0"/>
        <main name="inspector21"/><circuit name="inspector21">
        <comp lib="0" loc="(100,100)" name="Pin"><a name="type" val="input"/>
        <a name="width" val="1"/><a name="label" val="\(label)"/></comp></circuit></project>
        """
        try replacePresentedText(field, with: xml)
        try tapKey("action_import_xml")
        let names = try values("project_xml_label")
        waitFor("XML import completed") { !self.matching(names).firstMatch.exists }
        try closeImportReportIfPresented()
    }

    private func openWidth(label: String) throws {
        try tapKey("a11y_project_tools", toolbar: "editor-project-toolbar")
        try reveal(values("a11y_explorer_title"), verticalRegion: "editor-tool-menu").tap()
        try reveal(values("a11y_explorer_components"), verticalRegion: "a11y-circuit-list").tap()
        let search = identified("a11y-circuit-search")
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        try replacePresentedText(search, with: label)
        try reveal(labels("a11y_explorer_edit", arguments: ["name": label]), verticalRegion: "a11y-circuit-list").tap()
        XCTAssertTrue(identified("editor-inspector-sheet").waitForExistence(timeout: 5))
        // Compose exports this tag as a sibling of the sheet body on iOS.
        // Resolve the presented control globally instead of assuming ancestry.
        let width = try labels("inspector_edit", nestedKey: "attribute_label_width")
        try reveal(width).tap()
        _ = try numericField("1")
    }

    private func replaceNumeric(_ old: String, with value: String) throws {
        let field = try numericField(old)
        try replacePresentedText(field, with: value)
        XCTAssertEqual(field.value as? String, value)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5),
                      "Enable the Simulator software keyboard for this focus/IME case")
        checkFrame(field, name: "focused-width-\(value)", avoidingKeyboard: true)
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

    private func namedField(_ key: String) throws -> XCUIElement {
        let names = try values(key)
        func presented() -> [XCUIElement] { fields.filter { names.contains($0.label) && $0.isHittable } }
        waitFor("One presented \(key) field") { presented().count == 1 }
        let candidates = presented()
        XCTAssertEqual(candidates.count, 1, "The field \(key) must be uniquely presented")
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

private final class InspectorPinDocument: NSObject, XMLParserDelegate {
    private var path: [String] = []
    private(set) var attributes: [String: String] = [:]
    private(set) var component: [String: String] = [:]
    private(set) var canonicalProject: [String] = []
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
        if path == ["project"] && name == "circuit" { circuits += 1 }
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
