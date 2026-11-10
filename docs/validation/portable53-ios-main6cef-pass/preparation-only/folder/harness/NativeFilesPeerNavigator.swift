import XCTest
import UIKit

/// Opens a run-owned fixture in another app's Files folder through the presented document picker.
/// The caller must verify the imported project or bytes after this native selection completes.
final class NativeFilesPeerNavigator {
    private unowned let harness: NativeUIHarness
    private let peerDisplayName: String
    private let runID: String
    private var checkpoint = 0
    private var app: XCUIApplication { harness.app }

    private enum Labels {
        static let browse = ["Browse", "Explorar"]
        static let locations = ["Locations", "Localizações"]
        static let onMyIPad = ["On My iPad", "No Meu iPad"]
        static let onMyIPhone = ["On My iPhone", "No Meu iPhone"]
        static let open = ["Open", "Abrir"]
        static let cancel = ["Cancel", "Cancelar", "Close", "Fechar"]
    }

    init(harness: NativeUIHarness, peerDisplayName: String, runID: String) {
        self.harness = harness
        self.peerDisplayName = peerDisplayName
        self.runID = runID
    }

    /// The shipping app has already opened its UIDocumentPicker. No file URL is opened directly.
    func open(filename: String) throws {
        guard !peerDisplayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              runID.range(of: "^[a-f0-9]{12}$", options: .regularExpression) != nil,
              !filename.isEmpty, filename != ".", filename != "..",
              URL(fileURLWithPath: filename).lastPathComponent == filename else {
            throw failure("Supply the peer's exact display name, a run identifier, and one fixture filename.", stage: "invalid-input")
        }
        capture("picker-presentation")
        guard NativeFilesBrowser.waitUntilLoaded(in: app) else {
            throw failure("The native document manager did not load its current directory.", stage: "picker-not-presented")
        }

        let local: [String]
        switch UIDevice.current.userInterfaceIdiom {
        case .pad: local = Labels.onMyIPad
        case .phone: local = Labels.onMyIPhone
        default: throw failure("Native peer Files navigation requires an iPhone or iPad.", stage: "unsupported-device")
        }
        try revealLocalLocation(local)
        if directoryHeadings(local).isEmpty {
            try tapEntry(local, step: "local-device")
        }
        try expectDirectory(local)
        try tapEntry([peerDisplayName], step: "peer-application-folder")
        try expectDirectory([peerDisplayName])
        try tapEntry(["LogisimE2E"], step: "e2e-folder")
        try expectDirectory(["LogisimE2E"])
        try tapEntry([runID], step: "run-folder")
        try expectDirectory([runID])

        // Files can hide extensions. A duplicate stem is ambiguous and must fail, never pick the first row.
        let stem = URL(fileURLWithPath: filename).deletingPathExtension().lastPathComponent
        let filenameLabels = stem == filename ? [filename] : [filename, stem]
        harness.attach("peer=\(peerDisplayName), run=\(runID), requested filename=\(filename), visible aliases=\(filenameLabels)",
                       name: "files-peer-requested-document")
        try tapEntry(filenameLabels, step: "fixture-document")
        capture("after-document-selection")

        try require("picker dismissal or its observed Open action", stage: "selection-did-not-progress", timeout: 20) {
            self.pickerDismissed() || !self.nativeButtons(Labels.open).isEmpty
        }
        if !pickerDismissed() {
            let open = try unique("native Open action", stage: "open-action") { self.nativeButtons(Labels.open) }
            try require("an enabled native Open action", stage: "open-action-disabled", timeout: 10) {
                open.exists && open.isEnabled && self.visible(open)
            }
            harness.attach("type=\(open.elementType.rawValue), label=\(open.label), identifier=\(open.identifier)",
                           name: "files-peer-open-control")
            capture("ready-to-confirm-open")
            open.tap()
        }
        try require("the native document picker's dismissal", stage: "picker-remained-open", timeout: 25) {
            self.pickerDismissed()
        }
        capture("picker-dismissed")
    }

    private func exact(_ labels: [String]) -> NSPredicate { NSPredicate(format: "label IN %@", labels) }

    private func visible(_ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        return frame.width > 0 && frame.height > 0 && frame.midX.isFinite && frame.midY.isFinite &&
            app.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) && element.isHittable
    }

    private func nativeButtons(_ labels: [String]) -> [XCUIElement] {
        app.navigationBars.buttons.matching(exact(labels)).allElementsBoundByIndex.filter(visible)
    }

    private func entries(_ labels: [String], location: Bool = false) -> [XCUIElement] {
        location ? NativeFilesBrowser.locationRows(in: app, labels: labels) : NativeFilesBrowser.contentRows(in: app, labels: labels)
    }

    private func displayed(_ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        return frame.width > 0 && frame.height > 0 && frame.midX.isFinite && frame.midY.isFinite &&
            app.frame.contains(CGPoint(x: frame.midX, y: frame.midY))
    }

    private func pickerBars() -> [XCUIElement] {
        app.navigationBars.matching(identifier: "FullDocumentManagerViewControllerNavigationBar")
            .allElementsBoundByIndex.filter(displayed)
    }

    private func pickerActions() -> [XCUIElement] {
        app.buttons.matching(identifier: "DOCPicker.actionButton").allElementsBoundByIndex.filter(displayed)
    }

    private func directoryHeadings(_ labels: [String]) -> [XCUIElement] {
        NativeFilesBrowser.directoryHeadings(in: app, labels: labels)
    }

    private func pickerDismissed() -> Bool {
        !NativeFilesBrowser.isPresented(in: app)
    }

    private func unique(_ name: String, stage: String, elements: @escaping () -> [XCUIElement]) throws -> XCUIElement {
        try require(name, stage: "missing-" + stage, timeout: 15) { !elements().isEmpty }
        let candidates = elements()
        guard candidates.count == 1, let candidate = candidates.first else {
            throw failure("A unique observed control is required for \(name); found \(candidates.count).", stage: "ambiguous-" + stage)
        }
        return candidate
    }

    private func tapEntry(_ labels: [String], step: String) throws {
        capture("before-" + step)
        if step != "local-device" { try NativeFilesBrowser.revealEntry(in: app, labels: labels, harness: harness) }
        let row = try unique("Files entry \(labels)", stage: step) { self.entries(labels, location: step == "local-device") }
        guard row.isEnabled else { throw failure("The observed Files entry is disabled: \(labels).", stage: "disabled-" + step) }
        harness.attach("type=\(row.elementType.rawValue), label=\(row.label), identifier=\(row.identifier), frame=\(row.frame)",
                       name: "files-peer-" + step + "-control")
        capture("ready-to-open-" + step)
        row.tap()
    }

    private func revealLocalLocation(_ labels: [String]) throws {
        capture("before-local-device-navigation")
        if !directoryHeadings(labels).isEmpty || !entries(labels, location: true).isEmpty { return }
        let tabs = app.tabBars.buttons.matching(exact(Labels.browse)).allElementsBoundByIndex.filter(visible)
        if !tabs.isEmpty {
            let browse = try unique("Files Browse tab", stage: "browse-tab") {
                self.app.tabBars.buttons.matching(self.exact(Labels.browse)).allElementsBoundByIndex.filter(self.visible)
            }
            capture("ready-to-open-browse-tab")
            browse.tap()
        }
        // A later picker can reopen inside either app's run folder. The observed iPad
        // browser exposes a Back history action instead of a parent-titled button.
        let ancestors = [runID, "LogisimE2E", peerDisplayName, app.label] + labels + Labels.browse + Labels.locations
        for step in 1...6 {
            if !directoryHeadings(labels).isEmpty || !entries(labels, location: true).isEmpty { return }
            let ancestor = try unique("a known Files ancestor", stage: "ancestor-\(step)") {
                let predicate = NSCompoundPredicate(orPredicateWithSubpredicates: [
                    self.exact(ancestors), NSPredicate(format: "identifier == %@", "DOC.navBarButton.backInHistory"),
                ])
                return self.app.navigationBars.buttons.matching(predicate).allElementsBoundByIndex
                    .filter { self.visible($0) && $0.isEnabled }
            }
            guard ancestor.isEnabled else {
                throw failure("The observed Files ancestor is disabled: \(ancestor.label).", stage: "ancestor-\(step)-disabled")
            }
            let before = directorySnapshot()
            harness.attach("type=\(ancestor.elementType.rawValue), label=\(ancestor.label), identifier=\(ancestor.identifier), frame=\(ancestor.frame)",
                           name: "files-peer-ancestor-\(step)-control")
            capture("ready-to-open-ancestor-\(step)")
            ancestor.tap()
            try require("a changed Files directory heading or the local-device location", stage: "ancestor-\(step)-did-not-progress", timeout: 10) {
                let current = self.directorySnapshot()
                return !self.directoryHeadings(labels).isEmpty || !self.entries(labels, location: true).isEmpty ||
                    (!current.isEmpty && current != before)
            }
            capture("after-opening-ancestor-\(step)")
        }
        guard !directoryHeadings(labels).isEmpty || !entries(labels, location: true).isEmpty else {
            throw failure("The local-device location did not appear after six observed Files ancestor actions.", stage: "ancestor-limit-reached")
        }
    }

    private func directorySnapshot() -> [String] {
        NativeFilesBrowser.directorySnapshot(in: app)
    }

    private func expectDirectory(_ labels: [String]) throws {
        capture("after-opening-" + labels[0])
        _ = try unique("Files directory heading \(labels)", stage: "directory-" + labels[0]) { self.directoryHeadings(labels) }
    }

    private func require(_ name: String, stage: String, timeout: TimeInterval, condition: @escaping () -> Bool) throws {
        let appeared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: app)
        guard XCTWaiter.wait(for: [appeared], timeout: timeout) == .completed else {
            throw failure("The current native tree did not expose \(name).", stage: stage)
        }
    }

    private func capture(_ name: String) {
        checkpoint += 1
        let label = "files-peer-\(checkpoint)-\(name)"
        harness.screenshot(label)
        harness.attach(app.debugDescription, name: label + "-tree")
    }

    private func failure(_ message: String, stage: String) -> Error {
        capture(stage)
        XCTFail(message)
        return NativeUIHarness.HarnessFailure.missingControl
    }
}
