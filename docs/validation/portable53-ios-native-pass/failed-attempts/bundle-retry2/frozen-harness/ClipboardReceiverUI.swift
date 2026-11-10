import XCTest
import UIKit

extension NativeUIHarness {
    /// Read the result of an explicit system Paste gesture through UITextView accessibility.
    /// The caller supplies a current-project marker or separately establishes freshness at its Copy action.
    func copiedProjectXMLViaReceiver(expectedFragment: String, minimumUTF8Bytes: Int = 1,
                                     attachmentName: String = "copied-project-receiver.circ") throws -> String {
        XCTAssertFalse(expectedFragment.isEmpty)
        XCTAssertGreaterThanOrEqual(minimumUTF8Bytes, 1)
        let receiver = XCUIApplication(bundleIdentifier: "thalesmms.logisim.validation.clipboardreceiver")
        // Keep the existing Logisim process and dialog. Never launch or terminate it during this handoff.
        defer {
            if receiver.state != .notRunning { receiver.terminate() }
            app.activate()
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15), "Return to the existing Logisim process")
        }
        receiver.activate()
        XCTAssertTrue(receiver.wait(for: .runningForeground, timeout: 15), "Install the test receiver before this scenario")
        screenshot("clipboard-receiver-foreground")
        attach(receiver.debugDescription, name: "clipboard-receiver-foreground-tree")

        func unique(_ identifier: String) throws -> XCUIElement {
            let query = receiver.descendants(matching: .any).matching(identifier: identifier)
            let appeared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                query.allElementsBoundByIndex.contains { $0.exists && $0.isHittable }
            }, object: receiver)
            XCTAssertEqual(XCTWaiter.wait(for: [appeared], timeout: 15), .completed, "Missing receiver control \(identifier)")
            let matches = query.allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
            XCTAssertEqual(matches.count, 1, "Receiver controls must have unique visible identifiers")
            return try XCTUnwrap(matches.count == 1 ? matches[0] : nil)
        }

        let clear = try unique("clipboard-clear")
        screenshot("clipboard-receiver-before-clear")
        clear.tap()
        let text = try unique("clipboard-project-text")
        // Read text accessibility content only after the explicit native Paste gesture below.
        let paste = try unique("clipboard-paste")
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: paste)
        XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 15), .completed, "The native Paste control must accept the copied text")
        XCTAssertGreaterThanOrEqual(paste.frame.height, 48)
        screenshot("clipboard-receiver-before-native-paste")
        attach(receiver.debugDescription, name: "clipboard-receiver-before-native-paste-tree")
        paste.tap()

        var observed = ""
        let received = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            observed = text.value as? String ?? ""
            return observed.contains("<project") && observed.contains("</project>") &&
                observed.contains(expectedFragment) && observed.utf8.count >= minimumUTF8Bytes
        }, object: receiver)
        let result = XCTWaiter.wait(for: [received], timeout: 20)
        screenshot("clipboard-receiver-after-native-paste")
        attach(receiver.debugDescription, name: "clipboard-receiver-after-native-paste-tree")
        XCTAssertEqual(result, .completed, "The explicit native Paste must expose the complete requested project XML")
        guard result == .completed else { throw HarnessFailure.missingControl }
        let attachment = XCTAttachment(data: Data(observed.utf8), uniformTypeIdentifier: "org.logisim-evolution.circuit")
        attachment.name = attachmentName
        attachment.lifetime = .keepAlways
        add(attachment)
        // Clear the scratch UI after preserving its complete received value. The
        // next app transition does not need a large text snapshot or a caret.
        try unique("clipboard-clear").tap()
        return observed
    }
}
