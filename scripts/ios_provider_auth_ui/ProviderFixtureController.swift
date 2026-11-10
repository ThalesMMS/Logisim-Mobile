import XCTest
import UIKit

/// Drives the fixture's public controls; evidence is read only from its UITextView.
final class ProviderFixtureController {
    typealias Snapshot = ProviderFixtureSnapshot

    private unowned let harness: NativeUIHarness
    private let fixture = XCUIApplication(bundleIdentifier: "thalesmms.logisim.validation.revocationfixture")
    private var baseline: Snapshot?
    private var lastObserved: Snapshot?
    private var preparationEvictionRequested = false
    private var denialRequested = false
    private var checkpoint = 0

    private enum ID {
        static let refresh = "fixture.evidence.refresh"
        static let evidence = "fixture.evidence"
        static let status = "fixture.status"
        static let evict = "fixture.evict"
        static let evictThenDeny = "fixture.evictThenDeny"
        static let allow = "fixture.auth.allow"
    }

    init(harness: NativeUIHarness) {
        self.harness = harness
    }

    /// Refresh has no public completion acknowledgement when its text is unchanged.
    /// Call observe for a new event or state; a single snapshot never supplies that proof.
    func snapshot(refresh: Bool = true) throws -> Snapshot {
        try activate()
        if refresh { try tap(ID.refresh, captureBefore: false) }
        let view = try unique(fixture.textViews.matching(identifier: ID.evidence), name: "evidence UITextView")
        guard let text = view.value as? String, text.utf8.count <= 2_000_000,
              let marker = text.range(of: "\n\nSNAPSHOT\n"),
              String(text[..<marker.lowerBound]) == "Native scenario: NOT_RUN\nApp Group: group.thalesmms.logisim.validation.revocationfixture\nDomain: logisim-revocation-fixture-v1\nItem: revocation-circuit-v1" else {
            throw failure("The public UITextView did not expose the expected fixture SNAPSHOT envelope.", stage: "invalid-envelope")
        }
        let result: Snapshot
        do {
            result = try JSONDecoder().decode(Snapshot.self, from: Data(text[marker.upperBound...].utf8))
            try ProviderAuthenticationOracle.validate(result, preserving: lastObserved)
            if let previous = lastObserved, previous.lastSequence == result.lastSequence {
                try ProviderAuthenticationOracle.require(previous.state == result.state,
                    "Fixture state changed without a corresponding public event")
            }
        } catch {
            harness.attach(text, name: "provider-fixture-invalid-public-evidence")
            throw failure("Could not validate the public fixture snapshot: \(error)", stage: "invalid-snapshot")
        }
        if baseline == nil {
            baseline = result
            capture("baseline")
            harness.attach(text, name: "provider-fixture-baseline-public-evidence")
        }
        lastObserved = result
        return result
    }

    /// Each poll uses the real Refresh button. No App Group or provider file is read here.
    func observe(timeout: TimeInterval, condition: @escaping (Snapshot) -> Bool) throws -> Snapshot {
        try observe(timeout: timeout, stopBeforeDenialFailure: true, condition: condition)
    }

    /// Evicts cached content once, while authenticated, before Main enters Files.
    /// The caller launches Main only after the public callback and state oracle pass.
    func evictForFreshFetch(main: XCUIApplication) throws -> Snapshot {
        guard main.state == .notRunning, !preparationEvictionRequested, !denialRequested,
              let initial = baseline else {
            throw failure("Preparation eviction requires Main stopped, an existing baseline, and no earlier eviction/denial attempt.",
                          stage: "preparation-eviction-precondition")
        }
        let before = try snapshot()
        do {
            try ProviderAuthenticationOracle.initial(initial)
            try ProviderAuthenticationOracle.initial(before)
            try ProviderAuthenticationOracle.require(before.state == initial.state,
                "Preparation eviction must begin with the unchanged authenticated initial backend")
        } catch {
            throw failure("Refusing preparation eviction before the initial-state guards pass: \(error)",
                          stage: "preparation-eviction-state")
        }
        try revealButton(ID.evict)
        capture("before-preparation-eviction")
        guard main.state == .notRunning else {
            throw failure("Main restarted before preparation eviction.", stage: "main-running-before-preparation-eviction")
        }
        let control = try actionableButton(ID.evict)
        preparationEvictionRequested = true
        control.tap()
        try requireFinished(button: ID.evict, timeout: 45,
            status: "Eviction completed. Authentication remains allowed. Native scenario: NOT_RUN.")
        let after = try observe(timeout: 15, stopBeforeDenialFailure: false) { current in
            current.events.contains {
                $0.sequence > before.lastSequence && ["eviction.completed", "eviction.failed"].contains($0.kind)
            }
        }
        do { try ProviderAuthenticationOracle.evictedForFreshFetch(after: before, in: after) }
        catch {
            throw failure("Preparation eviction did not preserve its authenticated initial backend: \(error)",
                          stage: "preparation-eviction-failed")
        }
        // Transfer prerequisites begin after the validated preparation, retaining
        // its complete history. Earlier cache/materialization events cannot prove
        // the later fetch or become a failure of that new transfer phase.
        baseline = after
        capture("preparation-eviction-completed")
        return after
    }

    func evictThenDeny(main: XCUIApplication) throws -> Snapshot {
        guard main.state == .notRunning, !denialRequested, let initial = baseline else {
            throw failure("Eviction requires Main to be stopped, one prior public baseline, and no earlier denial attempt.",
                          stage: "eviction-precondition")
        }
        let before = try snapshot()
        try stopOnFailedTransfer(before)
        do {
            try ProviderAuthenticationOracle.require(initial.state.authenticationAllowed && before.state.authenticationAllowed,
                "Authentication must remain allowed until fetch and upload have completed")
            let fetch = try ProviderAuthenticationOracle.completedRequest("provider.fetch", after: initial, in: before)
            let upload = try ProviderAuthenticationOracle.completedRequest("provider.modify", after: initial, in: before)
            try ProviderAuthenticationOracle.require(fetch.terminalSequence < upload.startedSequence &&
                before.state.generation > initial.state.generation,
                "A fresh fetch must precede a fresh upload that advances the backend generation")
            try ProviderAuthenticationOracle.require(before.events.contains {
                $0.sequence > upload.startedSequence && $0.sequence < upload.terminalSequence &&
                $0.kind == "backend.updated" && $0.itemIdentifier == ProviderAuthenticationOracle.item &&
                $0.process == upload.process && $0.details["sha256"] == before.state.backendSHA256 &&
                $0.details["bytes"] == String(before.state.backendByteCount) &&
                $0.errorDomain == nil && $0.errorCode == nil
            }, "The upload must have committed the currently observed backend bytes before eviction")
        } catch {
            throw failure("Refusing denial before successful fetch/upload: \(error)", stage: "transfer-precondition")
        }
        try activate()
        try revealButton(ID.evictThenDeny)
        capture("before-evict-then-deny")
        // Recheck after UI capture and scrolling; this helper never terminates Main itself.
        guard main.state == .notRunning else {
            throw failure("Main restarted before the eviction action.", stage: "main-running-before-eviction")
        }
        let control = try actionableButton(ID.evictThenDeny)
        denialRequested = true
        control.tap()
        try requireFinished(button: ID.evictThenDeny, timeout: 45,
            status: "Eviction completed; authentication denied. A later denied provider request is still required. Native scenario: NOT_RUN.")
        let after = try observe(timeout: 15, stopBeforeDenialFailure: false) { current in
            current.events.contains { $0.sequence > before.lastSequence && $0.kind == "eviction.failed" } ||
                (!current.state.authenticationAllowed && current.events.contains {
                    $0.sequence > before.lastSequence && $0.kind == "authentication.changed" && $0.details["allowed"] == "false"
                })
        }
        do { try ProviderAuthenticationOracle.evictedAndDenied(after: before, in: after) }
        catch { throw failure("Evict Then Deny did not complete successfully: \(error)", stage: "eviction-failed") }
        capture("evicted-and-authentication-denied")
        return after
    }

    /// Cleanup is an explicit Allow action, including its public error-resolution callback.
    func allowAuthenticationCleanup() throws -> Snapshot {
        let before = try snapshot()
        try tap(ID.allow)
        try requireFinished(button: ID.allow, timeout: 30,
            status: "Authentication allowed; error resolution signalled. Native scenario: NOT_RUN.")
        let after = try observe(timeout: 15, stopBeforeDenialFailure: false) { current in
            current.events.contains {
                $0.sequence > before.lastSequence &&
                ["authentication.resolved.signalled", "authentication.resolved.signalFailed"].contains($0.kind)
            }
        }
        guard after.state.authenticationAllowed,
              after.events.contains(where: { $0.sequence > before.lastSequence && $0.kind == "authentication.changed" &&
                  $0.details["allowed"] == "true" }),
              after.events.contains(where: { $0.sequence > before.lastSequence && $0.kind == "authentication.resolved.signalled" &&
                  $0.errorDomain == nil && $0.errorCode == nil }),
              !after.events.contains(where: { $0.sequence > before.lastSequence && $0.kind == "authentication.resolved.signalFailed" }) else {
            throw failure("Allow authentication did not report a successful public cleanup callback.", stage: "cleanup-failed")
        }
        // Allow may let the system resume pending work. The denied-phase preservation
        // oracle belongs before this action; return the final cleanup state separately.
        capture("authentication-cleanup-completed")
        return after
    }

    func terminate() {
        if fixture.state != .notRunning { fixture.terminate() }
    }

    private func observe(timeout: TimeInterval, stopBeforeDenialFailure: Bool,
                         condition: @escaping (Snapshot) -> Bool) throws -> Snapshot {
        guard timeout > 0 && timeout <= 60 else {
            throw failure("Fixture observation requires a positive timeout of at most sixty seconds.", stage: "invalid-timeout")
        }
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        repeat {
            let current = try snapshot()
            if stopBeforeDenialFailure { try stopOnFailedTransfer(current) }
            if condition(current) {
                attachPublicEvidence("condition-observed")
                capture("condition-observed")
                return current
            }
            Thread.sleep(forTimeInterval: 0.2)
        } while ProcessInfo.processInfo.systemUptime < deadline
        throw failure("Timed out waiting for the required new public fixture event/state.", stage: "observation-timeout")
    }

    private func stopOnFailedTransfer(_ snapshot: Snapshot) throws {
        guard !denialRequested, let baseline else { return }
        let failedKinds = ["provider.fetch.failed", "provider.fetch.cancelled", "provider.fetch.denied",
                           "provider.modify.failed", "provider.modify.cancelled", "provider.modify.denied"]
        let failures = snapshot.events.filter {
            $0.sequence > baseline.lastSequence && $0.itemIdentifier == ProviderAuthenticationOracle.item &&
                $0.domain == ProviderAuthenticationOracle.domain && failedKinds.contains($0.kind)
        }
        if !failures.isEmpty {
            throw failure("A real fetch/upload failed before denial: \(failures.map { "\($0.sequence):\($0.kind):\($0.errorDomain ?? "nil")/\($0.errorCode.map(String.init) ?? "nil")" }).",
                          stage: "transfer-failed-before-denial")
        }
    }

    private func activate() throws {
        if fixture.state != .runningForeground { fixture.activate() }
        guard fixture.wait(for: .runningForeground, timeout: 10) else {
            throw failure("The fixture app did not become foreground.", stage: "fixture-not-foreground")
        }
    }

    private func requireFinished(button identifier: String, timeout: TimeInterval, status expected: String) throws {
        let done = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let buttons = self.fixture.buttons.matching(identifier: identifier).allElementsBoundByIndex.filter { $0.exists }
            let statuses = self.fixture.staticTexts.matching(identifier: ID.status).allElementsBoundByIndex.filter { $0.exists }
            return buttons.count == 1 && buttons[0].isEnabled && statuses.count == 1 &&
                (statuses[0].label == expected || statuses[0].label.hasPrefix("Operation failed:"))
        }, object: fixture)
        guard XCTWaiter.wait(for: [done], timeout: timeout) == .completed,
              try unique(fixture.staticTexts.matching(identifier: ID.status), name: "status label").label == expected else {
            throw failure("The fixture did not expose its expected successful callback status: \(expected)", stage: "callback-status-failed")
        }
    }

    private func tap(_ identifier: String, captureBefore: Bool = true) throws {
        try revealButton(identifier)
        if captureBefore { capture("before-" + identifier) }
        try actionableButton(identifier).tap()
    }

    private func revealButton(_ identifier: String) throws {
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let matches = self.fixture.buttons.matching(identifier: identifier).allElementsBoundByIndex.filter { $0.exists }
            return matches.count == 1 && matches[0].isEnabled
        }, object: fixture)
        guard XCTWaiter.wait(for: [enabled], timeout: 10) == .completed else {
            throw failure("The unique fixture button is absent or busy: \(identifier)", stage: "button-not-ready")
        }
        let initial = try unique(fixture.buttons.matching(identifier: identifier), name: identifier)
        if displayed(initial) && initial.isHittable { return }
        let initialScroll = try unique(fixture.scrollViews, name: "fixture UIScrollView")
        let below = initial.frame.midY >= initialScroll.frame.midY
        for forward in [below, !below] {
            for _ in 0..<8 {
                let button = try unique(fixture.buttons.matching(identifier: identifier), name: identifier)
                if displayed(button) && button.isHittable { return }
                let scroll = try unique(fixture.scrollViews, name: "fixture UIScrollView")
                guard displayed(scroll) && scroll.isHittable else {
                    throw failure("The fixture's unique UIScrollView is not reachable.", stage: "scroll-not-reachable")
                }
                if forward { scroll.swipeUp(velocity: .slow) } else { scroll.swipeDown(velocity: .slow) }
            }
        }
        throw failure("The button did not become visible inside the fixture's UIScrollView: \(identifier)", stage: "button-not-visible")
    }

    private func actionableButton(_ identifier: String) throws -> XCUIElement {
        let button = try unique(fixture.buttons.matching(identifier: identifier), name: identifier)
        guard fixture.state == .runningForeground && displayed(button) && button.isHittable && button.isEnabled else {
            throw failure("The fixture button is not unique, visible, hittable and enabled: \(identifier)", stage: "button-not-actionable")
        }
        return button
    }

    private func unique(_ query: XCUIElementQuery, name: String) throws -> XCUIElement {
        let elements = query.allElementsBoundByIndex.filter { $0.exists }
        guard elements.count == 1 else {
            throw failure("Expected one \(name); observed \(elements.count).", stage: "ambiguous-control")
        }
        return elements[0]
    }

    private func displayed(_ element: XCUIElement) -> Bool {
        let frame = element.frame
        return element.exists && frame.width > 0 && frame.height > 0 && frame.midX.isFinite && frame.midY.isFinite &&
            fixture.frame.contains(CGPoint(x: frame.midX, y: frame.midY))
    }

    private func attachPublicEvidence(_ stage: String) {
        let views = fixture.textViews.matching(identifier: ID.evidence).allElementsBoundByIndex.filter { $0.exists }
        if views.count == 1, let text = views[0].value as? String {
            harness.attach(text, name: "provider-fixture-" + stage + "-public-evidence")
        }
    }

    private func capture(_ stage: String) {
        checkpoint += 1
        let name = "provider-fixture-\(checkpoint)-" + stage
        harness.screenshot(name)
        harness.attach(fixture.debugDescription, name: name + "-tree")
    }

    private func failure(_ message: String, stage: String) -> Error {
        attachPublicEvidence(stage)
        capture(stage)
        XCTFail(message)
        return ProviderAuthenticationOracle.Failure(description: message)
    }
}
