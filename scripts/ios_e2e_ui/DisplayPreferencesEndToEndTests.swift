import XCTest
import UIKit

/// On-screen evidence for the display preferences (#312), the remaining colours and value characters (#311),
/// the HDL code editor theme (#310) and the PLA table
/// file actions through the native Files picker (#316). The system light/dark switch itself is driven from the
/// host (`xcrun simctl ui <udid> appearance`), which a UI test cannot do.
final class DisplayPreferencesEndToEndTests: NativeUIHarness {
    private struct RunConfiguration: Decodable {
        let run_id: String
        let documents: String
    }

    private var run: RunConfiguration!
    private var stage = 0

    override func setUpWithError() throws {
        try super.setUpWithError()
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        run = try JSONDecoder().decode(RunConfiguration.self, from: Data(contentsOf: url))
        XCTAssertNotNil(run.run_id.range(of: "^[a-f0-9]{12}$", options: .regularExpression))
    }

    // MARK: #316

    /// Evolution's PlaTable.save output for this table (fixtures/pla/evolution-saved.txt).
    private static let evolutionSaved = "# Logisim PLA program table\n1x0x 101 # first match wins\n1xxx 010\n0011 111 # spaced comment\nx1x0 001\n"

    func testPlaTableExportAndImportUseTheFilesPicker() throws {
        let documents = URL(fileURLWithPath: run.documents, isDirectory: true)
        let project = documents.appendingPathComponent("pla-proof.circ")
        try Data("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project source="3.8.0" version="1.0"><lib desc="#Gates" name="0"/><main name="PLA proof"/>
            <circuit name="PLA proof"><comp lib="0" loc="(200,150)" name="PLA"><a name="in_width" val="4"/><a name="out_width" val="3"/><a name="label" val="decoder"/><a name="table" val="1x0x 101 # first match wins&#10;1xxx 010&#10;0011 111 # spaced comment&#10;x1x0 001&#10;"/></comp></circuit></project>
            """.utf8).write(to: project)
        // fixtures/pla/mobile-saved.txt has five inputs; the other file matches the PLA's 4x3 widths.
        try Data("# Logisim PLA program table\n10x1x 11 # alpha\nx0000 01\n".utf8)
            .write(to: documents.appendingPathComponent("pla-5x2.txt"))
        try Data("# Logisim PLA program table\n0000 111 # imported\n1111 000\n".utf8)
            .write(to: documents.appendingPathComponent("pla-4x3.txt"))

        XCUIDevice.shared.system.open(project)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        let pla = identified("canvas-component:import-1")
        if !pla.waitForExistence(timeout: 20) { try closeImportReportIfPresented() }
        try closeImportReportIfPresented()
        XCTAssertTrue(pla.waitForExistence(timeout: 20), "The PLA fixture must open")
        record("pla-project-open")
        try openTableEditor(pla)
        record("pla-table-editor")

        try tapLabel("action_export")
        let picker = NativeFilesDestinationNavigator(harness: self, documentsDirectory: documents, runID: run.run_id)
        let exported = try picker.completeExport(expectedFilename: "decoder.txt")
        let written = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (try? String(contentsOf: exported, encoding: .utf8)) == Self.evolutionSaved
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [written], timeout: 25), .completed,
                       "Files must receive Evolution's PLA file bytes at \(exported.path)")
        attach((try? String(contentsOf: exported, encoding: .utf8)) ?? "<unreadable>", name: "exported-decoder.txt")
        record("pla-after-export")

        try tapLabel("pla_import_button")
        try NativeFilesPeerNavigator(harness: self, peerDisplayName: app.label, runID: run.run_id).open(filename: "pla-5x2.txt")
        let mismatch = app.staticTexts["File contains unexpected input width, expected 4 but found 5"]
        XCTAssertTrue(mismatch.waitForExistence(timeout: 15), "Evolution's plaUnexpectedInputWidth message must appear")
        let errorTitle = matching(try values("pla_load_error_title")).firstMatch
        XCTAssertTrue(errorTitle.exists, "The error must use Evolution's plaLoadErrorTitle")
        XCTAssertTrue(tableText().contains("1x0x 101"), "A mismatching file must not replace the table")
        record("pla-width-mismatch")

        try tapLabel("pla_import_button")
        try NativeFilesPeerNavigator(harness: self, peerDisplayName: app.label, runID: run.run_id).open(filename: "pla-4x3.txt")
        harnessWait("the imported table", timeout: 15) { self.tableText().contains("0000 111 # imported") }
        record("pla-imported")
    }

    private func openTableEditor(_ pla: XCUIElement) throws {
        pla.tap()
        let properties = identified("tool-properties")
        if identified("editor-inspector-inline").waitForExistence(timeout: 3) {
            // The docked inspector already follows the selection.
        } else if properties.waitForExistence(timeout: 5) { properties.tap() } else { try tapKey("a11y_open_properties") }
        let edit = matching(try values("inspector_edit").map { $0.replacingOccurrences(of: "{name}", with: "Truth table") }, prefix: true).firstMatch
        let importButton = matching(try values("pla_import_button")).firstMatch
        // The inspector sheet may still be settling; repeat the tap until the table dialog is shown.
        for _ in 0..<3 where !importButton.exists {
            try scrollUntilHittable(edit)
            edit.tap()
            _ = importButton.waitForExistence(timeout: 4)
        }
        XCTAssertTrue(importButton.exists, "The PLA table dialog must show Import and Export")
    }

    private func tableText() -> String {
        let values = app.textViews.allElementsBoundByIndex.compactMap { $0.value as? String } +
            app.textFields.allElementsBoundByIndex.compactMap { $0.value as? String }
        return values.joined(separator: "\n")
    }

    // MARK: #311

    /// Two incompatible-width nets (4/1, and 2/3/2 whose common width 2 highlights the 3-bit end), a 1-bit net with a junction to poke and a floating probe.
    private static let widthProject = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project source="3.8.0" version="1.0"><lib desc="#Wiring" name="0"/><main name="Width proof"/>
        <circuit name="Width proof">
        <wire from="(100,100)" to="(200,100)"/><wire from="(100,180)" to="(140,180)"/><wire from="(140,180)" to="(200,180)"/>
        <wire from="(100,260)" to="(160,260)"/><wire from="(160,260)" to="(220,260)"/><wire from="(160,260)" to="(160,320)"/>
        <wire from="(260,340)" to="(300,340)"/>
        <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="w4"><a name="width" val="4"/></comp>
        <comp lib="0" loc="(200,100)" name="Pin" logisim-mobile-id="w1"><a name="type" val="output"/><a name="facing" val="west"/></comp>
        <comp lib="0" loc="(100,180)" name="Pin" logisim-mobile-id="w2"><a name="width" val="2"/></comp>
        <comp lib="0" loc="(200,180)" name="Pin" logisim-mobile-id="w3"><a name="type" val="output"/><a name="facing" val="west"/><a name="width" val="3"/></comp>
        <wire from="(140,180)" to="(140,220)"/><wire from="(140,220)" to="(200,220)"/>
        <comp lib="0" loc="(200,220)" name="Pin" logisim-mobile-id="w2b"><a name="type" val="output"/><a name="facing" val="west"/><a name="width" val="2"/></comp>
        <comp lib="0" loc="(100,260)" name="Pin" logisim-mobile-id="p"><a name="label" val="P"/></comp>
        <comp lib="0" loc="(220,260)" name="Pin" logisim-mobile-id="q"><a name="type" val="output"/><a name="facing" val="west"/><a name="label" val="Q"/></comp>
        <comp lib="0" loc="(160,320)" name="Probe" logisim-mobile-id="probe-driven"/>
        <comp lib="0" loc="(300,340)" name="Probe" logisim-mobile-id="probe-floating"><a name="label" val="F"/></comp>
        </circuit></project>
        """
    /// Y = A·B + C: the minimized K-map has two groups.
    private static let logicProject = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project source="3.8.0" version="1.0"><lib desc="#Wiring" name="0"/><lib desc="#Gates" name="1"/><main name="Logic proof"/>
        <circuit name="Logic proof">
        <wire from="(100,100)" to="(140,100)"/><wire from="(100,140)" to="(140,140)"/><wire from="(190,120)" to="(240,120)"/>
        <wire from="(100,200)" to="(220,200)"/><wire from="(220,200)" to="(220,160)"/><wire from="(220,160)" to="(240,160)"/>
        <wire from="(290,140)" to="(340,140)"/>
        <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="a"><a name="label" val="A"/></comp>
        <comp lib="0" loc="(100,140)" name="Pin" logisim-mobile-id="b"><a name="label" val="B"/></comp>
        <comp lib="0" loc="(100,200)" name="Pin" logisim-mobile-id="c"><a name="label" val="C"/></comp>
        <comp lib="1" loc="(190,120)" name="AND Gate" logisim-mobile-id="and"/>
        <comp lib="1" loc="(290,140)" name="OR Gate" logisim-mobile-id="or"/>
        <comp lib="0" loc="(340,140)" name="Pin" logisim-mobile-id="y"><a name="type" val="output"/><a name="facing" val="west"/><a name="label" val="Y"/></comp>
        </circuit></project>
        """

    private func openFixture(_ name: String, _ xml: String, waitingFor id: String) throws {
        let url = URL(fileURLWithPath: run.documents, isDirectory: true).appendingPathComponent(name)
        try Data(xml.utf8).write(to: url)
        XCUIDevice.shared.system.open(url)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        let marker = identified(id)
        if !marker.waitForExistence(timeout: 20) { try closeImportReportIfPresented() }
        try closeImportReportIfPresented()
        XCTAssertTrue(marker.waitForExistence(timeout: 20), "\(name) must open")
    }

    private func widthMessage() -> String {
        let message = identified("editor-width-error")
        XCTAssertTrue(message.waitForExistence(timeout: 15), "The width error message must be shown")
        return message.label
    }

    func testRemainingColorsAndValueCharacters() throws {
        try openFixture("width-proof.circ", Self.widthProject, waitingFor: "canvas-component:p")
        let message = widthMessage()
        attach(message, name: "width-error-message")
        XCTAssertEqual(message, "Incompatible widths (2)", "Evolution's canvasWidthError with the count")
        record("width-error-default")

        // Wire Poke on the P–Q net, halfway between P (body centre x=90) and the junction (x=160).
        let p = identified("canvas-component:p").frame, q = identified("canvas-component:q").frame
        let scale = (q.midX - p.midX) / 140
        // These fixtures have no <toolbar>, so no tools, as in Evolution (#344); the editor stays in Poke.
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: p.midX + 40 * scale, dy: p.midY)).tap()
        Thread.sleep(forTimeInterval: 1)
        record("wire-poke-stroke")

        try choose("preferences-unknown-char", optionLabel: "?")
        record("unknown-char-question-mark")

        try openColorDialog()
        let role = matching(try values("preferences_color_role").map { $0 + ": " }, prefix: true).firstMatch
        role.tap()
        let widthError = app.buttons.matching(NSPredicate(format: "label == %@", "Width error color")).firstMatch
        for _ in 0..<12 where !(widthError.exists && widthError.isHittable) {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)))
        }
        XCTAssertTrue(widthError.isHittable, "Width error color must be listed")
        // Compose exposes the outlined field as a text view labelled with its hint.
        let field = app.descendants(matching: .any).matching(NSPredicate(format: "elementType IN %@ AND label IN %@",
            [XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue], try values("preferences_color_value"))).firstMatch
        // A tap during the fling of the long menu only stops it.
        for _ in 0..<3 where !field.exists {
            if widthError.exists && widthError.isHittable { widthError.tap() }
            _ = field.waitForExistence(timeout: 2)
        }
        XCTAssertTrue(field.waitForExistence(timeout: 5), "The RGB field must be shown")
        attach("\(field.value ?? "")", name: "width-error-default-value")
        field.tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 10) + "#0000FF")
        record("color-dialog-width-error")
        try tapLabel("action_apply")
        try tapLabel("action_close")
        closeSheet()
        record("changed-before-restart")

        // A new process must load the saved character and colour.
        app.terminate()
        app.launch()
        try waitForEditorStartup()
        try openFixture("width-proof-restart.circ", Self.widthProject, waitingFor: "canvas-component:p")
        XCTAssertEqual(widthMessage(), "Incompatible widths (2)")
        record("after-restart")

        try openColorDialog()
        try tapLabel("preferences_colors_restore")
        try tapLabel("action_close")
        closeSheet()
        try choose("preferences-unknown-char", optionLabel: "U")
        record("restored-defaults")

        try openFixture("logic-proof.circ", Self.logicProject, waitingFor: "canvas-component:and")
        try openTool("analysis_title")
        try tapLabel("analysis_read_circuit")
        try tapLabel("analysis_simplify")
        try tapLabel("analysis_karnaugh")
        let numbered = identified("kmap-style-numbered")
        try scrollUntilHittable(numbered)
        numbered.tap()
        let cells = identified("kmap-cells")
        XCTAssertTrue(cells.waitForExistence(timeout: 10))
        attach(cells.label, name: "kmap-groups")
        XCTAssertEqual(cells.label, "Karnaugh map groups: 2")
        record("kmap-groups")
        try tapLabel("analysis_table")
        try tapLabel("analysis_select_block")
        identified("analysis-cell-0-1").tap()
        identified("analysis-cell-2-3").tap()
        record("table-cursor-selection")
        identified("editor-tool-close").tap()
    }

    /// Preferences → Canvas colors; the sheet may still be settling, so repeat the tap until the dialog shows.
    private func openColorDialog() throws {
        try openPreferences()
        let colors = app.buttons.matching(NSPredicate(format: "label IN %@", try values("preferences_colors"))).firstMatch
        try scrollUntilHittable(colors)
        let role = matching(try values("preferences_color_role").map { $0 + ": " }, prefix: true).firstMatch
        for _ in 0..<4 where !role.exists {
            if colors.exists && colors.isHittable { colors.tap() }
            _ = role.waitForExistence(timeout: 4)
        }
        if !role.exists { record("color-dialog-missing") }
        XCTAssertTrue(role.exists, "The colour dialog must open")
    }

    // MARK: #312 and #310

    func testDisplayPreferencesAndEditorTheme() throws {
        let project = URL(fileURLWithPath: run.documents, isDirectory: true).appendingPathComponent("display-proof.circ")
        try Data("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project source="3.8.0" version="1.0"><lib desc="#Wiring" name="0"/><lib desc="#Gates" name="1"/><main name="Display proof"/>
            <circuit name="Display proof">
            <wire from="(100,100)" to="(140,100)"/><wire from="(100,140)" to="(140,140)"/><wire from="(190,120)" to="(240,120)"/>
            <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="a"><a name="label" val="A"/></comp>
            <comp lib="0" loc="(100,140)" name="Pin" logisim-mobile-id="b"><a name="label" val="B"/></comp>
            <comp lib="1" loc="(190,120)" name="AND Gate" logisim-mobile-id="and"><a name="label" val="AND"/></comp>
            <comp lib="0" loc="(240,120)" name="Pin" logisim-mobile-id="y"><a name="type" val="output"/><a name="facing" val="west"/><a name="label" val="Y"/></comp>
            </circuit></project>
            """.utf8).write(to: project)
        XCUIDevice.shared.system.open(project)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        let gate = identified("canvas-component:and")
        if !gate.waitForExistence(timeout: 20) { try closeImportReportIfPresented() }
        try closeImportReportIfPresented()
        XCTAssertTrue(gate.waitForExistence(timeout: 20), "The display fixture must be shown")
        gate.tap() // Poke also selects a gate.
        record("halo-on-selected-and")

        // Touch equivalent of the component tip: the long-press menu lists the tip first.
        gate.press(forDuration: 1.2)
        let tip = identified("canvas-component-tip")
        let tipShown = tip.waitForExistence(timeout: 5)
        attach("long-press tip shown=\(tipShown) label=\(tip.exists ? tip.label : "")", name: "component-tip-long-press")
        record("component-tip-long-press")
        if tipShown { dismissMenus(); if tip.exists { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).tap() } }

        try setToggle("preferences-attribute-halo", on: false)
        gate.tap()
        record("halo-off-selected-and")
        try setToggle("preferences-attribute-halo", on: true)

        try choose("preferences-pin-appearance", option: "preferences_pin_dot_bigger")
        record("pin-dot-bigger")
        try choose("preferences-pin-appearance", option: "preferences_pin_dot_small")
        record("pin-dot-small")

        try openTool("analysis_title")
        try tapLabel("analysis_read_circuit")
        try tapLabel("analysis_karnaugh")
        let lined = identified("kmap-style-lined")
        try scrollUntilHittable(lined)
        record("kmap-numbered")
        lined.tap()
        XCTAssertTrue(identified("kmap-lined").waitForExistence(timeout: 5), "The lined Karnaugh map must be drawn")
        record("kmap-lined")
        identified("editor-tool-close").tap()

        try openTool("appearance_title")
        let grid = identified("appearance-show-grid")
        XCTAssertTrue(grid.waitForExistence(timeout: 10))
        record("appearance-grid-on")
        grid.tap()
        record("appearance-grid-off")
        grid.tap()
        identified("editor-tool-close").tap()

        try choose("preferences-editor-theme", optionLabel: "Eclipse")
        try openTool("hdl_tools_import_title")
        let sourceLabels = try values("hdl_tools_import_source")
        let field = NSPredicate(format: "elementType == %d OR elementType == %d", XCUIElement.ElementType.textField.rawValue,
                                XCUIElement.ElementType.textView.rawValue)
        let source = app.descendants(matching: .any).matching(NSCompoundPredicate(andPredicateWithSubpredicates: [
            field, NSCompoundPredicate(orPredicateWithSubpredicates: sourceLabels.map { NSPredicate(format: "label BEGINSWITH %@", $0) }),
        ])).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 10), "The HDL source editor must be shown")
        try scrollUntilHittable(source)
        source.tap()
        source.typeText("module inv(input a, output y); // inverter\nassign y = ~a;\nendmodule")
        record("hdl-editor-eclipse-theme")
    }

    // MARK: helpers

    private func record(_ name: String) {
        stage += 1
        let label = String(format: "%02d-%@", stage, name)
        screenshot(label)
        attach(app.debugDescription, name: label + "-tree")
    }

    private func tapLabel(_ key: String) throws {
        let control = matching(try values(key)).allElementsBoundByIndex.first { $0.exists && $0.isHittable }
        guard let control else {
            let target = matching(try values(key)).firstMatch
            try scrollUntilHittable(target)
            target.tap()
            return
        }
        control.tap()
    }

    private func openPreferences() throws {
        let item = try XCTUnwrap(try revealInMenus(values("menu_preferences")), "Preferences… must be reachable from the menus")
        item.tap()
        XCTAssertTrue(identified("preferences-theme").waitForExistence(timeout: 10), "The preferences sheet must open")
    }

    private func closeSheet() {
        let scrim = identified("editor-sheet-scrim")
        // On wide windows the scrim fills the screen and the sheet covers its lower part;
        // on compact ones it is only the strip above the sheet.
        if scrim.exists {
            let dy: CGFloat = scrim.frame.height < app.frame.height * 0.5 ? 0.75 : 0.2
            scrim.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: dy)).tap()
        }
        harnessWait("closed sheet") { !self.identified("editor-sheet-scrim").exists }
    }

    private func setToggle(_ id: String, on: Bool) throws {
        try openPreferences()
        let toggle = identified(id)
        try scrollUntilHittable(toggle)
        func isOn() -> Bool { "\(toggle.value ?? "")" == "1" || "\(toggle.value ?? "")".lowercased() == "on" }
        if isOn() != on {
            for _ in 0..<3 where isOn() != on {
                toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
                _ = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in isOn() == on }, object: app)], timeout: 3)
            }
            XCTAssertEqual(isOn(), on, "\(id) must switch")
        }
        record("\(id)-\(on ? "on" : "off")")
        closeSheet()
    }

    private func choose(_ id: String, option key: String) throws {
        try choose(id, optionLabel: try values(key)[0], labels: try values(key))
    }

    private func choose(_ id: String, optionLabel: String, labels: [String]? = nil) throws {
        try openPreferences()
        let row = identified(id)
        try scrollUntilHittable(row)
        // The menu lists the current value too; the option outside the row is the menu item.
        let rowFrame = row.frame
        func option() -> XCUIElement? {
            // Menu items are buttons labelled with the option alone; the row's button merges title and value.
            app.buttons.matching(NSPredicate(format: "label IN %@", labels ?? [optionLabel])).allElementsBoundByIndex.last { $0.exists }
        }
        for attempt in 0..<3 where option() == nil {
            Thread.sleep(forTimeInterval: 0.8)
            if attempt > 0 && !row.exists {
                // The menu is open (its popup hides the sheet) but lacks the option: record and stop.
                break
            }
            let target = app.coordinate(withNormalizedOffset: .zero)
                .withOffset(CGVector(dx: rowFrame.minX + rowFrame.width * 0.8 - app.frame.minX, dy: rowFrame.midY - app.frame.minY))
            if attempt == 0 { target.tap() } else { target.press(forDuration: 0.15) }
            _ = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in option() != nil }, object: app)], timeout: 5)
        }
        if option() == nil { record("\(id)-missing-\(optionLabel)") }
        let item = try XCTUnwrap(option(), "Choice \(optionLabel) must be listed")
        record("\(id)-menu")
        item.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        record("\(id)-\(optionLabel)")
        closeSheet()
    }

    private func openTool(_ key: String) throws {
        // The compact menu occasionally closes while its submenus are searched; search it again once.
        var item = try revealInMenus(values(key))
        if item == nil { dismissMenus(); item = try revealInMenus(values(key)) }
        try XCTUnwrap(item, "\(key) must be reachable from the menus").tap()
        XCTAssertTrue(identified("editor-tool-close").waitForExistence(timeout: 10), "The \(key) tool must open")
    }

    private func scrollUntilHittable(_ element: XCUIElement, attempts: Int = 14) throws {
        for attempt in 0..<attempts {
            if element.exists && element.isHittable && app.frame.insetBy(dx: 0, dy: 80).contains(element.frame.center) {
                // A tap during the fling only stops the scroll; wait until the control stops moving.
                var previous = element.frame
                for _ in 0..<10 {
                    Thread.sleep(forTimeInterval: 0.3)
                    if element.frame == previous { break }
                    previous = element.frame
                }
                if app.frame.insetBy(dx: 0, dy: 80).contains(element.frame.center) { return }
                continue
            }
            let below = !element.exists || element.frame.midY > app.frame.midY
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: below ? 0.8 : 0.45))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: below ? 0.45 : 0.8))
            start.press(forDuration: 0.05, thenDragTo: end)
            if attempt == attempts - 1 { record("scroll-exhausted") }
        }
        XCTAssertTrue(element.exists && element.isHittable, "The control must become reachable by scrolling")
    }
}

private extension CGRect {
    var center: CGPoint { CGPoint(x: midX, y: midY) }
}
