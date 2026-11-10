import XCTest
import UIKit

/// Selects a provider document through an already presented UIDocumentPicker.
/// auth003 observed activation and document selection before handoff diagnostics
/// failed. The caller still owns the full import and provider-operation oracles.
final class ProviderFilesNavigator {
    private unowned let harness: NativeUIHarness
    private var checkpoint = 0
    private var activationAttempted = false
    private var app: XCUIApplication { harness.app }

    private enum Labels {
        static let browse = ["Browse", "Explorar"]
        static let locations = ["Locations", "Localizações"]
        static let open = ["Open", "Abrir"]
    }

    init(harness: NativeUIHarness) {
        self.harness = harness
    }

    /// Dismissal confirms selection only; the caller must verify the imported project.
    func selectDocument(locationDisplayName: String, filename: String) throws {
        capture("initial-picker-census")
        guard locationDisplayName == ProviderActivationPrompt.expectedProviderName,
              !locationDisplayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !locationDisplayName.contains("\0"), !filename.isEmpty,
              filename != ".", filename != "..", !filename.contains("/"),
              !filename.contains("\\"), !filename.contains("\0") else {
            throw failure("Supply this fixture's exact provider display name and one document filename.", stage: "invalid-input")
        }
        let filenameLabels = documentLabels(filename)
        harness.attach("selectorStatus=PENDING_RUNTIME; location=\(locationDisplayName); filename=\(filename); documentLabels=\(filenameLabels)",
                       name: "provider-files-selector-contract")

        try require("one presented native document picker", stage: "picker-not-presented", timeout: 20) {
            NativeFilesBrowser.isPresented(in: self.app) && self.pickerBars().count == 1
        }
        guard NativeFilesBrowser.waitUntilLoaded(in: app) else {
            throw failure("The native document manager did not load its directory.", stage: "picker-not-loaded")
        }
        try requirePicker()
        try revealLocation(locationDisplayName)
        // A directory with the same title does not establish the provider's identity.
        // Always select its location row before using the heading as a postcondition.
        try tapUnique("provider location", stage: "provider-location", collection: "Browse View") {
            self.locationRows([locationDisplayName])
        }
        try activateSelectedProviderIfPrompted(locationDisplayName)
        _ = try unique("provider directory heading", stage: "provider-directory", actionable: false) {
            self.directoryHeadings([locationDisplayName])
        }
        capture("provider-directory-census")

        // Keep the frozen Files browser's exact labels plus the ", " metadata delimiter.
        // Keep the extension in the alias: a bare stem could also identify another file type.
        capture("before-document-reveal")
        try revealDocument(filenameLabels)
        try tapUnique("provider document", stage: "provider-document", collection: "File View") {
            self.contentRows(filenameLabels)
        }
        capture("after-document-selection")
        try require("picker handoff, a native Open leaf, or an alert", stage: "selection-did-not-progress", timeout: 20) {
            self.hasAlert() || self.pickerDismissed() || !self.navigationButtons(Labels.open).isEmpty
        }
        guard !hasAlert() else { throw failure("An unexpected alert appeared after document selection.", stage: "post-selection-alert") }
        if !pickerDismissed() {
            try tapUnique("native Open action", stage: "confirm-open") {
                self.navigationButtons(Labels.open)
            }
        }
        try require("native picker handoff or an alert", stage: "picker-remained-open", timeout: 25) {
            self.hasAlert() || self.pickerDismissed()
        }
        guard !hasAlert() else { throw failure("An unexpected alert appeared during the picker handoff.", stage: "handoff-alert") }
        // Handoff is a presence signal only. The caller still verifies the import,
        // complete XML and fresh provider operations before advancing the scenario.
        capture("picker-dismissed")
    }

    private func documentLabels(_ filename: String) -> [String] {
        let url = URL(fileURLWithPath: filename)
        // This alias follows the observed .circ row format; other extensions use the full name only.
        guard url.pathExtension == "circ" else { return [filename] }
        return [filename, url.deletingPathExtension().lastPathComponent + ", circ"]
    }

    private func revealLocation(_ displayName: String) throws {
        func reachedLocation() -> Bool {
            !locationRows([displayName]).isEmpty
        }
        if reachedLocation() { return }
        let tabs = app.tabBars.buttons.matching(exact(Labels.browse)).allElementsBoundByIndex.filter { $0.exists }
        if !tabs.isEmpty {
            try tapUnique("Files Browse tab", stage: "browse-tab") {
                self.app.tabBars.buttons.matching(self.exact(Labels.browse)).allElementsBoundByIndex.filter { $0.exists }
            }
        }
        for step in 1...6 {
            if reachedLocation() { return }
            let before = directoryHeadingElements().map(\.label)
            guard !before.isEmpty else { throw failure("No directory identity was available before Back.", stage: "back-directory-identity-missing") }
            try tapUnique("observed Browse/Locations or Back action", stage: "locations-ancestor-\(step)") {
                let roots = self.navigationButtons(Labels.browse + Labels.locations)
                if !roots.isEmpty { return roots }
                return self.pickerBars().flatMap {
                    $0.buttons.matching(identifier: "DOC.navBarButton.backInHistory").allElementsBoundByIndex
                }.filter { $0.exists }
            }
            try require("a changed Files directory or the requested provider location",
                        stage: "locations-ancestor-\(step)-did-not-progress", timeout: 15) {
                reachedLocation() || (!self.directoryHeadingElements().isEmpty && self.directoryHeadings(before).isEmpty)
            }
            capture("after-locations-ancestor-\(step)")
        }
        guard reachedLocation() else {
            throw failure("The provider location was not observed after six native ancestor actions.", stage: "provider-location-not-observed")
        }
    }

    private func exact(_ labels: [String]) -> NSPredicate {
        NSPredicate(format: "label IN %@", labels)
    }

    // Transition probes end at presence/identity queries. They never read child
    // geometry or call the shared helpers that filter their results by frame.
    private func labelMatch(_ labels: [String]) -> NSPredicate {
        NSCompoundPredicate(orPredicateWithSubpredicates: labels.map {
            NSPredicate(format: "label == %@ OR label BEGINSWITH %@", $0, $0 + ", ")
        })
    }

    private func directoryHeadingElements() -> [XCUIElement] {
        let identity = NSPredicate(format:
            "(elementType == %@ AND label != %@) OR (elementType == %@ AND label CONTAINS %@ AND (identifier == %@ OR identifier == NIL))",
            NSNumber(value: XCUIElement.ElementType.staticText.rawValue), "",
            NSNumber(value: XCUIElement.ElementType.button.rawValue), ", ", "")
        return pickerBars().flatMap { $0.descendants(matching: .any).matching(identity).allElementsBoundByIndex }.filter { $0.exists }
    }

    private func directoryHeadings(_ labels: [String]) -> [XCUIElement] {
        let title = labelMatch(labels)
        let anonymous = NSPredicate(format: "identifier == %@ OR identifier == NIL", "")
        return pickerBars().flatMap { bar in
            bar.staticTexts.matching(title).allElementsBoundByIndex +
                bar.buttons.matching(NSCompoundPredicate(andPredicateWithSubpredicates: [title, anonymous])).allElementsBoundByIndex
        }.filter { $0.exists }
    }

    private func collections(_ identifier: String) -> [XCUIElement] {
        app.collectionViews.matching(identifier: identifier).allElementsBoundByIndex.filter { $0.exists }
    }

    private func rows(_ identifier: String, labels: [String]) -> [XCUIElement] {
        collections(identifier).flatMap { $0.cells.matching(labelMatch(labels)).allElementsBoundByIndex }.filter { $0.exists }
    }

    private func locationRows(_ labels: [String]) -> [XCUIElement] { rows("Browse View", labels: labels) }
    private func contentRows(_ labels: [String]) -> [XCUIElement] { rows("File View", labels: labels) }

    private func hasAlert() -> Bool { app.alerts.allElementsBoundByIndex.contains { $0.exists } }

    private func revealDocument(_ labels: [String]) throws {
        try require("one native File View", stage: "file-view-missing", timeout: 15) {
            self.collections("File View").count == 1
        }
        for forward in [true, false] {
            for step in 0..<6 {
                let entries = contentRows(labels)
                guard entries.count <= 1 else { throw failure("The document row is ambiguous.", stage: "ambiguous-document-row") }
                if let entry = entries.first {
                    guard entry.isEnabled else { throw failure("The provider document row is disabled.", stage: "disabled-document-row") }
                    if visible(entry) { return }
                }
                try swipeFileView(forward: forward)
                capture("after-file-view-swipe-\(forward)-\(step + 1)")
                try require("the File View after scrolling", stage: "file-view-lost-after-swipe", timeout: 15) {
                    !self.hasAlert() && self.collections("File View").count == 1
                }
            }
        }
        throw failure("The provider document did not become actionable after bounded native scrolling.", stage: "document-not-revealed")
    }

    private func swipeFileView(forward: Bool) throws {
        try requirePicker(collection: "File View")
        // This proxy is acquired for this one gesture and is never reused after it.
        let collection = try unique("File View for scrolling", stage: "file-view-scroll", actionable: false) {
            self.collections("File View")
        }
        guard collection.isEnabled && collection.isHittable else {
            throw failure("The unique File View cannot receive a scroll gesture.", stage: "file-view-not-scrollable")
        }
        if forward { collection.swipeUp(velocity: .slow) } else { collection.swipeDown(velocity: .slow) }
    }

    private struct ActivationObservation {
        let alerts: [XCUIElement]
        let buttons: [[XCUIElement]]
        let census: [ProviderActivationPrompt.Alert]
    }

    /// This is called only after the unique provider location's validated tap.
    /// The document-manager bar is absent from AX while this native alert is up.
    private func activateSelectedProviderIfPrompted(_ displayName: String) throws {
        try require("the selected provider heading or a native alert", stage: "location-did-not-progress", timeout: 20) {
            !self.app.alerts.allElementsBoundByIndex.filter { $0.exists }.isEmpty ||
                !self.directoryHeadings([displayName]).isEmpty
        }
        capture("after-provider-location")
        let observed = activationObservation()
        let index: Int?
        do {
            index = try ProviderActivationPrompt.turnOnButtonIndex(locationDisplayName: displayName,
                afterLocationSelection: true, activationAttempted: activationAttempted, alerts: observed.census)
        } catch {
            throw failure("The selected provider exposed an inadmissible activation alert: \(error)", stage: "activation-alert-rejected")
        }
        if let index {
            capture("ready-to-activate-selected-provider")
            let current = activationObservation()
            let currentIndex: Int?
            do {
                currentIndex = try ProviderActivationPrompt.turnOnButtonIndex(locationDisplayName: displayName,
                    afterLocationSelection: true, activationAttempted: activationAttempted, alerts: current.census)
            } catch {
                throw failure("The activation alert changed before its tap: \(error)", stage: "activation-alert-changed")
            }
            guard current.census == observed.census, currentIndex == index, current.alerts.count == 1 else {
                throw failure("Activation title, body, controls or ownership changed before the tap.", stage: "activation-ownership-changed")
            }
            // The button comes from the same sole alert and the same census just validated.
            let button = current.buttons[0][index]
            activationAttempted = true
            button.tap()
            try require("activation alert dismissal", stage: "activation-alert-remained", timeout: 20) {
                self.app.alerts.allElementsBoundByIndex.allSatisfy { !$0.exists }
            }
            capture("after-provider-activation")
        }
        // Restore picker guards only after the alert is absent. Its disappearance
        // alone does not establish that Files reached the selected provider.
        try require("one native provider heading after activation", stage: "activation-provider-heading-missing", timeout: 20) {
            self.app.alerts.allElementsBoundByIndex.allSatisfy { !$0.exists } && self.pickerBars().count == 1 &&
                self.directoryHeadings([displayName]).count == 1
        }
        try requirePicker()
    }

    private func activationObservation() -> ActivationObservation {
        let alerts = app.alerts.allElementsBoundByIndex.filter { $0.exists }
        let buttons = alerts.map { $0.buttons.allElementsBoundByIndex.filter { $0.exists } }
        let census = alerts.enumerated().map { index, alert in
            let owner = alert.frame
            return ProviderActivationPrompt.Alert(control: activationControl(alert),
                staticTexts: alert.staticTexts.allElementsBoundByIndex.filter { $0.exists }.map {
                    activationControl($0, within: owner)
                },
                buttons: buttons[index].map { activationControl($0, within: owner) })
        }
        return ActivationObservation(alerts: alerts, buttons: buttons, census: census)
    }

    private func activationControl(_ element: XCUIElement, within owner: CGRect? = nil) -> ProviderActivationPrompt.Control {
        let role: ProviderActivationPrompt.Role
        switch element.elementType {
        case .alert: role = .alert
        case .staticText: role = .staticText
        case .button: role = .button
        default: role = .other
        }
        let frame = element.frame
        let center = CGPoint(x: frame.midX, y: frame.midY)
        let onScreen = displayed(frame) && (owner?.contains(center) ?? true)
        return ProviderActivationPrompt.Control(role: role, label: element.label, visible: onScreen,
            hittable: onScreen && element.isHittable, enabled: element.isEnabled)
    }

    private func displayed(_ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        return displayed(element.frame)
    }

    private func displayed(_ frame: CGRect) -> Bool {
        return frame.width > 0 && frame.height > 0 && frame.midX.isFinite && frame.midY.isFinite &&
            app.frame.contains(CGPoint(x: frame.midX, y: frame.midY))
    }

    private func visible(_ element: XCUIElement) -> Bool {
        displayed(element) && element.isHittable
    }

    private func pickerBars() -> [XCUIElement] {
        app.navigationBars.matching(identifier: "FullDocumentManagerViewControllerNavigationBar")
            .allElementsBoundByIndex.filter { $0.exists }
    }

    private func navigationButtons(_ labels: [String]) -> [XCUIElement] {
        pickerBars().flatMap { $0.buttons.matching(exact(labels)).allElementsBoundByIndex }.filter { $0.exists }
    }

    private func pickerDismissed() -> Bool {
        !hasAlert() && !NativeFilesBrowser.isPresented(in: app)
    }

    private func requirePicker(collection: String? = nil) throws {
        guard !hasAlert(), NativeFilesBrowser.isPresented(in: app), pickerBars().count == 1 else {
            throw failure("One native document-manager navigation bar is required without an alert.", stage: "picker-scope-changed")
        }
        if let collection, collections(collection).count != 1 {
            throw failure("One native \(collection) owner is required for the action.", stage: "collection-scope-changed")
        }
    }

    private func unique(_ name: String, stage: String, actionable: Bool = true,
                        elements: @escaping () -> [XCUIElement]) throws -> XCUIElement {
        try require(name, stage: "missing-" + stage, timeout: 15) { !elements().isEmpty }
        let candidates = elements()
        guard candidates.count == 1 else {
            throw failure("Expected one \(name); found \(candidates.count).", stage: "ambiguous-" + stage)
        }
        let candidate = candidates[0]
        guard !actionable || (visible(candidate) && candidate.isEnabled) else {
            throw failure("The observed \(name) is not reachable and enabled.", stage: "disabled-" + stage)
        }
        return candidate
    }

    private func tapUnique(_ name: String, stage: String, collection: String? = nil,
                           elements: @escaping () -> [XCUIElement]) throws {
        try requirePicker(collection: collection)
        capture("before-" + stage)
        capture("ready-to-tap-" + stage)
        // Snapshot capture can change layout; resolve and validate the control again before tapping.
        try requirePicker(collection: collection)
        let current = try unique(name, stage: stage, elements: elements)
        current.tap()
    }

    private func require(_ name: String, stage: String, timeout: TimeInterval,
                         condition: @escaping () -> Bool) throws {
        let appeared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: app)
        guard XCTWaiter.wait(for: [appeared], timeout: timeout) == .completed else {
            throw failure("The current picker did not expose \(name).", stage: stage)
        }
    }

    private func capture(_ stage: String) {
        checkpoint += 1
        let name = "provider-files-\(checkpoint)-\(stage)"
        harness.screenshot(name)
        harness.attach(app.debugDescription, name: name + "-tree")
        harness.attach("stage=\(stage); selectorStatus=PENDING_RUNTIME; geometry is retained only in the root tree",
                       name: name + "-stage")
    }

    private func failure(_ message: String, stage: String) -> Error {
        capture(stage)
        XCTFail(message)
        return NativeUIHarness.HarnessFailure.missingControl
    }
}
