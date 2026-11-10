# Poke, Text and Menu tools against Evolution (#345, #346, #347)

Mobile `bc0a598fb16800659494ccf8cb9c38a02db7263f` (`main`). The fix is `8c78d614`; `b835433b` adds the iOS canvas test and `bc0a598f` the Text font test. Core JAR from `./gradlew :core:jvmJar` at `bc0a598f`, SHA-256 `b09faadfda5668ec1737834718b31fd6cc634b01d0651e44b4b4e4d0bb95cc09`. Evolution `d58a61e0`, JAR `logisim-evolution-5.1.0dev-all.jar`, SHA-256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`. macOS (Apple silicon), Homebrew OpenJDK 21.0.13, 2026-11-08. The earlier comparison at `a4e57de9` stays in [options-tools-a4e57de9](../options-tools-a4e57de9/README.md).

## JVM oracles (at `bc0a598f`)

```sh
./gradlew :core:jvmJar
scripts/oracle/base-tools/run.sh EVOLUTION_JAR OUT     # exit 0: SAME poke, SAME menu, SAME text-tool
scripts/oracle/annotations/run.sh EVOLUTION_JAR        # PASS
```

`run.sh` now runs the Mobile side as well. `BaseToolsMobileOracle` calls `PinPoke.poke` (what `EditorState.pokePin` applies) and `ComponentMenu` (what the canvas menu shows) on the same fixtures. `TextToolOracle` mirrors the `EditorState` rules that changed: a toolbar Text without a font gets the profile default as an implicit attribute, and clearing an existing Text leaves it alone. Each Evolution process runs under a 60 s SIGKILL watchdog.

| Tool | Result | Log |
| --- | --- | --- |
| Poke (`Pin.PinPoker.handleBitPress`) | SAME. `p1` 0→1→0; tri-state `t1` U→0→1→U; `b4` toggles the poked bit; `h8` hex digits 01, 02, 12, 13; tri-state bus `t3` UU0, UU1, UUU, U0U. | [desktop](logs/oracle/poke-desktop.tsv), [mobile](logs/oracle/poke-mobile.tsv) |
| Menu (`MenuTool.MenuComponent` + `MenuExtender`, `MenuSelection`) | SAME for AND Gate, PLA, Pin, RAM, ROM, Splitter (Ascending disabled on the default distribution), Text, subcircuit `child`, multi-selection and empty canvas. | [desktop](logs/oracle/menu-desktop.tsv), [mobile](logs/oracle/menu-mobile.tsv) |
| Text (`TextTool.editingStopped`) | SAME: all 4 components reopen identically in Evolution. The bounds of Texts without a font now match (`162,128,117x32` and `258,84,85x16`), as does the explicit-font Text. | [log](logs/oracle/text-tool.log) |
| Annotations (Text/Image geometry, rerun after the `AnnotationLibrary` change) | PASS: 9 components. | [log](logs/oracle/annotations.log) |

Decision for #346: clearing an existing Text keeps it with its previous text, as Evolution does. `editingStopped` commits `CircuitMutation.add` of the same component under the remove label. A 2.7.1 project keeps `SansSerif plain 12` as its implicit default, while Evolution projects use `SansSerif bold 16`. Neither writes the font on save.

## Host tests (at `bc0a598f`)

`./gradlew :core:jvmTest --tests '*PinPokeTest' --tests '*ComponentMenuTest' --tests '*TextDefaultFontTest'` and `:composeApp:testAndroidHostTest --tests '*PinPokeEditorTest' --tests '*TextToolTest'` all pass. The fix commit also ran the affected `EditorState`, accessibility, localization, codec and annotation suites: 79 host tests and 125 core tests, 0 failures.

## Canvas on iOS and Android (tree of `6e94a409`, the pre-rebase twin of `b835433b`)

- **iOS**: `./gradlew :composeApp:iosSimulatorArm64Test --tests '*CanvasPokeAndMenuTest' -PiosTestDevice=<own iPhone 17e, iOS 27.2 simulator, deleted afterwards>`, 3/3 pass ([xml](logs/ios/TEST-iosSimulatorArm64Test.dev.logisim.mobile.CanvasPokeAndMenuTest.xml)). The test drives the real `CircuitCanvas`. A tap on the right digit of a classic 8-bit hex pin pokes bit 0 and a tap on the left digit pokes bit 4. A long press on a RAM shows exactly Delete, Show Attributes, Edit/Clear/Load/Save; Clear reaches the handler. A long press on empty canvas opens no menu.
- **Android**: e2e APK (`./gradlew -PandroidTestBuildType=e2e :androidApp:assembleE2e :androidApp:assembleE2eAndroidTest`, SHA-256 in [device.txt](logs/android/device.txt)) on its own AVD (Pixel 7, Android 15 `google_apis` arm64, `emulator-5584`, deleted afterwards). [fixture.circ](logs/android/fixture.circ) is opened through the fixture VIEW intent and driven with `adb input` by [drive.py](logs/android/drive.py). Screen positions come from the RAM's accessibility node (scale 1.95):
  - four taps on `h8` (digit 0, 0, 1, 0) give the status `h8 = 00000001`, `00000010`, `00010010` and `00010011`, Evolution's sequence ([final screenshot](logs/android/tap3.png));
  - a long press on the RAM shows Delete, Show Attributes, Edit Contents…, Clear Contents, Load Image…, Save Image… ([dump](logs/android/menu-ram.xml));
  - on the pin it shows Rotate Left, Rotate Right, Delete, Show Attributes ([dump](logs/android/menu-pin.xml)), and Rotate Right turns the pin south ([screenshot](logs/android/rotated.png));
  - on empty canvas it opens no menu ([dump](logs/android/menu-empty.xml)).

Between `6e94a409` and `bc0a598f`, `CircuitCanvas.kt`, `EditorState.kt`, `CanvasInteraction.kt`, `PinPoke.kt` and `ComponentMenu.kt` are unchanged. `App.kt` only gained upstream `7266f926` (#239, the header layout at large font scales), which does not touch the poke or menu wiring.

## Remaining Mobile differences

- Menu actions that open dialogs use the Mobile equivalent. Edit Contents opens the Memory editor tool. Edit PLA Program and Show Attributes open the attribute inspector, which holds the PLA table. Load/Save Image use the platform file picker in the "v3.0 hex words addressed" format.
- On a wire, Evolution's menu is Delete and Show Attributes. Mobile shows Delete and keeps "Split wire here" for touch, and it has no attribute sheet for wires. The menu tip header is also Mobile-only, because touch has no hover tooltip.
- Evolution's other `MenuExtender`s (SoC CPUs, bus, PIO, VGA, Programmable Generator, PLA ROM, Image) are not in this comparison.
