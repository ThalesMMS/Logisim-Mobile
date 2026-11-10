import CryptoKit
import FileProvider
import Foundation

@available(iOS 16.0, *)
final class RevocationFileProvider: NSObject, NSFileProviderReplicatedExtension {
    private typealias ItemResult = (NSFileProviderItem?, Error?)
    private typealias FetchResult = (URL?, NSFileProviderItem?, Error?)
    private typealias MutationResult = (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?)
    private let domain: NSFileProviderDomain
    private let storeResult: Result<FixtureStore, Error>
    private let manager: NSFileProviderManager?
    private let queue = DispatchQueue(label: "dev.logisim.validation.revocation-provider", qos: .utility, attributes: .concurrent)
    private let lock = NSLock()
    private var invalidated = false
    private var fileIdentifier: NSFileProviderItemIdentifier {
        NSFileProviderItemIdentifier(rawValue: FixtureConfiguration.itemIdentifier)
    }

    required init(domain: NSFileProviderDomain) {
        self.domain = domain
        manager = NSFileProviderManager(for: domain)
        if domain.identifier.rawValue == FixtureConfiguration.domainIdentifier {
            storeResult = Result { try FixtureStore() }
        } else {
            storeResult = .failure(RevocationProviderError.unsupported("The fixture only serves its own registered domain."))
        }
        super.init()
        record("provider.initialized")
    }

    func invalidate() {
        lock.lock()
        invalidated = true
        lock.unlock()
        record("provider.invalidated")
    }

    func enumerator(for containerItemIdentifier: NSFileProviderItemIdentifier,
                    request: NSFileProviderRequest) throws -> NSFileProviderEnumerator {
        let store = try activeStore()
        guard containerItemIdentifier != .trashContainer else {
            throw RevocationProviderError.unsupported("The fixture does not support trash.")
        }
        guard [.rootContainer, .workingSet, fileIdentifier].contains(containerItemIdentifier) else {
            throw RevocationProviderError.missing(containerItemIdentifier)
        }
        try store.record("provider.enumerator.created", itemIdentifier: containerItemIdentifier.rawValue,
                         details: ["domain": domain.identifier.rawValue])
        return RevocationProviderEnumerator(store: store, container: containerItemIdentifier,
                                             domainIdentifier: domain.identifier.rawValue)
    }

    func item(for identifier: NSFileProviderItemIdentifier, request: NSFileProviderRequest,
              completionHandler: @escaping (NSFileProviderItem?, Error?) -> Void) -> Progress {
        perform("provider.item", identifier: identifier, failure: { error -> ItemResult in (nil, error) },
                completion: { completionHandler($0.0, $0.1) }) { _ in
            let state = try self.activeStore().snapshot()
            guard identifier == .rootContainer || identifier == self.fileIdentifier else {
                throw RevocationProviderError.missing(identifier)
            }
            // Authentication changes do not announce a deletion or remove the file's write association.
            return (RevocationProviderItem(identifier: identifier, state: state), nil)
        }
    }

    func fetchContents(for itemIdentifier: NSFileProviderItemIdentifier, version requestedVersion: NSFileProviderItemVersion?,
                       request: NSFileProviderRequest,
                       completionHandler: @escaping (URL?, NSFileProviderItem?, Error?) -> Void) -> Progress {
        perform("provider.fetch", identifier: itemIdentifier, failure: { error -> FetchResult in (nil, nil, error) },
                completion: { completionHandler($0.0, $0.1, $0.2) }, discard: { result in
                    if let url = result.0 { try? FileManager.default.removeItem(at: url) }
                }) { _ in
            let store = try self.activeStore()
            guard itemIdentifier == self.fileIdentifier else {
                throw itemIdentifier == .rootContainer
                    ? RevocationProviderError.unsupported("The root folder has no downloadable file content.")
                    : RevocationProviderError.missing(itemIdentifier)
            }
            let state = try store.snapshot()
            guard state.authenticationAllowed else { throw FixtureStore.authenticationError() }
            let item = RevocationProviderItem(identifier: itemIdentifier, state: state)
            if let requestedVersion,
               requestedVersion.contentVersion != item.itemVersion.contentVersion ||
                requestedVersion.metadataVersion != item.itemVersion.metadataVersion {
                throw RevocationProviderError.unsupported("The requested fixture version is no longer current.")
            }
            guard let manager = self.manager else {
                throw RevocationProviderError.unsupported("No FileProvider manager is available for the fixture domain.")
            }
            // The store rechecks authentication under its cross-process lock before making this copy.
            let intermediate = try store.temporaryBackendCopy()
            defer { try? FileManager.default.removeItem(at: intermediate) }
            let directory = try manager.temporaryDirectoryURL()
            let destination = directory.appendingPathComponent(UUID().uuidString + ".circ")
            var handedToSystem = false
            defer { if !handedToSystem { try? FileManager.default.removeItem(at: destination) } }
            try FileManager.default.copyItem(at: intermediate, to: destination)
            let bytes = try Data(contentsOf: destination)
            let actualSHA = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
            let current = try store.snapshot()
            guard current.authenticationAllowed else { throw FixtureStore.authenticationError() }
            guard actualSHA == state.backendSHA256, current.backendSHA256 == state.backendSHA256,
                  bytes.count == state.backendByteCount else { throw RevocationProviderError.changed() }
            handedToSystem = true
            return (destination, item, nil)
        }
    }

    func createItem(basedOn itemTemplate: NSFileProviderItem, fields: NSFileProviderItemFields,
                    contents: URL?, options: NSFileProviderCreateItemOptions, request: NSFileProviderRequest,
                    completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void) -> Progress {
        perform("provider.create", identifier: itemTemplate.itemIdentifier, failure: { error -> MutationResult in (nil, fields, false, error) },
                completion: { completionHandler($0.0, $0.1, $0.2, $0.3) }) { _ in
            throw RevocationProviderError.unsupported("The fixture exposes one existing file and does not create items.")
        }
    }

    func modifyItem(_ item: NSFileProviderItem, baseVersion: NSFileProviderItemVersion,
                    changedFields: NSFileProviderItemFields, contents newContents: URL?,
                    options: NSFileProviderModifyItemOptions, request: NSFileProviderRequest,
                    completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void) -> Progress {
        let identifier = item.itemIdentifier
        let diagnostics = RevocationModifyDiagnostics.details(
            changedFieldsRaw: changedFields.rawValue, newContentsPresent: newContents != nil, optionsRaw: options.rawValue,
            requestedContentPolicyRaw: item.contentPolicy?.rawValue,
            fixtureDeclaredContentPolicyRaw: NSFileProviderContentPolicy.downloadLazilyAndEvictOnRemoteUpdate.rawValue)
        return perform("provider.modify", identifier: identifier, details: diagnostics,
                       failure: { error -> MutationResult in (nil, changedFields, false, error) },
                       completion: { completionHandler($0.0, $0.1, $0.2, $0.3) }) { beginCommit in
            let store = try self.activeStore()
            guard identifier == self.fileIdentifier else {
                throw RevocationProviderError.unsupported("Only the existing fixture file's content can be changed.")
            }
            let state = try store.snapshot()
            guard state.authenticationAllowed else { throw FixtureStore.authenticationError() }
            guard changedFields.contains(.contents), let newContents else {
                throw RevocationProviderError.unsupported("The fixture does not commit metadata-only changes.")
            }
            guard !changedFields.contains(.filename), !changedFields.contains(.parentItemIdentifier),
                  item.filename == FixtureConfiguration.filename, item.parentItemIdentifier == .rootContainer else {
                throw RevocationProviderError.unsupported("The fixture cannot be renamed or moved.")
            }
            guard let expectedSHA = String(data: baseVersion.contentVersion, encoding: .utf8),
                  expectedSHA == state.backendSHA256 else { throw RevocationProviderError.changed() }
            guard newContents.isFileURL else { throw RevocationProviderError.unsupported("An upload must be a local regular file.") }
            let attributes = try newContents.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
            guard attributes.isRegularFile == true, attributes.isSymbolicLink != true,
                  let size = attributes.fileSize, size >= 0, size <= 1024 * 1024 else {
                throw RevocationProviderError.unsupported("Fixture uploads must be regular files no larger than one MiB.")
            }
            let bytes = try Data(contentsOf: newContents)
            try beginCommit()
            let updated = try store.replaceBackend(bytes, expectedSHA256: expectedSHA)
            let remaining = changedFields.subtracting(.contents)
            return (RevocationProviderItem(identifier: identifier, state: updated), remaining, false, nil)
        }
    }

    func deleteItem(identifier: NSFileProviderItemIdentifier, baseVersion: NSFileProviderItemVersion,
                    options: NSFileProviderDeleteItemOptions, request: NSFileProviderRequest,
                    completionHandler: @escaping (Error?) -> Void) -> Progress {
        perform("provider.delete", identifier: identifier, failure: { error -> Error? in error }, completion: completionHandler) { _ in
            throw RevocationProviderError.unsupported("The fixture does not delete its backend item.")
        }
    }

    private func activeStore() throws -> FixtureStore {
        lock.lock()
        let stopped = invalidated
        lock.unlock()
        guard !stopped else { throw RevocationProviderError.cancelled() }
        return try storeResult.get()
    }

    private func perform<Value>(_ kind: String, identifier: NSFileProviderItemIdentifier,
                                details requestDetails: [String: String] = [:],
                                failure: @escaping (Error) -> Value, completion: @escaping (Value) -> Void,
                                discard: @escaping (Value) -> Void = { _ in },
                                work: @escaping (_ beginCommit: () throws -> Void) throws -> Value) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        progress.isCancellable = true
        let reply = RevocationProviderReply(progress: progress, completion: completion)
        let requestID = UUID().uuidString
        let details = requestDetails.merging(["requestID": requestID]) { _, new in new }
        record(kind + ".started", identifier: identifier, details: details)
        progress.cancellationHandler = {
            let error = RevocationProviderError.cancelled()
            _ = reply.cancel(failure(error)) { self.record(kind + ".cancelled", identifier: identifier, details: details, error: error) }
        }
        queue.async {
            guard !progress.isCancelled else { return }
            do {
                let value = try work { try reply.beginCommit() }
                if !reply.finish(value, beforeDelivery: { self.record(kind + ".completed", identifier: identifier, details: details) }) {
                    discard(value)
                }
            } catch {
                let error = error as NSError
                let suffix = error.domain == NSFileProviderErrorDomain && error.code == NSFileProviderError.notAuthenticated.rawValue
                    ? ".denied" : ".failed"
                _ = reply.finish(failure(error)) { self.record(kind + suffix, identifier: identifier, details: details, error: error) }
            }
        }
        return progress
    }

    private func record(_ kind: String, identifier: NSFileProviderItemIdentifier? = nil,
                        details: [String: String] = [:], error: NSError? = nil) {
        do {
            try storeResult.get().record(kind, itemIdentifier: identifier?.rawValue,
                                         details: details.merging(["domain": domain.identifier.rawValue]) { _, new in new }, error: error)
        } catch { NSLog("Revocation fixture could not record provider event: %@", String(describing: error)) }
    }
}

/// Cancellation and an I/O completion can race, but only one result reaches FileProvider.
private final class RevocationProviderReply<Value> {
    private let lock = NSLock()
    private var finished = false
    private var commitStarted = false
    private weak var progress: Progress?
    private let completion: (Value) -> Void

    init(progress: Progress, completion: @escaping (Value) -> Void) {
        self.progress = progress
        self.completion = completion
    }

    func beginCommit() throws {
        lock.lock()
        defer { lock.unlock() }
        guard !finished else { throw RevocationProviderError.cancelled() }
        commitStarted = true
    }

    @discardableResult func cancel(_ value: Value, beforeDelivery: () -> Void = {}) -> Bool {
        guard claimCompletion(cancellation: true) else { return false }
        deliver(value, beforeDelivery: beforeDelivery)
        return true
    }

    @discardableResult func finish(_ value: Value, beforeDelivery: () -> Void = {}) -> Bool {
        guard claimCompletion(cancellation: false) else { return false }
        deliver(value, beforeDelivery: beforeDelivery)
        return true
    }

    private func claimCompletion(cancellation: Bool) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !finished, !cancellation || !commitStarted else { return false }
        finished = true
        return true
    }

    private func deliver(_ value: Value, beforeDelivery: () -> Void) {
        progress?.cancellationHandler = nil
        progress?.completedUnitCount = 1
        beforeDelivery()
        completion(value)
    }
}
