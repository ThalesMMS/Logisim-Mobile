import Foundation
import XCTest

// Empty-query cases diagnose focus routing; only Q cases exercise selection of existing text.
final class FocusEndToEndTests: NativeUIHarness {
    private enum CommandTarget: String {
        case field
        case application
    }

    func testFieldCommandAAfterReopeningInsert() throws {
        try checkReopenedInsert(commandTarget: .field, typeSentinel: false)
    }

    func testApplicationCommandAAfterReopeningInsert() throws {
        try checkReopenedInsert(commandTarget: .application, typeSentinel: false)
    }

    func testFieldCommandAAfterReopeningInsertWithTypedSentinel() throws {
        try checkReopenedInsert(commandTarget: .field, typeSentinel: true)
    }

    func testApplicationCommandAAfterReopeningInsertWithTypedSentinel() throws {
        try checkReopenedInsert(commandTarget: .application, typeSentinel: true)
    }

    func testCommandAAfterYReplacementInSameInsertDialog() throws {
        orient(.portrait)
        try newProject()
        try openInsert()
        try originalKeyboardReplace(field("a11y_explorer_insert_y"), with: "100")
        try checkFindAfterSelectionHistory()
    }

    func testCommandAAfterCoordinateReplacementWithoutInspector() throws {
        orient(.portrait)
        try newProject()
        try openInsert()
        for (key, value) in [("a11y_explorer_insert_kind", "INPUT"),
                             ("a11y_explorer_insert_x", "100"), ("a11y_explorer_insert_y", "100")] {
            try originalKeyboardReplace(field(key), with: value)
        }
        hideSoftwareKeyboard()
        try reveal(values("component_input")).tap()
        waitFor("First insertion dismissed its dialog") { !self.hasField("a11y_explorer_insert_kind") }
        try openInsert()
        try checkFindAfterSelectionHistory()
    }

    private func checkFindAfterSelectionHistory() throws {
        let query = try field("a11y_explorer_insert_kind")
        XCTAssertTrue(query.waitForExistence(timeout: 5))
        query.tap()
        recordFields(query, phase: "reduced-history-after-tap")
        query.typeKey("a", modifierFlags: .command)
        recordFields(query, phase: "reduced-history-after-empty-command-a")
        query.typeText(XCUIKeyboardKey.delete.rawValue)
        try assertInsertValues(query: "", phase: "reduced-history-empty-delete")
        query.typeText("QFOCUS")
        try assertInsertValues(query: "QFOCUS", phase: "reduced-history-filled")
        recordFields(query, phase: "reduced-history-before-filled-command-a")
        query.typeKey("a", modifierFlags: .command)
        recordFields(query, phase: "reduced-history-after-filled-command-a")
        query.typeText(XCUIKeyboardKey.delete.rawValue)
        recordFields(query, phase: "reduced-history-after-filled-delete")
        try assertInsertValues(query: "", phase: "reduced-history-filled-delete")
        query.typeText("R")
        try assertInsertValues(query: "R", phase: "reduced-history-replacement")
    }

    private func originalKeyboardReplace(_ field: XCUIElement, with value: String) throws {
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        scrollAboveKeyboard(field)
        field.tap()
        recordFields(field, phase: "original-replace-after-tap")
        field.typeKey("a", modifierFlags: .command)
        recordFields(field, phase: "original-replace-after-select-all")
        field.typeText(XCUIKeyboardKey.delete.rawValue)
        field.typeText(value)
        XCTAssertEqual(text(field), value)
    }

    private func recordFields(_ target: XCUIElement, phase: String) {
        attach((["target: \(target.debugDescription)"] + editableControls.map { $0.debugDescription }).joined(separator: "\n"),
               name: "editing-focus-\(phase)")
    }

    private func newProject() throws {
        try tapKey("a11y_new_circuit", toolbar: "editor-project-toolbar")
    }

    private func checkReopenedInsert(commandTarget: CommandTarget, typeSentinel: Bool) throws {
        orient(.portrait)
        attach("commandTarget=\(commandTarget.rawValue), typedSentinel=\(typeSentinel), " +
               "sentinel=Q, replacement=R, expectedX=100, expectedY=100, appFrame=\(app.frame)",
               name: "focus-probe-configuration")
        try prepareFirstInsertion()
        try openInsert()
        let query = try field("a11y_explorer_insert_kind")
        try assertInsertValues(query: "", phase: "reopened")

        query.tap()
        record(query, phase: "after-tap")
        if typeSentinel {
            query.typeText("Q")
            record(query, phase: "after-typed-Q")
            try assertInsertValues(query: "Q", phase: "typed-Q")
        }
        record(query, phase: "before-command-a")
        switch commandTarget {
        case .field: query.typeKey("a", modifierFlags: .command)
        case .application: app.typeKey("a", modifierFlags: .command)
        }
        record(query, phase: "after-command-a")
        try assertInsertValues(query: typeSentinel ? "Q" : "", phase: "command-a")

        // No tap or focus repair between Cmd+A and Delete: a focus change must fail here.
        query.typeText(XCUIKeyboardKey.delete.rawValue)
        record(query, phase: "after-delete")
        try assertInsertValues(query: "", phase: "delete")
        query.typeText("R")
        record(query, phase: "after-replacement-R")
        try assertInsertValues(query: "R", phase: "replacement-R")
    }

    private func prepareFirstInsertion() throws {
        try tapKey("a11y_new_circuit", toolbar: "editor-project-toolbar")
        try openExplorer()
        try openInsert()
        let query = try field("a11y_explorer_insert_kind")
        XCTAssertEqual(text(query), "")
        query.tap()
        query.typeText("INPUT")
        try assertInsertValues(query: "INPUT", phase: "first-query")
        // The dialog keeps clear of the keyboard and scrolls (A1): X and Y are dragged into view before each tap.
        let x = try field("a11y_explorer_insert_x")
        scrollAboveKeyboard(x)
        x.tap()
        let y = try field("a11y_explorer_insert_y")
        scrollAboveKeyboard(y)
        y.tap()
        record(y, phase: "first-insert-last-focus-y")
        try assertInsertValues(query: "INPUT", phase: "first-y-focus")
        hideSoftwareKeyboard()
        try reveal(values("component_input")).tap()
        waitFor("First insertion dismissed its dialog") {
            !self.hasField("a11y_explorer_insert_kind")
        }
    }

    private func openExplorer() throws {
        if identified("a11y-circuit-search").exists { return }
        try tapKey("a11y_project_tools", toolbar: "editor-project-toolbar")
        try reveal(values("a11y_explorer_title"), verticalRegion: "editor-tool-menu").tap()
        XCTAssertTrue(identified("a11y-circuit-search").waitForExistence(timeout: 5))
    }

    private func openInsert() throws {
        try openExplorer()
        try reveal(values("a11y_explorer_insert"), verticalRegion: "a11y-circuit-list").tap()
        _ = try field("a11y_explorer_insert_kind")
        // X and Y appear once Custom coordinates is chosen (the other positions need no typed coordinates).
        try reveal(values("a11y_insert_custom")).tap()
        _ = try field("a11y_explorer_insert_x")
    }

    private func field(_ key: String) throws -> XCUIElement {
        let names = Set(try values(key))
        // X and Y lose their caption while the dialog's list cuts it; their tag still names them.
        let identifier = Self.fieldIdentifiers[key]
        func candidates() -> [XCUIElement] {
            editableControls.filter { names.contains($0.label) || (identifier != nil && $0.identifier == identifier) }
        }
        waitFor("One presented editable control for \(key)") { candidates().count == 1 }
        let fields = candidates()
        XCTAssertEqual(fields.count, 1, "The canonical field must be unambiguous")
        return try XCTUnwrap(fields.first)
    }

    private func hasField(_ key: String) -> Bool {
        guard let names = try? values(key) else { return false }
        let identifier = Self.fieldIdentifiers[key]
        return editableControls.contains { names.contains($0.label) || (identifier != nil && $0.identifier == identifier) }
    }

    private var editableControls: [XCUIElement] {
        (app.textViews.allElementsBoundByIndex + app.textFields.allElementsBoundByIndex).filter { $0.exists }
    }

    private func text(_ field: XCUIElement) -> String {
        field.value as? String ?? ""
    }

    private func assertInsertValues(query: String, phase: String) throws {
        XCTAssertEqual(text(try field("a11y_explorer_insert_kind")), query, "Find at \(phase)")
        XCTAssertEqual(text(try field("a11y_explorer_insert_x")), "100", "X must stay unchanged at \(phase)")
        XCTAssertEqual(text(try field("a11y_explorer_insert_y")), "100", "Y must stay unchanged at \(phase)")
    }

    private func record(_ target: XCUIElement, phase: String) {
        let controls = editableControls.map {
            "label=\($0.label), value=\(String(describing: $0.value)), hasUIFocus=\($0.hasFocus)\n\($0.debugDescription)"
        }
        attach((["target: \(target.debugDescription)"] + controls).joined(separator: "\n"),
               name: "focus-probe-\(phase)")
        attach(app.debugDescription, name: "focus-probe-\(phase)-tree")
        screenshot("focus-probe-\(phase)")
    }

    private func hideSoftwareKeyboard() {
        for name in ["Done", "Hide keyboard", "Concluído", "Ocultar teclado", "Return", "Retorno"] {
            let button = app.keyboards.buttons[name]
            if button.exists && button.isHittable { button.tap(); return }
        }
    }

    private func waitFor(_ description: String, condition: @escaping () -> Bool) {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 5), .completed, description)
    }
}
