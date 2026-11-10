import Foundation
import CryptoKit

/// A source owned by this test run in the separate receiver's shared Documents folder.
struct FilesCopySource {
    enum Failure: Error { case configuration, sourceChanged, invalidFilename, existingFile }
    let peerDisplayName: String
    let runID: String
    let documents: URL
    let fixture: URL
    let fixtureSHA256: String
    private let fixtureBytes: Data

    init(configuration: [String: Any]) throws {
        guard configuration["route"] as? String == "files-copy",
              let peer = configuration["peerDisplayName"] as? String, !peer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let runID = configuration["runID"] as? String,
              runID.range(of: "^[a-f0-9]{12}$", options: .regularExpression) != nil,
              let directoryPath = configuration["documents"] as? String, directoryPath.hasPrefix("/"),
              let fixturePath = configuration["fixture"] as? String, fixturePath.hasPrefix("/"),
              let hash = configuration["fixtureSHA256"] as? String,
              hash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw Failure.configuration }
        let directory = URL(fileURLWithPath: directoryPath, isDirectory: true).standardizedFileURL
        let source = URL(fileURLWithPath: fixturePath).standardizedFileURL
        guard directory.path == directoryPath, directory.resolvingSymlinksInPath().path == directory.path,
              directory.lastPathComponent == runID,
              directory.deletingLastPathComponent().lastPathComponent == "LogisimE2E",
              directory.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent == "Documents",
              source.path == fixturePath, source.deletingLastPathComponent() == directory,
              source.pathExtension == "circ", source.resolvingSymlinksInPath() == source else { throw Failure.configuration }
        let bytes = try Data(contentsOf: source)
        guard Self.sha256(bytes) == hash else { throw Failure.sourceChanged }
        self.peerDisplayName = peer
        self.runID = runID
        self.documents = directory
        self.fixture = source
        self.fixtureSHA256 = hash
        self.fixtureBytes = bytes
    }

    func stage(_ bytes: Data, filename: String) throws -> URL {
        let pattern = #"(?<![A-Za-z0-9])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![A-Za-z0-9])"#
        guard !filename.isEmpty, filename == (filename as NSString).lastPathComponent,
              !filename.contains("\\"), !filename.contains("\n"), !filename.contains("\r"),
              (filename as NSString).pathExtension == "circ",
              (filename as NSString).deletingPathExtension.range(of: pattern, options: .regularExpression) != nil else {
            throw Failure.invalidFilename
        }
        guard bytes == fixtureBytes, try Data(contentsOf: fixture) == fixtureBytes,
              documents.resolvingSymlinksInPath() == documents else { throw Failure.sourceChanged }
        let target = documents.appendingPathComponent(filename).standardizedFileURL
        guard target.deletingLastPathComponent() == documents,
              target.resolvingSymlinksInPath() == target else { throw Failure.invalidFilename }
        guard !FileManager.default.fileExists(atPath: target.path) else { throw Failure.existingFile }
        try bytes.write(to: target, options: .withoutOverwriting)
        try verify(target)
        return target
    }

    func verify(_ selected: URL) throws {
        guard selected.deletingLastPathComponent() == documents,
              documents.resolvingSymlinksInPath() == documents,
              selected.resolvingSymlinksInPath() == selected,
              try Data(contentsOf: fixture) == fixtureBytes,
              try Data(contentsOf: selected) == fixtureBytes else { throw Failure.sourceChanged }
    }

    private static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
