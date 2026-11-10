import FileProvider
import UIKit

@main
final class RevocationFixtureApp: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UINavigationController(rootViewController: FixtureControlViewController())
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}

final class FixtureControlViewController: UIViewController {
    private let queue = DispatchQueue(label: "thalesmms.logisim.validation.revocationfixture.control")
    private let status = UILabel()
    private let evidence = UITextView()
    private var buttons: [UIButton] = []
    private var busy = false

    private var domain: NSFileProviderDomain {
        NSFileProviderDomain(identifier: NSFileProviderDomainIdentifier(rawValue: FixtureConfiguration.domainIdentifier),
                             displayName: FixtureConfiguration.displayName)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = FixtureConfiguration.displayName
        view.backgroundColor = .systemBackground
        let scroll = UIScrollView()
        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 12
        scroll.translatesAutoresizingMaskIntoConstraints = false
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 16),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -16),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -16),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -32),
        ])
        let instructions = UILabel()
        instructions.numberOfLines = 0
        instructions.text = "Test fixture only. Prepare, register the domain, then select revocation.circ in another app's Files picker. Close that app before eviction. Denial is proven only when a later system-dispatched provider request fails and the editor preserves its project."
        stack.addArrangedSubview(instructions)
        status.numberOfLines = 0
        status.accessibilityIdentifier = "fixture.status"
        status.text = "Native scenario: NOT_RUN. No domain is registered automatically."
        stack.addArrangedSubview(status)
        addButton("Prepare backend", id: "fixture.prepare", to: stack) { [weak self] in
            self?.storeAction("Prepared backend") { _ = try $0.prepare() }
        }
        addButton("Register fixture domain", id: "fixture.domain.add", to: stack) { [weak self] in self?.registerDomain() }
        addButton("Inspect fixture domain", id: "fixture.domain.inspect", to: stack) { [weak self] in self?.inspectDomain() }
        addButton("Allow authentication", id: "fixture.auth.allow", to: stack) { [weak self] in self?.allowAuthentication() }
        addButton("Deny authentication without eviction", id: "fixture.auth.deny", to: stack) { [weak self] in
            self?.storeAction("Authentication denied. Cached content may remain readable.") { _ = try $0.setAuthenticationAllowed(false) }
        }
        addButton("Evict item", id: "fixture.evict", to: stack) { [weak self] in self?.evict(denyAfterCompletion: false) }
        addButton("Evict, then deny authentication", id: "fixture.evictThenDeny", to: stack) { [weak self] in self?.evict(denyAfterCompletion: true) }
        addButton("Refresh evidence", id: "fixture.evidence.refresh", to: stack) { [weak self] in self?.refreshEvidence() }
        let share = addButton("Share evidence", id: "fixture.evidence.share", to: stack) { [weak self] in self?.shareEvidence() }
        share.accessibilityHint = "Shares the observed fixture state and provider events. It does not mark the native scenario passed."
        evidence.isEditable = false
        evidence.isSelectable = true
        evidence.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        evidence.accessibilityIdentifier = "fixture.evidence"
        evidence.heightAnchor.constraint(equalToConstant: 420).isActive = true
        stack.addArrangedSubview(evidence)
        refreshEvidence()
    }

    @discardableResult private func addButton(_ title: String, id: String, to stack: UIStackView,
                                              action: @escaping () -> Void) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.titleLabel?.numberOfLines = 0
        button.contentHorizontalAlignment = .leading
        button.accessibilityIdentifier = id
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        stack.addArrangedSubview(button)
        buttons.append(button)
        return button
    }

    private func registerDomain() {
        guard !busy else { return }
        setBusy(true)
        queue.async {
            do {
                let store = try FixtureStore()
                _ = try store.snapshot()
                try store.record("domain.registration.requested", details: ["domain": FixtureConfiguration.domainIdentifier])
                NSFileProviderManager.add(self.domain) { error in
                    try? store.record(error == nil ? "domain.registration.completed" : "domain.registration.failed", error: error as NSError?)
                    self.finish(error == nil ? "Domain registration completed. Verify its location in Files; native scenario remains NOT_RUN." : nil, error: error)
                }
            } catch { self.finish(nil, error: error) }
        }
    }

    private func inspectDomain() {
        guard !busy else { return }
        setBusy(true)
        NSFileProviderManager.getDomainsWithCompletionHandler { domains, error in
            let matches = domains.filter { $0.identifier.rawValue == FixtureConfiguration.domainIdentifier }
            self.finish(error == nil ? "Matching fixture domains: \(matches.count). Native scenario remains NOT_RUN." : nil, error: error)
        }
    }

    private func allowAuthentication() {
        guard !busy else { return }
        setBusy(true)
        let selectedDomain = domain
        queue.async {
            do {
                let store = try FixtureStore()
                _ = try store.setAuthenticationAllowed(true)
                guard let manager = NSFileProviderManager(for: selectedDomain) else {
                    self.finish("Authentication allowed. Register the fixture domain before retrying native access.", error: nil)
                    return
                }
                manager.signalErrorResolved(FixtureStore.authenticationError()) { error in
                    try? store.record(error == nil ? "authentication.resolved.signalled" : "authentication.resolved.signalFailed", error: error as NSError?)
                    self.finish(error == nil ? "Authentication allowed; error resolution signalled. Native scenario: NOT_RUN." : nil, error: error)
                }
            } catch { self.finish(nil, error: error) }
        }
    }

    private func evict(denyAfterCompletion: Bool) {
        guard !busy else { return }
        setBusy(true)
        queue.async {
            do {
                let store = try FixtureStore()
                let state = try store.snapshot()
                guard state.authenticationAllowed else { throw FixtureStore.authenticationError() }
                guard let manager = NSFileProviderManager(for: self.domain) else {
                    throw NSError(domain: NSFileProviderErrorDomain, code: NSFileProviderError.providerNotFound.rawValue)
                }
                try store.record("eviction.requested", itemIdentifier: FixtureConfiguration.itemIdentifier,
                    details: ["denyAfterCompletion": String(denyAfterCompletion), "backendSHA256": state.backendSHA256])
                manager.evictItem(identifier: NSFileProviderItemIdentifier(rawValue: FixtureConfiguration.itemIdentifier)) { error in
                    do {
                        if let error {
                            try store.record("eviction.failed", itemIdentifier: FixtureConfiguration.itemIdentifier, error: error as NSError)
                            self.finish(nil, error: error)
                            return
                        }
                        try store.record("eviction.completed", itemIdentifier: FixtureConfiguration.itemIdentifier,
                                         details: ["backendSHA256": try store.snapshot().backendSHA256])
                        if denyAfterCompletion { _ = try store.setAuthenticationAllowed(false) }
                        self.finish(denyAfterCompletion
                            ? "Eviction completed; authentication denied. A later denied provider request is still required. Native scenario: NOT_RUN."
                            : "Eviction completed. Authentication remains allowed. Native scenario: NOT_RUN.", error: nil)
                    } catch { self.finish(nil, error: error) }
                }
            } catch { self.finish(nil, error: error) }
        }
    }

    private func storeAction(_ success: String, action: @escaping (FixtureStore) throws -> Void) {
        guard !busy else { return }
        setBusy(true)
        queue.async {
            do {
                try action(FixtureStore())
                self.finish(success + " Native scenario: NOT_RUN.", error: nil)
            } catch { self.finish(nil, error: error) }
        }
    }

    private func finish(_ success: String?, error: Error?) {
        DispatchQueue.main.async {
            self.setBusy(false)
            if let error = error as NSError? {
                self.status.text = "Operation failed: \(error.domain) / \(error.code)\n\(error.localizedDescription)\nNative scenario: NOT_RUN."
            } else { self.status.text = success }
            self.refreshEvidence()
        }
    }

    private func setBusy(_ value: Bool) {
        busy = value
        buttons.forEach { $0.isEnabled = !value }
    }

    private func refreshEvidence() {
        queue.async {
            let text: String
            do { text = try Self.currentEvidenceText() }
            catch { text = "Native scenario: NOT_RUN\n\(error.localizedDescription)" }
            DispatchQueue.main.async { self.evidence.text = text }
        }
    }

    private func shareEvidence() {
        guard !busy else { return }
        setBusy(true)
        queue.async {
            do {
                let text = try Self.currentEvidenceText()
                DispatchQueue.main.async {
                    self.setBusy(false)
                    self.evidence.text = text
                    let controller = UIActivityViewController(activityItems: [text], applicationActivities: nil)
                    controller.popoverPresentationController?.sourceView = self.view
                    controller.popoverPresentationController?.sourceRect = self.view.safeAreaLayoutGuide.layoutFrame
                    self.present(controller, animated: true)
                }
            } catch { self.finish(nil, error: error) }
        }
    }

    private static func currentEvidenceText() throws -> String {
        let store = try FixtureStore()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let snapshot = String(decoding: try encoder.encode(store.evidenceSnapshot()), as: UTF8.self)
        return "Native scenario: NOT_RUN\nApp Group: \(FixtureConfiguration.appGroupIdentifier)\nDomain: \(FixtureConfiguration.domainIdentifier)\nItem: \(FixtureConfiguration.itemIdentifier)\n\nSNAPSHOT\n\(snapshot)"
    }
}
