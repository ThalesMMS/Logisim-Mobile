import XCTest
import UIKit

/// Tests the native accessibility tree and real editor actions. This is not a VoiceOver test.
final class AccessibilityUITests: NativeUIHarness {
    private func openExplorer() throws {
        try tapKey("a11y_project_tools", toolbar: "editor-project-toolbar")
        try reveal(values("a11y_explorer_title"), verticalRegion: "editor-tool-menu").tap()
        XCTAssertTrue(identified("a11y-circuit-search").waitForExistence(timeout: 5))
    }

    private func closeExplorer() throws { try tapKey("action_close") }

    private func componentSearch() -> String {
        Bundle(for: Self.self).object(forInfoDictionaryKey: "ComponentSearch") as? String ?? "Reset"
    }

    private func requireVisibleSoftwareKeyboard(_ name: String) {
        let keyboard = app.keyboards.firstMatch
        func visibleKeys() -> Set<String> {
            guard keyboard.exists else { return [] }
            let window = app.frame
            let keyboardFrame = keyboard.frame
            return Set(keyboard.keys.allElementsBoundByIndex.compactMap { element in
                let frame = element.frame
                let center = CGPoint(x: frame.midX, y: frame.midY)
                guard frame.width > 0, frame.height > 0, frame.midX.isFinite, frame.midY.isFinite,
                      window.contains(center), keyboardFrame.contains(center) else { return nil }
                return element.label
            })
        }
        let shown = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            keyboard.exists && keyboard.frame.intersection(self.app.frame).height > 44 && visibleKeys().count >= 6
        }, object: app)
        let outcome = XCTWaiter.wait(for: [shown], timeout: 5)
        screenshot("\(name)-software-keyboard-evidence")
        attach("keyboard=\(keyboard.exists ? String(describing: keyboard.frame) : "absent"), app=\(app.frame), visible keys=\(visibleKeys().sorted())",
               name: "\(name)-software-keyboard-geometry")
        attach(app.debugDescription, name: "\(name)-software-keyboard-tree")
        XCTAssertEqual(outcome, .completed, "The software keyboard must be visibly on screen; an existing AX node or hardware input is insufficient")
        checkFrame(keyboard, name: "software-keyboard-\(name)")
    }

    private func selectComponent() throws -> String {
        let search = identified("a11y-circuit-search")
        search.tap()
        search.typeText(componentSearch())
        let labels = try values("a11y_explorer_select").map { $0.replacingOccurrences(of: "{name}", with: componentSearch()) }
        let item = try reveal(labels, verticalRegion: "a11y-circuit-list")
        let exact = matching(labels).allElementsBoundByIndex.filter { $0.identifier.hasPrefix("component:") }
        XCTAssertEqual(exact.count, 1, "The fixture must contain exactly one component with the requested label")
        XCTAssertTrue(item.identifier.hasPrefix("component:"))
        let id = item.identifier
        item.tap()
        return id
    }

    private func reachability(_ orientation: UIDeviceOrientation, name: String) throws {
        orient(orientation)
        let drawing = try canvas()
        checkFrame(drawing, name: "canvas")
        try openExplorer()
        _ = try selectComponent()
        screenshot("\(name)-explorer-selected")
        try app.performAccessibilityAudit()
        let prefixes = try values("a11y_explorer_edit").map { $0.components(separatedBy: "{name}")[0] }
        try reveal(prefixes, verticalRegion: "a11y-circuit-list", prefix: true).tap()
        let inspector = identified("editor-inspector-sheet")
        XCTAssertTrue(inspector.waitForExistence(timeout: 5), "Properties must be reachable in compact layouts")
        checkFrame(inspector, name: "inspector")
        screenshot("\(name)-inspector")
        attach(app.debugDescription, name: "\(name)-inspector-tree")
        try app.performAccessibilityAudit()
    }

    func testPortraitReachability() throws { try reachability(.portrait, name: "portrait") }
    func testLandscapeReachability() throws { try reachability(.landscapeLeft, name: "landscape") }

    func testSignalsRemainReachableInBothOrientations() throws {
        for (orientation, name) in [(UIDeviceOrientation.portrait, "portrait"), (.landscapeLeft, "landscape")] {
            orient(orientation)
            var panel = identified("editor-signal-panel")
            if !panel.exists {
                try tapKey("signals_heading", toolbar: "editor-command-toolbar")
                panel = identified("editor-signal-panel")
            }
            XCTAssertTrue(panel.waitForExistence(timeout: 5))
            checkFrame(panel, name: "signals-\(name)")
            let templates = try values("a11y_toggle_input").map { $0.replacingOccurrences(of: "{name}", with: componentSearch()) }
            let zeroLabels = templates.map { $0.replacingOccurrences(of: "{value}", with: "0") }
            let oneLabels = templates.map { $0.replacingOccurrences(of: "{value}", with: "1") }
            let input = try reveal(zeroLabels + oneLabels, toolbar: "editor-signal-panel")
            let wasZero = zeroLabels.contains { nativeLabel(input.label, matches: $0) }
            let wasOne = oneLabels.contains { nativeLabel(input.label, matches: $0) }
            XCTAssertNotEqual(wasZero, wasOne, "The fixture input must expose exactly one known bit")
            let before = wasZero ? zeroLabels : oneLabels
            let after = wasZero ? oneLabels : zeroLabels
            checkFrame(input, name: "signal-input-\(name)")
            input.tap()
            let changed = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
                self.visibleMatch(after) != nil
            }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 8), .completed, "The signal action must change the observed value")
            try reveal(after, toolbar: "editor-signal-panel").tap()
            let restored = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
                self.visibleMatch(before) != nil
            }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 8), .completed, "Restore the fixture's input value")
            screenshot("\(name)-signals")
            try app.performAccessibilityAudit()
            app.terminate()
            app.launch()
        }
    }

    func testSearchFocusAndTextSurviveRotationWithKeyboard() throws {
        orient(.portrait)
        try openExplorer()
        let search = identified("a11y-circuit-search")
        search.tap()
        search.typeText("Re")
        requireVisibleSoftwareKeyboard("portrait")
        checkFrame(search, name: "focused-search-portrait", avoidingKeyboard: true)
        screenshot("portrait-search-keyboard")
        orient(.landscapeLeft)
        requireVisibleSoftwareKeyboard("landscape")
        // Touch the visible software keys without focusing the field again.
        for key in ["s", "e", "t"] {
            let button = app.keyboards.firstMatch.keys[key].firstMatch
            XCTAssertTrue(button.waitForExistence(timeout: 5), "The software keyboard must expose key \(key)")
            button.tap()
        }
        XCTAssertEqual(search.value as? String, "Reset", "Rotation lost text or keyboard focus")
        checkFrame(search, name: "focused-search", avoidingKeyboard: true)
        screenshot("landscape-search-keyboard")
        try closeExplorer()
        checkFrame(try canvas(), name: "canvas-after-keyboard")
    }

    func testSelectedCircuitPointKeepsItsViewportPositionOnRotation() throws {
        orient(.portrait)
        try openExplorer()
        let id = try selectComponent()
        try closeExplorer()
        try tapKey("a11y_fit_selection", toolbar: "editor-command-toolbar")
        let item = identified("canvas-\(id)")
        XCTAssertTrue(item.waitForExistence(timeout: 5))
        XCTAssertTrue(item.isSelected, "The selection must be exposed to the native tree")
        let beforeCanvas = try canvas().frame
        let before = CGPoint(x: item.frame.midX - beforeCanvas.midX, y: item.frame.midY - beforeCanvas.midY)
        screenshot("portrait-selection-viewport")
        orient(.landscapeLeft)
        XCTAssertTrue(item.isSelected, "Rotation changed the selection")
        let afterCanvas = try canvas().frame
        let after = CGPoint(x: item.frame.midX - afterCanvas.midX, y: item.frame.midY - afterCanvas.midY)
        XCTAssertEqual(after.x, before.x, accuracy: 3, "Horizontal circuit position changed")
        XCTAssertEqual(after.y, before.y, accuracy: 3, "Vertical circuit position changed")
        screenshot("landscape-selection-viewport")
    }

    func testNativeAccessibilityAudit() throws {
        orient(.portrait)
        checkFrame(try canvas(), name: "canvas-before-audit")
        try app.performAccessibilityAudit()
        screenshot("native-audit")
        for tag in ["editor-project-toolbar", "editor-command-toolbar", "editor-simulation-toolbar"] {
            let overflow = identified("\(tag)-overflow")
            if overflow.exists {
                checkFrame(overflow, name: "\(tag)-overflow")
                XCTAssertTrue(overflow.isHittable)
                overflow.tap()
                let menu = identified("\(tag)-menu")
                XCTAssertTrue(menu.waitForExistence(timeout: 5))
                checkFrame(menu, name: "\(tag)-menu")
                screenshot("\(tag)-menu-audit")
                try app.performAccessibilityAudit()
                try tapKey("action_close")
                let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !menu.exists }, object: app)
                XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 5), .completed)
            }
        }
    }


}
