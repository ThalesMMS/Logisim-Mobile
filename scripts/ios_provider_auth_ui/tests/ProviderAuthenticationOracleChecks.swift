import Foundation
import Darwin

/// Pure host checks. Event dictionaries mirror fixture 601's public producer schema.
/// No XCTest, FileProvider, UIKit, App Group, network or device API is used.
@main
struct ProviderAuthenticationOracleChecks {
    static let fixtureProcess = "RevocationFixture:4101"
    static let providerProcess = "RevocationProvider:4102"
    static let domain = ProviderAuthenticationOracle.domain
    static let item = ProviderAuthenticationOracle.item
    static let fetchID = "11111111-1111-4111-8111-111111111111"
    static let uploadID = "22222222-2222-4222-8222-222222222222"
    static let deniedID = "33333333-3333-4333-8333-333333333333"
    static let sourceXML = """
    <?xml version="1.0" encoding="UTF-8"?>
    <project source="5.1.0-dev" version="1.0">
      <lib desc="#Wiring" name="0"/>
      <main name="provider_revocation"/>
      <circuit name="provider_revocation">
        <comp lib="0" loc="(100,100)" name="Pin"><a name="type" val="input"/><a name="width" val="1"/><a name="label" val="REVOCATION_INPUT"/></comp>
        <comp lib="0" loc="(200,100)" name="Pin"><a name="type" val="output"/><a name="width" val="1"/><a name="label" val="REVOCATION_OUTPUT"/></comp>
        <wire from="(100,100)" to="(200,100)"/>
      </circuit>
    </project>
    """
    static var southXML: String {
        sourceXML.replacingOccurrences(of: "<a name=\"label\" val=\"REVOCATION_INPUT\"/>",
            with: "<a name=\"label\" val=\"REVOCATION_INPUT\"/><a name=\"facing\" val=\"south\"/>")
    }
    static var northXML: String { southXML.replacingOccurrences(of: "name=\"facing\" val=\"south\"", with: "name=\"facing\" val=\"north\"") }
    static func state(xml: String? = nil, allowed: Bool = true, generation: Int = 1) -> ProviderFixtureState {
        ProviderFixtureState(schemaVersion: 1, authenticationAllowed: allowed,
            backendSHA256: xml.map { ProviderAuthenticationOracle.sha256(Data($0.utf8)) } ?? ProviderAuthenticationOracle.initialSHA256,
            backendByteCount: xml?.utf8.count ?? 552, generation: generation)
    }
    static func stamp(_ sequence: Int) -> String { String(format: "2026-11-05T12:00:%02d.000Z", sequence) }
    static func event(_ sequence: Int, _ kind: String, process: String? = nil, requestID: String? = nil,
                      eventDomain: String? = nil, eventItem: String? = nil, details: [String: String] = [:],
                      errorDomain: String? = nil, errorCode: Int? = nil, diagnostic: Bool = true,
                      diagnosticCode: Int? = nil, timestamp: String? = nil) -> ProviderFixtureEvent {
        var values = details
        if let requestID { values["requestID"] = requestID }
        if let eventDomain { values["domain"] = eventDomain }
        let chain: ProviderNSErrorDiagnostic?
        if let errorDomain, let errorCode, diagnostic {
            chain = ProviderNSErrorDiagnostic(chain: [.init(domain: errorDomain, code: diagnosticCode ?? errorCode, reason: "literal fixture error")], termination: .complete)
        } else { chain = nil }
        return ProviderFixtureEvent(sequence: Int64(sequence), timestamp: timestamp ?? stamp(sequence),
            process: process ?? (kind.hasPrefix("provider.") || kind == "backend.updated" ? providerProcess : fixtureProcess),
            kind: kind, itemIdentifier: eventItem, details: values, errorDomain: errorDomain, errorCode: errorCode, errorDiagnostic: chain)
    }
    static func request(_ sequence: Int, _ kind: String, _ id: String,
                        errorDomain: String? = nil, errorCode: Int? = nil,
                        diagnostic: Bool = true, timestamp: String? = nil) -> ProviderFixtureEvent {
        event(sequence, kind, requestID: id, eventDomain: domain, eventItem: item,
              errorDomain: errorDomain, errorCode: errorCode, diagnostic: diagnostic, timestamp: timestamp)
    }
    static var baseline: ProviderFixtureSnapshot {
        ProviderFixtureSnapshot(state: state(), events: [event(1, "prepare"),
            event(2, "domain.registration.requested", details: ["domain": domain]),
            event(3, "domain.registration.completed")])
    }
    static var fetched: ProviderFixtureSnapshot {
        ProviderFixtureSnapshot(state: state(), events: baseline.events + [request(4, "provider.fetch.started", fetchID), request(5, "provider.fetch.completed", fetchID)])
    }
    static var uploaded: ProviderFixtureSnapshot {
        ProviderFixtureSnapshot(state: state(xml: southXML, generation: 2), events: fetched.events + [
            request(6, "provider.modify.started", uploadID),
            event(7, "backend.updated", eventItem: item,
                  details: ["sha256": ProviderAuthenticationOracle.sha256(Data(southXML.utf8)), "bytes": String(southXML.utf8.count)]),
            request(8, "provider.modify.completed", uploadID)])
    }
    static var evicted: ProviderFixtureSnapshot {
        ProviderFixtureSnapshot(state: state(xml: southXML, allowed: false, generation: 2), events: uploaded.events + [
            event(9, "eviction.requested", eventItem: item, details: ["denyAfterCompletion": "true", "backendSHA256": uploaded.state.backendSHA256]),
            event(10, "eviction.completed", eventItem: item, details: ["backendSHA256": uploaded.state.backendSHA256]),
            event(11, "authentication.changed", details: ["allowed": "false"])])
    }
    static var evictedOnlyForFreshFetch: ProviderFixtureSnapshot {
        ProviderFixtureSnapshot(state: baseline.state, events: baseline.events + [
            event(4, "eviction.requested", eventItem: item, details: ["denyAfterCompletion": "false", "backendSHA256": baseline.state.backendSHA256]),
            event(5, "eviction.completed", eventItem: item, details: ["backendSHA256": baseline.state.backendSHA256])])
    }
    static func denied(kind: String = "provider.fetch", diagnostic: Bool = true,
                       started: String? = nil, terminal: String? = nil) -> ProviderFixtureSnapshot {
        ProviderFixtureSnapshot(state: evicted.state, events: evicted.events + [
            request(12, kind + ".started", deniedID, timestamp: started),
            request(13, kind + ".denied", deniedID, errorDomain: "NSFileProviderErrorDomain", errorCode: -1000,
                    diagnostic: diagnostic, timestamp: terminal)])
    }
    static func replacing(_ snapshot: ProviderFixtureSnapshot, index: Int, with event: ProviderFixtureEvent) -> ProviderFixtureSnapshot {
        var events = snapshot.events; events[index] = event
        return ProviderFixtureSnapshot(state: snapshot.state, events: events)
    }
    static func date(_ value: String) -> Date {
        let parser = ISO8601DateFormatter(); parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return parser.date(from: value)!
    }
    static func denyProof(_ snapshot: ProviderFixtureSnapshot,
                          before: String = "2026-11-05T12:00:11.500Z",
                          after: String = "2026-11-05T12:00:13.500Z") throws -> ProviderAuthenticationOracle.RequestProof {
        try ProviderAuthenticationOracle.deniedRequest(after: evicted, notBefore: date(before), notAfter: date(after), in: snapshot)
    }
    struct CheckFailure: Error, CustomStringConvertible { let description: String }
    static func reject(_ action: () throws -> Void) throws {
        do { try action() } catch { return }
        throw CheckFailure(description: "The negative case was accepted")
    }
    static func require(_ value: Bool, _ message: String) throws {
        if !value { throw CheckFailure(description: message) }
    }

    typealias Activation = ProviderActivationPrompt

    static func activationCensus() throws -> Activation.Alert {
        let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("fixtures/provider-activation-auth002.txt")
        let data = try Data(contentsOf: fixture)
        try require(ProviderAuthenticationOracle.sha256(data) == "045cb4cdc93c1b6ce9a56652782f5ae24ebfaccfc4a3d455ff4573934604f0e7",
                    "The auth002 census fixture must retain its exact captured bytes")
        guard let text = String(data: data, encoding: .utf8) else {
            throw CheckFailure(description: "The auth002 census is not UTF-8")
        }
        let lines = text.components(separatedBy: "\n")
        let indices = lines.indices.filter { lines[$0].trimmingCharacters(in: .whitespaces).hasPrefix("Alert,") }
        try require(indices.count == 1, "The retained census must contain one Alert role")
        let index = indices[0]
        let indentation = lines[index].prefix { $0 == " " || $0 == "\t" }.count
        let descendants = lines.dropFirst(index + 1).prefix { line in
            line.trimmingCharacters(in: .whitespaces).isEmpty ||
                line.prefix { $0 == " " || $0 == "\t" }.count > indentation
        }
        func label(_ line: String) throws -> String {
            guard let marker = line.range(of: "label: '"),
                  let end = line[marker.upperBound...].firstIndex(of: "'") else {
                throw CheckFailure(description: "A retained Alert control has no quoted label")
            }
            return String(line[marker.upperBound..<end])
        }
        func controls(_ role: Activation.Role, prefix: String) throws -> [Activation.Control] {
            try descendants.filter { $0.trimmingCharacters(in: .whitespaces).hasPrefix(prefix) }.map {
                // The dump retains roles/labels/frames, not these runtime AX booleans.
                // These flags are synthetic policy inputs, not proof of native hittability.
                Activation.Control(role: role, label: try label($0), visible: true, hittable: role == .button, enabled: true)
            }
        }
        try require(lines[..<index].contains { line in
            line.trimmingCharacters(in: .whitespaces).hasPrefix("Other,") && line.contains("label: 'Cancel'")
        }, "The retained census includes a background Other named Cancel outside the Alert")
        let staticTexts = try controls(.staticText, prefix: "StaticText,")
        let buttons = try controls(.button, prefix: "Button,")
        try require(staticTexts.count == 2 && buttons.count == 2, "The retained Alert has two StaticText controls and two buttons")
        return Activation.Alert(control: .init(role: .alert, label: try label(lines[index]), visible: true, hittable: false, enabled: true),
                                staticTexts: staticTexts, buttons: buttons)
    }

    static func activationIndex(_ alerts: [Activation.Alert], provider: String = "Logisim Revocation Fixture",
                                afterSelection: Bool = true, attempted: Bool = false) throws -> Int? {
        try Activation.turnOnButtonIndex(locationDisplayName: provider, afterLocationSelection: afterSelection,
                                         activationAttempted: attempted, alerts: alerts)
    }

    static func activationControl(_ value: Activation.Control, role: Activation.Role? = nil, label: String? = nil,
                                  visible: Bool? = nil, hittable: Bool? = nil, enabled: Bool? = nil) -> Activation.Control {
        Activation.Control(role: role ?? value.role, label: label ?? value.label,
                           visible: visible ?? value.visible, hittable: hittable ?? value.hittable, enabled: enabled ?? value.enabled)
    }

    static func activationAlert(_ value: Activation.Alert, control: Activation.Control? = nil,
                                staticTexts: [Activation.Control]? = nil, buttons: [Activation.Control]? = nil) -> Activation.Alert {
        Activation.Alert(control: control ?? value.control, staticTexts: staticTexts ?? value.staticTexts, buttons: buttons ?? value.buttons)
    }

    static func main() {
        var passed: [String] = [], failures: [[String: String]] = []
        func check(_ name: String, _ body: () throws -> Void) {
            do { try body(); passed.append(name) } catch { failures.append(["name": name, "error": String(describing: error)]) }
        }
        check("literal_fixture_552_and_SHA") {
            try require(Data(sourceXML.utf8).count == 552 && ProviderAuthenticationOracle.sha256(Data(sourceXML.utf8)) == ProviderAuthenticationOracle.initialSHA256, "The literal fixture bytes drifted from producer 601")
        }
        check("bootstrap_completed_has_no_domain_detail_in_real_terminal") { try ProviderAuthenticationOracle.initial(baseline) }
        check("fetch_real_pair_preserves_backend") { _ = try ProviderAuthenticationOracle.fetched(after: baseline, in: fetched) }
        check("fetch_cannot_reuse_baseline") { try reject { _ = try ProviderAuthenticationOracle.fetched(after: fetched, in: fetched) } }
        check("fetch_metadata_only_is_insufficient") {
            let value = ProviderFixtureSnapshot(state: baseline.state, events: baseline.events + [request(4, "provider.item.started", fetchID), request(5, "provider.item.completed", fetchID)])
            try reject { _ = try ProviderAuthenticationOracle.fetched(after: baseline, in: value) }
        }
        check("fetch_wrong_item_is_rejected") {
            let value = replacing(fetched, index: 4, with: event(5, "provider.fetch.completed", requestID: fetchID, eventDomain: domain, eventItem: "other"))
            try reject { _ = try ProviderAuthenticationOracle.fetched(after: baseline, in: value) }
        }
        check("fetch_wrong_domain_is_rejected") {
            let value = replacing(fetched, index: 4, with: event(5, "provider.fetch.completed", requestID: fetchID, eventDomain: "other", eventItem: item))
            try reject { _ = try ProviderAuthenticationOracle.fetched(after: baseline, in: value) }
        }
        check("fetch_wrong_process_is_rejected") {
            let value = replacing(fetched, index: 4, with: event(5, "provider.fetch.completed", process: fixtureProcess, requestID: fetchID, eventDomain: domain, eventItem: item))
            try reject { _ = try ProviderAuthenticationOracle.fetched(after: baseline, in: value) }
        }
        check("fetch_mismatched_requestID_is_rejected") {
            let value = replacing(fetched, index: 4, with: request(5, "provider.fetch.completed", uploadID))
            try reject { _ = try ProviderAuthenticationOracle.fetched(after: baseline, in: value) }
        }
        check("fetch_contradictory_terminal_is_rejected") {
            let value = ProviderFixtureSnapshot(state: fetched.state, events: fetched.events + [request(6, "provider.fetch.failed", fetchID, errorDomain: "NSCocoaErrorDomain", errorCode: 1)])
            try reject { _ = try ProviderAuthenticationOracle.fetched(after: baseline, in: value) }
        }
        check("upload_real_commit_matches_complete_XML") { _ = try ProviderAuthenticationOracle.uploaded(xml: southXML, after: fetched, in: uploaded) }
        check("upload_wrong_XML_is_rejected") { try reject { _ = try ProviderAuthenticationOracle.uploaded(xml: northXML, after: fetched, in: uploaded) } }
        check("upload_missing_backend_commit_is_rejected") {
            let value = replacing(uploaded, index: 6, with: event(7, "provider.metadata.updated", eventItem: item))
            try reject { _ = try ProviderAuthenticationOracle.uploaded(xml: southXML, after: fetched, in: value) }
        }
        check("upload_wrong_commit_process_is_rejected") {
            let value = replacing(uploaded, index: 6, with: event(7, "backend.updated", process: fixtureProcess, eventItem: item,
                details: ["sha256": uploaded.state.backendSHA256, "bytes": String(uploaded.state.backendByteCount)]))
            try reject { _ = try ProviderAuthenticationOracle.uploaded(xml: southXML, after: fetched, in: value) }
        }
        check("upload_unchanged_generation_is_rejected") {
            let value = ProviderFixtureSnapshot(state: state(xml: southXML, generation: 1), events: uploaded.events)
            try reject { _ = try ProviderAuthenticationOracle.uploaded(xml: southXML, after: fetched, in: value) }
        }
        check("eviction_completed_then_denial_preserves_backend") { try ProviderAuthenticationOracle.evictedAndDenied(after: uploaded, in: evicted) }
        check("eviction_without_completion_is_rejected") {
            let value = replacing(evicted, index: 9, with: event(10, "eviction.pending", eventItem: item))
            try reject { try ProviderAuthenticationOracle.evictedAndDenied(after: uploaded, in: value) }
        }
        check("failed_eviction_is_rejected") {
            let value = replacing(evicted, index: 9, with: event(10, "eviction.failed", eventItem: item, errorDomain: "NSFileProviderErrorDomain", errorCode: -1000))
            try reject { try ProviderAuthenticationOracle.evictedAndDenied(after: uploaded, in: value) }
        }
        check("denied_fetch_real_pair_in_Save_window") { _ = try denyProof(denied()) }
        check("denied_modify_real_pair_in_Save_window") { _ = try denyProof(denied(kind: "provider.modify")) }
        check("denial_before_Save_window_is_rejected") {
            try reject { _ = try denyProof(denied(started: "2026-11-05T12:00:10.000Z", terminal: "2026-11-05T12:00:11.000Z")) }
        }
        check("denial_after_error_window_is_rejected") {
            try reject { _ = try denyProof(denied(terminal: "2026-11-05T12:00:14.000Z")) }
        }
        check("denial_invalid_timestamp_is_rejected") {
            try reject { _ = try denyProof(denied(started: "not-a-date")) }
        }
        check("denial_reversed_timestamps_are_rejected") {
            try reject { _ = try denyProof(denied(started: "2026-11-05T12:00:13.000Z", terminal: "2026-11-05T12:00:12.000Z")) }
        }
        check("denial_metadata_only_is_insufficient") {
            try reject { _ = try denyProof(denied(kind: "provider.item")) }
        }
        check("denial_conflict_is_not_authentication") {
            let value = replacing(denied(), index: 12, with: request(13, "provider.fetch.denied", deniedID, errorDomain: "NSCocoaErrorDomain", errorCode: 516))
            try reject { _ = try denyProof(value) }
        }
        check("new_denial_requires_NSError_chain") { try reject { _ = try denyProof(denied(diagnostic: false)) } }
        check("new_denial_chain_root_must_match") {
            let value = replacing(denied(), index: 12, with: event(13, "provider.fetch.denied", requestID: deniedID, eventDomain: domain, eventItem: item,
                errorDomain: "NSFileProviderErrorDomain", errorCode: -1000, diagnosticCode: -2001))
            try reject { _ = try denyProof(value) }
        }
        check("old_prefix_error_without_chain_remains_decodable") {
            let old = ProviderFixtureSnapshot(state: state(), events: [event(1, "domain.registration.failed", errorDomain: "NSFileProviderErrorDomain", errorCode: -2001, diagnostic: false)])
            try ProviderAuthenticationOracle.validate(old)
        }
        check("denial_cannot_change_backend") {
            let value = ProviderFixtureSnapshot(state: state(xml: northXML, allowed: false, generation: 2), events: denied().events)
            try reject { _ = try denyProof(value) }
        }
        check("changed_earlier_event_is_rejected") {
            let value = replacing(denied(), index: 0, with: event(1, "prepare-tampered"))
            try reject { _ = try denyProof(value) }
        }
        check("truncated_or_reordered_sequence_is_rejected") {
            let value = replacing(denied(), index: 11, with: request(11, "provider.fetch.started", deniedID))
            try reject { _ = try denyProof(value) }
        }
        check("XML_fixture_structure_and_enum_change") {
            try ProviderCircuitDocument.read(sourceXML).requireFixture(facing: "east")
            try ProviderCircuitDocument.read(southXML).requireFixture(facing: "south")
            try ProviderCircuitDocument.read(northXML).requireOnlyFacingChanged(from: ProviderCircuitDocument.read(southXML))
        }
        check("XML_extra_wire_is_rejected") {
            let value = northXML.replacingOccurrences(of: "</circuit>", with: "<wire from=\"(300,100)\" to=\"(400,100)\"/></circuit>")
            try reject { try ProviderCircuitDocument.read(value).requireOnlyFacingChanged(from: ProviderCircuitDocument.read(southXML)) }
        }
        check("XML_extra_circuit_attribute_is_not_only_facing") {
            let value = northXML.replacingOccurrences(of: "<circuit name=\"provider_revocation\">", with: "<circuit name=\"provider_revocation\"><a name=\"clabel\" val=\"unexpected\"/>")
            try reject { try ProviderCircuitDocument.read(value).requireOnlyFacingChanged(from: ProviderCircuitDocument.read(southXML)) }
        }
        check("XML_project_attribute_change_is_not_only_facing") {
            let value = northXML.replacingOccurrences(of: "version=\"1.0\">", with: "version=\"2.0\">")
            try reject { try ProviderCircuitDocument.read(value).requireOnlyFacingChanged(from: ProviderCircuitDocument.read(southXML)) }
        }
        check("activation_census_exact_roles_and_labels_with_synthetic_flags") {
            let alert = try activationCensus()
            try require(Activation.expectedProviderName == "Logisim Revocation Fixture" &&
                        Activation.title == "Turn On “Logisim Revocation Fixture”?" &&
                        Activation.body == "To browse and add files, turn on Logisim Revocation Fixture." &&
                        Activation.turnOnLabel == "Turn On", "The activation policy must use the exact observed English strings")
            try require(alert.control.label == "Turn On “Logisim Revocation Fixture”?" && alert.control.role == .alert,
                        "Extract the observed Alert title and role")
            try require(alert.staticTexts.map(\.label) == ["Turn On “Logisim Revocation Fixture”?", "To browse and add files, turn on Logisim Revocation Fixture."],
                        "Extract only the two StaticText controls inside the Alert")
            try require(alert.buttons.map(\.label) == ["Cancel", "Turn On"], "The background Other named Cancel is not an Alert button")
            try require(try activationIndex([alert]) == 1, "The observed Turn On label identifies the second button")
        }
        check("activation_button_index_follows_label_not_position") {
            let alert = try activationCensus()
            let reversed = activationAlert(alert, buttons: Array(alert.buttons.reversed()))
            try require(try activationIndex([reversed]) == 0, "A reordered button array must return the Turn On index")
        }
        check("activation_absence_requires_selected_expected_provider") {
            try require(try activationIndex([]) == nil && activationIndex([], attempted: true) == nil,
                        "No alert means no activation action, including after an earlier attempt")
            try reject { _ = try activationIndex([], provider: "Another Provider") }
            try reject { _ = try activationIndex([], afterSelection: false) }
        }
        check("activation_wrong_provider_or_unselected_location_is_rejected") {
            let alert = try activationCensus()
            try reject { _ = try activationIndex([alert], provider: "Another Provider") }
            try reject { _ = try activationIndex([alert], afterSelection: false) }
        }
        check("activation_second_real_prompt_is_rejected") {
            let alert = try activationCensus()
            try reject { _ = try activationIndex([alert], attempted: true) }
        }
        check("activation_ambiguous_alerts_and_turn_on_buttons_are_rejected") {
            let alert = try activationCensus()
            try reject { _ = try activationIndex([alert, alert]) }
            for buttons in [alert.buttons + [alert.buttons[1]], [alert.buttons[1], alert.buttons[1]]] {
                let ambiguous = activationAlert(alert, buttons: buttons)
                try reject { _ = try activationIndex([ambiguous]) }
            }
        }
        check("activation_turn_on_requires_visible_hittable_enabled_inputs") {
            let alert = try activationCensus()
            let button = alert.buttons[1]
            for unavailable in [activationControl(button, visible: false), activationControl(button, hittable: false),
                                activationControl(button, enabled: false)] {
                let changed = activationAlert(alert, buttons: [alert.buttons[0], unavailable])
                try reject { _ = try activationIndex([changed]) }
            }
        }
        check("activation_exact_alert_title_body_and_button_labels_are_required") {
            let alert = try activationCensus()
            let changed = [
                activationAlert(alert, control: activationControl(alert.control, label: "Turn On Another Provider?")),
                activationAlert(alert, staticTexts: [activationControl(alert.staticTexts[0], label: "Turn On Logisim Revocation Fixture?"), alert.staticTexts[1]]),
                activationAlert(alert, staticTexts: [alert.staticTexts[0], activationControl(alert.staticTexts[1], label: "To browse files, turn on Logisim Revocation Fixture.")]),
                activationAlert(alert, buttons: [alert.buttons[0], activationControl(alert.buttons[1], label: "Turn on")]),
                activationAlert(alert, buttons: [activationControl(alert.buttons[0], label: "Cancelar"), alert.buttons[1]])
            ]
            for unexpected in changed { try reject { _ = try activationIndex([unexpected]) } }
        }
        check("activation_roles_and_alert_visibility_are_required") {
            let alert = try activationCensus()
            let changed = [
                activationAlert(alert, control: activationControl(alert.control, role: .other)),
                activationAlert(alert, control: activationControl(alert.control, visible: false)),
                activationAlert(alert, staticTexts: [activationControl(alert.staticTexts[0], role: .other), alert.staticTexts[1]]),
                activationAlert(alert, staticTexts: [alert.staticTexts[0], activationControl(alert.staticTexts[1], visible: false)]),
                activationAlert(alert, buttons: [activationControl(alert.buttons[0], role: .other), alert.buttons[1]]),
                activationAlert(alert, buttons: [alert.buttons[0], activationControl(alert.buttons[1], role: .staticText)])
            ]
            for unexpected in changed { try reject { _ = try activationIndex([unexpected]) } }
        }
        check("activation_missing_or_extra_controls_are_rejected") {
            let alert = try activationCensus()
            let changed = [
                activationAlert(alert, staticTexts: [alert.staticTexts[0]]),
                activationAlert(alert, staticTexts: alert.staticTexts + [alert.staticTexts[1]]),
                activationAlert(alert, buttons: [alert.buttons[1]]),
                activationAlert(alert, buttons: alert.buttons + [activationControl(alert.buttons[0], label: "Other")])
            ]
            for unexpected in changed { try reject { _ = try activationIndex([unexpected]) } }
        }
        check("fresh_fetch_prep_eviction_only_preserves_initial_backend") {
            try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: evictedOnlyForFreshFetch)
        }
        check("fresh_fetch_prep_can_follow_an_earlier_completed_fetch") {
            let value = ProviderFixtureSnapshot(state: fetched.state, events: fetched.events + [
                event(6, "eviction.requested", eventItem: item, details: ["denyAfterCompletion": "false", "backendSHA256": fetched.state.backendSHA256]),
                event(7, "eviction.completed", eventItem: item, details: ["backendSHA256": fetched.state.backendSHA256])])
            try ProviderAuthenticationOracle.evictedForFreshFetch(after: fetched, in: value)
        }
        check("fresh_fetch_prep_missing_request_is_rejected") {
            let value = replacing(evictedOnlyForFreshFetch, index: 3, with: event(4, "eviction.pending", eventItem: item))
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_missing_completion_is_rejected") {
            let value = replacing(evictedOnlyForFreshFetch, index: 4, with: event(5, "eviction.pending", eventItem: item))
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_old_proof_cannot_be_reused") {
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: evictedOnlyForFreshFetch, in: evictedOnlyForFreshFetch) }
        }
        check("fresh_fetch_prep_deny_true_is_rejected") {
            let value = replacing(evictedOnlyForFreshFetch, index: 3, with: event(4, "eviction.requested", eventItem: item,
                details: ["denyAfterCompletion": "true", "backendSHA256": baseline.state.backendSHA256]))
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_failed_eviction_is_rejected") {
            let value = ProviderFixtureSnapshot(state: baseline.state, events: evictedOnlyForFreshFetch.events + [
                event(6, "eviction.failed", eventItem: item, errorDomain: "NSFileProviderErrorDomain", errorCode: -1000)])
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_wrong_requested_item_is_rejected") {
            let value = replacing(evictedOnlyForFreshFetch, index: 3, with: event(4, "eviction.requested", eventItem: "other",
                details: ["denyAfterCompletion": "false", "backendSHA256": baseline.state.backendSHA256]))
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_wrong_completed_item_is_rejected") {
            let value = replacing(evictedOnlyForFreshFetch, index: 4, with: event(5, "eviction.completed", eventItem: "other",
                details: ["backendSHA256": baseline.state.backendSHA256]))
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_wrong_completed_process_is_rejected") {
            let value = replacing(evictedOnlyForFreshFetch, index: 4, with: event(5, "eviction.completed", process: "RevocationFixture:9999", eventItem: item,
                details: ["backendSHA256": baseline.state.backendSHA256]))
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_provider_process_cannot_impersonate_control") {
            let value = ProviderFixtureSnapshot(state: baseline.state, events: baseline.events + [
                event(4, "eviction.requested", process: providerProcess, eventItem: item,
                    details: ["denyAfterCompletion": "false", "backendSHA256": baseline.state.backendSHA256]),
                event(5, "eviction.completed", process: providerProcess, eventItem: item,
                    details: ["backendSHA256": baseline.state.backendSHA256])])
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_wrong_requested_hash_is_rejected") {
            let value = replacing(evictedOnlyForFreshFetch, index: 3, with: event(4, "eviction.requested", eventItem: item,
                details: ["denyAfterCompletion": "false", "backendSHA256": String(repeating: "0", count: 64)]))
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_wrong_completed_hash_is_rejected") {
            let value = replacing(evictedOnlyForFreshFetch, index: 4, with: event(5, "eviction.completed", eventItem: item,
                details: ["backendSHA256": String(repeating: "0", count: 64)]))
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_request_NSError_is_rejected") {
            let value = replacing(evictedOnlyForFreshFetch, index: 3, with: event(4, "eviction.requested", eventItem: item,
                details: ["denyAfterCompletion": "false", "backendSHA256": baseline.state.backendSHA256],
                errorDomain: "NSFileProviderErrorDomain", errorCode: -1000))
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_completion_NSError_is_rejected") {
            let value = replacing(evictedOnlyForFreshFetch, index: 4, with: event(5, "eviction.completed", eventItem: item,
                details: ["backendSHA256": baseline.state.backendSHA256], errorDomain: "NSFileProviderErrorDomain", errorCode: -1000))
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_completion_before_request_is_rejected") {
            let value = ProviderFixtureSnapshot(state: baseline.state, events: baseline.events + [
                event(4, "eviction.completed", eventItem: item, details: ["backendSHA256": baseline.state.backendSHA256]),
                event(5, "eviction.requested", eventItem: item, details: ["denyAfterCompletion": "false", "backendSHA256": baseline.state.backendSHA256])])
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_altered_backend_is_rejected") {
            let value = ProviderFixtureSnapshot(state: state(xml: southXML), events: evictedOnlyForFreshFetch.events)
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_altered_authentication_is_rejected") {
            let value = ProviderFixtureSnapshot(state: state(allowed: false), events: evictedOnlyForFreshFetch.events)
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_altered_generation_is_rejected") {
            let value = ProviderFixtureSnapshot(state: state(generation: 2), events: evictedOnlyForFreshFetch.events)
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_authentication_event_is_forbidden_even_if_state_matches") {
            let value = ProviderFixtureSnapshot(state: baseline.state, events: baseline.events + [
                event(4, "eviction.requested", eventItem: item, details: ["denyAfterCompletion": "false", "backendSHA256": baseline.state.backendSHA256]),
                event(5, "authentication.changed", details: ["allowed": "true"]),
                event(6, "eviction.completed", eventItem: item, details: ["backendSHA256": baseline.state.backendSHA256])])
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_backend_update_event_is_forbidden_even_if_state_matches") {
            let value = ProviderFixtureSnapshot(state: baseline.state, events: evictedOnlyForFreshFetch.events + [
                event(6, "backend.updated", eventItem: item, details: ["sha256": baseline.state.backendSHA256, "bytes": "552"])])
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_multiple_requests_are_rejected") {
            let value = ProviderFixtureSnapshot(state: baseline.state, events: baseline.events + [
                event(4, "eviction.requested", eventItem: item, details: ["denyAfterCompletion": "false", "backendSHA256": baseline.state.backendSHA256]),
                event(5, "eviction.requested", eventItem: item, details: ["denyAfterCompletion": "false", "backendSHA256": baseline.state.backendSHA256]),
                event(6, "eviction.completed", eventItem: item, details: ["backendSHA256": baseline.state.backendSHA256])])
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_multiple_completions_are_rejected") {
            let value = ProviderFixtureSnapshot(state: baseline.state, events: evictedOnlyForFreshFetch.events + [
                event(6, "eviction.completed", eventItem: item, details: ["backendSHA256": baseline.state.backendSHA256])])
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_multiple_evictions_are_rejected") {
            let value = ProviderFixtureSnapshot(state: baseline.state, events: evictedOnlyForFreshFetch.events + [
                event(6, "eviction.requested", eventItem: item, details: ["denyAfterCompletion": "false", "backendSHA256": baseline.state.backendSHA256]),
                event(7, "eviction.completed", eventItem: item, details: ["backendSHA256": baseline.state.backendSHA256])])
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_changed_prefix_is_rejected") {
            let value = replacing(evictedOnlyForFreshFetch, index: 0, with: event(1, "prepare-tampered"))
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: baseline, in: value) }
        }
        check("fresh_fetch_prep_noninitial_baseline_is_rejected") {
            let previous = ProviderFixtureSnapshot(state: state(allowed: false), events: baseline.events)
            let value = ProviderFixtureSnapshot(state: previous.state, events: evictedOnlyForFreshFetch.events)
            try reject { try ProviderAuthenticationOracle.evictedForFreshFetch(after: previous, in: value) }
        }
        let summary: [String: Any] = ["scope": "Pure macOS oracle checks; no Native/UI/FileProvider execution", "passed": passed, "failures": failures,
                                    "passedCount": passed.count, "failedCount": failures.count]
        let data = try! JSONSerialization.data(withJSONObject: summary, options: [.prettyPrinted, .sortedKeys])
        print(String(decoding: data, as: UTF8.self))
        exit(failures.isEmpty ? 0 : 1)
    }
}
