import FileProvider
import Foundation

@available(iOS 16.0, *)
final class RevocationProviderEnumerator: NSObject, NSFileProviderEnumerator {
    private let store: FixtureStore
    private let container: NSFileProviderItemIdentifier
    private let domainIdentifier: String
    private let lock = NSLock()
    private var invalidated = false
    private var itemIdentifier: NSFileProviderItemIdentifier {
        NSFileProviderItemIdentifier(rawValue: FixtureConfiguration.itemIdentifier)
    }

    init(store: FixtureStore, container: NSFileProviderItemIdentifier, domainIdentifier: String) {
        self.store = store
        self.container = container
        self.domainIdentifier = domainIdentifier
        super.init()
    }

    func invalidate() {
        lock.lock()
        invalidated = true
        lock.unlock()
        record("provider.enumerator.invalidated")
    }

    func enumerateItems(for observer: NSFileProviderEnumerationObserver, startingAt page: NSFileProviderPage) {
        do {
            let state = try currentState()
            // A document enumerator observes changes to that document; it has no children.
            if container != itemIdentifier {
                observer.didEnumerate([RevocationProviderItem(identifier: itemIdentifier, state: state)])
            }
            record("provider.enumerator.items", details: ["generation": String(state.generation)])
            observer.finishEnumerating(upTo: nil)
        } catch {
            record("provider.enumerator.failed", error: error as NSError)
            observer.finishEnumeratingWithError(error)
        }
    }

    func enumerateChanges(for observer: NSFileProviderChangeObserver, from syncAnchor: NSFileProviderSyncAnchor) {
        do {
            let state = try currentState()
            let anchor = currentAnchor(state)
            if syncAnchor != anchor {
                observer.didUpdate([RevocationProviderItem(identifier: itemIdentifier, state: state)])
            }
            record("provider.enumerator.changes", details: ["generation": String(state.generation)])
            observer.finishEnumeratingChanges(upTo: anchor, moreComing: false)
        } catch {
            record("provider.enumerator.failed", error: error as NSError)
            observer.finishEnumeratingWithError(error)
        }
    }

    func currentSyncAnchor(completionHandler: @escaping (NSFileProviderSyncAnchor?) -> Void) {
        do {
            let state = try currentState()
            record("provider.enumerator.anchor", details: ["generation": String(state.generation)])
            completionHandler(currentAnchor(state))
        } catch {
            record("provider.enumerator.failed", error: error as NSError)
            completionHandler(nil)
        }
    }

    private func currentState() throws -> FixtureState {
        lock.lock()
        let stopped = invalidated
        lock.unlock()
        guard !stopped else { throw RevocationProviderError.cancelled() }
        return try store.snapshot()
    }

    private func currentAnchor(_ state: FixtureState) -> NSFileProviderSyncAnchor {
        NSFileProviderSyncAnchor(rawValue: Data("fixture-v1:\(state.generation):\(state.backendSHA256)".utf8))
    }

    private func record(_ kind: String, details: [String: String] = [:], error: NSError? = nil) {
        do {
            try store.record(kind, itemIdentifier: container.rawValue,
                             details: details.merging(["domain": domainIdentifier]) { _, new in new }, error: error)
        } catch { NSLog("Revocation fixture could not record enumerator event: %@", String(describing: error)) }
    }
}
