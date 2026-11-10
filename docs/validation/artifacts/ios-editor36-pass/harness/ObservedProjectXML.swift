import XCTest
import UIKit

extension NativeUIHarness {
    /// Read the production editor's complete XML through its Edit or Copy action.
    func observedProjectXML(attachmentName: String) throws -> String {
        try tapKey("a11y_import_export_project", toolbar: "editor-project-toolbar")
        let editLabels = try values("xml_edit")
        let copyLabels = try values("action_copy")
        let body = identified("editor-xml-body")
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            (body.exists && body.frame.width > 0 && body.frame.height > 0) ||
                self.visibleMatch(editLabels) != nil || self.visibleMatch(copyLabels) != nil
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed,
                       "The XML dialog must actually present its Edit or Copy action")
        let action = try reveal(editLabels + copyLabels, verticalRegion: body.exists ? "editor-xml-body" : nil)
        let xml: String
        if editLabels.contains(where: { nativeLabel(action.label, matches: $0) }) {
            action.tap()
            func exports() -> [XCUIElement] {
                let fields = app.textViews.allElementsBoundByIndex + app.textFields.allElementsBoundByIndex
                return fields.filter {
                    guard $0.exists, let value = $0.value as? String else { return false }
                    return value.contains("<project") && value.contains("</project>")
                }
            }
            let editable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in exports().count == 1 }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [editable], timeout: 10), .completed,
                           "Wait for one complete editable XML value, not a truncated preview")
            let field = try XCTUnwrap(exports().first)
            xml = try XCTUnwrap(field.value as? String)
        } else {
            // Large projects intentionally expose Copy instead of a large editable
            // text control. A fresh pasteboard change proves this came from that tap.
            let pasteboard = UIPasteboard.general
            let previousChange = pasteboard.changeCount
            XCTAssertTrue(copyLabels.contains(where: { nativeLabel(action.label, matches: $0) }))
            action.tap()
            let copied = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                pasteboard.changeCount != previousChange
            }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [copied], timeout: 5), .completed,
                           "The production Copy action must update the system pasteboard")
            xml = try copiedProjectXMLViaReceiver(expectedFragment: "<project", attachmentName: attachmentName + "-receiver")
            XCTAssertTrue(xml.contains("<project") && xml.contains("</project>"),
                          "Read the complete copied XML rather than the shortened preview")
        }
        let attachment = XCTAttachment(data: Data(xml.utf8), uniformTypeIdentifier: "public.xml")
        attachment.name = attachmentName
        attachment.lifetime = .keepAlways
        add(attachment)
        try tapKey("action_close")
        let titles = try values("project_xml_title")
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            !self.matching(titles).firstMatch.exists
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 10), .completed, "Close the XML modal before proceeding")
        return xml
    }
}
