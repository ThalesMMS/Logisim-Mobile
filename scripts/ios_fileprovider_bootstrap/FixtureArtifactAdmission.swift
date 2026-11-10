import CryptoKit
import Foundation

/// Admission for one reviewed local Simulator fixture, not a signing profile or release approval.
enum FixtureArtifactAdmission {
    static let manifestSHA256 = "c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159"
    static let reviewSHA256 = "254c199cefd0f1659825d8c81bae5ea1504735265314ddeea33bbd3ff22a8ae8"
    private static let source = "601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd"
    private static let appID = "thalesmms.logisim.validation.revocationfixture"
    private static let group = "group.thalesmms.logisim.validation.revocationfixture"
    private static let applicationIdentifierPrefix = "P4VGX44GD8."
    private static let appTree = "e24e3fb58b07439ba1f9e50e955c76db72509dbf07ba0d8ea19ecce7f3f038d5"
    private static let providerTree = "e91733f45ac08a55374bce04083b333004ae4eda1d7bd83f758f84956ecc9f7f"

    static func validate(manifest: Data, review: Data) throws {
        try require(hash(manifest) == manifestSHA256, "producer manifest checksum")
        try require(hash(review) == reviewSHA256, "internal review checksum")
        let producer = try JSONDecoder().decode(Producer.self, from: manifest)
        let approval = try JSONDecoder().decode(Review.self, from: review)
        try require(producer.source == source && approval.source == source, "source pin")
        try require(producer.status == "BUILD_AND_ADHOC_SIGN_PASS", "producer status")
        try require(producer.nativeExecution == "NOT_RUN" && producer.domainRegistration == "NOT_RUN", "producer execution scope")
        try require(!producer.physicalDevice && producer.simulatorEntitlementReviewRequired, "Simulator review required")
        try require(producer.appGroup == group, "fixture group")
        try require(approval.schemaVersion == 1 && approval.reviewKind == "ROOT_INTERNAL_SIMULATOR_ADMISSION", "internal review kind")
        try require(approval.approvalAuthority == "ROOT" && approval.scope == "LOCAL_SIMULATOR_ONLY", "internal approval scope")
        try require(approval.operation == "ONE_PREPARE_REGISTER_INSPECT", "single bootstrap operation")
        try require(!approval.developerTeamApproved && !approval.releaseApproved, "no developer-team or release approval")
        try require(approval.applicationIdentifierPrefix == applicationIdentifierPrefix, "Simulator application identifier prefix")
        try require(approval.manifestSHA256 == manifestSHA256, "review manifest pin")
        try require(approval.appTreeSHA256 == appTree && approval.providerTreeSHA256 == providerTree, "review tree pins")
        try require(Set(producer.simulatorEntitlements.keys) == ["App", "Provider"], "producer roles")
        try require(Set(approval.approvedSimulatedEntitlements.keys) == ["App", "Provider"], "review roles")
        for (name, artifact, bundleID, tree) in [("App", producer.app, appID, appTree),
                                                ("Provider", producer.provider, appID + ".provider", providerTree)] {
            try require(artifact.bundle_id == bundleID && artifact.bundle_tree_sha256 == tree, name + " identity")
            guard let role = producer.simulatorEntitlements[name], let approved = approval.approvedSimulatedEntitlements[name] else {
                throw Rejection.invalid(name + " missing review")
            }
            try require(role.bundleIdentifier == bundleID, name + " role bundle")
            try require(role.sectionOwners == ["main"] && role.mainHasSectionPair, name + " main section ownership")
            try require(role.nativeExecution == "NOT_RUN" && role.generatedEntitlementsReview == "REQUIRED_BEFORE_NATIVE", name + " review status")
            try require(role.simulatedEntitlements == approved, name + " reviewed entitlements")
            try require(approved.applicationIdentifier == applicationIdentifierPrefix + bundleID && approved.appGroups == [group], name + " exact entitlement values")
            try require(role.generatedAdditionalKeys.applicationIdentifier == approved.applicationIdentifier, name + " generated application identifier")
            let main = role.images.filter { $0.image == "main" }
            let debug = role.images.filter { $0.image == "debug-dylib" }
            try require(main.count == 1 && debug.count == 1 && role.images.count == 2, name + " image roles")
            try require(main[0].sha256 == artifact.executable_sha256, name + " main executable pin")
            try require(Set(main[0].sections.keys) == ["__entitlements", "__ents_der"] && debug[0].sections.isEmpty, name + " exact section pair")
            try require(Set(role.packagingInputs.keys) == ["__entitlements", "__ents_der"], name + " packaging inputs")
            for section in ["__entitlements", "__ents_der"] {
                guard let embedded = main[0].sections[section], let packaged = role.packagingInputs[section] else {
                    throw Rejection.invalid(name + " missing section evidence")
                }
                try require(embedded.bytes > 0 && embedded.bytes == packaged.bytes && embedded.sha256 == packaged.sha256,
                            name + " section packaging match")
            }
        }
    }

    private static func hash(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func require(_ condition: Bool, _ field: String) throws {
        if !condition { throw Rejection.invalid(field) }
    }

    private enum Rejection: Error { case invalid(String) }
    private struct Producer: Decodable {
        struct Artifact: Decodable { let bundle_id: String; let bundle_tree_sha256: String; let executable_sha256: String }
        let source: String
        let status: String
        let appGroup: String
        let nativeExecution: String
        let domainRegistration: String
        let physicalDevice: Bool
        let simulatorEntitlementReviewRequired: Bool
        let app: Artifact
        let provider: Artifact
        let simulatorEntitlements: [String: SimulatorRole]
    }
    private struct SimulatorRole: Decodable {
        struct Section: Decodable { let bytes: Int; let sha256: String }
        struct Image: Decodable { let image: String; let sha256: String; let sections: [String: Section] }
        let bundleIdentifier: String
        let sectionOwners: [String]
        let mainHasSectionPair: Bool
        let nativeExecution: String
        let generatedEntitlementsReview: String
        let simulatedEntitlements: Entitlements
        let generatedAdditionalKeys: GeneratedKeys
        let codeSignatureEntitlements: EmptyObject
        let images: [Image]
        let packagingInputs: [String: Section]
    }
    private struct Review: Decodable {
        let schemaVersion: Int
        let reviewKind: String
        let approvalAuthority: String
        let scope: String
        let operation: String
        let source: String
        let manifestSHA256: String
        let appTreeSHA256: String
        let providerTreeSHA256: String
        let applicationIdentifierPrefix: String
        let developerTeamApproved: Bool
        let releaseApproved: Bool
        let approvedSimulatedEntitlements: [String: Entitlements]
    }
    private struct AnyKey: CodingKey {
        let stringValue: String
        let intValue: Int? = nil
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { return nil }
    }
    private static func exactKeys(_ decoder: Decoder, _ expected: Set<String>) throws {
        let container = try decoder.container(keyedBy: AnyKey.self)
        guard Set(container.allKeys.map(\.stringValue)) == expected else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Unexpected entitlement dictionary keys"))
        }
    }
    private struct Entitlements: Decodable, Equatable {
        let applicationIdentifier: String
        let appGroups: [String]
        private enum CodingKeys: String, CodingKey {
            case applicationIdentifier = "application-identifier"
            case appGroups = "com.apple.security.application-groups"
        }
        init(from decoder: Decoder) throws {
            try FixtureArtifactAdmission.exactKeys(decoder, ["application-identifier", "com.apple.security.application-groups"])
            let container = try decoder.container(keyedBy: CodingKeys.self)
            applicationIdentifier = try container.decode(String.self, forKey: .applicationIdentifier)
            appGroups = try container.decode([String].self, forKey: .appGroups)
        }
    }
    private struct GeneratedKeys: Decodable {
        let applicationIdentifier: String
        private enum CodingKeys: String, CodingKey { case applicationIdentifier = "application-identifier" }
        init(from decoder: Decoder) throws {
            try FixtureArtifactAdmission.exactKeys(decoder, ["application-identifier"])
            applicationIdentifier = try decoder.container(keyedBy: CodingKeys.self).decode(String.self, forKey: .applicationIdentifier)
        }
    }
    private struct EmptyObject: Decodable {
        init(from decoder: Decoder) throws { try FixtureArtifactAdmission.exactKeys(decoder, []) }
    }
}
