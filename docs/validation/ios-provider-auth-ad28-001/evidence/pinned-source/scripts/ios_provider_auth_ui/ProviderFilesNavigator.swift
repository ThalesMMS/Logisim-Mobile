import XCTest
import UIKit

/// Selects a provider document through an already presented UIDocumentPicker.
/// The provider location and document selectors await their first native census.
final class ProviderFilesNavigator {
    private unowned let harness: NativeUIHarness
    private var checkpoint = 0
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
        guard !locationDisplayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !locationDisplayName.contains("\0"), !filename.isEmpty,
              filename != ".", filename != "..", !filename.contains("/"),
              !filename.contains("\\"), !filename.contains("\0") else {
            throw failure("Supply the exact provider display name and a single document filename.", stage: "invalid-input")
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
        try tapUnique("provider location", stage: "provider-location") {
            NativeFilesBrowser.locationRows(in: self.app, labels: [locationDisplayName]).filter(self.visible)
        }
        _ = try unique("provider directory heading", stage: "provider-directory", actionable: false) {
            NativeFilesBrowser.directoryHeadings(in: self.app, labels: [locationDisplayName])
        }
        capture("provider-directory-census")

        // The frozen Files browser recognizes exact labels plus the ", " metadata delimiter.
        // Keep the extension in the alias: a bare stem could also identify another file type.
        capture("before-document-reveal")
        try NativeFilesBrowser.revealEntry(in: app, labels: filenameLabels, harness: harness)
        try tapUnique("provider document", stage: "provider-document") {
            NativeFilesBrowser.contentRows(in: self.app, labels: filenameLabels).filter(self.visible)
        }
        capture("after-document-selection")
        try require("picker dismissal or a native Open action", stage: "selection-did-not-progress", timeout: 20) {
            self.pickerDismissed() || !self.navigationButtons(Labels.open).isEmpty
        }
        if !pickerDismissed() {
            try tapUnique("native Open action", stage: "confirm-open") {
                self.navigationButtons(Labels.open)
            }
        }
        try require("native picker dismissal", stage: "picker-remained-open", timeout: 25) {
            self.pickerDismissed()
        }
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
            !NativeFilesBrowser.locationRows(in: app, labels: [displayName]).isEmpty
        }
        if reachedLocation() { return }
        let tabs = app.tabBars.buttons.matching(exact(Labels.browse)).allElementsBoundByIndex.filter(visible)
        if !tabs.isEmpty {
            try tapUnique("Files Browse tab", stage: "browse-tab") {
                self.app.tabBars.buttons.matching(self.exact(Labels.browse)).allElementsBoundByIndex.filter(self.visible)
            }
        }
        for step in 1...6 {
            if reachedLocation() { return }
            let before = navigationSnapshot()
            try tapUnique("observed Browse/Locations or Back action", stage: "locations-ancestor-\(step)") {
                let roots = self.navigationButtons(Labels.browse + Labels.locations)
                if !roots.isEmpty { return roots }
                return self.pickerBars().flatMap {
                    $0.buttons.matching(identifier: "DOC.navBarButton.backInHistory").allElementsBoundByIndex
                }.filter(self.visible)
            }
            try require("a changed Files directory or the requested provider location",
                        stage: "locations-ancestor-\(step)-did-not-progress", timeout: 15) {
                reachedLocation() || self.navigationSnapshot() != before
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

    private func displayed(_ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        return frame.width > 0 && frame.height > 0 && frame.midX.isFinite && frame.midY.isFinite &&
            app.frame.contains(CGPoint(x: frame.midX, y: frame.midY))
    }

    private func visible(_ element: XCUIElement) -> Bool {
        displayed(element) && element.isHittable
    }

    private func pickerBars() -> [XCUIElement] {
        app.navigationBars.matching(identifier: "FullDocumentManagerViewControllerNavigationBar")
            .allElementsBoundByIndex.filter(displayed)
    }

    private func navigationButtons(_ labels: [String]) -> [XCUIElement] {
        pickerBars().flatMap { $0.buttons.matching(exact(labels)).allElementsBoundByIndex }.filter(visible)
    }

    private func pickerDismissed() -> Bool {
        !NativeFilesBrowser.isPresented(in: app)
    }

    private func requirePicker() throws {
        guard NativeFilesBrowser.isPresented(in: app), pickerBars().count == 1 else {
            throw failure("One visible native document-manager navigation bar is required.", stage: "picker-scope-changed")
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
        guard displayed(candidate), !actionable || (candidate.isHittable && candidate.isEnabled) else {
            throw failure("The observed \(name) is not reachable and enabled.", stage: "disabled-" + stage)
        }
        return candidate
    }

    private func tapUnique(_ name: String, stage: String,
                           elements: @escaping () -> [XCUIElement]) throws {
        try requirePicker()
        capture("before-" + stage)
        let observed = try unique(name, stage: stage, elements: elements)
        harness.attach(describe(observed), name: "provider-files-" + stage + "-control")
        capture("ready-to-tap-" + stage)
        // Snapshot capture can change layout; resolve and validate the control again before tapping.
        try requirePicker()
        let current = try unique(name, stage: stage, elements: elements)
        current.tap()
    }

    private func navigationSnapshot() -> [String] {
        let locations = app.collectionViews.matching(identifier: "Browse View").allElementsBoundByIndex
            .filter(displayed).flatMap { $0.cells.allElementsBoundByIndex.filter(displayed).map(\.label) }
        return NativeFilesBrowser.directorySnapshot(in: app) + locations.sorted()
    }

    private func require(_ name: String, stage: String, timeout: TimeInterval,
                         condition: @escaping () -> Bool) throws {
        let appeared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: app)
        guard XCTWaiter.wait(for: [appeared], timeout: timeout) == .completed else {
            throw failure("The current picker did not expose \(name).", stage: stage)
        }
    }

    private func describe(_ element: XCUIElement) -> String {
        guard element.exists else { return "exists=false" }
        return "type=\(element.elementType.rawValue), label=\(element.label), identifier=\(element.identifier), frame=\(element.frame), enabled=\(element.isEnabled), hittable=\(displayed(element) && element.isHittable)"
    }

    private func capture(_ stage: String) {
        checkpoint += 1
        let name = "provider-files-\(checkpoint)-\(stage)"
        harness.screenshot(name)
        harness.attach(app.debugDescription, name: name + "-tree")
        let bars = pickerBars()
        let collections = ["Browse View", "File View"].flatMap {
            app.collectionViews.matching(identifier: $0).allElementsBoundByIndex.filter { $0.exists }
        }
        var census = ["selectorStatus=PENDING_RUNTIME", "headings=\(NativeFilesBrowser.directorySnapshot(in: app))"]
        for bar in bars {
            census.append("navigationBar: " + describe(bar))
            census += bar.buttons.allElementsBoundByIndex.map { "navigationButton: " + describe($0) }
        }
        for collection in collections {
            census.append("collection: " + describe(collection))
            census += collection.cells.allElementsBoundByIndex.map { "cell: " + describe($0) }
        }
        harness.attach(census.joined(separator: "\n"), name: name + "-selector-census")
    }

    private func failure(_ message: String, stage: String) -> Error {
        capture(stage)
        XCTFail(message)
        return NativeUIHarness.HarnessFailure.missingControl
    }
}
