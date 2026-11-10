import XCTest
import UIKit

/// Uses the Home Screen's real window commands; it never requests an application scene directly.
/// The caller must still verify distinct projects or documents to establish scene isolation.
final class NativeWindowNavigator {
    typealias WindowCardSelector = (XCUIApplication) throws -> [XCUIElement]

    private let harness: NativeUIHarness
    private let appDisplayName: String
    private let bundleIdentifier: String?
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    private var overviewRequested = false
    private var checkpointNumber = 0

    // Apple documents these system labels in its English and Portuguese iPad guides.
    private let newWindowLabels = ["New Window", "Nova Janela", "Nova janela"]
    private let overviewLabels = ["Show All Windows", "Mostrar Todas as Janelas", "Mostrar todas as janelas"]

    init(harness: NativeUIHarness, appDisplayName: String) {
        self.harness = harness
        self.appDisplayName = appDisplayName
        bundleIdentifier = Bundle(for: type(of: harness))
            .object(forInfoDictionaryKey: "TargetAppBundleIdentifier") as? String
    }

    /// Verifies that the system accepted its New Window command and brought the app forward.
    /// A test must subsequently verify the new scene through project state or observed overview cards.
    func createNewWindow() throws {
        overviewRequested = false
        let app = try targetApplication()
        try chooseIconCommand(newWindowLabels, stage: "new-window")
        guard app.wait(for: .runningForeground, timeout: 12) else {
            try fail("New Window did not bring the target application to the foreground.", stage: "new-window-not-foreground")
        }
        capture("new-window-foreground", application: app)
    }

    /// Opens the system overview. Follow with windowCards(selector:) to prove that its cards appeared.
    func showOverview() throws {
        overviewRequested = false
        try chooseIconCommand(overviewLabels, stage: "show-all-windows")
        overviewRequested = true
        capture("overview-command-completed", application: springboard)
    }

    /// The selector must target scene-card containers observed in the current OS accessibility tree.
    /// There is no default query because UIKit windows and app icons are not scene-card identities.
    func windowCards(selector: @escaping WindowCardSelector) throws -> [XCUIElement] {
        guard overviewRequested else {
            try fail("Call showOverview() before querying the system's scene cards.", stage: "overview-not-requested")
        }
        var cards: [XCUIElement] = []
        var selectorError: Error?
        let observed = wait(timeout: 10) {
            do {
                cards = self.distinctVisibleCards(try selector(self.springboard))
                return !cards.isEmpty
            } catch {
                selectorError = error
                return true
            }
        }
        if let selectorError {
            try fail("The explicit scene-card selector failed: \(selectorError)", stage: "card-selector-error")
        }
        guard observed, !cards.isEmpty else {
            try fail("The selector exposed no visible scene cards for \(appDisplayName). Icons and cards smaller than 100 by 100 points are rejected.", stage: "scene-cards-not-observed")
        }
        let summary = cards.enumerated().map {
            "card[\($0.offset)]: type=\($0.element.elementType.rawValue), label=\($0.element.label), identifier=\($0.element.identifier), frame=\($0.element.frame)"
        }.joined(separator: "\n")
        harness.attach(summary, name: "observed-scene-cards")
        capture("scene-cards-observed", application: springboard)
        return cards
    }

    /// A card tap succeeds only when the caller's actual project/document condition becomes true.
    func select(_ card: XCUIElement, postcondition: @escaping () throws -> Bool) throws {
        guard overviewRequested, isVisibleSceneCard(card) else {
            try fail("Selection requires a visible scene card from the requested overview.", stage: "invalid-scene-card")
        }
        capture("before-card-selection", application: springboard)
        card.tap()
        let app = try targetApplication()
        guard app.wait(for: .runningForeground, timeout: 12) else {
            try fail("The selected scene card did not bring the target application forward.", stage: "selected-card-not-foreground")
        }
        var verificationError: Error?
        let verified = wait(timeout: 12) {
            do { return try postcondition() }
            catch { verificationError = error; return true }
        }
        if let verificationError {
            try fail("The selected scene's project verification failed: \(verificationError)", stage: "selected-project-verification-error")
        }
        guard verified else {
            try fail("The card was tapped, but the expected project or document was not observed.", stage: "selected-project-not-observed")
        }
        overviewRequested = false
        capture("selected-project-observed", application: app)
    }

    private func targetApplication() throws -> XCUIApplication {
        guard let app = harness.app else {
            try fail("NativeUIHarness must complete setUpWithError() before window navigation.", stage: "harness-not-ready")
        }
        guard !appDisplayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            try fail("Supply the target app's exact display name.", stage: "app-name-empty")
        }
        return app
    }

    private func chooseIconCommand(_ labels: [String], stage: String) throws {
        _ = try targetApplication()
        XCUIDevice.shared.press(.home)
        guard springboard.wait(for: .runningForeground, timeout: 10) else {
            try fail("The Home Screen did not become foreground.", stage: "\(stage)-home-not-observed")
        }
        capture("\(stage)-home", application: springboard)
        var icons: [XCUIElement] = []
        guard wait(timeout: 8, until: {
            icons = self.visibleAppIcons()
            return !icons.isEmpty
        }) else {
            try fail("No hittable Home Screen or Dock icon matches the exact display name or configured bundle identifier. Put the target icon on the visible page before this scenario.", stage: "\(stage)-icon-not-observed")
        }
        guard icons.count == 1 else {
            try fail("More than one hittable app icon matches the target. Resolve duplicate installations before choosing a window command.", stage: "\(stage)-ambiguous-icon")
        }
        icons[0].press(forDuration: 1)
        capture("\(stage)-icon-menu", application: springboard)
        var commands: [XCUIElement] = []
        guard wait(timeout: 8, until: {
            commands = self.visibleCommands(labels)
            return !commands.isEmpty
        }) else {
            try fail("The system icon menu did not expose a documented window command: \(labels). Check Windowed Apps or Stage Manager and the actual OS menu labels.", stage: "\(stage)-command-not-observed")
        }
        guard commands.count == 1 else {
            try fail("The system exposed multiple hittable controls for \(labels). A unique observed command is required.", stage: "\(stage)-ambiguous-command")
        }
        let command = commands[0]
        command.tap()
        guard wait(timeout: 8, until: { !command.exists }) else {
            try fail("The window command left its menu open; no successful navigation is assumed.", stage: "\(stage)-menu-remained-open")
        }
    }

    private func visibleAppIcons() -> [XCUIElement] {
        let icons = springboard.icons.allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
        if let bundleIdentifier, !bundleIdentifier.isEmpty {
            let exactIdentifier = icons.filter { $0.identifier == bundleIdentifier }
            if !exactIdentifier.isEmpty { return exactIdentifier }
        }
        return icons.filter { $0.label == appDisplayName || $0.identifier == appDisplayName }
    }

    private func visibleCommands(_ labels: [String]) -> [XCUIElement] {
        let exact = NSPredicate(format: "label IN %@", labels)
        return (springboard.buttons.matching(exact).allElementsBoundByIndex +
                springboard.menuItems.matching(exact).allElementsBoundByIndex)
            .filter { $0.exists && $0.isHittable }
    }

    private func isVisibleSceneCard(_ element: XCUIElement) -> Bool {
        guard element.exists, element.isHittable, element.elementType != .icon,
              element.label.hasPrefix(appDisplayName) || element.identifier.hasPrefix(appDisplayName) else { return false }
        let visible = element.frame.intersection(springboard.frame)
        return !visible.isNull && visible.width >= 100 && visible.height >= 100
    }

    private func distinctVisibleCards(_ candidates: [XCUIElement]) -> [XCUIElement] {
        var result: [XCUIElement] = []
        for card in candidates where isVisibleSceneCard(card) {
            if !result.contains(where: { $0.frame == card.frame && $0.label == card.label }) { result.append(card) }
        }
        return result
    }

    private func wait(timeout: TimeInterval, until condition: @escaping () -> Bool) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        return XCTWaiter.wait(for: [expectation], timeout: timeout) == .completed
    }

    private func capture(_ stage: String, application: XCUIApplication) {
        checkpointNumber += 1
        let name = "window-\(checkpointNumber)-\(stage)"
        harness.screenshot(name)
        harness.attach(application.debugDescription, name: "\(name)-tree")
    }

    private func fail(_ message: String, stage: String) throws -> Never {
        if let app = harness.app, app.state == .runningForeground {
            capture(stage, application: app)
        } else {
            capture(stage, application: springboard)
        }
        XCTFail(message)
        throw NavigationFailure(message: message)
    }

    private struct NavigationFailure: Error, CustomStringConvertible {
        let message: String
        var description: String { message }
    }
}
