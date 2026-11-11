import XCTest
import UIKit

/// Shared native UI operations for accessibility and editor end-to-end scenarios.
class NativeUIHarness: XCTestCase {
    var app: XCUIApplication!
    var catalog: [String: [String: Any]] = [:]
    private var originalOrientation = UIDeviceOrientation.unknown

    /// Default scenarios query the application; document-owned suites may retain one public window.
    var nativeQueryRoot: XCUIElement { app }

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
        try waitForEditorStartup(completeFirstLaunch: true)
    }

    func waitForEditorStartup(completeFirstLaunch: Bool = false) throws {
        let themeChooser = identified("first-launch-theme")
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            (completeFirstLaunch && themeChooser.exists) || self.identified("editor-import-report").exists ||
                self.identified("editor-project-toolbar").exists ||
                self.identified("editor-command-toolbar").exists
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 20), .completed, "The editor must finish its startup load")
        if completeFirstLaunch && themeChooser.exists {
            let continueButton = identified("first-launch-theme-continue")
            for _ in 0..<4 where !continueButton.isHittable { themeChooser.swipeUp() }
            XCTAssertTrue(continueButton.isHittable, "The initial theme choice must be confirmable")
            continueButton.tap()
            try waitForEditorStartup()
            return
        }
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

    func recordAccessibilityAudit(_ context: String) throws {
        var issueCount = 0
        attach(app.debugDescription, name: "audit-\(context)-tree-before")
        let previousContinuation = continueAfterFailure
        // Collect every issue from this audit while retaining each XCTest failure.
        continueAfterFailure = true
        defer { continueAfterFailure = previousContinuation }
        try app.performAccessibilityAudit { issue in
            issueCount += 1
            var details = [
                "context=\(context)", "auditTypeRaw=\(issue.auditType.rawValue)",
                "compact=\(issue.compactDescription)", "detailed=\(issue.detailedDescription)",
            ]
            if let element = issue.element {
                details.append("element.exists=\(element.exists)")
                if element.exists {
                    details += ["identifier=\(element.identifier)", "label=\(element.label)",
                                "value=\(String(describing: element.value))", "frame=\(element.frame)",
                                "elementType=\(element.elementType.rawValue)", element.debugDescription]
                }
            } else {
                details.append("element=nil")
            }
            self.attach(details.joined(separator: "\n"), name: "audit-\(context)-issue-\(issueCount)")
            self.screenshot("audit-\(context)-issue-\(issueCount)-screen")
            // Returning true would suppress the issue. Every issue stays recorded.
            return false
        }
        attach(app.debugDescription, name: "audit-\(context)-tree-after")
        attach("context=\(context), issues=\(issueCount), issueHandlerAlwaysReturns=false", name: "audit-\(context)-summary")
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
        let root: XCUIElement = container ?? nativeQueryRoot
        return root.descendants(matching: .any).matching(NSCompoundPredicate(orPredicateWithSubpredicates: predicates))
    }

    func nativeLabel(_ actual: String, matches canonical: String) -> Bool {
        actual == canonical || actual.hasPrefix(canonical + ", ")
    }

    func identified(_ id: String) -> XCUIElement {
        nativeQueryRoot.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    func visibleMatch(_ labels: [String], prefix: Bool = false, within container: XCUIElement? = nil) -> XCUIElement? {
        if let container, !container.exists { return nil }
        let window = app.frame
        let viewport = (container?.frame ?? nativeQueryRoot.frame).intersection(window)
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
        if toolbar == "editor-signal-panel" && !identified("editor-signal-panel").exists {
            // The signal panel stays folded until its status bar toggle opens it.
            try reveal(try values("signals_heading"), toolbar: "editor-simulation-toolbar").tap()
        }
        if let verticalRegion, toolbar == nil,
           verticalRegion.hasPrefix("editor-menu-") || ["editor-project-toolbar-menu", "editor-file-menu", "editor-tool-menu"].contains(verticalRegion) {
            // Menu popups reach the bottom of the screen; drag through their middle instead.
            if let found = try findInRegion(labels, region: verticalRegion, prefix: prefix) { return found }
            XCTFail("No reachable control for \(labels) in \(verticalRegion)")
            throw HarnessFailure.missingControl
        }
        let searchRegion = verticalRegion.map { identified($0) }
        if let searchRegion {
            XCTAssertTrue(quickWait(searchRegion, timeout: 5), "The requested menu or scroll region must be presented")
            // A sheet's list moves while the sheet rises. Measured then, a control on screen reads as hidden
            // and the first scroll below loses it (#367).
            waitForStableFrame(searchRegion)
        }
        func reachable() -> XCUIElement? {
            visibleMatch(labels, prefix: prefix, within: searchRegion)
        }
        if let toolbarRegion {
            if let found = reachable() { return found }
            if toolbarRegion != "editor-project-toolbar",
               let found = visibleMatch(labels, prefix: prefix, within: identified("editor-project-toolbar")) {
                // Library and properties buttons sit in the header on narrow windows.
                return found
            }
            let overflow = identified("\(toolbarRegion)-overflow")
            if overflow.exists {
                checkFrame(overflow, name: "\(toolbarRegion)-overflow")
                XCTAssertTrue(overflow.isHittable, "The toolbar menu must be reachable")
                overflow.tap()
                let menu = identified("\(toolbarRegion)-menu")
                if quickWait(menu, timeout: 2) {
                    if let found = try findInRegion(labels, region: "\(toolbarRegion)-menu", prefix: prefix) { return found }
                    if toolbarRegion == "editor-project-toolbar",
                       let found = try drillDown(labels, menu: "\(toolbarRegion)-menu", prefix: prefix) { return found }
                    XCTFail("No reachable control for \(labels) in \(toolbarRegion)-menu")
                    throw HarnessFailure.missingControl
                }
                // The toolbar unfolded into extra rows in place. Compose on iOS flattens those rows
                // beside the toolbar node, so search the window rather than the toolbar subtree.
                if let found = visibleMatch(labels, prefix: prefix) { return found }
            }
            if let found = try revealInMenus(labels, prefix: prefix) { return found }
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
            // The last scroll of a direction can be the one that uncovers the control.
            if let found = reachable() { return found }
        }
        XCTFail("No reachable control for \(labels)")
        throw HarnessFailure.missingControl
    }

    /// Wait until an element keeps one frame across two samples, or give up and let the caller measure it.
    func waitForStableFrame(_ element: XCUIElement, timeout: TimeInterval = 3) {
        var previous = element.frame
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
            let frame = element.frame
            if frame == previous { return }
            previous = frame
        }
    }

    /// The open inspector's tag: a modal sheet, or the panel docked beside a wide canvas (#321).
    func presentedInspector(timeout: TimeInterval = 10) -> String? {
        let sheet = identified("editor-inspector-sheet")
        let inline = identified("editor-inspector-inline")
        let toolSheet = identified("editor-tool-sheet")
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if sheet.exists { return "editor-inspector-sheet" }
            // The docked panel exists behind the Explorer too; it is the inspector once that sheet closes.
            if inline.exists && !toolSheet.exists { return "editor-inspector-inline" }
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        } while Date() < deadline
        return nil
    }

    /// Reveal a control of the open inspector, scrolling the docked panel, which is shorter than its content.
    func revealInInspector(_ labels: [String], prefix: Bool = false) throws -> XCUIElement {
        let inline = identified("editor-inspector-inline")
        guard !identified("editor-inspector-sheet").exists, inline.exists else {
            return try reveal(labels, prefix: prefix)
        }
        waitForStableFrame(inline)
        // Compose on iOS exports the panel's tag beside its content, so match by the panel's frame.
        let panel = inline.frame.intersection(app.frame)
        func found() -> XCUIElement? {
            matching(labels, prefix: prefix).allElementsBoundByIndex.first { element in
                guard element.exists else { return false }
                let frame = element.frame
                guard frame.width > 0, frame.height > 0, panel.contains(CGPoint(x: frame.midX, y: frame.midY)) else { return false }
                return element.isHittable
            }
        }
        // Component panels stay pinned above the property list, so drag only the panel's lower band
        // and in short steps: a full-height drag would start on a pinned key or skip whole rows.
        let origin = app.coordinate(withNormalizedOffset: .zero)
        func point(_ y: CGFloat) -> XCUICoordinate {
            origin.withOffset(CGVector(dx: panel.midX - app.frame.minX, dy: y - app.frame.minY))
        }
        for forward in [true, false] {
            for _ in 0..<24 {
                if let element = found() { return element }
                let lower = panel.maxY - 8, upper = lower - 44
                point(forward ? lower : upper).press(forDuration: 0.05, thenDragTo: point(forward ? upper : lower),
                                                     withVelocity: .slow, thenHoldForDuration: 0.1)
            }
            if let element = found() { return element }
        }
        XCTFail("No reachable control for \(labels) in the docked inspector")
        throw HarnessFailure.missingControl
    }

    /// Search one identified menu viewport, scrolling it both ways, without failing the test.
    func findInRegion(_ labels: [String], region id: String, prefix: Bool = false) throws -> XCUIElement? {
        let region = identified(id)
        guard quickWait(region, timeout: 3) else { return nil }
        if let found = visibleMatch(labels, prefix: prefix, within: region) { return found }
        if contentFits(region) { return nil }
        for direction in [true, false] {
            var previous = contentSignature(region)
            for _ in 0..<8 {
                dragMenu(region, forward: direction)
                if let found = visibleMatch(labels, prefix: prefix, within: region) { return found }
                let signature = contentSignature(region)
                if signature == previous { break }
                previous = signature
            }
        }
        return nil
    }

    /// Poll a single element without XCTest's one-second existence interval.
    @discardableResult
    func quickWait(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if element.exists { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        } while Date() < deadline
        return element.exists
    }

    /// Whether every element of a menu lies inside its visible frame, so scrolling cannot reveal more.
    func contentFits(_ region: XCUIElement) -> Bool {
        guard let snapshot = try? region.snapshot() else { return false }
        let visible = snapshot.frame.intersection(app.frame).insetBy(dx: -1, dy: -1)
        func inside(_ node: XCUIElementSnapshot) -> Bool {
            (node.frame.isEmpty || visible.contains(node.frame)) && node.children.allSatisfy(inside)
        }
        return snapshot.children.allSatisfy(inside)
    }

    /// Drag through the middle of a menu, away from the screen-edge system gesture areas.
    func dragMenu(_ region: XCUIElement, forward: Bool) {
        let top = region.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25))
        let bottom = region.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
        let (start, end) = forward ? (bottom, top) : (top, bottom)
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.1)
    }

    /// Labels and frames of a region's subtree, from one snapshot; unchanged after a scroll means its end.
    func contentSignature(_ region: XCUIElement) -> String {
        guard let snapshot = try? region.snapshot() else { return "" }
        func walk(_ node: XCUIElementSnapshot) -> String {
            node.label + "@" + NSCoder.string(for: node.frame) + "[" + node.children.map(walk).joined(separator: ";") + "]"
        }
        return walk(snapshot)
    }

    /// Open menus: the menu bar's popups, the compact menu, and the File and Project item lists.
    static let menuPopups = NSPredicate(format: "identifier BEGINSWITH 'editor-menu-' OR identifier IN %@",
                                        ["editor-project-toolbar-menu", "editor-file-menu", "editor-tool-menu"])

    private func openMenu() -> String? {
        let open = app.descendants(matching: .any).matching(Self.menuPopups).firstMatch
        return quickWait(open, timeout: 3) ? open.identifier : nil
    }

    private func menuTitles(in container: XCUIElement) -> [String] {
        container.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'menu-title-'"))
            .allElementsBoundByIndex.map(\.identifier)
    }

    /// Search the header's menus: each menu bar title, or the compact menu and its submenus.
    func revealInMenus(_ labels: [String], prefix: Bool = false) throws -> XCUIElement? {
        let header = identified("editor-project-toolbar")
        guard header.exists else { return nil }
        // Compose on iOS flattens the menu bar beside the header node; search the window.
        let titles = menuTitles(in: nativeQueryRoot)
        for title in titles {
            identified(title).tap()
            if let popup = openMenu(), let found = try findInRegion(labels, region: popup, prefix: prefix) { return found }
            dismissMenus()
        }
        let overflow = identified("editor-project-toolbar-overflow")
        guard titles.isEmpty, overflow.exists else { return nil }
        overflow.tap()
        if let found = try findInRegion(labels, region: "editor-project-toolbar-menu", prefix: prefix) { return found }
        return try drillDown(labels, menu: "editor-project-toolbar-menu", prefix: prefix)
    }

    /// Open each submenu of the compact header menu in turn.
    func drillDown(_ labels: [String], menu: String, prefix: Bool = false) throws -> XCUIElement? {
        let titles = menuTitles(in: identified(menu))
        for title in titles {
            if !identified(menu).exists {
                identified("editor-project-toolbar-overflow").tap()
                XCTAssertTrue(quickWait(identified(menu), timeout: 3), "The compact menu must reopen")
            }
            let item = identified(title)
            item.tap()
            harnessWait("opened submenu \(title)") { !item.exists }
            if let popup = openMenu(), let found = try findInRegion(labels, region: popup, prefix: prefix) { return found }
            dismissMenus()
        }
        return nil
    }

    /// Close an open menu by tapping the status line, which has no action of its own.
    func dismissMenus() {
        let open = app.descendants(matching: .any).matching(Self.menuPopups).firstMatch
        guard open.exists else { return }
        // Element-relative coordinates follow the interface orientation. A long menu reaches the status
        // line and its last item would take the tap (#367), so tap the line beyond the menu's end.
        let status = identified("editor-status")
        let line = status.frame, menu = open.frame
        var dx: CGFloat = 0.9
        if menu.intersects(line) && line.width > 0 {
            dx = (menu.maxX + 16 - line.minX) / line.width
            XCTAssertLessThan(dx, 1, "The status line must extend beyond the open menu")
        }
        status.coordinate(withNormalizedOffset: CGVector(dx: dx, dy: 0.5)).tap()
        harnessWait("closed editor menu") { !open.exists }
    }

    /// Open the File menu, which replaced the project files dialog.
    func openEditorFileMenu() throws {
        try tapKey("a11y_project_files", toolbar: "editor-project-toolbar")
        XCTAssertTrue(identified("editor-file-menu").waitForExistence(timeout: 5), "The File menu must open")
    }

    func harnessWait(_ description: String, timeout: TimeInterval = 5, _ condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: timeout), .completed, description)
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
            let view = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
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
        if summaries.count != 1 {
            attach(summaries.map { "\($0.elementType.rawValue) \($0.frame) \($0.label)" }.joined(separator: "\n") +
                   "\n\n" + app.debugDescription, name: "canvas-description-mismatch-tree")
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
