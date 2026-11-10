import Foundation

@main enum ModifyDiagnosticsChecks {
    static func main() {
        var count = 0
        func expect(_ condition: @autoclosure () -> Bool) {
            precondition(condition())
            count += 1
        }
        func details(_ fields: UInt, contents: Bool = false, options: UInt = 0, policy: Int? = nil) -> [String: String] {
            RevocationModifyDiagnostics.details(changedFieldsRaw: fields, newContentsPresent: contents,
                                                optionsRaw: options, requestedContentPolicyRaw: policy,
                                                fixtureDeclaredContentPolicyRaw: 2)
        }
        let empty = details(0)
        expect(empty["changedFieldsRaw"] == "0")
        expect(empty["changedFieldNames"] == "")
        expect(empty["changedFieldsUnknownMask"] == "0")
        expect(empty["newContentsPresent"] == "false")
        expect(empty["modifyOptionsRaw"] == "0")
        expect(empty["requestedContentPolicyPresent"] == "false")
        expect(empty["requestedContentPolicyRaw"] == "absent")
        expect(empty["requestedContentPolicyName"] == "absent")
        expect(empty["fixtureDeclaredContentPolicyRaw"] == "2")
        expect(empty["fixtureDeclaredContentPolicyName"] == "downloadLazilyAndEvictOnRemoteUpdate")

        let names = ["contents", "filename", "parentItemIdentifier", "lastUsedDate", "tagData", "favoriteRank",
                     "creationDate", "contentModificationDate", "fileSystemFlags", "extendedAttributes", "typeAndCreator"]
        for (bit, name) in names.enumerated() {
            let raw = UInt(1) << bit
            let single = details(raw)
            expect(single["changedFieldsRaw"] == String(raw))
            expect(single["changedFieldNames"] == name)
            expect(single["changedFieldsUnknownMask"] == "0")
        }
        let all = details(2047, contents: true, options: 7, policy: 0)
        expect(all["changedFieldNames"] == names.joined(separator: ","))
        expect(all["changedFieldsUnknownMask"] == "0")
        expect(all["newContentsPresent"] == "true")
        expect(all["modifyOptionsRaw"] == "7")
        expect(all["requestedContentPolicyPresent"] == "true")
        expect(all["requestedContentPolicyRaw"] == "0")
        expect(all["requestedContentPolicyName"] == "inherited")
        expect(all != empty)

        let unknown = UInt(1) << (UInt.bitWidth - 1)
        let mixed = details(unknown | 9, options: unknown)
        expect(mixed["changedFieldsRaw"] == String(unknown | 9))
        expect(mixed["changedFieldNames"] == "contents,lastUsedDate")
        expect(mixed["changedFieldsUnknownMask"] == String(unknown))
        expect(mixed["modifyOptionsRaw"] == String(unknown))
        expect(details(UInt.max)["changedFieldsUnknownMask"] == String(UInt.max & ~UInt(2047)))
        for (raw, name) in [(0, "inherited"), (1, "downloadLazily"), (2, "downloadLazilyAndEvictOnRemoteUpdate"),
                            (3, "downloadEagerlyAndKeepDownloaded"), (-1, "unknown"), (99, "unknown")] {
            let policy = details(0, policy: raw)
            expect(policy["requestedContentPolicyPresent"] == "true")
            expect(policy["requestedContentPolicyRaw"] == String(raw))
            expect(policy["requestedContentPolicyName"] == name)
        }
        // These combinations stay distinct. Diagnostics must not classify a missing URL as metadata-only.
        expect(details(1, contents: false)["changedFieldNames"] == "contents")
        expect(details(1, contents: false)["newContentsPresent"] == "false")
        expect(details(8, contents: true)["changedFieldNames"] == "lastUsedDate")
        expect(details(8, contents: true)["newContentsPresent"] == "true")
        expect(Set(empty.keys) == ["changedFieldsRaw", "changedFieldNames", "changedFieldsUnknownMask",
                                  "newContentsPresent", "modifyOptionsRaw", "requestedContentPolicyPresent",
                                  "requestedContentPolicyRaw", "requestedContentPolicyName",
                                  "fixtureDeclaredContentPolicyRaw", "fixtureDeclaredContentPolicyName"])
        print("Modify diagnostics: \(count) checks PASS; Native NOT_RUN")
    }
}
