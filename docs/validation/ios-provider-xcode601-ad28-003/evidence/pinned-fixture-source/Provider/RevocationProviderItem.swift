import FileProvider
import Foundation
import UniformTypeIdentifiers

@available(iOS 16.0, *)
final class RevocationProviderItem: NSObject, NSFileProviderItem {
    let itemIdentifier: NSFileProviderItemIdentifier
    private let state: FixtureState

    init(identifier: NSFileProviderItemIdentifier, state: FixtureState) {
        itemIdentifier = identifier
        self.state = state
        super.init()
    }

    private var isRoot: Bool { itemIdentifier == .rootContainer }
    var parentItemIdentifier: NSFileProviderItemIdentifier { .rootContainer }
    var filename: String { isRoot ? FixtureConfiguration.displayName : FixtureConfiguration.filename }
    var contentType: UTType {
        isRoot ? .folder : UTType(importedAs: "org.logisim-evolution.circuit", conformingTo: .xml)
    }
    var capabilities: NSFileProviderItemCapabilities {
        isRoot ? [.allowsContentEnumerating] : [.allowsReading, .allowsWriting]
    }
    var documentSize: NSNumber? { isRoot ? nil : NSNumber(value: state.backendByteCount) }
    var childItemCount: NSNumber? { isRoot ? NSNumber(value: 1) : nil }
    var itemVersion: NSFileProviderItemVersion {
        NSFileProviderItemVersion(contentVersion: Data((isRoot ? "root-v1" : state.backendSHA256).utf8),
                                  metadataVersion: Data("generation-\(state.generation)".utf8))
    }
    var contentPolicy: NSFileProviderContentPolicy { .downloadLazilyAndEvictOnRemoteUpdate }
}

enum RevocationProviderError {
    static func unsupported(_ reason: String) -> NSError {
        NSError(domain: NSCocoaErrorDomain, code: NSFeatureUnsupportedError,
                userInfo: [NSLocalizedDescriptionKey: reason])
    }

    static func cancelled() -> NSError { NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError) }

    static func missing(_ identifier: NSFileProviderItemIdentifier) -> NSError {
        NSError(domain: NSFileProviderErrorDomain, code: NSFileProviderError.noSuchItem.rawValue,
                userInfo: [NSFileProviderErrorNonExistentItemIdentifierKey: identifier.rawValue,
                           NSLocalizedDescriptionKey: "The fixture has no item with this identifier."])
    }

    static func changed() -> NSError {
        NSError(domain: NSCocoaErrorDomain, code: NSFileWriteUnknownError,
                userInfo: [NSLocalizedDescriptionKey: "The fixture backend changed before this request completed."])
    }
}
