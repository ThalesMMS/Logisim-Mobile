import Foundation

@main
enum FixtureErrorDiagnosticChecks {
    private typealias Entry = FixtureErrorDiagnostic.Entry
    private typealias Termination = FixtureErrorDiagnostic.Termination
    private static let rootEntry = Entry(domain: "NSFileProviderErrorDomain", code: -2001,
                                         reason: "No provider was found.")

    static func main() throws {
        let checks: [(String, () throws -> Void)] = [
            ("root domain, code and failure-reason precedence", rootMetadata),
            ("localized-description fallback and empty reason", reasonFallback),
            ("underlying -2012/-2013/-2014 beneath root -2001", underlyingProviderCodes),
            ("absent and non-error underlying values", nonErrorUnderlyingValues),
            ("Swift Error bridging", bridgedSwiftError),
            ("distinct identities with equal diagnostic fields", equalFieldsAreNotACycle),
            ("self cycle", selfCycle),
            ("two-node cycle", twoNodeCycle),
            ("seven/eight complete entries and ninth-entry depth limit", depthBoundary),
            ("cycle detection at the eighth entry", cycleAtDepthBoundary),
            ("512-scalar reason limit including one oversized grapheme", reasonLimit),
            ("literal Codable payload and round trip", codablePayload)
        ]
        var completed: [String] = []
        for (name, check) in checks {
            try check()
            completed.append(name)
        }
        let report: [String: Any] = [
            "hostErrorDiagnostic": "PASS",
            "nativeExecution": "NOT_RUN",
            "scope": "Foundation NSError capture and Codable only; no FileProvider domain, UI or registration.",
            "checks": completed
        ]
        let bytes = try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
        print(String(decoding: bytes, as: UTF8.self))
    }

    private static func rootMetadata() throws {
        let diagnostic = FixtureErrorDiagnostic(error: root())
        try expect(diagnostic, entries: [rootEntry], name: "Root domain/code/reason")
        _ = try encodedPayload(diagnostic)
    }

    private static func reasonFallback() throws {
        let fallback = error(domain: "DescriptionDomain", code: 7, description: "Fallback description.")
        try expect(FixtureErrorDiagnostic(error: fallback), entries: [
            Entry(domain: "DescriptionDomain", code: 7, reason: "Fallback description.")
        ], name: "Description is used when failure reason is absent")
        let empty = error(domain: "DescriptionDomain", code: 8, reason: "", description: "Do not substitute this.")
        try expect(FixtureErrorDiagnostic(error: empty), entries: [
            Entry(domain: "DescriptionDomain", code: 8, reason: "")
        ], name: "An explicitly empty failure reason is preserved")
    }

    private static func underlyingProviderCodes() throws {
        for code in [-2012, -2013, -2014] {
            let leaf = error(domain: "NSFileProviderErrorDomain", code: code, reason: "Underlying \(code).")
            try expect(FixtureErrorDiagnostic(error: root(underlying: leaf)), entries: [
                rootEntry, Entry(domain: "NSFileProviderErrorDomain", code: code, reason: "Underlying \(code).")
            ], name: "Root -2001 retains underlying \(code)")
        }
    }

    private static func nonErrorUnderlyingValues() throws {
        let values: [Any?] = [nil, NSNull(), "NOT_AN_ERROR_PAYLOAD", 42]
        for value in values {
            let diagnostic = FixtureErrorDiagnostic(error: root(underlying: value))
            try expect(diagnostic, entries: [rootEntry], name: "Only Error/NSError links are traversed")
            _ = try encodedPayload(diagnostic)
        }
    }

    private static func bridgedSwiftError() throws {
        try expect(FixtureErrorDiagnostic(error: root(underlying: SwiftUnderlyingError.leaf)), entries: [
            rootEntry, Entry(domain: "SwiftDiagnosticDomain", code: -2013, reason: "Swift underlying reason.")
        ], name: "A Swift Error preserves its bridged NSError fields")
    }

    private static func equalFieldsAreNotACycle() throws {
        let first = LinkedDiagnosticError(domain: "EqualDomain", code: 11, reason: "Equal fields.")
        let second = LinkedDiagnosticError(domain: "EqualDomain", code: 11, reason: "Equal fields.")
        first.next = second
        try expect(FixtureErrorDiagnostic(error: first), entries: [
            Entry(domain: "EqualDomain", code: 11, reason: "Equal fields."),
            Entry(domain: "EqualDomain", code: 11, reason: "Equal fields.")
        ], name: "Equal values on different objects do not terminate the chain")
    }

    private static func selfCycle() throws {
        let value = LinkedDiagnosticError(domain: "SelfCycleDomain", code: 1, reason: "Self cycle.")
        value.next = value
        defer { value.next = nil }
        let diagnostic = FixtureErrorDiagnostic(error: value)
        try expect(diagnostic, entries: [Entry(domain: "SelfCycleDomain", code: 1, reason: "Self cycle.")],
                   termination: .cycle, name: "Self cycle emits one entry")
        try require(try encodedPayload(diagnostic)["termination"] as? String == "cycle", "Cycle Codable value")
    }

    private static func twoNodeCycle() throws {
        let first = LinkedDiagnosticError(domain: "FirstCycleDomain", code: 1, reason: "First node.")
        let second = LinkedDiagnosticError(domain: "SecondCycleDomain", code: 2, reason: "Second node.")
        first.next = second
        second.next = first
        defer { first.next = nil; second.next = nil }
        try expect(FixtureErrorDiagnostic(error: first), entries: [
            Entry(domain: "FirstCycleDomain", code: 1, reason: "First node."),
            Entry(domain: "SecondCycleDomain", code: 2, reason: "Second node.")
        ], termination: .cycle, name: "A two-node cycle emits each identity once")
    }

    private static func depthBoundary() throws {
        let cases: [(Int, Int, Termination, String)] = [
            (7, 7, .complete, "complete"), (8, 8, .complete, "complete"), (9, 8, .depthLimit, "depthLimit")
        ]
        for (length, retained, termination, encodedTermination) in cases {
            let nodes = linkedErrors(count: length)
            let diagnostic = FixtureErrorDiagnostic(error: nodes[0])
            let expected = (1...retained).map { Entry(domain: "DepthDomain", code: $0, reason: "Depth \($0).") }
            try expect(diagnostic, entries: expected, termination: termination, name: "Chain with \(length) nodes")
            try require(try encodedPayload(diagnostic)["termination"] as? String == encodedTermination,
                        "Depth termination Codable value")
        }
    }

    private static func cycleAtDepthBoundary() throws {
        let nodes = linkedErrors(count: 8)
        nodes[7].next = nodes[0]
        defer { for node in nodes { node.next = nil } }
        try expect(FixtureErrorDiagnostic(error: nodes[0]), entries:
            (1...8).map { Entry(domain: "DepthDomain", code: $0, reason: "Depth \($0).") },
            termination: .cycle, name: "Revisiting an identity at depth eight is a cycle")
    }

    private static func reasonLimit() throws {
        let boundary = String(repeating: "x", count: 512)
        let exact = FixtureErrorDiagnostic(error: error(domain: "TextDomain", code: 1, reason: boundary))
        try expect(exact, entries: [Entry(domain: "TextDomain", code: 1, reason: boundary)],
                   name: "Exactly 512 scalars are retained")

        let emojiPrefix = String(repeating: "😀", count: 512)
        let emoji = FixtureErrorDiagnostic(error: error(domain: "TextDomain", code: 2, reason: emojiPrefix + "😀"))
        try expect(emoji, entries: [Entry(domain: "TextDomain", code: 2, reason: emojiPrefix)],
                   name: "513 non-ASCII scalars are bounded without splitting a scalar")
        try require(emoji.chain[0].reason.utf8.count == 2048, "Four-byte scalars retain a bounded valid string")

        let combiningPrefix = "a" + String(repeating: "\u{0301}", count: 511)
        let combining = FixtureErrorDiagnostic(error: error(domain: "TextDomain", code: 3,
                                                           reason: combiningPrefix + "\u{0301}"))
        try expect(combining, entries: [Entry(domain: "TextDomain", code: 3, reason: combiningPrefix)],
                   name: "A single grapheme cannot bypass the scalar limit")
        try require(combining.chain[0].reason.unicodeScalars.count == 512, "Combining-mark scalar bound")
        let encoded = try JSONEncoder().encode(combining)
        try require(try JSONDecoder().decode(FixtureErrorDiagnostic.self, from: encoded) == combining,
                    "Bounded Unicode survives Codable")
    }

    private static func codablePayload() throws {
        let literal = Data("""
        {"chain":[{"domain":"NSFileProviderErrorDomain","code":-2001,"reason":"No provider was found."},
        {"domain":"NSCocoaErrorDomain","code":13,"reason":"Local access was denied."}],"termination":"complete"}
        """.utf8)
        let decoded = try JSONDecoder().decode(FixtureErrorDiagnostic.self, from: literal)
        let expected = [rootEntry, Entry(domain: "NSCocoaErrorDomain", code: 13, reason: "Local access was denied.")]
        try expect(decoded, entries: expected, name: "Decode literal chain and termination")
        let leaf = error(domain: "NSCocoaErrorDomain", code: 13, reason: "Local access was denied.")
        let captured = FixtureErrorDiagnostic(error: root(underlying: leaf))
        try require(captured == decoded, "Captured fields match the literal Codable fixture")
        let payload = try encodedPayload(captured)
        try require(payload["termination"] as? String == "complete", "Complete Codable value")
        guard let entries = payload["chain"] as? [[String: Any]] else { throw CheckFailure("JSON chain array") }
        try require(entries.count == 2, "JSON retains both errors")
        for (entry, expectedEntry) in zip(entries, expected) {
            try require(Set(entry.keys) == Set(["domain", "code", "reason"]), "JSON entry has only diagnostic fields")
            try require(entry["domain"] as? String == expectedEntry.domain, "JSON domain")
            try require(entry["code"] as? Int == expectedEntry.code, "JSON numeric code")
            try require(entry["reason"] as? String == expectedEntry.reason, "JSON reason")
        }
        let encoded = try JSONEncoder().encode(captured)
        try require(try JSONDecoder().decode(FixtureErrorDiagnostic.self, from: encoded) == captured,
                    "Encoded chain round trip")
    }

    private static func error(domain: String, code: Int, reason: String? = nil,
                              description: String = "Description must not replace the failure reason.",
                              underlying: Any? = nil) -> NSError {
        var info: [String: Any] = [NSLocalizedDescriptionKey: description, "secretKey": "DO_NOT_SERIALIZE_SECRET"]
        if let reason { info[NSLocalizedFailureReasonErrorKey] = reason }
        if let underlying { info[NSUnderlyingErrorKey] = underlying }
        return NSError(domain: domain, code: code, userInfo: info)
    }

    private static func root(underlying: Any? = nil) -> NSError {
        error(domain: "NSFileProviderErrorDomain", code: -2001, reason: "No provider was found.", underlying: underlying)
    }

    private static func linkedErrors(count: Int) -> [LinkedDiagnosticError] {
        let nodes = (1...count).map { LinkedDiagnosticError(domain: "DepthDomain", code: $0, reason: "Depth \($0).") }
        for index in 0..<(count - 1) { nodes[index].next = nodes[index + 1] }
        return nodes
    }

    private static func encodedPayload(_ diagnostic: FixtureErrorDiagnostic) throws -> [String: Any] {
        let encoded = try JSONEncoder().encode(diagnostic)
        let text = String(decoding: encoded, as: UTF8.self)
        try require(!text.contains("secretKey") && !text.contains("DO_NOT_SERIALIZE_SECRET") &&
                    !text.contains("NOT_AN_ERROR_PAYLOAD"), "Arbitrary userInfo is absent from diagnostic JSON")
        guard let payload = try JSONSerialization.jsonObject(with: encoded) as? [String: Any] else {
            throw CheckFailure("Encoded diagnostic is a JSON object")
        }
        try require(Set(payload.keys) == Set(["chain", "termination"]), "JSON contains only chain and termination")
        return payload
    }

    private static func expect(_ diagnostic: FixtureErrorDiagnostic, entries: [Entry],
                               termination: Termination = .complete, name: String) throws {
        try require(diagnostic.chain == entries, name + ": complete domain/code/reason entries")
        try require(diagnostic.termination == termination, name + ": termination")
    }

    private static func require(_ condition: @autoclosure () throws -> Bool, _ name: String) throws {
        if try !condition() { throw CheckFailure(name) }
    }

    private struct CheckFailure: Error, CustomStringConvertible {
        let description: String
        init(_ description: String) { self.description = description }
    }

    private enum SwiftUnderlyingError: Int, Error, CustomNSError {
        case leaf = -2013
        static var errorDomain: String { "SwiftDiagnosticDomain" }
        var errorCode: Int { rawValue }
        var errorUserInfo: [String: Any] {
            [NSLocalizedDescriptionKey: "Swift underlying description.",
             NSLocalizedFailureReasonErrorKey: "Swift underlying reason."]
        }
    }

    private final class LinkedDiagnosticError: NSError, @unchecked Sendable {
        var next: NSError?
        private let reason: String

        init(domain: String, code: Int, reason: String) {
            self.reason = reason
            super.init(domain: domain, code: code, userInfo: nil)
        }

        required init?(coder: NSCoder) { return nil }
        override var localizedFailureReason: String? { reason }
        override var localizedDescription: String { reason }
        override var userInfo: [String: Any] {
            var info: [String: Any] = [NSLocalizedFailureReasonErrorKey: reason]
            if let next { info[NSUnderlyingErrorKey] = next }
            return info
        }
    }
}
