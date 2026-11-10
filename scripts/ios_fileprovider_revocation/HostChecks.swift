import FileProvider
import Foundation

@main
enum FixtureHostChecks {
    static func main() throws {
        if CommandLine.arguments.count == 4, CommandLine.arguments[1] == "worker" {
            let store = FixtureStore(directory: URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true))
            let worker = CommandLine.arguments[3]
            for index in 0..<40 {
                try store.record("host.worker", details: ["worker": worker, "index": String(index)])
                _ = try store.evidenceSnapshot()
            }
            return
        }
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("logisim-revocation-store-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = FixtureStore(directory: directory)
        let initial = try store.prepare()
        try require(initial.backendSHA256 == "54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88", "Literal initial circuit fingerprint")
        try require(initial.backendByteCount == 552 && initial.generation == 1, "Initial bytes and generation")
        let original = try store.backendData()
        let edited = Data(String(decoding: original, as: UTF8.self)
            .replacingOccurrences(of: "REVOCATION_INPUT", with: "AUTHORIZED_EDIT").utf8)
        let uploaded = try store.replaceBackend(edited, expectedSHA256: initial.backendSHA256)
        try require(uploaded.generation == 2 && uploaded.backendSHA256 != initial.backendSHA256, "Authorized upload changes version")
        try require(try store.backendData() == edited, "Authorized upload persists the exact bytes")
        try require(try copiedBytes(store) == edited, "Authorized fetch returns the exact bytes")

        _ = try store.setAuthenticationAllowed(false)
        try denied("Fetch while authentication is denied") { _ = try store.temporaryBackendCopy() }
        try denied("Upload while authentication is denied") {
            _ = try store.replaceBackend(original, expectedSHA256: uploaded.backendSHA256)
        }
        let deniedState = try store.snapshot()
        try require(!deniedState.authenticationAllowed && deniedState.backendSHA256 == uploaded.backendSHA256 &&
                    deniedState.generation == 2, "Denied operations preserve state and version")
        try require(try store.backendData() == edited, "Denied operations preserve backend bytes")
        let preparedAgain = try store.prepare()
        try require(!preparedAgain.authenticationAllowed && preparedAgain.backendSHA256 == uploaded.backendSHA256,
                    "Prepare does not reset denied state or existing backend")

        _ = try store.setAuthenticationAllowed(true)
        try require(try copiedBytes(store) == edited, "Allow restores reading")
        do {
            _ = try store.replaceBackend(original, expectedSHA256: initial.backendSHA256)
            throw CheckFailure("An outdated upload unexpectedly succeeded")
        } catch let error as NSError where error.domain == NSCocoaErrorDomain && error.code == NSFileWriteUnknownError {
            try require(try store.backendData() == edited, "CAS rejection preserves backend")
        }

        let children = try ["first", "second"].map { worker -> Process in
            let process = Process()
            process.executableURL = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
            process.arguments = ["worker", directory.path, worker]
            try process.run()
            return process
        }
        for child in children {
            child.waitUntilExit()
            try require(child.terminationReason == .exit && child.terminationStatus == 0, "Both real worker processes complete")
        }
        let reopened = FixtureStore(directory: directory)
        let evidence = try reopened.evidenceSnapshot()
        let events = evidence.events.filter { $0.kind == "host.worker" }
        try require(events.count == 80, "Two processes preserve all 80 events")
        for worker in ["first", "second"] {
            let indices = events.filter { $0.details["worker"] == worker }.compactMap { $0.details["index"].flatMap(Int.init) }.sorted()
            try require(indices == Array(0..<40), "Each worker preserves all literal indices")
        }
        try require(evidence.events.map(\.sequence) == (1...Int64(evidence.events.count)).map { $0 }, "Event sequences remain contiguous")
        try require(Set(events.map(\.process)).count == 2, "Events identify two distinct processes")
        try require(evidence.state.backendSHA256 == uploaded.backendSHA256 && evidence.state.generation == 2 &&
                    evidence.state.authenticationAllowed, "Concurrent event writes preserve backend state")
        try require(try reopened.backendData() == edited, "Reopened JSON preserves exact backend bytes")
        let report: [String: Any] = ["hostStorage": "PASS", "nativeExecution": "NOT_RUN",
            "scope": "Foundation storage, permissions policy and two real host processes; no FileProvider domain or UI",
            "checks": ["literal initial bytes", "authorized upload and fetch", "denied fetch and upload preserve bytes",
                       "idempotent prepare", "allow restores reading", "outdated upload rejected", "two-process JSON and event integrity"],
            "workerProcesses": 2, "workerEvents": events.count, "backendSHA256": evidence.state.backendSHA256]
        print(String(decoding: try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]), as: UTF8.self))
    }

    private static func copiedBytes(_ store: FixtureStore) throws -> Data {
        let copy = try store.temporaryBackendCopy()
        defer { try? FileManager.default.removeItem(at: copy) }
        return try Data(contentsOf: copy)
    }

    private static func denied(_ name: String, operation: () throws -> Void) throws {
        do {
            try operation()
            throw CheckFailure(name + " unexpectedly succeeded")
        } catch let error as NSError where error.domain == NSFileProviderErrorDomain && error.code == -1000 { }
    }

    private static func require(_ condition: @autoclosure () throws -> Bool, _ name: String) throws {
        if try !condition() { throw CheckFailure(name) }
    }

    private struct CheckFailure: Error, CustomStringConvertible {
        let description: String
        init(_ description: String) { self.description = description }
    }
}
