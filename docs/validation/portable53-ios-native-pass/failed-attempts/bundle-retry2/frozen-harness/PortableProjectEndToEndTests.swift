import Foundation
import XCTest
import UIKit
import CryptoKit

/// Offline-prepared native scenarios. Admission requires observing the source-picker controls on the target OS.
/// All editor operations use production UI; Foundation only reads committed storage and archives owned evidence.
final class PortableProjectEndToEndTests: NativeUIHarness {
    private struct RunConfiguration: Decodable {
        let run_id: String
        let documents: String
        let workspace: String
        let fixtures: [String: String]
        let visualExport: [String: String]
    }

    private var run: RunConfiguration!
    private var evidenceRoot: URL?
    private var receipt: [String: Any] = [:]
    private var evidenceFiles: [String] = []
    private var sequence = 0

    override func setUpWithError() throws {
        try super.setUpWithError()
        let file = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        run = try JSONDecoder().decode(RunConfiguration.self, from: Data(contentsOf: file))
        try require(run.run_id.range(of: "^[a-f0-9]{12}$", options: .regularExpression) != nil, "Run ID is invalid")
        try require(documents.lastPathComponent == run.run_id && documents.deletingLastPathComponent().lastPathComponent == "LogisimE2E" &&
                    documents.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent == "Documents",
                    "Run.documents must identify the runner-owned Documents/LogisimE2E/run-id folder")
        let derivedWorkspace = documents.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Library/Application Support/LogisimMobile/workspace", isDirectory: true).standardizedFileURL
        try require(workspace == derivedWorkspace, "Run.workspace does not belong to the supplied app container")
    }

    override func tearDownWithError() throws {
        if evidenceRoot != nil {
            if receipt["status"] as? String != "PASSED" || (testRun?.failureCount ?? 0) > 0 {
                receipt["status"] = "FAILED"
            }
            try? persistReceipt()
        }
        try super.tearDownWithError()
    }

    func testBundleImportExportsAndRestoresItsSourceArchive() throws { try roundtrip(mode: "bundle") }
    func testFolderImportExportsAndRestoresItsSourceArchive() throws { try roundtrip(mode: "folder") }

    private var documents: URL { URL(fileURLWithPath: run.documents, isDirectory: true).standardizedFileURL }
    private var workspace: URL { URL(fileURLWithPath: run.workspace, isDirectory: true).standardizedFileURL }

    private func roundtrip(mode: String) throws {
        let root = documents.appendingPathComponent("portable-\(mode)-evidence", isDirectory: true)
        try require(!FileManager.default.fileExists(atPath: root.path), "Portable evidence directory already exists")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        evidenceRoot = root
        let artifactHash = try XCTUnwrap(run.visualExport["buildSHA256"], "Run must supply its actual bundle-tree fingerprint")
        try require(artifactHash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil, "App artifact fingerprint is invalid")
        receipt = ["schema": 1, "fixture": "portable53-v1", "platform": "ios", "mode": mode,
                   "status": "RUNNING", "executedViaUi": true, "device": UIDevice.current.model + " / " + UIDevice.current.systemVersion,
                   "appArtifactSha256": artifactHash, "sourcePin": run.visualExport["sourcePin"] ?? "",
                   "exports": [[String: Any]](), "evidence": [String](),
                   "workspaceSnapshots": [String: String](), "catalogProjectIds": [String: [String]](),
                   "notes": "Native UI and committed storage observations. Desktop/mobile source-graph verification must consume both actual archived app exports separately."]
        try persistReceipt()
        do {
            orient(.portrait)
            record("before-portable-import")
            let before = try snapshot("before", portable: false)
            let input = try fixture(mode == "bundle" ? "portableBundle" : "portableSources")
            let sourceHashes = try inputHashes(input, mode: mode)
            try writeJSON(sourceHashes, name: "input-files-before.json")
            if mode == "bundle" {
                try require(sourceHashes["portable53.lsebdl"] == "74659456e95e402100bc14de0db7735c3672d091c6bf2d021126d8f72ca5c0bc",
                            "The staged bundle does not match portable53-v1")
            }
            try openPortableTool()
            let sourcePicker = PortableSourcePicker(harness: self, documents: documents, record: record)
            if mode == "bundle" {
                try tapPortable("portable_open_bundle")
                try sourcePicker.selectFile(input)
            } else {
                try tapPortable("portable_open_directory")
                try sourcePicker.selectDirectory(input)
                // PortableProjectTools renders each granted relative file.path as its actual button label.
                try tapLiteral("main.circ", region: "editor-tool-sheet")
            }
            let imported = try waitForFreshSnapshot("imported", excluding: before.catalogIDs)
            let importedID = try XCTUnwrap(imported.activeID)
            try require(!before.catalogIDs.contains(importedID), "The committed import snapshot reused an earlier workspace identity")
            let importedWindow = try imported.uniqueWindow()
            try require(imported.catalogIDs.isSuperset(of: before.catalogIDs), "Import removed an earlier catalog identity")
            for (window, project) in before.windows where window != importedWindow {
                try require(imported.windows[window] == project, "Import changed another window binding")
            }
            try expectPortableCanvas()
            try assertNoEditableReference()
            if mode == "bundle" {
                try require(imported.source == Data(contentsOf: input), "Imported source sidecar differs from the selected bundle")
            }
            try require(try inputHashes(input, mode: mode) == sourceHashes, "Native import changed its original sources")
            receipt["importedProjectId"] = importedID
            receipt["observedWindowBeforeRestart"] = importedWindow
            try persistReceipt()

            let first = try exportArchive(stage: "before", afterRestart: false)
            record("before-process-termination")
            let previousProcess = try observedProcessID()
            app.terminate()
            try require(app.state == .notRunning, "Process termination was not observed")
            receipt["restart"] = ["beforeProcessId": previousProcess, "terminationObserved": "notRunning"]
            try persistReceipt()
            app.launch()
            try require(app.wait(for: .runningForeground, timeout: 20), "Relaunch did not foreground the installed app")
            try closeImportReportIfPresented()
            try expectPortableCanvas()
            let restartedProcess = try observedProcessID()
            try require(restartedProcess != previousProcess, "Relaunch reused the previous observed process ID")
            receipt["restart"] = ["beforeProcessId": previousProcess, "afterProcessId": restartedProcess,
                                  "terminationObserved": "notRunning", "relaunchObserved": "runningForeground"]
            record("new-process-foreground-observed")
            let restarted = try snapshot("restarted", portable: true)
            try require(restarted.activeID == importedID, "Restart restored another workspace")
            try require(restarted.catalogIDs.isSuperset(of: imported.catalogIDs), "Restart removed a catalog identity")
            try require(restarted.windows[importedWindow] == importedID, "The observed window did not restore its portable workspace")
            try require(restarted.descriptor == imported.descriptor && restarted.source == imported.source,
                        "Restart changed the source-archive descriptor or bytes")
            try require(restarted.projectIdentity == imported.projectIdentity, "Restart changed circuit/component identities")
            receipt["restartedProjectId"] = importedID
            receipt["observedWindowAfterRestart"] = importedWindow
            try persistReceipt()
            let second = try exportArchive(stage: "after", afterRestart: true)
            try require(first.standardizedFileURL != second.standardizedFileURL, "Exports must be distinct evidence files")
            let selected = try Data(contentsOf: second)

            try openPortableTool()
            try tapPortable("portable_open_bundle")
            try sourcePicker.selectFile(second)
            let priorIDs = before.catalogIDs.union(imported.catalogIDs).union(restarted.catalogIDs)
            let reopened = try waitForFreshSnapshot("reopened", excluding: priorIDs)
            let reopenedID = try XCTUnwrap(reopened.activeID)
            try require(!priorIDs.contains(reopenedID), "The committed reopen snapshot reused an earlier workspace identity")
            try require(reopened.catalogIDs.isSuperset(of: priorIDs), "Reopening removed an earlier catalog identity")
            try require(reopened.windows[importedWindow] == reopenedID, "Reopening did not replace the observed window binding")
            try require(reopened.projectIdentity == restarted.projectIdentity, "Reopening changed definition or component identities")
            try require(reopened.source == selected, "Reopened source sidecar is not the actual selected app export")
            try expectPortableCanvas()
            try assertNoEditableReference()
            try require(try inputHashes(input, mode: mode) == sourceHashes, "Export/reopen changed the original fixture sources")
            try writeJSON(try inputHashes(input, mode: mode), name: "input-files-after.json")
            receipt["reopenedProjectId"] = reopenedID
            receipt["status"] = "PASSED"
            record("portable-roundtrip-completed")
            try persistReceipt()
        } catch {
            receipt["status"] = "FAILED"
            receipt["error"] = String(describing: error)
            record("portable-roundtrip-failed")
            try? persistReceipt()
            throw error
        }
    }

    private func observedProcessID() throws -> Int {
        try PortableWorkspaceEvidence.processID(inDiagnostics: app.debugDescription)
    }

    private func fixture(_ key: String) throws -> URL {
        let url = URL(fileURLWithPath: try XCTUnwrap(run.fixtures[key])).standardizedFileURL
        try require(url.path.hasPrefix(documents.path + "/"), "Portable fixture is outside this run")
        return url
    }

    private func inputHashes(_ url: URL, mode: String) throws -> [String: String] {
        if mode == "bundle" { return [url.lastPathComponent: portableSHA(try Data(contentsOf: url))] }
        var result: [String: String] = [:]
        for path in ["main.circ", "library/bridge.circ", "library/cell.circ"] {
            result[path] = portableSHA(try Data(contentsOf: url.appendingPathComponent(path)))
        }
        return result
    }

    private func openPortableTool() throws {
        if identified("editor-tool-sheet").exists { try tapKey("action_close") }
        try tapKey("a11y_project_tools", toolbar: "editor-project-toolbar")
        try reveal(values("portable_title"), verticalRegion: "editor-tool-menu").tap()
        try require(matching(try values("portable_open_bundle")).firstMatch.waitForExistence(timeout: 10),
                    "The mounted Portable projects panel did not appear")
        record("portable-panel-opened")
    }

    private func tapPortable(_ key: String) throws {
        try tapLiteral(values(key), region: "editor-tool-sheet")
    }

    private func tapLiteral(_ label: String, region: String) throws { try tapLiteral([label], region: region) }
    private func tapLiteral(_ labels: [String], region: String) throws {
        let predicates = labels.map { label in
            NSCompoundPredicate(orPredicateWithSubpredicates: [
                NSPredicate(format: "label == %@", label),
                NSPredicate(format: "label BEGINSWITH %@", label + ", "),
            ])
        }
        let candidates = app.buttons.matching(NSCompoundPredicate(orPredicateWithSubpredicates: predicates))
        func visibleButtons() -> [XCUIElement] {
            candidates.allElementsBoundByIndex.filter { button in
                guard button.exists else { return false }
                let frame = button.frame
                guard frame.width > 0, frame.height > 0, frame.midX.isFinite, frame.midY.isFinite,
                      app.frame.contains(frame) else { return false }
                return button.isHittable
            }
        }
        // Beta's captured sheet identifier is an empty sibling with an incorrect
        // frame. A unique hittable button needs no scroll through that frame.
        var buttons = visibleButtons()
        if buttons.isEmpty {
            let container = identified(region)
            try require(container.exists && matching(labels, within: container).count > 0,
                        "The nonvisible portable action is not owned by the identified scroll region: \(labels)")
            _ = try reveal(labels, verticalRegion: region)
            buttons = visibleButtons()
        }
        try require(buttons.count == 1, "A unique enabled portable action is required for \(labels)")
        let button = try XCTUnwrap(buttons.first)
        try require(button.isEnabled, "Portable action is disabled: \(labels)")
        button.tap()
    }

    private func expectPortableCanvas() throws {
        if identified("editor-tool-sheet").exists { try tapKey("action_close") }
        try closeImportReportIfPresented()
        let deadline = Date().addingTimeInterval(30)
        var observed = ""
        while Date() < deadline {
            observed = (try? canvasDescription()) ?? ""
            let ready = [("OUT_FIRST53", "00001000"), ("OUT_SECOND53", "00000011")].allSatisfy { label, bits in
                let pattern = NSRegularExpression.escapedPattern(for: label) + ": " + bits + "(?=,|\\.|$)"
                return observed.range(of: pattern, options: .regularExpression) != nil
            }
            if ready {
                for id in ["instance-first53", "instance-second53"] {
                    try require(identified("canvas-component:" + id).exists, "The canvas did not expose distinct instance \(id)")
                }
                record("independent-portable-outputs-observed")
                return
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        throw PortableHarnessError("The actual canvas never showed independent outputs 8 and 3: \(observed)")
    }

    private func assertNoEditableReference() throws {
        try tapKey("a11y_project_files", toolbar: "editor-project-toolbar")
        let save = try reveal(values("action_save_file"))
        try require(!save.isEnabled, "A portable import copy inherited an editable native file reference")
        try tapKey("action_close")
    }

    private func exportArchive(stage: String, afterRestart: Bool) throws -> URL {
        try openPortableTool()
        try tapPortable("portable_export")
        record("\(stage)-native-export-picker")
        let native = NativeFilesDestinationNavigator(harness: self, documentsDirectory: documents, runID: run.run_id)
        let destination = try native.completeExport(expectedFilename: "Top53.lsebdl")
        let deadline = Date().addingTimeInterval(25)
        var bytes: Data?
        while Date() < deadline {
            if let data = try? Data(contentsOf: destination), data.count >= 22,
               Array(data.prefix(4)) == [0x50, 0x4b, 0x03, 0x04],
               Array(data.suffix(22).prefix(4)) == [0x50, 0x4b, 0x05, 0x06] {
                RunLoop.current.run(until: Date().addingTimeInterval(0.2))
                if (try? Data(contentsOf: destination)) == data { bytes = data; break }
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        let content = try XCTUnwrap(bytes, "Files did not finish writing a complete portable ZIP archive")
        try require(matching(try values("portable_saved")).firstMatch.waitForExistence(timeout: 15), "The mounted tool did not confirm native export completion")
        let archived = try evidenceURL("export-\(stage)-restart.lsebdl")
        try require(!FileManager.default.fileExists(atPath: archived.path), "Never overwrite a prior app-export artifact")
        // Archive the whole file that Files wrote. Do not reencode a ZIP, overwrite a fixture or fabricate export bytes.
        try FileManager.default.moveItem(at: destination, to: archived)
        try require(!FileManager.default.fileExists(atPath: destination.path), "The completed export was not moved into evidence")
        try require(try Data(contentsOf: archived) == content, "Evidence archival changed the app-exported bytes")
        var exports = receipt["exports"] as? [[String: Any]] ?? []
        exports.append(["path": archived.lastPathComponent, "sha256": portableSHA(content), "afterRestart": afterRestart,
                        "nativeDestination": destination.path, "bytes": content.count])
        receipt["exports"] = exports
        evidenceFiles.append(archived.lastPathComponent)
        let attachment = XCTAttachment(data: content, uniformTypeIdentifier: "public.zip-archive")
        attachment.name = archived.lastPathComponent; attachment.lifetime = .keepAlways; add(attachment)
        record("\(stage)-app-export-archived")
        try tapKey("action_close")
        try persistReceipt()
        return archived
    }

    private func waitForFreshSnapshot(_ name: String, excluding existing: Set<String>) throws -> PortableWorkspaceSnapshot {
        let deadline = Date().addingTimeInterval(30)
        var last = "No committed portable project"
        while Date() < deadline {
            do {
                let state = try PortableWorkspaceEvidence(workspaceURL: workspace).readCurrent(requirePortableSource: true)
                if let id = state.activeID, !existing.contains(id) {
                    _ = try state.uniqueWindow()
                    return try snapshot(name, portable: true)
                }
                last = "The active catalog identity still belongs to an earlier workspace"
            } catch { last = String(describing: error) }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        throw PortableHarnessError("Portable copy did not commit a fresh workspace: \(last)")
    }

    private func snapshot(_ name: String, portable: Bool) throws -> PortableWorkspaceSnapshot {
        let final = try evidenceURL("snapshots/\(name)/workspace")
        try require(!FileManager.default.fileExists(atPath: final.path), "Never replace a prior workspace snapshot")
        let state = try PortableWorkspaceEvidence(workspaceURL: workspace)
            .capture(to: final, requirePortableSource: portable)
        var paths = receipt["workspaceSnapshots"] as? [String: String] ?? [:]
        paths[name] = "snapshots/\(name)/workspace"; receipt["workspaceSnapshots"] = paths
        var ids = receipt["catalogProjectIds"] as? [String: [String]] ?? [:]
        ids[name] = state.catalogIDs.sorted(); receipt["catalogProjectIds"] = ids
        try writeJSON(state.summary, name: "snapshots/\(name)/observation.json")
        if let source = state.source { try source.write(to: evidenceURL("snapshots/\(name)/source.lsebdl")) }
        record("workspace-\(name)-committed")
        try persistReceipt()
        return state
    }

    private func evidenceURL(_ relative: String) throws -> URL {
        let root = try XCTUnwrap(evidenceRoot)
        let url = root.appendingPathComponent(relative).standardizedFileURL
        try require(url.path.hasPrefix(root.path + "/"), "Evidence path escapes this run")
        return url
    }

    private func writeJSON(_ value: Any, name: String) throws {
        let url = try evidenceURL(name)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys]).write(to: url, options: .atomic)
        if !evidenceFiles.contains(name) { evidenceFiles.append(name) }
    }

    private func persistReceipt() throws {
        receipt["evidence"] = evidenceFiles
        receipt["updatedAt"] = ISO8601DateFormatter().string(from: Date())
        let url = try evidenceURL("ui-receipt.json")
        try JSONSerialization.data(withJSONObject: receipt, options: [.prettyPrinted, .sortedKeys]).write(to: url, options: .atomic)
    }

    private func record(_ name: String) {
        sequence += 1
        let stem = String(format: "evidence/%03d-%@", sequence, name)
        screenshot("portable-" + name)
        let tree = app.debugDescription
        attach(tree, name: "portable-" + name + "-tree")
        do {
            let png = try evidenceURL(stem + ".png")
            try FileManager.default.createDirectory(at: png.deletingLastPathComponent(), withIntermediateDirectories: true)
            try XCUIScreen.main.screenshot().pngRepresentation.write(to: png)
            try tree.write(to: evidenceURL(stem + ".txt"), atomically: true, encoding: .utf8)
            evidenceFiles += [stem + ".png", stem + ".txt"]
            try persistReceipt()
        } catch { XCTFail("Could not retain portable native evidence: \(error)") }
    }

    private func require(_ condition: @autoclosure () throws -> Bool, _ message: String) throws {
        if try !condition() { throw PortableHarnessError(message) }
    }
}

private struct PortableHarnessError: Error, CustomStringConvertible {
    let description: String
    init(_ message: String) { description = message }
}

private func portableSHA(_ content: Data) -> String { SHA256.hash(data: content).map { String(format: "%02x", $0) }.joined() }

/// Uses only observed Files rows and documented Open/Abrir folder commands. Additional permission UI fails for owner review.
private final class PortableSourcePicker {
    private unowned let harness: NativeUIHarness
    private let documents: URL
    private let record: (String) -> Void
    private var app: XCUIApplication { harness.app }
    init(harness: NativeUIHarness, documents: URL, record: @escaping (String) -> Void) {
        self.harness = harness; self.documents = documents; self.record = record
    }
    func selectFile(_ file: URL) throws {
        let path = try relativeComponents(file)
        try openRunFolder()
        for component in path.dropLast() { try openDirectory(component) }
        try tapRow([file.lastPathComponent, file.deletingPathExtension().lastPathComponent], name: "portable-file")
        try waitForDismissal()
    }
    func selectDirectory(_ directory: URL) throws {
        let path = try relativeComponents(directory)
        try openRunFolder()
        for component in path { try openDirectory(component) }
        let confirm = try unique("native folder Open/Abrir") {
            self.app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@", ["Open", "Abrir"]))
                .allElementsBoundByIndex.filter(self.visible)
        }
        guard confirm.isEnabled else { throw PortableHarnessError("The observed folder Open action is disabled") }
        record("portable-folder-before-confirmation"); confirm.tap(); try waitForDismissal()
    }
    private func relativeComponents(_ url: URL) throws -> [String] {
        let standardized = url.standardizedFileURL
        guard standardized.path.hasPrefix(documents.path + "/") else { throw PortableHarnessError("Source picker target is outside this run") }
        return Array(standardized.pathComponents.dropFirst(documents.pathComponents.count))
    }
    private func openRunFolder() throws {
        record("portable-source-picker-presented")
        guard NativeFilesBrowser.waitUntilLoaded(in: app) else {
            record("source-picker-directory-not-loaded")
            throw PortableHarnessError("The native source document manager did not load its current directory")
        }
        let local: [String]
        switch UIDevice.current.userInterfaceIdiom {
        case .pad: local = ["On My iPad", "No Meu iPad"]
        case .phone: local = ["On My iPhone", "No Meu iPhone"]
        default: throw PortableHarnessError("Portable Files selection requires an iPhone or iPad")
        }
        let browseLabels = ["Browse", "Explorar"]
        let rootLabels = browseLabels + ["Locations", "Localizações"]
        if NativeFilesBrowser.directoryHeadings(in: app, labels: local).isEmpty {
            let navigationReady = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
                !NativeFilesBrowser.directoryHeadings(in: self.app, labels: local).isEmpty || !self.rows(local).isEmpty ||
                    !self.app.tabBars.buttons.matching(NSPredicate(format: "label IN %@", browseLabels)).allElementsBoundByIndex.filter(self.visible).isEmpty ||
                    !self.app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@", rootLabels)).allElementsBoundByIndex.filter(self.visible).isEmpty
            }, object: app)
            guard XCTWaiter.wait(for: [navigationReady], timeout: 15) == .completed else {
                record("source-picker-no-navigation")
                throw PortableHarnessError("The native source picker did not present an observed local location or Browse/Locations control")
            }
            if NativeFilesBrowser.directoryHeadings(in: app, labels: local).isEmpty && rows(local).isEmpty {
                let browse = app.tabBars.buttons.matching(NSPredicate(format: "label IN %@", browseLabels))
                    .allElementsBoundByIndex.filter(visible)
                if browse.count == 1 { browse[0].tap() }
            }
            if NativeFilesBrowser.directoryHeadings(in: app, labels: local).isEmpty && rows(local).isEmpty {
                let rootReady = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
                    !NativeFilesBrowser.directoryHeadings(in: self.app, labels: local).isEmpty || !self.rows(local).isEmpty ||
                        !self.app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@", rootLabels)).allElementsBoundByIndex.filter(self.visible).isEmpty
                }, object: app)
                guard XCTWaiter.wait(for: [rootReady], timeout: 15) == .completed else {
                    record("source-picker-root-not-presented")
                    throw PortableHarnessError("Files did not finish presenting its local location or root navigation action")
                }
                if NativeFilesBrowser.directoryHeadings(in: app, labels: local).isEmpty && rows(local).isEmpty {
                    let locations = try unique("Files Browse/Locations") {
                        self.app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@", rootLabels))
                            .allElementsBoundByIndex.filter(self.visible)
                    }
                    locations.tap()
                }
            }
            if NativeFilesBrowser.directoryHeadings(in: app, labels: local).isEmpty {
                try tapRow(local, name: "local-device")
            }
        }
        try expectDirectory(local)
        let displayName = app.label
        guard !displayName.isEmpty else { throw PortableHarnessError("The observed application has no display name") }
        try openDirectory(displayName); try openDirectory("LogisimE2E"); try openDirectory(documents.lastPathComponent)
    }
    private func openDirectory(_ name: String) throws { try tapRow([name], name: name); try expectDirectory([name]) }
    private func expectDirectory(_ labels: [String]) throws {
        _ = try unique("Files directory \(labels)") {
            NativeFilesBrowser.directoryHeadings(in: self.app, labels: labels)
        }
    }
    private func rows(_ labels: [String]) -> [XCUIElement] {
        let canonical = NSCompoundPredicate(orPredicateWithSubpredicates: labels.map {
            NSPredicate(format: "label == %@ OR label BEGINSWITH %@", $0, $0 + ", ")
        })
        return app.cells.matching(canonical).allElementsBoundByIndex.filter(visible) +
            app.buttons.matching(NSPredicate(format: "label IN %@", labels)).allElementsBoundByIndex.filter(visible)
    }
    private func tapRow(_ labels: [String], name: String) throws {
        record("source-picker-before-" + name)
        if name != "local-device" { try NativeFilesBrowser.revealEntry(in: app, labels: labels, harness: harness) }
        let row = try unique("Files row \(labels)") { self.rows(labels) }
        guard row.isEnabled else { throw PortableHarnessError("Native source row is disabled: \(labels)") }
        row.tap()
    }
    private func visible(_ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        let frame = element.frame
        return frame.width > 0 && frame.height > 0 && frame.midX.isFinite && frame.midY.isFinite &&
            app.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) && element.isHittable
    }
    private func unique(_ name: String, elements: @escaping () -> [XCUIElement]) throws -> XCUIElement {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !elements().isEmpty }, object: app)
        guard XCTWaiter.wait(for: [ready], timeout: 15) == .completed else {
            record("source-picker-missing-control"); throw PortableHarnessError("No observed \(name)")
        }
        let found = elements()
        guard found.count == 1, let element = found.first else {
            record("source-picker-ambiguous-control"); throw PortableHarnessError("Ambiguous observed \(name): \(found.count)")
        }
        return element
    }
    private func waitForDismissal() throws {
        let nativeLabels = ["Cancel", "Cancelar", "Open", "Abrir"]
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            let controls = self.app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@", nativeLabels))
                .allElementsBoundByIndex.filter(self.visible)
            return controls.isEmpty && !self.harness.identified("PopoverDismissRegion").exists && !self.app.alerts.firstMatch.exists
        }, object: app)
        guard XCTWaiter.wait(for: [dismissed], timeout: 25) == .completed else {
            record("source-picker-not-dismissed")
            throw PortableHarnessError("The source picker did not dismiss, or an unreviewed native permission alert appeared")
        }
        record("source-picker-dismissed")
    }
}
