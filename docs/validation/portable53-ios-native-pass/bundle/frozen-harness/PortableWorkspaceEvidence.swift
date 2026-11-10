import Foundation
import CryptoKit
import Darwin

/// Read-only observer of IosCatalogStorage's committed files. Never creates a source lock or workspace.
struct PortableWorkspaceEvidence {
    let workspaceURL: URL
    var lockTimeout: TimeInterval = 10

    /// XCTest repeats the Application row in Attributes, subtree and query output.
    static func processID(inDiagnostics text: String) throws -> Int {
        let pattern = try NSRegularExpression(pattern: #"\bApplication,\s+(?:0x[0-9A-Fa-f]+,\s+)?pid:\s*([0-9]+)"#)
        let matches = pattern.matches(in: text, range: NSRange(text.startIndex..., in: text))
        let processes = Set(matches.compactMap { match -> Int? in
            guard let range = Range(match.range(at: 1), in: text) else { return nil }
            return Int(text[range])
        })
        guard processes.count == 1, let process = processes.first, process > 0 else {
            throw PortableEvidenceError("Public XCTest diagnostics did not identify one target process")
        }
        return process
    }

    func readCurrent(requirePortableSource: Bool) throws -> PortableWorkspaceSnapshot {
        try withReadLock { try PortableWorkspaceSnapshot.read(workspaceURL, portable: requirePortableSource) }
    }

    /// Copies the flat workspace while holding the same flock used by IosCatalogStorage.
    /// The returned directory has no live lock; it can be passed directly to verify-portable53.py.
    func capture(to destination: URL, requirePortableSource: Bool) throws -> PortableWorkspaceSnapshot {
        let source = workspaceURL.standardizedFileURL
        let target = destination.standardizedFileURL
        guard source != target, !target.path.hasPrefix(source.path + "/"),
              !source.path.hasPrefix(target.path + "/"), !FileManager.default.fileExists(atPath: target.path) else {
            throw PortableEvidenceError("Snapshot must use a new directory outside the source workspace")
        }
        return try withReadLock {
            let observed = try PortableWorkspaceSnapshot.read(source, portable: requirePortableSource)
            try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
            do {
                var hashes: [String: String] = [:]
                for file in try FileManager.default.contentsOfDirectory(at: source,
                        includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey]) {
                    let properties = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
                    guard properties.isRegularFile == true, properties.isSymbolicLink != true else {
                        throw PortableEvidenceError("Workspace evidence contains a symlink or non-regular entry")
                    }
                    if file.lastPathComponent == "catalog.lock" { continue }
                    let data = try Data(contentsOf: file)
                    try data.write(to: target.appendingPathComponent(file.lastPathComponent), options: .withoutOverwriting)
                    hashes[file.lastPathComponent] = portableEvidenceSHA(data)
                }
                let copied = try PortableWorkspaceSnapshot.read(target, portable: requirePortableSource)
                guard NSDictionary(dictionary: observed.summary).isEqual(to: copied.summary) else {
                    throw PortableEvidenceError("Copied workspace differs from the committed observation")
                }
                let manifest: [String: Any] = ["schema": 1, "status": "LOCKED_READ_ONLY_SNAPSHOT",
                    "sourceWorkspace": source.path, "snapshotWorkspace": target.path,
                    "coordination": "flock LOCK_SH on existing catalog.lock; production commits use LOCK_EX",
                    "files": hashes, "observation": copied.summary]
                try JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted, .sortedKeys])
                    .write(to: target.deletingLastPathComponent().appendingPathComponent("snapshot.json"), options: .withoutOverwriting)
                return copied
            } catch {
                // Preserve partial evidence with an explicit failure instead of returning it as a snapshot.
                try? Data(String(describing: error).utf8).write(to: target.deletingLastPathComponent().appendingPathComponent("snapshot-failed.txt"))
                throw error
            }
        }
    }

    private func withReadLock<T>(_ action: () throws -> T) throws -> T {
        let properties = try workspaceURL.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard properties.isDirectory == true, properties.isSymbolicLink != true else {
            throw PortableEvidenceError("Workspace evidence source is not a regular directory")
        }
        let descriptor = open(workspaceURL.appendingPathComponent("catalog.lock").path, O_RDONLY | O_NOFOLLOW)
        guard descriptor >= 0 else { throw PortableEvidenceError("Cannot read the existing workspace commit lock") }
        defer { close(descriptor) }
        let deadline = ProcessInfo.processInfo.systemUptime + lockTimeout
        while flock(descriptor, LOCK_SH | LOCK_NB) != 0 {
            guard errno == EINTR || errno == EWOULDBLOCK || errno == EAGAIN else {
                throw PortableEvidenceError("Cannot share the workspace commit lock")
            }
            guard ProcessInfo.processInfo.systemUptime < deadline else {
                throw PortableEvidenceError("Timed out waiting for a committed workspace snapshot")
            }
            Thread.sleep(forTimeInterval: 0.02)
        }
        defer { flock(descriptor, LOCK_UN) }
        for file in try FileManager.default.contentsOfDirectory(at: workspaceURL,
                includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey]) {
            let values = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true else {
                throw PortableEvidenceError("Workspace evidence contains a symlink or non-regular entry")
            }
        }
        return try action()
    }
}

private struct PortableEvidenceError: Error, CustomStringConvertible {
    let description: String
    init(_ message: String) { description = message }
}

private func portableEvidenceSHA(_ content: Data) -> String {
    SHA256.hash(data: content).map { String(format: "%02x", $0) }.joined()
}

private func portableEvidenceStamp(_ text: String) -> String {
    var hash: UInt64 = 0xcbf29ce484222325
    for byte in text.utf8 { hash = (hash ^ UInt64(byte)) &* 0x100000001b3 }
    return "\(text.utf8.count)-" + String(hash, radix: 16)
}

struct PortableWorkspaceSnapshot {
    let activeID: String?
    let catalogIDs: Set<String>
    let windows: [String: String]
    let descriptor: [String: String]?
    let source: Data?
    let projectIdentity: [String: [String]]?
    let persistedXML: String

    var summary: [String: Any] {
        ["activeProjectId": activeID ?? "", "catalogProjectIds": catalogIDs.sorted(), "windows": windows,
         "sourceDescriptor": descriptor ?? [:], "projectIdentity": projectIdentity ?? [:],
         "sourceArchiveSha256": source.map(portableEvidenceSHA) ?? ""]
    }

    func uniqueWindow() throws -> String {
        let candidates = windows.filter { $0.value == activeID }.map { $0.key }
        guard candidates.count == 1, let window = candidates.first else {
            throw PortableEvidenceError("The UI import did not identify exactly one committed window binding")
        }
        return window
    }

    static func read(_ directory: URL, portable: Bool) throws -> PortableWorkspaceSnapshot {
        let catalog = try PortableTokens(Data(contentsOf: directory.appendingPathComponent("catalog.workspace")), kind: "CATALOG", version: "1")
        let active = try catalog.next()
        guard ["true", "false"].contains(try catalog.next()) else {
            throw PortableEvidenceError("Catalog migration flag is malformed")
        }
        let count = try catalog.count()
        var infos: [String: PortableWorkspaceInfo] = [:]
        for _ in 0..<count {
            let info = try PortableWorkspaceInfo(catalog)
            guard infos[info.id] == nil else { throw PortableEvidenceError("Catalog repeats a workspace identity") }
            infos[info.id] = info
        }
        try catalog.finish()
        let ids = Set(infos.keys)
        var windows: [String: String] = [:]
        for file in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            where file.lastPathComponent.hasPrefix("window-") && file.lastPathComponent.hasSuffix(".workspace-link") {
            let link = try PortableTokens(Data(contentsOf: file), kind: "LINK", version: "1", link: true)
            let window = try link.next(), project = try link.next()
            try link.finish()
            guard !window.isEmpty, window.utf16.count <= 500,
                  file.lastPathComponent == "window-\(portableEvidenceStamp(window)).workspace-link",
                  project.isEmpty || ids.contains(project), windows[window] == nil else {
                throw PortableEvidenceError("Window identity, storage key or project binding is invalid")
            }
            windows[window] = project
        }
        guard !active.isEmpty else {
            if portable { throw PortableEvidenceError("Portable workspace is not active") }
            return PortableWorkspaceSnapshot(activeID: nil, catalogIDs: ids, windows: windows, descriptor: nil, source: nil, projectIdentity: nil, persistedXML: "")
        }
        guard let info = infos[active], !info.deleted else { throw PortableEvidenceError("Active workspace is missing or deleted") }
        let payload = directory.appendingPathComponent("project-\(active)-\(info.revision).workspace")
        let tokens = try PortableTokens(Data(contentsOf: payload), kind: "PROJECT", version: "4")
        let saved = try PortableWorkspaceInfo(tokens)
        guard saved.id == active && saved.revision == info.revision else { throw PortableEvidenceError("Catalog/payload revision or identity mismatch") }
        let xml = try tokens.next()
        _ = try tokens.next()
        for _ in 0..<(try tokens.count()) { _ = try tokens.next() }
        guard ["LEGACY", "LENGTH_PREFIXED"].contains(try tokens.next()) else { throw PortableEvidenceError("Unknown viewport key format") }
        for _ in 0..<(try tokens.count()) { for _ in 0..<4 { _ = try tokens.next() } }
        for _ in 0..<(try tokens.count()) { _ = try tokens.next() }
        for _ in 0..<(try tokens.count()) { for _ in 0..<4 { _ = try tokens.next() } }
        var descriptors: [String: [String: String]] = [:]
        var attachmentBytes = 0
        for _ in 0..<(try tokens.count(maximum: 32)) {
            let key = try tokens.next()
            let name = try tokens.next(), mime = try tokens.next(), sizeText = try tokens.next(), hash = try tokens.next()
            guard descriptors[key] == nil, portableMetadata(key, maximum: 200), portableMetadata(name, maximum: 4096),
                  portableMetadata(mime, maximum: 200), let size = Int(sizeText), size >= 0, size <= 64 * 1024 * 1024,
                  hash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
                throw PortableEvidenceError("Repeated or invalid attachment descriptor")
            }
            attachmentBytes += size
            guard attachmentBytes <= 64 * 1024 * 1024 else { throw PortableEvidenceError("Attachment descriptors exceed total size limit") }
            descriptors[key] = ["name": name, "mime": mime, "bytes": sizeText, "sha256": hash]
        }
        try tokens.finish()
        guard portable else {
            return PortableWorkspaceSnapshot(activeID: active, catalogIDs: ids, windows: windows, descriptor: nil, source: nil, projectIdentity: nil, persistedXML: xml)
        }
        guard !FileManager.default.fileExists(atPath: directory.appendingPathComponent("external-\(active).workspace-link").path),
              let descriptor = descriptors["portable-source"], descriptor["mime"] == "application/x-logisim-bundle",
              let digest = descriptor["sha256"], digest.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              let sizeText = descriptor["bytes"], let size = Int(sizeText), size > 0 && size <= 64 * 1024 * 1024 else {
            throw PortableEvidenceError("Portable copy lacks a valid sidecar or inherited a native editable reference")
        }
        let blob = try String(contentsOf: directory.appendingPathComponent("attachment-\(digest).workspace"), encoding: .utf8)
        let header = "LOGISIM-WORKSPACE-ATTACHMENT\n1\n"
        guard blob.hasPrefix(header), let source = Data(base64Encoded: String(blob.dropFirst(header.count))),
              source.count == size, portableEvidenceSHA(source) == digest,
              source.base64EncodedString() == String(blob.dropFirst(header.count)) else {
            throw PortableEvidenceError("Portable source blob, size and descriptor hash do not agree")
        }
        let identity = try PortableProjectIdentity.read(xml)
        return PortableWorkspaceSnapshot(activeID: active, catalogIDs: ids, windows: windows, descriptor: descriptor,
                                         source: source, projectIdentity: identity, persistedXML: xml)
    }
}

private struct PortableWorkspaceInfo {
    let id: String
    let revision: Int
    let deleted: Bool
    init(_ tokens: PortableTokens) throws {
        id = try tokens.next(); let name = try tokens.next(); revision = try tokens.integer()
        guard Int64(try tokens.next()) != nil, Int64(try tokens.next()) != nil else {
            throw PortableEvidenceError("Workspace timestamps are malformed")
        }
        let deletedText = try tokens.next(); deleted = deletedText == "true"
        guard ["true", "false"].contains(deletedText), id.range(of: "^[A-Za-z0-9_-]{1,80}$", options: .regularExpression) != nil,
              revision > 0, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw PortableEvidenceError("Workspace metadata is malformed")
        }
        let previous = try tokens.integer()
        var history = Set<Int>()
        guard previous < revision else { throw PortableEvidenceError("Workspace previous revision is invalid") }
        for _ in 0..<(try tokens.count()) {
            let item = try tokens.integer()
            guard item > 0, item < revision, history.insert(item).inserted else {
                throw PortableEvidenceError("Workspace revision history is invalid")
            }
        }
    }
}

private final class PortableTokens {
    private let units: [UInt16]
    private var index = 0
    init(_ content: Data, kind: String, version: String, link: Bool = false) throws {
        guard let text = String(data: content, encoding: .utf8), let range = text.range(of: "\n\n") else {
            throw PortableEvidenceError("Workspace envelope is not UTF-8 or is missing its header")
        }
        let header = text[..<range.lowerBound].components(separatedBy: "\n")
        let body = String(text[range.upperBound...])
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in body.utf8 { hash = (hash ^ UInt64(byte)) &* 0x100000001b3 }
        let checksum = (link ? "\(body.utf8.count)-" : "") + String(hash, radix: 16)
        guard header == ["LOGISIM-" + kind, version, checksum] else { throw PortableEvidenceError("Workspace envelope checksum/kind/version mismatch") }
        units = Array(body.utf16)
    }
    func next() throws -> String {
        var digits = ""
        while index < units.count && units[index] >= 48 && units[index] <= 57 {
            digits.append(Character(UnicodeScalar(Int(units[index]))!)); index += 1
            guard digits.count <= 8 else { throw PortableEvidenceError("Workspace token length is excessive") }
        }
        guard !digits.isEmpty, index < units.count, units[index] == 58, let count = Int(digits),
              count <= units.count - index - 1 else { throw PortableEvidenceError("Workspace token is truncated or invalid") }
        index += 1
        let value = String(decoding: units[index..<(index + count)], as: UTF16.self)
        index += count
        return value
    }
    func count(maximum: Int = 100_000) throws -> Int {
        let value = try integer()
        guard value <= maximum else { throw PortableEvidenceError("Workspace collection count is invalid") }
        return value
    }
    func integer() throws -> Int {
        guard let value = Int(try next()), value >= 0 else { throw PortableEvidenceError("Workspace integer is invalid") }
        return value
    }
    func finish() throws { if index != units.count { throw PortableEvidenceError("Unconsumed workspace fields") } }
}

private func portableMetadata(_ value: String, maximum: Int) -> Bool {
    value.utf16.count <= maximum && !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !value.unicodeScalars.contains { $0.value < 32 || (127...159).contains($0.value) }
}

private final class PortableProjectIdentity: NSObject, XMLParserDelegate {
    private var path: [String] = []
    private var currentCircuit = ""
    private var mainCircuitName: String?
    private var currentComponent = ""
    private var circuits: [String: String] = [:]
    private var components: [String: [String: String]] = [:]
    private var owners: [String: String] = [:]
    private var wireCount = 0
    private var invalid = false

    static func read(_ xml: String) throws -> [String: [String]] {
        let value = PortableProjectIdentity()
        let parser = XMLParser(data: Data(xml.utf8)); parser.delegate = value; parser.shouldResolveExternalEntities = false
        guard parser.parse(), !value.invalid, value.circuits.count == 3, value.components.count == 11, value.wireCount == 7 else {
            throw PortableEvidenceError("Portable workspace lost its 3 definitions, 11 component identities or 7 wires")
        }
        let groups: [String: Set<String>] = [
            "Top53": ["source-first53", "instance-first53", "out-first53", "source-second53", "instance-second53", "out-second53"],
            "Bridge53": ["bridge-in53", "bridge-cell53", "bridge-out53"], "Cell53": ["cell-in53", "cell-out53"],
        ]
        var identity: [String: [String]] = [:]
        var circuitForGroup: [String: String] = [:]
        for (group, componentIDs) in groups {
            let owner = Set(componentIDs.compactMap { value.owners[$0] })
            guard owner.count == 1, let circuit = owner.first,
                  Set(value.owners.filter { $0.value == circuit }.map { $0.key }) == componentIDs else {
                throw PortableEvidenceError("Portable components are not partitioned into their three source definitions")
            }
            identity[group] = [circuit] + componentIDs.sorted(); circuitForGroup[group] = circuit
        }
        guard circuitForGroup["Top53"] == "circuit-top53", value.mainCircuitName == value.circuits["circuit-top53"],
              value.components["instance-first53"]?["label"] == "FIRST53",
              value.components["instance-second53"]?["label"] == "SECOND53",
              value.components["instance-first53"]?["name"] == value.circuits[circuitForGroup["Bridge53"] ?? ""],
              value.components["instance-second53"]?["name"] == value.circuits[circuitForGroup["Bridge53"] ?? ""],
              value.components["bridge-cell53"]?["name"] == value.circuits[circuitForGroup["Cell53"] ?? ""] else {
            throw PortableEvidenceError("The two shared instances or their transitive definition were changed")
        }
        return identity
    }
    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String]) {
        if path == ["project"] && name == "main" {
            if mainCircuitName == nil { mainCircuitName = attributes["name"] } else { invalid = true }
        }
        if path == ["project"] && name == "circuit" {
            if let id = attributes["id"], let label = attributes["name"], circuits[id] == nil {
                circuits[id] = label; currentCircuit = id
            } else { invalid = true }
        }
        if path == ["project", "circuit"] && name == "comp" {
            if let id = attributes["logisim-mobile-id"], let kind = attributes["name"], components[id] == nil {
                components[id] = ["name": kind]; owners[id] = currentCircuit; currentComponent = id
            } else { invalid = true }
        }
        if path == ["project", "circuit", "comp"] && name == "a" && attributes["name"] == "label" {
            components[currentComponent]?["label"] = attributes["val"]
        }
        if path == ["project", "circuit"] && name == "wire" { wireCount += 1 }
        path.append(name)
    }
    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        if path == ["project", "circuit", "comp"] { currentComponent = "" }
        if path == ["project", "circuit"] { currentCircuit = "" }
        _ = path.popLast()
    }
}
