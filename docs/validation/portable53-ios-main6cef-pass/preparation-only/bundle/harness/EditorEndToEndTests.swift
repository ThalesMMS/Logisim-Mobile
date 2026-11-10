import Foundation
import XCTest
import UIKit

/// Drives production editor controls; the shared JSON supplies literal actions and expectations.
final class EditorEndToEndTests: NativeUIHarness {
    private var contract: [String: Any] = [:]
    private var capturedZoom: [String: Int] = [:]
    private var exportIndex = 0

    override func setUpWithError() throws {
        try super.setUpWithError()
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "editor-actions-v1", withExtension: "json"))
        contract = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        XCTAssertEqual(contract["schemaVersion"] as? Int, 1)
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

    func testSap1SequentialExecution() throws {
        orient(.portrait)
        let sap = try XCTUnwrap(contract["sap1"] as? [String: Any])
        try libraryAction("a11y_load_sap1")
        try resetSap1(sap)
        let count = try integer(sap, "manualHalfCycles")
        let interval = try integer(sap, "assertCounterEvery")
        for halfCycle in 1...count {
            try click("a11y_step_clock", toolbar: "editor-simulation-toolbar")
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
        let displayPredicate = NSCompoundPredicate(orPredicateWithSubpredicates: try values("component_hex_display").map {
            NSPredicate(format: "label BEGINSWITH %@", $0 + " (")
        })
        let displays = app.buttons.matching(displayPredicate).allElementsBoundByIndex.filter {
            $0.exists && $0.identifier.hasPrefix("canvas-component:")
        }.sorted { $0.frame.midX < $1.frame.midX }
        XCTAssertEqual(displays.count, expected.count, "Observe both distinct hex-display components")
        for (display, nibble) in zip(displays, expected) {
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
        screenshot("sap1-at-half-cycle-56")
        attach(description, name: "sap1-half-cycle-56-canvas")
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
        case "pasteSelection": try click("a11y_paste_components", toolbar: "editor-command-toolbar")
        case "deleteSelection": try click("a11y_delete_selection", toolbar: "editor-command-toolbar")
        case "assertDistinctCopies":
            for label in try XCTUnwrap(step["labels"] as? [String]) { try assertDistinctCopies(label, count: integer(step, "count")) }
        case "zoomIn": for _ in 0..<(step["count"] as? Int ?? 1) { try click("a11y_zoom_in") }
        case "zoomOut": for _ in 0..<(step["count"] as? Int ?? 1) { try click("a11y_zoom_out") }
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
        case "fitCircuit": try click("a11y_fit")
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
        if visibleMatch(try values(key)) == nil { try click("a11y_open_library", toolbar: "editor-command-toolbar") }
        try click(key)
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
        try click("a11y_explorer_components", region: "a11y-circuit-list")
        _ = try reveal(try values("a11y_explorer_search"), verticalRegion: "a11y-circuit-list")
        try replacePresentedText(identified("a11y-circuit-search"), with: query)
        finishKeyboardEntry()
    }

    private func closeExplorer() throws { try click("action_close") }

    private func editableField(_ key: String, within region: XCUIElement? = nil) throws -> XCUIElement {
        let names = Set(try values(key))
        let container = region ?? app!
        func matchingFields() -> [XCUIElement] {
            let fields = container.textViews.allElementsBoundByIndex + container.textFields.allElementsBoundByIndex
            return fields.filter { $0.exists && names.contains($0.label) }
        }
        waitUntil("one presented editable control for \(key)") { matchingFields().count == 1 }
        let fields = matchingFields()
        XCTAssertEqual(fields.count, 1, "The canonical field \(key) must be unambiguous across native text roles")
        return try XCTUnwrap(fields.first)
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
        let entries = [("a11y_explorer_insert_kind", kind.uppercased()),
                       ("a11y_explorer_insert_x", String(x)), ("a11y_explorer_insert_y", String(y))]
        for (key, value) in entries { try replacePresentedText(editableField(key), with: value) }
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
        XCTAssertTrue(identified("editor-inspector-sheet").waitForExistence(timeout: 5))
        try replacePresentedText(editableField("property_label"), with: label)
        finishKeyboardEntry()
        try click("action_apply")
        try dismissInspector()
    }

    private func dismissInspector() throws {
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
        XCTAssertTrue(identified("editor-inspector-sheet").waitForExistence(timeout: 5))
        try click("inspector_edit", nested: ["name": localizedValues("attribute_label_facing")])
        try click("direction_" + direction)
        try click("action_apply")
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
        XCTAssertEqual(captionsByID.count, count, "Paste must expose distinct component identities with complete label, location and ID captions")
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
        let fit = try reveal(try values("a11y_fit"))
        let candidates = [fit.label, fit.value as? String ?? ""] + fit.descendants(matching: .staticText).allElementsBoundByIndex.map { $0.label }
        let values = Set(candidates.compactMap { value -> Int? in
            let clean = value.trimmingCharacters(in: .whitespaces)
            guard clean.hasSuffix("%") else { return nil }
            return Int(clean.dropLast())
        })
        XCTAssertEqual(values.count, 1, "The zoom control must expose one observed percentage")
        return try XCTUnwrap(values.first)
    }

    private func simulationSteps() throws -> Int {
        let patterns = try values("simulation_steps").map { template -> NSRegularExpression in
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
        let pattern = "^" + NSRegularExpression.escapedPattern(for: label) + " \\((-?[0-9]+), (-?[0-9]+)\\), "
            + NSRegularExpression.escapedPattern(for: id) + "$"
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
