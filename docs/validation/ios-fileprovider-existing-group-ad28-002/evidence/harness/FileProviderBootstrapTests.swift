import CryptoKit
import Foundation
import XCTest

@MainActor
final class FileProviderBootstrapTests: XCTestCase {
    private let fixture = XCUIApplication(bundleIdentifier: "thalesmms.logisim.validation.revocationfixture")
    private let group = "group.thalesmms.logisim.validation.revocationfixture"
    private let domain = "logisim-revocation-fixture-v1"
    private let item = "revocation-circuit-v1"

    private struct SignedFixture: Decodable {
        struct Artifact: Decodable { let bundle_id: String; let bundle_tree_sha256: String }
        let source: String
        let status: String
        let appGroup: String
        let app: Artifact
        let provider: Artifact
    }
    private struct Snapshot: Decodable {
        struct State: Decodable, Equatable {
            let schemaVersion: Int
            let authenticationAllowed: Bool
            let backendSHA256: String
            let backendByteCount: Int
            let generation: Int
        }
        struct Event: Decodable {
            let sequence: Int64
            let timestamp: String
            let process: String
            let kind: String
            let details: [String: String]
            let errorDomain: String?
            let errorCode: Int?
        }
        let state: State
        let events: [Event]
        var lastSequence: Int64 { events.map(\.sequence).max() ?? 0 }
    }
    private enum Failure: Error { case operationFailed, unreachableControl, invalidPublicEvidence }

    override func tearDownWithError() throws {
        if fixture.state == .runningForeground { capture("terminal") }
        try super.tearDownWithError()
    }

    func testPrepareRegisterAndInspectFixtureDomain() throws {
        continueAfterFailure = false
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "ProvenanceSignedFixture", withExtension: "json"))
        let bytes = try Data(contentsOf: url)
        XCTAssertEqual(SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(),
                       "bda24c74410814276f00474a689ae95508deb7330cc0eacae9f55bf54ec7388a")
        let provenance = try JSONDecoder().decode(SignedFixture.self, from: bytes)
        XCTAssertEqual(provenance.source, "11fc44d53b229558fbfc57b66e0b2f2d11a0037b")
        XCTAssertEqual(provenance.status, "BUILD_AND_ADHOC_SIGN_PASS")
        XCTAssertEqual(provenance.appGroup, group)
        XCTAssertEqual(provenance.app.bundle_id, "thalesmms.logisim.validation.revocationfixture")
        XCTAssertEqual(provenance.app.bundle_tree_sha256, "8b2577dbe1c2f1e3233448010f82d7399b9f41d8fa52f8b4e73cc81ea1d34e2a")
        XCTAssertEqual(provenance.provider.bundle_id, "thalesmms.logisim.validation.revocationfixture.provider")
        XCTAssertEqual(provenance.provider.bundle_tree_sha256, "d6e6ee495cdeb2189c023d04977c0fafa0425aa4aa47083f5fb86d2e85fa0684")
        attach(String(decoding: bytes, as: UTF8.self), "provided-signed-fixture-provenance")

        fixture.launch()
        XCTAssertTrue(fixture.wait(for: .runningForeground, timeout: 20))
        let evidence = fixture.textViews.matching(identifier: "fixture.evidence")
        XCTAssertTrue(evidence.firstMatch.waitForExistence(timeout: 10))
        XCTAssertEqual(evidence.count, 1)
        try expose(evidence.firstMatch)
        wait("Initial public evidence") { !(self.evidenceValue() ?? "").isEmpty }
        let baseline: Snapshot?
        if evidenceValue() == "Native scenario: NOT_RUN\nPrepare the fixture before accessing its backend." {
            baseline = nil
        } else if let existing = snapshot() {
            baseline = existing
        } else {
            capture("invalid-baseline")
            XCTFail("Expected a complete public snapshot or the fixture's explicit unprepared message")
            throw Failure.invalidPublicEvidence
        }
        capture("baseline")

        try tap("fixture.prepare")
        try waitOperation("fixture.prepare", status: "Prepared backend Native scenario: NOT_RUN.")
        let prepared = try waitSnapshot("Prepared backend") { snapshot in
            snapshot.events.contains { $0.kind == "prepare" && $0.sequence > (baseline?.lastSequence ?? 0) }
        }
        XCTAssertEqual(prepared.state.schemaVersion, 1)
        XCTAssertEqual(prepared.state.backendSHA256.count, 64)
        XCTAssertGreaterThan(prepared.state.backendByteCount, 0)
        if let baseline {
            XCTAssertEqual(prepared.state, baseline.state, "Prepare must preserve an existing backend; it is not a reset")
        } else {
            XCTAssertEqual(prepared.state.backendSHA256, "54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88")
            XCTAssertEqual(prepared.state.backendByteCount, 552)
            XCTAssertEqual(prepared.state.generation, 1)
            XCTAssertTrue(prepared.state.authenticationAllowed)
        }
        capture("prepared")

        try tap("fixture.domain.add")
        try waitOperation("fixture.domain.add", status: "Domain registration completed. Verify its location in Files; native scenario remains NOT_RUN.")
        let registered = try waitSnapshot("Domain registration completed") { snapshot in
            snapshot.events.contains { $0.kind == "domain.registration.completed" && $0.sequence > prepared.lastSequence }
        }
        let registration = registered.events.filter { $0.sequence > prepared.lastSequence && $0.kind.hasPrefix("domain.registration.") }
        let requested = try XCTUnwrap(registration.first { $0.kind == "domain.registration.requested" })
        let completed = try XCTUnwrap(registration.first { $0.kind == "domain.registration.completed" })
        XCTAssertEqual(requested.details["domain"], domain)
        XCTAssertGreaterThan(completed.sequence, requested.sequence)
        XCTAssertTrue(requested.process.hasPrefix("RevocationFixture:"))
        XCTAssertTrue(completed.process.hasPrefix("RevocationFixture:"))
        XCTAssertFalse(requested.timestamp.isEmpty)
        XCTAssertFalse(completed.timestamp.isEmpty)
        XCTAssertFalse(registration.contains { $0.kind == "domain.registration.failed" })
        XCTAssertNil(completed.errorDomain)
        XCTAssertNil(completed.errorCode)
        XCTAssertEqual(registered.state, prepared.state)
        capture("registered")

        try tap("fixture.domain.inspect")
        try waitOperation("fixture.domain.inspect", status: "Matching fixture domains: 1. Native scenario remains NOT_RUN.")
        let inspected = try waitSnapshot("Public domain snapshot") { $0.lastSequence >= completed.sequence }
        XCTAssertEqual(inspected.state, prepared.state)
        capture("inspected")
        attach("BOOTSTRAP_REGISTER passed through UI. Extension execution, Files enumeration, fetch, Save, authentication denial and revocation: NOT_RUN.", "bootstrap-scope")
    }

    private func statusText() -> String { fixture.staticTexts["fixture.status"].label }
    private func evidenceValue() -> String? { fixture.textViews["fixture.evidence"].value as? String }

    private func snapshot() -> Snapshot? {
        guard let text = evidenceValue(), let marker = text.range(of: "\nSNAPSHOT\n") else { return nil }
        let header = String(text[..<marker.lowerBound])
        guard header.contains("App Group: " + group), header.contains("Domain: " + domain), header.contains("Item: " + item) else { return nil }
        return try? JSONDecoder().decode(Snapshot.self, from: Data(text[marker.upperBound...].utf8))
    }

    private func waitSnapshot(_ name: String, condition: @escaping (Snapshot) -> Bool) throws -> Snapshot {
        wait(name) { self.statusText().hasPrefix("Operation failed:") || self.snapshot().map(condition) == true }
        try requireOperationSucceeded()
        return try XCTUnwrap(snapshot(), name)
    }

    private func waitOperation(_ identifier: String, status: String) throws {
        wait(identifier + " callback") {
            self.fixture.buttons[identifier].isEnabled &&
                (self.statusText().hasPrefix("Operation failed:") || self.statusText() == status)
        }
        try requireOperationSucceeded()
    }

    private func requireOperationSucceeded() throws {
        guard !statusText().hasPrefix("Operation failed:") else {
            capture("operation-failed")
            XCTFail(statusText())
            throw Failure.operationFailed
        }
    }

    private func wait(_ name: String, condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: fixture)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 20), .completed, name)
    }

    private func tap(_ identifier: String) throws {
        let matches = fixture.buttons.matching(identifier: identifier)
        XCTAssertTrue(matches.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(matches.count, 1, identifier)
        let button = matches.firstMatch
        try expose(button)
        wait(identifier + " enabled") { button.isEnabled }
        capture("before-" + identifier)
        button.tap()
    }

    private func expose(_ element: XCUIElement) throws {
        for _ in 0..<8 {
            let frame = element.frame
            if element.exists && frame.width > 0 && frame.height > 0 && fixture.frame.contains(frame) && element.isHittable { return }
            let scrollers = fixture.scrollViews.allElementsBoundByIndex.filter { scroll in
                let bounds = scroll.frame
                return scroll.exists && bounds.minX.isFinite && bounds.minY.isFinite && bounds.width > 0 && bounds.height > 0 &&
                    !bounds.intersection(self.fixture.frame).isEmpty && scroll.isHittable
            }
            guard scrollers.count == 1 else { throw Failure.unreachableControl }
            if frame.midY < fixture.frame.midY { scrollers[0].swipeDown() } else { scrollers[0].swipeUp() }
        }
        capture("unreachable-control")
        XCTFail("The public fixture control must be visible and hittable")
        throw Failure.unreachableControl
    }

    private func capture(_ name: String) {
        attach("status=" + statusText() + "\n\n" + (evidenceValue() ?? "<no public UITextView value>"), name + "-public-evidence")
        attach(fixture.debugDescription, name + "-tree")
        let image = XCTAttachment(screenshot: fixture.screenshot())
        image.name = name
        image.lifetime = .keepAlways
        add(image)
    }

    private func attach(_ text: String, _ name: String) {
        let attachment = XCTAttachment(string: text)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
