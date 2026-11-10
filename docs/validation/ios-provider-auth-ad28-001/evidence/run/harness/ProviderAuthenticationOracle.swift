import CryptoKit
import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

struct ProviderFixtureState: Codable, Equatable {
    let schemaVersion: Int
    let authenticationAllowed: Bool
    let backendSHA256: String
    let backendByteCount: Int
    let generation: Int
}

struct ProviderNSErrorDiagnostic: Codable, Equatable {
    struct Entry: Codable, Equatable {
        let domain: String
        let code: Int
        let reason: String
    }
    enum Termination: String, Codable { case complete, cycle, depthLimit }
    let chain: [Entry]
    let termination: Termination
}

struct ProviderFixtureEvent: Codable, Equatable {
    let sequence: Int64
    let timestamp: String
    let process: String
    let kind: String
    let itemIdentifier: String?
    let details: [String: String]
    let errorDomain: String?
    let errorCode: Int?
    let errorDiagnostic: ProviderNSErrorDiagnostic?
    var requestID: String? { details["requestID"] }
    var domain: String? { details["domain"] }
}

struct ProviderFixtureSnapshot: Codable, Equatable {
    let state: ProviderFixtureState
    let events: [ProviderFixtureEvent]
    var lastSequence: Int64 { events.last?.sequence ?? 0 }
}

enum ProviderAuthenticationOracle {
    static let domain = "logisim-revocation-fixture-v1"
    static let item = "revocation-circuit-v1"
    static let initialSHA256 = "54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88"

    struct Failure: Error, CustomStringConvertible {
        let description: String
    }

    struct RequestProof: Codable, Equatable {
        let kind: String
        let requestID: String
        let process: String
        let startedSequence: Int64
        let terminalSequence: Int64
    }

    static func require(_ condition: Bool, _ message: String) throws {
        if !condition { throw Failure(description: message) }
    }

    static func sha256(_ bytes: Data) -> String {
        SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }

    static func validate(_ snapshot: ProviderFixtureSnapshot, preserving previous: ProviderFixtureSnapshot? = nil) throws {
        let state = snapshot.state
        try require(state.schemaVersion == 1 && state.generation >= 1 && state.backendByteCount > 0,
                    "The public fixture state must have its expected schema and a nonempty backend")
        try require(state.backendSHA256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
                    "The public fixture backend fingerprint must be a complete SHA256")
        for (index, event) in snapshot.events.enumerated() {
            try require(event.sequence == Int64(index + 1), "Fixture event sequence was truncated, reordered or duplicated")
            try require(!event.timestamp.isEmpty && !event.process.isEmpty && !event.kind.isEmpty,
                        "Fixture events must retain time, process and kind")
            if let diagnostic = event.errorDiagnostic {
                try require(!diagnostic.chain.isEmpty && diagnostic.chain.count <= 8,
                            "The NSError diagnostic must retain its bounded nonempty chain")
                try require(diagnostic.chain.first?.domain == event.errorDomain && diagnostic.chain.first?.code == event.errorCode,
                            "The NSError chain root must agree with the original domain and code")
                try require(diagnostic.chain.allSatisfy { $0.reason.unicodeScalars.count <= 512 }, "NSError reasons exceeded the producer bound")
            }
        }
        if let previous {
            try require(snapshot.events.count >= previous.events.count &&
                        Array(snapshot.events.prefix(previous.events.count)) == previous.events,
                        "Observing the fixture must preserve every earlier event")
        }
    }

    static func initial(_ snapshot: ProviderFixtureSnapshot) throws {
        try validate(snapshot)
        try require(snapshot.state == ProviderFixtureState(schemaVersion: 1, authenticationAllowed: true,
                    backendSHA256: initialSHA256, backendByteCount: 552, generation: 1),
                    "Begin with the registered fixture's exact original authenticated backend; never reset it in this scenario")
        try require(snapshot.events.contains { $0.kind == "domain.registration.completed" && $0.errorDomain == nil && $0.errorCode == nil },
                    "The existing successful bootstrap registration must be observable before Files")
    }

    static func completedRequest(_ kind: String, after baseline: ProviderFixtureSnapshot,
                                 in snapshot: ProviderFixtureSnapshot) throws -> RequestProof {
        try validate(snapshot, preserving: baseline)
        let candidates = snapshot.events.filter {
            $0.sequence > baseline.lastSequence && $0.kind == kind + ".completed"
        }
        for terminal in candidates {
            if let proof = try requestProof(kind, terminal: terminal, suffix: ".completed", after: baseline.lastSequence, in: snapshot) {
                return proof
            }
        }
        throw Failure(description: "A real \(kind) started/completed pair for the fixture item is required")
    }

    static func fetched(after baseline: ProviderFixtureSnapshot, in snapshot: ProviderFixtureSnapshot) throws -> RequestProof {
        let proof = try completedRequest("provider.fetch", after: baseline, in: snapshot)
        try require(snapshot.state == baseline.state, "Fetching must not mutate backend bytes, generation or authentication")
        return proof
    }

    static func uploaded(xml: String, after baseline: ProviderFixtureSnapshot,
                         in snapshot: ProviderFixtureSnapshot) throws -> RequestProof {
        let proof = try completedRequest("provider.modify", after: baseline, in: snapshot)
        let bytes = Data(xml.utf8)
        let fingerprint = sha256(bytes)
        try require(snapshot.state.authenticationAllowed && snapshot.state.generation > baseline.state.generation &&
                    snapshot.state.backendSHA256 == fingerprint && snapshot.state.backendByteCount == bytes.count,
                    "Save must commit the complete observed XML to the provider backend with a newer generation")
        try require(snapshot.events.contains {
            $0.sequence > proof.startedSequence && $0.sequence < proof.terminalSequence &&
            $0.process == proof.process && $0.kind == "backend.updated" && $0.itemIdentifier == item &&
            $0.details["sha256"] == fingerprint && $0.details["bytes"] == String(bytes.count) &&
            $0.errorDomain == nil && $0.errorCode == nil
        }, "The successful upload must contain its actual backend.updated commit")
        return proof
    }

    static func evictedAndDenied(after baseline: ProviderFixtureSnapshot, in snapshot: ProviderFixtureSnapshot) throws {
        try validate(snapshot, preserving: baseline)
        try unchangedBackend(baseline.state, snapshot.state)
        try require(baseline.state.authenticationAllowed && !snapshot.state.authenticationAllowed,
                    "Deny authentication only after an authenticated upload")
        let new = snapshot.events.filter { $0.sequence > baseline.lastSequence }
        guard let requested = new.first(where: { $0.kind == "eviction.requested" && $0.itemIdentifier == item &&
                $0.details["denyAfterCompletion"] == "true" && $0.details["backendSHA256"] == baseline.state.backendSHA256 }),
              let completed = new.first(where: { $0.sequence > requested.sequence && $0.kind == "eviction.completed" &&
                $0.itemIdentifier == item && $0.errorDomain == nil && $0.errorCode == nil }),
              new.contains(where: { $0.sequence > completed.sequence && $0.kind == "authentication.changed" &&
                $0.details["allowed"] == "false" }) else {
            throw Failure(description: "Public fixture events must prove successful eviction completed before authentication denial")
        }
        try require(!new.contains { $0.kind == "eviction.failed" }, "Failed eviction cannot qualify the denied phase")
    }

    static func deniedRequest(after baseline: ProviderFixtureSnapshot, notBefore saveInvokedAt: Date, notAfter errorObservedAt: Date,
                              in snapshot: ProviderFixtureSnapshot) throws -> RequestProof {
        try validate(snapshot, preserving: baseline)
        try unchangedBackend(baseline.state, snapshot.state)
        try require(!baseline.state.authenticationAllowed && !snapshot.state.authenticationAllowed,
                    "A denied request must occur while authentication is denied")
        try require(errorObservedAt >= saveInvokedAt, "The denied Save observation window must be ordered")
        for terminal in snapshot.events where terminal.sequence > baseline.lastSequence &&
            ["provider.fetch.denied", "provider.modify.denied"].contains(terminal.kind) {
            let kind = String(terminal.kind.dropLast(".denied".count))
            if let proof = try requestProof(kind, terminal: terminal, suffix: ".denied", after: baseline.lastSequence, in: snapshot) {
                let start = snapshot.events[Int(proof.startedSequence - 1)]
                guard let startedAt = eventDate(start.timestamp), let endedAt = eventDate(terminal.timestamp),
                      startedAt >= saveInvokedAt, endedAt >= startedAt, endedAt <= errorObservedAt else { continue }
                try require(terminal.errorDomain == "NSFileProviderErrorDomain" && terminal.errorCode == -1000,
                            "The actual provider request must fail with notAuthenticated, not a stale-version conflict")
                try require(terminal.errorDiagnostic != nil,
                            "The new fixture601 denial must retain its NSError diagnostic; legacy prefix events may omit it")
                return proof
            }
        }
        throw Failure(description: "Require a new real provider.fetch/modify denial after the attempted Save; metadata denial alone is insufficient")
    }

    private static func eventDate(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }

    static func unchangedBackend(_ before: ProviderFixtureState, _ after: ProviderFixtureState) throws {
        try require(before.backendSHA256 == after.backendSHA256 && before.backendByteCount == after.backendByteCount &&
                    before.generation == after.generation, "The denied phase must preserve backend bytes and generation exactly")
    }

    private static func requestProof(_ kind: String, terminal: ProviderFixtureEvent, suffix: String,
                                     after sequence: Int64, in snapshot: ProviderFixtureSnapshot) throws -> RequestProof? {
        guard terminal.kind == kind + suffix, terminal.itemIdentifier == item, terminal.domain == domain,
              terminal.process.hasPrefix("RevocationProvider:"), let request = terminal.requestID,
              UUID(uuidString: request) != nil else { return nil }
        let starts = snapshot.events.filter {
            $0.sequence > sequence && $0.sequence < terminal.sequence && $0.kind == kind + ".started" &&
            $0.requestID == request && $0.process == terminal.process && $0.itemIdentifier == item && $0.domain == domain
        }
        try require(starts.count == 1, "A provider terminal event must have exactly one matching started request")
        try require(eventDate(starts[0].timestamp).map { started in
            eventDate(terminal.timestamp).map { $0 >= started } ?? false
        } ?? false, "A provider request must retain valid, ordered start and terminal timestamps")
        let terminals = snapshot.events.filter { event in event.requestID == request &&
            [".completed", ".denied", ".failed", ".cancelled"].contains(where: { terminalSuffix in event.kind == kind + terminalSuffix }) }
        try require(terminals.count == 1, "A provider request cannot have contradictory terminal events")
        if suffix == ".completed" {
            try require(terminal.errorDomain == nil && terminal.errorCode == nil, "A completed request cannot contain an NSError")
        }
        return RequestProof(kind: kind, requestID: request, process: terminal.process,
                            startedSequence: starts[0].sequence, terminalSequence: terminal.sequence)
    }
}

struct ProviderCircuitDocument: Equatable {
    struct Element: Equatable {
        let name: String
        let attributes: [String: String]
        var children: [Element]
        var text: String

        func removingInputFacing() -> Element {
            var result = self
            result.children = children.map { $0.removingInputFacing() }
            if name == "comp" && children.contains(where: {
                $0.name == "a" && $0.attributes["name"] == "label" && $0.attributes["val"] == "REVOCATION_INPUT"
            }) {
                result.children.removeAll { $0.name == "a" && $0.attributes["name"] == "facing" }
            }
            return result
        }
    }
    struct Component: Equatable {
        let library: String
        let location: String
        let kind: String
        var attributes: [String: String]
    }
    let main: String
    let circuit: String
    let libraries: [String: String]
    let components: [Component]
    let wires: [[String: String]]
    let tree: Element

    static func read(_ xml: String) throws -> ProviderCircuitDocument {
        let delegate = CircuitParser()
        let parser = XMLParser(data: Data(xml.utf8))
        parser.shouldResolveExternalEntities = false
        parser.delegate = delegate
        guard parser.parse(), delegate.problem == nil, let root = delegate.root, root.name == "project" else {
            throw ProviderAuthenticationOracle.Failure(description:
                "The complete editor XML must be well-formed and contain one circuit: \(delegate.problem ?? parser.parserError?.localizedDescription ?? "unknown")")
        }
        return ProviderCircuitDocument(main: delegate.main, circuit: delegate.circuit, libraries: delegate.libraries,
                                       components: delegate.components, wires: delegate.wires, tree: root)
    }

    func requireFixture(facing: String) throws {
        try ProviderAuthenticationOracle.require(main == "provider_revocation" && circuit == main &&
            libraries["0"] == "#Wiring" && components.count == 2 && wires == [["from": "(100,100)", "to": "(200,100)"]],
            "The observed project must retain the fixture's circuit, two pins and exact wire")
        let inputs = components.filter { $0.attributes["label"] == "REVOCATION_INPUT" }
        let outputs = components.filter { $0.attributes["label"] == "REVOCATION_OUTPUT" }
        try ProviderAuthenticationOracle.require(inputs.count == 1 && outputs.count == 1, "The fixture pin labels must remain unique")
        let input = inputs[0], output = outputs[0]
        try ProviderAuthenticationOracle.require(input.kind == "Pin" && input.library == "0" && input.location == "(100,100)" &&
            input.attributes["type"] == "input" && input.attributes["width"] == "1" &&
            (input.attributes["facing"] ?? "east") == facing && output.kind == "Pin" && output.library == "0" &&
            output.location == "(200,100)" && output.attributes["type"] == "output" && output.attributes["width"] == "1",
            "Facing edits must preserve pin identity, type, location and width")
    }

    func requireOnlyFacingChanged(from before: ProviderCircuitDocument) throws {
        func withoutFacing(_ document: ProviderCircuitDocument) -> ProviderCircuitDocument {
            var pins = document.components
            for index in pins.indices where pins[index].attributes["label"] == "REVOCATION_INPUT" {
                pins[index].attributes.removeValue(forKey: "facing")
            }
            return ProviderCircuitDocument(main: document.main, circuit: document.circuit, libraries: document.libraries,
                                           components: pins, wires: document.wires, tree: document.tree.removingInputFacing())
        }
        try ProviderAuthenticationOracle.require(withoutFacing(self) == withoutFacing(before),
            "The second enum edit must change only the input's facing attribute")
    }

    private final class CircuitParser: NSObject, XMLParserDelegate {
        var main = "", circuit = "", libraries: [String: String] = [:]
        var components: [Component] = [], wires: [[String: String]] = []
        var problem: String?
        var root: Element?
        private var stack: [String] = []
        private var nodes: [Element] = []
        private var circuitCount = 0
        func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?,
                    attributes: [String: String]) {
            let parent = stack.last
            stack.append(name)
            nodes.append(Element(name: name, attributes: attributes, children: [], text: ""))
            switch (parent, name) {
            case (nil, "project"): break
            case ("project"?, "main"):
                if !main.isEmpty { problem = "duplicate main" }; main = attributes["name"] ?? ""
            case ("project"?, "lib"):
                if let id = attributes["name"], let desc = attributes["desc"], libraries[id] == nil { libraries[id] = desc }
                else { problem = "duplicate or malformed library" }
            case ("project"?, "circuit"):
                circuitCount += 1
                if circuitCount != 1 { problem = "multiple circuits" }; circuit = attributes["name"] ?? ""
            case ("circuit"?, "comp"):
                components.append(Component(library: attributes["lib"] ?? "", location: attributes["loc"] ?? "",
                                             kind: attributes["name"] ?? "", attributes: [:]))
            case ("comp"?, "a"):
                guard let key = attributes["name"], let value = attributes["val"], !components.isEmpty,
                      components[components.count - 1].attributes[key] == nil else { problem = "malformed pin attribute"; return }
                components[components.count - 1].attributes[key] = value
            case ("circuit"?, "wire"): wires.append(attributes)
            default: break
            }
        }
        func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
            _ = stack.popLast()
            guard var node = nodes.popLast() else { problem = "unbalanced XML tree"; return }
            if node.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { node.text = "" }
            if nodes.isEmpty { root = node } else { nodes[nodes.count - 1].children.append(node) }
        }
        func parser(_ parser: XMLParser, foundCharacters string: String) {
            if !nodes.isEmpty { nodes[nodes.count - 1].text += string }
        }
        func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
            if let value = String(data: CDATABlock, encoding: .utf8), !nodes.isEmpty { nodes[nodes.count - 1].text += value }
        }
        func parser(_ parser: XMLParser, resolveExternalEntityName name: String, systemID: String?) -> Data? {
            problem = "external entity"; return nil
        }
    }
}
