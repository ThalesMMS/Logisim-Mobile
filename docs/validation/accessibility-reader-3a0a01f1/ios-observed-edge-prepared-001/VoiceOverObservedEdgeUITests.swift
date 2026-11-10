import XCTest
import UIKit

/// Collects evidence. A test PASS alone does not qualify reader focus identity or order.
@available(iOS 27.0, *)
@MainActor
final class VoiceOverObservedEdgeUITests: XCTestCase {
    private enum CaptureError: Error {
        case appNotForeground
        case readerNotEnabled
        case emptySpeech(String)
    }

    func testCaptureForwardBackwardEdgeWithRestoration() throws {
        continueAfterFailure = true
        let app = XCUIApplication(bundleIdentifier: "thalesmms.logisim.mobile")
        let initialAppState = app.state
        guard initialAppState == .runningForeground else { throw CaptureError.appNotForeground }
        let service = XCUIDevice.shared.voiceOverService
        let originallyEnabled = service.isEnabled
        if let error = attachJSON("original-state", [
            "originallyEnabled": originallyEnabled,
            "initialAppStateRaw": initialAppState.rawValue,
            "testProcessUptimeSeconds": ProcessInfo.processInfo.systemUptime,
            "qualification": "CAPTURE_ONLY_REQUIRES_INDEPENDENT_FOCUS_REVIEW",
        ]) { throw error }
        var operationError: Error?
        var restorationError: Error?
        do {
            if !originallyEnabled { try service.enable() }
            guard service.isEnabled else { throw CaptureError.readerNotEnabled }
            try capture("S0-current", app: app, service: service) { try service.currentSpeech() }
            try capture("S1-forward", app: app, service: service) { try service.moveForward() }
            try capture("S2-backward", app: app, service: service) { try service.moveBackward() }
        } catch {
            operationError = error
            recordError("operation-error", error, service: service)
        }
        do {
            if service.isEnabled != originallyEnabled {
                if originallyEnabled { try service.enable() }
                else { try service.disable() }
            }
        } catch {
            restorationError = error
            recordError("restoration-error", error, service: service)
        }
        let finallyEnabled = service.isEnabled
        attachJSON("restoration-state", [
            "originallyEnabled": originallyEnabled,
            "finallyEnabled": finallyEnabled,
            "operationFailed": operationError != nil,
            "restorationFailed": restorationError != nil,
            "testProcessUptimeSeconds": ProcessInfo.processInfo.systemUptime,
            "qualification": "CAPTURE_ONLY_REQUIRES_INDEPENDENT_FOCUS_REVIEW",
        ])
        XCTAssertEqual(finallyEnabled, originallyEnabled, "Restore the original reader setting")
        if let operationError { throw operationError }
        if let restorationError { throw restorationError }
    }

    private func capture(
        _ step: String,
        app: XCUIApplication,
        service: XCUIVoiceOverService,
        operation: () throws -> XCUIVoiceOverService.Output
    ) throws {
        guard service.isEnabled else { throw CaptureError.readerNotEnabled }
        guard app.state == .runningForeground else { throw CaptureError.appNotForeground }
        let started = ProcessInfo.processInfo.systemUptime
        let speech = try operation()
        let speechReturned = ProcessInfo.processInfo.systemUptime
        let before = XCUIScreen.main.screenshot()
        let snapshot = try app.snapshot()
        let after = XCUIScreen.main.screenshot()
        let ended = ProcessInfo.processInfo.systemUptime
        attachScreenshot("\(step)-before-tree", before)
        attachScreenshot("\(step)-after-tree", after)
        if let error = attachJSON(step, [
            "step": step,
            "utterance": speech.utterance,
            "serviceEnabledAfterCapture": service.isEnabled,
            "appStateAfterCaptureRaw": app.state.rawValue,
            "captureStartedUptimeSeconds": started,
            "speechReturnedUptimeSeconds": speechReturned,
            "captureEndedUptimeSeconds": ended,
            "beforeScreenshot": screenshotMetadata(before),
            "afterScreenshot": screenshotMetadata(after),
            "tree": describe(snapshot, path: []),
            "treePathMeaning": "Snapshot address only; not VoiceOver traversal order",
            "readerFocusIdentity": "NOT_RETURNED_BY_PUBLIC_SERVICE_API",
            "qualification": "CAPTURE_ONLY_REQUIRES_INDEPENDENT_FOCUS_REVIEW",
        ]) { throw error }
        guard service.isEnabled else { throw CaptureError.readerNotEnabled }
        guard app.state == .runningForeground else { throw CaptureError.appNotForeground }
        guard !speech.utterance.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw CaptureError.emptySpeech(step)
        }
    }

    private func describe(_ node: XCUIElementSnapshot, path: [Int]) -> [String: Any] {
        var data: [String: Any] = [
            "snapshotPath": path,
            "identifier": node.identifier,
            "label": node.label,
            "title": node.title,
            "elementTypeRaw": node.elementType.rawValue,
            "enabled": node.isEnabled,
            "selected": node.isSelected,
            "frameInScreenPoints": [
                "x": node.frame.origin.x, "y": node.frame.origin.y,
                "width": node.frame.width, "height": node.frame.height,
            ],
            "children": node.children.enumerated().map { index, child in
                describe(child, path: path + [index])
            },
        ]
        if let value = node.value { data["valueDescription"] = String(describing: value) }
        return data
    }

    private func screenshotMetadata(_ screenshot: XCUIScreenshot) -> [String: Any] {
        let image = screenshot.image
        return [
            "imageWidthPoints": image.size.width,
            "imageHeightPoints": image.size.height,
            "imageScale": image.scale,
            "pixelWidth": image.cgImage?.width ?? 0,
            "pixelHeight": image.cgImage?.height ?? 0,
        ]
    }

    private func recordError(_ name: String, _ error: Error, service: XCUIVoiceOverService) {
        let native = error as NSError
        attachJSON(name, [
            "domain": native.domain, "code": native.code,
            "description": native.localizedDescription,
            "serviceEnabled": service.isEnabled,
            "testProcessUptimeSeconds": ProcessInfo.processInfo.systemUptime,
        ])
    }

    private func attachScreenshot(_ name: String, _ screenshot: XCUIScreenshot) {
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "voiceover-observed-edge-\(name).png"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @discardableResult
    private func attachJSON(_ name: String, _ data: [String: Any]) -> Error? {
        do {
            let serialized = try JSONSerialization.data(withJSONObject: data, options: [.prettyPrinted, .sortedKeys])
            let attachment = XCTAttachment(string: String(decoding: serialized, as: UTF8.self))
            attachment.name = "voiceover-observed-edge-\(name).json"
            attachment.lifetime = .keepAlways
            add(attachment)
            return nil
        } catch {
            let attachment = XCTAttachment(string: "Serialization failed: \(error)\n\(data)")
            attachment.name = "voiceover-observed-edge-\(name)-serialization-error.txt"
            attachment.lifetime = .keepAlways
            add(attachment)
            XCTFail("Could not serialize observation \(name): \(error)")
            return error
        }
    }
}
