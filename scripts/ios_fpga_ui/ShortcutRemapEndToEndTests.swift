import XCTest

/// Issue #189 on iPadOS. ⌘G is the text system's Find Next on iPadOS, so the free chord ⌘J is used.
/// remap Find Action with hardware-keyboard chords, persist it, detect a conflict and restore defaults.
final class ShortcutRemapEndToEndTests: NativeUIHarness {
    func testRemapPersistConflictAndRestoreWithHardwareKeys() throws {
        // Start from the defaults even if an earlier run left an override.
        try openShortcuts()
        let reset = identified("preferences-shortcuts-restore")
        for _ in 0..<12 where !(reset.exists && reset.isHittable) { app.swipeUp(velocity: .slow) }
        for _ in 0..<4 where reset.isEnabled { sleep(1); reset.tap(); sleep(1) }
        XCTAssertFalse(reset.isEnabled, "No override remains before the scenario")
        attach(rowLabel(), name: "initial-row")
        app.terminate(); app.launch(); try waitForEditorStartup()

        XCTAssertTrue(try probe("1-default", "a", [.command, .shift]), "Default ⌘⇧A opens Find Action")
        try openShortcuts()
        XCTAssertTrue(rowLabel().contains("A"), rowLabel())
        let apply = matching(try values("action_apply")).firstMatch
        for _ in 0..<3 where !apply.exists {
            sleep(1)
            identified("preferences-shortcut-FIND_ACTION").tap()
            _ = apply.waitForExistence(timeout: 3)
        }
        XCTAssertTrue(apply.exists, "The remap dialog must open")
        let field = try shortcutField()
        attach(app.debugDescription, name: "shortcut-dialog-tree")
        field.tap(); sleep(1)
        field.typeKey("s", modifierFlags: .command); sleep(1)
        let conflict = matching(try values("preferences_shortcut_conflict").map { $0.components(separatedBy: "{")[0] }, prefix: true).firstMatch
        XCTAssertTrue(conflict.waitForExistence(timeout: 5), "⌘S must conflict with Save")
        screenshot("2-conflict")
        attach(conflict.label, name: "conflict-label")
        field.typeKey("j", modifierFlags: .command); sleep(1)
        XCTAssertFalse(conflict.exists, "⌘J is free")
        XCTAssertEqual(field.value as? String, "J")
        try tapKey("action_apply")
        sleep(1)
        attach(rowLabel(), name: "applied-row")
        XCTAssertTrue(rowLabel().contains("J"), rowLabel())
        screenshot("3-applied")

        app.terminate(); app.launch(); try waitForEditorStartup()
        XCTAssertTrue(try probe("4-remapped", "j", .command), "⌘J opens Find Action after relaunch")
        XCTAssertFalse(try probe("5-old", "a", [.command, .shift]), "The old chord no longer opens Find Action")
        try openShortcuts()
        attach(rowLabel(), name: "relaunched-row")
        XCTAssertTrue(rowLabel().contains("J"), "The remap persisted: \(rowLabel())")
        let restore = identified("preferences-shortcuts-restore")
        for _ in 0..<12 where !(restore.exists && restore.isHittable) { app.swipeUp(velocity: .slow) }
        sleep(1)
        XCTAssertTrue(restore.isEnabled, "Restore is enabled while an override exists")
        for _ in 0..<4 where restore.isEnabled { sleep(1); restore.tap(); sleep(1) }
        XCTAssertFalse(restore.isEnabled, "Restore disables itself once no override remains")
        screenshot("6-restore-tapped")
        app.terminate(); app.launch(); try waitForEditorStartup()
        try openShortcuts()
        attach(rowLabel(), name: "restored-row")
        XCTAssertFalse(rowLabel().contains("J"), rowLabel())
        screenshot("6-restored")

        app.terminate(); app.launch(); try waitForEditorStartup()
        XCTAssertTrue(try probe("7-restored", "a", [.command, .shift]), "Defaults restored")
        XCTAssertFalse(try probe("8-restored-j", "j", .command))
    }

    /// Issue #314: Evolution's remaining hotkeys with hardware-keyboard events on the iPad.
    func testEvolutionHotkeysWithHardwareKeys() throws {
        try resetShortcutsToDefaults()
        app.terminate(); app.launch(); try waitForEditorStartup()

        // Shift tapped twice opens Find Action (SEARCH_DOUBLE_SHIFT, on by default). typeKey(.shift)
        // without the Shift flag reaches the app as nothing, and perform(withKeyModifiers:) leaves
        // about 10 s between taps; with the flag each call is one clean Shift press and release,
        // about 300 ms apart (#320). XCUITest paces the calls, so a pair can miss Evolution's
        // 300 ms window; retry the pair and record each attempt.
        let commands = matching(try values("commands_heading")).firstMatch
        var attempts: [String] = []
        var doubleShift = false
        for _ in 0..<3 where !doubleShift {
            try canvas().tap()
            sleep(1)
            let start = Date()
            app.typeKey(XCUIKeyboardKey.shift.rawValue, modifierFlags: .shift)
            let between = Date()
            app.typeKey(XCUIKeyboardKey.shift.rawValue, modifierFlags: .shift)
            doubleShift = commands.waitForExistence(timeout: 4)
            attempts.append("first=\(Int(between.timeIntervalSince(start) * 1000))ms opened=\(doubleShift)")
        }
        screenshot("314-1-double-shift")
        attach("double-shift " + attempts.joined(separator: "; "), name: "314-double-shift")
        XCTAssertTrue(doubleShift, "Shift, Shift opens Find Action: \(attempts)")
        dismissSheet(commands)

        // ⌘⇧E and ⌘P open the drawing export panel, which carries Print PDF.
        for (name, key, flags) in [("314-2-export", "e", XCUIElement.KeyModifierFlags([.command, .shift])),
                                   ("314-3-print", "p", XCUIElement.KeyModifierFlags.command)] {
            try canvas().tap()
            app.typeKey(key, modifierFlags: flags)
            let heading = matching(try values("visual_export_heading")).firstMatch
            let opened = heading.waitForExistence(timeout: 5)
            screenshot(name)
            attach("\(name) opened=\(opened)", name: name)
            XCTAssertTrue(opened, "\(name) opens the export panel")
            dismissSheet(heading)
        }

        // ⌘n picks the nth selectable tool of Evolution's default.templ toolbar (#324): Poke, Edit, Wiring,
        // Text, Image, input Pin, output Pin, NOT, AND, OR (⌘0), then XOR, NAND, NOR and D Flip-Flop unassigned.
        // A new project carries that toolbar; a file without <toolbar> has no tools (#344).
        try canvas().tap()
        app.typeKey("n", modifierFlags: .command)
        sleep(3)
        let placing = try values("status_place").map { $0.components(separatedBy: "{")[0] }
        var picked: [String] = []
        for (key, name) in [("5", "Image"), ("8", "NOT"), ("9", "AND")] {
            try canvas().tap()
            app.typeKey(key, modifierFlags: .command)
            let selected = matching(placing.map { $0 + name }, prefix: true).firstMatch.waitForExistence(timeout: 4)
            picked.append("⌘\(key)=\(name):\(selected)")
            XCTAssertTrue(selected, "⌘\(key) selects \(name)")
        }
        attach(picked.joined(separator: " "), name: "314-tool-numbers")
        screenshot("314-4-tool-9")
        // W, =, R change the AND tool before placing it.
        for key in ["w", "=", "r"] { app.typeKey(key, modifierFlags: []); sleep(1) }
        try canvas().coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.25)).tap()
        sleep(2)
        screenshot("314-5-placed")
        // The inspector exposes each row as a button labelled "<action> <name>, <name>, <value>".
        func property(_ name: String) -> String? {
            app.buttons.matching(NSPredicate(format: "label CONTAINS %@", ", \(name), ")).allElementsBoundByIndex
                .first?.label.components(separatedBy: ", ").last
        }
        func properties() -> String { ["Facing", "Gate size", "Number of inputs"].map { "\($0)=\(property($0) ?? "?")" }.joined(separator: " ") }
        attach(properties(), name: "314-placed-inspector")
        XCTAssertEqual(property("Facing"), "South", "R rotates the tool before placing it")
        XCTAssertEqual(property("Gate size"), "70", "W makes the gate wide")
        XCTAssertEqual(property("Number of inputs"), "3", "= adds an input")

        // M and − apply to the selected gate.
        app.typeKey("m", modifierFlags: [])
        app.typeKey("-", modifierFlags: [])
        sleep(1)
        attach(properties(), name: "314-edited-inspector")
        XCTAssertEqual(property("Gate size"), "50", "M edits the selected gate")
        XCTAssertEqual(property("Number of inputs"), "2", "− edits the selected gate")
        screenshot("314-6-edited")

        // Remap the unassigned tool 11 with a conflict check, apply ⌘J, use it, then restore.
        try openShortcuts(row: "TOOL_SELECT_11")
        let apply = matching(try values("action_apply")).firstMatch
        for _ in 0..<3 where !apply.exists {
            sleep(1)
            identified("preferences-shortcut-TOOL_SELECT_11").tap()
            _ = apply.waitForExistence(timeout: 3)
        }
        let field = try shortcutField()
        field.tap(); sleep(1)
        field.typeKey("s", modifierFlags: .command); sleep(1)
        let conflict = matching(try values("preferences_shortcut_conflict").map { $0.components(separatedBy: "{")[0] }, prefix: true).firstMatch
        XCTAssertTrue(conflict.waitForExistence(timeout: 5), "⌘S conflicts with Save")
        screenshot("314-7-conflict")
        field.typeKey("j", modifierFlags: .command); sleep(1)
        XCTAssertFalse(conflict.exists)
        try tapKey("action_apply")
        sleep(1)
        attach(rowLabel("TOOL_SELECT_11"), name: "314-applied-row")
        XCTAssertTrue(rowLabel("TOOL_SELECT_11").contains("J"), rowLabel("TOOL_SELECT_11"))
        app.terminate(); app.launch(); try waitForEditorStartup()
        try canvas().tap()
        app.typeKey("j", modifierFlags: .command)
        let xor = placing.map { $0 + "XOR" }
        let selected = matching(xor, prefix: true).firstMatch.waitForExistence(timeout: 4)
        screenshot("314-8-remapped-tool")
        attach("⌘J selects XOR=\(selected)", name: "314-remapped-tool")
        XCTAssertTrue(selected, "⌘J selects toolbar tool 11 (XOR) after relaunch")
        try resetShortcutsToDefaults()
        app.terminate(); app.launch(); try waitForEditorStartup()
        try canvas().tap()
        app.typeKey("j", modifierFlags: .command)
        XCTAssertFalse(matching(xor, prefix: true).firstMatch.waitForExistence(timeout: 3), "Defaults leave tool 11 unassigned")
        screenshot("314-9-restored")

        // ⌘⇧W leaves the document for the workspace project list.
        try canvas().tap()
        app.typeKey("w", modifierFlags: [.command, .shift])
        let projects = matching(try values("workspace_close_session")).firstMatch
        let closed = projects.waitForExistence(timeout: 5)
        screenshot("314-10-close-window")
        attach("close opened projects=\(closed)", name: "314-close")
        XCTAssertTrue(closed, "⌘⇧W shows the project list")
    }

    private func dismissSheet(_ marker: XCUIElement) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.04, dy: 0.45)).tap()
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !marker.exists }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 10), .completed, "Close the sheet before the next step")
    }

    private func resetShortcutsToDefaults() throws {
        try openShortcuts()
        let reset = identified("preferences-shortcuts-restore")
        for _ in 0..<40 where !(reset.exists && reset.isHittable) { app.swipeUp(velocity: .slow) }
        for _ in 0..<4 where reset.isEnabled { sleep(1); reset.tap(); sleep(1) }
        XCTAssertFalse(reset.isEnabled, "No override remains")
    }

    private func probe(_ name: String, _ key: String, _ flags: XCUIElement.KeyModifierFlags) throws -> Bool {
        try canvas().tap()
        app.typeKey(key, modifierFlags: flags)
        let opened = matching(try values("commands_heading")).firstMatch.waitForExistence(timeout: 4)
        screenshot(name)
        attach("\(name): opened=\(opened)", name: "probe")
        if opened {
            // The search sheet ignores Escape; tap the scrim beside it, as a user would.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.04, dy: 0.45)).tap()
            let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                !((try? self.matching(self.values("commands_heading")).firstMatch.exists) ?? false)
            }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 10), .completed, "Close Find Action before the next step")
        }
        return opened
    }

    private func openShortcuts(row name: String = "FIND_ACTION") throws {
        try tapKey("menu_preferences", toolbar: "editor-project-toolbar")
        let toggle = identified("preferences-shortcuts")
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        for _ in 0..<14 where !(toggle.exists && toggle.isHittable) { app.swipeUp(velocity: .slow) }
        let row = identified("preferences-shortcut-\(name)")
        for _ in 0..<3 where !identified("preferences-shortcut-FIND_ACTION").exists {
            sleep(1)
            toggle.tap()
            _ = row.waitForExistence(timeout: 3)
        }
        for _ in 0..<40 where !(row.exists && row.isHittable) { app.swipeUp(velocity: .slow) }
        XCTAssertTrue(row.isHittable, "\(name) row must be reachable")
    }

    private func shortcutField() throws -> XCUIElement {
        let tagged = identified("preferences-shortcut-key")
        if tagged.waitForExistence(timeout: 3) {
            let inner = (tagged.textFields.allElementsBoundByIndex + tagged.textViews.allElementsBoundByIndex).first
            return inner ?? tagged
        }
        let names = Set(try values("preferences_shortcut_letter"))
        let field = (app.textFields.allElementsBoundByIndex + app.textViews.allElementsBoundByIndex)
            .first { candidate in names.contains { nativeLabel(candidate.label, matches: $0) } }
        if let field { return field }
        // Compose exposes the dialog field as a text view whose value is the current key.
        let any = app.textViews.allElementsBoundByIndex + app.textFields.allElementsBoundByIndex
        return try XCTUnwrap(any.first { $0.exists && $0.isHittable }, "Missing shortcut key field")
    }

    private func rowLabel(_ name: String = "FIND_ACTION") -> String {
        let row = identified("preferences-shortcut-\(name)")
        return ([row.label] + row.staticTexts.allElementsBoundByIndex.map(\.label)).joined(separator: " | ")
    }
}
