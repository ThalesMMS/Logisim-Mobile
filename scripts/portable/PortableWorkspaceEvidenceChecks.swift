import Foundation
import CryptoKit
import Darwin

/// Executable offline checks for the UI-test-only evidence collector. Never launches an app.
@main struct PortableWorkspaceEvidenceChecks {
    static func sha(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
    static func token(_ value: String) -> String { "\(value.utf16.count):\(value)" }
    static func tokens(_ values: [String]) -> String { values.map(token).joined() }
    static func envelope(_ kind: String, _ body: String, link: Bool = false) -> Data {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in body.utf8 { hash = (hash ^ UInt64(byte)) &* 0x100000001b3 }
        let digest = (link ? "\(body.utf8.count)-" : "") + String(hash, radix: 16)
        return Data("LOGISIM-\(kind)\n\(kind == "PROJECT" ? "4" : "1")\n\(digest)\n\n\(body)".utf8)
    }
    static func require(_ condition: @autoclosure () throws -> Bool, _ message: String) throws {
        guard try condition() else { throw NSError(domain: message, code: 1) }
    }
    static func failure(_ action: () throws -> Void, containing: String? = nil) throws {
        do { try action() } catch {
            if let text = containing { try require(String(describing: error).contains(text), "Unexpected failure: \(error)") }
            return
        }
        throw NSError(domain: "Expected rejection", code: 1)
    }
    static func main() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("portable-evidence-check-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let workspace = root.appendingPathComponent("workspace")
        try FileManager.default.createDirectory(at: workspace, withIntermediateDirectories: true)
        try Data().write(to: workspace.appendingPathComponent("catalog.lock"))
        let source = Data("offline source bytes — not a native export".utf8)
        let digest = sha(source)
        let info = ["fresh", "A🧩B", "1", "0", "0", "false", "0", "0"]
        let groups: [(String, String, [(String, String, String)])] = [
            ("circuit-top53", "Top53", [("source-first53", "Constant", ""), ("instance-first53", "Bridge53", "FIRST53"),
                ("out-first53", "Pin", ""), ("source-second53", "Constant", ""), ("instance-second53", "Bridge53", "SECOND53"), ("out-second53", "Pin", "")]),
            ("qualified-bridge", "Bridge53", [("bridge-in53", "Pin", ""), ("bridge-cell53", "Cell53", "TRANSITIVE53"), ("bridge-out53", "Pin", "")]),
            ("qualified-cell", "Cell53", [("cell-in53", "Pin", ""), ("cell-out53", "Pin", "")]),
        ]
        var xml = "<project><main name=\"Top53\"/>"
        for (index, group) in groups.enumerated() {
            xml += "<circuit id=\"\(group.0)\" name=\"\(group.1)\">"
            for (id, kind, label) in group.2 {
                xml += "<comp logisim-mobile-id=\"\(id)\" name=\"\(kind)\"><a name=\"label\" val=\"\(label)\"/></comp>"
            }
            for _ in 0..<(index == 0 ? 5 : 1) { xml += "<wire from=\"(0,0)\" to=\"(10,0)\"/>" }
            xml += "</circuit>"
        }
        xml += "</project>"
        let catalogBody = tokens(["fresh", "true", "1"] + info)
        let projectBody = tokens(info + [xml, "circuit-top53", "0", "LENGTH_PREFIXED", "0", "0", "0", "1",
            "portable-source", "Top53.lsebdl", "application/x-logisim-bundle", String(source.count), digest])
        let catalog = envelope("CATALOG", catalogBody)
        let payload = envelope("PROJECT", projectBody)
        let link = envelope("LINK", tokens(["scene🧩id", "fresh"]), link: true)
        let blob = Data(("LOGISIM-WORKSPACE-ATTACHMENT\n1\n" + source.base64EncodedString()).utf8)
        let catalogURL = workspace.appendingPathComponent("catalog.workspace")
        let payloadURL = workspace.appendingPathComponent("project-fresh-1.workspace")
        let blobURL = workspace.appendingPathComponent("attachment-\(digest).workspace")
        var windowHash: UInt64 = 0xcbf29ce484222325
        for byte in "scene🧩id".utf8 { windowHash = (windowHash ^ UInt64(byte)) &* 0x100000001b3 }
        let linkURL = workspace.appendingPathComponent("window-\("scene🧩id".utf8.count)-\(String(windowHash, radix: 16)).workspace-link")
        try catalog.write(to: catalogURL); try payload.write(to: payloadURL)
        try blob.write(to: blobURL); try link.write(to: linkURL)
        var observer = PortableWorkspaceEvidence(workspaceURL: workspace)
        var checks: [String] = []
        let publicDiagnostics = """
        Attributes: Application, 0x111a38a00, pid: 78717, label: 'Logisim Mobile'
        Element subtree:
         →Application, 0x111a38a00, pid: 78717, label: 'Logisim Mobile'
        Query chain:
         →Find: Application 'thalesmms.logisim.mobile'
          Output: {
            Application, 0x111a51f40, pid: 78717, label: 'Logisim Mobile'
          }
        """
        try require(try PortableWorkspaceEvidence.processID(inDiagnostics: publicDiagnostics) == 78717, "Public diagnostic PID")
        try failure({ _ = try PortableWorkspaceEvidence.processID(inDiagnostics: "Application 'unresolved'") })
        try failure({ _ = try PortableWorkspaceEvidence.processID(inDiagnostics: publicDiagnostics + "\nApplication, pid: 123") })
        checks.append("public XCTest diagnostic PID parsing accepts repeated target rows and rejects absent or ambiguous IDs")
        let observed = try observer.readCurrent(requirePortableSource: true)
        try require(observed.activeID == "fresh" && observed.catalogIDs == ["fresh"], "Catalog identities")
        try require(observed.source == source && observed.persistedXML == xml, "Source/archive bytes")
        try require(observed.windows["scene🧩id"] == "fresh", "UTF-16 window identity")
        try require(try observed.uniqueWindow() == "scene🧩id", "Observed routing")
        try require(observed.projectIdentity?["Top53"]?.first == "circuit-top53", "XML definition identity")
        checks.append("v4 UTF16 catalog/payload/link and exact portable source bytes")
        let destination = root.appendingPathComponent("evidence/imported/workspace")
        let captured = try observer.capture(to: destination, requirePortableSource: true)
        try require(captured.source == source && captured.catalogIDs == observed.catalogIDs, "Captured observation")
        try require(!FileManager.default.fileExists(atPath: destination.appendingPathComponent("catalog.lock").path), "No live lock in snapshot")
        let manifestURL = destination.deletingLastPathComponent().appendingPathComponent("snapshot.json")
        let manifest = try JSONSerialization.jsonObject(with: Data(contentsOf: manifestURL)) as! [String: Any]
        let files = manifest["files"] as! [String: String]
        for (name, hash) in files {
            try require(hash == sha(Data(contentsOf: destination.appendingPathComponent(name))), "Snapshot file manifest")
        }
        try require(files.count == 4, "Expected exact source file count excluding lock")
        checks.append("locked copy with per-file hash manifest and no source mutation")
        try failure({ _ = try observer.capture(to: destination, requirePortableSource: true) }, containing: "new directory")
        try failure({ _ = try observer.capture(to: workspace.appendingPathComponent("bad"), requirePortableSource: true) }, containing: "outside")
        checks.append("existing and in-source destinations rejected")
        let lock = open(workspace.appendingPathComponent("catalog.lock").path, O_RDONLY)
        try require(lock >= 0 && flock(lock, LOCK_EX | LOCK_NB) == 0, "Offline writer lock acquisition")
        observer.lockTimeout = 0.08
        try failure({ _ = try observer.readCurrent(requirePortableSource: true) }, containing: "Timed out")
        flock(lock, LOCK_UN); close(lock)
        _ = try observer.readCurrent(requirePortableSource: true)
        checks.append("shared capture waits for exclusive production-style commit lock and fails on deadline")
        try Data("corrupt".utf8).write(to: blobURL)
        try failure({ _ = try observer.readCurrent(requirePortableSource: true) }, containing: "blob")
        try blob.write(to: blobURL)
        checks.append("corrupt source attachment rejected")
        try FileManager.default.removeItem(at: blobURL)
        try failure({ _ = try observer.readCurrent(requirePortableSource: true) })
        try blob.write(to: blobURL)
        checks.append("missing descriptor-referenced blob rejected")
        let invalidDescriptor = projectBody.replacingOccurrences(of: token("Top53.lsebdl"), with: token(""))
        try envelope("PROJECT", invalidDescriptor).write(to: payloadURL)
        try failure({ _ = try observer.readCurrent(requirePortableSource: false) }, containing: "invalid attachment descriptor")
        try payload.write(to: payloadURL)
        checks.append("invalid attachment metadata rejected even before requiring portable source")
        try Data().write(to: workspace.appendingPathComponent("external-fresh.workspace-link"))
        try failure({ _ = try observer.readCurrent(requirePortableSource: true) }, containing: "inherited")
        try FileManager.default.removeItem(at: workspace.appendingPathComponent("external-fresh.workspace-link"))
        checks.append("inherited native editable association rejected")
        try envelope("CATALOG", tokens(["fresh", "true", "2"] + info + info)).write(to: catalogURL)
        try failure({ _ = try observer.readCurrent(requirePortableSource: true) }, containing: "repeats")
        try catalog.write(to: catalogURL)
        checks.append("duplicate catalog identities rejected")
        try (catalog + Data([32])).write(to: catalogURL)
        try failure({ _ = try observer.readCurrent(requirePortableSource: true) }, containing: "checksum")
        try catalog.write(to: catalogURL)
        checks.append("corrupted envelope checksum rejected")
        let wrongLink = workspace.appendingPathComponent("window-wrong.workspace-link")
        try FileManager.default.moveItem(at: linkURL, to: wrongLink)
        try failure({ _ = try observer.readCurrent(requirePortableSource: true) }, containing: "storage key")
        try FileManager.default.moveItem(at: wrongLink, to: linkURL)
        checks.append("window content must match the production filename and catalog identity")
        let symlink = workspace.appendingPathComponent("unexpected.workspace")
        try FileManager.default.createSymbolicLink(at: symlink, withDestinationURL: payloadURL)
        try failure({ _ = try observer.readCurrent(requirePortableSource: true) }, containing: "symlink")
        try FileManager.default.removeItem(at: symlink)
        checks.append("symlink workspace entries rejected before reading")
        try FileManager.default.removeItem(at: workspace.appendingPathComponent("catalog.lock"))
        try failure({ _ = try observer.readCurrent(requirePortableSource: true) }, containing: "existing")
        try require(!FileManager.default.fileExists(atPath: workspace.appendingPathComponent("catalog.lock").path), "No source lock creation")
        checks.append("observer refuses missing source lock without creating one")
        if CommandLine.arguments.count > 2 {
            let owner = PortableWorkspaceEvidence(workspaceURL: URL(fileURLWithPath: CommandLine.arguments[2]))
            let result = try owner.readCurrent(requirePortableSource: true)
            try require(result.projectIdentity?.count == 3 && result.source != nil, "Owning core generated workspace")
            checks.append("owning Kotlin core generated project v4 and attachment decoded")
        }
        let report: [String: Any] = ["schema": 1, "status": "OFFLINE_EVIDENCE_COLLECTOR_CHECKS_PASSED",
            "nativeUiStatus": "NOT_RUN", "checks": checks, "count": checks.count,
            "scope": "macOS Foundation/CryptoKit execution of isolated UI-test collector; no app, native picker, process restart or iOS execution"]
        try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
            .write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
    }
}
