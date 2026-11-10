import XCTest
import UIKit

// Shared criteria from the native accessibility scenarios, executed separately on iPad.
final class XmlDialogEndToEndTests: NativeUIHarness {
    func testXmlActionsAreAccessibleAcrossRotation() throws {
        let previousContinuation = continueAfterFailure
        defer { continueAfterFailure = previousContinuation }
        for (orientation, name) in [(UIDeviceOrientation.portrait, "portrait"), (.landscapeLeft, "landscape")] {
            orient(orientation)
            try tapKey("a11y_import_export_project", toolbar: "editor-project-toolbar")
            screenshot("\(name)-xml-actions-before")
            attach(app.debugDescription, name: "\(name)-xml-actions-before-tree")
            continueAfterFailure = true
            for key in ["action_copy", "action_paste", "action_close", "action_import_xml"] {
                do {
                    let labels = try values(key)
                    let bodyAction = key == "action_copy" || key == "action_paste"
                    let control: XCUIElement
                    if let visible = visibleMatch(labels) {
                        control = visible
                    } else if bodyAction && identified("editor-xml-body").exists {
                        control = try reveal(labels, verticalRegion: "editor-xml-body")
                    } else {
                        control = try reveal(labels)
                    }
                    XCTAssertEqual(control.elementType, .button, "XML action \(key) must expose its button role")
                    XCTAssertTrue(control.isHittable, "XML action \(key) must be operable")
                    checkFrame(control, name: "\(name)-xml-\(key)", minimum: CGSize(width: 48, height: 48), avoidingKeyboard: true)
                    if bodyAction {
                        let body = identified("editor-xml-body")
                        if body.exists {
                            XCTAssertTrue(body.frame.contains(control.frame), "The whole action must fit its observed scroll viewport")
                        }
                    }
                } catch HarnessFailure.missingControl {
                    // reveal recorded the missing native action; inspect the others too.
                }
            }
            screenshot("\(name)-xml-actions-after")
            attach(app.debugDescription, name: "\(name)-xml-actions-after-tree")
            continueAfterFailure = previousContinuation
            if let close = visibleMatch(try values("action_close")) {
                close.tap()
            } else {
                throw HarnessFailure.missingControl
            }
        }
    }

    func testXmlDialogKeepsInsideActionsAndDismissesOutside() throws {
        for (orientation, name) in [(UIDeviceOrientation.portrait, "portrait"), (.landscapeLeft, "landscape")] {
            orient(orientation)
            try tapKey("a11y_import_export_project", toolbar: "editor-project-toolbar")
            let body = identified("editor-xml-body")
            XCTAssertTrue(body.waitForExistence(timeout: 5))
            screenshot("\(name)-xml-before-inside-action")
            attach(app.debugDescription, name: "\(name)-xml-before-inside-action-tree")
            let copiedBefore = UIPasteboard.general.changeCount
            try reveal(values("action_copy"), verticalRegion: "editor-xml-body").tap()
            let copied = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                UIPasteboard.general.changeCount > copiedBefore
            }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [copied], timeout: 5), .completed, "The inside Copy action must execute")
            XCTAssertTrue(body.exists, "An inside action must keep the dialog open")

            // Material3 1.9.0 puts 24 dp between the Surface edge and this body.
            let surfaceLeft = body.frame.minX - 24
            XCTAssertGreaterThan(surfaceLeft, app.frame.minX + 2, "This fixture must expose a lateral scrim")
            let outside = CGPoint(x: (app.frame.minX + surfaceLeft) / 2, y: body.frame.midY)
            XCTAssertTrue(app.frame.contains(outside))
            attach("body=\(body.frame), surfaceLeft=\(surfaceLeft), outside=\(outside), app=\(app.frame)",
                   name: "\(name)-xml-outside-tap-geometry")
            app.coordinate(withNormalizedOffset: .zero)
                .withOffset(CGVector(dx: outside.x - app.frame.minX, dy: outside.y - app.frame.minY)).tap()
            let closed = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
                !body.exists && (self.identified("editor-project-toolbar").exists || self.identified("editor-command-toolbar").exists)
            }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 5), .completed,
                           "A complete tap outside the Surface must dismiss the XML dialog")
            screenshot("\(name)-xml-dismissed-outside")
        }
    }

}
