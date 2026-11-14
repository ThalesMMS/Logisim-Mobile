# iPad scenarios: FPGA Clock tick (#307) and shortcuts (#189, #314)

`scripts/run-ios-fpga-tick.py` runs XCUITest scenarios on an explicitly reserved, booted iPad Simulator. They use only production controls.

- `FpgaTickEndToEndTests/testTickFrequencyIsShownSavedAndRestored`:
  1. Imports `fpga-tick.circ` through the project XML dialog and selects BASYS3 in Project → FPGA.
  2. Checks that the panel lists no Clock port and starts from the saved 10 MHz.
  3. Checks that the saved map is loaded when the panel opens (no button tap), changes the tick to 12.5 MHz and saves the map in the circuit. The project XML must then contain `downloadFrequency` `1.25E7` and no Clock entry.
  4. Relaunches the app, checks the restored frequency and exports the FPGA project through the system save picker.
- `FpgaTickEndToEndTests/testMapsThatBindTheClockKeepThePhysicalInput`: a map that binds the Clock to W5 shows the notice and keeps `p_source_clock`.
- `ShortcutRemapEndToEndTests/testRemapPersistConflictAndRestoreWithHardwareKeys` sends hardware-keyboard chords with `typeKey`:
  1. Checks that ⌘⇧A opens Find Action.
  2. Opens the remap dialog. ⌘S shows the conflict with Save, and ⌘J is applied.
  3. After a relaunch, ⌘J opens Find Action and the old chord does not.
  4. Restores the defaults and checks the default chord again.

  ⌘G is not used because the iPadOS text system reserves it for Find Next.
- `ShortcutRemapEndToEndTests/testEvolutionHotkeysWithHardwareKeys` covers the Evolution hotkeys of #314:
  1. ⌘⇧E and ⌘P open the drawing export panel, which holds Print PDF.
  2. ⌘n selects the nth selectable tool of Evolution's default toolbar (`default.templ`, #324): ⌘5 Image, ⌘8 NOT and ⌘9 AND. W, = and R make the AND tool wide, add an input and rotate it before placing; M and − then edit the placed gate.
  3. Toolbar tool 11 (XOR) starts unassigned. ⌘S shows the conflict with Save, ⌘J is applied and selects XOR after a relaunch, and restoring the defaults unassigns it again.
  4. ⌘⇧W returns to the workspace project list.

  5. Shift tapped twice opens Find Action. The test sends each tap with `typeKey(XCUIKeyboardKey.shift.rawValue, modifierFlags: .shift)`, which reaches Compose as one Shift press and release. `typeKey` without the Shift flag delivers no key event, and `XCUIElement.perform(withKeyModifiers: .shift)` leaves about 10 s between taps (#320). The pair is about 300 ms apart, close to Evolution's window, so the test makes up to three attempts and records each one.

```sh
python3 scripts/run-ios-fpga-tick.py --simulator UUID --baseline-sha SHA \
    --app iosApp/Build/Products/Debug-iphonesimulator/LogisimMobile.app --output /tmp/new-run
```

Run on a freshly erased Simulator, or run the FPGA scenarios before the shortcut scenario. Hardware-key events can leave the Simulator showing the floating numeric keypad, which hides the tick field from the text-replacement helper.

The exported ZIP stays in the Simulator's Files storage. Compare its SHA-256 with `--test-fpga fpga-tick.circ main BASYS3.xml 12500000 HDLONLY` from the same revision.
