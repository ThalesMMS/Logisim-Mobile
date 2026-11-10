import CryptoKit
import Darwin
import FileProvider
import Foundation

enum FixtureConfiguration {
    static let appGroupIdentifier = "group.thalesmms.logisim.validation.revocationfixture"
    static let domainIdentifier = "logisim-revocation-fixture-v1"
    static let itemIdentifier = "revocation-circuit-v1"
    static let filename = "revocation.circ"
    static let displayName = "Logisim Revocation Fixture"
}

struct FixtureState: Codable {
    let schemaVersion: Int
    var authenticationAllowed: Bool
    var backendSHA256: String
    var backendByteCount: Int
    var generation: Int
}

struct FixtureEvent: Codable {
    let sequence: Int64
    let timestamp: String
    let process: String
    let kind: String
    let itemIdentifier: String?
    let details: [String: String]
    let errorDomain: String?
    let errorCode: Int?
    let errorDiagnostic: FixtureErrorDiagnostic?
}

struct FixtureEvidence: Codable {
    let state: FixtureState
    let events: [FixtureEvent]
}

/// The control app and extension share only this fixture-specific App Group.
/// Backend bytes, authentication and events commit together under a process-shared lock.
final class FixtureStore {
    private struct Database: Codable {
        var state: FixtureState
        var contents: Data
        var events: [FixtureEvent]
        var nextSequence: Int64
    }

    private let directory: URL
    private var databaseURL: URL { directory.appendingPathComponent("fixture-state.json") }
    private var lockURL: URL { directory.appendingPathComponent("fixture.lock") }
    private static let maximumBytes = 1_048_576

    convenience init() throws {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: FixtureConfiguration.appGroupIdentifier) else {
            throw Self.failure("The fixture App Group is unavailable. Check both targets' signing and entitlements.")
        }
        self.init(directory: container.appendingPathComponent("RevocationFixture", isDirectory: true))
    }

    /// Used by host storage checks. Neither installed target accepts a directory from external input.
    init(directory: URL) { self.directory = directory.standardizedFileURL }

    /// Invoked by the control app's Prepare button, never by extension initialization.
    @discardableResult func prepare() throws -> FixtureState {
        try exclusive(create: true) {
            var database: Database
            if FileManager.default.fileExists(atPath: databaseURL.path) {
                database = try read()
            } else {
                let bytes = Data(Self.initialCircuit.utf8)
                database = Database(state: FixtureState(schemaVersion: 1, authenticationAllowed: true,
                    backendSHA256: Self.sha256(bytes), backendByteCount: bytes.count, generation: 1),
                    contents: bytes, events: [], nextSequence: 1)
            }
            append("prepare", to: &database)
            try write(database)
            return database.state
        }
    }

    func snapshot() throws -> FixtureState { try exclusive { try read().state } }
    func backendData() throws -> Data { try exclusive { try read().contents } }
    func events() throws -> [FixtureEvent] { try exclusive { try read().events } }
    func evidenceSnapshot() throws -> FixtureEvidence {
        try exclusive {
            let database = try read()
            return FixtureEvidence(state: database.state, events: database.events)
        }
    }

    @discardableResult func setAuthenticationAllowed(_ allowed: Bool) throws -> FixtureState {
        try exclusive {
            var database = try read()
            database.state.authenticationAllowed = allowed
            append("authentication.changed", details: ["allowed": String(allowed)], to: &database)
            try write(database)
            return database.state
        }
    }

    /// Returns a disposable copy, because FileProvider may consume the returned URL.
    func temporaryBackendCopy() throws -> URL {
        try exclusive {
            let database = try read()
            guard database.state.authenticationAllowed else { throw Self.authenticationError() }
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("revocation-\(UUID().uuidString).circ")
            try database.contents.write(to: url, options: .withoutOverwriting)
            return url
        }
    }

    @discardableResult func replaceBackend(_ contents: Data, expectedSHA256: String?) throws -> FixtureState {
        try exclusive {
            var database = try read()
            guard database.state.authenticationAllowed else { throw Self.authenticationError() }
            guard contents.count <= Self.maximumBytes else { throw Self.failure("Fixture upload exceeds one MiB.") }
            if let expectedSHA256, expectedSHA256 != database.state.backendSHA256 {
                throw NSError(domain: NSCocoaErrorDomain, code: NSFileWriteUnknownError,
                              userInfo: [NSLocalizedDescriptionKey: "The fixture backend changed before this upload."])
            }
            database.contents = contents
            database.state.backendSHA256 = Self.sha256(contents)
            database.state.backendByteCount = contents.count
            database.state.generation += 1
            append("backend.updated", itemIdentifier: FixtureConfiguration.itemIdentifier,
                   details: ["sha256": database.state.backendSHA256, "bytes": String(contents.count)], to: &database)
            try write(database)
            return database.state
        }
    }

    func record(_ kind: String, itemIdentifier: String? = nil, details: [String: String] = [:], error: NSError? = nil) throws {
        try exclusive {
            var database = try read()
            append(kind, itemIdentifier: itemIdentifier, details: details, error: error, to: &database)
            try write(database)
        }
    }

    static func authenticationError() -> NSError {
        NSError(domain: NSFileProviderErrorDomain, code: NSFileProviderError.notAuthenticated.rawValue,
                userInfo: [NSLocalizedDescriptionKey: "The fixture account's authentication was denied by its control app."])
    }

    private func exclusive<T>(create: Bool = false, _ action: () throws -> T) throws -> T {
        if create { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
        let descriptor = Darwin.open(lockURL.path, O_RDWR | O_CLOEXEC | O_NOFOLLOW | (create ? O_CREAT : 0), 0o600)
        guard descriptor >= 0 else { throw Self.failure("Prepare the fixture before accessing its backend.") }
        defer { Darwin.close(descriptor) }
        while flock(descriptor, LOCK_EX) != 0 {
            if errno != EINTR { throw Self.failure("Cannot lock the fixture backend.") }
        }
        defer { flock(descriptor, LOCK_UN) }
        return try action()
    }

    private func read() throws -> Database {
        let database = try JSONDecoder().decode(Database.self, from: Data(contentsOf: databaseURL))
        guard database.state.schemaVersion == 1, database.contents.count <= Self.maximumBytes,
              database.state.backendByteCount == database.contents.count,
              database.state.backendSHA256 == Self.sha256(database.contents) else {
            throw Self.failure("The fixture backend fingerprint is invalid. Existing evidence was left untouched.")
        }
        return database
    }

    private func write(_ database: Database) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(database).write(to: databaseURL, options: .atomic)
    }

    private func append(_ kind: String, itemIdentifier: String? = nil, details: [String: String] = [:],
                        error: NSError? = nil, to database: inout Database) {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        database.events.append(FixtureEvent(sequence: database.nextSequence,
            timestamp: formatter.string(from: Date()),
            process: "\(ProcessInfo.processInfo.processName):\(ProcessInfo.processInfo.processIdentifier)",
            kind: kind, itemIdentifier: itemIdentifier, details: details,
            errorDomain: error?.domain, errorCode: error?.code,
            errorDiagnostic: error.map { FixtureErrorDiagnostic(error: $0) }))
        database.nextSequence += 1
    }

    private static func failure(_ description: String) -> NSError {
        NSError(domain: NSCocoaErrorDomain, code: NSFileReadUnknownError,
                userInfo: [NSLocalizedDescriptionKey: description])
    }

    private static func sha256(_ bytes: Data) -> String {
        SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }

    private static let initialCircuit = """
    <?xml version="1.0" encoding="UTF-8"?>
    <project source="5.1.0-dev" version="1.0">
      <lib desc="#Wiring" name="0"/>
      <main name="provider_revocation"/>
      <circuit name="provider_revocation">
        <comp lib="0" loc="(100,100)" name="Pin"><a name="type" val="input"/><a name="width" val="1"/><a name="label" val="REVOCATION_INPUT"/></comp>
        <comp lib="0" loc="(200,100)" name="Pin"><a name="type" val="output"/><a name="width" val="1"/><a name="label" val="REVOCATION_OUTPUT"/></comp>
        <wire from="(100,100)" to="(200,100)"/>
      </circuit>
    </project>
    """
}
