import Foundation
import XCTest
import UIKit

/// Drives production editor controls; the shared JSON supplies literal actions and expectations.
final class EditorEndToEndTests: NativeUIHarness {
    private var contract: [String: Any] = [:]
    private var capturedZoom: [String: Int] = [:]
    private var exportIndex = 0
    private var useOriginalCommandAReplacement = false
    private var originalCommandAReplacementCount = 0
    private var pointerWindow: XCUIElement?
    /// iPadOS may open a system-opened document in a new scene; the case then queries only that window.
    private var documentWindow: DocumentWindowScope?
    private enum InspectorPresentation { case sheet, inline(CGRect) }
    private var inspectorPresentation: InspectorPresentation?

    override var nativeQueryRoot: XCUIElement { pointerWindow ?? documentWindow?.window ?? super.nativeQueryRoot }

    override func setUpWithError() throws {
        try super.setUpWithError()
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "editor-actions-v1", withExtension: "json"))
        contract = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        XCTAssertEqual(contract["schemaVersion"] as? Int, 1)
    }

    func testFieldCommandAAfterFullInspectorRoundtrip() throws {
        try checkCommandAAfterFullInspectorRoundtrip(commandOnApplication: false)
    }

    func testApplicationCommandAAfterFullInspectorRoundtrip() throws {
        try checkCommandAAfterFullInspectorRoundtrip(commandOnApplication: true)
    }

    func testFieldCommandAWithQFOCUSAfterFullInspectorRoundtrip() throws {
        try checkCommandAAfterFullInspectorRoundtrip(commandOnApplication: false, emptyCommandFirst: false)
    }

    func testApplicationCommandAWithQFOCUSAfterFullInspectorRoundtrip() throws {
        try checkCommandAAfterFullInspectorRoundtrip(commandOnApplication: true, emptyCommandFirst: false)
    }

    private func checkCommandAAfterFullInspectorRoundtrip(commandOnApplication: Bool, emptyCommandFirst: Bool = true) throws {
        orient(.portrait)
        let scenarios = try XCTUnwrap(contract["scenarios"] as? [[String: Any]])
        let scenario = try XCTUnwrap(scenarios.first { $0["id"] as? String == "editing-connections-history-viewport" })
        let steps = try XCTUnwrap(scenario["steps"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(steps.count, 3)
        XCTAssertEqual(try string(steps[0], "action"), "newProject")
        XCTAssertEqual(try string(steps[1], "action"), "assertDocument")
        XCTAssertEqual(try string(steps[1], "checkpoint"), "empty")
        XCTAssertEqual(try string(steps[2], "kind"), "input")
        XCTAssertEqual(try integer(steps[2], "x"), 100)
        XCTAssertEqual(try integer(steps[2], "y"), 100)
        XCTAssertEqual(try string(steps[2], "label"), "E2E_IN")
        attach("commandTarget=\(commandOnApplication ? "application" : "field"), " +
               "prelude=original contract steps 1-3 including XML and Inspector, " +
               "emptyCommandFirst=\(emptyCommandFirst), sentinel=QFOCUS, replacement=R, expectedX=100, expectedY=100",
               name: "full-history-focus-configuration")
        try performOriginalKeyboardPrelude(steps.prefix(3))

        try openExplorer()
        try click("a11y_explorer_insert", region: "a11y-circuit-list")
        // X and Y appear once Custom coordinates is chosen (the other positions need no typed coordinates).
        try click("a11y_insert_custom")
        let query = try editableField("a11y_explorer_insert_kind")
        XCTAssertTrue(query.waitForExistence(timeout: 5))
        query.tap()
        recordEditingFocus(query, phase: "full-history-after-tap")
        if emptyCommandFirst {
            sendCommandA(query, onApplication: commandOnApplication)
            recordEditingFocus(query, phase: "full-history-after-empty-command-a")
            // Match the original failure point before introducing any sentinel text.
            query.typeText(XCUIKeyboardKey.delete.rawValue)
            XCTAssertEqual(query.value as? String, "")
            try assertFullHistoryCoordinates()
        }

        query.typeText("QFOCUS")
        XCTAssertEqual(query.value as? String, "QFOCUS", "The existing native input session must write into Find")
        recordEditingFocus(query, phase: "full-history-before-filled-command-a")
        sendCommandA(query, onApplication: commandOnApplication)
        recordEditingFocus(query, phase: "full-history-after-filled-command-a")
        // One Delete cannot clear QFOCUS unless all six characters were selected.
        query.typeText(XCUIKeyboardKey.delete.rawValue)
        recordEditingFocus(query, phase: "full-history-after-filled-delete")
        XCTAssertEqual(query.value as? String, "", "One Delete after Command-A must clear the entire QFOCUS value")
        try assertFullHistoryCoordinates()
        query.typeText("R")
        XCTAssertEqual(query.value as? String, "R")
        try assertFullHistoryCoordinates()
        attach(app.debugDescription, name: "full-history-final-tree")
        screenshot("full-history-final-screen")
    }

    private func performOriginalKeyboardPrelude(_ steps: ArraySlice<[String: Any]>) throws {
        let previousMode = useOriginalCommandAReplacement
        let replacementsBefore = originalCommandAReplacementCount
        useOriginalCommandAReplacement = true
        defer { useOriginalCommandAReplacement = previousMode }
        for (index, step) in steps.enumerated() {
            try XCTContext.runActivity(named: "full-history original step \(index + 1): \(try string(step, "action"))") { _ in
                try perform(step)
            }
        }
        XCTAssertEqual(originalCommandAReplacementCount - replacementsBefore, 5,
                       "The prelude must use original keyboard replacement for Find, X, Y, Explorer search, and Label")
    }

    private func sendCommandA(_ field: XCUIElement, onApplication: Bool) {
        if onApplication { app.typeKey("a", modifierFlags: .command) }
        else { field.typeKey("a", modifierFlags: .command) }
    }

    private func assertFullHistoryCoordinates() throws {
        XCTAssertEqual(try editableField("a11y_explorer_insert_x").value as? String, "100")
        XCTAssertEqual(try editableField("a11y_explorer_insert_y").value as? String, "100")
    }

    func testEditingConnectionsHistoryAndViewport() throws {
        orient(.portrait)
        let scenarios = try XCTUnwrap(contract["scenarios"] as? [[String: Any]])
        let scenario = try XCTUnwrap(scenarios.first { $0["id"] as? String == "editing-connections-history-viewport" })
        let steps = try XCTUnwrap(scenario["steps"] as? [[String: Any]])
        for (index, step) in steps.enumerated() {
            let action = try string(step, "action")
            try XCTContext.runActivity(named: "contract step \(index + 1): \(action)") { _ in
                let started = Date()
                do {
                    try perform(step)
                    attach("step=\(index + 1), action=\(action), elapsed=\(Date().timeIntervalSince(started)), status=passed",
                           name: "contract-step-\(index + 1)")
                } catch {
                    attach("step=\(index + 1), action=\(action), status=failed, error=\(error)", name: "failed-contract-step")
                    screenshot("failed-contract-step-\(index + 1)")
                    throw error
                }
            }
        }
        screenshot("editing-final-wired-document")
    }

    /// #328: Explorer terminal and wire buttons expose their spoken label once in the native tree.
    func testExplorerTerminalAndWireButtonsExposeTheirLabelOnce() throws {
        orient(.portrait)
        try perform(["action": "newProject"])
        try perform(["action": "insertComponent", "kind": "input", "x": 100, "y": 100, "label": "A"])
        try perform(["action": "insertComponent", "kind": "output", "x": 300, "y": 100, "label": "Y"])
        try connect(source: "A", sourcePort: "out", destination: "Y", destinationPort: "in")

        try explorerSearch("Y")
        try click("a11y_explorer_ports", arguments: ["name": "Y"], region: "a11y-circuit-list")
        let expected = Set(try labels("a11y_explorer_start_connection", arguments: ["name": "Y, in"]))
        let terminal = identified("a11y-circuit-list").descendants(matching: .button)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'port:'")).firstMatch
        XCTAssertTrue(terminal.waitForExistence(timeout: 5), "The expanded row must expose its terminal button")
        attach(app.debugDescription, name: "native-tree-terminal")
        attach("identifier=\(terminal.identifier), label=\(terminal.label), value=\(String(describing: terminal.value))",
               name: "terminal-button")
        XCTAssertTrue(expected.contains(terminal.label), "The terminal button must expose its label once: \(terminal.label)")
        XCTAssertFalse((terminal.value as? String ?? "").isEmpty, "The value keeps direction, width and signal")

        try click("a11y_explorer_wires", region: "a11y-circuit-list")
        let wire = identified("a11y-wire-0")
        XCTAssertTrue(wire.waitForExistence(timeout: 5), "The wire list must expose the new wire")
        attach(app.debugDescription, name: "native-tree-wire")
        attach("identifier=\(wire.identifier), label=\(wire.label), value=\(String(describing: wire.value))", name: "wire-button")
        let name = try XCTUnwrap(wire.label.components(separatedBy: ". ").first)
        XCTAssertTrue(wire.label.count > name.count, "The wire label keeps the connections: \(wire.label)")
        XCTAssertFalse(wire.label.hasSuffix(", " + name), "The wire button must expose its name once: \(wire.label)")
        try closeExplorer()
    }

    func testOriginal271OpenEditSimulateSaveAndReopen() throws {
        orient(.portrait)
        try libraryAction("a11y_new_circuit")
        let resources = Bundle(for: Self.self)
        let contractURL = try XCTUnwrap(resources.url(forResource: "legacy-original-actions-v1", withExtension: "json"))
        contract = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: contractURL)) as? [String: Any])
        XCTAssertEqual(contract["schemaVersion"] as? Int, 1)
        let runURL = try XCTUnwrap(resources.url(forResource: "Run", withExtension: "json"))
        let run = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: runURL)) as? [String: Any])
        let fixtures = try XCTUnwrap(run["fixtures"] as? [String: String])
        let staged = URL(fileURLWithPath: try XCTUnwrap(fixtures["legacyOriginal"])).standardizedFileURL
        let documents = URL(fileURLWithPath: try string(run, "documents"), isDirectory: true).standardizedFileURL
        XCTAssertEqual(staged.deletingLastPathComponent(), documents)
        let original = try Data(contentsOf: staged)
        // A per-run filename nonce identifies the window that the system open admits.
        let source = documents.appendingPathComponent(
            staged.deletingPathExtension().lastPathComponent + "-" + UUID().uuidString + "." + staged.pathExtension)
        try original.write(to: source, options: .withoutOverwriting)
        attach(source.lastPathComponent, name: "original-2.7.1-nonce-filename")
        let originalAttachment = XCTAttachment(data: original, uniformTypeIdentifier: "org.logisim-evolution.circuit")
        originalAttachment.name = "original-2.7.1-inverter.circ"
        originalAttachment.lifetime = .keepAlways
        add(originalAttachment)
        let title = try string(contract, "circuitName")
        let scenarios = try XCTUnwrap(contract["scenarios"] as? [[String: Any]])
        let scenario = try XCTUnwrap(scenarios.first { $0["id"] as? String == "legacy-original-open-edit-simulate-reopen" })
        var savedXML: String?

        for (index, step) in try XCTUnwrap(scenario["steps"] as? [[String: Any]]).enumerated() {
            let action = try string(step, "action")
            try XCTContext.runActivity(named: "legacy contract step \(index + 1): \(action)") { _ in
                switch action {
                case "openFixture":
                    try openInDocumentWindow(source)
                    try waitForLegacyDocument(title)
                case "saveExport":
                    try assertDocument(checkpoint: string(step, "checkpoint"))
                    let exported = try exportXml()
                    try click("a11y_project_files", toolbar: "editor-project-toolbar")
                    XCTAssertTrue(identified("editor-file-menu").waitForExistence(timeout: 5))
                    try click("action_save_file")
                    let savedLabels = try labels("ios_file_saved_on_device", arguments: ["name": source.lastPathComponent])
                    XCTAssertTrue(matching(savedLabels).firstMatch.waitForExistence(timeout: 20))
                    waitUntil("Save File writes the complete observed export") {
                        (try? Data(contentsOf: source)) == Data(exported.utf8)
                    }
                    XCTAssertEqual(try Data(contentsOf: source), Data(exported.utf8))
                    XCTAssertNotEqual(try Data(contentsOf: source), original)
                    savedXML = exported
                    let attachment = XCTAttachment(data: try Data(contentsOf: source), uniformTypeIdentifier: "org.logisim-evolution.circuit")
                    attachment.name = "original-2.7.1-saved.circ"
                    attachment.lifetime = .keepAlways
                    add(attachment)
                case "reopenExport":
                    let expected = try XCTUnwrap(savedXML, "Save the exported document before reopening it")
                    try libraryAction("a11y_new_circuit")
                    XCTAssertNotEqual(try exportXml(), expected, "Opening a new circuit must replace the edited document")
                    try openInDocumentWindow(source)
                    try waitForLegacyDocument(title)
                    XCTAssertEqual(try exportXml(), expected, "Reopen the actual bytes written by Save File")
                    XCTAssertEqual(try Data(contentsOf: source), Data(expected.utf8))
                default:
                    try perform(step)
                }
                attach("step=\(index + 1), action=\(action), status=passed", name: "legacy-contract-step-\(index + 1)")
            }
        }
        screenshot("original-2.7.1-reopened-and-simulated")
    }

    /// #241 on iOS: the TalkBack AND-gate flow, driven only through the accessibility tree of the
    /// Circuit Explorer (identifiers, labels, values and traits). VoiceOver is out of scope; the
    /// simulator has no screen reader, so this checks what a reader would be given.
    func testAccessibleAndGateExplorerFlowSaveAndReopen() throws {
        orient(.portrait)
        let resources = Bundle(for: Self.self)
        let runURL = try XCTUnwrap(resources.url(forResource: "Run", withExtension: "json"))
        let run = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: runURL)) as? [String: Any])
        let runID = try string(run, "run_id")
        let documents = URL(fileURLWithPath: try string(run, "documents"), isDirectory: true).standardizedFileURL

        try XCTContext.runActivity(named: "create project and insert A, B, AND, Y through the explorer") { _ in
            try libraryAction("a11y_new_circuit")
            try assertDocument(checkpoint: "empty")
            try insert(kind: "input", x: 100, y: 100, label: "A")
            try insert(kind: "input", x: 100, y: 300, label: "B")
            try insert(kind: "and", x: 300, y: 200, label: "G")
            try insert(kind: "output", x: 500, y: 200, label: "Y")
        }
        try XCTContext.runActivity(named: "labels, values and traits of the component rows") { _ in
            for (label, kind) in [("A", "component_input"), ("B", "component_input"), ("G", "component_and"), ("Y", "component_output")] {
                try explorerSearch(label)
                let row = try explorerRow(label)
                XCTAssertEqual(row.elementType, .button, "\(label) row must expose the button trait")
                XCTAssertTrue(row.identifier.hasPrefix("component:"), "\(label) row must keep its component identifier")
                let value = row.value as? String ?? ""
                let kinds = try localizedValues(kind)
                // The value is "{kind}. {signal}", prefixed by the selected state when the row is selected.
                XCTAssertTrue(kinds.values.contains { value.hasPrefix($0 + ". ") || value.contains(". " + $0 + ". ") },
                              "\(label) row value must announce its kind and signal, got \(value)")
                if kind == "component_input" {
                    let activateLabels = try labels("a11y_explorer_activate", arguments: ["name": label])
                    _ = try reveal(activateLabels, verticalRegion: "a11y-circuit-list")
                    XCTAssertTrue(app.buttons.allElementsBoundByIndex.contains { b in activateLabels.contains { nativeLabel(b.label, matches: $0) } },
                                  "Toggle \(label) must expose the button trait")
                }
                attach("label=\(row.label), identifier=\(row.identifier), value=\(value), type=\(row.elementType.rawValue), selected=\(row.isSelected)",
                       name: "a11y-row-\(label)")
            }
            try closeExplorer()
        }
        try XCTContext.runActivity(named: "connect through terminal actions") { _ in
            try connect(source: "A", sourcePort: "out", destination: "G", destinationPort: "in0")
            try connect(source: "B", sourcePort: "out", destination: "G", destinationPort: "in1")
            try connect(source: "G", sourcePort: "out", destination: "Y", destinationPort: "in")
            let observed = try ObservedEditorDocument.parse(exportXml())
            XCTAssertEqual(observed.components.count, 4)
            XCTAssertGreaterThanOrEqual(observed.wires.count, 3)
            // The destination terminal lists the gate output among its connections.
            try explorerSearch("Y")
            try click("a11y_explorer_ports", arguments: ["name": "Y"], region: "a11y-circuit-list")
            let terminalLabels = try labels("a11y_explorer_start_connection", arguments: ["name": "Y, in"])
            _ = try reveal(terminalLabels, verticalRegion: "a11y-circuit-list")
            // The visible caption repeats the label; the button itself carries the trait and the value.
            // Compose iOS appends the visible caption to contentDescription; matching() accepts "label, caption".
            let terminal = app.buttons.matching(NSCompoundPredicate(andPredicateWithSubpredicates: [
                NSPredicate(format: "identifier BEGINSWITH 'port:' AND identifier ENDSWITH ':in'"),
                NSCompoundPredicate(orPredicateWithSubpredicates: terminalLabels.map { NSPredicate(format: "label BEGINSWITH %@", $0) })])).firstMatch
            XCTAssertTrue(terminal.waitForExistence(timeout: 5), "The terminal action must expose the button trait")
            XCTAssertTrue(terminalLabels.contains { nativeLabel(terminal.label, matches: $0) }, "Terminal label: \(terminal.label)")
            XCTAssertFalse((terminal.value as? String ?? "").isEmpty, "The terminal must announce direction, width and value")
            attach("label=\(terminal.label), value=\(terminal.value ?? "nil")", name: "a11y-terminal-Y-in")
            try click("a11y_explorer_connections", arguments: ["name": "Y, in"], region: "a11y-circuit-list")
            _ = try reveal(try labels("a11y_explorer_select", arguments: ["name": "G, out"]), verticalRegion: "a11y-circuit-list")
            try closeExplorer()
        }
        try truthTable(phase: "edited")
        try XCTContext.runActivity(named: "selection is exposed as a trait and in the value") { _ in
            try explorerSearch("Y")
            let row = try explorerRow("Y")
            XCTAssertTrue(row.identifier.hasPrefix("component:"))
            row.tap()
            waitUntil("Y row exposes the selected trait") { (try? self.explorerRow("Y").isSelected) == true }
            let selected = try localizedValues("a11y_explorer_selected", arguments: ["kind": "", "value": ""]).values
                .map { $0.components(separatedBy: ".").first ?? $0 }
            let value = try explorerRow("Y").value as? String ?? ""
            XCTAssertTrue(selected.contains { value.hasPrefix($0 + ".") }, "Selected value must start with the selected state, got \(value)")
            try closeExplorer()
        }

        try XCTContext.runActivity(named: "relaunch restores the saved workspace project") { _ in
            let saved = try exportXml()
            app.terminate()
            app.launch()
            try waitForEditorStartup()
            XCTAssertEqual(try exportXml(), saved, "Relaunch must restore the project saved in the workspace")
        }
        try truthTable(phase: "relaunched")

        let filename = "main.circ"
        let destination = documents.appendingPathComponent(filename)
        let saved = try XCTContext.runActivity(named: "save as through Files") { _ -> String in
            let expected = try exportXml()
            try click("a11y_project_files", toolbar: "editor-project-toolbar")
            XCTAssertTrue(identified("editor-file-menu").waitForExistence(timeout: 5))
            try click("action_save_as")
            let picker = NativeFilesDestinationNavigator(harness: self, documentsDirectory: documents, runID: runID)
            XCTAssertEqual(try picker.completeExport(expectedFilename: filename), destination)
            waitUntil("Files writes the exported project") { (try? Data(contentsOf: destination)) == Data(expected.utf8) }
            XCTAssertEqual(try Data(contentsOf: destination), Data(expected.utf8))
            let observed = try ObservedEditorDocument.parse(expected)
            XCTAssertEqual(observed.components.count, 4)
            for label in ["A", "B", "G", "Y"] {
                XCTAssertTrue(expected.contains("<a name=\"label\" val=\"\(label)\"/>"), "Saved XML must keep label \(label)")
            }
            XCTAssertTrue(expected.contains("name=\"AND Gate\""))
            let attachment = XCTAttachment(data: try Data(contentsOf: destination), uniformTypeIdentifier: "org.logisim-evolution.circuit")
            attachment.name = "a11y-and-saved.circ"
            attachment.lifetime = .keepAlways
            add(attachment)
            return expected
        }
        try XCTContext.runActivity(named: "relaunch and reopen the saved file") { _ in
            try libraryAction("a11y_new_circuit")
            XCTAssertNotEqual(try exportXml(), saved, "A new project must replace the edited one before reopening")
            app.terminate()
            app.launch()
            try waitForEditorStartup()
            // iPhone has one scene; openInDocumentWindow's nonce-filename anchor is for iPad windows.
            XCUIDevice.shared.system.open(destination)
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
            let opened = try fileOpenedStatus(filename)
            let origin = try labels("import_report_origin", arguments: ["origin": filename])
            waitUntil("The app reports that it opened \(filename)") {
                opened.exists || self.matching(origin).firstMatch.exists
            }
            screenshot("a11y-and-reopened-status")
            try closeImportReportIfPresented()
            XCTAssertEqual(try exportXml(), saved, "Reopen must restore the bytes written by Save as")
        }
        try truthTable(phase: "reopened")
        screenshot("a11y-and-reopened")
    }

    private func explorerRow(_ label: String) throws -> XCUIElement {
        try reveal(try labels("a11y_explorer_select", arguments: ["name": label]), verticalRegion: "a11y-circuit-list")
    }

    /// Reads Y from the explorer row value after Refresh values, and from the canvas description.
    private func truthTable(phase: String) throws {
        try XCTContext.runActivity(named: "truth table (\(phase))") { _ in
            let low = try labels("a11y_value_low"), high = try labels("a11y_value_high")
            var a = false, b = false
            for (wantA, wantB) in [(false, false), (true, false), (true, true), (false, true)] {
                for (label, want, current) in [("A", wantA, a), ("B", wantB, b)] where want != current {
                    try explorerSearch(label)
                    try click("a11y_explorer_activate", arguments: ["name": label], region: "a11y-circuit-list")
                }
                a = wantA; b = wantB
                try explorerSearch("Y")
                try click("a11y_explorer_refresh", region: "a11y-circuit-list")
                let expected = (a && b) ? high : low
                waitUntil("Y announces \(expected.first ?? "") for A=\(a) B=\(b)") {
                    guard let value = (try? self.explorerRow("Y"))?.value as? String else { return false }
                    return expected.contains { value.hasSuffix($0) }
                }
                let value = try explorerRow("Y").value as? String ?? ""
                attach("phase=\(phase), A=\(a ? 1 : 0), B=\(b ? 1 : 0), Y.value=\(value)", name: "a11y-truth-\(phase)-\(a ? 1 : 0)\(b ? 1 : 0)")
                try closeExplorer()
                try assertOutput(label: "Y", value: (a && b) ? "1" : "0")
            }
            // Leave A and B low so a saved copy starts from the same state.
            for label in ["B"] {
                try explorerSearch(label)
                try click("a11y_explorer_activate", arguments: ["name": label], region: "a11y-circuit-list")
            }
            try closeExplorer()
        }
    }

    func testProjectMouseMappings() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Pointer synthesis requires iPadOS.")
        orient(.portrait)
        let runURL = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        let run = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: runURL)) as? [String: Any])
        let runID = try string(run, "run_id")
        let inputID = "canvas-component:mouse-in-\(runID)"
        let outputID = "canvas-component:mouse-out-\(runID)"
        let fixtures = try XCTUnwrap(run["fixtures"] as? [String: String])
        let titles = try XCTUnwrap(run["titles"] as? [String: String])
        let source = URL(fileURLWithPath: try XCTUnwrap(fixtures["mouseMappings"]))
        XCUIDevice.shared.system.open(source)
        try waitForLegacyDocument(try XCTUnwrap(titles["mouseMappings"]))
        let windows = app.windows.allElementsBoundByIndex.filter { window in
            window.descendants(matching: .any).matching(identifier: inputID).firstMatch.exists
        }
        XCTAssertEqual(windows.count, 1, "Only the fixture's window may receive input")
        pointerWindow = try XCTUnwrap(windows.first)
        try click("action_wire", toolbar: "editor-command-toolbar")
        let output = identified(outputID)
        waitUntil("Y starts high") { (output.value as? String)?.hasSuffix("1.") == true }

        let pin = identified(inputID)
        XCTAssertTrue(pin.waitForExistence(timeout: 10))
        let pinPoint = pin.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        pinPoint.rightClick()
        waitUntil("mapped Poke Tool changes Y to low") { (output.value as? String)?.hasSuffix("0.") == true }
        screenshot("mapped-secondary-poke")

        let canvas = identified("editor-canvas")
        XCTAssertTrue(canvas.exists)
        let canvasFrame = canvas.frame
        let location = CGPoint(x: canvasFrame.midX, y: (pin.frame.midY + canvasFrame.maxY) / 2)
        XCTAssertTrue(canvasFrame.contains(location))
        XCTAssertGreaterThan(location.y, pin.frame.maxY + 20)
        let placement = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
            dx: location.x - app.frame.minX, dy: location.y - app.frame.minY))
        placement.click()

        let xml = try exportXml()
        let observed = try ObservedEditorDocument.parse(xml)
        XCTAssertEqual(observed.components.count, 4)
        XCTAssertTrue(observed.components.contains {
            $0["name"] as? String == "Pin" && ($0["attributes"] as? [String: String])?["width"] == "8"
        })
        XCTAssertEqual(observed.wires.count, 2)
        XCTAssertTrue(xml.contains("map=\"Button3\"") && xml.contains("map=\"Button1\""))
        attach("Secondary click changed Y from 1 to 0. Primary click inserted an eight-bit Pin.",
               name: "project-mouse-mappings-observation")
        screenshot("mapped-primary-placement")
    }

    /// Opens through the system and binds every later query to the one live window that names this file.
    private func openInDocumentWindow(_ source: URL) throws {
        func editorWindows() -> Int {
            // The import report can cover the canvas right after an open; either marks an editor window.
            app.windows.allElementsBoundByIndex.filter { window in
                ["editor-canvas", "editor-import-report"].contains {
                    window.descendants(matching: .any).matching(identifier: $0).firstMatch.exists
                }
            }.count
        }
        documentWindow = nil
        let windowsBefore = editorWindows()
        XCUIDevice.shared.system.open(source)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        let filename = source.lastPathComponent
        let anchor = try DocumentWindowAnchor(filename: filename,
            openedLabels: try labels("status_file_opened", arguments: ["name": filename]),
            originLabels: try labels("import_report_origin", arguments: ["origin": filename]),
            countedOpenedLabels: try countedTemplates("import_report_status_file", arguments: ["name": filename]))
        var resolved: DocumentWindowScope?
        var ambiguous = false
        let admitted = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            do { resolved = try DocumentWindowScope.resolve(in: self.app, anchor: anchor) }
            catch { ambiguous = true; return true }
            return resolved != nil
        }, object: app)
        guard XCTWaiter.wait(for: [admitted], timeout: 20) == .completed, !ambiguous, let scope = resolved else {
            screenshot("legacy-window-admission-failed")
            attach(app.debugDescription, name: "legacy-window-admission-failed-tree")
            XCTFail("Exactly one live window must name the opened file \(filename)")
            throw ScenarioFailure.missingControl
        }
        documentWindow = scope
        attach("editorWindowsBefore=\(windowsBefore), editorWindowsAfter=\(editorWindows()), windows=\(app.windows.count), admitted=\(scope.window.frame)",
               name: "legacy-window-admitted-\(exportIndex)")
        XCTAssertEqual(editorWindows(), max(windowsBefore, 1), "Open With must reuse the existing editor window, not add a scene")
        scope.record(in: self, stage: "legacy-file-admitted")
    }

    private func waitForLegacyDocument(_ title: String) throws {
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        let report = identified("editor-import-report")
        waitUntil("The requested legacy circuit is visible") {
            if report.exists {
                return report.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch.exists
            }
            return self.matching([title]).firstMatch.exists
        }
        try closeImportReportIfPresented()
        XCTAssertTrue(matching([title]).firstMatch.waitForExistence(timeout: 10))
    }

    func testSap1SequentialExecution() throws {
        orient(.portrait)
        let sap = try XCTUnwrap(contract["sap1"] as? [String: Any])
        try libraryAction("a11y_load_sap1")
        try resetSap1(sap)
        let count = try integer(sap, "manualHalfCycles")
        let interval = try integer(sap, "assertCounterEvery")
        for halfCycle in 1...count {
            try click("simulation_control_tick_half", toolbar: "editor-simulation-toolbar")
            if halfCycle % interval == 0 { try expectSimulationSteps(halfCycle) }
        }
        XCTAssertEqual(try simulationSteps(), 56)
        let expected = try XCTUnwrap(sap["displayNibbles"] as? [String])
        let outputLabels = try values("component_output").map(NSRegularExpression.escapedPattern(for:))
        let pattern = "(?:" + outputLabels.joined(separator: "|") + "): ([01?E]{4})(?=,|\\.|$)"
        let expression = try NSRegularExpression(pattern: pattern)
        let description = try canvasDescription()
        let range = NSRange(description.startIndex..<description.endIndex, in: description)
        let observed = expression.matches(in: description, range: range).compactMap {
            Range($0.range(at: 1), in: description).map { String(description[$0]) }
        }
        XCTAssertEqual(Array(observed.suffix(2)), expected, "SAP-1 must visibly show 08 at half-cycle 56")
        screenshot("sap1-at-half-cycle-56")
        attach(description, name: "sap1-half-cycle-56-canvas")
        let displayPredicate = NSCompoundPredicate(orPredicateWithSubpredicates: try values("component_hex_display").map {
            NSPredicate(format: "label BEGINSWITH %@", $0 + " (")
        })
        // A crowded target can sit away from its component (#365), so order by the column in the label.
        func displayColumn(_ element: XCUIElement) -> Int {
            let parts = element.label.split(separator: "(", maxSplits: 1)
            guard parts.count == 2, let comma = parts[1].firstIndex(of: ","), let column = Int(parts[1][..<comma]) else { return .max }
            return column
        }
        let displays = app.buttons.matching(displayPredicate).allElementsBoundByIndex.filter {
            $0.exists && $0.identifier.hasPrefix("canvas-component:")
        }.sorted { displayColumn($0) < displayColumn($1) }
        XCTAssertLessThanOrEqual(displays.count, expected.count)
        let useExplorer = displays.count < expected.count
        let displayColumns = [640, 680]
        XCTAssertEqual(displayColumns.count, expected.count)
        var displayIDs = Set<String>()
        for (index, pair) in zip(displayColumns, expected).enumerated() {
            let (column, nibble) = pair
            let display: XCUIElement
            if useExplorer {
                // The canvas omits overlapping targets. Search by the bundled circuit's display positions.
                try explorerSearch("(\(column), 330)")
                let rows = identified("a11y-circuit-list").descendants(matching: .any).matching(NSPredicate(
                    format: "identifier BEGINSWITH %@ AND label CONTAINS %@ AND label CONTAINS %@",
                    "component:", String(column), "330"))
                display = rows.firstMatch
                guard display.waitForExistence(timeout: 5) else {
                    XCTFail("Could not find the SAP-1 display at (\(column), 330)")
                    return
                }
                XCTAssertEqual(rows.count, 1)
            } else {
                display = displays[index]
            }
            XCTAssertTrue(displayIDs.insert(display.identifier).inserted, "Observe both distinct hex-display components")
            let hex = String(try XCTUnwrap(Int(nibble, radix: 2)), radix: 16).uppercased()
            let digit = try localizedValues("io_state_digit", arguments: ["digit": hex],
                                            nested: ["point": localizedValues("io_state_off")])
            let state = try labels("a11y_explorer_component_state", nested: [
                "kind": localizedValues("component_hex_display"),
                "value": digit,
            ])
            let actual = display.value as? String ?? ""
            attach("identifier=\(display.identifier), value=\(actual), expected=\(state)", name: "sap1-display-\(hex)")
            XCTAssertTrue(state.contains(actual),
                          "The actual display component must expose its expected nibble")
        }
        if useExplorer { try closeExplorer() }
        try assertRunPause(sap, startingAt: count)
    }

    func testSap1AutomaticRunPause() throws {
        orient(.portrait)
        let sap = try XCTUnwrap(contract["sap1"] as? [String: Any])
        try libraryAction("a11y_load_sap1")
        try resetSap1(sap)
        try assertRunPause(sap, startingAt: 0)
    }

    private func resetSap1(_ sap: [String: Any]) throws {
        try click("a11y_reset_simulation", toolbar: "editor-simulation-toolbar")
        let reset = try string(sap, "resetInput")
        try click("a11y_toggle_input", arguments: ["name": reset, "value": "0"], toolbar: "editor-signal-panel")
        try click("a11y_toggle_input", arguments: ["name": reset, "value": "1"], toolbar: "editor-signal-panel")
        try expectSimulationSteps(0)
    }

    private func assertRunPause(_ sap: [String: Any], startingAt: Int) throws {
        let runLabels = try values("action_run").flatMap { [$0, "▶ " + $0] }
        let pauseLabels = try values("action_pause").flatMap { [$0, "Ⅱ " + $0] }
        let run = try reveal(runLabels, toolbar: "editor-simulation-toolbar")
        run.tap()
        let pause = try reveal(pauseLabels, toolbar: "editor-simulation-toolbar")
        XCTAssertTrue(pause.exists, "Run must enter the observable running phase")
        RunLoop.current.run(until: Date().addingTimeInterval(Double(try integer(sap, "runMilliseconds")) / 1000))
        // Run and Pause can occupy different overflow positions. Resolve the
        // current action rather than reusing coordinates from the previous phase.
        pause.tap()
        let idle = try reveal(runLabels, toolbar: "editor-simulation-toolbar")
        XCTAssertTrue(idle.exists, "Pause must return the control to its observable idle phase")
        XCTAssertFalse(matching(pauseLabels).firstMatch.exists)
        try closeSimulationMenuIfPresented()
        let paused = try simulationSteps()
        XCTAssertGreaterThan(paused, startingAt, "Run must advance the actual simulation counter")
        RunLoop.current.run(until: Date().addingTimeInterval(Double(try integer(sap, "pauseObservationMilliseconds")) / 1000))
        XCTAssertEqual(try simulationSteps(), paused, "Pause must stop the actual simulation counter")
        attach("startedAt=\(startingAt), pausedAt=\(paused)", name: "sap1-run-pause-observation")
        screenshot("sap1-paused")
    }

    func testLargeProjectXmlCopyInPortraitAndLandscape() throws {
        orient(.portrait)
        try libraryAction("a11y_load_sap1")
        let before = try observedProjectXML(attachmentName: "sap1-portrait-copy.circ")
        let project = try ObservedEditorDocument.parse(before)
        let sap = try XCTUnwrap(contract["sap1"] as? [String: Any])
        XCTAssertEqual(project.circuitCount, try integer(sap, "circuitCount"))
        XCTAssertEqual(project.components.count, try integer(sap, "componentCount"))
        XCTAssertEqual(project.wires.count, try integer(sap, "wireCount"))
        orient(.landscapeLeft)
        XCTAssertEqual(try observedProjectXML(attachmentName: "sap1-landscape-copy.circ"), before,
                       "Rotating and copying the large project must preserve its complete XML")
        screenshot("sap1-after-landscape-copy")
    }

    private func perform(_ step: [String: Any]) throws {
        switch try string(step, "action") {
        case "newProject": try libraryAction("a11y_new_circuit")
        case "insertComponent":
            try insert(kind: string(step, "kind"), x: integer(step, "x"), y: integer(step, "y"), label: string(step, "label"))
        case "setFacing": try setFacing(label: string(step, "label"), direction: string(step, "direction"))
        case "setLabel":
            try explorerSearch(string(step, "label"))
            try click("a11y_explorer_edit", arguments: ["name": string(step, "label")], region: "a11y-circuit-list")
            try waitForInspector(of: string(step, "label"))
            let field = try editableField("property_label", within: presentedInspector())
            XCTAssertEqual(field.value as? String, try string(step, "label"), "The inspector must edit the requested component")
            try replaceEditorText(field, with: string(step, "value"))
            finishKeyboardEntry()
            try applyLabelIfPending()
            try dismissInspector()
        case "assertDocument": try assertDocument(checkpoint: string(step, "checkpoint"))
        case "undo": try click("action_undo", toolbar: "editor-project-toolbar")
        case "redo": try click("action_redo", toolbar: "editor-project-toolbar")
        case "connectPorts":
            try connect(source: string(step, "source"), sourcePort: string(step, "sourcePort"),
                        destination: string(step, "destination"), destinationPort: string(step, "destinationPort"))
        case "assertOutput": try assertOutput(label: string(step, "label"), value: string(step, "value"))
        case "activateComponent":
            try explorerSearch(string(step, "label"))
            try click("a11y_explorer_activate", arguments: ["name": string(step, "label")], region: "a11y-circuit-list")
            try closeExplorer()
        case "removeWire": try removeWire(step)
        case "selectAll": try click("a11y_select_all", toolbar: "editor-command-toolbar")
        case "copySelection": try click("a11y_copy_selection", toolbar: "editor-command-toolbar")
        case "pasteSelection":
            try click("a11y_paste_components", toolbar: "editor-command-toolbar")
            try closeSimulationMenuIfPresented()
        case "confirmPaste", "cancelPaste":
            let tag = try string(step, "action") == "confirmPaste" ? "paste-confirm" : "paste-cancel"
            let control = identified(tag)
            XCTAssertTrue(control.waitForExistence(timeout: 5))
            control.tap()
        case "deleteSelection": try click("a11y_delete_selection", toolbar: "editor-command-toolbar")
        case "assertDistinctCopies":
            for label in try XCTUnwrap(step["labels"] as? [String]) { try assertDistinctCopies(label, count: integer(step, "count")) }
        // Compact chrome keeps zoom steps and Fit in the View menu; wider chrome shows them in the window.
        case "zoomIn": for _ in 0..<(step["count"] as? Int ?? 1) { try click("a11y_zoom_in", toolbar: "editor-project-toolbar") }
        case "zoomOut": for _ in 0..<(step["count"] as? Int ?? 1) { try click("a11y_zoom_out", toolbar: "editor-project-toolbar") }
        case "captureZoom": capturedZoom[try string(step, "name")] = try zoomPercent()
        case "assertZoomGreater":
            let before = try XCTUnwrap(capturedZoom[string(step, "reference")])
            XCTAssertGreaterThan(try zoomPercent(), before)
        case "assertZoomEqual":
            let before = try XCTUnwrap(capturedZoom[string(step, "reference")])
            XCTAssertLessThanOrEqual(abs(try zoomPercent() - before), try integer(step, "tolerancePercent"))
        case "selectComponent":
            let label = try string(step, "label")
            try explorerSearch(label)
            let item = try reveal(try labels("a11y_explorer_select", arguments: ["name": label]), verticalRegion: "a11y-circuit-list")
            XCTAssertTrue(item.identifier.hasPrefix("component:"), "The selected native element must identify a component")
            item.tap()
            XCTAssertTrue(item.isSelected, "The native tree must expose selection")
            try closeExplorer()
        case "fitSelection": try click("a11y_fit_selection", toolbar: "editor-command-toolbar")
        case "fitCircuit": try click("a11y_fit_circuit", toolbar: "editor-project-toolbar")
        default: XCTFail("Unsupported shared contract action: \(step)"); throw ScenarioFailure.invalidContract
        }
    }

    private func string(_ object: [String: Any], _ key: String) throws -> String {
        try XCTUnwrap(object[key] as? String, "Missing string \(key) in \(object)")
    }

    private func integer(_ object: [String: Any], _ key: String) throws -> Int {
        try XCTUnwrap(object[key] as? Int, "Missing integer \(key) in \(object)")
    }

    private func localizedValues(_ key: String, arguments: [String: String] = [:],
                                 nested: [String: [String: String]] = [:]) throws -> [String: String] {
        let entry = try XCTUnwrap(catalog[key], "Missing localization key \(key)")
        var result: [String: String] = [:]
        for (language, raw) in entry {
            guard var text = raw as? String else { continue }
            for (name, value) in arguments { text = text.replacingOccurrences(of: "{\(name)}", with: value) }
            for (name, translations) in nested {
                let value = try XCTUnwrap(translations[language] ?? translations["en"])
                text = text.replacingOccurrences(of: "{\(name)}", with: value)
            }
            result[language] = text
        }
        XCTAssertFalse(result.isEmpty)
        return result
    }

    private func labels(_ key: String, arguments: [String: String] = [:],
                        nested: [String: [String: String]] = [:]) throws -> [String] {
        Array(try localizedValues(key, arguments: arguments, nested: nested).values)
    }

    private func click(_ key: String, arguments: [String: String] = [:], nested: [String: [String: String]] = [:],
                       toolbar: String? = nil, region: String? = nil) throws {
        let names = try labels(key, arguments: arguments, nested: nested)
        let element = try reveal(names, toolbar: toolbar, verticalRegion: region)
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 5), .completed, "The action \(key) must become enabled")
        element.tap()
    }

    /// The keyboard's Done key applies a typed label, like Enter in Evolution's attribute table, and the label's
    /// Apply then stays disabled until the text differs again; tap Apply only while the label is still pending.
    private func applyLabelIfPending() throws {
        let element = try reveal(try labels("action_apply"), toolbar: nil, verticalRegion: nil)
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: element)
        let pending = XCTWaiter.wait(for: [enabled], timeout: 2) == .completed
        if pending { element.tap() }
        attach("applyTappedAfterDone=\(pending)", name: "label-apply-pending")
    }

    private func closeSimulationMenuIfPresented() throws {
        for tag in ["editor-simulation-toolbar-menu", "editor-command-toolbar-menu"] {
            let menu = identified(tag)
            if menu.exists {
                // Close is the AlertDialog confirmation, outside its scroll body.
                try click("action_close")
                waitUntil("closed simulation toolbar menu") { !menu.exists }
            }
        }
    }

    private func libraryAction(_ key: String) throws {
        try closeImportReportIfPresented()
        // New and the built-in examples live in the File menu.
        try click(key, toolbar: "editor-project-toolbar")
        if key == "a11y_load_sap1" {
            // Workspace loading presents a behavior-changing migration report after the tap returns; SAP-1's
            // version defaults instead leave it closed behind the status Details action (C145).
            let report = identified("editor-import-report")
            let details = identified("import-report-status-details")
            waitUntil("SAP-1 opened") { report.exists || details.exists }
        }
        try closeImportReportIfPresented()
    }

    private func openExplorer() throws {
        if identified("a11y-circuit-search").exists { return }
        try click("a11y_project_tools", toolbar: "editor-project-toolbar")
        try click("a11y_explorer_title", region: "editor-tool-menu")
        XCTAssertTrue(identified("a11y-circuit-search").waitForExistence(timeout: 5))
    }

    private func explorerSearch(_ query: String) throws {
        try openExplorer()
        _ = try revealExplorerHeader(.components)
        let search = try revealExplorerHeader(.search)
        try replaceEditorText(search, with: query)
        finishKeyboardEntry()
    }

    private func explorerSearchGeometry(region: CGRect, window: CGRect, inputFrames: [CGRect])
        -> (viewport: CGRect, start: CGPoint, end: CGPoint)? {
        func finite(_ frame: CGRect) -> Bool {
            [frame.origin.x, frame.origin.y, frame.size.width, frame.size.height].allSatisfy { $0.isFinite } &&
                frame.size.width > 0 && frame.size.height > 0
        }
        guard finite(region), finite(window) else { return nil }
        var viewport = region.intersection(window)
        for input in inputFrames {
            guard [input.origin.x, input.origin.y, input.size.width, input.size.height].allSatisfy({ $0.isFinite }),
                  input.size.width >= 0 && input.size.height >= 0 else { return nil }
            if input.size.width == 0 || input.size.height == 0 { continue }
            let obstacle = input.intersection(window)
            if !obstacle.isNull && obstacle.intersects(viewport) {
                viewport = viewport.intersection(CGRect(x: window.minX, y: window.minY,
                    width: window.width, height: max(0, obstacle.minY - window.minY)))
            }
        }
        guard finite(viewport), viewport.width >= 48, viewport.height >= 48 else { return nil }
        let distance = min(CGFloat(96), viewport.height / 4)
        return (viewport, CGPoint(x: viewport.midX, y: viewport.midY - distance / 2),
                CGPoint(x: viewport.midX, y: viewport.midY + distance / 2))
    }

    private enum ExplorerHeaderTarget: String {
        case components, search
        var key: String { self == .components ? "a11y_explorer_components" : "a11y_explorer_search" }
    }

    private enum ExplorerHeaderError: Error { case unavailable(String) }

    private func explorerHeaderReadiness(frame: CGRect, viewport: CGRect, canonical: Bool, enabled: Bool)
        -> (fullyVisible: Bool, canSkipSelected: Bool)? {
        guard enabled,
              [frame.origin.x, frame.origin.y, frame.size.width, frame.size.height].allSatisfy({ $0.isFinite }),
              frame.size.width > 0 && frame.size.height > 0 else { return nil }
        let matchesControlRequirements = canonical && frame.size.width >= 48 && frame.size.height >= 48
        let contained = viewport.contains(frame)
        guard !contained || matchesControlRequirements else { return nil }
        return (contained && matchesControlRequirements, matchesControlRequirements)
    }

    private struct ExplorerHeaderObservation {
        let control: XCUIElement?
        let selected: Bool?
        let canSkipSelected: Bool
        let frame: CGRect?
        let viewport: CGRect
        let window: CGRect
        let start: CGPoint
        let end: CGPoint
        let fullyVisible: Bool
        let hittable: Bool
    }

    private func revealExplorerHeader(_ target: ExplorerHeaderTarget) throws -> XCUIElement {
        let names = try values(target.key)
        let invocation = UUID().uuidString
        var order = 0
        func observe(_ phase: String, attempt: Int) throws -> ExplorerHeaderObservation {
            defer { order += 1 }
            let sheet = identified("editor-tool-sheet")
            let list = identified("a11y-circuit-list")
            guard sheet.exists && list.exists else {
                attach("invocation=\(invocation); order=\(order); target=\(target.rawValue); phase=\(phase); container=missing",
                       name: "editor-header-observation")
                throw ExplorerHeaderError.unavailable("Explorer sheet/list is missing")
            }
            let window = app.frame
            let listFrame = list.frame
            var inputRegions: [String: CGRect] = [:]
            for (index, input) in app.keyboards.allElementsBoundByIndex.filter({ $0.exists }).enumerated() {
                inputRegions["Keyboard[\(index)]"] = input.frame
            }
            for identifier in ["inputView", "SystemInputAssistantView"] {
                let matches = app.descendants(matching: .any).matching(identifier: identifier)
                    .allElementsBoundByIndex.filter { $0.exists }
                for (index, input) in matches.enumerated() { inputRegions["\(identifier)[\(index)]"] = input.frame }
            }
            guard let geometry = explorerSearchGeometry(region: listFrame, window: window,
                                                        inputFrames: Array(inputRegions.values)) else {
                attach("invocation=\(invocation); order=\(order); target=\(target.rawValue); phase=\(phase); window=\(window); list=\(listFrame); inputs=\(inputRegions)",
                       name: "editor-header-observation")
                throw ExplorerHeaderError.unavailable("Explorer header viewport is covered or invalid")
            }
            let controls: [XCUIElement]
            switch target {
            case .components:
                controls = matching(names, within: list).allElementsBoundByIndex
                    .filter { $0.exists && $0.elementType == .button }
            case .search:
                controls = (app.textViews.matching(identifier: "a11y-circuit-search").allElementsBoundByIndex +
                            app.textFields.matching(identifier: "a11y-circuit-search").allElementsBoundByIndex)
                    .filter { $0.exists }
            }
            guard controls.count <= 1 else {
                attach("invocation=\(invocation); order=\(order); target=\(target.rawValue); phase=\(phase); count=\(controls.count); viewport=\(geometry.viewport); inputs=\(inputRegions)",
                       name: "editor-header-observation")
                throw ExplorerHeaderError.unavailable("Explorer header control is ambiguous")
            }
            let control = controls.count == 1 ? controls[0] : nil
            let frame = control?.frame
            let selected = target == .components ? control.map { $0.isSelected } : nil
            let canonical = control.map { element in names.contains { nativeLabel(element.label, matches: $0) } } ?? false
            let enabled = control?.isEnabled
            let validFrame = frame.map { rect in
                [rect.origin.x, rect.origin.y, rect.size.width, rect.size.height].allSatisfy { $0.isFinite } &&
                    rect.size.width >= 48 && rect.size.height >= 48
            } ?? false
            let visible = frame.map { validFrame && geometry.viewport.contains($0) } ?? false
            let readiness = frame.flatMap {
                explorerHeaderReadiness(frame: $0, viewport: geometry.viewport,
                                        canonical: canonical, enabled: enabled == true)
            }
            let hittable = readiness?.fullyVisible == true ? control?.isHittable : nil
            let inputDescription = inputRegions.sorted { $0.key < $1.key }
                .map { "\($0.key):\($0.value)" }.joined(separator: ";")
            attach("invocation=\(invocation); order=\(order); target=\(target.rawValue); phase=\(phase); attempt=\(attempt); count=\(controls.count); selected=\(selected.map(String.init) ?? "unobserved"); role=\(control.map { String($0.elementType.rawValue) } ?? "unobserved"); label=\(control?.label ?? "unobserved"); enabled=\(enabled.map(String.init) ?? "unobserved"); field=\(frame.map(String.init(describing:)) ?? "unobserved"); viewport=\(geometry.viewport); window=\(window); list=\(listFrame); inputs=\(inputDescription); fullyVisible=\(control == nil ? "unobserved" : String(visible)); hittable=\(hittable.map(String.init) ?? "unqueried")",
                   name: "editor-header-observation")
            if control != nil && readiness == nil {
                throw ExplorerHeaderError.unavailable("Explorer header control has an invalid label, state or frame")
            }
            return ExplorerHeaderObservation(control: control, selected: selected,
                canSkipSelected: readiness?.canSkipSelected == true, frame: frame,
                viewport: geometry.viewport, window: window, start: geometry.start, end: geometry.end,
                fullyVisible: readiness?.fullyVisible == true, hittable: hittable == true)
        }
        do {
            for attempt in 0...12 {
                let inspected = try observe("inspect", attempt: attempt)
                if target == .components && inspected.selected == true && inspected.canSkipSelected,
                   let control = inspected.control {
                    attach("invocation=\(invocation); target=components; decision=skip-publicly-selected; tapRequests=0",
                           name: "editor-header-decision")
                    return control
                }
                if inspected.fullyVisible && inspected.hittable {
                    let ready = try observe("before-action", attempt: attempt)
                    if target == .components && ready.selected == true && ready.canSkipSelected,
                       let control = ready.control {
                        attach("invocation=\(invocation); target=components; decision=skip-publicly-selected; tapRequests=0",
                               name: "editor-header-decision")
                        return control
                    }
                    guard ready.fullyVisible && ready.hittable, let control = ready.control else {
                        throw ExplorerHeaderError.unavailable("Explorer header changed before the action")
                    }
                    if target == .search { return control }
                    attach("invocation=\(invocation); target=components; decision=tap-once; tapRequests=1",
                           name: "editor-header-decision")
                    control.tap()
                    var confirmed: ExplorerHeaderObservation?
                    var observationError: Error?
                    let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                        do {
                            let next = try observe("after-components-tap", attempt: attempt)
                            guard next.control != nil && next.fullyVisible && next.hittable else {
                                throw ExplorerHeaderError.unavailable("Components became absent or covered after its tap")
                            }
                            confirmed = next
                            return next.selected == true
                        } catch { observationError = error; return true }
                    }, object: app)
                    let result = XCTWaiter.wait(for: [selected], timeout: 5)
                    if let observationError { throw observationError }
                    guard result == .completed, confirmed?.selected == true, let selectedControl = confirmed?.control else {
                        throw ExplorerHeaderError.unavailable("Components selection was not confirmed after its single tap")
                    }
                    return selectedControl
                }
                guard attempt < 12 else { break }
                let ready = try observe("before-backward-scroll", attempt: attempt)
                if target == .components && ready.selected == true && ready.canSkipSelected,
                   let control = ready.control {
                    attach("invocation=\(invocation); target=components; decision=skip-publicly-selected; tapRequests=0",
                           name: "editor-header-decision")
                    return control
                }
                if ready.fullyVisible && ready.hittable { continue }
                if let frame = ready.frame, frame.maxY > ready.viewport.maxY {
                    throw ExplorerHeaderError.unavailable("The observed header is below the viewport; backward-only preparation cannot reveal it")
                }
                let origin = app.coordinate(withNormalizedOffset: .zero)
                let window = ready.window
                attach("invocation=\(invocation); target=\(target.rawValue); decision=short-backward-scroll; start=\(ready.start); end=\(ready.end)",
                       name: "editor-header-decision")
                origin.withOffset(CGVector(dx: ready.start.x - window.minX, dy: ready.start.y - window.minY))
                    .press(forDuration: 0.05,
                           thenDragTo: origin.withOffset(CGVector(dx: ready.end.x - window.minX, dy: ready.end.y - window.minY)),
                           withVelocity: .slow, thenHoldForDuration: 0.1)
            }
            throw ExplorerHeaderError.unavailable("Explorer header did not become reachable within twelve short backward scrolls")
        } catch {
            attach(app.debugDescription, name: "editor-header-failure-tree")
            screenshot("editor-header-failure")
            XCTFail("Explorer header preparation failed: \(error)")
            throw error
        }
    }

    private func closeExplorer() throws { try click("action_close") }

    private func editableField(_ key: String, within region: CGRect? = nil) throws -> XCUIElement {
        let names = Set(try values(key))
        // X and Y lose their caption while the Insert dialog's list cuts it; their tag still names them.
        let identifier = Self.fieldIdentifiers[key]
        let container = nativeQueryRoot
        func matchingFields() -> [XCUIElement] {
            let fields = container.textViews.allElementsBoundByIndex + container.textFields.allElementsBoundByIndex
            return fields.filter {
                guard $0.exists, names.contains($0.label) || (identifier != nil && $0.identifier == identifier) else { return false }
                guard let region else { return true }
                let frame = $0.frame
                return region.contains(CGPoint(x: frame.midX, y: frame.midY))
            }
        }
        waitUntil("one presented editable control for \(key)") { matchingFields().count == 1 }
        let fields = matchingFields()
        XCTAssertEqual(fields.count, 1, "The canonical field \(key) must be unambiguous across native text roles")
        return try XCTUnwrap(fields.first)
    }

    private func replaceEditorText(_ field: XCUIElement, with text: String) throws {
        guard useOriginalCommandAReplacement else {
            try replacePresentedText(field, with: text)
            return
        }
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        recordEditingFocus(field, phase: "after-tap")
        field.typeKey("a", modifierFlags: .command)
        recordEditingFocus(field, phase: "after-select-all")
        field.typeText(XCUIKeyboardKey.delete.rawValue)
        field.typeText(text)
        XCTAssertEqual(field.value as? String, text,
                       "Replace the complete field rather than depending on the tap's caret position")
        originalCommandAReplacementCount += 1
    }

    private func recordEditingFocus(_ target: XCUIElement, phase: String) {
        let controls = app.textViews.allElementsBoundByIndex + app.textFields.allElementsBoundByIndex
        let details = (["target: \(target.debugDescription)"] + controls.map { $0.debugDescription }).joined(separator: "\n")
        let attachment = XCTAttachment(string: details)
        attachment.name = "editing-focus-\(phase)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func finishKeyboardEntry() {
        for name in ["Done", "Hide keyboard", "Concluído", "Ocultar teclado", "Return", "Retorno"] {
            let button = app.keyboards.buttons[name]
            if button.exists && button.isHittable { button.tap(); return }
        }
    }

    private func insert(kind: String, x: Int, y: Int, label: String) throws {
        try openExplorer()
        try click("a11y_explorer_insert", region: "a11y-circuit-list")
        try click("a11y_insert_custom")
        let entries = [("a11y_explorer_insert_kind", kind.uppercased()),
                       ("a11y_explorer_insert_x", String(x)), ("a11y_explorer_insert_y", String(y))]
        for (key, value) in entries {
            let field = try editableField(key)
            // The dialog keeps clear of the keyboard and scrolls (A1): bring the field into view as a finger does.
            scrollAboveKeyboard(field)
            try replaceEditorText(field, with: value)
        }
        finishKeyboardEntry()
        let insertionLabels = try values("a11y_explorer_insert_kind")
        let insertionPredicate = NSPredicate(format: "label IN %@", insertionLabels)
        try click("component_" + kind)
        waitUntil("Insertion must complete and dismiss its dialog") {
            !self.app.textViews.matching(insertionPredicate).firstMatch.exists &&
                !self.app.textFields.matching(insertionPredicate).firstMatch.exists
        }
        try explorerSearch("(\(x), \(y))")
        let names = try labels("a11y_explorer_select", arguments: ["name": ""])
        let item = try reveal(names.map { $0.trimmingCharacters(in: .whitespaces) }, verticalRegion: "a11y-circuit-list", prefix: true)
        let name = try componentCaption(item)
        try click("a11y_explorer_edit", arguments: ["name": name], region: "a11y-circuit-list")
        // Wide windows keep the inspector docked in the sidebar instead of presenting a sheet.
        let inspectorSheet = identified("editor-inspector-sheet")
        let inspectorInline = identified("editor-inspector-inline")
        waitUntil("the inspector opened from the explorer") { inspectorSheet.exists || inspectorInline.exists }
        inspectorPresentation = inspectorSheet.exists ? .sheet : .inline(inspectorInline.frame)
        try replaceEditorText(editableField("property_label"), with: label)
        finishKeyboardEntry()
        try applyLabelIfPending()
        try dismissInspector()
    }

    /// Compact windows present the inspector as a sheet. Wide windows keep it inline in the sidebar,
    /// where it is current only once the requested component is the unique selection on the canvas.
    private func waitForInspector(of label: String) throws {
        let sheet = identified("editor-inspector-sheet")
        let inline = identified("editor-inspector-inline")
        func selected() -> [XCUIElement] {
            nativeQueryRoot.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'canvas-component:'"))
                .allElementsBoundByIndex.filter { $0.exists && $0.isSelected }
        }
        waitUntil("the inspector of \(label) is presented") {
            sheet.exists || (inline.exists && selected().map(\.label) == [label])
        }
        if sheet.exists {
            inspectorPresentation = .sheet
        } else {
            XCTAssertEqual(selected().map(\.label), [label], "Only the requested component may be selected for the inline inspector")
            XCTAssertTrue(inline.exists && inline.frame.width > 0 && inline.frame.height > 0)
            inspectorPresentation = .inline(inline.frame)
        }
        attach("component=\(label), presentation=\(inspectorPresentation)", name: "inspector-presentation")
    }

    private func presentedInspector() -> CGRect? {
        if case .inline(let frame) = inspectorPresentation { return frame }
        return nil
    }

    private func dismissInspector() throws {
        if case .inline = inspectorPresentation {
            // The sidebar inspector stays in place; it must not have opened a second, modal copy.
            XCTAssertFalse(identified("editor-inspector-sheet").exists)
            inspectorPresentation = nil
            return
        }
        inspectorPresentation = nil
        let sheet = identified("editor-inspector-sheet")
        XCTAssertTrue(sheet.exists)
        let handles = app.buttons.matching(identifier: "editor-inspector-drag-handle")
        XCTAssertEqual(handles.count, 1, "Use the visible inspector's identified native drag handle")
        let handle = handles.firstMatch
        let descriptions = try values("a11y_inspector_handle")
        XCTAssertTrue(descriptions.contains { nativeLabel(handle.label, matches: $0) })
        checkFrame(handle, name: "inspector-drag-handle")
        let frame = handle.frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let start = origin.withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: frame.midY - app.frame.minY))
        let end = origin.withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: app.frame.height - 5))
        start.press(forDuration: 0.1, thenDragTo: end)
        waitUntil("dismissed property inspector") { !self.identified("editor-inspector-sheet").exists }
    }

    private func setFacing(label: String, direction: String) throws {
        try explorerSearch(label)
        try click("a11y_explorer_edit", arguments: ["name": label], region: "a11y-circuit-list")
        try waitForInspector(of: label)
        try click("inspector_edit", nested: ["name": localizedValues("attribute_label_facing")])
        // The Facing menu applies the chosen direction; it has no Apply button.
        try click("direction_" + direction)
        try dismissInspector()
    }

    private func connect(source: String, sourcePort: String, destination: String, destinationPort: String) throws {
        try explorerSearch(source)
        try click("a11y_explorer_ports", arguments: ["name": source], region: "a11y-circuit-list")
        try click("a11y_explorer_start_connection", arguments: ["name": "\(source), \(sourcePort)"], region: "a11y-circuit-list")
        try explorerSearch(destination)
        try click("a11y_explorer_ports", arguments: ["name": destination], region: "a11y-circuit-list")
        try click("a11y_explorer_complete_connection", arguments: ["name": "\(destination), \(destinationPort)"], region: "a11y-circuit-list")
        try closeExplorer()
    }

    private func removeWire(_ step: [String: Any]) throws {
        try openExplorer()
        try click("a11y_explorer_wires", region: "a11y-circuit-list")
        let from = try XCTUnwrap(step["from"] as? [Int])
        let to = try XCTUnwrap(step["to"] as? [Int])
        XCTAssertEqual(from.count, 2); XCTAssertEqual(to.count, 2)
        guard from.count == 2 && to.count == 2 else { throw ScenarioFailure.invalidContract }
        let name = try localizedValues("a11y_explorer_wire", arguments: ["index": String(integer(step, "index")),
            "x1": String(from[0]), "y1": String(from[1]), "x2": String(to[0]), "y2": String(to[1])])
        try click("a11y_explorer_remove", nested: ["name": name], region: "a11y-circuit-list")
        try closeExplorer()
    }

    private func componentCaption(_ item: XCUIElement) throws -> String {
        let captions = item.staticTexts.allElementsBoundByIndex.filter { $0.exists }.map(\.label)
        do {
            return try NativeComponentCaption.literal(nativeLabel: item.label, captions: captions,
                                                      selectTemplates: values("a11y_explorer_select"))
        } catch {
            attach("identifier=\(item.identifier), label=\(item.label), captions=\(captions)", name: "invalid-component-caption")
            XCTFail("The native component row must expose one unambiguous literal caption: \(error)")
            throw error
        }
    }

    private func assertDistinctCopies(_ label: String, count: Int) throws {
        try explorerSearch(label)
        let region = identified("a11y-circuit-list")
        XCTAssertTrue(region.waitForExistence(timeout: 5))
        let rows = region.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "component:"))
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 5), "The filtered explorer must expose component rows")
        var captionsByID: [String: String] = [:]
        for direction in [true, false] {
            for _ in 0..<6 {
                let viewport = region.frame.intersection(app.frame)
                let observed = rows.allElementsBoundByIndex.filter { row in
                    guard row.exists else { return false }
                    let frame = row.frame
                    guard frame.width > 0, frame.height > 0, frame.midX.isFinite, frame.midY.isFinite,
                          viewport.contains(CGPoint(x: frame.midX, y: frame.midY)) else { return false }
                    return row.isHittable
                }
                for row in observed {
                    let caption = try componentCaption(row)
                    guard let id = NativeComponentCaption.duplicateIdentity(identifier: row.identifier,
                                                                           caption: caption, label: label) else { continue }
                    if let earlier = captionsByID[id] {
                        XCTAssertEqual(earlier, caption, "A component identity changed its caption while scanning the unchanged project")
                    }
                    captionsByID[id] = caption
                }
                if captionsByID.count >= count { break }
                try scroll(region, horizontal: false, forward: direction)
            }
            if captionsByID.count >= count { break }
        }
        XCTAssertEqual(captionsByID.count, count, "Paste must expose distinct component identities with label and location captions")
        XCTAssertEqual(Set(captionsByID.values).count, captionsByID.count,
                       "Each pasted copy must have a caption that tells it apart: its location, or its ID when copies share one")
        attach(captionsByID.sorted { $0.key < $1.key }.map { "\($0.key): \($0.value)" }.joined(separator: "\n"),
               name: "distinct-\(label)-native-identities")
        try closeExplorer()
    }

    private func assertOutput(label: String, value: String) throws {
        let pattern = NSRegularExpression.escapedPattern(for: label) + ": " + NSRegularExpression.escapedPattern(for: value) + "(?=,|\\.|$)"
        let expression = try NSRegularExpression(pattern: pattern)
        waitUntil("output \(label)=\(value)") {
            guard let description = try? self.canvasDescription() else { return false }
            return expression.firstMatch(in: description, range: NSRange(description.startIndex..<description.endIndex, in: description)) != nil
        }
    }

    private func zoomPercent() throws -> Int {
        // The status bar names its exact-zoom control with the current percentage, as Android reads it.
        let patterns = try values("a11y_zoom_exact").map { template in
            try NSRegularExpression(pattern: "^" + NSRegularExpression.escapedPattern(for: template)
                .replacingOccurrences(of: NSRegularExpression.escapedPattern(for: "{percent}"), with: "(\\d+)"))
        }
        var observed = Set<Int>()
        func walk(_ node: XCUIElementSnapshot) {
            let label = node.label as NSString
            for pattern in patterns {
                if let match = pattern.firstMatch(in: node.label, range: NSRange(location: 0, length: label.length)),
                   let value = Int(label.substring(with: match.range(at: 1))) { observed.insert(value) }
            }
            node.children.forEach(walk)
        }
        walk(try app.snapshot())
        XCTAssertEqual(observed.count, 1, "The zoom control must expose one observed percentage: \(observed)")
        return try XCTUnwrap(observed.first)
    }

    private func simulationSteps() throws -> Int {
        let patterns = try values("simulation_ticks").map { template -> NSRegularExpression in
            let escaped = NSRegularExpression.escapedPattern(for: template)
                .replacingOccurrences(of: NSRegularExpression.escapedPattern(for: "{count}"), with: "([0-9]+)")
            return try NSRegularExpression(pattern: "^" + escaped + "$")
        }
        let counterPredicate = NSCompoundPredicate(orPredicateWithSubpredicates: patterns.map {
            NSPredicate(format: "label MATCHES %@", $0.pattern)
        })
        let counts = Set(app.staticTexts.matching(counterPredicate).allElementsBoundByIndex.compactMap { element -> Int? in
            let text = element.label
            for pattern in patterns {
                if let match = pattern.firstMatch(in: text, range: NSRange(text.startIndex..<text.endIndex, in: text)),
                   let range = Range(match.range(at: 1), in: text) { return Int(text[range]) }
            }
            return nil
        })
        XCTAssertEqual(counts.count, 1, "The actual simulation counter must be visible and unambiguous")
        return try XCTUnwrap(counts.first)
    }

    private func expectSimulationSteps(_ expected: Int) throws {
        let published = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            (try? self.simulationSteps()) == expected
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [published], timeout: 10), .completed,
                       "The worker must publish exactly \(expected) half cycles without another clock tap")
        XCTAssertEqual(try simulationSteps(), expected)
    }

    private func exportXml() throws -> String {
        exportIndex += 1
        return try observedProjectXML(attachmentName: "observed-editor-export-\(exportIndex).circ")
    }

    private func assertDocument(checkpoint: String) throws {
        let snapshots = try XCTUnwrap(contract["snapshots"] as? [String: [String: Any]])
        let expected = try XCTUnwrap(snapshots[checkpoint])
        let observed = try ObservedEditorDocument.parse(exportXml())
        XCTAssertEqual(observed.circuitCount, try integer(expected, "circuitCount"), checkpoint)
        XCTAssertEqual(try observed.components.map(canonical).sorted(),
                       try XCTUnwrap(expected["components"] as? [[String: Any]]).map(canonical).sorted(), checkpoint)
        XCTAssertEqual(try observed.wires.map(canonical).sorted(),
                       try XCTUnwrap(expected["wires"] as? [[[Int]]]).map(canonical).sorted(), checkpoint)
        attach("checkpoint=\(checkpoint), circuits=\(observed.circuitCount), components=\(observed.components.count), wires=\(observed.wires.count)",
               name: "observed-checkpoint-\(checkpoint)")
    }

    private func canonical(_ object: Any) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), as: UTF8.self)
    }

    private func waitUntil(_ description: String, condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 10), .completed, description)
    }

    private enum ScenarioFailure: Error { case missingControl, invalidContract }
}

private enum NativeComponentCaption {
    enum ResolutionFailure: Error { case missingOrAmbiguous([String]) }

    static func literal(nativeLabel: String, captions: [String], selectTemplates: [String]) throws -> String {
        let matches = Set(captions.filter { caption in
            !caption.isEmpty && selectTemplates.contains { template in
                guard template.components(separatedBy: "{name}").count == 2 else { return false }
                let canonical = template.replacingOccurrences(of: "{name}", with: caption)
                return nativeLabel == canonical || nativeLabel == canonical + ", " + caption
            }
        })
        guard matches.count == 1, let caption = matches.first else {
            throw ResolutionFailure.missingOrAmbiguous(matches.sorted())
        }
        return caption
    }

    static func duplicateIdentity(identifier: String, caption: String, label: String) -> String? {
        let prefix = "component:"
        guard identifier.hasPrefix(prefix), caption.hasPrefix(label + " (") else { return nil }
        let id = String(identifier.dropFirst(prefix.count))
        guard !id.isEmpty else { return nil }
        // The explorer names a duplicate label by its location; it appends the internal id only when two copies
        // with the same name share one location, so the identity comes from the row's identifier.
        let pattern = "^" + NSRegularExpression.escapedPattern(for: label) + " \\((-?[0-9]+), (-?[0-9]+)\\)(?:, "
            + NSRegularExpression.escapedPattern(for: id) + ")?$"
        guard let expression = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(caption.startIndex..<caption.endIndex, in: caption)
        guard expression.firstMatch(in: caption, range: range)?.range == range else { return nil }
        return id
    }
}

private final class ObservedEditorDocument: NSObject, XMLParserDelegate {
    var circuitCount = 0
    var components: [[String: Any]] = []
    var wires: [[[Int]]] = []
    private var path: [String] = []
    private var current: [String: Any]?
    private var invalid = false
    private var attributeName: String?
    private var attributeValue: String?
    private var attributeText = ""

    static func parse(_ xml: String) throws -> ObservedEditorDocument {
        let observed = ObservedEditorDocument()
        let parser = XMLParser(data: Data(xml.utf8))
        parser.shouldResolveExternalEntities = false
        parser.delegate = observed
        XCTAssertTrue(parser.parse(), "The observed export must be valid XML: \(String(describing: parser.parserError))")
        XCTAssertFalse(observed.invalid, "The observed export has an invalid component, attribute or wire")
        return observed
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?,
                attributes: [String: String]) {
        if path == ["project"] && name == "circuit" { circuitCount += 1 }
        if path == ["project", "circuit"] && name == "comp" {
            if let kind = attributes["name"], let point = point(attributes["loc"]) {
                current = ["name": kind, "location": point, "attributes": [String: String]()]
            } else { invalid = true }
        }
        if path == ["project", "circuit", "comp"] && name == "a" {
            let values = current?["attributes"] as? [String: String] ?? [:]
            attributeName = attributes["name"]
            attributeValue = attributes["val"]
            attributeText = ""
            if attributeName == nil || values[attributeName ?? ""] != nil { invalid = true }
        }
        if path == ["project", "circuit"] && name == "wire" {
            if let from = point(attributes["from"]), let to = point(attributes["to"]) { wires.append([from, to]) }
            else { invalid = true }
        }
        path.append(name)
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        if path == ["project", "circuit", "comp", "a"] {
            if let key = attributeName {
                var values = current?["attributes"] as? [String: String] ?? [:]
                if attributeValue != nil && !attributeText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    invalid = true
                }
                values[key] = attributeValue ?? attributeText
                current?["attributes"] = values
            }
            attributeName = nil
            attributeValue = nil
            attributeText = ""
        }
        if path == ["project", "circuit", "comp"], let component = current { components.append(component); current = nil }
        _ = path.popLast()
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if path == ["project", "circuit", "comp", "a"] { attributeText += string }
    }

    func parser(_ parser: XMLParser, foundCDATA data: Data) {
        if path == ["project", "circuit", "comp", "a"] {
            if let text = String(data: data, encoding: .utf8) { attributeText += text }
            else { invalid = true }
        }
    }

    private func point(_ value: String?) -> [Int]? {
        guard let value, value.hasPrefix("("), value.hasSuffix(")") else { return nil }
        let parts = value.dropFirst().dropLast().split(separator: ",", omittingEmptySubsequences: false)
        guard parts.count == 2,
              let x = Int(parts[0].trimmingCharacters(in: .whitespaces)),
              let y = Int(parts[1].trimmingCharacters(in: .whitespaces)) else { return nil }
        return [x, y]
    }
}
