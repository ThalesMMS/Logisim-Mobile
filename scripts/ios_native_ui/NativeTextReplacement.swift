import Foundation
import XCTest
import UIKit

public enum NativeTextReplacementIdentity {
    case keyboardBuffer(componentLabel: String)

    fileprivate func matches(_ label: String) -> Bool {
        switch self {
        case .keyboardBuffer(let componentLabel):
            guard !componentLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
            let prefix = componentLabel + " · "
            guard label.hasPrefix(prefix) else { return false }
            let counts = label.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: false)
            guard counts.count == 2,
                  counts.allSatisfy({ !$0.isEmpty && $0.allSatisfy({ $0.isASCII && $0.isNumber }) }),
                  let count = Int(counts[0]), let capacity = Int(counts[1]),
                  (1...256).contains(capacity), (0...capacity).contains(count) else { return false }
            return true
        }
    }
}

private func nativeTextReplacementLabelMatches(_ label: String, original: String,
                                               identity: NativeTextReplacementIdentity?) -> Bool {
    // Compose on iOS appends a field's supporting or error text to its label after a comma, so emptying a validated
    // field ("Scale, A factor: 1 is 100%, 2 is 200%." -> "Scale, Enter a scale greater than zero.") keeps the
    // same caption before that comma.
    guard let identity else {
        func caption(_ text: String) -> String { text.components(separatedBy: ", ").first ?? text }
        return label == original || (!caption(original).isEmpty && caption(label) == caption(original))
    }
    return !label.isEmpty && identity.matches(label)
}

extension NativeUIHarness {
    /// Replaces a presented nonsecure field through its public editing menu.
    /// A native selection is accepted only when Delete empties the complete field.
    public func replacePresentedText(_ field: XCUIElement, with text: String) throws {
        try replacePresentedText(field, with: text, keyboardIdentity: nil, expectedFinalValue: text, fieldScope: nil)
    }

    /// The production keyboard buffer includes its changing count in its label.
    /// The caller must identify that component and state the observed final value.
    public func replacePresentedText(_ field: XCUIElement, with text: String,
                                     identity: NativeTextReplacementIdentity, expectedFinalValue: String, fieldScope: XCUIElement? = nil) throws {
        try replacePresentedText(field, with: text, keyboardIdentity: identity, expectedFinalValue: expectedFinalValue, fieldScope: fieldScope)
    }

    private func replacePresentedText(_ field: XCUIElement, with text: String,
                                      keyboardIdentity: NativeTextReplacementIdentity?, expectedFinalValue: String, fieldScope: XCUIElement?) throws {
        guard field.waitForExistence(timeout: 5),
              [.textView, .textField].contains(field.elementType),
              nativeTextControlIsVisible(field), field.isEnabled else {
            try failNativeTextReplacement("The requested editable field must be presented and enabled.",
                                          stage: "field-not-presented", field: field)
        }
        let label = field.label
        let identifier = field.identifier
        let role = field.elementType
        func isSameField() -> Bool {
            guard field.exists && field.elementType == role && field.identifier == identifier else { return false }
            // Compose omits the label of a field whose caption is scrolled out of its container,
            // so a label that comes or goes is not a new field when the identifier still matches.
            let labelOnlyHidden = keyboardIdentity == nil && !identifier.isEmpty && (label.isEmpty || field.label.isEmpty)
            guard labelOnlyHidden || nativeTextReplacementLabelMatches(field.label, original: label, identity: keyboardIdentity) else { return false }
            guard let keyboardIdentity else { return true }
            guard field.isEnabled && nativeTextControlIsVisible(field) else { return false }
            let root: XCUIElement = fieldScope ?? app
            let matches = (root.textFields.allElementsBoundByIndex + root.textViews.allElementsBoundByIndex).filter {
                $0.elementType == role && $0.identifier == identifier && $0.isEnabled &&
                    nativeTextControlIsVisible($0) && keyboardIdentity.matches($0.label)
            }
            return matches.count == 1
        }
        guard isSameField() else {
            try failNativeTextReplacement("The presented keyboard buffer must have one matching component label, role and identifier.",
                                          stage: "field-identity-invalid", field: field)
        }
        let initialValue = field.value
        guard initialValue == nil || initialValue is String else {
            try failNativeTextReplacement("The editable field must expose a textual value or no value.",
                                          stage: "unsupported-field-value", field: field)
        }
        let initiallyEmpty = nativeTextValueIsEmpty(initialValue, placeholder: field.placeholderValue)
        field.tap()

        if !initiallyEmpty {
            guard isSameField() else {
                try failNativeTextReplacement("The field query changed identity after the initial tap.",
                                              stage: "field-identity-changed", field: field)
            }
            let selectAllLabels = ["Select All", "Selecionar Tudo", "Selecionar tudo"]
            let cutLabels = ["Cut", "Cortar", "Recortar"]
            let copyLabels = ["Copy", "Copiar"]
            func observedMenuControls() -> [XCUIElement] {
                let menuItems = app.menuItems.allElementsBoundByIndex.filter { nativeTextControlGeometryIsVisible($0) }
                if !menuItems.isEmpty { return menuItems.filter { $0.isEnabled && $0.isHittable } }
                // Older editing menus expose Buttons. Do not mix those with the
                // keyboard's Copy button when the observed menu uses MenuItems.
                let predicate = NSPredicate(format: "label IN %@", selectAllLabels + cutLabels + copyLabels)
                return app.buttons.matching(predicate).allElementsBoundByIndex.filter {
                    nativeTextControlIsVisible($0) && $0.isEnabled
                }
            }
            func menuAppeared(timeout: TimeInterval) -> Bool {
                let appeared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                    let controls = observedMenuControls()
                    return controls.contains { selectAllLabels.contains($0.label) } ||
                        (controls.contains { cutLabels.contains($0.label) } && controls.contains { copyLabels.contains($0.label) })
                }, object: app)
                return XCTWaiter.wait(for: [appeared], timeout: timeout) == .completed
            }
            // A long press on glyphs places the caret and leaves the menu closed; past the text it opens
            // the menu. Same on iPhone iOS 27.0 and 27.2 in the app (#362). Press there first, and keep
            // the centre and the lower edge for a line that reaches the end of the field.
            let frame = field.frame
            let inputFrames = softwareInputFrames().values
            let longPress: (XCUICoordinate) -> Void = { $0.press(forDuration: 1) }
            // A multi-line landscape field missed all three presses once (#364); a double tap selects a word and opens the same menu.
            let gestures: [(CGVector, (XCUICoordinate) -> Void)] = [
                (CGVector(dx: 0.97, dy: 0.6), longPress), (CGVector(dx: 0.5, dy: 0.5), longPress),
                (CGVector(dx: 0.97, dy: 0.88), longPress), (CGVector(dx: 0.2, dy: 0.5), { $0.doubleTap() })
            ]
            var menuPresented = false
            var attempts: [String] = []
            for (offset, gesture) in gestures {
                let point = CGPoint(x: frame.minX + frame.width * offset.dx, y: frame.minY + frame.height * offset.dy)
                guard app.frame.contains(point), !inputFrames.contains(where: { $0.contains(point) }) else {
                    attempts.append("skipped \(offset): off screen or under the keyboard")
                    continue
                }
                gesture(field.coordinate(withNormalizedOffset: offset))
                if menuAppeared(timeout: 4) { menuPresented = true; break }
                attempts.append("no menu after the gesture at \(offset)")
            }
            if !attempts.isEmpty { attach(attempts.joined(separator: "\n"), name: "native-text-replacement-menu-attempts") }
            guard menuPresented else {
                try failNativeTextReplacement("The native editing menu must expose Select All or an enabled Cut and Copy pair.",
                                              stage: "selection-menu-missing", field: field)
            }
            let menu = observedMenuControls()
            let controls = menu.filter { selectAllLabels.contains($0.label) }
            guard controls.count <= 1 else {
                try failNativeTextReplacement("The native editing menu exposed \(controls.count) Select All actions; exactly one is required.",
                                              stage: "select-all-ambiguous", field: field)
            }
            if let selectAll = controls.first {
                selectAll.tap()
            } else {
                let cut = menu.filter { cutLabels.contains($0.label) }
                let copy = menu.filter { copyLabels.contains($0.label) }
                guard cut.count == 1 && copy.count == 1 else {
                    try failNativeTextReplacement("The observed selection menu must expose one Cut and one Copy action.",
                                                  stage: "selection-menu-ambiguous", field: field)
                }
                // A short value can already be fully selected after the press.
                // The clearing postcondition below proves whether it was complete.
                screenshot("native-text-replacement-existing-selection")
                attach(app.debugDescription, name: "native-text-replacement-existing-selection-menu")
            }
            guard isSameField() else {
                try failNativeTextReplacement("The field query changed identity while selecting its contents.",
                                              stage: "field-identity-changed", field: field)
            }
            field.typeText(XCUIKeyboardKey.delete.rawValue)
        }

        let cleared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            isSameField() && self.nativeTextValueIsEmpty(field.value, placeholder: field.placeholderValue)
        }, object: field)
        guard XCTWaiter.wait(for: [cleared], timeout: 5) == .completed else {
            try failNativeTextReplacement("The complete field must be empty before entering replacement text.",
                                          stage: "field-not-cleared", field: field)
        }
        if !text.isEmpty { field.typeText(text) }
        let replaced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            // The keyboard can cover the field in landscape, so only its existence is required here.
            guard field.exists else { return false }
            let actual = field.value
            return expectedFinalValue.isEmpty ? self.nativeTextValueIsEmpty(actual, placeholder: field.placeholderValue) : (actual as? String) == expectedFinalValue
        }, object: field)
        // A long multi-line entry reaches the field's value well after the last key.
        guard XCTWaiter.wait(for: [replaced], timeout: 20) == .completed else {
            attach("typed=\(text)\nexpected=\(expectedFinalValue)\nactual=\(String(describing: field.value))", name: "native-text-replacement-values")
            try failNativeTextReplacement("The field did not contain the exact expected replacement value.",
                                          stage: "final-value-mismatch", field: field)
        }
    }

    private func nativeTextValueIsEmpty(_ rawValue: Any?, placeholder: String?) -> Bool {
        guard let rawValue else { return true }
        guard let value = rawValue as? String else { return false }
        return value.isEmpty || (placeholder != nil && value == placeholder)
    }

    private func nativeTextControlIsVisible(_ control: XCUIElement) -> Bool {
        nativeTextControlGeometryIsVisible(control) && control.isHittable
    }

    private func nativeTextControlGeometryIsVisible(_ control: XCUIElement) -> Bool {
        guard control.exists else { return false }
        let frame = control.frame
        guard frame.minX.isFinite, frame.minY.isFinite, frame.width.isFinite, frame.height.isFinite,
              frame.width > 0, frame.height > 0,
              app.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) else { return false }
        return true
    }

    private func failNativeTextReplacement(_ message: String, stage: String, field: XCUIElement) throws -> Never {
        screenshot("native-text-replacement-\(stage)")
        attach(field.debugDescription, name: "native-text-replacement-\(stage)-field")
        attach(app.debugDescription, name: "native-text-replacement-\(stage)-tree")
        XCTFail(message)
        throw NativeTextReplacementFailure.failed(message)
    }
}

private enum NativeTextReplacementFailure: Error {
    case failed(String)
}
