import Foundation
import XCTest
import UIKit

/// Uses shipping controls and a real loopback companion; no editor injection hooks.
final class HdlEndToEndTests: NativeUIHarness {
    private var run: [String: Any] = [:]

    override func setUpWithError() throws {
        try super.setUpWithError()
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        run = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        orient(.portrait)
        // The project list opens from File > Projects… in the editor header.
        try tapKey("a11y_open_projects", toolbar: "editor-project-toolbar")
        try tapKey("workspace_new_project")
        let title = "HDL-UI-" + (try string("run_id")) + "-" + name
        try replace(canonicalField("workspace_name"), with: title)
        try tapKey("action_save")
        XCTAssertTrue(matching([title]).firstMatch.waitForExistence(timeout: 30),
                      "Each scenario must use its own new workspace, preserving the previous project")
    }

    func testCompactKeyboardActionPreservesExistingHdlSource() throws {
        try openTool("hdl_tools_sim_title")
        orient(.landscapeLeft)
        XCTAssertLessThan(app.frame.height, 600, "Run this compact-sheet proof on an iPhone")
        let field = try taggedField("hdl-sim-source")
        let source = try XCTUnwrap(field.value as? String)
        XCTAssertFalse(source.isEmpty, "Observe an existing source without replacing it")
        field.tap()
        _ = try visibleToolKeyboardAction()
        XCTAssertEqual(field.value as? String, source)
        try hideToolKeyboard(field: field, expected: source, confidential: false)
        let insert = try reveal(values("hdl_tools_sim_insert"), verticalRegion: "hdl-sim-scroll")
        XCTAssertTrue(identified("hdl-sim-apply").exists)
        checkFrame(insert, name: "hdl-sim-apply")
        screenshot("hdl-existing-source-insert-reachable")
        try closeTool()
    }

    func testCompactImportFailureAndVerilogBlifApply() throws {
        orient(.portrait)
        try pairCompanion()
        let original = try projectXML(attachmentName: "hdl-import-original")
        let suffix = try string("run_id")
        let invalidTop = "ui_invalid_" + suffix
        try openTool("hdl_tools_import_title")
        orient(.landscapeLeft)
        XCTAssertLessThan(app.frame.height, 600, "Run this compact-sheet proof on an iPhone")
        try addImportFile(name: invalidTop + ".v", source: "module \(invalidTop)(;\n", top: invalidTop)
        try prepareAndConfirmImport()
        try waitMessage("hdl_tools_import_provider_error", prefix: true)
        screenshot("hdl-import-real-yosys-error")
        attach(app.debugDescription, name: "hdl-import-error-tree")
        try closeTool()
        XCTAssertEqual(try projectXML(attachmentName: "hdl-import-after-error"), original,
                       "A compiler failure must leave the complete project XML unchanged")

        for blif in [false, true] {
            let top = (blif ? "ui_blif_" : "ui_gate_") + suffix
            let source = blif ? ".model \(top)\n.inputs a b\n.outputs y\n.names a b y\n11 1\n.end\n" :
                "module \(top)(input [1:0] a,b, output [1:0] y);\n  assign y = a ^ b;\nendmodule\n"
            try openTool("hdl_tools_import_title")
            if blif {
                // The control cycles through the formats and repaints after each tap, so read it before tapping again.
                let blifFormat = matching(try rendered("hdl_tools_import_format", ["format": "BLIF"])).firstMatch
                for _ in 0..<4 where !blifFormat.waitForExistence(timeout: 2) {
                    try reveal(try prefixValues("hdl_tools_import_format"), verticalRegion: "hdl-import-scroll", prefix: true).tap()
                }
                XCTAssertTrue(blifFormat.waitForExistence(timeout: 5), "The format control must reach BLIF")
            }
            try addImportFile(name: top + (blif ? ".blif" : ".v"), source: source, top: top)
            try prepareAndConfirmImport()
            try waitMessage("hdl_tools_import_synthesis_complete")
            screenshot(blif ? "hdl-blif-complete" : "hdl-verilog-complete")
            if !blif { try checkImportApplyReachableWithKeyboard() }
            try tapAction("hdl_tools_import_apply", tag: "hdl-import-apply", region: "hdl-import-scroll")
            try closeTool()
            let xml = try projectXML(attachmentName: "hdl-import-" + top)
            XCTAssertTrue(xml.contains("name=\"hdl_\(top)\""), "Apply must add the synthesized native circuit")
            let archive = SourceArchive()
            XCTAssertTrue(archive.read(xml))
            XCTAssertEqual(archive.files[top + (blif ? ".blif" : ".v")], source,
                           "The UI import must preserve the explicitly submitted source")
            XCTAssertTrue(xml.contains("name=\"width\" val=\"\(blif ? 1 : 2)\""))
            screenshot(blif ? "hdl-blif-applied" : "hdl-verilog-applied")
        }
    }

    func testCompactCosimulationSamplesResetStopAndCompilerFailure() throws {
        orient(.portrait)
        try pairCompanion()
        try openTool("hdl_tools_sim_title")
        orient(.landscapeLeft)
        XCTAssertLessThan(app.frame.height, 600, "Run this compact-sheet proof on an iPhone")
        let top = "ui_time_" + (try string("run_id"))
        let source = """
        library ieee;
        use ieee.std_logic_1164.all;
        use ieee.numeric_std.all;
        entity \(top) is port(y: out std_logic_vector(1 downto 0)); end entity;
        architecture rtl of \(top) is begin
          process
            variable tick: natural := 0;
          begin
            y <= std_logic_vector(to_unsigned(tick mod 4, 2));
            wait for 1 ns;
            tick := tick + 1;
          end process;
        end architecture;
        """
        try replace(taggedField("hdl-sim-source"), with: source)
        try tapAction("hdl_tools_sim_insert", tag: "hdl-sim-apply", region: "hdl-sim-scroll")
        try waitMessage("hdl_tools_sim_source_saved")
        try prepareAndConfirmSimulation()
        try waitMessage("hdl_tools_sim_started", timeout: 90)
        screenshot("hdl-cosim-started")
        _ = try reveal(try values("hdl_tools_sim_advance_label"), verticalRegion: "hdl-sim-scroll")
        try replace(taggedField("hdl-sim-advance"), with: "1")
        try sample()
        try sample()
        try tapAction("hdl_tools_sim_reset", tag: "hdl-sim-reset", region: "hdl-sim-scroll")
        try waitMessage("hdl_tools_sim_reset_complete", timeout: 90)
        screenshot("hdl-cosim-reset")
        try sample()
        try tapAction("hdl_tools_sim_cancel", tag: "hdl-sim-stop", region: "hdl-sim-scroll")
        try waitMessage("hdl_tools_sim_stopped")
        screenshot("hdl-cosim-stopped")

        let invalid = source.replacingOccurrences(of: "y <= std_logic_vector(to_unsigned(tick mod 4, 2));", with: "y <= signal_that_does_not_exist;")
        XCTAssertNotEqual(invalid, source)
        try replace(taggedField("hdl-sim-source"), with: invalid)
        try tapAction("hdl_tools_sim_apply_source", tag: "hdl-sim-apply", region: "hdl-sim-scroll")
        try waitMessage("hdl_tools_sim_source_saved")
        try prepareAndConfirmSimulation()
        try waitMessage("hdl_ip_job_failure_reason", prefix: true, timeout: 90)
        XCTAssertFalse(["00", "01", "10", "11"].contains { outputText($0).exists },
                       "A real compiler failure must not publish a known output")
        screenshot("hdl-cosim-real-compiler-error")
        attach(app.debugDescription, name: "hdl-cosim-error-tree")
        try closeTool()
    }

    private func pairCompanion() throws {
        try openTool("additional_companion_title")
        try replace(canonicalField("companion_ui_address"), with: try string("companion_endpoint"))
        try replace(canonicalField("companion_ui_token", secure: true), with: try string("companion_token"), confidential: true)
        try tapKey("companion_ui_verify")
        try waitMessage("additional_companion_verified", timeout: 30)
        screenshot("hdl-companion-verified")
        try closeTool()
    }

    private func projectXML(attachmentName: String) throws -> String {
        // The File menu is too short in landscape to reach this item without scrolling.
        let orientation = XCUIDevice.shared.orientation
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = orientation; sleep(2) }
        sleep(2)
        try tapKey("a11y_import_export_project", toolbar: "editor-project-toolbar")
        try tapKey("xml_edit")
        func fields() -> [XCUIElement] {
            (app.textViews.allElementsBoundByIndex + app.textFields.allElementsBoundByIndex).filter {
                guard $0.exists, let value = $0.value as? String else { return false }
                return value.contains("<project") && value.contains("</project>")
            }
        }
        let presented = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in fields().count == 1 }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [presented], timeout: 10), .completed,
                       "Read one complete XML field after native presentation")
        let field = try XCTUnwrap(fields().first)
        let xml = try XCTUnwrap(field.value as? String)
        let attachment = XCTAttachment(data: Data(xml.utf8), uniformTypeIdentifier: "public.xml")
        attachment.name = attachmentName; attachment.lifetime = .keepAlways; add(attachment)
        try tapKey("action_close")
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !field.exists }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 10), .completed)
        return xml
    }

    private func openTool(_ key: String) throws {
        try tapKey("a11y_project_tools", toolbar: "editor-project-toolbar")
        try reveal(values(key), verticalRegion: "editor-tool-menu").tap()
    }

    private func closeTool() throws { try tapKey("action_close") }

    private func addImportFile(name: String, source: String, top: String) throws {
        try replace(taggedField("hdl-import-file-name"), with: name)
        try replace(taggedField("hdl-import-source"), with: source)
        try tapAction("hdl_tools_import_add_file", tag: "hdl-import-add-file", region: "hdl-import-scroll")
        _ = try reveal(try values("hdl_tools_import_top"), verticalRegion: "hdl-import-scroll")
        try replace(canonicalField("hdl_tools_import_top"), with: top)
    }

    private func prepareAndConfirmImport() throws {
        try tapAction("hdl_tools_import_prepare", tag: "hdl-import-prepare", region: "hdl-import-scroll")
        try waitMessage("hdl_tools_import_review_plan")
        screenshot("hdl-import-explicit-plan")
        try tapAction("hdl_tools_import_confirm", tag: "hdl-import-confirm", region: "hdl-import-scroll")
    }

    private func prepareAndConfirmSimulation() throws {
        try tapAction("hdl_tools_sim_prepare", tag: "hdl-sim-prepare", region: "hdl-sim-scroll")
        try waitMessage("hdl_tools_sim_review_start")
        screenshot("hdl-cosim-explicit-plan")
        try tapAction("hdl_tools_sim_confirm_start", tag: "hdl-sim-confirm", region: "hdl-sim-scroll")
    }

    private func sample() throws {
        let before = try statusTimes()
        let target = max(before.engine, before.session) + 1
        let expected = String(target % 4, radix: 2).leftPadded(to: 2)
        try tapAction("hdl_tools_sim_sample", tag: "hdl-sim-step", region: "hdl-sim-scroll")
        let received = try rendered("hdl_tools_sim_sample_received", ["time": String(target)])
        XCTAssertTrue(matching(received).firstMatch.waitForExistence(timeout: 90))
        _ = try reveal(["y = " + expected], verticalRegion: "hdl-sim-scroll")
        XCTAssertTrue(outputText(expected).exists, "The displayed output must equal targetTime mod 4 from the real VHDL process")
        attach("engineBefore=\(before.engine), sessionBefore=\(before.session), target=\(target), expected=\(expected)", name: "hdl-sample-time-oracle")
        screenshot("hdl-cosim-\(target)ns-\(expected)")
    }

    private func statusTimes() throws -> (engine: Int, session: Int) {
        let prefixes = try prefixValues("hdl_tools_sim_status")
        let status = matching(prefixes, prefix: true).firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        let label = status.label
        for template in try values("hdl_tools_sim_status") {
            let pattern = NSRegularExpression.escapedPattern(for: template)
                .replacingOccurrences(of: NSRegularExpression.escapedPattern(for: "{status}"), with: ".*?")
                .replacingOccurrences(of: NSRegularExpression.escapedPattern(for: "{time}"), with: "([0-9]+)")
                .replacingOccurrences(of: NSRegularExpression.escapedPattern(for: "{sessionTime}"), with: "([0-9]+)")
            let regex = try NSRegularExpression(pattern: pattern)
            if let match = regex.firstMatch(in: label, range: NSRange(label.startIndex..., in: label)),
               let first = Range(match.range(at: 1), in: label), let second = Range(match.range(at: 2), in: label),
               let engine = Int(label[first]), let session = Int(label[second]) { return (engine, session) }
        }
        XCTFail("Cannot read the actual engine/session timestamps: \(label)")
        throw HarnessFailure.missingControl
    }

    private func outputText(_ value: String) -> XCUIElement { matching(["y = " + value]).firstMatch }

    private func tapAction(_ key: String, tag: String, region: String) throws {
        let control = try revealWhole(key, tag: tag, region: region)
        XCTAssertTrue(control.exists, "The expected production action must be identified")
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: control)
        XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 10), .completed)
        checkFrame(control, name: tag)
        control.tap()
    }

    /// `reveal` accepts a sliver of a control; a person scrolls on until it can be touched.
    /// The caller keeps the strict frame check, so a control that cannot be reached still fails.
    private func revealWhole(_ key: String, tag: String, region: String) throws -> XCUIElement {
        _ = try reveal(values(key), verticalRegion: region)
        // `reveal` can return the button's text node, 20 points tall; the touch target is the tagged button.
        let control = identified(tag)
        let container = identified(region)
        for _ in 0..<8 {
            let frame = control.frame.intersection(viewport(of: container))
            if !frame.isNull && frame.height >= 44 { break }
            nudge(container, contentUp: control.frame.midY > viewport(of: container).midY)
        }
        return control
    }

    /// The part of a scroll container that a finger can reach while the keyboard is up.
    private func viewport(of container: XCUIElement) -> CGRect {
        var visible = container.frame.intersection(app.frame)
        for keyboard in softwareInputFrames().values where keyboard.minY > visible.minY && keyboard.minX <= visible.midX && keyboard.maxX >= visible.midX {
            visible.size.height = min(visible.height, keyboard.minY - visible.minY)
        }
        return visible
    }

    private func centreIsVisible(_ element: XCUIElement, in container: XCUIElement) -> Bool {
        let frame = element.frame
        return viewport(of: container).contains(CGPoint(x: frame.midX, y: frame.midY))
    }

    /// A short drag, so a control near the viewport edge cannot be carried past the opposite edge.
    private func nudge(_ container: XCUIElement, contentUp: Bool) {
        let visible = viewport(of: container)
        let distance = min(64, visible.height / 2 - 4)
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let lower = origin.withOffset(CGVector(dx: visible.midX, dy: visible.midY + distance / 2))
        let upper = origin.withOffset(CGVector(dx: visible.midX, dy: visible.midY - distance / 2))
        (contentUp ? lower : upper).press(forDuration: 0.05, thenDragTo: contentUp ? upper : lower,
                                          withVelocity: .slow, thenHoldForDuration: 0.1)
    }

    /// With the keyboard up in landscape, Apply must still be reachable by scrolling the sheet.
    private func checkImportApplyReachableWithKeyboard() throws {
        // Defines is on screen once the job finished; focusing it edits nothing.
        let field = try canonicalField("hdl_tools_import_defines")
        field.tap()
        _ = try visibleToolKeyboardAction()
        let control = try revealWhole("hdl_tools_import_apply", tag: "hdl-import-apply", region: "hdl-import-scroll")
        checkFrame(control, name: "hdl-import-apply-with-keyboard", avoidingKeyboard: true)
        XCTAssertTrue(control.isHittable, "Apply must be reachable by touch while the keyboard is up")
        screenshot("hdl-import-apply-with-keyboard")
        // Apply is now in view and Defines is not: Compose hides that field's label, and nothing edited it.
        try hideToolKeyboard(field: nil, expected: "", confidential: true)
    }

    private func waitMessage(_ key: String, prefix: Bool = false, timeout: TimeInterval = 30) throws {
        let labels = try prefix ? prefixValues(key) : values(key)
        XCTAssertTrue(matching(labels, prefix: prefix).firstMatch.waitForExistence(timeout: timeout), "Missing result for \(key)")
    }

    private func prefixValues(_ key: String) throws -> [String] {
        try values(key).map { $0.components(separatedBy: "{")[0] }
    }

    private func rendered(_ key: String, _ substitutions: [String: String]) throws -> [String] {
        try values(key).map { raw in substitutions.reduce(raw) { $0.replacingOccurrences(of: "{\($1.key)}", with: $1.value) } }
    }

    private func taggedField(_ tag: String) throws -> XCUIElement {
        let region = identified(tag)
        XCTAssertTrue(region.waitForExistence(timeout: 10), "Missing production field \(tag)")
        let container = identified(tag.hasPrefix("hdl-import-") ? "hdl-import-scroll" : "hdl-sim-scroll")
        var trace: [String] = []
        for forward in [true, false] {
            for _ in 0..<12 {
                let fields = [region] + region.textViews.allElementsBoundByIndex + region.textFields.allElementsBoundByIndex
                if let field = fields.first(where: { ($0.elementType == .textView || $0.elementType == .textField) && $0.exists }) {
                    // A tall field stays hittable while its centre, where XCUITest touches, is off screen.
                    if centreIsVisible(field, in: container) && field.isHittable { return field }
                    trace.append("frame=\(field.frame) viewport=\(viewport(of: container))")
                    nudge(container, contentUp: field.frame.midY > viewport(of: container).midY)
                    continue
                }
                try scroll(container, horizontal: false, forward: forward)
            }
        }
        attach(trace.joined(separator: "\n"), name: "hdl-field-scroll-trace-\(tag)")
        XCTFail("Cannot reach the editable field \(tag) within its identified scroll container")
        throw HarnessFailure.missingControl
    }

    private func canonicalField(_ key: String, secure: Bool = false) throws -> XCUIElement {
        let names = Set(try values(key))
        func candidates() -> [XCUIElement] {
            let fields = app.textViews.allElementsBoundByIndex + app.textFields.allElementsBoundByIndex +
                (secure ? app.secureTextFields.allElementsBoundByIndex : [])
            return fields.filter { field in field.exists && names.contains { nativeLabel(field.label, matches: $0) } }
        }
        let presented = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in candidates().count == 1 }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [presented], timeout: 10), .completed,
                       "Wait for exactly one canonical field \(key) after the tool presentation")
        let found = try XCTUnwrap(candidates().first, "Missing canonical field \(key); observed labels: \(names.sorted())")
        // The tree reorders when a field takes focus, so an element bound by index can become another field.
        return app.descendants(matching: found.elementType).matching(NSPredicate(format: "label == %@", found.label)).firstMatch
    }

    private func replace(_ field: XCUIElement, with text: String, confidential: Bool = false) throws {
        XCTAssertTrue(field.exists && field.isEnabled)
        let current = field.value as? String ?? ""
        if !confidential && current == text { return }
        field.tap()
        if identified("editor-tool-sheet").exists { _ = try visibleToolKeyboardAction() }
        let old = current == field.placeholderValue ? "" : current
        if confidential {
            XCTAssertTrue(old.isEmpty, "The confidential pairing field must start empty; actual authentication verifies its submitted value")
            field.typeText(text)
        } else {
            try replacePresentedText(field, with: text)
            XCTAssertEqual(field.value as? String, text,
                           "The editable value must equal the requested text before submitting an operation")
        }
        if identified("editor-tool-sheet").exists {
            try hideToolKeyboard(field: field, expected: text, confidential: confidential)
        }
    }

    private func visibleToolKeyboardAction() throws -> XCUIElement {
        let visibleKeyboard = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !self.softwareInputFrames().isEmpty }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [visibleKeyboard], timeout: 10), .completed,
                       "Observe the actual software keyboard before testing its production dismissal action")
        let hide = identified("editor-tool-hide-keyboard")
        XCTAssertTrue(hide.waitForExistence(timeout: 5), "The tool must provide its normal Hide keyboard action")
        let names = try values("action_hide_keyboard")
        XCTAssertTrue(names.contains { nativeLabel(hide.label, matches: $0) })
        checkFrame(hide, name: "editor-tool-hide-keyboard", minimum: CGSize(width: 48, height: 48), avoidingKeyboard: true)
        XCTAssertTrue(hide.isHittable, "The production keyboard action must be reachable by touch")
        return hide
    }

    private func hideToolKeyboard(field: XCUIElement?, expected: String, confidential: Bool) throws {
        let hide = try visibleToolKeyboardAction()
        screenshot("hdl-tool-keyboard-before-hide")
        let frame = hide.frame
        attach("Tap the visible action at x=\(frame.midX), y=\(frame.maxY - 4), four points above its lower edge; measured frame=\(frame)",
               name: "hdl-keyboard-action-lower-edge-touch")
        hide.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 1))
            .withOffset(CGVector(dx: 0, dy: -4)).tap()
        let hidden = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.softwareInputFrames().isEmpty }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 10), .completed,
                       "Hide keyboard must remove the real input surface")
        XCTAssertTrue(identified("editor-tool-sheet").exists, "Keyboard dismissal must preserve the active tool")
        XCTAssertTrue(identified("editor-tool-close").waitForExistence(timeout: 5))
        if let field {
            XCTAssertTrue(field.exists, "Keyboard dismissal must preserve the editor field")
            if !confidential { XCTAssertEqual(field.value as? String, expected, "Keyboard dismissal must preserve the exact draft/source") }
        }
        screenshot("hdl-tool-keyboard-hidden-draft-preserved")
    }

    private func string(_ key: String) throws -> String { try XCTUnwrap(run[key] as? String) }
}

private final class SourceArchive: NSObject, XMLParserDelegate {
    var files: [String: String] = [:]
    private var name: String?
    private var value = ""
    func read(_ xml: String) -> Bool {
        let parser = XMLParser(data: Data(xml.utf8)); parser.delegate = self
        return parser.parse()
    }
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes: [String: String]) {
        if elementName == "a", attributes["name"] == "logisim-mobile-hdl-source" { name = attributes["file"]; value = "" }
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) { if name != nil { value += string } }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if elementName == "a", let name { files[name] = value; self.name = nil }
    }
}

private extension String {
    func leftPadded(to width: Int) -> String { String(repeating: "0", count: Swift.max(0, width - count)) + self }
}
