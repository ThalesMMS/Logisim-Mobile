import Foundation
import XCTest
import UIKit
import CryptoKit

/// Production I/O controls and file import only. The runner stages Run.fixtures["io"].
/// Keep this source outside the target until the owner supplies the fixture/resource mapping.
final class IoEndToEndTests: NativeUIHarness {
    private typealias Object = [String: Any]
    private var contract: Object = [:]
    private var run: Object = [:]
    private var exportNumber = 0
    private var expectedAttributes: [String: [String: String]] = [:]

    override func setUpWithError() throws {
        try super.setUpWithError()
        let bundle = Bundle(for: Self.self)
        let source = try XCTUnwrap(bundle.url(forResource: "io-actions-v1", withExtension: "json"))
        contract = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: source)) as? Object)
        XCTAssertEqual(contract["schemaVersion"] as? Int, 1)
        let configuration = try XCTUnwrap(bundle.url(forResource: "Run", withExtension: "json"))
        run = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: configuration)) as? Object)
    }

    func testIoDisplaySignalsAndNativeDrawing() throws {
        try openFixture()
        let transitions: [Object] = try item(contract, "displayTransitions")
        for transition in transitions {
            try activate(item(transition, "source"))
            try assertOutputs(item(transition, "outputs"))
            let label: String = try item(transition, "visual")
            let name: String = try item(transition, "id")
            try captureVisual(label, name: "io-display-" + name)
            attach(try item(transition, "expect") as String, name: "visual-review-" + name)
        }
        try checkpoint("io-display-transitions")
    }

    func testIoDipLastExportAndReopen() throws {
        try openFixture()
        let details: Object = try item(contract, "dip")
        let label: String = try item(details, "label")
        try properties(label)
        let changes: [Object] = try item(details, "switches")
        for change in changes {
            let number: Int = try item(change, "number")
            let before: String = try item(change, "before")
            let after: String = try item(change, "after")
            try click("io_a11y_switch", arguments: ["label": label, "number": String(number)],
                      nested: ["state": translations("io_switch_" + before)])
            _ = try reveal(labels("io_a11y_switch", arguments: ["label": label, "number": String(number)],
                                  nested: ["state": translations("io_switch_" + after)]))
        }
        try closeProperties()
        try assertOutputs(item(details, "outputs"))
        let persisted: [String: String] = try item(details, "persistedAttributes")
        try checkpoint("io-dip-last", attributes: [label: persisted])
        let dimension: Object = try item(details, "dimension")
        let name: String = try item(dimension, "name")
        try editProperty(label, name: name, value: item(dimension, "value"))
        try checkpoint("io-dip-four", attributes: [label: ["number": "4", "positions": "2"]])
        try editProperty(label, name: name, value: item(dimension, "invalid"), reject: true)
        try checkpoint("io-dip-invalid-preserved", attributes: [label: ["number": "4", "positions": "2"]])
        try editProperty(label, name: name, value: item(dimension, "restore"))
        try tapKey("a11y_reset_simulation", toolbar: "editor-simulation-toolbar")
        try closeToolbarMenu()
        try assertOutputs(item(details, "outputs"))
        let xml = try checkpoint("io-dip-export", attributes: [label: persisted])
        try openXml(Data(xml.utf8), name: "io-dip-reopen")
        try assertOutputs(item(details, "outputs"))
        try checkpoint("io-dip-reopened", attributes: [label: persisted])
    }

    func testIoMomentaryControlsAndJoystick() throws {
        try openFixture()
        let pointer: Object = try item(contract, "pointer")
        let button: Object = try item(pointer, "button")
        let buttonLabel: String = try item(button, "label")
        try momentary(component: buttonLabel, key: buttonLabel, seconds: item(button, "holdSeconds"))
        try assertOutputs([try item(button, "probe"): try item(button, "released")])
        let keypad: Object = try item(pointer, "keypad")
        try momentary(component: item(keypad, "label"), key: item(keypad, "key"), seconds: item(keypad, "holdSeconds"))
        try assertOutputs([try item(keypad, "probe"): try item(keypad, "released")])
        let joystick: Object = try item(pointer, "joystick")
        let label: String = try item(joystick, "label")
        try properties(label)
        try reveal(["→"]).tap()
        _ = try reveal(labels("io_a11y_joystick", arguments: ["label": label, "x": "14", "y": "0"]))
        try closeProperties()
        try assertOutputs(item(joystick, "right"))
        try properties(label)
        try tapKey("io_center")
        let control = try reveal(labels("io_a11y_joystick", arguments: ["label": label, "x": "0", "y": "0"]))
        checkFrame(control, name: "io-joystick")
        let drag: [String: Double] = try item(joystick, "drag")
        let start = control.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let end = control.coordinate(withNormalizedOffset: CGVector(dx: try XCTUnwrap(drag["x"]), dy: try XCTUnwrap(drag["y"])))
        let request: Object = ["pressBeforeDragSeconds": 0.2, "holdAtEndSeconds": 2.0, "velocity": "slow",
                               "endNormalized": ["x": try XCTUnwrap(drag["x"]), "y": try XCTUnwrap(drag["y"])]]
        try recordTouchRegion(control, component: label, phase: "before-drag", request: request,
                              expectation: joystickVideoExpectation(drag))
        let dragStarted = Date().timeIntervalSince1970
        start.press(forDuration: 0.2, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 2.0)
        let dragReturned = Date().timeIntervalSince1970
        let centered = try reveal(labels("io_a11y_joystick", arguments: ["label": label, "x": "0", "y": "0"]))
        try recordTouchRegion(centered, component: label, phase: "after-release",
                              request: request.merging(["apiStartedUnixSeconds": dragStarted, "apiReturnedUnixSeconds": dragReturned]) { _, new in new },
                              expectation: joystickVideoExpectation(drag))
        screenshot("io-joystick-released")
        try closeProperties()
        try assertOutputs(item(joystick, "centered"))
        try checkpoint("io-pointer-controls")
        attach("Retain during-touch video for Button/keypad pressed rendering and joystick movement. " +
               "These assertions observe released motor state and a latched joystick nudge; released state alone does not prove the held frame.",
               name: "during-touch-review-required")
    }

    func testIoCanvasButtonHeldOutputVideo() throws {
        orient(.portrait)
        let paths: [String: String] = try item(run, "fixtures")
        let path = URL(fileURLWithPath: try XCTUnwrap(paths["ioHeld"], "Stage examples/io-held-output.circ in Run.fixtures.ioHeld"))
        let fixture = try Data(contentsOf: path)
        let fixtureHash = SHA256.hash(data: fixture).map { String(format: "%02x", $0) }.joined()
        let fixtureDocument = try assertHeldFixtureDocument(String(decoding: fixture, as: UTF8.self))
        try openXml(fixture, name: "io-canvas-held-output")
        try tapKey("a11y_simulation_event_step", toolbar: "editor-simulation-toolbar")
        try closeToolbarMenu()
        try assertOutputs(["OUT_HELD": "0", "REF_LOW": "0", "REF_HIGH": "1"])
        try tapKey("action_simulate", toolbar: "editor-command-toolbar")
        try closeToolbarMenu()
        try tapKey("a11y_fit_circuit", toolbar: "editor-command-toolbar")
        try closeToolbarMenu()
        let before = try heldCanvasGeometry()
        try assertOutputs(["OUT_HELD": "0", "REF_LOW": "0", "REF_HIGH": "1"])
        let preDescription = try XCTUnwrap(currentCanvasText())
        let preObserved = Date().timeIntervalSince1970
        attach(app.debugDescription, name: "io-canvas-held-before-tree")
        screenshot("io-canvas-held-before-zero")
        // Leave stable intervals for the owner's passive pre/post captures.
        Thread.sleep(forTimeInterval: 0.75)
        let press = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
            dx: before.buttonCenter.x - app.frame.minX, dy: before.buttonCenter.y - app.frame.minY))
        let started = Date().timeIntervalSince1970
        press.press(forDuration: 4)
        let returned = Date().timeIntervalSince1970
        Thread.sleep(forTimeInterval: 0.75)
        try assertOutputs(["OUT_HELD": "0", "REF_LOW": "0", "REF_HIGH": "1"])
        let postDescription = try XCTUnwrap(currentCanvasText())
        let postObserved = Date().timeIntervalSince1970
        let after = try heldCanvasGeometry()
        let comparedFrames = [("button", before.buttonFrame, after.buttonFrame),
                              ("probe", before.probeFrame, after.probeFrame),
                              ("probe glyph", before.valueFrame, after.valueFrame),
                              ("reference low", before.lowFrame, after.lowFrame),
                              ("reference high", before.highFrame, after.highFrame),
                              ("reference low glyph", before.lowValueFrame, after.lowValueFrame),
                              ("reference high glyph", before.highValueFrame, after.highValueFrame)]
        let geometryStable = before.applicationFrame == after.applicationFrame && comparedFrames.allSatisfy { _, first, last in
            [first.minX - last.minX, first.minY - last.minY, first.width - last.width, first.height - last.height]
                .allSatisfy { $0.isFinite && abs($0) <= 0.5 }
        }
        XCTAssertEqual(before.applicationFrame, after.applicationFrame, "The recording coordinate space must stay fixed")
        for (name, first, last) in comparedFrames {
            XCTAssertEqual(first.minX, last.minX, accuracy: 0.5, "Stable \(name) x")
            XCTAssertEqual(first.minY, last.minY, accuracy: 0.5, "Stable \(name) y")
            XCTAssertEqual(first.width, last.width, accuracy: 0.5, "Stable \(name) width")
            XCTAssertEqual(first.height, last.height, accuracy: 0.5, "Stable \(name) height")
        }
        screenshot("io-canvas-held-after-zero")
        attach(app.debugDescription, name: "io-canvas-held-after-tree")
        var record: Object = [
            "recordType": "native-output-probe-sequence", "schemaVersion": 1,
            "testCase": "IoEndToEndTests.testIoCanvasButtonHeldOutputVideo", "nativeXCTestName": name,
            "targetEnvironment": "simulator", "timeSource": "Foundation.Date Unix seconds in iOS Simulator",
            "fixtureName": path.lastPathComponent, "fixtureSha256": fixtureHash,
            "expectedSequence": ["0", "1", "0"],
            "nativeBeforeDescription": preDescription, "nativeAfterDescription": postDescription,
            "probe": ["label": "OUT_HELD", "kind": "OUTPUT", "width": 1, "radix": "2",
                      "valueRoiNormalizedToApplication": normalizedRect(before.valueFrame, in: before.applicationFrame),
                      "geometryStable": geometryStable],
            "referenceProbes": [
                "low": ["label": "REF_LOW", "kind": "OUTPUT", "width": 1, "radix": "2", "expected": "0",
                        "observedBefore": "0", "observedAfter": "0", "geometryStable": geometryStable,
                        "valueRoiNormalizedToApplication": normalizedRect(before.lowValueFrame, in: before.applicationFrame)],
                "high": ["label": "REF_HIGH", "kind": "OUTPUT", "width": 1, "radix": "2", "expected": "1",
                         "observedBefore": "1", "observedAfter": "1", "geometryStable": geometryStable,
                         "valueRoiNormalizedToApplication": normalizedRect(before.highValueFrame, in: before.applicationFrame)]
            ],
            "applicationFramePoints": frameObject(before.applicationFrame),
            "geometryBefore": before.json, "geometryAfter": after.json,
            "gesture": ["apiStartedUnixSeconds": started, "apiReturnedUnixSeconds": returned,
                        "requestedHoldSeconds": 4, "preObservedUnixSeconds": preObserved,
                        "postObservedUnixSeconds": postObserved],
            "limits": ["maxPressLatencySeconds": 0.75, "maxReleaseLatencySeconds": 0.5,
                       "minHeldSampleSeparationSeconds": 1, "maxHeldEndGapSeconds": 0.75],
            "timingMeaning": "Times bound the synchronous XCTest API call, not measured hardware DOWN/UP. Latency limits refer to these API bounds.",
            "qualification": "Native released output only. Require separate timestamped capture classification of literal OUT_HELD glyph 0,1,1,0 and temporal review before qualifying held output."
        ]
        #if !targetEnvironment(simulator)
        XCTFail("This evidence case requires the iOS Simulator capture interval collector")
        #endif
        if let simulator = ProcessInfo.processInfo.environment["SIMULATOR_UDID"] ?? run["simulatorUDID"] as? String {
            record["simulatorUDID"] = simulator
        }
        attach(try json(record), name: "io-canvas-held-output-sequence.json")
        let source = XCTAttachment(data: fixture, uniformTypeIdentifier: "public.xml")
        source.name = "io-canvas-held-source.circ"
        source.lifetime = .keepAlways
        add(source)
        let exported = try assertHeldFixtureDocument(observedProjectXML(attachmentName: "io-canvas-held-after.circ"))
        XCTAssertEqual(exported.components, fixtureDocument.components, "The transient gesture must not edit any saved attribute")
    }

    private func assertHeldFixtureDocument(_ xml: String) throws -> IoObservedDocument {
        let observed = try IoObservedDocument.parse(xml)
        XCTAssertEqual(observed.components.count, 6)
        XCTAssertEqual(observed.wires, 3)
        XCTAssertEqual(Set(observed.wireEndpoints), Set(["(140,120)|(240,120)", "(140,200)|(240,200)", "(140,280)|(240,280)"]))
        for (label, value) in [("CONST_LOW", "0x0"), ("CONST_HIGH", "0x1")] {
            XCTAssertEqual(observed.components[label]?["name"], "Constant")
            XCTAssertEqual(observed.components[label]?["width"], "1")
            XCTAssertEqual(observed.components[label]?["value"], value)
        }
        for label in ["OUT_HELD", "REF_LOW", "REF_HIGH"] {
            for (attribute, expected) in ["name": "Pin", "type": "output", "width": "1", "radix": "2", "facing": "west", "appearance": "classic", "labelfont": "SansSerif bold 16"] {
                XCTAssertEqual(observed.components[label]?[attribute], expected, "The output references must use the same literal glyph format")
            }
        }
        XCTAssertEqual(observed.components["BTN_HELD"]?["name"], "Button")
        XCTAssertEqual(observed.components["BTN_HELD"]?["press"], "active")
        return observed
    }

    private struct HeldCanvasGeometry {
        let applicationFrame: CGRect
        let buttonFrame: CGRect
        let probeFrame: CGRect
        let bodyFrame: CGRect
        let valueFrame: CGRect
        let lowFrame: CGRect
        let highFrame: CGRect
        let lowValueFrame: CGRect
        let highValueFrame: CGRect
        let scale: CGFloat
        var buttonCenter: CGPoint { CGPoint(x: buttonFrame.midX, y: buttonFrame.midY) }
        var json: [String: Any] {
            ["buttonNativeFramePoints": ["x": buttonFrame.minX, "y": buttonFrame.minY, "width": buttonFrame.width, "height": buttonFrame.height],
             "probeNativeFramePoints": ["x": probeFrame.minX, "y": probeFrame.minY, "width": probeFrame.width, "height": probeFrame.height],
             "probeBodyFramePoints": ["x": bodyFrame.minX, "y": bodyFrame.minY, "width": bodyFrame.width, "height": bodyFrame.height],
             "probeValueFramePoints": ["x": valueFrame.minX, "y": valueFrame.minY, "width": valueFrame.width, "height": valueFrame.height],
             "referenceLowFramePoints": ["x": lowFrame.minX, "y": lowFrame.minY, "width": lowFrame.width, "height": lowFrame.height],
             "referenceHighFramePoints": ["x": highFrame.minX, "y": highFrame.minY, "width": highFrame.width, "height": highFrame.height],
             "referenceLowValueFramePoints": ["x": lowValueFrame.minX, "y": lowValueFrame.minY, "width": lowValueFrame.width, "height": lowValueFrame.height],
             "referenceHighValueFramePoints": ["x": highValueFrame.minX, "y": highValueFrame.minY, "width": highValueFrame.width, "height": highValueFrame.height],
             "pointsPerCircuitUnit": scale, "worldCenterDistance": 120,
             "geometryDerivation": "Native targets have a minimum touch size. Scale comes from BTN_HELD/OUT_HELD centers at world x130/x250. The Pin body is 20x20 world units; its value ROI is inset 20 percent to exclude label and oval."]
        }
    }

    private func heldCanvasGeometry() throws -> HeldCanvasGeometry {
        XCTAssertFalse(identified("editor-inspector-sheet").exists, "Keep the functional probe visible on Canvas")
        XCTAssertTrue(app.keyboards.allElementsBoundByIndex.allSatisfy { !$0.exists }, "No software keyboard may cover the output")
        func component(_ label: String) throws -> XCUIElement {
            let candidates = app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label + ", "))
                .allElementsBoundByIndex.filter { $0.exists && $0.isHittable && $0.identifier.hasPrefix("canvas-component:") }
            guard candidates.count == 1, let candidate = candidates.first else {
                attach(try json(["recordType": "io-canvas-held-preparation", "status": "NOT_RUN",
                                 "testCase": "IoEndToEndTests.testIoCanvasButtonHeldOutputVideo",
                                 "missingCapability": "Unique reachable native Canvas component geometry",
                                 "component": label, "matchingNodes": candidates.count,
                                 "qualification": "No frame, touch or functional result is inferred."]),
                       name: "io-canvas-held-preparation.json")
                XCTFail("Preparation blocked: the public native tree must expose one visible \(label) frame")
                throw HarnessFailure.missingControl
            }
            return candidate
        }
        let button = try component("BTN_HELD")
        let output = try component("OUT_HELD")
        let low = try component("REF_LOW")
        let high = try component("REF_HIGH")
        checkFrame(button, name: "held-canvas-button")
        checkFrame(output, name: "held-canvas-output")
        checkFrame(low, name: "held-canvas-reference-low")
        checkFrame(high, name: "held-canvas-reference-high")
        let first = button.frame, last = output.frame
        let scale = (last.midX - first.midX) / 120
        XCTAssertGreaterThan(scale, 0)
        XCTAssertEqual(first.midY, last.midY, accuracy: 0.5, "The known fixture centers must remain on one horizontal line")
        func bodyFrame(_ native: CGRect) -> CGRect {
            CGRect(x: native.midX - 10 * scale, y: native.midY - 10 * scale, width: 20 * scale, height: 20 * scale)
        }
        let body = bodyFrame(last)
        let value = body.insetBy(dx: body.width * 0.2, dy: body.height * 0.2)
        let visibleCanvas = try canvas().frame.intersection(app.frame)
        for native in [first, last, low.frame, high.frame] {
            XCTAssertTrue(visibleCanvas.contains(native), "Button, target and references must fit inside the visible Canvas")
        }
        for (label, reference, worldDelta) in [("low", low.frame, CGFloat(80)), ("high", high.frame, CGFloat(160))] {
            XCTAssertEqual(reference.midX, last.midX, accuracy: 0.5, "Reference \(label) must use the same output column")
            XCTAssertEqual(reference.midY - last.midY, worldDelta * scale, accuracy: 1, "Reference \(label) must share the same scale")
            XCTAssertTrue(reference.insetBy(dx: -0.5, dy: -0.5).contains(bodyFrame(reference)), "Reference body must fit its native target")
        }
        XCTAssertTrue(last.insetBy(dx: -0.5, dy: -0.5).contains(body), "Pin body must lie inside its native target")
        XCTAssertGreaterThanOrEqual(value.width, 12, "The literal output glyph must be large enough for digit classification")
        XCTAssertGreaterThanOrEqual(value.height, 12)
        let lowBody = bodyFrame(low.frame), highBody = bodyFrame(high.frame)
        return HeldCanvasGeometry(applicationFrame: app.frame, buttonFrame: first, probeFrame: last,
                                  bodyFrame: body, valueFrame: value, lowFrame: low.frame, highFrame: high.frame,
                                  lowValueFrame: lowBody.insetBy(dx: lowBody.width * 0.2, dy: lowBody.height * 0.2),
                                  highValueFrame: highBody.insetBy(dx: highBody.width * 0.2, dy: highBody.height * 0.2), scale: scale)
    }

    private func frameObject(_ frame: CGRect) -> Object {
        ["x": frame.minX, "y": frame.minY, "width": frame.width, "height": frame.height]
    }

    private func normalizedRect(_ frame: CGRect, in application: CGRect) -> Object {
        ["x": (frame.minX - application.minX) / application.width,
         "y": (frame.minY - application.minY) / application.height,
         "width": frame.width / application.width, "height": frame.height / application.height]
    }

    func testIoKeyboardTtyAndCapacity() throws {
        try openFixture()
        let keyboard: Object = try item(contract, "keyboard")
        let label: String = try item(keyboard, "label")
        try enterKeyboard(try item(keyboard, "text"), component: label)
        try assertOutputs(item(keyboard, "queued"))
        for phase in ["afterFirst", "afterSecond"] {
            try stepCharacter()
            let expected: Object = try item(keyboard, phase)
            try assertOutputs(item(expected, "outputs"))
            try captureVisual(item(keyboard, "tty"), name: "io-tty-" + phase)
            attach(try json(item(expected, "ttyRows") as [String]), name: "expected-tty-" + phase)
        }
        for phase in ["backspace", "formFeed"] {
            let expected: Object = try item(keyboard, phase)
            try properties(label)
            try tapKey(item(expected, "controlKey"))
            try closeProperties()
            try assertOutputs(["OUT_KEYBOARD_DATA": item(expected, "data"), "OUT_KEYBOARD_AVAILABLE": "1"])
            try stepCharacter()
            try captureVisual(item(keyboard, "tty"), name: "io-tty-" + phase)
            attach(try json(item(expected, "ttyRows") as [String]), name: "expected-tty-" + phase)
        }
        let capacity: Object = try item(keyboard, "capacityEdit")
        try editProperty(label, name: item(capacity, "name"), value: item(capacity, "value"))
        try properties(label)
        let field = try keyboardField(label)
        try replacePresentedText(field, with: item(capacity, "text"),
                                 identity: .keyboardBuffer(componentLabel: label),
                                 expectedFinalValue: item(capacity, "expected"))
        XCTAssertEqual(field.value as? String, try item(capacity, "expected") as String)
        try closeProperties()
        try checkpoint("io-keyboard-capacity", attributes: [label: ["buflen": "4"]])
    }

    func testIoDimensionChangesRemainExportable() throws {
        try openFixture()
        let changes: [Object] = try item(contract, "dimensionEdits")
        for change in changes {
            let label: String = try item(change, "label")
            let name: String = try item(change, "name")
            let value: String = try item(change, "value")
            try editProperty(label, name: name, value: value)
            try checkpoint("io-dimension-" + label.lowercased() + "-" + name, attributes: [label: [name: value]])
            try captureVisual(label, name: "io-dimension-render-" + label.lowercased() + "-" + name)
            let restored: String = try item(change, "restore")
            try editProperty(label, name: name, value: restored)
            try checkpoint("io-restored-" + label.lowercased() + "-" + name, attributes: [label: [name: restored]])
            try assertOutputs(item(contract, "baselineOutputs"))
        }
        try checkpoint("io-dimensions-restored")
    }

    private func item<T>(_ object: Object, _ key: String) throws -> T {
        try XCTUnwrap(object[key] as? T, "Missing \(key) in the I/O contract")
    }

    private func translations(_ key: String, arguments: [String: String] = [:], nested: [String: [String: String]] = [:]) throws -> [String: String] {
        let entry = try XCTUnwrap(catalog[key], "Missing I/O control label \(key)")
        var result: [String: String] = [:]
        for (language, value) in entry {
            guard var text = value as? String else { continue }
            for (key, value) in arguments { text = text.replacingOccurrences(of: "{\(key)}", with: value) }
            for (key, values) in nested { text = text.replacingOccurrences(of: "{\(key)}", with: try XCTUnwrap(values[language] ?? values["en"])) }
            result[language] = text
        }
        return result
    }

    private func labels(_ key: String, arguments: [String: String] = [:], nested: [String: [String: String]] = [:]) throws -> [String] {
        Array(try translations(key, arguments: arguments, nested: nested).values)
    }

    private func click(_ key: String, arguments: [String: String] = [:], nested: [String: [String: String]] = [:], region: String? = nil) throws {
        let element = try reveal(labels(key, arguments: arguments, nested: nested), verticalRegion: region)
        XCTAssertTrue(element.isEnabled, "I/O control \(key) must be enabled")
        element.tap()
    }

    private func wait(_ description: String, condition: @escaping () -> Bool) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 20), .completed, description)
    }

    private func fixtureUrl() throws -> URL {
        let paths: [String: String] = try item(run, "fixtures")
        return URL(fileURLWithPath: try XCTUnwrap(paths["io"], "Stage the shared I/O fixture in Run.fixtures.io"))
    }

    private func openFixture() throws {
        expectedAttributes = [:]
        orient(.portrait)
        try openXml(Data(contentsOf: fixtureUrl()), name: "io-showcase")
        try tapKey("a11y_simulation_event_step", toolbar: "editor-simulation-toolbar")
        try closeToolbarMenu()
        try assertOutputs(item(contract, "baselineOutputs"))
        try checkpoint("io-fixture-opened")
        attach("Every checkpoint requires the external connectivity and attribute oracle. Native actions passing alone do not qualify issue #44.",
               name: "io-postflight-required")
    }

    private func openXml(_ data: Data, name: String) throws {
        let path = try fixtureUrl().deletingLastPathComponent().appendingPathComponent(name + "-" + UUID().uuidString + ".circ")
        try data.write(to: path, options: .atomic)
        XCUIDevice.shared.system.open(path)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        let expected = try labels("status_file_opened", arguments: ["name": path.lastPathComponent])
        let origins = try labels("import_report_origin", arguments: ["origin": path.lastPathComponent])
        let report = identified("editor-import-report")
        let deadline = Date().addingTimeInterval(20)
        while true {
            // Each case has the same circuit name. Associate a migration report
            // with this unique filename before dismissing it, not a restored report.
            let presented = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                report.exists ? self.matching(origins, within: report).firstMatch.exists : self.matching(expected).firstMatch.exists
            }, object: app)
            let remaining = deadline.timeIntervalSinceNow
            guard remaining > 0,
                  XCTWaiter.wait(for: [presented], timeout: remaining) == .completed else {
                XCTFail("The system-opened I/O document did not expose its matching report or opened-file status")
                throw HarnessFailure.missingControl
            }
            if report.exists {
                guard matching(origins, within: report).firstMatch.exists else { continue }
                screenshot("io-import-report-" + name)
                attach(app.debugDescription, name: "io-import-report-" + name + "-tree")
                guard report.exists && matching(origins, within: report).firstMatch.exists else { continue }
                try tapKey("action_close")
                let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !report.exists }, object: app)
                XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 10), .completed,
                               "Close must dismiss the import report associated with the I/O source file")
                continue
            }
            XCTAssertTrue(matching(expected).firstMatch.exists)
            break
        }
    }

    private func openExplorer(_ query: String) throws {
        try tapKey("a11y_project_tools", toolbar: "editor-project-toolbar")
        try reveal(values("a11y_explorer_title"), verticalRegion: "editor-tool-menu").tap()
        _ = try reveal(values("a11y_explorer_components"), verticalRegion: "a11y-circuit-list")
        let field = identified("a11y-circuit-search")
        _ = try reveal(values("a11y_explorer_search"), verticalRegion: "a11y-circuit-list")
        try replacePresentedText(field, with: query)
        finishEntry()
    }

    private func properties(_ label: String) throws {
        try openExplorer(label)
        try click("a11y_explorer_edit", arguments: ["name": label], region: "a11y-circuit-list")
        XCTAssertTrue(identified("editor-inspector-sheet").waitForExistence(timeout: 10))
    }

    private func closeProperties() throws {
        finishEntry()
        XCTAssertTrue(identified("editor-inspector-sheet").exists)
        let handles = app.buttons.matching(identifier: "editor-inspector-drag-handle")
            .allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
        XCTAssertEqual(handles.count, 1, "Use the presented inspector's identified native drag handle")
        let handle = try XCTUnwrap(handles.first)
        let descriptions = try values("a11y_inspector_handle")
        XCTAssertTrue(descriptions.contains { nativeLabel(handle.label, matches: $0) })
        checkFrame(handle, name: "io-inspector-drag-handle")
        let frame = handle.frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let start = origin.withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: frame.midY - app.frame.minY))
        let end = origin.withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: app.frame.height - 5))
        start.press(forDuration: 0.1, thenDragTo: end)
        wait("closed I/O inspector") { !self.identified("editor-inspector-sheet").exists }
    }

    private func activate(_ label: String) throws {
        try openExplorer(label)
        try click("a11y_explorer_activate", arguments: ["name": label], region: "a11y-circuit-list")
        try tapKey("action_close")
    }

    private func finishEntry() {
        for name in ["Done", "Hide keyboard", "Concluído", "Ocultar teclado"] {
            let button = app.keyboards.buttons[name]
            if button.exists && button.isHittable { button.tap(); return }
        }
    }

    private func keyboardField(_ label: String) throws -> XCUIElement {
        XCTAssertTrue(identified("editor-inspector-sheet").exists)
        func matchingFields() -> [XCUIElement] {
            (app.textFields.allElementsBoundByIndex + app.textViews.allElementsBoundByIndex).filter {
                $0.exists && $0.isHittable && $0.label.hasPrefix(label + " · ")
            }
        }
        wait("presented I/O buffer editor \(label)") { matchingFields().count == 1 }
        let fields = matchingFields()
        XCTAssertEqual(fields.count, 1, "The actual I/O buffer editor must have one canonical label")
        return try XCTUnwrap(fields.first)
    }

    private func enterKeyboard(_ value: String, component: String) throws {
        try properties(component)
        let field = try keyboardField(component)
        try replacePresentedText(field, with: value, identity: .keyboardBuffer(componentLabel: component),
                                 expectedFinalValue: value)
        XCTAssertEqual(field.value as? String, value)
        try closeProperties()
    }

    private func editProperty(_ label: String, name: String, value: String, reject: Bool = false) throws {
        try properties(label)
        try click("inspector_edit", nested: ["name": translations("attribute_label_" + name.lowercased())])
        let labels = Set(try values("property_label"))
        let fields = (app.textFields.allElementsBoundByIndex + app.textViews.allElementsBoundByIndex).filter {
            $0.exists && $0.isHittable && !labels.contains($0.label) && !$0.label.hasPrefix("IO_KEYBOARD · ")
        }
        XCTAssertEqual(fields.count, 1, "The presented property dialog must own exactly one editor")
        try replacePresentedText(XCTUnwrap(fields.first), with: value)
        finishEntry()
        let apply = try reveal(values("action_apply"))
        if reject {
            XCTAssertFalse(apply.isEnabled, "Invalid I/O dimensions must be rejected")
            try tapKey("action_cancel")
        } else {
            XCTAssertTrue(apply.isEnabled)
            apply.tap()
            expectedAttributes[label, default: [:]][name] = value
        }
        try closeProperties()
    }

    private func momentary(component: String, key: String, seconds: Double) throws {
        try properties(component)
        let released = try labels("io_a11y_button", arguments: ["label": key], nested: ["state": translations("io_button_released")])
        let control = try reveal(released)
        checkFrame(control, name: "io-" + component)
        let request: Object = ["holdSeconds": seconds]
        let expectation: Object = ["controlBackgroundArgb": ["held": "#ffb8d7ff", "released": "#ffeeeeee"],
                                   "backgroundRoiInsetFraction": 0.08,
                                   "ignore": "label glyphs and border; review an interior background region"]
        try recordTouchRegion(control, component: component, phase: "before-hold", request: request, expectation: expectation)
        let holdStarted = Date().timeIntervalSince1970
        control.press(forDuration: seconds)
        let holdReturned = Date().timeIntervalSince1970
        let releasedControl = try reveal(released)
        try recordTouchRegion(releasedControl, component: component, phase: "after-release",
                              request: request.merging(["apiStartedUnixSeconds": holdStarted, "apiReturnedUnixSeconds": holdReturned]) { _, new in new },
                              expectation: expectation)
        screenshot("io-released-" + component.lowercased())
        try closeProperties()
    }

    private func joystickVideoExpectation(_ drag: [String: Double]) throws -> Object {
        let x = try XCTUnwrap(drag["x"])
        let y = try XCTUnwrap(drag["y"])
        // IoInputPanel uses a 132dp control around a centered 120dp drawing.
        // The marker's displacement is 0.8 of the drawing radius at raw +/-14.
        let rawX = min(14.0, max(-14.0, floor((x - 0.5) * 28.0 + 0.5)))
        let rawY = min(14.0, max(-14.0, floor((y - 0.5) * 28.0 + 0.5)))
        let radius = 0.5 * 0.8 * 120.0 / 132.0
        return ["redKnobArgb": "#ffff0000", "expectedRawEnd": ["x": rawX, "y": rawY],
                "heldCenterNormalizedToControl": ["x": 0.5 + rawX / 14.0 * radius, "y": 0.5 + rawY / 14.0 * radius],
                "releasedCenterNormalizedToControl": ["x": 0.5, "y": 0.5],
                "knobRadiusCanvasPixels": 10]
    }

    private func recordTouchRegion(_ control: XCUIElement, component: String, phase: String,
                                   request: Object, expectation: Object) throws {
        let frame = control.frame
        let application = app.frame
        XCTAssertTrue(frame.minX.isFinite && frame.minY.isFinite && frame.width.isFinite && frame.height.isFinite)
        XCTAssertGreaterThan(frame.width, 0)
        XCTAssertGreaterThan(frame.height, 0)
        XCTAssertGreaterThan(application.width, 0)
        XCTAssertGreaterThan(application.height, 0)
        let nativeLabel = control.label
        let nativeValue = control.value as? String ?? ""
        let now = Date()
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let record: Object = [
            "recordType": "native-touch-video-region", "component": component, "phase": phase,
            "utc": formatter.string(from: now), "unixSeconds": now.timeIntervalSince1970,
            "systemUptimeSeconds": ProcessInfo.processInfo.systemUptime,
            "nativeLabel": nativeLabel, "nativeValue": nativeValue,
            "controlFramePoints": ["x": frame.minX, "y": frame.minY, "width": frame.width, "height": frame.height],
            "applicationFramePoints": ["x": application.minX, "y": application.minY, "width": application.width, "height": application.height],
            "roiNormalizedToApplication": ["x": (frame.minX - application.minX) / application.width,
                                           "y": (frame.minY - application.minY) / application.height,
                                           "width": frame.width / application.width, "height": frame.height / application.height],
            "requestedGesture": request, "expectedVideoObservation": expectation,
            "coordinateSpace": "XCUI screen points. Apply normalized ROI only when the video frame matches the application frame.",
            "timingMeaning": "Before/after the synchronous XCTest API call, not measured hardware contact times.",
            "qualification": "ROI and timing metadata only; during-touch pixels require a retained recording and separate review."
        ]
        attach(try json(record), name: "io-touch-" + component.lowercased() + "-" + phase)
    }

    private func closeToolbarMenu() throws {
        for tag in ["editor-simulation-toolbar-menu", "editor-command-toolbar-menu"] {
            let menu = identified(tag)
            if menu.exists { try tapKey("action_close"); wait("closed simulation overflow") { !menu.exists } }
        }
    }

    private func stepCharacter() throws {
        let keyboard: Object = try item(contract, "keyboard")
        let count: Int = try item(keyboard, "halfCyclesPerCharacter")
        for _ in 0..<count {
            try tapKey("a11y_step_clock", toolbar: "editor-simulation-toolbar")
            try closeToolbarMenu()
        }
    }

    private func currentCanvasText() -> String? {
        guard identified("editor-canvas").exists, let prefixes = try? values("a11y_canvas").map({ $0.components(separatedBy: "{outputs}")[0].trimmingCharacters(in: .whitespaces) }) else { return nil }
        let frame = identified("editor-canvas").frame
        let summaries = matching(prefixes, prefix: true).allElementsBoundByIndex.filter { $0.exists && $0.frame == frame }
        return summaries.count == 1 ? summaries[0].label : nil
    }

    private func assertOutputs(_ expected: [String: String]) throws {
        let patterns = try expected.map { label, bits in
            try NSRegularExpression(pattern: "(?<![A-Za-z0-9_])" + NSRegularExpression.escapedPattern(for: label) + ": " + NSRegularExpression.escapedPattern(for: bits) + "(?=,|\\.|$)")
        }
        wait("native I/O output probes \(expected)") {
            guard let text = self.currentCanvasText() else { return false }
            let range = NSRange(text.startIndex..<text.endIndex, in: text)
            return patterns.allSatisfy { $0.firstMatch(in: text, range: range) != nil }
        }
        attach(try XCTUnwrap(currentCanvasText()), name: "io-probes")
    }

    private func captureVisual(_ label: String, name: String) throws {
        try openExplorer(label)
        try click("a11y_explorer_select", arguments: ["name": label], region: "a11y-circuit-list")
        try tapKey("action_close")
        try tapKey("a11y_fit_selection", toolbar: "editor-command-toolbar")
        try closeToolbarMenu()
        func candidates() -> [XCUIElement] {
            app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label + ", ")).allElementsBoundByIndex.filter {
                $0.exists && $0.identifier.hasPrefix("canvas-component:") && $0.frame.width > 0 && $0.frame.height > 0
            }
        }
        wait("focused production canvas component \(label)") { candidates().count == 1 }
        let nodes = candidates()
        XCTAssertEqual(nodes.count, 1, "The focused drawing component must expose its production canvas semantics")
        let node = try XCTUnwrap(nodes.first)
        checkFrame(node, name: label)
        attach("label=\(node.label), state=\(node.value as? String ?? ""), frame=\(node.frame)", name: name + "-semantics")
        attach(app.debugDescription, name: name + "-tree")
        screenshot(name)
    }

    @discardableResult private func checkpoint(_ name: String, attributes: [String: [String: String]] = [:]) throws -> String {
        for (label, changes) in attributes {
            expectedAttributes[label, default: [:]].merge(changes) { _, requested in requested }
        }
        exportNumber += 1
        let basename = "\(exportNumber)-\(name)"
        let document = basename + ".circ"
        let xml = try observedProjectXML(attachmentName: document)
        let metadata: Object = ["schemaVersion": 1, "scope": "native-mounted-editor", "document": document,
                                "expectedAttributes": expectedAttributes, "postflightRequired": true]
        let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys]),
                                       uniformTypeIdentifier: "public.json")
        attachment.name = basename + ".io-checkpoint.json"
        attachment.lifetime = .keepAlways
        add(attachment)
        let observed = try IoObservedDocument.parse(xml)
        let types: [String: String] = try item(contract, "componentTypes")
        XCTAssertEqual(observed.components.count, 54)
        XCTAssertGreaterThan(observed.wires, 0, "Reject an obviously disconnected export before the mandatory connectivity postflight")
        for (label, expected) in types {
            XCTAssertEqual(observed.components[label]?["name"], expected, "Preserve \(label)'s actual component type")
        }
        for (label, changes) in expectedAttributes {
            for (name, value) in changes { XCTAssertEqual(observed.components[label]?[name], value, "Persist \(label).\(name)") }
        }
        screenshot(name)
        return xml
    }

    private func json(_ value: Any) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), as: UTF8.self)
    }
}

private final class IoObservedDocument: NSObject, XMLParserDelegate {
    var components: [String: [String: String]] = [:]
    var wires = 0
    var wireEndpoints: [String] = []
    private var path: [String] = []
    private var current: [String: String]?
    private var invalid = false

    static func parse(_ xml: String) throws -> IoObservedDocument {
        let result = IoObservedDocument()
        let parser = XMLParser(data: Data(xml.utf8))
        parser.delegate = result
        XCTAssertTrue(parser.parse(), "The actual UI export must parse as XML")
        XCTAssertFalse(result.invalid, "The actual export must retain unique labels and complete attributes")
        return result
    }

    func parser(_ parser: XMLParser, didStartElement element: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String]) {
        path.append(element)
        if path == ["project", "circuit", "comp"] { current = ["name": attributes["name"] ?? ""] }
        if path == ["project", "circuit", "comp", "a"], let name = attributes["name"] {
            if current?[name] != nil { invalid = true }
            current?[name] = attributes["val"]
        }
        if path == ["project", "circuit", "wire"] {
            wires += 1
            wireEndpoints.append([attributes["from"] ?? "", attributes["to"] ?? ""].sorted().joined(separator: "|"))
        }
    }

    func parser(_ parser: XMLParser, didEndElement element: String, namespaceURI: String?, qualifiedName: String?) {
        if path == ["project", "circuit", "comp"], let value = current {
            if let label = value["label"], components[label] == nil { components[label] = value }
            else { invalid = true }
            current = nil
        }
        path.removeLast()
    }
}
