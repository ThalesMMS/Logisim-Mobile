import Foundation

/// Observation only. Raw masks follow the iPhoneSimulator 27.2 FileProvider headers.
enum RevocationModifyDiagnostics {
    private static let fields: [(UInt, String)] = [
        (1 << 0, "contents"),
        (1 << 1, "filename"),
        (1 << 2, "parentItemIdentifier"),
        (1 << 3, "lastUsedDate"),
        (1 << 4, "tagData"),
        (1 << 5, "favoriteRank"),
        (1 << 6, "creationDate"),
        (1 << 7, "contentModificationDate"),
        (1 << 8, "fileSystemFlags"),
        (1 << 9, "extendedAttributes"),
        (1 << 10, "typeAndCreator"),
    ]

    static func details(changedFieldsRaw: UInt, newContentsPresent: Bool, optionsRaw: UInt,
                        requestedContentPolicyRaw: Int?, fixtureDeclaredContentPolicyRaw: Int) -> [String: String] {
        let knownMask = fields.reduce(UInt(0)) { $0 | $1.0 }
        return [
            "changedFieldsRaw": String(changedFieldsRaw),
            "changedFieldNames": fields.filter { changedFieldsRaw & $0.0 != 0 }.map(\.1).joined(separator: ","),
            "changedFieldsUnknownMask": String(changedFieldsRaw & ~knownMask),
            "newContentsPresent": String(newContentsPresent),
            "modifyOptionsRaw": String(optionsRaw),
            "requestedContentPolicyPresent": String(requestedContentPolicyRaw != nil),
            "requestedContentPolicyRaw": requestedContentPolicyRaw.map(String.init) ?? "absent",
            "requestedContentPolicyName": requestedContentPolicyRaw.map(policyName) ?? "absent",
            "fixtureDeclaredContentPolicyRaw": String(fixtureDeclaredContentPolicyRaw),
            "fixtureDeclaredContentPolicyName": policyName(fixtureDeclaredContentPolicyRaw),
        ]
    }

    private static func policyName(_ rawValue: Int) -> String {
        switch rawValue {
        case 0: return "inherited"
        case 1: return "downloadLazily"
        case 2: return "downloadLazilyAndEvictOnRemoteUpdate"
        case 3: return "downloadEagerlyAndKeepDownloaded"
        default: return "unknown"
        }
    }
}
