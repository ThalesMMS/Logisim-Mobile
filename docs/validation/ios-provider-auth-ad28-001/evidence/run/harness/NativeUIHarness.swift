import XCTest
import UIKit

/// Shared native UI operations for accessibility and editor end-to-end scenarios.
class NativeUIHarness: XCTestCase {
    var app: XCUIApplication!
    var catalog: [String: [String: Any]] = [:]
    private var originalOrientation = UIDeviceOrientation.unknown

    override func setUpWithError() throws {
        continueAfterFailure = false
        let bundle = Bundle(for: Self.self)
        let identifier = try XCTUnwrap(bundle.object(forInfoDictionaryKey: "TargetAppBundleIdentifier") as? String)
        XCTAssertFalse(identifier.isEmpty, "Supply the bundle identifier of the installed artifact")
        let url = try XCTUnwrap(bundle.url(forResource: "Catalog", withExtension: "json"))
        catalog = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: [String: Any]])
        originalOrientation = XCUIDevice.shared.orientation
        app = XCUIApplication(bundleIdentifier: identifier)
        app.launch()
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            self.identified("editor-import-report").exists || self.identified("editor-project-toolbar").exists ||
                self.identified("editor-command-toolbar").exists
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 20), .completed, "The editor must finish its startup load")
        try closeImportReportIfPresented()
    }

    override func tearDownWithError() throws {
        if let app {
            attach(app.debugDescription, name: "native-tree")
            screenshot("final-screen")
            app.terminate()
        }
        if originalOrientation != .unknown { XCUIDevice.shared.orientation = originalOrientation }
    }

    func values(_ key: String) throws -> [String] {
        let entry = try XCTUnwrap(catalog[key], "Missing canonical localization key: \(key)")
        let values = entry.values.compactMap { $0 as? String }
        XCTAssertFalse(values.isEmpty, "Expected a nonplural control label: \(key)")
        return values
    }

    func closeImportReportIfPresented() throws {
        let report = identified("editor-import-report")
        guard report.exists else { return }
        screenshot("import-report-before-close")
        attach(app.debugDescription, name: "import-report-before-close-tree")
        // A pending system Open With can replace the restored project while the
        // evidence is captured. Do not tap a Close action from that obsolete view.
        guard report.exists else { return }
        try tapKey("action_close")
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !report.exists }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 10), .completed,
                       "Close must dismiss the specific import report without converting or editing the project")
    }

    func matching(_ labels: [String], prefix: Bool = false, within container: XCUIElement? = nil) -> XCUIElementQuery {
        let predicates: [NSPredicate] = labels.map { label in
            if prefix { return NSPredicate(format: "label BEGINSWITH %@", label) }
            // Compose iOS merges contentDescription and visible text, for example
            // "Open project tools, Tools". Require the delimiter to keep names exact.
            return NSCompoundPredicate(orPredicateWithSubpredicates: [
                NSPredicate(format: "label == %@", label),
                NSPredicate(format: "label BEGINSWITH %@", label + ", "),
            ])
        }
        let root: XCUIElement = container ?? app
        return root.descendants(matching: .any).matching(NSCompoundPredicate(orPredicateWithSubpredicates: predicates))
    }

    func nativeLabel(_ actual: String, matches canonical: String) -> Bool {
        actual == canonical || actual.hasPrefix(canonical + ", ")
    }

    func identified(_ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    func visibleMatch(_ labels: [String], prefix: Bool = false, within container: XCUIElement? = nil) -> XCUIElement? {
        let window = app.frame
        let viewport = container?.frame.intersection(window) ?? window
        return matching(labels, prefix: prefix, within: container).allElementsBoundByIndex.first { element in
            guard element.exists else { return false }
            let frame = element.frame
            // Offscreen Compose nodes can have an invalid activation point. Inspect
            // geometry before asking XCTest for hittability, then let reveal scroll.
            guard frame.width > 0, frame.height > 0, frame.midX.isFinite, frame.midY.isFinite,
                  viewport.contains(CGPoint(x: frame.midX, y: frame.midY)) else { return false }
            return element.isHittable
        }
    }

    /// Scroll identified containers only; a global swipe could move the circuit.
    func reveal(_ labels: [String], toolbar: String? = nil, verticalRegion: String? = nil,
                        prefix: Bool = false) throws -> XCUIElement {
        if let toolbar {
            var candidates = [toolbar]
            if toolbar == "editor-project-toolbar" || toolbar == "editor-simulation-toolbar" {
                candidates.append("editor-command-toolbar")
            }
            // A modal hides the background toolbar from the native tree. Reuse
            // its observed menu before inspecting or touching that background.
            if let menu = candidates.map({ "\($0)-menu" }).first(where: { identified($0).exists }) {
                return try reveal(labels, verticalRegion: menu, prefix: prefix)
            }
        }
        var toolbarRegion = toolbar
        if let toolbar, ["editor-project-toolbar", "editor-simulation-toolbar"].contains(toolbar),
           !identified(toolbar).exists && identified("editor-command-toolbar").exists {
            toolbarRegion = "editor-command-toolbar"
        }
        let searchRegion = verticalRegion.map { identified($0) }
        if let searchRegion {
            XCTAssertTrue(searchRegion.waitForExistence(timeout: 5), "The requested menu or scroll region must be presented")
        }
        func reachable() -> XCUIElement? {
            visibleMatch(labels, prefix: prefix, within: searchRegion)
        }
        if let toolbarRegion {
            if let found = reachable() { return found }
            let overflow = identified("\(toolbarRegion)-overflow")
            if overflow.exists {
                checkFrame(overflow, name: "\(toolbarRegion)-overflow")
                XCTAssertTrue(overflow.isHittable, "The toolbar menu must be reachable")
                overflow.tap()
                return try reveal(labels, verticalRegion: "\(toolbarRegion)-menu", prefix: prefix)
            }
        }
        if toolbar == nil && verticalRegion == nil {
            if let found = reachable() { return found }
            let appeared = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
                self.visibleMatch(labels, prefix: prefix) != nil
            }, object: app)
            if XCTWaiter.wait(for: [appeared], timeout: 5) == .completed,
               let found = visibleMatch(labels, prefix: prefix) { return found }
            XCTFail("No reachable control for \(labels) after waiting for its native presentation")
            throw HarnessFailure.missingControl
        }
        for direction in [true, false] {
            for _ in 0..<12 {
                if let found = reachable() { return found }
                guard let regionId = toolbarRegion ?? verticalRegion else { break }
                let region = identified(regionId)
                XCTAssertTrue(region.waitForExistence(timeout: 5), "Container \(regionId) must be identified for safe scrolling")
                try scroll(region, horizontal: toolbarRegion != nil, forward: direction)
            }
        }
        XCTFail("No reachable control for \(labels)")
        throw HarnessFailure.missingControl
    }

    func scroll(_ region: XCUIElement, horizontal: Bool, forward: Bool) throws {
        try scrollFrame(region.frame, horizontal: horizontal, forward: forward)
    }

    func softwareInputFrames() -> [String: CGRect] {
        guard app.keyboards.firstMatch.exists else { return [:] }
        var candidates = ["Keyboard": app.keyboards.firstMatch.frame]
        // The observed iOS tree exposes predictions above the Keyboard node.
        // Include those native input surfaces instead of guessing an extra inset.
        for identifier in ["inputView", "SystemInputAssistantView"] {
            let view = identified(identifier)
            if view.exists { candidates[identifier] = view.frame }
        }
        return candidates.compactMapValues { frame in
            guard frame.minX.isFinite, frame.minY.isFinite, frame.width.isFinite, frame.height.isFinite,
                  frame.width > 0, frame.height > 0 else { return nil }
            let visible = frame.intersection(app.frame)
            return visible.isNull || visible.width <= 0 || visible.height <= 0 ? nil : visible
        }
    }

    func scrollFrame(_ frame: CGRect, horizontal: Bool, forward: Bool) throws {
        var visible = frame.intersection(app.frame)
        for keyboard in softwareInputFrames().values {
            if keyboard.minX <= visible.midX && keyboard.maxX >= visible.midX && keyboard.minY >= app.frame.minY {
                visible = visible.intersection(CGRect(x: app.frame.minX, y: app.frame.minY,
                                                     width: app.frame.width, height: keyboard.minY - app.frame.minY))
            }
        }
        XCTAssertFalse(visible.isNull, "The identified scroll area is covered")
        XCTAssertGreaterThan(horizontal ? visible.width : visible.height, 24)
        let inset: CGFloat = 8
        let start = horizontal ? CGPoint(x: visible.maxX - inset, y: visible.midY) : CGPoint(x: visible.midX, y: visible.maxY - inset)
        let end = horizontal ? CGPoint(x: visible.minX + inset, y: visible.midY) : CGPoint(x: visible.midX, y: visible.minY + inset)
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let first = forward ? start : end
        let last = forward ? end : start
        origin.withOffset(CGVector(dx: first.x - app.frame.minX, dy: first.y - app.frame.minY))
            .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: last.x - app.frame.minX, dy: last.y - app.frame.minY)),
                   withVelocity: .slow, thenHoldForDuration: 0.1)
    }

    func orient(_ orientation: UIDeviceOrientation) {
        XCUIDevice.shared.orientation = orientation
        let landscape = orientation == .landscapeLeft || orientation == .landscapeRight
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            let frame = self.app.frame
            return frame.width > 0 && frame.height > 0 && (landscape ? frame.width > frame.height : frame.height > frame.width)
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 10), .completed,
                       "Requested orientation was not observed in the actual application window")
        attach("requested=\(orientation.rawValue), actual app frame=\(app.frame)", name: "observed-orientation")
    }

    func tapKey(_ key: String, toolbar: String? = nil) throws {
        try reveal(values(key), toolbar: toolbar).tap()
    }

    func canvas() throws -> XCUIElement {
        let tagged = identified("editor-canvas")
        if tagged.exists { return tagged }
        let prefixes = try values("a11y_canvas").map { $0.components(separatedBy: "{outputs}")[0].trimmingCharacters(in: .whitespaces) }
        return try XCTUnwrap(visibleMatch(prefixes, prefix: true), "The visible circuit canvas must be exposed")
    }

    func canvasDescription() throws -> String {
        let target = try canvas()
        let prefixes = try values("a11y_canvas").map { $0.components(separatedBy: "{outputs}")[0].trimmingCharacters(in: .whitespaces) }
        // Compose exposes the semantic summary separately from the tagged drawing
        // node. Read its text only when both refer to the same observed canvas.
        let summaries = matching(prefixes, prefix: true).allElementsBoundByIndex.filter {
            $0.exists && $0.frame == target.frame
        }
        XCTAssertEqual(summaries.count, 1, "The canvas must expose one matching circuit description")
        return try XCTUnwrap(summaries.first).label
    }

    func checkFrame(_ element: XCUIElement, name: String, minimum: CGSize = CGSize(width: 44, height: 44), avoidingKeyboard: Bool = false) {
        XCTAssertTrue(element.exists, "Missing \(name)")
        let frame = element.frame
        let clipped = frame.intersection(app.frame)
        let inputFrames = avoidingKeyboard ? softwareInputFrames() : [:]
        attach("\(name): frame=\(frame), visible=\(clipped), input surfaces=\(inputFrames), app=\(app.frame)", name: "geometry-\(name)")
        XCTAssertFalse(clipped.isNull, "\(name) is outside the application")
        XCTAssertGreaterThanOrEqual(clipped.width, minimum.width, "\(name) is clipped horizontally")
        XCTAssertGreaterThanOrEqual(clipped.height, minimum.height, "\(name) is clipped vertically")
        if avoidingKeyboard {
            XCTAssertEqual(clipped.width, frame.width, accuracy: 1, "The entire focused \(name) must stay on screen")
            XCTAssertEqual(clipped.height, frame.height, accuracy: 1, "The entire focused \(name) must stay on screen")
            for (surface, inputFrame) in inputFrames {
                let covered = frame.intersection(inputFrame)
                XCTAssertTrue(covered.isNull || covered.width <= 1 || covered.height <= 1,
                              "The focused \(name) overlaps the visible \(surface): \(covered)")
            }
        }
    }

    func screenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func attach(_ text: String, name: String) {
        let attachment = XCTAttachment(string: text)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    enum HarnessFailure: Error { case missingControl }
}
