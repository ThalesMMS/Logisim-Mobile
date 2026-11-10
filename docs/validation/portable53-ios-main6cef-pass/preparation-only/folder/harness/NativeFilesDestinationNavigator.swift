import XCTest
import UIKit

/// Observed directory identity shared by export, peer-document and portable-source pickers.
enum NativeFilesBrowser {
    static func directoryHeadings(in app: XCUIApplication, labels: [String]) -> [XCUIElement] {
        let bars = app.navigationBars.matching(identifier: "FullDocumentManagerViewControllerNavigationBar")
            .allElementsBoundByIndex.filter { $0.exists }
        let titles = NSCompoundPredicate(orPredicateWithSubpredicates: labels.map {
            NSPredicate(format: "label == %@ OR label BEGINSWITH %@", $0, $0 + ", ")
        })
        return bars.flatMap { bar in
            bar.staticTexts.matching(titles).allElementsBoundByIndex +
                bar.buttons.matching(titles).allElementsBoundByIndex.filter { $0.identifier.isEmpty }
        }.filter { displayed($0, in: app) }
    }

    static func directorySnapshot(in app: XCUIApplication) -> [String] {
        app.navigationBars.matching(identifier: "FullDocumentManagerViewControllerNavigationBar")
            .allElementsBoundByIndex.filter { $0.exists }.flatMap { bar in
                bar.staticTexts.matching(NSPredicate(format: "label != %@", "")).allElementsBoundByIndex +
                    bar.buttons.matching(NSPredicate(format: "label CONTAINS %@", ", ")).allElementsBoundByIndex
                        .filter { $0.identifier.isEmpty }
            }.filter { displayed($0, in: app) }.map(\.label).sorted()
    }

    static func waitUntilLoaded(in app: XCUIApplication, timeout: TimeInterval = 30) -> Bool {
        let manager = app.navigationBars.matching(identifier: "FullDocumentManagerViewControllerNavigationBar").firstMatch
        let title = NSPredicate(format:
            "(elementType == %@ AND label != %@) OR (elementType == %@ AND label CONTAINS %@ AND (identifier == %@ OR identifier == NIL))",
            NSNumber(value: XCUIElement.ElementType.staticText.rawValue), "",
            NSNumber(value: XCUIElement.ElementType.button.rawValue), ", ", "")
        return manager.descendants(matching: .any).matching(title).firstMatch.waitForExistence(timeout: timeout)
    }

    static func isPresented(in app: XCUIApplication) -> Bool {
        app.navigationBars.matching(identifier: "FullDocumentManagerViewControllerNavigationBar").firstMatch.exists ||
            app.buttons.matching(identifier: "DOCPicker.actionButton").firstMatch.exists
    }

    static func contentRows(in app: XCUIApplication, labels: [String]) -> [XCUIElement] {
        rows(in: app, collectionIdentifier: "File View", labels: labels)
    }

    static func locationRows(in app: XCUIApplication, labels: [String]) -> [XCUIElement] {
        rows(in: app, collectionIdentifier: "Browse View", labels: labels)
    }

    private static func collection(in app: XCUIApplication, identifier: String) -> XCUIElement? {
        let views = app.collectionViews.matching(identifier: identifier).allElementsBoundByIndex.filter { $0.exists }
        guard views.count <= 1 else {
            XCTFail("The native Files role must have one collection: \(identifier); found \(views.count)")
            return nil
        }
        return views.first
    }

    private static func rows(in app: XCUIApplication, collectionIdentifier: String, labels: [String]) -> [XCUIElement] {
        guard let view = collection(in: app, identifier: collectionIdentifier) else { return [] }
        let predicate = NSCompoundPredicate(orPredicateWithSubpredicates: labels.map {
            NSPredicate(format: "label == %@ OR label BEGINSWITH %@", $0, $0 + ", ")
        })
        return view.cells.matching(predicate).allElementsBoundByIndex.filter { displayed($0, in: app) && $0.isHittable }
    }

    static func revealEntry(in app: XCUIApplication, labels: [String], harness: NativeUIHarness) throws {
        func targetVisible() -> Bool {
            !contentRows(in: app, labels: labels).isEmpty
        }
        if targetVisible() { return }
        let loaded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard let view = collection(in: app, identifier: "File View") else { return false }
            return view.cells.count > 0
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 15), .completed,
                       "The native Files collection must load before scrolling for a fixture")
        guard let view = collection(in: app, identifier: "File View") else { throw NativeUIHarness.HarnessFailure.missingControl }
        for forward in [true, false] {
            for index in 0..<6 {
                if targetVisible() { return }
                harness.attach("labels=\(labels), direction=\(forward ? "up" : "down"), step=\(index + 1), frame=\(view.frame)",
                               name: "files-observed-collection-scroll")
                if forward { view.swipeUp(velocity: .slow) } else { view.swipeDown(velocity: .slow) }
            }
        }
    }

    private static func displayed(_ title: XCUIElement, in app: XCUIApplication) -> Bool {
        guard title.exists else { return false }
        let frame = title.frame
        return frame.width > 0 && frame.height > 0 && frame.midX.isFinite && frame.midY.isFinite &&
            app.frame.contains(CGPoint(x: frame.midX, y: frame.midY))
    }
}

/// Optional Files navigation shared by document and visual export cases. No app hook or file write is used.
/// This selector contract must be checked against the final iOS accessibility tree before admission.
final class NativeFilesDestinationNavigator {
    private unowned let harness: NativeUIHarness
    private let documents: URL
    private let runID: String
    private var checkpoint = 0
    private var app: XCUIApplication { harness.app }

    private enum Labels {
        static let browse = ["Browse", "Explorar"]
        static let locations = ["Locations", "Localizações"]
        static let onMyIPad = ["On My iPad", "No Meu iPad"]
        static let onMyIPhone = ["On My iPhone", "No Meu iPhone"]
        static let commit = ["Export", "Exportar", "Move", "Mover", "Save", "Salvar"]
        static let cancel = ["Cancel", "Cancelar", "Close", "Fechar"]
    }

    init(harness: NativeUIHarness, documentsDirectory: URL, runID: String) {
        self.harness = harness
        documents = documentsDirectory.standardizedFileURL
        self.runID = runID
    }

    /// A caller has already opened UIDocumentPicker. This method confirms its actual destination selection.
    /// The caller must read and verify the returned file; completion alone is not evidence of valid output.
    func completeExport(expectedFilename: String) throws -> URL {
        XCTAssertNotNil(runID.range(of: "^[a-f0-9]{12}$", options: .regularExpression))
        XCTAssertEqual(documents.lastPathComponent, runID)
        XCTAssertEqual(documents.deletingLastPathComponent().lastPathComponent, "LogisimE2E")
        XCTAssertEqual(documents.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent, "Documents")
        let destination = documents.appendingPathComponent(expectedFilename)
        XCTAssertEqual(destination.lastPathComponent, expectedFilename)
        XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path), "A new destination is required; never overwrite prior evidence")
        capture("native-picker-presentation")
        XCTAssertTrue(NativeFilesBrowser.waitUntilLoaded(in: app),
                      "The native document manager must load its current directory before navigation")

        let local: [String]
        switch UIDevice.current.userInterfaceIdiom {
        case .pad: local = Labels.onMyIPad
        case .phone: local = Labels.onMyIPhone
        default: throw failure("Native Files destination requires an iPhone or iPad")
        }
        try revealLocalLocation(local)
        if directoryHeadings(local).isEmpty {
            try tapDestination(local, step: "local-device")
        }
        try expectDirectory(local)
        // Read the shipping app's native display name. Do not freeze the old product stem LogisimMobile.
        let displayName = app.label
        XCTAssertFalse(displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        harness.attach(displayName, name: "files-observed-application-display-name")
        try tapDestination([displayName], step: "application-folder")
        try expectDirectory([displayName])
        try tapDestination(["LogisimE2E"], step: "e2e-folder")
        try expectDirectory(["LogisimE2E"])
        try tapDestination([runID], step: "run-folder")
        try expectDirectory([runID])
        XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
        capture("destination-before-confirmation")
        let commit = try unique("native Export/Move/Save") { self.nativeButtons(Labels.commit) }
        XCTAssertTrue(commit.isEnabled)
        harness.attach("label=\(commit.label), app=\(displayName), destination=\(destination.path)", name: "files-selected-destination")
        // Filename-field roles and extension visibility are not assumed. The caller checks the actual default filename on disk.
        let filenameFields = app.textFields.allElementsBoundByIndex.filter { $0.exists }.map {
            "label=\($0.label), value=\($0.value ?? "nil")"
        }.joined(separator: "\n")
        harness.attach(filenameFields, name: "files-observed-filename-fields")
        capture("ready-to-confirm-export")
        commit.tap()
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            !NativeFilesBrowser.isPresented(in: self.app)
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 25), .completed,
                       "The actual Files controller must disappear; a canvas behind it is insufficient")
        capture("native-picker-dismissed")
        return destination
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

    private func destinations(_ labels: [String], location: Bool = false) -> [XCUIElement] {
        location ? NativeFilesBrowser.locationRows(in: app, labels: labels) : NativeFilesBrowser.contentRows(in: app, labels: labels)
    }

    private func unique(_ name: String, elements: @escaping () -> [XCUIElement]) throws -> XCUIElement {
        let appeared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !elements().isEmpty }, object: app)
        let result = XCTWaiter.wait(for: [appeared], timeout: 15)
        if result != .completed { capture("missing-" + name) }
        XCTAssertEqual(result, .completed, "The current native tree must expose \(name)")
        let candidates = elements()
        if candidates.count != 1 { capture("ambiguous-" + name) }
        XCTAssertEqual(candidates.count, 1, "A unique observed control is required for \(name)")
        return try XCTUnwrap(candidates.count == 1 ? candidates[0] : nil)
    }

    private func tapDestination(_ labels: [String], step: String) throws {
        capture("before-" + step)
        if step != "local-device" { try NativeFilesBrowser.revealEntry(in: app, labels: labels, harness: harness) }
        let row = try unique("Files destination \(labels)") { self.destinations(labels, location: step == "local-device") }
        XCTAssertTrue(row.isEnabled)
        harness.attach("type=\(row.elementType.rawValue), label=\(row.label), identifier=\(row.identifier)", name: "files-" + step + "-control")
        capture("ready-to-open-" + step)
        row.tap()
    }

    private func revealLocalLocation(_ labels: [String]) throws {
        capture("before-local-device-navigation")
        if !directoryHeadings(labels).isEmpty || !destinations(labels, location: true).isEmpty { return }
        let tabs = app.tabBars.buttons.matching(exact(Labels.browse)).allElementsBoundByIndex.filter(visible)
        if !tabs.isEmpty {
            let browse = try unique("Files Browse tab") {
                self.app.tabBars.buttons.matching(self.exact(Labels.browse)).allElementsBoundByIndex.filter(self.visible)
            }
            capture("ready-to-open-browse-tab")
            browse.tap()
        }
        if !destinations(labels, location: true).isEmpty { return }
        let root = try unique("Files Browse/Locations navigation action") {
            self.nativeButtons(Labels.browse + Labels.locations)
        }
        capture("ready-to-open-locations")
        root.tap()
        _ = try unique("local-device location") { self.destinations(labels, location: true) }
    }

    private func directoryHeadings(_ labels: [String]) -> [XCUIElement] {
        NativeFilesBrowser.directoryHeadings(in: app, labels: labels)
    }

    private func expectDirectory(_ labels: [String]) throws {
        capture("after-opening-" + labels[0])
        _ = try unique("Files directory heading \(labels)") { self.directoryHeadings(labels) }
    }

    private func capture(_ name: String) {
        checkpoint += 1
        let label = "files-destination-\(checkpoint)-\(name)"
        harness.screenshot(label)
        harness.attach(app.debugDescription, name: label + "-tree")
    }

    private func failure(_ message: String) -> Error {
        capture("unsupported-native-ui")
        XCTFail(message)
        return NativeUIHarness.HarnessFailure.missingControl
    }
}
