import Foundation
import XCTest
import UIKit

/// Issue #307: the FPGA panel feeds dedicated Clocks from the board tick, through shipping controls only.
final class FpgaTickEndToEndTests: NativeUIHarness {
    private var run: [String: Any] = [:]

    override func setUpWithError() throws {
        try super.setUpWithError()
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        run = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        orient(.portrait)
    }

    func testTickFrequencyIsShownSavedAndRestored() throws {
        try importProject(try string("tick_xml"))
        try openFpgaWithBoard("BASYS3")
        XCTAssertTrue(matching(["p_clk_out"], prefix: true).firstMatch.exists, "The output stays mappable")
        XCTAssertFalse(matching(["p_source_clock"], prefix: true).firstMatch.exists, "The tick replaces the Clock port")
        let field = try tickField()
        XCTAssertEqual(field.value as? String, "10000000", "The field starts from downloadFrequency")
        try waitRendered("fpga_tick_effective", ["frequency": "10000000", "cycles": "10"])
        screenshot("fpga-tick-saved-frequency")

        // The stored map loads with the panel; scroll to the mapping actions below clk_out without tapping them.
        _ = try sheetReveal(values("fpga_map_revert"))
        let bound = app.descendants(matching: .any).matching(NSPredicate(format: "value == %@", "U16")).firstMatch
        XCTAssertTrue(bound.waitForExistence(timeout: 10), "The saved map binds clk_out to U16 when the panel opens")
        try replaceTick(field, with: "12500000")
        try waitRendered("fpga_tick_effective", ["frequency": "12500000", "cycles": "8"])
        try tapPanel("fpga_map_save")
        screenshot("fpga-tick-save-tapped")
        // Saving commits the project; the panel reloads from the stored downloadFrequency.
        let reloaded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (try? self.tickField().value as? String) == "12500000"
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [reloaded], timeout: 20), .completed)
        screenshot("fpga-tick-after-save")
        try tapKey("action_close")

        let xml = try projectXML(attachmentName: "fpga-tick-saved-project")
        XCTAssertTrue(xml.contains("name=\"downloadFrequency\" val=\"1.25E7\""), "Save stores the tick like Evolution")
        XCTAssertFalse(xml.contains("key=\"/source_clock\""), "The saved map omits the Clock fed by the tick")
        XCTAssertTrue(xml.contains("key=\"/clk_out\""))

        app.terminate()
        app.launch()
        try waitForEditorStartup()
        try openFpgaWithBoard("BASYS3")
        XCTAssertEqual(try tickField().value as? String, "12500000", "The frequency survives a relaunch")
        try waitRendered("fpga_tick_effective", ["frequency": "12500000", "cycles": "8"])
        screenshot("fpga-tick-after-relaunch")
        _ = try sheetReveal(values("fpga_map_revert"))
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "value == %@", "U16"))
            .firstMatch.waitForExistence(timeout: 10), "The saved map is loaded again after a relaunch")
        try tapPanel("fpga_export_project")
        // The system save picker belongs to the app process on iPadOS; confirm its primary action.
        let saveButtons = ["Save", "Move", "Done"]
        var confirmed = false
        for _ in 0..<20 where !confirmed {
            for title in saveButtons where app.buttons[title].exists && app.buttons[title].isHittable {
                screenshot("fpga-export-picker")
                app.buttons[title].tap(); confirmed = true; break
            }
            if !confirmed { Thread.sleep(forTimeInterval: 1) }
        }
        XCTAssertTrue(confirmed, "The export picker must present a save action")
        Thread.sleep(forTimeInterval: 3)
        screenshot("fpga-export-saved")
    }

    func testMapsThatBindTheClockKeepThePhysicalInput() throws {
        try importProject(try string("legacy_xml"))
        try openFpgaWithBoard("BASYS3")
        XCTAssertTrue(matching(try values("fpga_tick_physical_map")).firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(matching(["p_source_clock"], prefix: true).firstMatch.exists)
        XCTAssertFalse(matching(try values("fpga_tick_frequency_input")).firstMatch.exists)
        screenshot("fpga-tick-legacy-physical-clock")
        try tapKey("action_close")
    }

    private func importProject(_ xml: String) throws {
        try tapKey("a11y_import_export_project", toolbar: "editor-project-toolbar")
        try tapKey("xml_edit")
        func fields() -> [XCUIElement] {
            (app.textViews.allElementsBoundByIndex + app.textFields.allElementsBoundByIndex).filter {
                guard $0.exists, let value = $0.value as? String else { return false }
                return value.contains("<project")
            }
        }
        let presented = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in fields().count == 1 }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [presented], timeout: 10), .completed)
        let field = try XCTUnwrap(fields().first)
        field.tap()
        try replacePresentedText(field, with: xml)
        try tapKey("action_import_xml")
        // A behavior-changing import opens its report; version defaults alone leave it behind the status
        // Details action (C145). Close a report before using the editor.
        let report = identified("editor-import-report")
        let details = identified("import-report-status-details")
        let loaded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in report.exists || details.exists }, object: app)
        _ = XCTWaiter.wait(for: [loaded], timeout: 10)
        try closeImportReportIfPresented()
    }

    private func openFpgaWithBoard(_ board: String) throws {
        // The FPGA tool sits in Evolution's FPGA menu, which keeps the tools menu tag.
        try tapKey("a11y_fpga_tools", toolbar: "editor-project-toolbar")
        try reveal(values("fpga_title"), verticalRegion: "editor-tool-menu").tap()
        let prefixes = try values("fpga_board_catalog").map { $0 + ":" }
        let choice = matching(prefixes, prefix: true).firstMatch
        XCTAssertTrue(choice.waitForExistence(timeout: 20), "The board catalog must load")
        if !choice.label.hasSuffix(board) {
            choice.tap()
            let option = app.buttons[board].exists ? app.buttons[board] : matching([board]).firstMatch
            XCTAssertTrue(option.waitForExistence(timeout: 10))
            option.tap()
        }
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.matching(prefixes.map { $0 + " " + board }).firstMatch.exists
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 20), .completed, "Select \(board)")
        _ = try sheetReveal(values("fpga_tick_frequency_input") + values("fpga_tick_physical_map"))
    }

    private func tickField() throws -> XCUIElement {
        let names = Set(try values("fpga_tick_frequency_input"))
        let fields = (app.textFields.allElementsBoundByIndex + app.textViews.allElementsBoundByIndex).filter { $0.exists }
        // The label is exposed only on some presentations; the panel has one numeric field, the tick frequency.
        let field = fields.first { candidate in names.contains { nativeLabel(candidate.label, matches: $0) } } ??
            fields.first { (($0.value as? String) ?? "").range(of: "^[0-9.]+$", options: .regularExpression) != nil }
        return try XCTUnwrap(field, "Missing tick field")
    }

    private func replaceTick(_ field: XCUIElement, with value: String) throws {
        field.tap()
        try replacePresentedText(field, with: value)
        if identified("editor-tool-hide-keyboard").exists { identified("editor-tool-hide-keyboard").tap() }
    }

    private func tapPanel(_ key: String) throws {
        let control = try sheetReveal(values(key))
        XCTAssertTrue(control.isEnabled)
        control.tap()
    }

    /// The FPGA list has no tagged scroll region; swipe inside the presented sheet until the control is hittable.
    private func sheetReveal(_ labels: [String]) throws -> XCUIElement {
        let sheet = identified("editor-tool-sheet")
        XCTAssertTrue(sheet.waitForExistence(timeout: 10))
        for direction in [true, false] {
            for _ in 0..<14 {
                if let element = visibleMatch(labels), element.isHittable {
                    // Let scroll momentum stop so the tap activates the control instead of halting the list.
                    Thread.sleep(forTimeInterval: 1.5)
                    if let settled = visibleMatch(labels), settled.isHittable { return settled }
                }
                if direction { sheet.swipeUp(velocity: .slow) } else { sheet.swipeDown(velocity: .slow) }
            }
        }
        XCTFail("No reachable control for \(labels)")
        throw HarnessFailure.missingControl
    }

    private func waitRendered(_ key: String, _ substitutions: [String: String]) throws {
        let labels = try values(key).map { raw in
            substitutions.reduce(raw) { $0.replacingOccurrences(of: "{\($1.key)}", with: $1.value) }
        }
        XCTAssertTrue(matching(labels).firstMatch.waitForExistence(timeout: 15), "Missing \(labels)")
    }

    private func projectXML(attachmentName: String) throws -> String {
        try tapKey("a11y_import_export_project", toolbar: "editor-project-toolbar")
        try tapKey("xml_edit")
        func fields() -> [XCUIElement] {
            (app.textViews.allElementsBoundByIndex + app.textFields.allElementsBoundByIndex).filter {
                guard $0.exists, let value = $0.value as? String else { return false }
                return value.contains("<project") && value.contains("</project>")
            }
        }
        let presented = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in fields().count == 1 }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [presented], timeout: 10), .completed)
        let xml = try XCTUnwrap(fields().first?.value as? String)
        let attachment = XCTAttachment(data: Data(xml.utf8), uniformTypeIdentifier: "public.xml")
        attachment.name = attachmentName; attachment.lifetime = .keepAlways; add(attachment)
        try tapKey("action_close")
        return xml
    }

    private func string(_ key: String) throws -> String { try XCTUnwrap(run[key] as? String) }
}
