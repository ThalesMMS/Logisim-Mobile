import Foundation

struct FixtureErrorDiagnostic: Codable, Equatable {
    struct Entry: Codable, Equatable {
        let domain: String
        let code: Int
        let reason: String
    }

    enum Termination: String, Codable {
        case complete
        case cycle
        case depthLimit
    }

    static let maximumDepth = 8
    static let maximumReasonUnicodeScalars = 512

    let chain: [Entry]
    let termination: Termination

    init(error: NSError) {
        var chain: [Entry] = []
        var visited: [ObjectIdentifier: NSError] = [:]
        var current: NSError? = error
        var termination = Termination.complete

        while let value = current {
            let identity = ObjectIdentifier(value)
            if visited[identity] != nil {
                termination = .cycle
                break
            }
            if chain.count == Self.maximumDepth {
                termination = .depthLimit
                break
            }
            // Retain visited errors so identity cannot be reused while traversing a bridged chain.
            visited[identity] = value
            let reason = value.localizedFailureReason ?? value.localizedDescription
            var boundedReason = ""
            for scalar in reason.unicodeScalars.prefix(Self.maximumReasonUnicodeScalars) {
                boundedReason.unicodeScalars.append(scalar)
            }
            chain.append(Entry(domain: value.domain, code: value.code,
                               reason: boundedReason))

            // Only the standard underlying-error link is inspected; other userInfo is not serialized.
            let underlying = value.userInfo[NSUnderlyingErrorKey]
            if let underlying = underlying as? NSError {
                current = underlying
            } else if let underlying = underlying as? Error {
                current = underlying as NSError
            } else {
                current = nil
            }
        }

        self.chain = chain
        self.termination = termination
    }
}
